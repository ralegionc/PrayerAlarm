package com.lemon.prayeralarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.TimeZone

/** Computes upcoming prayer times and schedules exact alarms for every enabled prayer. */
object AlarmScheduler {

    const val ACTION_PRAYER_ALARM = "com.lemon.prayeralarm.ACTION_PRAYER_ALARM"
    const val EXTRA_PRAYER = "extra_prayer"
    private const val REQUEST_CODE_BASE = 5100

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return alarmManager.canScheduleExactAlarms()
    }

    /** Recomputes and (re)schedules alarms for all five prayers based on current settings. */
    fun scheduleAll(context: Context) {
        val prefs = PrefsRepository(context)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        if (!prefs.hasLocation) return

        val method = CalculationMethod.forIndex(prefs.calculationMethodIndex)
        val madhab = Madhab.fromIndex(prefs.madhabIndex)
        val tzHours = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 3_600_000.0
        val now = LocalDateTime.now()

        for (prayer in Prayer.values()) {
            val pendingIntent = buildPendingIntent(context, prayer)
            val mode = prefs.alarmMode(prayer)

            if (mode == AlarmMode.OFF) {
                alarmManager.cancel(pendingIntent)
                continue
            }

            val offset = prefs.offsetMinutes(prayer)
            val triggerDateTime = nextOccurrence(
                prayer, prefs.latitude, prefs.longitude, tzHours, method, madhab, offset, now
            ) ?: continue

            val triggerMillis = triggerDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
                } else {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
                }
            } catch (e: SecurityException) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pendingIntent)
            }
        }
    }

    fun cancelAll(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (prayer in Prayer.values()) {
            alarmManager.cancel(buildPendingIntent(context, prayer))
        }
    }

    /** Prayer times for [date], with per-prayer minute offsets already applied. */
    fun timesForDate(context: Context, date: LocalDate): Map<Prayer, LocalTime>? {
        val prefs = PrefsRepository(context)
        if (!prefs.hasLocation) return null
        val method = CalculationMethod.forIndex(prefs.calculationMethodIndex)
        val madhab = Madhab.fromIndex(prefs.madhabIndex)
        val tzHours = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 3_600_000.0
        val times = PrayerTimesCalculator.calculate(date, prefs.latitude, prefs.longitude, tzHours, method, madhab)
        return Prayer.values().associateWith { prayer ->
            rawTimeFor(prayer, times).plusMinutes(prefs.offsetMinutes(prayer).toLong())
        }
    }

    private fun nextOccurrence(
        prayer: Prayer,
        lat: Double,
        lng: Double,
        tzHours: Double,
        method: CalculationMethod,
        madhab: Madhab,
        offsetMinutes: Int,
        now: LocalDateTime
    ): LocalDateTime? {
        val today = LocalDate.now()
        for (dayOffset in 0..3) {
            val date = today.plusDays(dayOffset.toLong())
            val times = PrayerTimesCalculator.calculate(date, lat, lng, tzHours, method, madhab)
            val adjusted = rawTimeFor(prayer, times).plusMinutes(offsetMinutes.toLong())
            val candidate = LocalDateTime.of(date, adjusted)
            if (candidate.isAfter(now)) return candidate
        }
        return null
    }

    private fun rawTimeFor(prayer: Prayer, times: PrayerTimesCalculator.Times): LocalTime = when (prayer) {
        Prayer.FAJR -> times.fajr
        Prayer.DHUHR -> times.dhuhr
        Prayer.ASR -> times.asr
        Prayer.MAGHRIB -> times.maghrib
        Prayer.ISHA -> times.isha
    }

    private fun buildPendingIntent(context: Context, prayer: Prayer): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_PRAYER_ALARM
            putExtra(EXTRA_PRAYER, prayer.storageKey)
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_BASE + prayer.ordinal,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
