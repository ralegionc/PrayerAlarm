package com.lemon.prayeralarm

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
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
    private val fadeHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelfCleanly()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_SNOOZE) {
            // The full-screen alarm screen is often suppressed, so snooze has to be reachable
            // from the notification itself rather than only from that screen.
            val key = intent.getStringExtra(AlarmScheduler.EXTRA_PRAYER)
            val prayer = key?.let { Prayer.fromKey(it) } ?: currentPrayer ?: Prayer.FAJR
            SnoozeScheduler.snooze(this, prayer, SNOOZE_MINUTES)
            stopSelfCleanly()
            return START_NOT_STICKY
        }

        val prayerKey = intent?.getStringExtra(AlarmScheduler.EXTRA_PRAYER)
        val prayer = prayerKey?.let { Prayer.fromKey(it) } ?: Prayer.FAJR
        currentPrayer = prayer

        NotificationHelper.ensureChannels(this)
        startForeground(NotificationHelper.NOTIFICATION_ID_AZAN, buildNotification(prayer))
        acquireWakeLock()
        startPlayback(prayer)
        launchAlarmActivity(prayer)
        armSafetyTimeout()

        return START_STICKY
    }

    /**
     * Plays, in order of preference: the user's own recording for this prayer, the device's
     * alarm ringtone, then the bundled placeholder clip. A user's full adhan plays once; the
     * short fallback sounds loop until dismissed.
     */
    private fun startPlayback(prayer: Prayer) {
        mediaPlayer?.release()

        val userAzan = preparedPlayer(AzanSound.uriFor(this, prayer), loop = false)
        val player = userAzan
            ?: preparedPlayer(systemAlarmUri(), loop = true)
            ?: preparedBundledPlayer()

        if (userAzan != null) {
            // A one-shot recording would otherwise leave the service running silently until
            // the safety timeout; stop as soon as the adhan finishes on its own.
            userAzan.setOnCompletionListener { stopSelfCleanly() }
        }

        mediaPlayer = player
        player?.let {
            it.start()
            startFadeIn(it)
        }

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    /**
     * Ramps the volume up from silence so a 4am Fajr alarm does not start at full blast.
     * Steps are dropped if the player has since been replaced or released.
     */
    private fun startFadeIn(player: MediaPlayer) {
        fadeHandler.removeCallbacksAndMessages(null)
        val steps = 20
        val stepMillis = FADE_IN_MILLIS / steps
        try {
            player.setVolume(0f, 0f)
        } catch (e: IllegalStateException) {
            return
        }
        for (step in 1..steps) {
            fadeHandler.postDelayed({
                if (mediaPlayer === player) {
                    try {
                        val level = step / steps.toFloat()
                        player.setVolume(level, level)
                    } catch (e: IllegalStateException) {
                        // Released mid-fade; nothing left to raise.
                    }
                }
            }, stepMillis * step)
        }
    }

    private fun alarmAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

    private fun systemAlarmUri(): Uri? =
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

    /** Returns a prepared player, or null if [uri] is missing, unreadable or not decodable. */
    private fun preparedPlayer(uri: Uri?, loop: Boolean): MediaPlayer? {
        if (uri == null) return null
        val player = MediaPlayer()
        return try {
            player.setAudioAttributes(alarmAttributes())
            player.isLooping = loop
            player.setDataSource(this, uri)
            player.prepare()
            player
        } catch (e: Exception) {
            player.release()
            null
        }
    }

    private fun preparedBundledPlayer(): MediaPlayer? {
        val player = MediaPlayer()
        return try {
            player.setAudioAttributes(alarmAttributes())
            player.isLooping = true
            resources.openRawResourceFd(R.raw.azan_placeholder).use { afd ->
                player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            player.prepare()
            player
        } catch (e: Exception) {
            player.release()
            null
        }
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

        val snoozeIntent = Intent(this, AzanPlaybackService::class.java).apply {
            action = ACTION_SNOOZE
            putExtra(AlarmScheduler.EXTRA_PRAYER, prayer.storageKey)
        }
        val snoozePendingIntent = PendingIntent.getService(
            this, 1, snoozeIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
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
            .addAction(R.drawable.ic_notification, getString(R.string.alarm_snooze), snoozePendingIntent)
            .addAction(R.drawable.ic_notification, getString(R.string.alarm_dismiss), stopPendingIntent)
            // Keeps both buttons visible even when the shade shows the notification collapsed.
            .setStyle(
                androidx.core.app.NotificationCompat.BigTextStyle()
                    .bigText(getString(R.string.notification_playing_text))
            )
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
        fadeHandler.removeCallbacksAndMessages(null)
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
        private const val FADE_IN_MILLIS = 12_000L
        const val ACTION_STOP = "com.lemon.prayeralarm.ACTION_STOP_AZAN"
        const val ACTION_SNOOZE = "com.lemon.prayeralarm.ACTION_SNOOZE_AZAN"
        private const val SNOOZE_MINUTES = 5
    }
}
