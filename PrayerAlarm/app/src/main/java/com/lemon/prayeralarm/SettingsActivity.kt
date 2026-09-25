package com.lemon.prayeralarm

import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.lemon.prayeralarm.databinding.ActivitySettingsBinding
import com.lemon.prayeralarm.databinding.ItemPrayerSettingsRowBinding
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.Executors

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: PrefsRepository
    private val rowBindings = mutableMapOf<Prayer, ItemPrayerSettingsRowBinding>()

    /** Fixed iqamah times as picked on screen, saved with everything else on Save. */
    private val pendingFixedIqamah = mutableMapOf<Prayer, LocalTime?>()

    /** Timetable downloads and parsing, kept off the main thread. */
    private val worker = Executors.newSingleThreadExecutor()

    private val pickFajrAzan =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            onAzanPicked(uri, forFajr = true)
        }

    private val pickOtherAzan =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            onAzanPicked(uri, forFajr = false)
        }

    private val pickTimetable =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                val app = applicationContext
                val name = AzanSound.displayName(this, uri) ?: uri.lastPathSegment ?: "file"
                importInBackground(name) { MosqueTimetable.read(app, uri) }
            }
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
        setupTimetable()
        setupPerPrayerRows()
        setupExactAlarmWarning()
        binding.buttonSaveSettings.setOnClickListener { saveAll() }
        binding.buttonGrantFullScreen.setOnClickListener { openFullScreenIntentSettings() }
        refreshComputedTimes()
    }

    override fun onResume() {
        super.onResume()
        refreshAzanLabels()
        refreshHomeNetwork()
        refreshTimetableStatus()
        setupExactAlarmWarning()
        refreshFullScreenCard()
        refreshComputedTimes()
    }

    /**
     * Text fields only persist on focus loss, which never happens if the user types a value
     * and then leaves the screen directly. Flush them here so edits are never silently lost.
     */
    override fun onPause() {
        super.onPause()
        persistPendingEdits()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private fun setupMethodSpinner() {
        binding.spinnerMethod.setSelection(prefs.calculationMethodIndex)
        binding.spinnerMethod.onItemSelectedListener = refreshOnSelect()
    }

    private fun setupMadhabSpinner() {
        binding.spinnerMadhab.setSelection(prefs.madhabIndex)
        binding.spinnerMadhab.onItemSelectedListener = refreshOnSelect()
    }

    /** Controls only move the preview; Save is what writes them. */
    private fun refreshOnSelect() = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            refreshComputedTimes()
        }
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }

    private fun setupWifiFields() {
        binding.buttonUseCurrentSsid.setOnClickListener {
            if (HomeNetwork.markCurrentAsHome(this)) {
                Toast.makeText(this, R.string.settings_home_saved, Toast.LENGTH_SHORT).show()
                rescheduleAlarms()
            } else {
                Toast.makeText(this, R.string.settings_home_need_wifi, Toast.LENGTH_LONG).show()
            }
            refreshHomeNetwork()
        }
        binding.buttonClearHome.setOnClickListener {
            HomeNetwork.clearHome(this)
            rescheduleAlarms()
            refreshHomeNetwork()
        }
        refreshHomeNetwork()
    }

    /** Shows which network counts as home, and what the phone is on right now. */
    private fun refreshHomeNetwork() {
        binding.textHomeNetwork.text = when {
            !HomeNetwork.hasHomeNetwork(this) -> getString(R.string.settings_home_unset)
            prefs.homeSsid.isNotBlank() -> getString(R.string.settings_home_set, prefs.homeSsid)
            else -> getString(R.string.settings_home_set_unnamed)
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

    // ------------------------------------------------------------------ mosque timetable

    private fun setupTimetable() {
        val source = prefs.timetableSource
        if (source.startsWith("https://")) binding.editTimetableUrl.setText(source)

        binding.buttonDownloadTimetable.setOnClickListener {
            val url = binding.editTimetableUrl.text.toString().trim()
            // Android refuses plain HTTP by default, so an http link would only fail later
            // with a less helpful message.
            if (!url.startsWith("https://")) {
                Toast.makeText(this, R.string.settings_timetable_bad_url, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            importInBackground(url) { MosqueTimetable.download(url) }
        }
        binding.buttonPickTimetable.setOnClickListener { pickTimetable.launch(TIMETABLE_MIME_TYPES) }
        binding.buttonRemoveTimetable.setOnClickListener {
            MosqueTimetable.clear(this)
            Toast.makeText(this, R.string.timetable_removed, Toast.LENGTH_SHORT).show()
            afterTimetableChange()
        }
        refreshTimetableStatus()
    }

    /**
     * Fetches with [fetch] and imports the result, off the main thread. A network or file error
     * is reported on screen rather than swallowed: a timetable that silently failed to load
     * would leave alarms on calculated times with nothing to say so.
     */
    private fun importInBackground(source: String, fetch: () -> String) {
        val app = applicationContext
        setTimetableBusy(true)
        binding.textTimetableStatus.text = getString(R.string.settings_timetable_working)
        worker.execute {
            val outcome = try {
                MosqueTimetable.import(app, fetch(), source)
            } catch (e: Exception) {
                MosqueTimetable.Outcome(false, e.message ?: e.javaClass.simpleName)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                setTimetableBusy(false)
                afterTimetableChange()
                if (outcome.ok) {
                    Toast.makeText(this, outcome.message, Toast.LENGTH_LONG).show()
                } else {
                    val message = getString(R.string.settings_timetable_failed, outcome.message)
                    binding.textTimetableStatus.text = message
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun afterTimetableChange() {
        refreshTimetableStatus()
        rescheduleAlarms()
        refreshComputedTimes()
    }

    private fun setTimetableBusy(busy: Boolean) {
        binding.buttonDownloadTimetable.isEnabled = !busy
        binding.buttonPickTimetable.isEnabled = !busy
        binding.buttonRemoveTimetable.isEnabled = !busy
    }

    private fun refreshTimetableStatus() {
        val active = MosqueTimetable.isActive(this)
        binding.textTimetableStatus.text = if (active) {
            prefs.timetableSummary.ifBlank { prefs.timetableSource }
        } else {
            getString(R.string.settings_timetable_none)
        }
        binding.buttonRemoveTimetable.visibility = if (active) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------ per-prayer rows

    private fun setupPerPrayerRows() {
        binding.perPrayerContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)

        for (prayer in Prayer.values()) {
            val rowBinding = ItemPrayerSettingsRowBinding.inflate(inflater, binding.perPrayerContainer, false)
            rowBinding.rowPrayerName.text = NotificationHelper.prayerName(this, prayer)
            rowBinding.rowOffset.setText(prefs.offsetMinutes(prayer).toString())
            rowBinding.rowModeSpinner.setSelection(prefs.alarmMode(prayer).index)
            rowBinding.rowAnchorSpinner.setSelection(prefs.alarmAnchor(prayer).index)
            // Sunrise and Tahajjud have no iqamah to count from.
            val iqamahVisibility = if (prayer.isObligatory) View.VISIBLE else View.GONE
            rowBinding.rowAnchorBlock.visibility = iqamahVisibility
            rowBinding.rowIqamahRuleBlock.visibility = iqamahVisibility
            if (prayer.isObligatory) setupIqamahRule(prayer, rowBinding)

            // Typing an offset moves the alarm-time line straight away, so the effect of a
            // change is visible before it is saved.
            rowBinding.rowOffset.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: Editable?) {
                    refreshComputedTimes()
                }
            })
            rowBinding.rowModeSpinner.onItemSelectedListener = refreshOnSelect()
            rowBinding.rowAnchorSpinner.onItemSelectedListener = refreshOnSelect()

            // Seven expanded cards is a long scroll, so each prayer collapses to one line
            // and only opens when it is the one being edited.
            rowBinding.rowHeader.setOnClickListener {
                val opening = rowBinding.rowDetails.visibility != View.VISIBLE
                rowBinding.rowDetails.visibility = if (opening) View.VISIBLE else View.GONE
                rowBinding.rowChevron.text = if (opening) "▴" else "▾"
            }

            binding.perPrayerContainer.addView(rowBinding.root)
            rowBindings[prayer] = rowBinding
        }
    }

    private fun setupIqamahRule(prayer: Prayer, row: ItemPrayerSettingsRowBinding) {
        val rule = prefs.iqamahRule(prayer)
        pendingFixedIqamah[prayer] = rule.fixed
        row.rowIqamahAfter.setText(rule.minutesAfterAdhan?.toString().orEmpty())
        row.rowIqamahAfter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) {
                refreshComputedTimes()
            }
        })
        row.rowIqamahFixed.setOnClickListener {
            val start = pendingFixedIqamah[prayer]
                ?: AlarmScheduler.previewPrayerTime(
                    this, prayer, LocalDate.now(),
                    binding.spinnerMethod.selectedItemPosition,
                    binding.spinnerMadhab.selectedItemPosition
                )
                ?: LocalTime.NOON
            TimePickerDialog(this, { _, hour, minute ->
                pendingFixedIqamah[prayer] = LocalTime.of(hour, minute)
                showFixedIqamah(prayer, row)
                refreshComputedTimes()
            }, start.hour, start.minute, DateFormat.is24HourFormat(this)).show()
        }
        row.rowIqamahFixedClear.setOnClickListener {
            pendingFixedIqamah[prayer] = null
            showFixedIqamah(prayer, row)
            refreshComputedTimes()
        }
        showFixedIqamah(prayer, row)
    }

    private fun showFixedIqamah(prayer: Prayer, row: ItemPrayerSettingsRowBinding) {
        val fixed = pendingFixedIqamah[prayer]
        row.rowIqamahFixed.text = if (fixed == null) {
            getString(R.string.settings_iqamah_fixed_unset)
        } else {
            getString(R.string.settings_iqamah_fixed_set, fixed.format(TIME_FORMAT))
        }
        row.rowIqamahFixedClear.visibility = if (fixed == null) View.GONE else View.VISIBLE
    }

    /** The iqamah rules as they stand on screen, saved or not. */
    private fun iqamahRulesFromControls(): Map<Prayer, IqamahRule> =
        rowBindings.filterKeys { it.isObligatory }.mapValues { (prayer, row) ->
            IqamahRule(
                pendingFixedIqamah[prayer],
                row.rowIqamahAfter.text.toString().toIntOrNull()
            )
        }

    private fun setupExactAlarmWarning() {
        val needsPermission =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !AlarmScheduler.canScheduleExact(this)
        val visibility = if (needsPermission) View.VISIBLE else View.GONE
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

    private fun anchorOf(prayer: Prayer, row: ItemPrayerSettingsRowBinding): AlarmAnchor =
        if (prayer.isObligatory) AlarmAnchor.fromIndex(row.rowAnchorSpinner.selectedItemPosition)
        else AlarmAnchor.PRAYER_TIME

    /** Writes every control on the screen, then re-arms the alarms to match. */
    private fun persistPendingEdits() {
        prefs.calculationMethodIndex = binding.spinnerMethod.selectedItemPosition
        prefs.madhabIndex = binding.spinnerMadhab.selectedItemPosition
        // Clamped so a stray keystroke cannot push the nudge hours away from the prayer.
        prefs.preReminderMinutes =
            (binding.editPreReminder.text.toString().toIntOrNull() ?: 0).coerceIn(0, 120)
        val rules = iqamahRulesFromControls()
        for ((prayer, rule) in rules) prefs.setIqamahRule(prayer, rule)
        for ((prayer, rowBinding) in rowBindings) {
            prefs.setOffsetMinutes(prayer, rowBinding.rowOffset.text.toString().toIntOrNull() ?: 0)
            prefs.setAlarmMode(
                prayer,
                AlarmMode.fromIndex(rowBinding.rowModeSpinner.selectedItemPosition)
            )
            if (prayer.isObligatory) prefs.setAlarmAnchor(prayer, anchorOf(prayer, rowBinding))
        }
        rescheduleAlarms()
        PrayerWidgetProvider.refreshAll(this)
    }

    /**
     * From Android 14 a full-screen intent is only honoured for apps the system classes as
     * alarm or calling apps; for anything else it silently degrades to a heads-up notification,
     * which is why the alarm screen with its buttons never appeared.
     */
    private fun refreshFullScreenCard() {
        val blocked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            !(getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() ?: true)
        binding.cardFullScreen.visibility = if (blocked) View.VISIBLE else View.GONE
    }

    private fun openFullScreenIntentSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        startActivity(
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                data = Uri.fromParts("package", packageName, null)
            }
        )
    }

    /** Compact mode label for the collapsed row, where the full wording will not fit. */
    private fun shortMode(mode: AlarmMode): String = getString(
        when (mode) {
            AlarmMode.OFF -> R.string.mode_short_off
            AlarmMode.VIBRATE_ALWAYS -> R.string.mode_short_vibrate
            AlarmMode.LOUD_HOME_WIFI_ONLY -> R.string.mode_short_home
            AlarmMode.LOUD_EVERYWHERE -> R.string.mode_short_everywhere
        }
    )

    private fun saveAll() {
        persistPendingEdits()
        refreshComputedTimes()
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
    }

    /**
     * Shows each prayer's time, its iqamah where the mosque gives one, and when the alarm the
     * current settings would produce will ring, and relative to what.
     *
     * Computed from the controls rather than from storage, so the numbers track unsaved edits
     * and an offset can be lined up against the real prayer or iqamah before it is committed.
     */
    private fun refreshComputedTimes() {
        val methodIndex = binding.spinnerMethod.selectedItemPosition
        val madhabIndex = binding.spinnerMadhab.selectedItemPosition
        val today = LocalDate.now()
        val timetable = MosqueTimetable.isActive(this)
        val rules = iqamahRulesFromControls()

        for ((prayer, row) in rowBindings) {
            val mode = AlarmMode.fromIndex(row.rowModeSpinner.selectedItemPosition)
            val offset = row.rowOffset.text.toString().toIntOrNull() ?: 0
            val anchor = anchorOf(prayer, row)

            row.rowOffsetLabel.setText(
                when {
                    !prayer.isObligatory -> R.string.settings_offset_label
                    anchor == AlarmAnchor.IQAMAH -> R.string.settings_offset_label_iqamah
                    else -> R.string.settings_offset_label_prayer
                }
            )

            if (mode == AlarmMode.OFF) {
                // Nothing will ring, so today's times are shown purely as a reference point.
                val shown = showTimes(row, prayer, today, methodIndex, madhabIndex, rules)
                row.rowAlarmTime.text = getString(
                    if (shown) R.string.settings_alarm_off else R.string.settings_no_time
                )
                row.rowSummary.text = getString(R.string.mode_short_off)
                row.rowAnchorNote.visibility = View.GONE
                continue
            }

            val preview = AlarmScheduler.previewNextAlarm(
                this, prayer, offset, anchor, methodIndex, madhabIndex, rules
            )
            if (preview == null) {
                row.rowPrayerTime.text = ""
                row.rowIqamah.visibility = View.GONE
                row.rowAlarmTime.text = getString(R.string.settings_no_time)
                continue
            }

            // Both lines describe the same upcoming occurrence rather than today, which may
            // already be hours in the past.
            showTimes(row, prayer, preview.date, methodIndex, madhabIndex, rules)

            // Iqamah was asked for but this occurrence counts from the prayer time: say why,
            // so the alarm does not quietly ring earlier than expected.
            val note = when {
                anchor != AlarmAnchor.IQAMAH || preview.fromIqamah -> null
                !timetable && rules[prayer]?.isSet != true -> R.string.settings_iqamah_needs_timetable
                else -> R.string.settings_iqamah_none
            }
            row.rowAnchorNote.visibility = if (note == null) View.GONE else View.VISIBLE
            note?.let { row.rowAnchorNote.setText(it) }

            val days = ChronoUnit.DAYS.between(today, preview.ringsAt.toLocalDate())
            val time = preview.ringsAt.toLocalTime().format(TIME_FORMAT)
            val ringsAt = when (days) {
                0L -> getString(R.string.settings_alarm_at, time)
                1L -> getString(R.string.settings_alarm_at_tomorrow, time)
                else -> getString(R.string.settings_alarm_at_day, preview.ringsAt.format(DAY_FORMAT), time)
            }
            val reference = when {
                preview.fromIqamah -> getString(R.string.anchor_ref_iqamah)
                prayer.isObligatory -> getString(R.string.anchor_ref_prayer)
                else -> NotificationHelper.prayerName(this, prayer)
            }
            val relation = when {
                offset == 0 -> getString(R.string.relation_at, reference)
                offset < 0 -> getString(R.string.relation_before, -offset, reference)
                else -> getString(R.string.relation_after, offset, reference)
            }
            row.rowAlarmTime.text = getString(R.string.settings_alarm_line, ringsAt, relation)

            val whenText = when (days) {
                0L -> time
                1L -> getString(R.string.settings_when_tomorrow, time)
                else -> getString(R.string.settings_when_day, preview.ringsAt.format(DAY_FORMAT), time)
            }
            row.rowSummary.text = getString(R.string.settings_row_summary, whenText, shortMode(mode))
        }
    }

    /**
     * Fills in the prayer time for [date] and, where one is known, its iqamah.
     * Returns false when there is no time to show at all.
     */
    private fun showTimes(
        row: ItemPrayerSettingsRowBinding,
        prayer: Prayer,
        date: LocalDate,
        methodIndex: Int,
        madhabIndex: Int,
        rules: Map<Prayer, IqamahRule>
    ): Boolean {
        val prayerTime =
            AlarmScheduler.previewPrayerTime(this, prayer, date, methodIndex, madhabIndex)
        row.rowPrayerTime.text = prayerTime?.format(TIME_FORMAT).orEmpty()
        val iqamah =
            AlarmScheduler.previewIqamah(this, prayer, date, methodIndex, madhabIndex, rules)
        row.rowIqamah.visibility = if (iqamah == null) View.GONE else View.VISIBLE
        iqamah?.let {
            row.rowIqamah.text = getString(R.string.settings_iqamah_line, it.toLocalTime().format(TIME_FORMAT))
        }
        return prayerTime != null
    }

    private fun rescheduleAlarms() {
        AlarmScheduler.scheduleAll(this)
    }

    companion object {
        private val AUDIO_MIME_TYPES = arrayOf("audio/*")

        /** CSV has no single agreed type, so accept the ones phones actually report for it. */
        private val TIMETABLE_MIME_TYPES = arrayOf(
            "text/*",
            "application/csv",
            "application/vnd.ms-excel",
            "application/octet-stream"
        )
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("h:mm a")
        private val DAY_FORMAT = DateTimeFormatter.ofPattern("EEEE")
    }
}
