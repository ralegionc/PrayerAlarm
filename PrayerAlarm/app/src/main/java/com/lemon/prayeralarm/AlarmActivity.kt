package com.lemon.prayeralarm

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.lemon.prayeralarm.databinding.ActivityAlarmBinding

/** Full-screen alarm UI shown over the lock screen when the azan starts playing. */
class AlarmActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlarmBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()

        binding = ActivityAlarmBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prayerKey = intent.getStringExtra(AlarmScheduler.EXTRA_PRAYER)
        val prayer = prayerKey?.let { Prayer.fromKey(it) } ?: Prayer.FAJR

        binding.textPrayerName.text = NotificationHelper.prayerName(this, prayer)
        binding.buttonDismiss.setOnClickListener { stopAzanAndFinish() }
        binding.buttonSnooze.setOnClickListener { snoozeAndFinish(prayer) }
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
    }

    private fun stopAzanAndFinish() {
        val stopIntent = Intent(this, AzanPlaybackService::class.java).apply {
            action = AzanPlaybackService.ACTION_STOP
        }
        startService(stopIntent)
        finish()
    }

    private fun snoozeAndFinish(prayer: Prayer) {
        val stopIntent = Intent(this, AzanPlaybackService::class.java).apply {
            action = AzanPlaybackService.ACTION_STOP
        }
        startService(stopIntent)
        SnoozeScheduler.snooze(this, prayer, 5)
        finish()
    }

    override fun onBackPressed() {
        // Prevent dismissing the alarm with the back button; require an explicit action.
    }
}
