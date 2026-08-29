package com.lemon.prayeralarm

import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * Tests for the prayer time maths.
 *
 * The calculator has no Android dependencies, so it runs as a plain JVM test. Two of the bugs
 * fixed by hand — a daylight-saving error and a high-latitude one — were silent: they produced
 * plausible times rather than failing, so nothing surfaced them. The point of these tests is
 * that a future change to the maths cannot quietly reintroduce either.
 */
class PrayerTimesCalculatorTest {

    private val montrealLat = 45.50932
    private val montrealLng = -73.626335

    private fun montreal(
        date: LocalDate = LocalDate.of(2026, 8, 22),
        tzHours: Double = -4.0,
        method: CalculationMethod = CalculationMethod.ISNA,
        madhab: Madhab = Madhab.STANDARD
    ) = PrayerTimesCalculator.calculate(date, montrealLat, montrealLng, tzHours, method, madhab)

    private fun minutesBetween(a: LocalTime, b: LocalTime) =
        abs(a.toSecondOfDay() - b.toSecondOfDay()) / 60

    // ---------------------------------------------------------------- ordering

    @Test
    fun `times run in order through the day`() {
        val t = montreal()
        assertTrue("fajr before sunrise", t.fajr < t.sunrise)
        assertTrue("sunrise before dhuhr", t.sunrise < t.dhuhr)
        assertTrue("dhuhr before asr", t.dhuhr < t.asr)
        assertTrue("asr before maghrib", t.asr < t.maghrib)
        assertTrue("maghrib before isha", t.maghrib < t.isha)
    }

    @Test
    fun `ordering holds at the solstices and equinoxes`() {
        for (date in listOf(
            LocalDate.of(2026, 3, 20),
            LocalDate.of(2026, 6, 21),
            LocalDate.of(2026, 9, 22),
            LocalDate.of(2026, 12, 21)
        )) {
            val t = montreal(date = date)
            assertTrue("$date: fajr before sunrise", t.fajr < t.sunrise)
            assertTrue("$date: sunrise before dhuhr", t.sunrise < t.dhuhr)
            assertTrue("$date: dhuhr before asr", t.dhuhr < t.asr)
            assertTrue("$date: asr before maghrib", t.asr < t.maghrib)
        }
    }

    // ---------------------------------------------------------------- madhab

    @Test
    fun `hanafi asr is later than standard asr`() {
        val standard = montreal(madhab = Madhab.STANDARD)
        val hanafi = montreal(madhab = Madhab.HANAFI)
        assertTrue(
            "hanafi asr ${hanafi.asr} should be after standard ${standard.asr}",
            hanafi.asr > standard.asr
        )
        // Only Asr depends on the madhab; everything else must be untouched.
        assertEquals(standard.fajr, hanafi.fajr)
        assertEquals(standard.dhuhr, hanafi.dhuhr)
        assertEquals(standard.maghrib, hanafi.maghrib)
    }

    // ---------------------------------------------------------------- method

    @Test
    fun `a shallower fajr angle gives a later fajr`() {
        // ISNA uses 15 degrees, MWL 18. The sun reaches 15 degrees below the horizon later.
        val isna = montreal(method = CalculationMethod.ISNA)
        val mwl = montreal(method = CalculationMethod.MWL)
        assertTrue("isna fajr ${isna.fajr} should be after mwl ${mwl.fajr}", isna.fajr > mwl.fajr)
    }

    @Test
    fun `interval based isha sits a fixed gap after maghrib`() {
        // Makkah puts Isha 90 minutes after Maghrib rather than at a sun angle.
        val t = montreal(method = CalculationMethod.MAKKAH)
        assertEquals(90, minutesBetween(t.isha, t.maghrib).toLong().toInt())
    }

    // ---------------------------------------------------------------- timezone / DST

    @Test
    fun `shifting the timezone shifts every time by the same amount`() {
        // The daylight-saving bug applied one date's offset to another date. Times must track
        // the offset exactly, so that passing the right offset per date is all that is needed.
        val edt = montreal(tzHours = -4.0)
        val est = montreal(tzHours = -5.0)
        assertEquals(60, minutesBetween(edt.fajr, est.fajr))
        assertEquals(60, minutesBetween(edt.dhuhr, est.dhuhr))
        assertEquals(60, minutesBetween(edt.maghrib, est.maghrib))
        assertTrue("standard time is an hour earlier", est.dhuhr < edt.dhuhr)
    }

    // ---------------------------------------------------------------- high latitude

    @Test
    fun `london midsummer keeps fajr and isha apart`() {
        // At 51.5 degrees the sun never reaches 18 degrees below the horizon in June. The old
        // code clamped the domain error and collapsed Fajr and Isha onto the same minute, which
        // looked like a real time. They must stay hours apart, with Isha in the evening.
        val t = PrayerTimesCalculator.calculate(
            LocalDate.of(2026, 6, 21), 51.5074, -0.1278, 1.0,
            CalculationMethod.MWL, Madhab.STANDARD
        )
        assertTrue("isha ${t.isha} should be in the evening", t.isha.hour >= 21)
        assertTrue("fajr ${t.fajr} should be in the early morning", t.fajr.hour <= 4)
        assertTrue(
            "fajr ${t.fajr} and isha ${t.isha} collapsed together",
            minutesBetween(t.isha, t.fajr) > 120
        )
    }

    @Test
    @Ignore(
        "Known limitation. Above the Arctic Circle in midsummer the sun never crosses the " +
            "horizon, so sunrise and sunset do not exist and the angle-based rule has nothing " +
            "to anchor to. The calculator currently returns 00:00 for fajr, sunrise, maghrib " +
            "and isha rather than reporting that they are undefined, which would put three " +
            "alarms at midnight. Fixing it needs a nearest-latitude or midnight fallback. " +
            "Montreal at 45.5 degrees is unaffected; this bites above roughly 66 degrees."
    )
    fun `far north midsummer still produces ordered times`() {
        // Tromso, well inside the Arctic Circle, is where naive sun-angle maths falls apart.
        val t = PrayerTimesCalculator.calculate(
            LocalDate.of(2026, 6, 21), 69.6492, 18.9553, 2.0,
            CalculationMethod.MWL, Madhab.STANDARD
        )
        val dump = "fajr=${t.fajr} sunrise=${t.sunrise} dhuhr=${t.dhuhr} " +
            "asr=${t.asr} maghrib=${t.maghrib} isha=${t.isha}"
        assertTrue("dhuhr before asr | $dump", t.dhuhr < t.asr)
        assertTrue("asr before maghrib | $dump", t.asr < t.maghrib)
        assertTrue("fajr before dhuhr | $dump", t.fajr < t.dhuhr)
    }

    // ---------------------------------------------------------------- regression pin

    @Test
    fun `montreal times stay where they were verified`() {
        // Pinned from output checked against the device on 22 August 2026. This does not prove
        // the maths is right, only that it has not moved; the invariants above cover sanity.
        val t = montreal()
        assertEquals(LocalTime.of(4, 33), t.fajr)
        assertEquals(LocalTime.of(6, 4), t.sunrise)
        assertEquals(LocalTime.of(12, 57), t.dhuhr)
        assertEquals(LocalTime.of(16, 47), t.asr)
        assertEquals(LocalTime.of(19, 50), t.maghrib)
        assertEquals(LocalTime.of(21, 20), t.isha)
    }
}
