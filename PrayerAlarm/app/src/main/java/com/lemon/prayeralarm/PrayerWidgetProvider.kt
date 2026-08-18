package com.lemon.prayeralarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.chrono.HijrahDate
import java.time.format.DateTimeFormatter

/**
 * Home-screen widget: a live clock, both calendar dates, the resolved city, the time the
 * current prayer window ends, and a strip of the day's times with the active prayer picked out.
 *
 * The clock is a TextClock so it ticks unattended. Everything else changes only when a prayer
 * window turns over, so instead of polling, the widget schedules a single refresh for the exact
 * moment the current window closes.
 */
class PrayerWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) {
            updateWidget(context, appWidgetManager, id)
        }
        scheduleBoundaryRefresh(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            refreshAll(context)
        }
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        // Last widget removed; stop waking up for it.
        alarmManager(context).cancel(refreshIntent(context))
    }

    companion object {

        private const val ACTION_REFRESH = "com.lemon.prayeralarm.ACTION_WIDGET_REFRESH"
        private const val REQUEST_CODE_REFRESH = 5300

        private val TIME = DateTimeFormatter.ofPattern("h:mm a")
        private val GREGORIAN = DateTimeFormatter.ofPattern("MMMM d, yyyy")
        private val HIJRI = DateTimeFormatter.ofPattern("MMMM d, yyyy")

        /** Label view, time view, and the prayer each column stands for. */
        private val COLUMNS = listOf(
            Triple(R.id.widgetFajrLabel, R.id.widgetFajr, Prayer.FAJR),
            Triple(R.id.widgetSunriseLabel, R.id.widgetSunrise, null),
            Triple(R.id.widgetDhuhrLabel, R.id.widgetDhuhr, Prayer.DHUHR),
            Triple(R.id.widgetAsrLabel, R.id.widgetAsr, Prayer.ASR),
            Triple(R.id.widgetMaghribLabel, R.id.widgetMaghrib, Prayer.MAGHRIB),
            Triple(R.id.widgetIshaLabel, R.id.widgetIsha, Prayer.ISHA)
        )

        /** Marks the sunrise column, which is informational rather than a prayer. */
        private const val SUNRISE_COLUMN = 1

        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(
                ComponentName(context, PrayerWidgetProvider::class.java)
            )
            for (id in ids) {
                updateWidget(context, manager, id)
            }
            if (ids.isNotEmpty()) scheduleBoundaryRefresh(context)
        }

        private fun updateWidget(context: Context, manager: AppWidgetManager, id: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_prayer)
            views.setOnClickPendingIntent(
                R.id.widgetRoot,
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

            val today = LocalDate.now()
            views.setTextViewText(R.id.widgetGregorian, today.format(GREGORIAN))
            views.setTextViewText(R.id.widgetHijri, hijriText(today))

            val times = AlarmScheduler.timesForDate(context, today)
            val raw = AlarmScheduler.rawTimesForDate(context, today)

            if (times == null || raw == null) {
                views.setTextViewText(
                    R.id.widgetCity,
                    context.getString(R.string.widget_no_location)
                )
                views.setTextViewText(R.id.widgetEndsTime, "")
                for ((labelId, timeId, _) in COLUMNS) {
                    views.setTextViewText(timeId, "")
                    views.setTextColor(labelId, color(context, R.color.widget_on_footer))
                }
                manager.updateAppWidget(id, views)
                return
            }

            views.setTextViewText(R.id.widgetCity, CityResolver.label(context))

            val values = listOf(
                times.getValue(Prayer.FAJR),
                raw.sunrise,
                times.getValue(Prayer.DHUHR),
                times.getValue(Prayer.ASR),
                times.getValue(Prayer.MAGHRIB),
                times.getValue(Prayer.ISHA)
            )

            val active = activeColumn(context, LocalDateTime.now())
            val normal = color(context, R.color.widget_on_footer)
            val highlight = color(context, R.color.widget_highlight)

            COLUMNS.forEachIndexed { index, (labelId, timeId, prayer) ->
                val isActive = index == active
                val label = if (prayer == null) {
                    context.getString(R.string.prayer_sunrise)
                } else {
                    NotificationHelper.prayerName(context, prayer)
                }
                views.setTextViewText(labelId, if (isActive) bold(label) else label)
                views.setTextViewText(
                    timeId,
                    values[index].format(TIME).let { if (isActive) bold(it) else it }
                )
                views.setTextColor(labelId, if (isActive) highlight else normal)
                views.setTextColor(timeId, if (isActive) highlight else normal)
            }

            val endsAt = windowEnd(context, LocalDateTime.now())
            views.setTextViewText(
                R.id.widgetEndsTime,
                endsAt?.toLocalTime()?.format(TIME) ?: ""
            )

            manager.updateAppWidget(id, views)
        }

        private fun bold(text: String): CharSequence = SpannableString(text).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        private fun color(context: Context, res: Int) = ContextCompat.getColor(context, res)

        private fun hijriText(today: LocalDate): String = try {
            HijrahDate.from(today).format(HIJRI) + " AH"
        } catch (e: Exception) {
            ""
        }

        /**
         * Islamic midnight: the midpoint between Maghrib and the following Fajr, which is the
         * preferred close of the Isha window rather than clock midnight.
         */
        private fun islamicMidnight(
            maghrib: LocalDateTime,
            nextFajr: LocalDateTime
        ): LocalDateTime = maghrib.plusMinutes(Duration.between(maghrib, nextFajr).toMinutes() / 2)

        /** Index into [COLUMNS] of the prayer currently in effect, or -1 if unknown. */
        private fun activeColumn(context: Context, now: LocalDateTime): Int {
            val b = boundaries(context, now.toLocalDate()) ?: return -1
            return when {
                now.isBefore(b.fajr) -> COLUMNS.indexOfFirst { it.third == Prayer.ISHA }
                now.isBefore(b.sunrise) -> 0
                now.isBefore(b.dhuhr) -> SUNRISE_COLUMN
                now.isBefore(b.asr) -> 2
                now.isBefore(b.maghrib) -> 3
                now.isBefore(b.isha) -> 4
                else -> 5
            }
        }

        /** When the currently active window closes. */
        private fun windowEnd(context: Context, now: LocalDateTime): LocalDateTime? {
            val b = boundaries(context, now.toLocalDate()) ?: return null
            return when {
                // Before dawn we are still inside last night's Isha, which runs out at Fajr.
                now.isBefore(b.fajr) -> b.fajr
                now.isBefore(b.sunrise) -> b.sunrise
                now.isBefore(b.dhuhr) -> b.dhuhr
                now.isBefore(b.asr) -> b.asr
                now.isBefore(b.maghrib) -> b.maghrib
                now.isBefore(b.isha) -> b.isha
                else -> b.nextFajr?.let { islamicMidnight(b.maghrib, it) }
            }
        }

        private class Boundaries(
            val fajr: LocalDateTime,
            val sunrise: LocalDateTime,
            val dhuhr: LocalDateTime,
            val asr: LocalDateTime,
            val maghrib: LocalDateTime,
            val isha: LocalDateTime,
            val nextFajr: LocalDateTime?
        )

        private fun boundaries(context: Context, today: LocalDate): Boundaries? {
            val times = AlarmScheduler.timesForDate(context, today) ?: return null
            val raw = AlarmScheduler.rawTimesForDate(context, today) ?: return null
            fun at(time: LocalTime) = LocalDateTime.of(today, time)
            val tomorrow = AlarmScheduler.timesForDate(context, today.plusDays(1))
            return Boundaries(
                fajr = at(times.getValue(Prayer.FAJR)),
                sunrise = at(raw.sunrise),
                dhuhr = at(times.getValue(Prayer.DHUHR)),
                asr = at(times.getValue(Prayer.ASR)),
                maghrib = at(times.getValue(Prayer.MAGHRIB)),
                isha = at(times.getValue(Prayer.ISHA)),
                nextFajr = tomorrow?.let {
                    LocalDateTime.of(today.plusDays(1), it.getValue(Prayer.FAJR))
                }
            )
        }

        /**
         * Wakes the widget once, exactly when the current window closes, instead of polling.
         * The system's own 30-minute refresh would otherwise leave the highlight and the end
         * time stale for up to half an hour after a prayer comes in.
         */
        private fun scheduleBoundaryRefresh(context: Context) {
            val next = windowEnd(context, LocalDateTime.now()) ?: return
            val millis = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val manager = alarmManager(context)
            val pending = refreshIntent(context)
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC, millis, pending)
            } catch (e: SecurityException) {
                manager.set(AlarmManager.RTC, millis, pending)
            }
        }

        private fun alarmManager(context: Context) =
            context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        private fun refreshIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_REFRESH,
            Intent(context, PrayerWidgetProvider::class.java).setAction(ACTION_REFRESH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
