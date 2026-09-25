package com.lemon.prayeralarm

import java.time.LocalDateTime
import java.time.LocalTime

/**
 * How a mosque sets one prayer's iqamah, as the user entered it.
 *
 * Mosques usually hold a fixed clock time while the adhan is early enough, and switch to a few
 * minutes after the adhan once it moves past that time (Isha at 8:30 p.m. in autumn, adhan + 5
 * in summer). Taking the later of the two covers both seasons with one setting; either part may
 * also be left out on its own.
 */
data class IqamahRule(val fixed: LocalTime?, val minutesAfterAdhan: Int?) {

    val isSet: Boolean get() = fixed != null || minutesAfterAdhan != null

    /**
     * The iqamah for the prayer whose adhan is at [adhan], or null when nothing is set.
     *
     * Never earlier than the adhan: a fixed time the adhan has already passed would otherwise
     * put iqamah before the prayer has even begun.
     */
    fun resolve(adhan: LocalDateTime): LocalDateTime? {
        if (!isSet) return null
        val candidates = listOfNotNull(
            adhan,
            fixed?.let { adhan.toLocalDate().atTime(it) },
            minutesAfterAdhan?.let { adhan.plusMinutes(it.toLong()) }
        )
        return candidates.max()
    }

    companion object {
        val NONE = IqamahRule(null, null)
    }
}
