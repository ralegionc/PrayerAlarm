package com.lemon.prayeralarm

/**
 * Parameters for a prayer-time calculation method.
 *
 * fajrAngle / ishaAngle: sun depression angle below horizon, in degrees.
 * ishaIntervalMinutes: if > 0, Isha is this many minutes after Maghrib instead of angle-based.
 * maghribAngle: 0 means Maghrib = sunset. Some Shia-oriented methods use a small angle after sunset.
 */
data class CalculationMethod(
    val index: Int,
    val fajrAngle: Double,
    val ishaAngle: Double,
    val ishaIntervalMinutes: Int = 0,
    val maghribAngle: Double = 0.0
) {
    companion object {
        val MWL = CalculationMethod(0, 18.0, 17.0)
        val ISNA = CalculationMethod(1, 15.0, 15.0)
        val EGYPT = CalculationMethod(2, 19.5, 17.5)
        val MAKKAH = CalculationMethod(3, 18.5, 0.0, ishaIntervalMinutes = 90)
        val KARACHI = CalculationMethod(4, 18.0, 18.0)
        val TEHRAN = CalculationMethod(5, 17.7, 14.0, maghribAngle = 4.5)
        val JAFARI = CalculationMethod(6, 16.0, 14.0, maghribAngle = 4.0)
        val KUWAIT = CalculationMethod(7, 18.0, 17.5)
        val QATAR = CalculationMethod(8, 18.0, 0.0, ishaIntervalMinutes = 90)
        val SINGAPORE = CalculationMethod(9, 20.0, 18.0)
        val DIYANET = CalculationMethod(10, 18.0, 17.0)
        val FRANCE_UOIF = CalculationMethod(11, 12.0, 12.0)
        val RUSSIA = CalculationMethod(12, 16.0, 15.0)
        val MOONSIGHTING = CalculationMethod(13, 18.0, 18.0)

        /** Ordered to match R.array.calculation_methods */
        fun forIndex(index: Int, customFajr: Double = 18.0, customIsha: Double = 18.0): CalculationMethod {
            return when (index) {
                0 -> MWL
                1 -> ISNA
                2 -> EGYPT
                3 -> MAKKAH
                4 -> KARACHI
                5 -> TEHRAN
                6 -> JAFARI
                7 -> KUWAIT
                8 -> QATAR
                9 -> SINGAPORE
                10 -> DIYANET
                11 -> FRANCE_UOIF
                12 -> RUSSIA
                13 -> MOONSIGHTING
                14 -> CalculationMethod(14, customFajr, customIsha)
                else -> MWL
            }
        }
    }
}
