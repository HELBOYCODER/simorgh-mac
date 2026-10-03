package com.simorgh.mac.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.simorgh.mac.client.ServerRepository
import com.simorgh.mac.data.ServerStore
import com.simorgh.mac.data.SettingsStore
import com.simorgh.mac.engine.EngineClient
import com.simorgh.mac.engine.Runner
import com.simorgh.mac.model.ConnState
import com.simorgh.mac.model.ConnectTarget
import com.simorgh.mac.model.Settings
import com.simorgh.mac.platform.openUrl
import com.simorgh.mac.str.Lang
import com.simorgh.mac.ui.shell.AppMessages
import com.simorgh.mac.ui.shell.Tab
import com.simorgh.mac.update.AppUpdater
import com.simorgh.mac.update.UpdateState
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What needs the host: opening URLs (browser) and the clipboard (AWT). */
interface PlatformActions {
    fun requestVpnPermission(onResult: (Boolean) -> Unit)
    fun requestNotificationPermission(onResult: (Boolean) -> Unit)
    fun requestLocalNetworkPermission(onResult: (Boolean) -> Unit)
    fun openVpnSettings()
    fun openUrl(url: String)
    fun applyLanguage(settings: Settings)
}

/**
 * The UI's single entry point to the engine, the server store and settings —
 * the desktop port of the Android AppController. Screens stay stateless.
 */
@Stable
class AppController(
    val engine: Runner,
    val servers: ServerRepository,
    val settings: SettingsStore,
    val updater: AppUpdater,
) {
    val messages = AppMessages()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("controller"))

    var updateSheetOpen by mutableStateOf(false)
    private var showNextFinding = false
    var pendingImport by mutableStateOf<String?>(null)
    var tab by mutableStateOf(Tab.Home)

    var connectedWith by mutableStateOf<Settings?>(null)
        private set

    val platform: PlatformActions = object : PlatformActions {
        // macOS proxy mode needs no permission; the TUN path is surfaced as an
        // error dialog by the Runner when simorghd answers "requires root".
        override fun requestVpnPermission(onResult: (Boolean) -> Unit) = onResult(true)
        override fun requestNotificationPermission(onResult: (Boolean) -> Unit) = onResult(true)
        override fun requestLocalNetworkPermission(onResult: (Boolean) -> Unit) = onResult(true)
        override fun openVpnSettings() {
            openUrl("x-apple.systempreferences:com.apple.preferences.network")
        }
        override fun openUrl(url: String) { openUrl(url) }
        override fun applyLanguage(settings: Settings) { Lang.language = settings.language }
    }

    init {
        platform.applyLanguage(settings.current)
        updater.proxyPort = {
            if (engine.state.value is ConnState.Connected) settings.current.httpPort else null
        }
        scope.launch {
            updater.state.collect { s ->
                when (s) {
                    is UpdateState.Available -> if (showNextFinding || s.release.version != updater.dismissedVersion) {
                        updateSheetOpen = true
                    }
                    is UpdateState.UpToDate -> if (showNextFinding) messages.show(com.simorgh.mac.str.str("settings_update_latest"))
                    is UpdateState.CheckFailed -> if (showNextFinding) messages.show(com.simorgh.mac.str.str("settings_update_failed", s.message))
                    else -> {}
                }
                if (s !is UpdateState.Checking && s !is UpdateState.Idle) showNextFinding = false
            }
        }
        updater.checkOnStart()
        scope.launch {
            engine.state.collect { s ->
                when {
                    s is ConnState.Connected && connectedWith == null -> connectedWith = settings.current
                    s == ConnState.Idle || s is ConnState.Failed -> connectedWith = null
                }
            }
        }
        // Keep the engine alive in the background so the first connect is quick.
        scope.launch { runCatching { EngineClient.shared.ensureStarted() } }
    }

    fun toggle() {
        if (engine.state.value.isActive) engine.disconnect() else connect()
    }

    fun connect(target: ConnectTarget = ConnectTarget.decode(settings.current.lastTarget)) {
        engine.connect(target)
    }

    fun selectTarget(target: ConnectTarget) {
        settings.update { it.copy(lastTarget = target.encode()) }
        if (engine.state.value.isActive) {
            reconnect(target)
        } else {
            connect(target)
        }
    }

    private var reconnectJob: kotlinx.coroutines.Job? = null

    fun reconnect(target: ConnectTarget = ConnectTarget.decode(settings.current.lastTarget)) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            if (engine.state.value.isActive) {
                engine.disconnect()
                withTimeoutOrNull(8_000) { engine.state.first { !it.isActive && it != ConnState.Disconnecting } }
            }
            connectedWith = null
            connect(target)
        }
    }

    fun openUpdates() {
        when (updater.state.value) {
            is UpdateState.Idle, is UpdateState.UpToDate, is UpdateState.CheckFailed -> {
                showNextFinding = true
                updater.check()
            }
            is UpdateState.Checking -> showNextFinding = true
            else -> updateSheetOpen = true
        }
    }

    fun dismissUpdate() {
        (updater.state.value as? UpdateState.Available)?.let { updater.dismissedVersion = it.release.version }
        updateSheetOpen = false
    }

    fun installUpdate() {
        updater.openDownloaded()
    }

    fun update(transform: (Settings) -> Settings) {
        val before = settings.current
        settings.update(transform)
        val after = settings.current
        if (before != after) {
            engine.pushSettings()
            if (before.language != after.language) platform.applyLanguage(after)
        }
    }

    fun needsReconnect(current: Settings, select: (Settings) -> Any): Boolean {
        val snapshot = connectedWith ?: return false
        if (engine.state.value !is ConnState.Connected) return false
        return select(snapshot) != select(current)
    }

    fun copy(text: String, sensitive: Boolean = false) {
        runCatching {
            val clip = java.awt.datatransfer.StringSelection(text)
            java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(clip, null)
        }
        messages.show(com.simorgh.mac.str.str("msg_copied"))
    }

    fun readClipboard(): String = runCatching {
        java.awt.Toolkit.getDefaultToolkit().systemClipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
    }.getOrNull().orEmpty()
}

val LocalController = staticCompositionLocalOf<AppController> { error("No AppController provided") }
