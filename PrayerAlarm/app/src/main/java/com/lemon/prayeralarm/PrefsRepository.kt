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
        private const val KEY_OFFSET_PREFIX = "offset_"
        private const val KEY_MODE_PREFIX = "mode_"
    }
}
