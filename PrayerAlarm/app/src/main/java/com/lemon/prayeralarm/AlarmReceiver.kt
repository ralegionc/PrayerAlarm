package com.lemon.prayeralarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Fires when a scheduled prayer alarm time is reached. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prayerKey = intent.getStringExtra(AlarmScheduler.EXTRA_PRAYER)
        val prayer = prayerKey?.let { Prayer.fromKey(it) }

        // A lead-time nudge only posts a notification; it must not touch the real alarms.
        if (intent.action == AlarmScheduler.ACTION_PRE_REMINDER) {
            if (prayer != null) {
                val lead = PrefsRepository(context).preReminderMinutes
                if (lead > 0) NotificationHelper.showPreReminder(context, prayer, lead)
            }
            return
        }

        if (prayer != null) {
            val prefs = PrefsRepository(context)
            val mode = prefs.alarmMode(prayer)

            val shouldPlayLoud = when (mode) {
                AlarmMode.LOUD_EVERYWHERE -> true
                AlarmMode.LOUD_HOME_WIFI_ONLY -> WifiHelper.isConnectedToHomeNetwork(context, prefs.homeSsid)
                AlarmMode.VIBRATE_ALWAYS, AlarmMode.OFF -> false
            }

            if (mode != AlarmMode.OFF) {
                if (shouldPlayLoud) {
                    val serviceIntent = Intent(context, AzanPlaybackService::class.java).apply {
                        putExtra(AlarmScheduler.EXTRA_PRAYER, prayer.storageKey)
                    }
                    ContextCompat.startForegroundService(context, serviceIntent)
                } else {
                    NotificationHelper.showVibrateReminder(context, prayer)
                }
            }
        }

        // Recompute and re-arm alarms for the following day.
        AlarmScheduler.scheduleAll(context)
        PrayerWidgetProvider.refreshAll(context)
    }
}
