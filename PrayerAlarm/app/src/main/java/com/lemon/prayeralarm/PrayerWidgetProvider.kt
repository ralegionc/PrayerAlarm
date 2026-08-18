package com.lemon.prayeralarm

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Home-screen widget showing the next prayer and how long until it. */
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

        /**
         * Redraws every placed widget. Safe to call when none exist — the id array is simply
         * empty — so schedulers can fire it unconditionally.
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
            val next = AlarmScheduler.nextPrayer(context)

            if (next == null) {
                views.setTextViewText(
                    R.id.widgetPrayer,
                    context.getString(R.string.widget_no_location)
                )
                views.setTextViewText(R.id.widgetCountdown, "")
            } else {
                val (prayer, at) = next
                views.setTextViewText(
                    R.id.widgetPrayer,
                    context.getString(
                        R.string.widget_next_prayer,
                        NotificationHelper.prayerName(context, prayer),
                        at.format(DateTimeFormatter.ofPattern("h:mm a"))
                    )
                )
                val remaining = Duration.between(LocalDateTime.now(), at)
                val hours = remaining.toHours()
                val minutes = remaining.toMinutes() % 60
                val spread = if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
                views.setTextViewText(
                    R.id.widgetCountdown,
                    context.getString(R.string.widget_countdown, spread)
                )
            }

            views.setOnClickPendingIntent(
                R.id.widgetRoot,
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

            manager.updateAppWidget(id, views)
        }
    }
}
