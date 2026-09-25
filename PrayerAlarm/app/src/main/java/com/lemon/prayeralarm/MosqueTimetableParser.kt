package com.lemon.prayeralarm

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Reads a mosque's published timetable into times the app can schedule against.
 *
 * Published timetables are made by hand and it shows. The one this was built against writes
 * Dhuhr as "12:38 AM", afternoon times as "13:05 PM" and "2:41PM", and switches daylight saving
 * on the wrong days: a fixed yearly template can only ever match one year's clock changes, and
 * this one matches none. Taken literally, each of those is an alarm at the wrong hour.
 *
 * So nothing is taken literally. Times are parsed leniently and folded onto the half of the day
 * their prayer belongs to. Each row's daylight saving is worked out by comparing its Dhuhr with
 * the app's own calculation for that date, then stripped, so every value is stored in standard
 * time; when a date is looked up, the phone's real zone rules for that date put it back.
 * Anything still implausible is dropped, and the app falls back to its own calculation for that
 * prayer on that day.
 *
 * Pure Kotlin with no Android dependencies, so all of it runs under plain JUnit.
 */
object MosqueTimetableParser {

    /**
     * A timetable column, with the range of standard-time minutes after midnight it may hold.
     *
     * The ranges are deliberately wide. They exist to catch a time filed in the wrong half of
     * the day, not to second-guess the mosque, and they have to hold at any latitude. Isha may
     * run past midnight, so its range goes beyond 1440.
     */
    enum class Column(val prayer: Prayer, val isIqamah: Boolean, val window: IntRange) {
        FAJR(Prayer.FAJR, false, 0..600),
        FAJR_IQAMAH(Prayer.FAJR, true, 0..660),
        SUNRISE(Prayer.SUNRISE, false, 120..660),
        DHUHR(Prayer.DHUHR, false, 600..900),
        DHUHR_IQAMAH(Prayer.DHUHR, true, 600..960),
        ASR(Prayer.ASR, false, 720..1140),
        ASR_IQAMAH(Prayer.ASR, true, 720..1200),
        MAGHRIB(Prayer.MAGHRIB, false, 840..1440),
        MAGHRIB_IQAMAH(Prayer.MAGHRIB, true, 840..1470),
        ISHA(Prayer.ISHA, false, 900..1620),
        ISHA_IQAMAH(Prayer.ISHA, true, 900..1650);

        companion object {
            fun of(prayer: Prayer, iqamah: Boolean): Column? =
                values().firstOrNull { it.prayer == prayer && it.isIqamah == iqamah }
        }
    }

    /** One calendar day, keyed by month and day so a yearly template serves every year. */
    data class Day(val month: Int, val day: Int, val minutes: Map<Column, Int>)

    class Result(
        val days: List<Day>,
        /** Cells whose text was malformed or filed in the wrong half of the day, then repaired. */
        val corrected: Int,
        /** Cells that could not be read or failed a sanity check, so fall back to calculation. */
        val rejected: Int,
        /** Rows the file had shifted for daylight saving, now back in standard time. */
        val daylightRows: Int,
        val error: String? = null
    )

    /** How far an iqamah may sit after its adhan before it is treated as a misfiled value. */
    private const val MAX_IQAMAH_GAP = 180

    private val ADHAN_ORDER = listOf(
        Column.FAJR, Column.SUNRISE, Column.DHUHR, Column.ASR, Column.MAGHRIB, Column.ISHA
    )

    private val TIME = Regex("""^(\d{1,2})\s*[:h.]\s*(\d{2})\s*(?:([AaPp])\.?\s*[Mm]\.?)?$""")
    private val CANONICAL = Regex("""^\d{1,2}:\d{2}(?: [AaPp][Mm])?$""")
    private val DATE = Regex("""^\s*(\d{1,4})[/\-.](\d{1,2})(?:[/\-.](\d{1,4}))?""")

    /**
     * @param standardDhuhr the calculated Dhuhr, in standard-time minutes after midnight, for a
     *   month and day. It is what reveals whether a row has daylight saving baked in: Dhuhr moves
     *   a minute or so a day, so a row an hour away from it has been shifted for the clocks.
     */
    fun parse(text: String, standardDhuhr: (month: Int, day: Int) -> Int?): Result {
        val lines = text.removePrefix("﻿").lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return failure("The file is empty.")

        val delimiter = if (lines[0].count { it == ';' } > lines[0].count { it == ',' }) ';' else ','
        val header = split(lines[0], delimiter)
        val columns = header.map(::columnFor)
        if (columns.none { it != null }) {
            return failure("The first row has no prayer columns such as Fajr, Dhuhr or Asr.")
        }
        val dateIndex = header
            .indexOfFirst { it.lowercase().filter(Char::isLetter) == "date" }
            .takeIf { it >= 0 } ?: 0

        // Every row is read before any date is interpreted, because whether "03/04" means March
        // or April depends on the rest of the file.
        val rows = lines.drop(1).map { split(it, delimiter) }
        val dates = rows.map { row -> row.getOrNull(dateIndex)?.let(::dateParts) }
        val dayFirst = dates.any { it != null && !it.yearFirst && it.a > 12 }

        var corrected = 0
        var rejected = 0
        var daylightRows = 0
        // A row with no readable Dhuhr keeps the previous row's daylight state, which is what
        // a clock change looks like from one day to the next anyway.
        var shift = 0
        val days = LinkedHashMap<Pair<Int, Int>, Day>()

        for ((index, row) in rows.withIndex()) {
            val (month, day) = monthDay(dates[index], dayFirst) ?: continue

            val cells = HashMap<Column, Int>()
            for ((c, column) in columns.withIndex()) {
                if (column == null) continue
                val cellText = row.getOrNull(c)?.trim().orEmpty()
                if (cellText.isEmpty()) continue
                val time = parseTime(cellText)
                if (time == null) {
                    rejected++
                    continue
                }
                val (minutes, refiled) = fold(column, time)
                if (time.irregular || refiled) corrected++
                cells[column] = minutes
            }

            val dhuhr = cells[Column.DHUHR]
            val expected = standardDhuhr(month, day)
            if (dhuhr != null && expected != null) {
                val hours = ((dhuhr - expected) / 60.0).roundToInt()
                if (hours == 0 || hours == 1) shift = hours * 60
            }
            if (shift != 0) daylightRows++

            val standard = cells.mapValuesTo(HashMap()) { it.value - shift }
            rejected += dropImplausible(standard)
            if (standard.isNotEmpty()) days[month to day] = Day(month, day, standard)
        }

        if (days.isEmpty()) return failure("No row had a readable date and time.")
        return Result(days.values.toList(), corrected, rejected, daylightRows)
    }

    /**
     * Converts standard-time minutes on [date] into the wall-clock time for that day, applying
     * [zone]'s real daylight-saving rules for that date rather than whatever the file assumed.
     */
    fun toLocal(date: LocalDate, standardMinutes: Int, zone: ZoneId): LocalDateTime {
        val standardOffset = zone.rules.getStandardOffset(date.atStartOfDay(zone).toInstant())
        val instant = date.atStartOfDay()
            .plusMinutes(standardMinutes.toLong())
            .toInstant(standardOffset)
        return LocalDateTime.ofInstant(instant, zone)
    }

    fun serialize(days: List<Day>): String = days.joinToString("\n") { day ->
        (listOf("${day.month}-${day.day}") + day.minutes.map { (column, m) -> "${column.name}=$m" })
            .joinToString(",")
    }

    fun deserialize(text: String): List<Day> = text.lines().mapNotNull { line ->
        val parts = line.split(',')
        val monthDay = parts.firstOrNull()?.split('-') ?: return@mapNotNull null
        val month = monthDay.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
        val day = monthDay.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
        val cells = parts.drop(1).mapNotNull { pair ->
            val (name, value) = pair.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
            val column = Column.values().firstOrNull { it.name == name } ?: return@mapNotNull null
            value.toIntOrNull()?.let { column to it }
        }.toMap()
        Day(month, day, cells)
    }

    private fun failure(message: String) = Result(emptyList(), 0, 0, 0, message)

    // ------------------------------------------------------------------ columns

    private fun columnFor(header: String): Column? {
        val h = header.lowercase().filter(Char::isLetter)
        if (h.isEmpty() || "jumu" in h || "juma" in h || "hijri" in h || "date" in h) return null
        val iqamah = "iqam" in h || "ikam" in h || "jamaa" in h || "jamat" in h
        val prayer = when {
            "fajr" in h || "fajar" in h || "subh" in h -> Prayer.FAJR
            "shour" in h || "shur" in h || "sunrise" in h || "shrouq" in h -> Prayer.SUNRISE
            "dhuhr" in h || "zuhr" in h || "duhr" in h || "zohr" in h || "thuhr" in h -> Prayer.DHUHR
            "asr" in h -> Prayer.ASR
            "maghrib" in h || "magrib" in h || "maghreb" in h -> Prayer.MAGHRIB
            "isha" in h -> Prayer.ISHA
            else -> return null
        }
        if (prayer == Prayer.SUNRISE) return if (iqamah) null else Column.SUNRISE
        return Column.of(prayer, iqamah)
    }

    private fun split(line: String, delimiter: Char): List<String> {
        val out = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && line.getOrNull(i + 1) == '"' -> {
                    cell.append('"')
                    i++
                }
                c == '"' -> quoted = !quoted
                c == delimiter && !quoted -> {
                    out.add(cell.toString())
                    cell.setLength(0)
                }
                else -> cell.append(c)
            }
            i++
        }
        out.add(cell.toString())
        return out
    }

    // ------------------------------------------------------------------ dates

    private class DateParts(val a: Int, val b: Int, val c: Int?, val yearFirst: Boolean)

    private fun dateParts(text: String): DateParts? {
        val m = DATE.find(text) ?: return null
        val first = m.groupValues[1]
        return DateParts(
            first.toInt(),
            m.groupValues[2].toInt(),
            m.groupValues[3].toIntOrNull(),
            yearFirst = first.length == 4
        )
    }

    private fun monthDay(parts: DateParts?, dayFirst: Boolean): Pair<Int, Int>? {
        parts ?: return null
        val (month, day) = when {
            parts.yearFirst -> parts.b to (parts.c ?: return null)
            dayFirst -> parts.b to parts.a
            else -> parts.a to parts.b
        }
        // Checked against a leap year so a 29 February row survives.
        return try {
            LocalDate.of(2024, month, day)
            month to day
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------ times

    private class Time(val minutes: Int, val hadSuffix: Boolean, val irregular: Boolean)

    private fun parseTime(text: String): Time? {
        val m = TIME.matchEntire(text) ?: return null
        var hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].toInt()
        if (hour > 23 || minute > 59) return null
        val suffix = m.groupValues[3].uppercase()
        var irregular = !CANONICAL.matches(text)
        when {
            // "13:05 PM": a 24-hour time with a stray suffix. The number wins.
            hour >= 13 -> if (suffix.isNotEmpty()) irregular = true
            suffix == "P" && hour != 12 -> hour += 12
            suffix == "A" && hour == 12 -> hour = 0
        }
        return Time(hour * 60 + minute, suffix.isNotEmpty(), irregular)
    }

    /**
     * Moves a time onto the half of the day its prayer belongs to.
     *
     * Returns the minutes and whether an explicit AM or PM had to be overruled to get there.
     * Folding a time written without any suffix is interpretation rather than correction, so
     * it is not counted: a file that simply omits AM and PM is not wrong.
     */
    private fun fold(column: Column, time: Time): Pair<Int, Boolean> {
        val m = time.minutes
        return when (column.prayer) {
            Prayer.FAJR, Prayer.SUNRISE ->
                if (m >= 12 * 60) (m - 720) to time.hadSuffix else m to false
            Prayer.DHUHR ->
                if (m < 6 * 60) (m + 720) to time.hadSuffix else m to false
            Prayer.ASR, Prayer.MAGHRIB ->
                if (m < 12 * 60) (m + 720) to time.hadSuffix else m to false
            Prayer.ISHA -> when {
                // Just after midnight: still the evening's Isha, now on the next date.
                m < 3 * 60 -> (m + 1440) to false
                m < 12 * 60 -> (m + 720) to time.hadSuffix
                else -> m to false
            }
            Prayer.TAHAJJUD -> m to false
        }
    }

    /** Removes values that cannot be right and returns how many went. */
    private fun dropImplausible(cells: MutableMap<Column, Int>): Int {
        var dropped = 0
        for (column in Column.values()) {
            val m = cells[column] ?: continue
            if (m !in column.window) {
                cells.remove(column)
                dropped++
            }
        }
        // The prayer times of one day have to run forward. A value out of step with those before
        // it was misfiled even if it passed its own window.
        var last = -1
        for (column in ADHAN_ORDER) {
            val m = cells[column] ?: continue
            if (m <= last) {
                cells.remove(column)
                dropped++
            } else {
                last = m
            }
        }
        for (column in Column.values().filter { it.isIqamah }) {
            val m = cells[column] ?: continue
            val adhan = Column.of(column.prayer, iqamah = false)?.let { cells[it] } ?: continue
            if (m < adhan || m > adhan + MAX_IQAMAH_GAP) {
                cells.remove(column)
                dropped++
            }
        }
        return dropped
    }
}
