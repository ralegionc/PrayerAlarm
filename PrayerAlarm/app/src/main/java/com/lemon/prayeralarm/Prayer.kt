package com.lemon.prayeralarm

/** The five daily prayers that can have an alarm. Sunrise is informational only. */
enum class Prayer(val storageKey: String) {
    FAJR("fajr"),
    DHUHR("dhuhr"),
    ASR("asr"),
    MAGHRIB("maghrib"),
    ISHA("isha");

    companion object {
        fun fromKey(key: String): Prayer? = values().firstOrNull { it.storageKey == key }
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

enum class Madhab(val index: Int, val asrShadowFactor: Int) {
    STANDARD(0, 1),
    HANAFI(1, 2);

    companion object {
        fun fromIndex(i: Int): Madhab = values().firstOrNull { it.index == i } ?: STANDARD
    }
}
