package com.lemon.prayeralarm

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Recognises the home Wi-Fi network without ever reading its name.
 *
 * Android hides the SSID from background callers unless the app holds background location, so
 * the alarm cannot ask which network it is on by name. It can, however, read the network's IP
 * configuration with nothing but ACCESS_NETWORK_STATE, and that is enough to tell one network
 * from another: the router address and the DNS servers it hands out are stable for a given
 * network and differ between them.
 *
 * The user marks their network as home once; every later check compares the live configuration
 * against what was stored. Nothing is cached across connections, so this stays correct after a
 * reboot or a rejoin, which is exactly where an SSID cache goes wrong.
 */
object HomeNetwork {

    /** Router addresses and DNS servers of the active Wi-Fi network, or null when not on Wi-Fi. */
    private fun readConfig(context: Context): Pair<String, String>? = try {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        // Deliberately not activeNetwork: that is whichever network carries the default route,
        // which can be cellular while Wi-Fi is still connected -- during a VoLTE call, for
        // instance. Asking it would report "not on Wi-Fi" while sitting on the home network.
        @Suppress("DEPRECATION")
        val network = connectivityManager?.allNetworks?.firstOrNull {
            connectivityManager.getNetworkCapabilities(it)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        if (network == null) {
            null
        } else {
            val linkProperties = connectivityManager.getLinkProperties(network)
            val gateways = linkProperties?.routes.orEmpty()
                .filter { it.isDefaultRoute }
                .mapNotNull { it.gateway?.hostAddress }
                .sorted()
                .joinToString(",")
            val dns = linkProperties?.dnsServers.orEmpty()
                .mapNotNull { it.hostAddress }
                .sorted()
                .joinToString(",")
            if (gateways.isBlank()) null else gateways to dns
        }
    } catch (e: SecurityException) {
        null
    }

    /** True when currently on Wi-Fi, regardless of which one. */
    fun isOnWifi(context: Context): Boolean = readConfig(context) != null

    /**
     * Remembers the current network as home. Returns false when not on Wi-Fi, so the caller can
     * tell the user rather than silently storing nothing.
     */
    fun markCurrentAsHome(context: Context): Boolean {
        val (gateways, dns) = readConfig(context) ?: return false
        val prefs = PrefsRepository(context)
        prefs.homeGateways = gateways
        prefs.homeDns = dns
        // Kept only as a human-readable label; matching never uses it.
        WifiHelper.currentSsid(context)?.let { prefs.homeSsid = it }
        return true
    }

    fun clearHome(context: Context) {
        val prefs = PrefsRepository(context)
        prefs.homeGateways = ""
        prefs.homeDns = ""
        prefs.homeSsid = ""
    }

    fun hasHomeNetwork(context: Context): Boolean =
        PrefsRepository(context).homeGateways.isNotBlank()

    /**
     * True when the current network looks like the one marked as home.
     *
     * The router address must match exactly. DNS servers must overlap rather than match outright,
     * because a network can legitimately hand out a different set — adding or dropping an IPv6
     * resolver, say — without becoming a different network. Requiring both signals makes an
     * accidental match on another network with the same private address range unlikely.
     */
    fun isAtHome(context: Context): Boolean {
        val prefs = PrefsRepository(context)
        val savedGateways = prefs.homeGateways
        if (savedGateways.isBlank()) return false

        val (gateways, dns) = readConfig(context) ?: return false
        if (gateways != savedGateways) return false

        val savedDns = prefs.homeDns
        if (savedDns.isBlank() || dns.isBlank()) return true
        val live = dns.split(",").toSet()
        return savedDns.split(",").any { it in live }
    }
}
