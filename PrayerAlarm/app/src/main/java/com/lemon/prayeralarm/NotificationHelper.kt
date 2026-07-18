package com.lemon.prayeralarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat

object NotificationHelper {
    const val CHANNEL_AZAN = "azan_alarms"
    const val CHANNEL_SILENT = "silent_reminders"
    const val NOTIFICATION_ID_AZAN = 5501
    const val NOTIFICATION_ID_VIBRATE = 5502

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val azanChannel = NotificationChannel(
            CHANNEL_AZAN,
            context.getString(R.string.notification_channel_azan),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.notification_channel_azan_desc)
            setSound(null, null) // playback is handled manually by the service so it can loop/stop
            enableVibration(true)
        }

        val silentChannel = NotificationChannel(
            CHANNEL_SILENT,
            context.getString(R.string.notification_channel_silent),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.notification_channel_silent_desc)
            setSound(null, null)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 400)
        }

        manager.createNotificationChannel(azanChannel)
        manager.createNotificationChannel(silentChannel)
    }

    fun showVibrateReminder(context: Context, prayer: Prayer) {
        ensureChannels(context)
        vibrate(context)

        val openIntent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_SILENT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_playing_title, prayerName(context, prayer)))
            .setContentText(context.getString(R.string.notification_playing_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID_VIBRATE, notification)
    }

    private fun vibrate(context: Context) {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        val pattern = longArrayOf(0, 400, 200, 400, 200, 400)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }

    fun prayerName(context: Context, prayer: Prayer): String = when (prayer) {
        Prayer.FAJR -> context.getString(R.string.prayer_fajr)
        Prayer.DHUHR -> context.getString(R.string.prayer_dhuhr)
        Prayer.ASR -> context.getString(R.string.prayer_asr)
        Prayer.MAGHRIB -> context.getString(R.string.prayer_maghrib)
        Prayer.ISHA -> context.getString(R.string.prayer_isha)
    }
}
