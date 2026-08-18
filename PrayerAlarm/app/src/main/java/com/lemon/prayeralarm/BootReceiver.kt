package com.lemon.prayeralarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restores alarms after a device reboot or app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            NotificationHelper.ensureChannels(context)
            AlarmScheduler.scheduleAll(context)
            PrayerWidgetProvider.refreshAll(context)
        }
    }
}
