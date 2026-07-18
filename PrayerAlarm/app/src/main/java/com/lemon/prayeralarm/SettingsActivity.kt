package com.lemon.prayeralarm

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.AdapterView
import androidx.appcompat.app.AppCompatActivity
import com.lemon.prayeralarm.databinding.ActivitySettingsBinding
import com.lemon.prayeralarm.databinding.ItemPrayerSettingsRowBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: PrefsRepository
    private val rowBindings = mutableMapOf<Prayer, ItemPrayerSettingsRowBinding>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = PrefsRepository(this)

        setupMethodSpinner()
        setupMadhabSpinner()
        setupWifiFields()
        setupPerPrayerRows()
        setupExactAlarmWarning()
    }

    override fun onResume() {
        super.onResume()
        binding.textCurrentSsid.text = getString(
            R.string.settings_home_wifi_current,
            WifiHelper.currentSsid(this) ?: "—"
        )
        setupExactAlarmWarning()
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

    private fun rescheduleAlarms() {
        AlarmScheduler.scheduleAll(this)
    }
}
