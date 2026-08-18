package com.lemon.prayeralarm

import android.content.Context
import android.content.SharedPreferences

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
     * Last Wi-Fi SSID observed while the app was in the foreground. Android hides the SSID
     * from background callers, so alarms compare against this remembered value instead.
     */
    var lastSeenSsid: String
        get() = prefs.getString(KEY_LAST_SSID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_SSID, value).apply()

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

    fun alarmMode(prayer: Prayer): AlarmMode =
        AlarmMode.fromIndex(prefs.getInt(KEY_MODE_PREFIX + prayer.storageKey, AlarmMode.VIBRATE_ALWAYS.index))

    fun setAlarmMode(prayer: Prayer, mode: AlarmMode) {
        prefs.edit().putInt(KEY_MODE_PREFIX + prayer.storageKey, mode.index).apply()
    }

    companion object {
        private const val KEY_LAT = "lat"
        private const val KEY_LNG = "lng"
        private const val KEY_METHOD = "calc_method"
        private const val KEY_MADHAB = "madhab"
        private const val KEY_HOME_SSID = "home_ssid"
        private const val KEY_LAST_SSID = "last_ssid"
        private const val KEY_CITY = "city_name"
        private const val KEY_PRE_REMINDER = "pre_reminder_minutes"
        private const val KEY_AZAN_FAJR = "azan_fajr_uri"
        private const val KEY_AZAN_DEFAULT = "azan_default_uri"
        private const val KEY_OFFSET_PREFIX = "offset_"
        private const val KEY_MODE_PREFIX = "mode_"
    }
}
