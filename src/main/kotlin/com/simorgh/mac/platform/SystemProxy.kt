package com.simorgh.mac.platform

/**
 * macOS system proxy, following the pattern observed from Vulpine: the only
 * lever is the SOCKS firewall proxy on the active service, and it is flipped
 * by the root LaunchDaemon helper (`networksetup` needs admin on this
 * machine — the direct call fails silently, which is why the proxy appeared
 * to "grant no access"). The direct path remains as a fallback for machines
 * where it works unprivileged.
 */
object SystemProxy {
    /** The service backing the default route: "Wi-Fi", "Thunderbolt Ethernet", … */
    fun activeService(): String? {
        val device = exec("route", "-n", "get", "default")
            .lineSequence()
            .firstOrNull { it.contains("interface:") }
            ?.substringAfter("interface:")?.trim()
            ?: return null
        val ports = exec("networksetup", "-listallhardwareports")
        var currentPort: String? = null
        for (line in ports.lineSequence()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("Hardware Port:") -> currentPort = trimmed.removePrefix("Hardware Port:").trim()
                trimmed.startsWith("Device:") && trimmed.removePrefix("Device:").trim() == device ->
                    if (currentPort != null) return currentPort
            }
        }
        return null
    }

    @Volatile private var applied = false
    @Volatile private var socksWasEnabled = false

    private fun isEnabled(vararg args: String): Boolean =
        exec("networksetup", *args).contains("Enabled: Yes")

    /** Vulpine's exact shape: SOCKS firewall proxy only, web proxy untouched. */
    fun apply(socksPort: Int): Boolean {
        if (!applied) {
            socksWasEnabled = activeService()?.let {
                isEnabled("-getsocksfirewallproxy", it)
            } ?: false
            applied = true
        }
        if (SimorghHelper.isInstalled()) {
            if (SimorghHelper.startProxy(socksPort)) return true
        }
        val service = activeService() ?: return false
        return try {
            exec("networksetup", "-setsocksfirewallproxy", service, "127.0.0.1", socksPort.toString())
            exec("networksetup", "-setsocksfirewallproxystate", service, "on")
            isEnabled("-getsocksfirewallproxy", service)
        } catch (e: Exception) {
            false
        }
    }

    fun restore() {
        if (!applied) return
        applied = false
        if (SimorghHelper.isInstalled()) {
            SimorghHelper.stopProxy()
            return
        }
        val service = activeService() ?: return
        exec("networksetup", "-setsocksfirewallproxystate", service, if (socksWasEnabled) "on" else "off")
    }
}
