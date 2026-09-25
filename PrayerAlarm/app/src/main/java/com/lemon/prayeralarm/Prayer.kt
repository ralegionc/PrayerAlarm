package com.lemon.prayeralarm

/**
 * Everything that can carry an alarm.
 *
 * Sunrise and Tahajjud are not obligatory prayers, but both are worth waking for: sunrise
 * closes the Fajr window, and Tahajjud marks the last third of the night. [isObligatory]
 * separates them so the main screen and the next-prayer countdown can ignore them while the
 * settings screen still offers each one an alarm.
 */
enum class Prayer(val storageKey: String, val isObligatory: Boolean) {
    FAJR("fajr", true),
    SUNRISE("sunrise", false),
    DHUHR("dhuhr", true),
    ASR("asr", true),
    MAGHRIB("maghrib", true),
    ISHA("isha", true),
    TAHAJJUD("tahajjud", false);

    companion object {
        fun fromKey(key: String): Prayer? = values().firstOrNull { it.storageKey == key }

        /** The five daily prayers, for anything that should not list sunrise or Tahajjud. */
        fun obligatory(): List<Prayer> = values().filter { it.isObligatory }
    }
}

enum class AlarmMode(val index: Int) {
    OFF(0),
    VIBRATE_ALWAYS(1),
    LOUD_HOME_WIFI_ONLY(2),
    LOUD_EVERYWHERE(3);

    companion object {
        fun fromIndex(i: Int): AlarmMode = values().firstOrNull { it.index == i } ?: VIBRATE_ALWAYS
    }
}

/**
 * What a prayer's alarm offset is measured from: the start of the prayer time, or the iqamah,
 * when the congregation actually stands. Iqamah needs a mosque timetable; without one, or on a
 * day the timetable gives no iqamah, the alarm is measured from the prayer time instead.
 */
enum class AlarmAnchor(val index: Int) {
    PRAYER_TIME(0),
    IQAMAH(1);

    companion object {
        fun fromIndex(i: Int): AlarmAnchor = values().firstOrNull { it.index == i } ?: PRAYER_TIME
    }
}

enum class Madhab(val index: Int, val asrShadowFactor: Int) {
    STANDARD(0, 1),
    HANAFI(1, 2);

    companion object {
        fun fromIndex(i: Int): Madhab = values().firstOrNull { it.index == i } ?: STANDARD
    }
}
