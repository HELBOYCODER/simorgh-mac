package com.simorgh.mac.engine

import com.simorgh.mac.Paths
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Handle on the `simorghd` engine daemon (docs/rpc-contract.md).
 *
 * Two endpoints live side by side:
 *  - the unprivileged child this class spawns (random loopback port, token
 *    read from the SIMORGH_READY handshake). This is the proxy-mode engine
 *    and the host of every discovery/test/scan job.
 *  - an externally-launched root helper (fixed loopback port [TUN_PORT],
 *    token in `<dataDir>/tun/tun-token`, graceful stop via
 *    `<dataDir>/tun/STOP`). The TUN inbound needs root; macOS prompts for
 *    admin once via [startPrivilegedHelper] and the daemon then polls the
 *    stop file so a later disconnect needs no second prompt.
 *
 * [usePrivileged] flips which endpoint every subsequent RPC goes to; the
 * Runner decides when a session is in TUN mode.
 */
class EngineClient {

    @Volatile private var port: Int = -1
    @Volatile private var token: String = ""
    @Volatile private var process: Process? = null
    @Volatile private var suppressRestart: Boolean = false
    private val startMutex = Mutex()
    private var restarts = 0

    @Volatile var usePrivileged: Boolean = false
        private set

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    /** Emits each time the daemon (re)starts — listeners re-sync their state. */
    private val _daemonRestarted = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val daemonRestarted: SharedFlow<Unit> = _daemonRestarted.asSharedFlow()

    val logFile: File get() = File(Paths.dataDir, "simorgh.log")
    val tunDir: File get() = File(Paths.dataDir, "tun").apply { mkdirs() }
    val tunTokenFile: File get() = File(tunDir, "tun-token")
    val tunLogFile: File get() = File(tunDir, "simorgh-tun.log")
    val stopFile: File get() = File(tunDir, "STOP")

    val isAlive: Boolean get() = if (usePrivileged) isPortOpen() else process?.isAlive == true

    /** Ensure the daemon is up; returns once the handshake line was read. */
    suspend fun ensureStarted(): Unit = startMutex.withLock {
        if (usePrivileged) {
            if (isPortOpen() && tunTokenFile.isFile && tunTokenFile.length() > 0) {
                token = tunTokenFile.readText().trim()
                port = TUN_PORT
                return
            }
            error("root helper is not running")
        }
        if (isAlive && port > 0) return
        val binary = Paths.bundledTool("simorghd")
            ?: error("simorghd binary not found (put it in vendor/simorghd)")
        // Packaging strips the exec bit from app-resources binaries; the bundle
        // lives on a user-writable volume once installed, so restore it here.
        runCatching { binary.setExecutable(true, false) }
        withContext(Dispatchers.IO) { startDaemon(binary) }
        restarts = 0
    }

    private fun startDaemon(binary: File) {
        suppressRestart = false
        val pb = ProcessBuilder(binary.absolutePath, "--data-dir", Paths.dataDir.absolutePath)
            .redirectErrorStream(false)
        val p = pb.start()
        p.inputStream.bufferedReader().useLines { lines ->
            val ready = lines.firstOrNull { it.startsWith(SIMORGH_READY) }
                ?: throw IllegalStateException("simorghd exited before becoming ready")
            val json = JSONObject(ready.removePrefix("$SIMORGH_READY "))
            port = json.getInt("port")
            token = json.getString("token")
        }
        // stderr: copy to the log file (the contract: anything before READY on
        // stderr is fatal start-up noise; after READY it is engine logging).
        val errReader = p.errorStream
        thread(name = "simorghd-stderr", isDaemon = true) {
            logFile.appendBytes(("\n--- launch ${System.currentTimeMillis()}\n").toByteArray())
            errReader.copyTo(logFile.outputStream().buffered(8192))
        }
        process = p
        p.onExit().thenAccept {
            // Watchdog: restart on crash with backoff, capped.
            process = null
            port = -1
            if (suppressRestart || usePrivileged) return@thenAccept
            if (restarts < MAX_RESTARTS) {
                restarts++
                GlobalScope.launch(Dispatchers.IO + CoroutineName("daemon-restart")) {
                    runCatching {
                        Thread.sleep(500L * restarts)
                        startMutex.withLock {
                            if (!isAlive && !suppressRestart && !usePrivileged) startDaemon(binary)
                        }
                        _daemonRestarted.tryEmit(Unit)
                    }
                }
            }
        }
        _daemonRestarted.tryEmit(Unit)
    }

    fun stopDaemon() {
        suppressRestart = true
        runCatching { process?.destroy() }
        process = null
        port = -1
    }

    // ------------------------------------------------- privileged (TUN) helper

    /**
     * Ask macOS for admin rights once, launch the root simorghd on
     * [TUN_PORT], read back the token file, then re-point every subsequent
     * RPC at the privileged endpoint. The unprivileged child is torn down so
     * its ports are free and its watchdog does not restart it.
     *
     * Returns true when the helper is answering; false when the user declined
     * the dialog or the daemon failed to bind.
     */
    suspend fun startPrivilegedHelper(): Boolean = withContext(Dispatchers.IO) {
        if (usePrivileged && isPortOpen() && tunTokenFile.isFile && tunTokenFile.length() > 0) {
            token = tunTokenFile.readText().trim()
            port = TUN_PORT
            return@withContext true
        }
        val binary = Paths.bundledTool("simorghd") ?: return@withContext false
        runCatching { tunTokenFile.delete() }
        runCatching { stopFile.delete() }
        runCatching { tunLogFile.delete() }
        val script = File(tunDir, "tun-launch.sh").apply {
            writeText(
                """
                |#!/bin/sh
                |umask 022
                |rm -f '${stopFile.absolutePath}' '${tunTokenFile.absolutePath}'
                |nohup '${binary.absolutePath}' \
                |  --data-dir '${Paths.dataDir.absolutePath}' \
                |  --listen 127.0.0.1:$TUN_PORT \
                |  --token-file '${tunTokenFile.absolutePath}' \
                |  --stop-file '${stopFile.absolutePath}' \
                |  >> '${tunLogFile.absolutePath}' 2>&1 </dev/null &
                |disown 2>/dev/null || true
                |exit 0
                |""".trimMargin(),
            )
            setExecutable(true, false)
        }
        // Tear down the unprivileged child first: its ports are about to be
        // replaced, and its watchdog would otherwise restart it.
        startMutex.withLock {
            suppressRestart = true
            runCatching { process?.destroy() }
            process = null
            port = -1
        }
        val prompt = "Simorgh needs administrator access once to open the VPN tunnel."
        // Escape any embedded backslash or double quote in the path before it
        // lands inside the AppleScript double-quoted string literal.
        val safeScriptPath = script.absolutePath.replace("\\", "\\\\").replace("\"", "\\\"")
        val appleScript = "do shell script \"sh '$safeScriptPath'\" " +
            "with administrator privileges with prompt \"$prompt\""
        val proc = runCatching {
            ProcessBuilder("osascript", "-e", appleScript)
                .redirectErrorStream(true)
                .start()
        }.getOrNull() ?: return@withContext false
        val finished = proc.waitFor(180, TimeUnit.SECONDS)
        if (!finished) { runCatching { proc.destroy() }; return@withContext false }
        if (proc.exitValue() != 0) return@withContext false
        // The helper writes the token file shortly after binding [TUN_PORT].
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (tunTokenFile.isFile && tunTokenFile.length() > 0 && isPortOpen()) {
                token = runCatching { tunTokenFile.readText().trim() }.getOrDefault("")
                port = TUN_PORT
                usePrivileged = true
                _daemonRestarted.tryEmit(Unit)
                return@withContext token.isNotEmpty()
            }
            delay(200)
        }
        false
    }

    /**
     * Ask the root daemon to exit by creating the world-readable stop file;
     * it polls every second (docs/rpc-contract.md "stop-file") and shuts the
     * engine down on the way out. Then re-arm the unprivileged child so the
     * discovery/test/scan jobs keep working for the next proxy session.
     */
    suspend fun stopPrivilegedHelper(): Unit = withContext(Dispatchers.IO) {
        if (!usePrivileged) return@withContext
        runCatching {
            if (!stopFile.exists()) {
                stopFile.createNewFile()
                stopFile.setReadable(true, false)
            }
        }
        val deadline = System.currentTimeMillis() + 6_000
        while (System.currentTimeMillis() < deadline && isPortOpen()) delay(200)
        usePrivileged = false
        port = -1
        token = ""
        runCatching { tunTokenFile.delete() }
        runCatching { stopFile.delete() }
        runCatching { ensureStarted() }
    }

    private fun isPortOpen(): Boolean = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", TUN_PORT), 400); true
        }
    }.getOrDefault(false)

    // ------------------------------------------------------------------ RPC

    /** POST /rpc/<method> with a JSON body; returns the parsed answer. */
    suspend fun rpc(method: String, body: JSONObject = JSONObject()): JSONObject =
        post("/rpc/$method", body)

    /** Start a job; returns its id. Throws EngineJobError when the answer carries {"error"}. */
    suspend fun startJob(method: String, body: JSONObject): String {
        val answer = post("/job/$method", body)
        answer.optString("error").takeIf { it.isNotEmpty() }?.let { throw EngineJobError(it) }
        return answer.getString("job")
    }

    suspend fun cancelJob(id: String): JSONObject = post("/job/$id/cancel", JSONObject())

    /**
     * Attach to a job's NDJSON event stream. Replays all events produced so
     * far, then streams live; completes after the terminal `done` event (EOF).
     */
    fun jobEvents(id: String): Flow<JSONObject> = flow {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/job/$id/events"))
            .header("Authorization", "Bearer $token")
            .GET()
            .timeout(Duration.ofHours(1))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofLines())
        if (response.statusCode() != 200) {
            val bodyText = runCatching { response.body().use { lines -> lines.map { it }.toString() } }.getOrDefault("")
            throw EngineJobError(bodyText.ifBlank { "job stream HTTP ${response.statusCode()}" })
        }
        response.body().use { lines ->
            val it = lines.iterator()
            while (it.hasNext()) {
                val line = it.next()
                if (line.isBlank()) continue
                val event = runCatching { JSONObject(line) }.getOrNull() ?: continue
                emit(event)
                if (event.optString("t") == "done") break
            }
        }
    }.flowOn(Dispatchers.IO)

    /** Run a job and collect its events; returns the `done` event. */
    suspend fun runJob(method: String, body: JSONObject, onEvent: suspend (JSONObject) -> Unit): JSONObject {
        val id = startJob(method, body)
        var done = JSONObject().put("t", "done")
        jobEvents(id).collect { e ->
            onEvent(e)
            if (e.optString("t") == "done") done = e
        }
        return done
    }

    private suspend fun post(path: String, body: JSONObject): JSONObject {
        ensureStarted()
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(60))
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        return withContext(Dispatchers.IO) {
            val response: HttpResponse<String> = try {
                http.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (e: Exception) {
                // A dead daemon mid-call: restart once, then retry. The
                // privileged helper is not restarted here: if it died the
                // user has to re-authorise, which is a Runner-level concern.
                if (!isAlive && !usePrivileged) {
                    startMutex.withLock { if (!isAlive) ensureStartedLocked() }
                    http.send(request, HttpResponse.BodyHandlers.ofString())
                } else throw e
            }
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                // Token/port lost to a silent restart: resync once.
                ensureStarted()
                val retry = http.send(
                    HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
                        .header("Authorization", "Bearer $token")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                JSONObject(retry.body())
            } else {
                JSONObject(response.body())
            }
        }
    }

    private suspend fun ensureStartedLocked() {
        val binary = Paths.bundledTool("simorghd") ?: return
        runCatching { startDaemon(binary) }
    }

    class EngineJobError(message: String) : Exception(message)

    companion object {
        private const val SIMORGH_READY = "SIMORGH_READY"
        private const val MAX_RESTARTS = 3
        /** Fixed loopback port the root helper binds so the GUI can find it without a handshake. */
        const val TUN_PORT = 37038
        val shared = EngineClient()
    }
}
