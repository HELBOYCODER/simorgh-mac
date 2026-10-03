package com.simorgh.mac.engine

import com.simorgh.mac.Paths
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.BufferOverflow
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
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.concurrent.thread

/**
 * Handle on the `simorghd` engine daemon (docs/rpc-contract.md).
 *
 * Spawns the bundled binary with `--data-dir ~/Library/Application Support/Simorgh`,
 * waits for the `SIMORGH_READY` handshake line, then serves every RPC as a
 * suspend call and every long job as an NDJSON event flow. If the daemon dies
 * it is restarted with backoff, at most [MAX_RESTARTS] consecutive times.
 */
class EngineClient {

    @Volatile private var port: Int = -1
    @Volatile private var token: String = ""
    @Volatile private var process: Process? = null
    private val startMutex = Mutex()
    private var restarts = 0

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    /** Emits each time the daemon (re)starts — listeners re-sync their state. */
    private val _daemonRestarted = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val daemonRestarted: SharedFlow<Unit> = _daemonRestarted.asSharedFlow()

    val logFile: File get() = File(Paths.dataDir, "simorgh.log")

    val isAlive: Boolean get() = process?.isAlive == true

    /** Ensure the daemon is up; returns once the handshake line was read. */
    suspend fun ensureStarted(): Unit = startMutex.withLock {
        if (isAlive && port > 0) return
        val binary = Paths.bundledTool("simorghd")
            ?: error("simorghd binary not found (put it in vendor/simorghd)")
        withContext(Dispatchers.IO) { startDaemon(binary) }
        restarts = 0
    }

    private fun startDaemon(binary: File) {
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
            if (restarts < MAX_RESTARTS) {
                restarts++
                GlobalScope.launch(Dispatchers.IO + CoroutineName("daemon-restart")) {
                    runCatching {
                        Thread.sleep(500L * restarts)
                        startMutex.withLock { if (!isAlive) startDaemon(binary) }
                        _daemonRestarted.tryEmit(Unit)
                    }
                }
            }
        }
        _daemonRestarted.tryEmit(Unit)
    }

    fun stopDaemon() {
        runCatching { process?.destroy() }
        process = null
        port = -1
    }

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
            val bodyText = runCatching { response.body().use { it.sequence().toString() } }.getOrDefault("")
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
            .header("Connection", "close")
            .timeout(Duration.ofSeconds(60))
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        return withContext(Dispatchers.IO) {
            val response = try {
                http.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (e: Exception) {
                // A dead daemon mid-call: restart once, then retry.
                if (!isAlive) {
                    startMutex.withLock { if (!isAlive) ensureStartedLocked() }
                    val retry = http.send(request, HttpResponse.BodyHandlers.ofString())
                    JSONObject(retry.body())
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
        val shared = EngineClient()
    }
}

