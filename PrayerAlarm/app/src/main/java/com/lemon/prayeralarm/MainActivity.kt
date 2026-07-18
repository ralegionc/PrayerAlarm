package com.lemon.prayeralarm

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.lemon.prayeralarm.databinding.ActivityMainBinding
import com.lemon.prayeralarm.databinding.ItemPrayerRowBinding
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PrefsRepository

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            fetchLocationAndRefresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PrefsRepository(this)
        NotificationHelper.ensureChannels(this)

        binding.buttonUpdateLocation.setOnClickListener { requestPermissionsAndLocation() }
        binding.buttonSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.textExactAlarmWarning.setOnClickListener { openExactAlarmSettings() }

        if (!prefs.hasLocation) {
            requestPermissionsAndLocation()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun requestPermissionsAndLocation() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun fetchLocationAndRefresh() {
        if (!LocationHelper.hasPermission(this)) {
            refreshUi()
            return
        }
        LocationHelper.requestLocation(this) { location ->
            runOnUiThread {
                if (location != null) {
                    prefs.setLocation(location.latitude, location.longitude)
                    AlarmScheduler.scheduleAll(this)
                }
                refreshUi()
            }
        }
    }

    private fun refreshUi() {
        if (!prefs.hasLocation) {
            binding.textLocation.text = getString(R.string.label_no_location)
            binding.textNextPrayer.text = ""
            binding.prayerListContainer.removeAllViews()
            return
        }

        binding.textLocation.text = getString(R.string.label_location, prefs.latitude, prefs.longitude)

        val today = LocalDate.now()
        val todayTimes = AlarmScheduler.timesForDate(this, today)
        binding.prayerListContainer.removeAllViews()

        if (todayTimes != null) {
            val timeFormatter = DateTimeFormatter.ofPattern("h:mm a")
            val inflater = LayoutInflater.from(this)

            for (prayer in Prayer.values()) {
                val rowBinding = ItemPrayerRowBinding.inflate(inflater, binding.prayerListContainer, false)
                rowBinding.rowPrayerName.text = NotificationHelper.prayerName(this, prayer)
                rowBinding.rowPrayerTime.text = todayTimes.getValue(prayer).format(timeFormatter)
                binding.prayerListContainer.addView(rowBinding.root)
            }

            binding.textNextPrayer.text = describeNextPrayer(today, todayTimes)
        }

        val needsExactAlarmPermission =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !AlarmScheduler.canScheduleExact(this)
        binding.textExactAlarmWarning.visibility = if (needsExactAlarmPermission) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun describeNextPrayer(today: LocalDate, todayTimes: Map<Prayer, java.time.LocalTime>): String {
        val now = LocalDateTime.now()
        var best: Pair<Prayer, LocalDateTime>? = null

        for (prayer in Prayer.values()) {
            val dt = LocalDateTime.of(today, todayTimes.getValue(prayer))
            if (dt.isAfter(now) && (best == null || dt.isBefore(best!!.second))) {
                best = prayer to dt
            }
        }

        if (best == null) {
            val tomorrowTimes = AlarmScheduler.timesForDate(this, today.plusDays(1)) ?: return ""
            val dt = LocalDateTime.of(today.plusDays(1), tomorrowTimes.getValue(Prayer.FAJR))
            best = Prayer.FAJR to dt
        }

        val (prayer, dt) = best!!
        val duration = Duration.between(now, dt)
        val hours = duration.toHours()
        val minutes = duration.toMinutes() % 60
        val countdown = if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
        return getString(R.string.label_next_prayer, NotificationHelper.prayerName(this, prayer), countdown)
    }

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = android.net.Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }
}
