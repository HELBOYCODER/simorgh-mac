package com.simorgh.mac.platform

/**
 * macOS system proxy via `networksetup`, applied on connect and restored on
 * disconnect/quit (Settings switch, default on). Only the active network
 * service is touched; previous state is remembered so restore is exact.
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
    @Volatile private var webWasEnabled = false

    private fun isEnabled(vararg args: String): Boolean =
        exec("networksetup", *args).contains("Enabled: Yes")

    fun apply(socksPort: Int, httpPort: Int): Boolean {
        val service = activeService() ?: return false
        return try {
            if (!applied) {
                socksWasEnabled = isEnabled("-getsocksfirewallproxy", service)
                webWasEnabled = isEnabled("-getwebproxy", service)
                applied = true
            }
            exec("networksetup", "-setsocksfirewallproxy", service, "127.0.0.1", socksPort.toString())
            exec("networksetup", "-setsocksfirewallproxystate", service, "on")
            exec("networksetup", "-setwebproxy", service, "127.0.0.1", httpPort.toString())
            exec("networksetup", "-setwebproxystate", service, "on")
            true
        } catch (e: Exception) {
            false
        }
    }

    fun restore() {
        if (!applied) return
        applied = false
        val service = activeService() ?: return
        exec("networksetup", "-setsocksfirewallproxystate", service, if (socksWasEnabled) "on" else "off")
        exec("networksetup", "-setwebproxystate", service, if (webWasEnabled) "on" else "off")
    }
}
