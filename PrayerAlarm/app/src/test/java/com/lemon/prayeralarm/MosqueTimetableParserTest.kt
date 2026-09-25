package com.lemon.prayeralarm

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for timetable import.
 *
 * [REAL_ROWS] are copied verbatim from Masjid Al Falah's published timetable, one row per defect
 * found in it: a missing space ("2:41PM"), a 24-hour time with a stray suffix ("13:05 PM"), Dhuhr
 * written as "12:38 AM", and daylight saving switched on 12 March and 5 November rather than on
 * the real dates.
 */
class MosqueTimetableParserTest {

    private val montreal: ZoneId = ZoneId.of("America/Toronto")
    private val mosqueLat = 45.5430064
    private val mosqueLng = -73.6485338

    /** The app's own Dhuhr in Eastern Standard Time, which each row is checked against. */
    private val standardDhuhr: (Int, Int) -> Int? = { month, day ->
        PrayerTimesCalculator.calculate(
            LocalDate.of(2024, month, day), mosqueLat, mosqueLng, -5.0,
            CalculationMethod.ISNA, Madhab.STANDARD
        ).dhuhr.toSecondOfDay() / 60
    }

    private fun parse(text: String) = MosqueTimetableParser.parse(text, standardDhuhr)

    private fun local(result: MosqueTimetableParser.Result, date: LocalDate, column: MosqueTimetableParser.Column): LocalDateTime? {
        val day = result.days.firstOrNull { it.month == date.monthValue && it.day == date.dayOfMonth }
        val minutes = day?.minutes?.get(column) ?: return null
        return MosqueTimetableParser.toLocal(date, minutes, montreal)
    }

    // ---------------------------------------------------------------- the real file

    @Test
    fun `every row of the real sample is read`() {
        val r = parse(REAL_ROWS)
        assertNull(r.error)
        assertEquals(8, r.days.size)
        assertEquals("no cell should need discarding", 0, r.rejected)
    }

    @Test
    fun `each formatting defect is repaired and counted`() {
        // 2:41PM, 13:05 PM, and 12:38 AM twice.
        assertEquals(4, parse(REAL_ROWS).corrected)
    }

    @Test
    fun `rows the file shifted for daylight saving are detected`() {
        // 12 March, 24 September, 1 November and 4 November are in the file's daylight period.
        assertEquals(4, parse(REAL_ROWS).daylightRows)
    }

    @Test
    fun `values are stored in standard time`() {
        val sep24 = parse(REAL_ROWS).days.first { it.month == 9 && it.day == 24 }
        assertEquals(4 * 60 + 23, sep24.minutes[MosqueTimetableParser.Column.FAJR])
        assertEquals(11 * 60 + 48, sep24.minutes[MosqueTimetableParser.Column.DHUHR])
        assertEquals(15 * 60 + 30, sep24.minutes[MosqueTimetableParser.Column.ASR_IQAMAH])
    }

    // ---------------------------------------------------------------- daylight saving

    @Test
    fun `dhuhr follows the real spring clock change, not the file`() {
        // Clocks went forward on 8 March 2026, but the file stays in standard time until the
        // 12th. Read literally, Dhuhr on the 9th would ring an hour early at 12:05.
        val r = parse(REAL_ROWS)
        assertEquals(
            LocalDateTime.of(2026, 3, 9, 13, 5),
            local(r, LocalDate.of(2026, 3, 9), MosqueTimetableParser.Column.DHUHR)
        )
    }

    @Test
    fun `dhuhr follows the real autumn clock change, not the file`() {
        // Clocks went back on 1 November 2026, the file stays in daylight time until the 5th,
        // and on the 1st it also writes Dhuhr as 12:38 AM. Read literally: an alarm at 00:38.
        val r = parse(REAL_ROWS)
        assertEquals(
            LocalDateTime.of(2026, 11, 1, 11, 38),
            local(r, LocalDate.of(2026, 11, 1), MosqueTimetableParser.Column.DHUHR)
        )
    }

    @Test
    fun `days the file already got right are unchanged`() {
        val r = parse(REAL_ROWS)
        assertEquals(
            LocalDateTime.of(2026, 9, 24, 12, 48),
            local(r, LocalDate.of(2026, 9, 24), MosqueTimetableParser.Column.DHUHR)
        )
        assertEquals(
            LocalDateTime.of(2026, 9, 24, 5, 53),
            local(r, LocalDate.of(2026, 9, 24), MosqueTimetableParser.Column.FAJR_IQAMAH)
        )
        assertEquals(
            LocalDateTime.of(2026, 11, 5, 11, 38),
            local(r, LocalDate.of(2026, 11, 5), MosqueTimetableParser.Column.DHUHR)
        )
    }

    @Test
    fun `the stray 24 hour time lands on the right hour`() {
        val r = parse(REAL_ROWS)
        assertEquals(
            LocalDateTime.of(2026, 3, 12, 13, 5),
            local(r, LocalDate.of(2026, 3, 12), MosqueTimetableParser.Column.DHUHR)
        )
    }

    @Test
    fun `a year with different clock change dates is handled too`() {
        // In 2027 the clocks change on 14 March. The template shifts on the 12th, so reading it
        // literally would put the 12th and 13th an hour late instead.
        val r = parse(REAL_ROWS)
        assertEquals(
            LocalDateTime.of(2027, 3, 12, 12, 5),
            local(r, LocalDate.of(2027, 3, 12), MosqueTimetableParser.Column.DHUHR)
        )
    }

    // ---------------------------------------------------------------- sanity checks

    @Test
    fun `an iqamah before its own adhan is discarded`() {
        // The 24 September row with its Fajr iqamah altered to precede the adhan.
        val text = HEADER + "\n" + REAL_ROWS.lines()
            .first { it.startsWith("09/24") }
            .replace("5:23 AM,5:53 AM", "5:23 AM,5:03 AM")
        val r = parse(text)
        assertEquals(1, r.rejected)
        assertNull(r.days.single().minutes[MosqueTimetableParser.Column.FAJR_IQAMAH])
        assertNotNull(r.days.single().minutes[MosqueTimetableParser.Column.FAJR])
    }

    @Test
    fun `a file without prayer columns is refused`() {
        val r = parse("date,name,notes\n01/01/2020,a,b")
        assertTrue(r.error != null)
        assertTrue(r.days.isEmpty())
    }

    @Test
    fun `day first dates and semicolons are understood`() {
        // European layout of the 24 September row: dd/MM/yyyy separated by semicolons.
        val text = "Date;Fajr;Fajr Iqamah;Dhuhr;Asr;Maghrib;Isha\n" +
            "24/09/2020;5:23 AM;5:53 AM;12:48 PM;4:09 PM;6:51 PM;8:13 PM"
        val day = parse(text).days.single()
        assertEquals(9, day.month)
        assertEquals(24, day.day)
        assertEquals(11 * 60 + 48, day.minutes[MosqueTimetableParser.Column.DHUHR])
    }

    @Test
    fun `storage round trips`() {
        val days = parse(REAL_ROWS).days
        val restored = MosqueTimetableParser.deserialize(MosqueTimetableParser.serialize(days))
        assertEquals(days, restored)
    }

    companion object {
        private const val HEADER =
            "date,dateHijri,adhanFajr,iqamaFajr,shourouk,adhanDhuhr,iqamaDhuhr,adhanAsr,iqamaAsr," +
                "adhanMaghrib,iqamaMaghrib,adhanIsha,iqamaIsha,Jumua1,Jumua2"

        private val REAL_ROWS = HEADER + "\n" + """
02/03/2020,"AlEthnien, 03 Safar, 2020",5:49 AM,6:09 AM,7:13 AM,12:09 PM,12:45 PM,2:41PM,3:00 PM,5:04 PM,5:11 PM,6:29 PM,7:30 PM,12:20 PM,1:10 PM
03/09/2020,"AlEthnien, 09 Rabi' Awwal, 2020",4:57 AM,5:17 AM,6:18 AM,12:05 PM,12:45 PM,3:17 PM,3:30 PM,5:53 PM,6:00 PM,7:14 PM,7:30 PM,12:20 PM,1:10 PM
03/11/2020,"AlArbia'a, 11 Rabi' Awwal, 2020",4:53 AM,5:13 AM,6:14 AM,12:05 PM,12:45 PM,3:18 PM,3:30 PM,5:55 PM,6:02 PM,7:17 PM,7:30 PM,12:20 PM,1:10 PM
03/12/2020,"AlKhamis, 12 Rabi' Awwal, 2020",5:53 AM,6:13 AM,7:14 AM,13:05 PM,1:15 PM,4:18 PM,5:00 PM,6:55 PM,7:02 PM,8:17 PM,8:27 PM,12:20 PM,1:10 PM
09/24/2020,"AlKhamis, 24 Ramadan, 2020",5:23 AM,5:53 AM,6:43 AM,12:48 PM,1:15 PM,4:09 PM,4:30 PM,6:51 PM,6:58 PM,8:13 PM,8:23 PM,12:20 PM,1:10 PM
11/01/2020,"AlAhad, 01 Thoul Ki'dah, 2020",6:10 AM,6:30 AM,7:32 AM,12:38 AM,1:15 PM,3:17 PM,3:45 PM,5:43 PM,5:50 PM,7:06 PM,7:30 PM,12:20 PM,1:10 PM
11/04/2020,"AlArbia'a, 04 Thoul Ki'dah, 2020",6:14 AM,6:34 AM,7:36 AM,12:38 AM,1:15 PM,3:14 PM,3:45 PM,5:39 PM,5:46 PM,7:02 PM,7:30 PM,12:20 PM,1:10 PM
11/05/2020,"AlKhamis, 05 Thoul Ki'dah, 2020",5:15 AM,5:35 AM,6:37 AM,11:38 AM,12:45 PM,2:13 PM,2:30 PM,4:37 PM,4:44 PM,6:01 PM,7:30 PM,12:20 PM,1:10 PM
""".trim()
    }
}
