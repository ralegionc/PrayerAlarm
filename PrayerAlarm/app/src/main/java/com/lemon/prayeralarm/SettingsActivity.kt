package com.lemon.prayeralarm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.widget.AdapterView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.lemon.prayeralarm.databinding.ActivitySettingsBinding
import com.lemon.prayeralarm.databinding.ItemPrayerSettingsRowBinding
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: PrefsRepository
    private val rowBindings = mutableMapOf<Prayer, ItemPrayerSettingsRowBinding>()

    private val requestBackgroundLocation =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                Toast.makeText(this, R.string.settings_bg_location_ok, Toast.LENGTH_LONG).show()
            } else {
                // From Android 11 the system stops showing this prompt, so the only remaining
                // route to "Allow all the time" is the app's own settings page.
                openAppDetails()
            }
            refreshBackgroundLocationCard()
        }

    private val pickFajrAzan =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            onAzanPicked(uri, forFajr = true)
        }

    private val pickOtherAzan =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            onAzanPicked(uri, forFajr = false)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = PrefsRepository(this)

        setupMethodSpinner()
        setupMadhabSpinner()
        setupWifiFields()
        binding.editPreReminder.setText(prefs.preReminderMinutes.toString())
        setupAzanFields()
        setupPerPrayerRows()
        setupExactAlarmWarning()
        binding.buttonSaveSettings.setOnClickListener { saveAll() }
        binding.buttonGrantBgLocation.setOnClickListener { requestBackgroundLocationAccess() }
        refreshComputedTimes()
        refreshBackgroundLocationCard()
    }

    override fun onResume() {
        super.onResume()
        WifiHelper.cacheCurrentSsid(this)
        refreshAzanLabels()
        binding.textCurrentSsid.text = getString(
            R.string.settings_home_wifi_current,
            WifiHelper.currentSsid(this) ?: "—"
        )
        setupExactAlarmWarning()
        refreshComputedTimes()
        refreshBackgroundLocationCard()
    }

    /**
     * Text fields only persist on focus loss, which never happens if the user types a value
     * and then leaves the screen directly. Flush them here so edits are never silently lost.
     */
    override fun onPause() {
        super.onPause()
        persistPendingEdits()
    }

    private fun setupMethodSpinner() {
        binding.spinnerMethod.setSelection(prefs.calculationMethodIndex)
        binding.spinnerMethod.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                refreshComputedTimes()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupMadhabSpinner() {
        binding.spinnerMadhab.setSelection(prefs.madhabIndex)
        binding.spinnerMadhab.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                refreshComputedTimes()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupWifiFields() {
        binding.editHomeSsid.setText(prefs.homeSsid)
        binding.editHomeSsid.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                prefs.homeSsid = binding.editHomeSsid.text.toString().trim()
                rescheduleAlarms()
            }
        }
        binding.buttonUseCurrentSsid.setOnClickListener {
            val current = WifiHelper.currentSsid(this)
            if (current != null) {
                binding.editHomeSsid.setText(current)
                prefs.homeSsid = current
                rescheduleAlarms()
            }
        }
        binding.textCurrentSsid.text = getString(
            R.string.settings_home_wifi_current,
            WifiHelper.currentSsid(this) ?: "—"
        )
    }

    private fun setupAzanFields() {
        binding.buttonChooseFajrAzan.setOnClickListener { pickFajrAzan.launch(AUDIO_MIME_TYPES) }
        binding.buttonChooseOtherAzan.setOnClickListener { pickOtherAzan.launch(AUDIO_MIME_TYPES) }
        binding.buttonClearFajrAzan.setOnClickListener {
            prefs.fajrAzanUri = ""
            refreshAzanLabels()
        }
        binding.buttonClearOtherAzan.setOnClickListener {
            prefs.defaultAzanUri = ""
            refreshAzanLabels()
        }
        refreshAzanLabels()
    }

    private fun onAzanPicked(uri: Uri?, forFajr: Boolean) {
        if (uri == null) return
        // Without a persistable grant the URI stops working as soon as this process dies,
        // which would silently break the alarm hours later when it actually matters.
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Not every provider offers a persistable grant; the readability check below decides.
        }
        if (!AzanSound.isReadable(this, uri)) {
            Toast.makeText(this, R.string.settings_azan_unreadable, Toast.LENGTH_LONG).show()
            return
        }
        if (forFajr) prefs.fajrAzanUri = uri.toString() else prefs.defaultAzanUri = uri.toString()
        refreshAzanLabels()
    }

    private fun refreshAzanLabels() {
        binding.textOtherAzanName.text = azanLabel(prefs.defaultAzanUri, sharedAvailable = false) {
            prefs.defaultAzanUri = ""
        }
        binding.textFajrAzanName.text =
            azanLabel(prefs.fajrAzanUri, sharedAvailable = prefs.defaultAzanUri.isNotBlank()) {
                prefs.fajrAzanUri = ""
            }
    }

    /**
     * Label for one azan slot. A stored file that has become unreadable (deleted, or its
     * permission revoked) is cleared here, so the screen never claims a sound is set when
     * playback would actually fall back to the ringtone.
     */
    private fun azanLabel(stored: String, sharedAvailable: Boolean, clear: () -> Unit): String {
        if (stored.isNotBlank()) {
            val uri = Uri.parse(stored)
            val name = AzanSound.displayName(this, uri)
            if (name != null && AzanSound.isReadable(this, uri)) {
                return getString(R.string.settings_azan_selected, name)
            }
            clear()
        }
        return getString(
            if (sharedAvailable) R.string.settings_azan_none_shared
            else R.string.settings_azan_none_ringtone
        )
    }

    private fun setupPerPrayerRows() {
        binding.perPrayerContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)

        for (prayer in Prayer.values()) {
            val rowBinding = ItemPrayerSettingsRowBinding.inflate(inflater, binding.perPrayerContainer, false)
            rowBinding.rowPrayerName.text = NotificationHelper.prayerName(this, prayer)
            rowBinding.rowOffset.setText(prefs.offsetMinutes(prayer).toString())
            rowBinding.rowModeSpinner.setSelection(prefs.alarmMode(prayer).index)

            // Typing an offset moves the alarm-time line straight away, so the effect of a
            // change is visible before it is saved.
            rowBinding.rowOffset.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: Editable?) {
                    refreshComputedTimes()
                }
            })

            rowBinding.rowModeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    refreshComputedTimes()
                    refreshBackgroundLocationCard()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            binding.perPrayerContainer.addView(rowBinding.root)
            rowBindings[prayer] = rowBinding
        }
    }

    private fun setupExactAlarmWarning() {
        val needsPermission =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !AlarmScheduler.canScheduleExact(this)
        val visibility = if (needsPermission) android.view.View.VISIBLE else android.view.View.GONE
        binding.textExactAlarmWarning.visibility = visibility
        binding.buttonGrantExactAlarm.visibility = visibility
        binding.buttonGrantExactAlarm.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            }
        }
    }

    /** Writes every control on the screen, then re-arms the alarms to match. */
    private fun persistPendingEdits() {
        prefs.calculationMethodIndex = binding.spinnerMethod.selectedItemPosition
        prefs.madhabIndex = binding.spinnerMadhab.selectedItemPosition
        prefs.homeSsid = binding.editHomeSsid.text.toString().trim()
        // Clamped so a stray keystroke cannot push the nudge hours away from the prayer.
        prefs.preReminderMinutes =
            (binding.editPreReminder.text.toString().toIntOrNull() ?: 0).coerceIn(0, 120)
        for ((prayer, rowBinding) in rowBindings) {
            prefs.setOffsetMinutes(prayer, rowBinding.rowOffset.text.toString().toIntOrNull() ?: 0)
            prefs.setAlarmMode(
                prayer,
                AlarmMode.fromIndex(rowBinding.rowModeSpinner.selectedItemPosition)
            )
        }
        rescheduleAlarms()
        PrayerWidgetProvider.refreshAll(this)
    }

    /**
     * True when the Wi-Fi name is readable from a background alarm.
     *
     * Below Android 10 there is no separate background permission and the foreground grant is
     * enough, so nothing needs asking for.
     */
    private fun hasBackgroundLocation(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

    /** Whether any prayer is set to the mode that actually depends on knowing the network. */
    private fun usesHomeWifiMode(): Boolean = rowBindings.values.any {
        AlarmMode.fromIndex(it.rowModeSpinner.selectedItemPosition) == AlarmMode.LOUD_HOME_WIFI_ONLY
    }

    /** The prompt only appears when home Wi-Fi mode is in use and the grant is still missing. */
    private fun refreshBackgroundLocationCard() {
        val needed = usesHomeWifiMode() && !hasBackgroundLocation()
        binding.cardBgLocation.visibility =
            if (needed) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun requestBackgroundLocationAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // Background access cannot be granted before foreground access exists.
            openAppDetails()
            return
        }
        requestBackgroundLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    private fun openAppDetails() {
        Toast.makeText(this, R.string.settings_bg_location_hint, Toast.LENGTH_LONG).show()
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
        )
    }

    private fun saveAll() {
        persistPendingEdits()
        refreshComputedTimes()
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
    }

    /**
     * Shows each prayer time and the alarm time the current settings would produce.
     *
     * Computed from the controls rather than from storage, so the numbers track unsaved edits
     * and an offset can be lined up against the real prayer time before it is committed.
     */
    private fun refreshComputedTimes() {
        val times = AlarmScheduler.previewTimes(
            this,
            LocalDate.now(),
            binding.spinnerMethod.selectedItemPosition,
            binding.spinnerMadhab.selectedItemPosition
        )
        for ((prayer, rowBinding) in rowBindings) {
            if (times == null) {
                rowBinding.rowPrayerTime.text = ""
                rowBinding.rowAlarmTime.text = getString(R.string.settings_no_time)
                continue
            }
            val raw = AlarmScheduler.rawTimeFor(prayer, times)
            rowBinding.rowPrayerTime.text = raw.format(TIME_FORMAT)
            val mode = AlarmMode.fromIndex(rowBinding.rowModeSpinner.selectedItemPosition)
            rowBinding.rowAlarmTime.text = if (mode == AlarmMode.OFF) {
                getString(R.string.settings_alarm_off)
            } else {
                val offset = rowBinding.rowOffset.text.toString().toIntOrNull() ?: 0
                getString(
                    R.string.settings_alarm_at,
                    raw.plusMinutes(offset.toLong()).format(TIME_FORMAT)
                )
            }
        }
    }

    private fun rescheduleAlarms() {
        AlarmScheduler.scheduleAll(this)
    }

    companion object {
        private val AUDIO_MIME_TYPES = arrayOf("audio/*")
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("h:mm a")
    }
}
