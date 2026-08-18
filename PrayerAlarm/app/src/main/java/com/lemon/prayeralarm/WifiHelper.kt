package com.lemon.prayeralarm

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager

/** Reads the currently connected Wi-Fi network name (SSID), if any. */
object WifiHelper {

    /**
     * Returns the current SSID without quotes, or null if not connected to Wi-Fi
     * or if the SSID cannot be read (e.g. missing location permission on this OS version).
     *
     * Never throws. Reading network state needs ACCESS_NETWORK_STATE and reading the SSID
     * needs location access; either can be absent or revoked at runtime, and an unreadable
     * SSID must degrade to null rather than take down the screen that asked for it.
     */
    fun currentSsid(context: Context): String? = try {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = connectivityManager?.activeNetwork
        val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }

        if (capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            null
        } else {
            val wifiManager =
                context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val ssid = wifiManager?.connectionInfo?.ssid
            if (ssid.isNullOrBlank() || ssid == "<unknown ssid>") {
                null
            } else {
                ssid.removePrefix("\"").removeSuffix("\"")
            }
        }
    } catch (e: SecurityException) {
        null
    }

    /**
     * Records the current SSID while the app is in the foreground. Must be called from an
     * Activity (onResume); a background caller reads back "<unknown ssid>" and stores nothing.
     */
    fun cacheCurrentSsid(context: Context) {
        val current = currentSsid(context) ?: return
        PrefsRepository(context).lastSeenSsid = current
    }

    /**
     * True when the device is on [homeSsid].
     *
     * Android only reveals the SSID to foreground callers, so an alarm firing in the background
     * always reads null. In that case we fall back to the SSID last seen while the app was open,
     * which is what makes "azan on home Wi-Fi only" work from a background alarm at all.
     */
    fun isConnectedToHomeNetwork(context: Context, homeSsid: String): Boolean {
        if (homeSsid.isBlank()) return false
        val current = currentSsid(context)
            ?: PrefsRepository(context).lastSeenSsid.takeIf { it.isNotBlank() }
            ?: return false
        return current.equals(homeSsid, ignoreCase = true)
    }
}
