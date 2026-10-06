package com.simorgh.mac.platform

import com.simorgh.mac.Paths
import java.io.File
import java.util.UUID

private const val DAEMON_LABEL = "sh.simorgh.mac.helper"
private const val DAEMON_PLIST_PATH = "/Library/LaunchDaemons/$DAEMON_LABEL.plist"
private const val TUN_PORT = 37038

/**
 * Simorgh's privileged helper, modelled on the mechanism Vulpine ships: a
 * root LaunchDaemon installed with ONE administrator prompt that then stays
 * installed across app restarts, reboots and reinstalls. The app talks to it
 * through a nonce-guarded cmd/done file pair; the daemon never exits on its
 * own (KeepAlive), so the user is asked for a password exactly once.
 *
 * Beyond Vulpine's proxy-only helper, this one can also raise the real TUN
 * tunnel: `start_vpn` runs a root simorghd (whole-system tunnel: browser,
 * Telegram, everything), `stop_vpn` takes it down.
 */
object SimorghHelper {

    private val dir: File get() = File(Paths.dataDir, "helper").apply { mkdirs() }
    private val cmdFile get() = File(dir, "cmd")
    private val doneFile get() = File(dir, "cmd.done")
    private val stopFile get() = File(dir, "stop")
    private val startedFile get() = File(dir, "watcher.started")
    private val scriptFile get() = File(dir, "simorgh-helper.sh")
    private val plistStagingFile get() = File(dir, "simorgh-helper.plist")
    private val proxyPortFile get() = File(dir, "proxy_port")

    private val tunDir get() = File(Paths.dataDir, "tun").apply { mkdirs() }
    private val tokenFile get() = File(tunDir, "tun-token")
    private val stopTunFile get() = File(tunDir, "STOP")
    private val tunLogFile get() = File(tunDir, "simorgh-tun.log")

    @Volatile private var promptedForAdmin = false

    private fun engineBinary(): File? = Paths.bundledTool("simorghd")

    private fun watcherAlive(): Boolean {
        val printOut = runCatching {
            ProcessBuilder("launchctl", "print", "system/$DAEMON_LABEL")
                .redirectErrorStream(true).start().inputStream.bufferedReader().readText()
        }.getOrDefault("")
        if ("state = running" in printOut) return true
        return ProcessHandle.allProcesses()
            .anyMatch { h -> h.info().commandLine().orElse("").contains("simorgh-helper.sh") }
    }

    fun isInstalled(): Boolean = watcherAlive()

    /** The script body mirrors Vulpine's MacHelper: active-service lookup, cmd loop, uninstall. */
    private fun writeScript(): Boolean {
        val binary = engineBinary()?.absolutePath ?: return false
        val desired = """
            |#!/bin/sh
            |# Simorgh privileged helper - generated, do not edit.
            |PATH="/usr/bin:/bin:/usr/sbin:/sbin"; export PATH
            |DIR="${dir.absolutePath}"
            |TUN="${tunDir.absolutePath}"
            |DATA="${Paths.dataDir.absolutePath}"
            |BIN="$binary"
            |OWNER="${System.getProperty("user.name")}"
            |
            |active_service() {
            |  eval `netstat -rn -f inet | awk '$1=="default" && ${'$'}NF ~ /^en/ {print "GW="${'$'}2"; PIF="${'$'}NF; exit}'`
            |  [ -z "${'$'}PIF" ] && eval `netstat -rn -f inet | awk '$1=="default" {print "GW="${'$'}2"; PIF="${'$'}NF; exit}'`
            |  [ -z "${'$'}PIF" ] && return
            |  networksetup -listnetworkserviceorder | awk -v ifc="${'$'}PIF" '
            |    /^\([0-9]+\)/ { name=${'$'}0; sub(/^[^)]*\) */, "", name) }
            |    index(${'$'}0, "Device: " ifc) > 0 { print name; exit }
            |  '
            |}
            |
            |vpn_up() { pgrep -f "tun/tun-token" >/dev/null 2>&1; }
            |
            |handle() {
            |  case "${'$'}1" in
            |    start_vpn)
            |      vpn_up && return
            |      umask 022
            |      rm -f "${'$'}TUN/STOP" "${'$'}TUN/tun-token"
            |      "${'$'}BIN" --data-dir "${'$'}DATA" --listen 127.0.0.1:$TUN_PORT \
            |        --token-file "${'$'}TUN/tun-token" --stop-file "${'$'}TUN/STOP" \
            |        >> "${'$'}TUN/simorgh-tun.log" 2>&1 &
            |      i=0
            |      while [ ${'$'}i -lt 50 ]; do
            |        [ -f "${'$'}TUN/tun-token" ] && chown "${'$'}OWNER" "${'$'}TUN/tun-token" 2>/dev/null && break
            |        i=${'$'}((i+1)); sleep 0.1
            |      done
            |      ;;
            |    stop_vpn)
            |      pkill -f "tun/tun-token" 2>/dev/null
            |      rm -f "${'$'}TUN/tun-token"
            |      ;;
            |    start_proxy)
            |      SVC=`active_service`
            |      PORT=`cat "${'$'}DIR/proxy_port" 2>/dev/null`
            |      [ -n "${'$'}SVC" ] && [ -n "${'$'}PORT" ] || return
            |      networksetup -setsocksfirewallproxy "${'$'}SVC" 127.0.0.1 "${'$'}PORT" >/dev/null 2>&1
            |      networksetup -setsocksfirewallproxystate "${'$'}SVC" on >/dev/null 2>&1
            |      ;;
            |    stop_proxy)
            |      SVC=`active_service`
            |      [ -n "${'$'}SVC" ] && networksetup -setsocksfirewallproxystate "${'$'}SVC" off >/dev/null 2>&1
            |      ;;
            |    set_dns)
            |      SVC=`active_service`
            |      [ -n "${'$'}SVC" ] && networksetup -setdnsservers "${'$'}SVC" 1.1.1.1 8.8.8.8 >/dev/null 2>&1
            |      ;;
            |    clear_dns)
            |      SVC=`active_service`
            |      [ -n "${'$'}SVC" ] && networksetup -setdnsservers "${'$'}SVC" "Empty" >/dev/null 2>&1
            |      ;;
            |  esac
            |}
            |
            |uninstall() {
            |  handle stop_proxy
            |  handle stop_vpn
            |  rm -f "${'$'}DIR/cmd" "${'$'}DIR/cmd.done" "${'$'}DIR/stop" "${'$'}DIR/watcher.started"
            |  launchctl bootout system/$DAEMON_LABEL 2>/dev/null
            |  rm -f "$DAEMON_PLIST_PATH"
            |  exit 0
            |}
            |
            |[ -f "${'$'}DIR/stop" ] && uninstall
            |touch "${'$'}DIR/watcher.started"
            |while :; do
            |  [ -f "${'$'}DIR/stop" ] && uninstall
            |  if [ -f "${'$'}DIR/cmd" ] && [ ! -f "${'$'}DIR/cmd.done" ]; then
            |    nonce=`head -1 "${'$'}DIR/cmd"`
            |    action=`sed -n 2p "${'$'}DIR/cmd"`
            |    handle "${'$'}action"
            |    echo "${'$'}nonce" > "${'$'}DIR/cmd.done"
            |  fi
            |  sleep 0.3
            |done
            """.trimMargin() + "\n"
        val previous = scriptFile.takeIf { it.exists() }?.readText()
        if (previous == desired) return false
        scriptFile.writeText(desired)
        scriptFile.setReadable(true, false)
        return true
    }

    private fun writePlist() {
        plistStagingFile.writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
              <key>Label</key><string>$DAEMON_LABEL</string>
              <key>ProgramArguments</key>
              <array><string>/bin/sh</string><string>${scriptFile.absolutePath}</string></array>
              <key>RunAtLoad</key><true/>
              <key>KeepAlive</key><true/>
              <key>StandardOutPath</key><string>${File(dir, "launchd.out").absolutePath}</string>
              <key>StandardErrorPath</key><string>${File(dir, "launchd.err").absolutePath}</string>
            </dict>
            </plist>
            """.trimIndent() + "\n",
        )
    }

    /** One administrator prompt in the app's lifetime; afterwards the daemon is silent. */
    fun install(): Boolean {
        val scriptChanged = writeScript()
        if (watcherAlive() && !scriptChanged) return true
        writePlist()
        runCatching { stopFile.delete() }
        runCatching { startedFile.delete() }
        val installCommand =
            "cp '${plistStagingFile.absolutePath}' '$DAEMON_PLIST_PATH' && " +
                "chown root:wheel '$DAEMON_PLIST_PATH' && chmod 644 '$DAEMON_PLIST_PATH' && " +
                "launchctl bootout system/$DAEMON_LABEL 2>/dev/null; " +
                "launchctl bootstrap system '$DAEMON_PLIST_PATH'"
        val promptScript =
            "do shell script \"$installCommand\" " +
                "with prompt \"Simorgh needs administrator access once to install its VPN helper. This is the last time it will ask.\" " +
                "with administrator privileges"
        val result = runCatching {
            ProcessBuilder("osascript", "-e", promptScript)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start().waitFor()
        }
        if (result.getOrDefault(1) != 0) return false
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (startedFile.exists() && watcherAlive()) return true
            Thread.sleep(250)
        }
        return watcherAlive()
    }

    private fun sendCommand(action: String, timeoutMs: Long = 30_000): Boolean {
        if (!watcherAlive()) {
            promptedForAdmin = true
            if (!install()) return false
        }
        runCatching { doneFile.delete() }
        runCatching { cmdFile.delete() } // a stale cmd would be answered with the wrong nonce
        val nonce = UUID.randomUUID().toString()
        cmdFile.writeText("$nonce\n$action\n")
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (doneFile.exists() && doneFile.readText().trim() == nonce) {
                runCatching { cmdFile.delete(); doneFile.delete() }
                return true
            }
            Thread.sleep(150)
        }
        return false
    }

    /** Bring up the root engine daemon; the app then adopts it over RPC. */
    fun startVpn(): Boolean = sendCommand("start_vpn")

    fun stopVpn(): Boolean = sendCommand("stop_vpn")

    fun startProxy(port: Int): Boolean {
        proxyPortFile.writeText(port.toString())
        return sendCommand("start_proxy")
    }

    fun stopProxy(): Boolean = sendCommand("stop_proxy")

    /** Point the system resolver at addresses that route into the tunnel. */
    fun setDns(): Boolean = sendCommand("set_dns")

    fun clearDns(): Boolean = sendCommand("clear_dns")

    /** True when the one-time prompt has already been shown and declined. */
    val isAdminAvailable: Boolean get() = watcherAlive() || !promptedForAdmin

    val tokenFileRef: File get() = tokenFile
    val stopTunFileRef: File get() = stopTunFile
    val tunLogFileRef: File get() = tunLogFile
}
