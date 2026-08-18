package com.lemon.prayeralarm

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager

/**
 * Reads the current Wi-Fi network name, for display only.
 *
 * Android reveals the SSID to foreground callers, which is enough to label the network in
 * settings. Deciding whether an alarm is at home is [HomeNetwork]'s job, because that runs in
 * the background where the name is hidden.
 */
object WifiHelper {

    /**
     * Returns the current SSID without quotes, or null when not on Wi-Fi or when the name is
     * withheld — which is the normal result for a background caller.
     *
     * Never throws. Reading network state needs ACCESS_NETWORK_STATE and reading the SSID needs
     * location access; either can be absent or revoked at runtime, and an unreadable SSID must
     * degrade to null rather than take down the screen that asked for it.
     */
    fun currentSsid(context: Context): String? = try {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        // Same reasoning as HomeNetwork: Wi-Fi can be connected without being the default route.
        @Suppress("DEPRECATION")
        val network = connectivityManager?.allNetworks?.firstOrNull {
            connectivityManager.getNetworkCapabilities(it)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }

        if (network == null) {
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
}
