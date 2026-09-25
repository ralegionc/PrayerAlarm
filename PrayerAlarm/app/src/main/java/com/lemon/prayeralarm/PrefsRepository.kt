package com.lemon.prayeralarm

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalTime

/** Central storage for all user settings. Backed by SharedPreferences. */
class PrefsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("prayer_alarm_prefs", Context.MODE_PRIVATE)

    var latitude: Double
        get() = prefs.getFloat(KEY_LAT, Float.NaN).toDouble()
        set(value) = prefs.edit().putFloat(KEY_LAT, value.toFloat()).apply()

    var longitude: Double
        get() = prefs.getFloat(KEY_LNG, Float.NaN).toDouble()
        set(value) = prefs.edit().putFloat(KEY_LNG, value.toFloat()).apply()

    val hasLocation: Boolean
        get() = !latitude.isNaN() && !longitude.isNaN()

    fun setLocation(lat: Double, lng: Double) {
        prefs.edit().putFloat(KEY_LAT, lat.toFloat()).putFloat(KEY_LNG, lng.toFloat()).apply()
    }

    var calculationMethodIndex: Int
        get() = prefs.getInt(KEY_METHOD, 0)
        set(value) = prefs.edit().putInt(KEY_METHOD, value).apply()

    var madhabIndex: Int
        get() = prefs.getInt(KEY_MADHAB, 0)
        set(value) = prefs.edit().putInt(KEY_MADHAB, value).apply()

    var homeSsid: String
        get() = prefs.getString(KEY_HOME_SSID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HOME_SSID, value).apply()

    /**
     * Router addresses of the network marked as home, comma separated.
     *
     * Identifying home by its IP configuration rather than its name is what lets a background
     * alarm decide correctly without holding background location.
     */
    var homeGateways: String
        get() = prefs.getString(KEY_HOME_GATEWAYS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HOME_GATEWAYS, value).apply()

    /** DNS servers of the network marked as home, comma separated. */
    var homeDns: String
        get() = prefs.getString(KEY_HOME_DNS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HOME_DNS, value).apply()

    /** Cached place name for the stored coordinates, shown in the widget. */
    var cityName: String
        get() = prefs.getString(KEY_CITY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CITY, value).apply()

    /** Minutes before each prayer to post a heads-up reminder. 0 disables it. */
    var preReminderMinutes: Int
        get() = prefs.getInt(KEY_PRE_REMINDER, 0)
        set(value) = prefs.edit().putInt(KEY_PRE_REMINDER, value).apply()

    /** Content URI of the user's Fajr azan recording, or "" to fall back. */
    var fajrAzanUri: String
        get() = prefs.getString(KEY_AZAN_FAJR, "") ?: ""
        set(value) = prefs.edit().putString(KEY_AZAN_FAJR, value).apply()

    /** Content URI of the azan used for the other four prayers, or "" to fall back. */
    var defaultAzanUri: String
        get() = prefs.getString(KEY_AZAN_DEFAULT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_AZAN_DEFAULT, value).apply()

    fun offsetMinutes(prayer: Prayer): Int =
        prefs.getInt(KEY_OFFSET_PREFIX + prayer.storageKey, 0)

    fun setOffsetMinutes(prayer: Prayer, minutes: Int) {
        prefs.edit().putInt(KEY_OFFSET_PREFIX + prayer.storageKey, minutes).apply()
    }

    fun alarmMode(prayer: Prayer): AlarmMode {
        // Sunrise and Tahajjud start off; nobody expects an unrequested alarm before dawn.
        val fallback = if (prayer.isObligatory) AlarmMode.VIBRATE_ALWAYS else AlarmMode.OFF
        return AlarmMode.fromIndex(
            prefs.getInt(KEY_MODE_PREFIX + prayer.storageKey, fallback.index)
        )
    }

    fun setAlarmMode(prayer: Prayer, mode: AlarmMode) {
        prefs.edit().putInt(KEY_MODE_PREFIX + prayer.storageKey, mode.index).apply()
    }

    /** Sunrise and Tahajjud have no iqamah, so for them this is always the prayer time. */
    fun alarmAnchor(prayer: Prayer): AlarmAnchor {
        if (!prayer.isObligatory) return AlarmAnchor.PRAYER_TIME
        return AlarmAnchor.fromIndex(prefs.getInt(KEY_ANCHOR_PREFIX + prayer.storageKey, 0))
    }

    fun setAlarmAnchor(prayer: Prayer, anchor: AlarmAnchor) {
        prefs.edit().putInt(KEY_ANCHOR_PREFIX + prayer.storageKey, anchor.index).apply()
    }

    /** The user's own iqamah for [prayer]; takes precedence over an imported timetable. */
    fun iqamahRule(prayer: Prayer): IqamahRule {
        if (!prayer.isObligatory) return IqamahRule.NONE
        val fixed = prefs.getInt(KEY_IQAMAH_FIXED_PREFIX + prayer.storageKey, -1)
        val after = prefs.getInt(KEY_IQAMAH_AFTER_PREFIX + prayer.storageKey, -1)
        return IqamahRule(
            fixed.takeIf { it >= 0 }?.let { LocalTime.ofSecondOfDay(it * 60L) },
            after.takeIf { it >= 0 }
        )
    }

    fun setIqamahRule(prayer: Prayer, rule: IqamahRule) {
        prefs.edit()
            .putInt(
                KEY_IQAMAH_FIXED_PREFIX + prayer.storageKey,
                rule.fixed?.let { it.toSecondOfDay() / 60 } ?: -1
            )
            .putInt(KEY_IQAMAH_AFTER_PREFIX + prayer.storageKey, rule.minutesAfterAdhan ?: -1)
            .apply()
    }

    /** Where the imported mosque timetable came from: a link, or a file name. */
    var timetableSource: String
        get() = prefs.getString(KEY_TIMETABLE_SOURCE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TIMETABLE_SOURCE, value).apply()

    /** What the last import found, shown in settings so a bad file does not go unnoticed. */
    var timetableSummary: String
        get() = prefs.getString(KEY_TIMETABLE_SUMMARY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TIMETABLE_SUMMARY, value).apply()

    companion object {
        private const val KEY_LAT = "lat"
        private const val KEY_LNG = "lng"
        private const val KEY_METHOD = "calc_method"
        private const val KEY_MADHAB = "madhab"
        private const val KEY_HOME_SSID = "home_ssid"
        private const val KEY_HOME_GATEWAYS = "home_gateways"
        private const val KEY_HOME_DNS = "home_dns"
        private const val KEY_CITY = "city_name"
        private const val KEY_PRE_REMINDER = "pre_reminder_minutes"
        private const val KEY_AZAN_FAJR = "azan_fajr_uri"
        private const val KEY_AZAN_DEFAULT = "azan_default_uri"
        private const val KEY_OFFSET_PREFIX = "offset_"
        private const val KEY_MODE_PREFIX = "mode_"
        private const val KEY_ANCHOR_PREFIX = "anchor_"
        private const val KEY_IQAMAH_FIXED_PREFIX = "iqamah_fixed_"
        private const val KEY_IQAMAH_AFTER_PREFIX = "iqamah_after_"
        private const val KEY_TIMETABLE_SOURCE = "timetable_source"
        private const val KEY_TIMETABLE_SUMMARY = "timetable_summary"
    }
}
