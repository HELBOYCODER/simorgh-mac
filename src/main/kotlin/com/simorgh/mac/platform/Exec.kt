package com.simorgh.mac.platform

import java.awt.Desktop
import java.net.URI

/** Runs a command, returns trimmed stdout (empty on failure). */
fun exec(vararg cmd: String, timeoutMs: Long = 10_000): String = runCatching {
    val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    if (!p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
        p.destroyForcibly()
        return ""
    }
    out.trim()
}.getOrDefault("")

fun openUrl(url: String): Boolean = runCatching {
    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
        Desktop.getDesktop().browse(URI(url))
    } else {
        exec("open", url)
        true
    }
}.isSuccess
