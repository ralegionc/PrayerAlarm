package com.lemon.prayeralarm

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The imported mosque timetable: fetching, storage and lookup.
 *
 * It is kept in the app's own files in the parser's normalised form, so after an import nothing
 * touches the network again. Lookups return real local date-times with the current year's
 * daylight saving applied, which is the reason the file's own daylight saving was stripped out
 * in the first place.
 */
object MosqueTimetable {

    private const val FILE_NAME = "mosque_timetable.txt"

    /** Generous for a year of times; small enough that a wrong link cannot fill the phone. */
    private const val MAX_BYTES = 2 * 1024 * 1024

    class Outcome(val ok: Boolean, val message: String)

    @Volatile
    private var cache: Map<Pair<Int, Int>, MosqueTimetableParser.Day>? = null

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)

    private fun table(context: Context): Map<Pair<Int, Int>, MosqueTimetableParser.Day> {
        cache?.let { return it }
        val stored = file(context)
        val loaded = if (stored.exists()) {
            MosqueTimetableParser.deserialize(stored.readText()).associateBy { it.month to it.day }
        } else {
            emptyMap()
        }
        cache = loaded
        return loaded
    }

    fun isActive(context: Context): Boolean = table(context).isNotEmpty()

    fun hasDay(context: Context, date: LocalDate): Boolean =
        table(context).containsKey(date.monthValue to date.dayOfMonth)

    /** When [prayer] begins on [date] according to the mosque, or null to use the calculation. */
    fun prayerTime(context: Context, prayer: Prayer, date: LocalDate): LocalDateTime? =
        MosqueTimetableParser.Column.of(prayer, iqamah = false)?.let { at(context, date, it) }

    /** When the congregation stands for [prayer] on [date], or null if the mosque gives none. */
    fun iqamah(context: Context, prayer: Prayer, date: LocalDate): LocalDateTime? =
        MosqueTimetableParser.Column.of(prayer, iqamah = true)?.let { at(context, date, it) }

    private fun at(context: Context, date: LocalDate, column: MosqueTimetableParser.Column): LocalDateTime? {
        val day = table(context)[date.monthValue to date.dayOfMonth] ?: return null
        val minutes = day.minutes[column] ?: return null
        return MosqueTimetableParser.toLocal(date, minutes, ZoneId.systemDefault())
    }

    /**
     * Parses [text] and stores it, replacing any earlier timetable.
     *
     * Needs a saved location, because each row's daylight saving is found by comparing its Dhuhr
     * with the one calculated for that date. Does no network or disk reads of its own, so it is
     * safe off the main thread.
     */
    fun import(context: Context, text: String, source: String): Outcome {
        val prefs = PrefsRepository(context)
        if (!prefs.hasLocation) {
            return Outcome(false, context.getString(R.string.timetable_need_location))
        }
        val zone = ZoneId.systemDefault()
        val standardHours = zone.rules.getStandardOffset(Instant.now()).totalSeconds / 3600.0
        val method = CalculationMethod.forIndex(prefs.calculationMethodIndex)
        val madhab = Madhab.fromIndex(prefs.madhabIndex)
        val lat = prefs.latitude
        val lng = prefs.longitude

        val result = MosqueTimetableParser.parse(text) { month, day ->
            // 2024 is a leap year, so a 29 February row has a date to be checked against.
            PrayerTimesCalculator.calculate(
                LocalDate.of(2024, month, day), lat, lng, standardHours, method, madhab
            ).dhuhr.toSecondOfDay() / 60
        }
        result.error?.let { return Outcome(false, it) }

        file(context).writeText(MosqueTimetableParser.serialize(result.days))
        cache = null
        val summary = context.getString(
            R.string.timetable_summary,
            result.days.size,
            result.corrected,
            result.rejected,
            source
        )
        prefs.timetableSource = source
        prefs.timetableSummary = summary
        return Outcome(true, summary)
    }

    fun clear(context: Context) {
        file(context).delete()
        cache = null
        val prefs = PrefsRepository(context)
        prefs.timetableSource = ""
        prefs.timetableSummary = ""
    }

    /** Fetches a timetable over HTTPS. Blocking, so call it off the main thread. */
    @Throws(IOException::class)
    fun download(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("the server answered HTTP $code")
            return connection.inputStream.use(::readCapped)
        } finally {
            connection.disconnect()
        }
    }

    /** Reads a timetable file the user picked. Blocking, so call it off the main thread. */
    @Throws(IOException::class)
    fun read(context: Context, uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use(::readCapped)
            ?: throw IOException("the file could not be opened")

    private fun readCapped(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > MAX_BYTES) throw IOException("the file is far larger than a timetable")
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
