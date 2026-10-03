package com.simorgh.mac.data

import com.simorgh.mac.Paths
import com.simorgh.mac.model.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Crowd rankings (FEEDS-NOTES.md §4): rankings.json says which servers other
 * users on this network found working. The client rules are the Android
 * app's: a cached copy younger than 20 minutes is reused; failures fall back
 * to the cache at any age; a download only replaces the cache when its
 * generated_at is strictly newer, and a stamp over 24 h ahead is refused.
 */
object Rankings {
    data class Entry(val id: String, val link: String, val score: Double, val reporters: Int, val ms: Int)

    private val cacheFile = File(Paths.dataDir, "rankings.json")
    @Volatile private var loadedAt = 0L
    @Volatile private var entries: List<Entry> = emptyList()
    @Volatile var relays: List<String> = emptyList()
        private set

    /** Servers the crowd found working, highest score first. */
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    fun servers(): List<Entry> = entries

    suspend fun refreshIfNeeded(force: Boolean = false) {
        val age = System.currentTimeMillis() - loadedAt
        if (!force && entries.isNotEmpty() && age < 20 * 60_000) return
        val body = fetchFirst() ?: run {
            if (entries.isEmpty()) loadCache() // fall back to the cache at any age
            return
        }
        val parsed = runCatching { JSONObject(body) }.getOrNull() ?: return
        if (parsed.optInt("v") != 1) return // a captive-portal page or a new schema: refuse
        val generatedAt = parsed.optLong("generated_at")
        val cached = readCachedStamp()
        if (generatedAt <= 0 || generatedAt > System.currentTimeMillis() / 1000 + 86_400 || generatedAt <= cached) return
        saveCache(body, generatedAt)
        parse(parsed)
    }

    private suspend fun fetchFirst(): String? = withContext(Dispatchers.IO) {
        for (url in Sources.RANKINGS_URLS) {
            val text = runCatching { httpGet(url, 4L shl 20) }.getOrNull()
            if (text != null && text.isNotBlank()) return@withContext text
        }
        null
    }

    private suspend fun parse(root: JSONObject) {
        relays = root.optJSONArray("relays")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
        val out = ArrayList<Entry>()
        val nets = root.optJSONObject("nets") ?: return
        // Reading order: no per-network hint on desktop yet, so the worldwide
        // bucket plus any asn/cell buckets, without duplicate ids.
        val seen = HashSet<String>()
        for (netKey in nets.keys()) {
            val servers = nets.getJSONObject(netKey).optJSONArray("servers") ?: continue
            for (i in 0 until servers.length()) {
                val s = servers.getJSONObject(i)
                val id = s.optString("id")
                if (id.isBlank() || !seen.add(id)) continue
                out += Entry(id, s.getString("link"), s.optDouble("score", 0.0), s.optInt("reporters"), s.optInt("ms"))
            }
        }
        entries = out.sortedByDescending { it.score }
        applyToStore()
    }

    /** Crowd-ranked servers join the store as `feed:crowd`; the badge follows. */
    private suspend fun applyToStore() {
        val store = ServerStore.get()
        val byKey = entries.mapNotNull { e ->
            val existing = store.all().firstOrNull { it.key == e.id }
            if (existing != null) {
                if (existing.source.startsWith(Server.SOURCE_FEED_PREFIX)) {
                    existing.copy(source = Server.SOURCE_FEED_PREFIX + "crowd", delayMs = if (e.ms > 0) e.ms else existing.delayMs)
                } else null
            } else {
                runCatching {
                    val answer = com.simorgh.mac.engine.EngineClient.shared.rpc("parse_links", JSONObject().put("text", e.link))
                    answer.optJSONArray("items")?.takeIf { it.length() > 0 }
                        ?.let { Server.fromLinkInfo(it.getJSONObject(0), Server.SOURCE_FEED_PREFIX + "crowd") }
                }.getOrNull()
            }
        }
        if (byKey.isNotEmpty()) store.upsert(byKey)
    }

    private fun readCachedStamp(): Long = runCatching {
        JSONObject(File(Paths.dataDir, "rankings.stamp.json").readText()).getLong("generated_at")
    }.getOrDefault(0L)

    private suspend fun loadCache() {
        runCatching {
            val root = JSONObject(cacheFile.readText())
            if (root.optInt("v") == 1) parse(root)
        }
    }

    private fun saveCache(body: String, stamp: Long) {
        runCatching {
            cacheFile.writeText(body)
            File(Paths.dataDir, "rankings.stamp.json").writeText(JSONObject().put("generated_at", stamp).toString())
        }
    }

    /**
     * Crowd report (FEEDS-NOTES §5): after a search, anonymously share which
     * public servers and clean IPs worked, through the tunnel when it is up.
     * Only feed-sourced results are reported — never user imports.
     */
    fun reportResults(results: List<Pair<Server, Int>>, clean: List<Pair<String, Int>> = emptyList()) {
        val relays = relays
        if (relays.isEmpty()) return
        val public = results.filter { (s, _) -> s.source.startsWith(Server.SOURCE_FEED_PREFIX) }
        if (public.isEmpty()) return
        val body = JSONObject()
            .put("v", 1)
            .put("nonce", installSalt())
            .put(
                "results",
                JSONArray(public.sortedByDescending { (_, ms) -> ms >= 0 }
                    .take(40)
                    .map { (s, ms) -> JSONObject().put("id", s.key).put("ok", ms >= 0).put("ms", if (ms < 0) JSONObject.NULL else ms) }),
            )
            .put("clean", JSONArray(clean.take(10).map { (ip, ms) -> JSONObject().put("ip", ip).put("ms", ms) }))
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob()).launchQuietly {
            for (relay in relays) {
                if (!relay.startsWith("https://")) continue
                val ok = runCatching { httpPost("$relay/v1/report", body.toString()) }.isSuccess
                if (ok) return@launchQuietly
            }
        }
    }

    private fun installSalt(): String {
        val f = File(Paths.dataDir, "salt")
        if (!f.exists()) f.writeText(java.security.SecureRandom().let { r -> ByteArray(16).also { r.nextBytes(it) }.joinToString("") { "%02x".format(it) } })
        return f.readText()
    }

    private fun httpGet(url: String, maxBytes: Long): String {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.instanceFollowRedirects = true
        try {
            val body = conn.inputStream.use { it.readBytes() }
            if (body.size.toLong() > maxBytes) error("body too large")
            return String(body, Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
    }

    private fun httpPost(url: String, body: String) {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        conn.disconnect()
        if (code !in 200..299) error("relay answered $code")
    }

    private fun kotlinx.coroutines.CoroutineScope.launchQuietly(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
        launch { runCatching { block() } }
    }
}
