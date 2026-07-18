package com.lemon.prayeralarm

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build

/** Reads the currently connected Wi-Fi network name (SSID), if any. */
object WifiHelper {

    /**
     * Returns the current SSID without quotes, or null if not connected to Wi-Fi
     * or if the SSID cannot be read (e.g. missing location permission on this OS version).
     */
    fun currentSsid(context: Context): String? {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val network = connectivityManager.activeNetwork ?: return null
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null

        val wifiManager =
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        val ssid = wifiManager.connectionInfo?.ssid ?: return null
        if (ssid.isBlank() || ssid == "<unknown ssid>") return null
        return ssid.removePrefix("\"").removeSuffix("\"")
    }

    fun isConnectedToHomeNetwork(context: Context, homeSsid: String): Boolean {
        if (homeSsid.isBlank()) return false
        val current = currentSsid(context) ?: return false
        return current.equals(homeSsid, ignoreCase = true)
    }
}
