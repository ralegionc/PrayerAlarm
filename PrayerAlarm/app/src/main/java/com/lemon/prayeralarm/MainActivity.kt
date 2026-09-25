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
        binding.buttonDiagnostics.setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.textExactAlarmWarning.setOnClickListener { openExactAlarmSettings() }

        if (!prefs.hasLocation) {
            requestPermissionsAndLocation()
        }
    }

    override fun onResume() {
        super.onResume()
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
            clearGrid()
            return
        }

        // Prefer the resolved place name; CityResolver falls back to coordinates on its own.
        binding.textLocation.text = CityResolver.label(this)

        val today = LocalDate.now()
        val todayTimes = AlarmScheduler.prayerTimesForDate(this, today)

        if (todayTimes == null) {
            clearGrid()
        } else {
            val timeFormatter = DateTimeFormatter.ofPattern("h:mm a")
            val next = findNextPrayer(today, todayTimes)
            fillGrid(todayTimes, next?.first, timeFormatter)
            showNextPrayer(next, timeFormatter)
            binding.textLastThird.text = lastThirdText(today, timeFormatter)
        }

        val needsExactAlarmPermission =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !AlarmScheduler.canScheduleExact(this)
        binding.textExactAlarmWarning.visibility = if (needsExactAlarmPermission) android.view.View.VISIBLE else android.view.View.GONE
    }

    /** The six grid cells, in reading order, paired with the prayer each one shows. */
    private fun gridCells(): List<Triple<Prayer, android.view.View, Pair<android.widget.TextView, android.widget.TextView>>> =
        listOf(
            Triple(Prayer.FAJR, binding.cellFajr, binding.nameFajr to binding.timeFajr),
            Triple(Prayer.SUNRISE, binding.cellSunrise, binding.nameSunrise to binding.timeSunrise),
            Triple(Prayer.DHUHR, binding.cellDhuhr, binding.nameDhuhr to binding.timeDhuhr),
            Triple(Prayer.ASR, binding.cellAsr, binding.nameAsr to binding.timeAsr),
            Triple(Prayer.MAGHRIB, binding.cellMaghrib, binding.nameMaghrib to binding.timeMaghrib),
            Triple(Prayer.ISHA, binding.cellIsha, binding.nameIsha to binding.timeIsha)
        )

    private fun fillGrid(
        times: Map<Prayer, java.time.LocalTime>,
        highlight: Prayer?,
        formatter: DateTimeFormatter
    ) {
        val muted = ContextCompat.getColor(this, R.color.muted)
        val normal = ContextCompat.getColor(this, R.color.on_surface)
        val tint = ContextCompat.getColor(this, R.color.widget_highlight)

        for ((prayer, cell, labels) in gridCells()) {
            val (name, time) = labels
            time.text = times[prayer]?.format(formatter).orEmpty()
            val isNext = prayer == highlight
            cell.setBackgroundResource(if (isNext) R.drawable.row_next_bg else 0)
            name.setTextColor(if (isNext) tint else muted)
            time.setTextColor(if (isNext) tint else normal)
        }
    }

    private fun clearGrid() {
        for ((_, cell, labels) in gridCells()) {
            labels.second.text = ""
            cell.setBackgroundResource(0)
        }
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
        // Only the five daily prayers count as "next prayer"; sunrise is not one.
        for (prayer in Prayer.obligatory()) {
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
