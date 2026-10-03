package com.simorgh.mac.data

import com.simorgh.mac.R
import com.simorgh.mac.str.Ctx

/**
 * Desktop stand-in for the Android NetworkIdentity (which read SSID/BSSID from
 * ConnectivityManager with location permission). On a Mac the "current network"
 * is the primary non-loopback IPv4 interface: the id is "iface:ip" and the
 * label is the machine's hostname, which is what a user recognizes at home or
 * in an office.
 */
object NetworkIdentity {

    /** Stable id of the current network ("en0:192.168.1.12"), or null when offline. */
    fun current(context: Ctx): String? {
        val iface = java.net.NetworkInterface.getNetworkInterfaces()?.asSequence()
            ?.filter { ni ->
                runCatching { ni.isUp && !ni.isLoopback && !ni.isVirtual && !ni.isPointToPoint }.getOrDefault(false)
            }
            ?.firstOrNull { ni ->
                ni.inetAddresses.asSequence().any { addr ->
                    addr.hostAddress?.indexOf('.')?.let { it >= 0 } == true &&
                        !addr.isLoopbackAddress && !addr.isLinkLocalAddress
                }
            } ?: return null
        val ip = iface.inetAddresses.asSequence()
            .firstOrNull { addr -> addr.hostAddress?.contains('.') == true && !addr.isLoopbackAddress && !addr.isLinkLocalAddress }
            ?.hostAddress ?: return null
        return "${iface.name}:$ip"
    }

    /** Human-readable name shown when trusting the network: the host name, else "This network". */
    fun label(context: Ctx): String =
        runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()
            ?.takeIf { it.isNotBlank() && it != "localhost" }
            ?: context.getString(R.string.trusted_unknown_network)
}
