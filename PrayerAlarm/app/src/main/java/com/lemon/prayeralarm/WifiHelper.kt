package com.lemon.prayeralarm

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager

/** Reads the currently connected Wi-Fi network name (SSID), if any. */
object WifiHelper {

    /**
     * Handle of the active Wi-Fi connection, or null when the device is not on Wi-Fi.
     *
     * The handle is stable for the life of one connection, so it says whether a later reading
     * is the *same* network rather than merely another one with the same name. Needs only
     * ACCESS_NETWORK_STATE, so unlike the SSID it stays readable from the background.
     */
    private fun activeWifiHandle(context: Context): Long? = try {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = connectivityManager?.activeNetwork
        val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }
        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
            network.networkHandle
        } else {
            null
        }
    } catch (e: SecurityException) {
        null
    }

    /**
     * Returns the current SSID without quotes, or null if not connected to Wi-Fi
     * or if the SSID cannot be read (e.g. missing location permission on this OS version).
     *
     * Never throws. Reading network state needs ACCESS_NETWORK_STATE and reading the SSID
     * needs location access; either can be absent or revoked at runtime, and an unreadable
     * SSID must degrade to null rather than take down the screen that asked for it.
     */
    fun currentSsid(context: Context): String? = try {
        if (activeWifiHandle(context) == null) {
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
     * Records the current SSID, and which connection it belongs to, while the app is in the
     * foreground. Must be called from an Activity (onResume); a background caller reads back
     * "<unknown ssid>" and stores nothing.
     */
    fun cacheCurrentSsid(context: Context) {
        val current = currentSsid(context) ?: return
        val handle = activeWifiHandle(context) ?: return
        val prefs = PrefsRepository(context)
        prefs.lastSeenSsid = current
        prefs.lastSeenNetworkHandle = handle
    }

    /**
     * True when the device is on [homeSsid].
     *
     * Android hides the SSID from background callers, so an alarm firing in the background
     * usually reads null and has to fall back on what was cached while the app was open. The
     * fallback is only sound when we still know it applies:
     *
     *  - not on Wi-Fi at all (mobile data) is a definite no, never a cache lookup, otherwise
     *    the azan plays at full volume the moment the user steps outside;
     *  - on Wi-Fi with a readable name, trust the name;
     *  - on Wi-Fi with the name hidden, trust the cache only if this is literally the same
     *    connection it was cached from, so a different network cannot inherit it.
     *
     * Anything less certain returns false, which downgrades to a vibrate reminder rather than
     * playing the azan aloud somewhere it should not.
     */
    fun isConnectedToHomeNetwork(context: Context, homeSsid: String): Boolean {
        if (homeSsid.isBlank()) return false
        val handle = activeWifiHandle(context) ?: return false

        currentSsid(context)?.let { return it.equals(homeSsid, ignoreCase = true) }

        val prefs = PrefsRepository(context)
        if (prefs.lastSeenNetworkHandle != handle) return false
        return prefs.lastSeenSsid.isNotBlank() &&
            prefs.lastSeenSsid.equals(homeSsid, ignoreCase = true)
    }
}
