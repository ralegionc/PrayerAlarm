package com.lemon.prayeralarm

import android.content.Context
import android.location.Geocoder
import android.os.Build
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Turns stored coordinates into a place name for display.
 *
 * Geocoding needs a network round trip, so the result is cached in preferences and refreshed
 * only when the location changes. The widget never geocodes; it just reads the cached name.
 */
object CityResolver {

    /** Resolves the city for [lat]/[lng] off the main thread and caches it. */
    fun refresh(context: Context, lat: Double, lng: Double) {
        if (!Geocoder.isPresent()) return
        val appContext = context.applicationContext
        Executors.newSingleThreadExecutor().execute {
            val name = lookup(appContext, lat, lng)
            if (!name.isNullOrBlank()) {
                PrefsRepository(appContext).cityName = name
                // The lookup is asynchronous, so the widget needs a nudge once it resolves.
                PrayerWidgetProvider.refreshAll(appContext)
            }
        }
    }

    private fun lookup(context: Context, lat: Double, lng: Double): String? = try {
        val geocoder = Geocoder(context, Locale.getDefault())
        @Suppress("DEPRECATION")
        val results = geocoder.getFromLocation(lat, lng, 1)
        val address = results?.firstOrNull()
        // locality is the city; the fallbacks cover rural areas where it is absent.
        address?.locality
            ?: address?.subAdminArea
            ?: address?.adminArea
            ?: address?.countryName
    } catch (e: Exception) {
        // No geocoder backend, or no network. The caller keeps whatever name it already had.
        null
    }

    /** Best available label for the current location, falling back to coordinates. */
    fun label(context: Context): String {
        val prefs = PrefsRepository(context)
        val cached = prefs.cityName
        if (cached.isNotBlank()) return cached
        if (!prefs.hasLocation) return ""
        return String.format(Locale.getDefault(), "%.2f, %.2f", prefs.latitude, prefs.longitude)
    }
}
