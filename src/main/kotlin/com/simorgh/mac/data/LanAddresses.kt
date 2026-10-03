package com.simorgh.mac.data

import java.net.Inet4Address
import java.net.NetworkInterface

/** The Mac's shareable LAN addresses for the "share over LAN" sheet. */
object LanAddresses {
    fun list(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { ni ->
                ni.interfaceAddresses
                    .map { it.address }
                    .filterIsInstance<Inet4Address>()
                    .filter { it.isSiteLocalAddress }
                    .map { it.hostAddress }
            }
    }.getOrDefault(emptyList())
}
