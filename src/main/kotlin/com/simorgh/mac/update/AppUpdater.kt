package com.simorgh.mac.update

import com.simorgh.mac.BuildConfig
import com.simorgh.mac.Paths
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI

/** A release newer than this build. */
data class ReleaseInfo(
    /** Without the leading "v". */
    val version: String,
    /** The release page, for installing by hand. */
    val page: String,
    /** What changed, one short line each. */
    val notes: List<String>,
    val dmgName: String,
    val dmgUrl: String,
    val dmgSize: Long,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState

    /** The check itself failed. */
    data class CheckFailed(val message: String) : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class Downloading(val release: ReleaseInfo, val received: Long, val total: Long) : UpdateState {
        val fraction: Float get() = if (total > 0) (received.toFloat() / total).coerceIn(0f, 1f) else 0f
    }

    /** Downloaded; opening the DMG mounts it for the drag-to-Applications step. */
    data class Ready(val release: ReleaseInfo, val dmg: File) : UpdateState
    data class NeedsReinstall(val release: ReleaseInfo) : UpdateState
    data class Failed(val release: ReleaseInfo, val message: String) : UpdateState
}

/**
 * GitHub Releases of HELBOYCODER/simorgh-mac: latest tag vs BuildConfig.VERSION_NAME,
 * the `Simorgh-macOS.dmg` asset. Ported semantics of the Android AppUpdater —
 * check, download (through the tunnel when it is up), then open the DMG.
 */
class AppUpdater private constructor() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("updater"))
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** When set and the runtime is connected, downloads go through the local HTTP proxy. */
    var proxyPort: () -> Int? = { null }
    var dismissedVersion: String = ""

    private var job: Job? = null
    private var downloadTarget: File? = null

    fun checkOnStart() {
        val last = runCatching { File(Paths.dataDir, "update-check").readText().toLongOrNull() }.getOrNull() ?: 0L
        if (System.currentTimeMillis() - last < 20 * 3_600_000L) return
        check()
    }

    fun check() {
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Checking
        job = scope.launch {
            val release = runCatching { fetchLatest() }
            release.exceptionOrNull()?.let {
                _state.value = UpdateState.CheckFailed(it.message ?: "network")
                return@launch
            }
            val r = release.getOrNull()
            File(Paths.dataDir, "update-check").writeText(System.currentTimeMillis().toString())
            if (r == null) {
                _state.value = UpdateState.CheckFailed("no release found")
            } else if (Versions.newer(r.version, BuildConfig.VERSION_NAME)) {
                _state.value = UpdateState.Available(r)
            } else {
                _state.value = UpdateState.UpToDate
            }
        }
    }

    private fun fetchLatest(): ReleaseInfo? {
        val conn = URI(REPO_API).toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (conn.responseCode != 200) error("GitHub answered ${conn.responseCode}")
            val body = conn.inputStream.use { String(it.readBytes()) }
            val json = org.json.JSONObject(body)
            val tag = json.optString("tag_name").removePrefix("v")
            if (tag.isBlank()) error("release has no tag")
            val notes = json.optString("body").lines()
                .map { it.trim().removePrefix("-").trim() }
                .filter { it.isNotBlank() && it.length < 200 }
                .take(12)
            var dmgName = ""
            var dmgUrl = ""
            var dmgSize = 0L
            val assets = json.optJSONArray("assets") ?: JSONArray()
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name")
                if (name.endsWith(".dmg", ignoreCase = true) && (name.startsWith("Simorgh") || dmgUrl.isEmpty())) {
                    dmgName = name
                    dmgUrl = a.getString("browser_download_url")
                    dmgSize = a.optLong("size")
                    if (name.startsWith("Simorgh")) break
                }
            }
            return ReleaseInfo(
                version = tag,
                page = json.optString("html_url", REPO_PAGE),
                notes = notes,
                dmgName = dmgName.ifBlank { "Simorgh-macOS.dmg" },
                dmgUrl = dmgUrl,
                dmgSize = dmgSize,
            )
        } finally {
            conn.disconnect()
        }
    }

    fun download() {
        val available = (_state.value as? UpdateState.Available)?.release
            ?: (_state.value as? UpdateState.Failed)?.release
            ?: return
        if (available.dmgUrl.isBlank()) {
            _state.value = UpdateState.Failed(available, "the release carries no downloadable DMG")
            return
        }
        _state.value = UpdateState.Downloading(available, 0, available.dmgSize)
        job = scope.launch {
            val outDir = File(Paths.dataDir, "updates").apply { mkdirs() }
            val out = File(outDir, available.dmgName)
            downloadTarget = out
            runCatching {
                val proxyPortValue = proxyPort()
                val proxy = if (proxyPortValue != null) Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPortValue)) else Proxy.NO_PROXY
                val conn = URI(available.dmgUrl).toURL().openConnection(proxy) as HttpURLConnection
                conn.connectTimeout = 20_000
                conn.readTimeout = 30_000
                val total = if (conn.contentLengthLong > 0) conn.contentLengthLong else available.dmgSize
                out.outputStream().use { sink ->
                    conn.inputStream.use { src ->
                        val buf = ByteArray(64 * 1024)
                        var received = 0L
                        while (true) {
                            val n = src.read(buf)
                            if (n < 0) break
                            sink.write(buf, 0, n)
                            received += n
                            _state.value = UpdateState.Downloading(available, received, total)
                        }
                    }
                }
                conn.disconnect()
                _state.value = UpdateState.Ready(available, out)
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                _state.value = UpdateState.Failed(available, it.message ?: "download failed")
            }
        }
    }

    fun cancel() {
        job?.cancel()
        downloadTarget?.delete()
        (_state.value as? UpdateState.Downloading)?.let { _state.value = UpdateState.Available(it.release) }
    }

    fun canInstall(): Boolean = _state.value is UpdateState.Ready

    /** The desktop counterpart of firing the install intent: mount the DMG in Finder. */
    fun openDownloaded(): Boolean {
        val ready = _state.value as? UpdateState.Ready ?: return false
        return runCatching {
            ProcessBuilder("open", ready.dmg.absolutePath).start().waitFor() == 0
        }.getOrDefault(false)
    }

    fun installIntent(): File? = (_state.value as? UpdateState.Ready)?.dmg

    companion object {
        const val REPO = "HELBOYCODER/simorgh-mac"
        private const val REPO_API = "https://api.github.com/repos/HELBOYCODER/simorgh-mac/releases/latest"
        private const val REPO_PAGE = "https://github.com/HELBOYCODER/simorgh-mac/releases/latest"

        @Volatile private var instance: AppUpdater? = null
        fun get(): AppUpdater = instance ?: synchronized(this) { instance ?: AppUpdater().also { instance = it } }
    }
}

/** major.minor.patch compared numerically; a pre-release sorts before its release. */
object Versions {
    fun parse(text: String): List<Long>? {
        val t = text.trim().trimStart('v', 'V')
        val core = t.substringBefore('-', t).substringBefore('+')
        if (core.isEmpty()) return null
        return core.split('.').map { it.toLongOrNull() ?: return null }
    }

    fun newer(candidate: String, installed: String): Boolean = compare(candidate, installed) > 0

    fun compare(a: String, b: String): Int {
        val pa = parse(a) ?: return 0
        val pb = parse(b) ?: return 0
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0L }
            val y = pb.getOrElse(i) { 0L }
            if (x != y) return x.compareTo(y)
        }
        // pre-release < release
        val preA = a.trim().trimStart('v', 'V').contains('-')
        val preB = b.trim().trimStart('v', 'V').contains('-')
        return when {
            preA && !preB -> -1
            !preA && preB -> 1
            else -> 0
        }
    }
}
