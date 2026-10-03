package com.simorgh.mac.platform

import com.simorgh.mac.model.ConnState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.image.BufferedImage

/**
 * The desktop counterpart of the Android QuickSettings tile: a menu-bar icon
 * with connect / disconnect and a live status line.
 */
object Tray {
    private var trayIcon: TrayIcon? = null
    private var statusItem: MenuItem? = null
    private var toggleItem: MenuItem? = null
    private var observe: Job? = null

    fun install(
        image: () -> BufferedImage?,
        onOpen: () -> Unit,
        onToggle: () -> Unit,
        state: StateFlow<ConnState>,
        scope: CoroutineScope,
    ) {
        if (!SystemTray.isSupported()) return
        uninstall()
        val popup = PopupMenu()
        statusItem = MenuItem(statusText(state.value)).also { it.isEnabled = false }
        toggleItem = MenuItem(toggleText(state.value)).also { item ->
            item.addActionListener { onToggle() }
        }
        popup.add(statusItem)
        popup.addSeparator()
        popup.add(toggleItem)
        popup.add(MenuItem("Open Simorgh").apply { addActionListener { onOpen() } })
        popup.addSeparator()
        popup.add(MenuItem("Quit").apply { addActionListener { exitProcess() } })
        val img = image() ?: BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        val icon = TrayIcon(img, "Simorgh", popup).apply {
            isImageAutoSize = true
            addActionListener { onOpen() }
        }
        runCatching { SystemTray.getSystemTray().add(icon) }.onFailure { return }
        trayIcon = icon
        observe = scope.launch {
            state.collect { s ->
                statusItem?.label = statusText(s)
                toggleItem?.label = toggleText(s)
                icon.toolTip = "Simorgh — ${statusText(s)}"
            }
        }
    }

    fun uninstall() {
        observe?.cancel()
        trayIcon?.let { runCatching { SystemTray.getSystemTray().remove(it) } }
        trayIcon = null
    }

    private fun statusText(s: ConnState): String = when (s) {
        is ConnState.Connected -> "Connected via ${s.server.name}"
        is ConnState.Searching -> "Searching…"
        is ConnState.Connecting -> "Connecting…"
        is ConnState.Reconnecting -> "Reconnecting…"
        is ConnState.Disconnecting -> "Disconnecting…"
        is ConnState.Failed -> "Failed"
        ConnState.Idle -> "Disconnected"
    }

    private fun toggleText(s: ConnState): String = if (s.isActive) "Disconnect" else "Connect"
}

private fun exitProcess() {
    kotlin.system.exitProcess(0)
}
