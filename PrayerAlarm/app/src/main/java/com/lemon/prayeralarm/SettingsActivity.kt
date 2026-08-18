package com.lemon.prayeralarm

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.AdapterView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.lemon.prayeralarm.databinding.ActivitySettingsBinding
import com.lemon.prayeralarm.databinding.ItemPrayerSettingsRowBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: PrefsRepository
    private val rowBindings = mutableMapOf<Prayer, ItemPrayerSettingsRowBinding>()

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
                prefs.calculationMethodIndex = position
                rescheduleAlarms()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupMadhabSpinner() {
        binding.spinnerMadhab.setSelection(prefs.madhabIndex)
        binding.spinnerMadhab.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                prefs.madhabIndex = position
                rescheduleAlarms()
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

            rowBinding.rowOffset.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    val minutes = rowBinding.rowOffset.text.toString().toIntOrNull() ?: 0
                    prefs.setOffsetMinutes(prayer, minutes)
                    rescheduleAlarms()
                }
            }

            rowBinding.rowModeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    prefs.setAlarmMode(prayer, AlarmMode.fromIndex(position))
                    rescheduleAlarms()
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

    private fun persistPendingEdits() {
        prefs.homeSsid = binding.editHomeSsid.text.toString().trim()
        // Clamped so a stray keystroke cannot push the nudge hours away from the prayer.
        prefs.preReminderMinutes =
            (binding.editPreReminder.text.toString().toIntOrNull() ?: 0).coerceIn(0, 120)
        for ((prayer, rowBinding) in rowBindings) {
            val minutes = rowBinding.rowOffset.text.toString().toIntOrNull() ?: 0
            prefs.setOffsetMinutes(prayer, minutes)
        }
        rescheduleAlarms()
    }

    private fun rescheduleAlarms() {
        AlarmScheduler.scheduleAll(this)
    }

    companion object {
        private val AUDIO_MIME_TYPES = arrayOf("audio/*")
    }
}
