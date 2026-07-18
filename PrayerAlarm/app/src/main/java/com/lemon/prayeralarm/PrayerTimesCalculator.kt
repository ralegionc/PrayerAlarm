package com.lemon.prayeralarm

import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.*

/**
 * Astronomical prayer-time calculator.
 *
 * This is a Kotlin port of the widely used "PrayTimes.org" sun-angle algorithm
 * (the same approach used by most prayer-time apps and the AlAdhan API):
 * it computes the sun's declination and the equation of time for the day, then
 * finds the clock time at which the sun crosses each required angle below the
 * horizon (or above it, for Dhuhr/Asr).
 *
 * Two passes are used for convergence, matching the reference implementation.
 */
object PrayerTimesCalculator {

    data class Times(
        val fajr: LocalTime,
        val sunrise: LocalTime,
        val dhuhr: LocalTime,
        val asr: LocalTime,
        val maghrib: LocalTime,
        val isha: LocalTime
    )

    /**
     * @param timeZoneOffsetHours e.g. 5.5 for UTC+5:30. Include DST if applicable.
     */
    fun calculate(
        date: LocalDate,
        latitude: Double,
        longitude: Double,
        timeZoneOffsetHours: Double,
        method: CalculationMethod,
        madhab: Madhab
    ): Times {
        val jDate = julian(date.year, date.monthValue, date.dayOfMonth) - longitude / (15.0 * 24.0)

        // Initial rough guesses (hours of day), refined over two iterations.
        var times = mapOf(
            "fajr" to 5.0, "sunrise" to 6.0, "dhuhr" to 12.0,
            "asr" to 13.0, "sunset" to 18.0, "maghrib" to 18.0, "isha" to 18.0
        )
        repeat(2) {
            times = computePass(times, jDate, latitude, method, madhab)
        }

        val correction = timeZoneOffsetHours - longitude / 15.0
        val adjusted = times.mapValues { it.value + correction }.toMutableMap()

        if (method.ishaIntervalMinutes > 0) {
            adjusted["isha"] = adjusted["maghrib"]!! + method.ishaIntervalMinutes / 60.0
        }

        return Times(
            fajr = toLocalTime(adjusted["fajr"]!!),
            sunrise = toLocalTime(adjusted["sunrise"]!!),
            dhuhr = toLocalTime(adjusted["dhuhr"]!!),
            asr = toLocalTime(adjusted["asr"]!!),
            maghrib = toLocalTime(adjusted["maghrib"]!!),
            isha = toLocalTime(adjusted["isha"]!!)
        )
    }

    private fun computePass(
        prev: Map<String, Double>,
        jDate: Double,
        lat: Double,
        method: CalculationMethod,
        madhab: Madhab
    ): Map<String, Double> {
        val portion = prev.mapValues { it.value / 24.0 }
        val riseSet = riseSetAngle()

        val fajr = sunAngleTime(method.fajrAngle, portion.getValue("fajr"), jDate, lat, ccw = true)
        val sunrise = sunAngleTime(riseSet, portion.getValue("sunrise"), jDate, lat, ccw = true)
        val dhuhr = midDay(portion.getValue("dhuhr"), jDate)
        val asr = asrTime(madhab.asrShadowFactor, portion.getValue("asr"), jDate, lat)
        val sunset = sunAngleTime(riseSet, portion.getValue("sunset"), jDate, lat, ccw = false)
        val maghribAngle = if (method.maghribAngle > 0.0) method.maghribAngle else riseSet
        val maghrib = sunAngleTime(maghribAngle, portion.getValue("maghrib"), jDate, lat, ccw = false)
        val ishaAngle = if (method.ishaIntervalMinutes > 0) 18.0 else method.ishaAngle
        val isha = sunAngleTime(ishaAngle, portion.getValue("isha"), jDate, lat, ccw = false)

        return mapOf(
            "fajr" to fajr, "sunrise" to sunrise, "dhuhr" to dhuhr, "asr" to asr,
            "sunset" to sunset, "maghrib" to maghrib, "isha" to isha
        )
    }

    private fun riseSetAngle(elevationMeters: Double = 0.0): Double =
        0.833 + 0.0347 * sqrt(max(0.0, elevationMeters))

    private fun midDay(time: Double, jDate: Double): Double {
        val eqt = sunPosition(jDate + time).equation
        return fixHour(12.0 - eqt)
    }

    private fun sunAngleTime(angle: Double, time: Double, jDate: Double, lat: Double, ccw: Boolean): Double {
        val decl = sunPosition(jDate + time).declination
        val noon = midDay(time, jDate)
        val cosArg = ((-dsin(angle) - dsin(decl) * dsin(lat)) / (dcos(decl) * dcos(lat)))
            .coerceIn(-1.0, 1.0) // guards against NaN at extreme latitudes/seasons
        val t = (1.0 / 15.0) * darccos(cosArg)
        return noon + if (ccw) -t else t
    }

    private fun asrTime(shadowFactor: Int, time: Double, jDate: Double, lat: Double): Double {
        val decl = sunPosition(jDate + time).declination
        val angle = -darccot(shadowFactor.toDouble() + dtan(abs(lat - decl)))
        return sunAngleTime(angle, time, jDate, lat, ccw = false)
    }

    private data class SunPos(val declination: Double, val equation: Double)

    private fun sunPosition(jd: Double): SunPos {
        val d = jd - 2451545.0
        val g = fixAngle(357.529 + 0.98560028 * d)
        val q = fixAngle(280.459 + 0.98564736 * d)
        val l = fixAngle(q + 1.915 * dsin(g) + 0.020 * dsin(2 * g))
        val e = 23.439 - 0.00000036 * d
        val ra = darctan2(dcos(e) * dsin(l), dcos(l)) / 15.0
        val eqt = q / 15.0 - fixHour(ra)
        val decl = darcsin(dsin(e) * dsin(l))
        return SunPos(decl, eqt)
    }

    private fun julian(year: Int, month: Int, day: Int): Double {
        var y = year
        var m = month
        if (m <= 2) {
            y -= 1
            m += 12
        }
        val a = floor(y / 100.0)
        val b = 2 - a + floor(a / 4.0)
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + day + b - 1524.5
    }

    private fun toLocalTime(hours: Double): LocalTime {
        val h = fixHour(hours)
        val totalMinutes = Math.round(h * 60.0)
        val hh = ((totalMinutes / 60) % 24).toInt()
        val mm = (totalMinutes % 60).toInt()
        return LocalTime.of(hh, mm)
    }

    private fun fixAngle(a: Double): Double {
        var x = a % 360.0
        if (x < 0) x += 360.0
        return x
    }

    private fun fixHour(a: Double): Double {
        var x = a % 24.0
        if (x < 0) x += 24.0
        return x
    }

    private fun dsin(d: Double) = sin(Math.toRadians(d))
    private fun dcos(d: Double) = cos(Math.toRadians(d))
    private fun dtan(d: Double) = tan(Math.toRadians(d))
    private fun darcsin(x: Double) = Math.toDegrees(asin(x.coerceIn(-1.0, 1.0)))
    private fun darccos(x: Double) = Math.toDegrees(acos(x.coerceIn(-1.0, 1.0)))
    private fun darctan2(y: Double, x: Double) = Math.toDegrees(atan2(y, x))
    private fun darccot(x: Double) = Math.toDegrees(atan(1.0 / x))
}
