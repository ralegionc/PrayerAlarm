package com.lemon.prayeralarm

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.RemoteViews
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.chrono.HijrahDate
import java.time.format.DateTimeFormatter

/**
 * Home-screen widget: the day's prayer times, sunrise, both calendar dates, the current
 * location, a live clock, and a countdown to the end of the current prayer window.
 *
 * The clock and the countdown are a TextClock and a count-down Chronometer, so they tick on
 * their own. Only the date-dependent text needs redrawing, which keeps the widget cheap: it
 * refreshes when alarms are rescheduled, when the app is opened, and on the system interval.
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
    }

    companion object {

        private val TIME = DateTimeFormatter.ofPattern("h:mm a")
        private val GREGORIAN = DateTimeFormatter.ofPattern("EEE d MMM yyyy")
        private val HIJRI = DateTimeFormatter.ofPattern("d MMMM yyyy")

        private val TIME_VIEW_IDS = intArrayOf(
            R.id.widgetFajr, R.id.widgetSunrise, R.id.widgetDhuhr,
            R.id.widgetAsr, R.id.widgetMaghrib, R.id.widgetIsha
        )

        /**
         * Redraws every placed widget. Safe when none exist, since the id array is then
         * empty, so schedulers can call it unconditionally.
         */
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(
                ComponentName(context, PrayerWidgetProvider::class.java)
            )
            for (id in ids) {
                updateWidget(context, manager, id)
            }
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
            views.setTextViewText(R.id.widgetDates, datesText(today))

            val times = AlarmScheduler.timesForDate(context, today)
            val raw = AlarmScheduler.rawTimesForDate(context, today)

            if (times == null || raw == null) {
                views.setTextViewText(
                    R.id.widgetCity,
                    context.getString(R.string.widget_no_location)
                )
                for (viewId in TIME_VIEW_IDS) {
                    views.setTextViewText(viewId, "")
                }
                clearCountdown(views)
                manager.updateAppWidget(id, views)
                return
            }

            views.setTextViewText(R.id.widgetCity, CityResolver.label(context))
            views.setTextViewText(R.id.widgetFajr, times.getValue(Prayer.FAJR).format(TIME))
            views.setTextViewText(R.id.widgetSunrise, raw.sunrise.format(TIME))
            views.setTextViewText(R.id.widgetDhuhr, times.getValue(Prayer.DHUHR).format(TIME))
            views.setTextViewText(R.id.widgetAsr, times.getValue(Prayer.ASR).format(TIME))
            views.setTextViewText(R.id.widgetMaghrib, times.getValue(Prayer.MAGHRIB).format(TIME))
            views.setTextViewText(R.id.widgetIsha, times.getValue(Prayer.ISHA).format(TIME))

            val now = LocalDateTime.now()
            val window = currentWindow(context, now)
            if (window == null) {
                clearCountdown(views)
            } else {
                val (label, endsAt) = window
                views.setTextViewText(R.id.widgetEndsLabel, label)
                val remaining = Duration.between(now, endsAt).toMillis().coerceAtLeast(0L)
                // A base in the future plus count-down mode makes the Chronometer tick itself,
                // so the timer stays live without waking the app every minute.
                views.setChronometer(
                    R.id.widgetCountdown,
                    SystemClock.elapsedRealtime() + remaining,
                    null,
                    true
                )
                views.setChronometerCountDown(R.id.widgetCountdown, true)
            }

            manager.updateAppWidget(id, views)
        }

        private fun clearCountdown(views: RemoteViews) {
            views.setTextViewText(R.id.widgetEndsLabel, "")
            views.setChronometer(R.id.widgetCountdown, SystemClock.elapsedRealtime(), null, false)
        }

        private fun datesText(today: LocalDate): String = try {
            today.format(GREGORIAN) + "   " + HijrahDate.from(today).format(HIJRI) + " AH"
        } catch (e: Exception) {
            today.format(GREGORIAN)
        }

        /**
         * The prayer window [now] falls inside, as a label and the instant it ends.
         *
         * Fajr closes at sunrise rather than at Dhuhr, and the stretch between sunrise and
         * Dhuhr belongs to no prayer at all, so that one counts down to Dhuhr beginning
         * instead of to a window ending.
         */
        private fun currentWindow(
            context: Context,
            now: LocalDateTime
        ): Pair<String, LocalDateTime>? {
            val today = now.toLocalDate()
            val times = AlarmScheduler.timesForDate(context, today) ?: return null
            val raw = AlarmScheduler.rawTimesForDate(context, today) ?: return null

            fun at(time: LocalTime) = LocalDateTime.of(today, time)
            fun ends(prayer: Prayer) = context.getString(
                R.string.widget_ends_in,
                NotificationHelper.prayerName(context, prayer)
            )

            val fajr = at(times.getValue(Prayer.FAJR))
            val sunrise = at(raw.sunrise)
            val dhuhr = at(times.getValue(Prayer.DHUHR))
            val asr = at(times.getValue(Prayer.ASR))
            val maghrib = at(times.getValue(Prayer.MAGHRIB))
            val isha = at(times.getValue(Prayer.ISHA))

            return when {
                // Before dawn we are still inside last night's Isha window.
                now.isBefore(fajr) -> ends(Prayer.ISHA) to fajr
                now.isBefore(sunrise) -> ends(Prayer.FAJR) to sunrise
                now.isBefore(dhuhr) -> context.getString(
                    R.string.widget_starts_in,
                    NotificationHelper.prayerName(context, Prayer.DHUHR)
                ) to dhuhr
                now.isBefore(asr) -> ends(Prayer.DHUHR) to asr
                now.isBefore(maghrib) -> ends(Prayer.ASR) to maghrib
                now.isBefore(isha) -> ends(Prayer.MAGHRIB) to isha
                else -> {
                    val tomorrow = AlarmScheduler.timesForDate(context, today.plusDays(1))
                        ?: return null
                    ends(Prayer.ISHA) to
                        LocalDateTime.of(today.plusDays(1), tomorrow.getValue(Prayer.FAJR))
                }
            }
        }
    }
}
