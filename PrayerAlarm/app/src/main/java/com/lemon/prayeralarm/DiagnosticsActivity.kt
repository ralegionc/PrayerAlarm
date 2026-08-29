package com.lemon.prayeralarm

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.lemon.prayeralarm.databinding.ActivityDiagnosticsBinding
import com.lemon.prayeralarm.databinding.ItemDiagnosticRowBinding
import java.time.format.DateTimeFormatter

/**
 * Shows why an alarm might not fire.
 *
 * Every one of these can be switched off by the system or the user without telling the app, and
 * each failure looks identical from the outside: the prayer passes in silence. Gathering them on
 * one screen turns "it did not ring" into something answerable, and each row offers the settings
 * page that fixes it.
 */
class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiagnosticsBinding

    private class Check(
        val title: String,
        val detail: String,
        val ok: Boolean,
        val actionLabel: String? = null,
        val action: (() -> Unit)? = null
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }

    override fun onResume() {
        super.onResume()
        // Re-read on every return: the user has usually just come back from a settings page.
        render(buildChecks())
    }

    private fun render(checks: List<Check>) {
        val problems = checks.count { !it.ok }
        binding.textSummary.text = if (problems == 0) {
            getString(R.string.diagnostics_all_ok)
        } else {
            getString(R.string.diagnostics_problems, problems)
        }

        binding.checkContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)
        // Problems first; a working item is not what someone opening this screen came to read.
        for (check in checks.sortedBy { it.ok }) {
            val row = ItemDiagnosticRowBinding.inflate(inflater, binding.checkContainer, false)
            row.rowStatusDot.text = if (check.ok) "✓" else "⚠"
            row.rowStatusDot.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (check.ok) R.color.widget_highlight else R.color.accent
                )
            )
            row.rowTitle.text = check.title
            row.rowDetail.text = check.detail
            val action = check.action
            if (!check.ok && action != null) {
                row.rowAction.visibility = android.view.View.VISIBLE
                row.rowAction.text = check.actionLabel ?: getString(R.string.diag_fix)
                row.rowAction.setOnClickListener { action() }
            }
            binding.checkContainer.addView(row.root)
        }
    }

    private fun buildChecks(): List<Check> = listOf(
        exactAlarmCheck(),
        batteryCheck(),
        notificationCheck(),
        fullScreenCheck(),
        locationCheck(),
        homeNetworkCheck(),
        nextAlarmCheck()
    )

    private fun exactAlarmCheck(): Check {
        val allowed = AlarmScheduler.canScheduleExact(this)
        return Check(
            title = "Exact alarms",
            detail = if (allowed) {
                "Allowed. Alarms fire at the exact prayer time."
            } else {
                "Blocked. Android may delay an alarm by minutes or longer."
            },
            ok = allowed,
            action = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).withPackage())
                }
            }
        )
    }

    private fun batteryCheck(): Check {
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val exempt = power?.isIgnoringBatteryOptimizations(packageName) ?: true
        return Check(
            title = "Battery optimisation",
            detail = if (exempt) {
                "Unrestricted. The app can wake up to ring."
            } else {
                "Restricted. Android may stop alarms while the phone is idle, " +
                    "which usually shows up as a missed Fajr."
            },
            ok = exempt,
            actionLabel = "Allow",
            action = {
                // The direct request shows a single system dialog; the settings list is the
                // fallback for devices that refuse it.
                open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).withPackage())
            }
        )
    }

    private fun notificationCheck(): Check {
        val enabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        val manager = getSystemService(NotificationManager::class.java)
        val channel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager?.getNotificationChannel(NotificationHelper.CHANNEL_AZAN)
        } else {
            null
        }
        val channelMuted = channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE
        val ok = enabled && !channelMuted
        return Check(
            title = "Notifications",
            detail = when {
                !enabled -> "Turned off. The azan cannot show its Dismiss and Snooze buttons."
                channelMuted -> "The azan channel is muted in system settings."
                else -> "Enabled, with the azan channel active."
            },
            ok = ok,
            action = {
                open(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                )
            }
        )
    }

    private fun fullScreenCheck(): Check {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return Check("Full screen alarm", "Available on this Android version.", true)
        }
        val manager = getSystemService(NotificationManager::class.java)
        val allowed = manager?.canUseFullScreenIntent() ?: true
        return Check(
            title = "Full screen alarm",
            detail = if (allowed) {
                "Allowed. The alarm screen opens over the lock screen."
            } else {
                "Blocked. You get a notification instead of the alarm screen. " +
                    "The notification still has Snooze and Dismiss."
            },
            ok = allowed,
            actionLabel = "Allow",
            action = { open(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).withPackage()) }
        )
    }

    private fun locationCheck(): Check {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val servicesOn = locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
            locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
        val prefs = PrefsRepository(this)
        val ok = prefs.hasLocation
        return Check(
            title = "Location",
            detail = when {
                !prefs.hasLocation && !granted -> "No permission and no saved location, so no times can be computed."
                !prefs.hasLocation -> "No location saved yet. Tap Update Location on the main screen."
                !granted -> "Using the saved location. Permission was revoked, so it cannot refresh if you move."
                !servicesOn -> "Saved location in use. Location services are off, so it cannot refresh."
                else -> "Saved: " + CityResolver.label(this)
            },
            ok = ok,
            action = { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).withPackage()) }
        )
    }

    private fun homeNetworkCheck(): Check {
        val prefs = PrefsRepository(this)
        val usesHomeMode = Prayer.values().any {
            prefs.alarmMode(it) == AlarmMode.LOUD_HOME_WIFI_ONLY
        }
        if (!usesHomeMode) {
            return Check("Home network", "Not used. No prayer is set to home Wi-Fi only.", true)
        }
        val hasHome = HomeNetwork.hasHomeNetwork(this)
        val atHome = HomeNetwork.isAtHome(this)
        val onWifi = HomeNetwork.isOnWifi(this)
        return Check(
            title = "Home network",
            detail = when {
                !hasHome -> "No home network saved, so the azan will stay silent everywhere."
                atHome -> "On your home network now. The azan will play."
                onWifi -> "On Wi-Fi, but not the one saved as home. The azan will stay silent."
                else -> "Not on Wi-Fi. The azan will stay silent until you are home."
            },
            ok = hasHome,
            actionLabel = "Open settings",
            action = { startActivity(Intent(this, SettingsActivity::class.java)) }
        )
    }

    private fun nextAlarmCheck(): Check {
        val next = AlarmScheduler.nextPrayer(this)
        if (next == null) {
            return Check("Next alarm", "Nothing scheduled. Check the location above.", false)
        }
        val (prayer, at) = next
        val mode = PrefsRepository(this).alarmMode(prayer)
        val name = NotificationHelper.prayerName(this, prayer)
        val when1 = at.format(DateTimeFormatter.ofPattern("EEE d MMM, h:mm a"))
        val modeText = when (mode) {
            AlarmMode.OFF -> "alarm off"
            AlarmMode.VIBRATE_ALWAYS -> "vibrate only"
            AlarmMode.LOUD_HOME_WIFI_ONLY -> "azan at home, vibrate elsewhere"
            AlarmMode.LOUD_EVERYWHERE -> "azan everywhere"
        }
        return Check(
            title = "Next alarm",
            detail = "$name, $when1 ($modeText)",
            ok = mode != AlarmMode.OFF
        )
    }

    private fun Intent.withPackage(): Intent =
        apply { data = Uri.fromParts("package", packageName, null) }

    /** Settings screens vary by device, so a missing one must not crash the diagnostics. */
    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).withPackage())
        }
    }
}
