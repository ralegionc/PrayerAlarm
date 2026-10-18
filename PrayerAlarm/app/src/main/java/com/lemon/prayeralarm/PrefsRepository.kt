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

    /** Content URI of the default azan, used by any prayer without its own, or "" for the ringtone. */
    var defaultAzanUri: String
        get() = prefs.getString(KEY_AZAN_DEFAULT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_AZAN_DEFAULT, value).apply()

    /**
     * The recording chosen for [prayer] alone, or "" to use the default. Fajr keeps the key it
     * had when it was the only prayer with its own file, so an existing choice carries over.
     */
    fun azanUri(prayer: Prayer): String =
        prefs.getString(azanKey(prayer), "") ?: ""

    fun setAzanUri(prayer: Prayer, uri: String) {
        prefs.edit().putString(azanKey(prayer), uri).apply()
    }

    private fun azanKey(prayer: Prayer) =
        if (prayer == Prayer.FAJR) KEY_AZAN_FAJR else KEY_AZAN_PRAYER_PREFIX + prayer.storageKey

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
        private const val KEY_AZAN_PRAYER_PREFIX = "azan_uri_"
        private const val KEY_OFFSET_PREFIX = "offset_"
        private const val KEY_MODE_PREFIX = "mode_"
    }
}
