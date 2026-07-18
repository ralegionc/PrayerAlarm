package com.lemon.prayeralarm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/** Wraps the platform LocationManager (no Play Services dependency needed). */
object LocationHelper {

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Gets a fresh GPS/network fix. Calls [onResult] with the location or null on failure.
     * Falls back to the last known location if a fresh fix can't be obtained quickly.
     */
    fun requestLocation(context: Context, onResult: (Location?) -> Unit) {
        if (!hasPermission(context)) {
            onResult(null)
            return
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }

        if (provider == null) {
            onResult(lastKnown(context))
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val executor = Executors.newSingleThreadExecutor()
                val cancellationSignal = CancellationSignal()
                lm.getCurrentLocation(provider, cancellationSignal, executor) { location ->
                    onResult(location ?: lastKnown(context))
                }
            } else {
                requestSingleUpdateLegacy(context, lm, provider, onResult)
            }
        } catch (e: SecurityException) {
            onResult(null)
        }
    }

    private fun requestSingleUpdateLegacy(
        context: Context,
        lm: LocationManager,
        provider: String,
        onResult: (Location?) -> Unit
    ) {
        val mainHandler = Handler(Looper.getMainLooper())
        var resolved = false

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!resolved) {
                    resolved = true
                    lm.removeUpdates(this)
                    onResult(location)
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        try {
            lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
        } catch (e: SecurityException) {
            onResult(null)
            return
        }

        // Safety timeout in case no fix arrives quickly.
        mainHandler.postDelayed({
            if (!resolved) {
                resolved = true
                lm.removeUpdates(listener)
                onResult(lastKnown(context))
            }
        }, 15_000L)
    }

    private fun lastKnown(context: Context): Location? {
        if (!hasPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull {
                try {
                    lm.getLastKnownLocation(it)
                } catch (e: SecurityException) {
                    null
                }
            }
            .maxByOrNull { it.time }
    }
}
