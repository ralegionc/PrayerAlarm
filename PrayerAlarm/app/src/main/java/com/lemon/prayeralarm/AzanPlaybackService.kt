package com.lemon.prayeralarm

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.CountDownTimer
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * Foreground service that loudly plays the azan sound and shows a full-screen
 * alarm UI, similar to a normal Android alarm clock. Stops on user action or
 * automatically after a safety timeout.
 */
class AzanPlaybackService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var safetyTimer: CountDownTimer? = null
    private var currentPrayer: Prayer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelfCleanly()
            return START_NOT_STICKY
        }

        val prayerKey = intent?.getStringExtra(AlarmScheduler.EXTRA_PRAYER)
        val prayer = prayerKey?.let { Prayer.fromKey(it) } ?: Prayer.FAJR
        currentPrayer = prayer

        NotificationHelper.ensureChannels(this)
        startForeground(NotificationHelper.NOTIFICATION_ID_AZAN, buildNotification(prayer))
        acquireWakeLock()
        startPlayback()
        launchAlarmActivity(prayer)
        armSafetyTimeout()

        return START_STICKY
    }

    private fun startPlayback() {
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            isLooping = true
            try {
                val afd = resources.openRawResourceFd(R.raw.azan_placeholder)
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                prepare()
                start()
            } catch (e: Exception) {
                // If the audio resource is missing/corrupt, fail silently rather than crash.
            }
        }

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    private fun launchAlarmActivity(prayer: Prayer) {
        val intent = Intent(this, AlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(AlarmScheduler.EXTRA_PRAYER, prayer.storageKey)
        }
        startActivity(intent)
    }

    private fun buildNotification(prayer: Prayer): android.app.Notification {
        val stopIntent = Intent(this, AzanPlaybackService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, AlarmActivity::class.java).apply {
            putExtra(AlarmScheduler.EXTRA_PRAYER, prayer.storageKey)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NotificationHelper.CHANNEL_AZAN)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_playing_title, NotificationHelper.prayerName(this, prayer)))
            .setContentText(getString(R.string.notification_playing_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .addAction(0, getString(R.string.alarm_dismiss), stopPendingIntent)
            .build()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "PrayerAlarm:AzanWakeLock"
        ).apply { acquire(6 * 60 * 1000L) }
    }

    private fun armSafetyTimeout() {
        safetyTimer?.cancel()
        safetyTimer = object : CountDownTimer(5 * 60 * 1000L, 60_000L) {
            override fun onTick(millisUntilFinished: Long) {}
            override fun onFinish() {
                stopSelfCleanly()
            }
        }.start()
    }

    private fun stopSelfCleanly() {
        safetyTimer?.cancel()
        mediaPlayer?.let {
            try {
                if (it.isPlaying) it.stop()
            } catch (e: IllegalStateException) {
                // ignore
            }
            it.release()
        }
        mediaPlayer = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopSelfCleanly()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.lemon.prayeralarm.ACTION_STOP_AZAN"
    }
}
