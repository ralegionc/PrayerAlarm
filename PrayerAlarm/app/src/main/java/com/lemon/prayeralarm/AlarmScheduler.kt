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
    const val ACTION_PRE_REMINDER = "com.lemon.prayeralarm.ACTION_PRE_REMINDER"
    const val EXTRA_PRAYER = "extra_prayer"
    const val EXTRA_LEAD_MINUTES = "extra_lead_minutes"
    private const val REQUEST_CODE_BASE = 5100
    private const val REQUEST_CODE_PRE_BASE = 5200

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
                prayer, prefs.latitude, prefs.longitude, method, madhab, offset, now
            ) ?: continue

            setExactAlarm(alarmManager, triggerDateTime, pendingIntent)

            // Optional "prayer is coming up" nudge ahead of the alarm itself.
            val preIntent = buildPreReminderIntent(context, prayer)
            val lead = prefs.preReminderMinutes
            val preTime = triggerDateTime.minusMinutes(lead.toLong())
            if (lead <= 0 || !preTime.isAfter(now)) {
                alarmManager.cancel(preIntent)
            } else {
                setExactAlarm(alarmManager, preTime, preIntent)
            }
        }

        PrayerWidgetProvider.refreshAll(context)
    }

    private fun setExactAlarm(
        alarmManager: AlarmManager,
        at: LocalDateTime,
        pendingIntent: PendingIntent
    ) {
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pendingIntent)
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pendingIntent)
            }
        } catch (e: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pendingIntent)
        }
    }

    /** The next prayer due after [from], with per-prayer offsets applied. */
    fun nextPrayer(
        context: Context,
        from: LocalDateTime = LocalDateTime.now()
    ): Pair<Prayer, LocalDateTime>? {
        for (dayOffset in 0..1) {
            val date = from.toLocalDate().plusDays(dayOffset.toLong())
            val times = timesForDate(context, date) ?: return null
            var best: Pair<Prayer, LocalDateTime>? = null
            for (prayer in Prayer.values()) {
                val dt = LocalDateTime.of(date, times.getValue(prayer))
                val current = best
                if (dt.isAfter(from) && (current == null || dt.isBefore(current.second))) {
                    best = prayer to dt
                }
            }
            if (best != null) return best
        }
        return null
    }

    fun cancelAll(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (prayer in Prayer.values()) {
            alarmManager.cancel(buildPendingIntent(context, prayer))
            alarmManager.cancel(buildPreReminderIntent(context, prayer))
        }
    }

    /**
     * UTC offset in hours for [date] specifically.
     *
     * Using the offset at "now" for a future date puts every computed time an hour out across a
     * daylight-saving boundary, which is exactly when a mis-timed Fajr alarm is least welcome.
     */
    private fun tzHoursFor(date: LocalDate): Double {
        val millis = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return TimeZone.getDefault().getOffset(millis) / 3_600_000.0
    }

    /** Full astronomical times for [date], including sunrise. No offsets applied. */
    fun rawTimesForDate(context: Context, date: LocalDate): PrayerTimesCalculator.Times? {
        val prefs = PrefsRepository(context)
        if (!prefs.hasLocation) return null
        return PrayerTimesCalculator.calculate(
            date,
            prefs.latitude,
            prefs.longitude,
            tzHoursFor(date),
            CalculationMethod.forIndex(prefs.calculationMethodIndex),
            Madhab.fromIndex(prefs.madhabIndex)
        )
    }

    /**
     * Times for [date] computed from explicitly supplied settings rather than what is stored.
     * Lets the settings screen preview the effect of a choice the user has not saved yet.
     */
    fun previewTimes(
        context: Context,
        date: LocalDate,
        methodIndex: Int,
        madhabIndex: Int
    ): PrayerTimesCalculator.Times? {
        val prefs = PrefsRepository(context)
        if (!prefs.hasLocation) return null
        return PrayerTimesCalculator.calculate(
            date,
            prefs.latitude,
            prefs.longitude,
            tzHoursFor(date),
            CalculationMethod.forIndex(methodIndex),
            Madhab.fromIndex(madhabIndex)
        )
    }

    /** Prayer times for [date], with per-prayer minute offsets already applied. */
    fun timesForDate(context: Context, date: LocalDate): Map<Prayer, LocalTime>? {
        val prefs = PrefsRepository(context)
        val times = rawTimesForDate(context, date) ?: return null
        return Prayer.values().associateWith { prayer ->
            rawTimeFor(prayer, times).plusMinutes(prefs.offsetMinutes(prayer).toLong())
        }
    }

    private fun nextOccurrence(
        prayer: Prayer,
        lat: Double,
        lng: Double,
        method: CalculationMethod,
        madhab: Madhab,
        offsetMinutes: Int,
        now: LocalDateTime
    ): LocalDateTime? {
        val today = LocalDate.now()
        for (dayOffset in 0..3) {
            val date = today.plusDays(dayOffset.toLong())
            val times = PrayerTimesCalculator.calculate(date, lat, lng, tzHoursFor(date), method, madhab)
            val adjusted = rawTimeFor(prayer, times).plusMinutes(offsetMinutes.toLong())
            val candidate = LocalDateTime.of(date, adjusted)
            if (candidate.isAfter(now)) return candidate
        }
        return null
    }

    /** The astronomical time for one prayer, before any user offset. */
    fun rawTimeFor(prayer: Prayer, times: PrayerTimesCalculator.Times): LocalTime = when (prayer) {
        Prayer.FAJR -> times.fajr
        Prayer.DHUHR -> times.dhuhr
        Prayer.ASR -> times.asr
        Prayer.MAGHRIB -> times.maghrib
        Prayer.ISHA -> times.isha
    }

    private fun buildPreReminderIntent(context: Context, prayer: Prayer): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_PRE_REMINDER
            putExtra(EXTRA_PRAYER, prayer.storageKey)
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_PRE_BASE + prayer.ordinal,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
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
