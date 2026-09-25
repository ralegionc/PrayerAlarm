package com.lemon.prayeralarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Computes upcoming prayer times and schedules exact alarms for every enabled prayer. */
object AlarmScheduler {

    const val ACTION_PRAYER_ALARM = "com.lemon.prayeralarm.ACTION_PRAYER_ALARM"
    const val ACTION_PRE_REMINDER = "com.lemon.prayeralarm.ACTION_PRE_REMINDER"
    const val EXTRA_PRAYER = "extra_prayer"
    const val EXTRA_LEAD_MINUTES = "extra_lead_minutes"
    private const val REQUEST_CODE_BASE = 5100
    private const val REQUEST_CODE_PRE_BASE = 5200

    /** A resolved alarm: when it rings, which day's prayer it belongs to, and what it counts from. */
    class Preview(val ringsAt: LocalDateTime, val date: LocalDate, val fromIqamah: Boolean)

    /** The moment an offset is measured from, and whether that moment is an iqamah. */
    private class Base(val at: LocalDateTime, val fromIqamah: Boolean)

    /** Everything needed to resolve times, gathered once per call. */
    private class Setup(
        val context: Context,
        val lat: Double,
        val lng: Double,
        val method: CalculationMethod,
        val madhab: Madhab,
        val iqamahRules: Map<Prayer, IqamahRule>
    )

    private fun setup(
        context: Context,
        methodIndex: Int? = null,
        madhabIndex: Int? = null,
        iqamahRules: Map<Prayer, IqamahRule>? = null
    ): Setup? {
        val prefs = PrefsRepository(context)
        if (!prefs.hasLocation) return null
        return Setup(
            context,
            prefs.latitude,
            prefs.longitude,
            CalculationMethod.forIndex(methodIndex ?: prefs.calculationMethodIndex),
            Madhab.fromIndex(madhabIndex ?: prefs.madhabIndex),
            iqamahRules ?: Prayer.obligatory().associateWith { prefs.iqamahRule(it) }
        )
    }

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return alarmManager.canScheduleExactAlarms()
    }

    /** Recomputes and (re)schedules alarms for every prayer based on current settings. */
    fun scheduleAll(context: Context) {
        val prefs = PrefsRepository(context)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val s = setup(context) ?: return
        val now = LocalDateTime.now()

        for (prayer in Prayer.values()) {
            val pendingIntent = buildPendingIntent(context, prayer)
            val mode = prefs.alarmMode(prayer)

            if (mode == AlarmMode.OFF) {
                alarmManager.cancel(pendingIntent)
                alarmManager.cancel(buildPreReminderIntent(context, prayer))
                continue
            }

            val next = nextOccurrence(
                s, prayer, prefs.offsetMinutes(prayer), prefs.alarmAnchor(prayer), now
            ) ?: continue

            setExactAlarm(alarmManager, next.ringsAt, pendingIntent)

            // Optional "prayer is coming up" nudge ahead of the alarm itself.
            val preIntent = buildPreReminderIntent(context, prayer)
            val lead = prefs.preReminderMinutes
            val preTime = next.ringsAt.minusMinutes(lead.toLong())
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

    /** The next alarm that will actually ring after [from], across every prayer that has one. */
    fun nextPrayer(
        context: Context,
        from: LocalDateTime = LocalDateTime.now()
    ): Pair<Prayer, LocalDateTime>? {
        val prefs = PrefsRepository(context)
        val s = setup(context) ?: return null
        return Prayer.values()
            .filter { prefs.alarmMode(it) != AlarmMode.OFF }
            .mapNotNull { prayer ->
                nextOccurrence(s, prayer, prefs.offsetMinutes(prayer), prefs.alarmAnchor(prayer), from)
                    ?.let { prayer to it.ringsAt }
            }
            .minByOrNull { it.second }
    }

    fun cancelAll(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (prayer in Prayer.values()) {
            alarmManager.cancel(buildPendingIntent(context, prayer))
            alarmManager.cancel(buildPreReminderIntent(context, prayer))
        }
    }

    private fun tzHoursFor(date: LocalDate): Double =
        PrayerTimesCalculator.utcOffsetHours(date, ZoneId.systemDefault())

    /** Full astronomical times for [date], including sunrise. No offsets, no timetable. */
    fun rawTimesForDate(context: Context, date: LocalDate): PrayerTimesCalculator.Times? {
        val s = setup(context) ?: return null
        return calculate(s, date)
    }

    private fun calculate(s: Setup, date: LocalDate): PrayerTimesCalculator.Times =
        PrayerTimesCalculator.calculate(date, s.lat, s.lng, tzHoursFor(date), s.method, s.madhab)

    /**
     * The prayer times for [date] as they should be shown: the mosque's where a timetable is
     * imported, otherwise the calculated ones. No alarm offsets.
     *
     * An offset delays the alarm, it does not move the prayer, so folding it into the shown time
     * would misreport when the prayer is.
     */
    fun prayerTimesForDate(context: Context, date: LocalDate): Map<Prayer, LocalTime>? {
        val s = setup(context) ?: return null
        val calculated = calculate(s, date)
        // Tahajjud belongs to a night rather than a day, so it has no place in a per-day map.
        return Prayer.values()
            .filter { it != Prayer.TAHAJJUD }
            .associateWith { prayer ->
                MosqueTimetable.prayerTime(context, prayer, date)?.toLocalTime()
                    ?: rawTimeFor(prayer, calculated)
            }
    }

    /** The iqamah times for [date]; empty when neither the user nor a timetable gives any. */
    fun iqamahTimesForDate(context: Context, date: LocalDate): Map<Prayer, LocalTime> {
        val s = setup(context) ?: return emptyMap()
        return Prayer.obligatory()
            .mapNotNull { prayer -> iqamahAt(s, prayer, date)?.let { prayer to it.toLocalTime() } }
            .toMap()
    }

    /** When [prayer] begins on [date]: the mosque's time where a timetable has one. */
    private fun prayerAt(s: Setup, prayer: Prayer, date: LocalDate): LocalDateTime =
        MosqueTimetable.prayerTime(s.context, prayer, date)
            ?: LocalDateTime.of(date, rawTimeFor(prayer, calculate(s, date)))

    /**
     * The iqamah for [prayer] on [date]. The user's own rule wins over a timetable: it is what
     * they entered for their mosque, and a timetable may well belong to a different one.
     */
    private fun iqamahAt(s: Setup, prayer: Prayer, date: LocalDate): LocalDateTime? {
        val rule = s.iqamahRules[prayer] ?: IqamahRule.NONE
        if (rule.isSet) return rule.resolve(prayerAt(s, prayer, date))
        return MosqueTimetable.iqamah(s.context, prayer, date)
    }

    /**
     * The first time the alarm for [prayer] rings after [now].
     *
     * The search starts from yesterday. Isha can run past midnight, and Tahajjud always does, so
     * the next ring can belong to a prayer dated the day before; starting from today missed the
     * last third of a night that had already begun.
     */
    private fun nextOccurrence(
        s: Setup,
        prayer: Prayer,
        offsetMinutes: Int,
        anchor: AlarmAnchor,
        now: LocalDateTime
    ): Preview? {
        for (dayOffset in -1..3) {
            val date = now.toLocalDate().plusDays(dayOffset.toLong())
            val base = baseFor(s, prayer, date, anchor) ?: continue
            // A negative offset moves the alarm before the prayer, which is why this compares
            // the adjusted time rather than the prayer time itself.
            val ringsAt = base.at.plusMinutes(offsetMinutes.toLong())
            if (ringsAt.isAfter(now)) return Preview(ringsAt, date, base.fromIqamah)
        }
        return null
    }

    /**
     * What an alarm for [prayer] on [date] is measured from.
     *
     * Asking for iqamah when none is known for that day falls back to the day's prayer time
     * rather than skipping the alarm. Tahajjud is always
     * calculated: it is two thirds of the way from Maghrib to the following Fajr, so it belongs
     * to a night and no timetable lists it.
     */
    private fun baseFor(s: Setup, prayer: Prayer, date: LocalDate, anchor: AlarmAnchor): Base? {
        if (prayer == Prayer.TAHAJJUD) {
            val tonight = calculate(s, date)
            val next = date.plusDays(1)
            val maghrib = LocalDateTime.of(date, tonight.maghrib)
            val fajr = LocalDateTime.of(next, calculate(s, next).fajr)
            val nightMinutes = Duration.between(maghrib, fajr).toMinutes()
            if (nightMinutes <= 0) return null
            return Base(maghrib.plusMinutes(nightMinutes * 2 / 3), fromIqamah = false)
        }
        if (anchor == AlarmAnchor.IQAMAH) {
            iqamahAt(s, prayer, date)?.let { return Base(it, fromIqamah = true) }
        }
        return Base(prayerAt(s, prayer, date), fromIqamah = false)
    }

    /**
     * The next time an alarm for [prayer] would ring, under settings the user may not have saved
     * yet. Lets the settings screen say "rings at 5:44 a.m. tomorrow, 10 min before iqamah"
     * while the controls are still being moved.
     */
    fun previewNextAlarm(
        context: Context,
        prayer: Prayer,
        offsetMinutes: Int,
        anchor: AlarmAnchor,
        methodIndex: Int,
        madhabIndex: Int,
        iqamahRules: Map<Prayer, IqamahRule>,
        from: LocalDateTime = LocalDateTime.now()
    ): Preview? {
        val s = setup(context, methodIndex, madhabIndex, iqamahRules) ?: return null
        return nextOccurrence(s, prayer, offsetMinutes, anchor, from)
    }

    /** When [prayer] begins on [date] under the given settings, before any offset. */
    fun previewPrayerTime(
        context: Context,
        prayer: Prayer,
        date: LocalDate,
        methodIndex: Int,
        madhabIndex: Int
    ): LocalTime? {
        val s = setup(context, methodIndex, madhabIndex) ?: return null
        return baseFor(s, prayer, date, AlarmAnchor.PRAYER_TIME)?.at?.toLocalTime()
    }

    /** The iqamah for [prayer] on [date] under settings that may not be saved yet. */
    fun previewIqamah(
        context: Context,
        prayer: Prayer,
        date: LocalDate,
        methodIndex: Int,
        madhabIndex: Int,
        iqamahRules: Map<Prayer, IqamahRule>
    ): LocalDateTime? {
        if (!prayer.isObligatory) return null
        val s = setup(context, methodIndex, madhabIndex, iqamahRules) ?: return null
        return iqamahAt(s, prayer, date)
    }

    /** The astronomical time for one prayer, before any user offset. */
    fun rawTimeFor(prayer: Prayer, times: PrayerTimesCalculator.Times): LocalTime = when (prayer) {
        Prayer.FAJR -> times.fajr
        Prayer.SUNRISE -> times.sunrise
        Prayer.DHUHR -> times.dhuhr
        Prayer.ASR -> times.asr
        Prayer.MAGHRIB -> times.maghrib
        Prayer.ISHA -> times.isha
        // Never reached: baseFor intercepts Tahajjud, which needs two days of times.
        Prayer.TAHAJJUD -> times.isha
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
