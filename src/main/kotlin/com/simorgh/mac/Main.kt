package com.simorgh.mac

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.simorgh.mac.client.ServerRepository
import com.simorgh.mac.data.FirstRun
import com.simorgh.mac.data.ServerStore
import com.simorgh.mac.data.SettingsStore
import com.simorgh.mac.engine.EngineClient
import com.simorgh.mac.engine.Runner
import com.simorgh.mac.model.ConnState
import com.simorgh.mac.platform.SystemProxy
import com.simorgh.mac.platform.Tray
import com.simorgh.mac.str.Lang
import com.simorgh.mac.str.LocalContext
import com.simorgh.mac.update.AppUpdater
import com.simorgh.mac.ui.AppController
import com.simorgh.mac.ui.ZeroNetApp
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

fun main() {
    val client = EngineClient.shared
    val store = ServerStore.get()
    val settings = SettingsStore.get()
    val runner = Runner(client, store, settings)
    val repository = ServerRepository.get(store, runner.serversChanged)
    val updater = AppUpdater.get()
    val controller = AppController(runner, repository, settings, updater)

    // Headless-verification affordance: when SIMORGH_TAB names a tab, open the
    // app straight there with onboarding already done, so screenshots of each
    // screen can be captured without GUI automation. Inert unless the env is set.
    runCatching {
        System.getenv("SIMORGH_TAB")?.let { name ->
            controller.tab = com.simorgh.mac.ui.shell.Tab.valueOf(name)
            FirstRun.onboarded = true
        }
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        runCatching { SystemProxy.restore() }
        // A live root helper is torn down by the same stop-file contract the
        // Runner uses for disconnect: no second admin prompt, the daemon
        // notices the file within one second and exits, taking the engine
        // with it. Best-effort because the shutdown hook must not hang.
        runCatching {
            if (client.usePrivileged) {
                if (!client.stopFile.exists()) {
                    client.stopFile.createNewFile()
                    client.stopFile.setReadable(true, false)
                }
            }
        }
        runCatching { client.stopDaemon() }
    })

    application {
        val windowState = rememberWindowState(size = DpSize(480.dp, 820.dp))
        var trayIconImage by remember { mutableStateOf<BufferedImage?>(null) }
        DisposableEffect(Unit) {
            trayIconImage = loadIcon(16)
            Tray.install(
                image = { loadIcon(16) },
                onOpen = { windowState.isMinimized = false; windowState.placement = WindowPlacement.Floating },
                onToggle = { controller.toggle() },
                state = controller.engine.state,
                scope = controller.scope,
            )
            onDispose {
                Tray.uninstall()
                runCatching { SystemProxy.restore() }
                client.stopDaemon()
            }
        }
        Window(
            onCloseRequest = {
                // Close = disconnect cleanly, then quit (the tray's "Quit" is the same path).
                // The root helper is asked to exit through the stop-file
                // contract so no second admin prompt is needed.
                runCatching {
                    if (client.usePrivileged && !client.stopFile.exists()) {
                        client.stopFile.createNewFile()
                        client.stopFile.setReadable(true, false)
                    }
                }
                runCatching { com.simorgh.mac.platform.exec("pkill", "-f", "simorghd") }
                client.stopDaemon()
                runCatching { SystemProxy.restore() }
                exitApplication()
            },
            title = "Simorgh",
            state = windowState,
        ) {
            CompositionLocalProvider(LocalContext provides com.simorgh.mac.str.Ctx()) {
                ZeroNetApp(
                    controller = controller,
                    showOnboarding = !FirstRun.onboarded,
                    onOnboardingFinished = { FirstRun.onboarded = true },
                    onThemeResolved = { /* tray keeps its own tinted text; nothing to restyle on desktop */ },
                )
            }
        }
    }
}

/** The app icon shipped in resources — used for the tray and in-window branding. */
fun loadIcon(size: Int): BufferedImage? = runCatching {
    val stream = MainIcons::class.java.getResourceAsStream("/simorgh-icon.png")
        ?: File("src/main/resources/simorgh-icon.png").inputStream()
    val src = stream.use { ImageIO.read(it) } ?: return@runCatching null
    val out = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g = out.createGraphics()
    g.drawImage(src, 0, 0, size, size, null)
    g.dispose()
    out
}.getOrNull()

private object MainIcons
