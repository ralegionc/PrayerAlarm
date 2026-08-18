package com.lemon.prayeralarm

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.lemon.prayeralarm.databinding.ActivityMainBinding
import com.lemon.prayeralarm.databinding.ItemPrayerRowBinding
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.chrono.HijrahDate
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
        binding.buttonQibla.setOnClickListener {
            startActivity(Intent(this, QiblaActivity::class.java))
        }
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
        // Only a foreground caller can read the SSID; remember it for background alarms.
        WifiHelper.cacheCurrentSsid(this)
        if (prefs.hasLocation && prefs.cityName.isBlank()) {
            CityResolver.refresh(this, prefs.latitude, prefs.longitude)
        }
        // App updates and force-stops clear pending alarms, and nothing else re-arms them,
        // so re-schedule on every visit rather than only after a location fix.
        AlarmScheduler.scheduleAll(this)
        PrayerWidgetProvider.refreshAll(this)
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
                    CityResolver.refresh(this, location.latitude, location.longitude)
                    AlarmScheduler.scheduleAll(this)
                }
                refreshUi()
            }
        }
    }

    private fun refreshUi() {
        binding.textHijriDate.text = hijriDateText()
        binding.textGregorianDate.text =
            LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy"))

        if (!prefs.hasLocation) {
            binding.textLocation.text = getString(R.string.label_no_location)
            binding.textNextPrayerName.text = ""
            binding.textNextPrayerTime.text = ""
            binding.textNextCountdown.text = ""
            binding.textLastThird.text = ""
            binding.prayerListContainer.removeAllViews()
            return
        }

        // Prefer the resolved place name; CityResolver falls back to coordinates on its own.
        binding.textLocation.text = CityResolver.label(this)

        val today = LocalDate.now()
        val todayTimes = AlarmScheduler.prayerTimesForDate(this, today)
        binding.prayerListContainer.removeAllViews()

        if (todayTimes != null) {
            val timeFormatter = DateTimeFormatter.ofPattern("h:mm a")
            val inflater = LayoutInflater.from(this)

            val next = findNextPrayer(today, todayTimes)

            // Sunrise is not a prayer, but it closes the Fajr window, so it belongs in the list.
            val sunrise = AlarmScheduler.rawTimesForDate(this, today)?.sunrise
            for (prayer in Prayer.values()) {
                addRow(
                    inflater,
                    NotificationHelper.prayerName(this, prayer),
                    todayTimes.getValue(prayer).format(timeFormatter),
                    highlight = prayer == next?.first
                )
                if (prayer == Prayer.FAJR && sunrise != null) {
                    addRow(
                        inflater,
                        getString(R.string.prayer_sunrise),
                        sunrise.format(timeFormatter),
                        highlight = false
                    )
                }
            }

            showNextPrayer(next, timeFormatter)
            binding.textLastThird.text = lastThirdText(today, timeFormatter)
        }

        val needsExactAlarmPermission =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !AlarmScheduler.canScheduleExact(this)
        binding.textExactAlarmWarning.visibility = if (needsExactAlarmPermission) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun addRow(
        inflater: LayoutInflater,
        name: String,
        time: String,
        highlight: Boolean
    ) {
        val rowBinding = ItemPrayerRowBinding.inflate(inflater, binding.prayerListContainer, false)
        rowBinding.rowPrayerName.text = name
        rowBinding.rowPrayerTime.text = time
        if (highlight) {
            rowBinding.rowRoot.setBackgroundResource(R.drawable.row_next_bg)
            val tint = ContextCompat.getColor(this, R.color.widget_highlight)
            rowBinding.rowPrayerName.setTextColor(tint)
            rowBinding.rowPrayerTime.setTextColor(tint)
        }
        binding.prayerListContainer.addView(rowBinding.root)
    }

    /** Fills the header block with the upcoming prayer, its time, and how long until it. */
    private fun showNextPrayer(
        next: Pair<Prayer, LocalDateTime>?,
        formatter: DateTimeFormatter
    ) {
        if (next == null) {
            binding.textNextPrayerName.text = ""
            binding.textNextPrayerTime.text = ""
            binding.textNextCountdown.text = ""
            return
        }
        val (prayer, at) = next
        binding.textNextPrayerName.text = NotificationHelper.prayerName(this, prayer)
        binding.textNextPrayerTime.text = at.toLocalTime().format(formatter)
        val duration = Duration.between(LocalDateTime.now(), at)
        val hours = duration.toHours()
        val minutes = duration.toMinutes() % 60
        val countdown = if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
        binding.textNextCountdown.text = getString(R.string.label_in, countdown)
    }

    /** The next prayer still to come, rolling over to tomorrow's Fajr once Isha has passed. */
    private fun findNextPrayer(
        today: LocalDate,
        todayTimes: Map<Prayer, java.time.LocalTime>
    ): Pair<Prayer, LocalDateTime>? {
        val now = LocalDateTime.now()
        var best: Pair<Prayer, LocalDateTime>? = null
        for (prayer in Prayer.values()) {
            val at = LocalDateTime.of(today, todayTimes.getValue(prayer))
            if (at.isAfter(now) && (best == null || at.isBefore(best.second))) {
                best = prayer to at
            }
        }
        if (best != null) return best
        val tomorrow = AlarmScheduler.prayerTimesForDate(this, today.plusDays(1)) ?: return null
        return Prayer.FAJR to
            LocalDateTime.of(today.plusDays(1), tomorrow.getValue(Prayer.FAJR))
    }

    /** Today's date in the Islamic calendar, via the JDK's Umm al-Qura implementation. */
    private fun hijriDateText(): String = try {
        val hijri = HijrahDate.from(LocalDate.now())
        getString(R.string.label_hijri_date, hijri.format(DateTimeFormatter.ofPattern("d MMMM yyyy")))
    } catch (e: Exception) {
        ""
    }

    /**
     * Start of the final third of the night, measured from Maghrib to the following Fajr —
     * the window traditionally used for Tahajjud.
     */
    private fun lastThirdText(today: LocalDate, formatter: DateTimeFormatter): String {
        val tonight = AlarmScheduler.rawTimesForDate(this, today) ?: return ""
        val tomorrow = AlarmScheduler.rawTimesForDate(this, today.plusDays(1)) ?: return ""
        val maghrib = LocalDateTime.of(today, tonight.maghrib)
        val fajr = LocalDateTime.of(today.plusDays(1), tomorrow.fajr)
        val nightMinutes = Duration.between(maghrib, fajr).toMinutes()
        if (nightMinutes <= 0) return ""
        val start = maghrib.plusMinutes(nightMinutes * 2 / 3)
        return getString(R.string.label_last_third, start.format(formatter))
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
