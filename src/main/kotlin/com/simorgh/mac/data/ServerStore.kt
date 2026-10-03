package com.simorgh.mac.data

import com.simorgh.mac.Paths
import com.simorgh.mac.model.Server
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Subscription(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean,
    val updatedAt: Long,
    val count: Int,
)

/** One network's memory of a server: an EWMA score and the last success. */
private class HistEntry(var score: Double, var lastOk: Long)

/**
 * The server table on JSON files under the data dir — the desktop counterpart
 * of the Android app's shared SQLite store. Same query surface, same pruning
 * and scoring rules; only working discoveries are kept, user configs always.
 */
class ServerStore private constructor() {
    private val servers = LinkedHashMap<String, Server>()
    private val firstSeen = HashMap<String, Long>()
    private val history = HashMap<String, HashMap<String, HistEntry>>()
    private val subs = LinkedHashMap<String, Subscription>()

    private val serversFile = File(Paths.dataDir, "servers.json")
    private val historyFile = File(Paths.dataDir, "history.json")
    private val subsFile = File(Paths.dataDir, "subscriptions.json")

    init {
        load()
    }

    @Synchronized private fun load() {
        runCatching {
            val arr = JSONArray(serversFile.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val s = Server(
                    key = o.getString("key"), link = o.getString("link"), name = o.getString("name"),
                    protocol = o.getString("protocol"), transport = o.getString("transport"),
                    security = o.getString("security"), host = o.getString("host"), port = o.getInt("port"),
                    country = o.getString("country"), source = o.getString("source"),
                    favorite = o.optBoolean("favorite"), excluded = o.optBoolean("excluded"),
                    delayMs = o.optInt("delay_ms", -1), lastTestedAt = o.optLong("tested_at"),
                    aliveCount = o.optInt("alive_count"), failCount = o.optInt("fail_count"),
                    lastError = o.optString("last_error").ifBlank { null },
                    fingerprint = o.optString("fingerprint"),
                )
                servers[s.key] = s
                firstSeen[s.key] = o.optLong("first_seen", System.currentTimeMillis())
            }
        }
        runCatching {
            val root = JSONObject(historyFile.readText())
            for (network in root.keys()) {
                val byKey = HashMap<String, HistEntry>()
                val o = root.getJSONObject(network)
                for (key in o.keys()) {
                    val e = o.getJSONObject(key)
                    byKey[key] = HistEntry(e.getDouble("score"), e.getLong("last_ok"))
                }
                history[network] = byKey
            }
        }
        runCatching {
            val arr = JSONArray(subsFile.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val s = Subscription(
                    o.getString("id"), o.getString("name"), o.getString("url"),
                    o.optBoolean("enabled", true), o.optLong("updated_at"), o.optInt("count"),
                )
                subs[s.id] = s
            }
        }
    }

    @Synchronized private fun saveServers() {
        val arr = JSONArray()
        for ((key, s) in servers) {
            arr.put(
                JSONObject()
                    .put("key", s.key).put("link", s.link).put("name", s.name)
                    .put("protocol", s.protocol).put("transport", s.transport).put("security", s.security)
                    .put("host", s.host).put("port", s.port).put("country", s.country).put("source", s.source)
                    .put("favorite", s.favorite).put("excluded", s.excluded)
                    .put("delay_ms", s.delayMs).put("tested_at", s.lastTestedAt)
                    .put("alive_count", s.aliveCount).put("fail_count", s.failCount)
                    .put("last_error", s.lastError ?: "").put("fingerprint", s.fingerprint)
                    .put("first_seen", firstSeen[key] ?: System.currentTimeMillis()),
            )
        }
        atomicWrite(serversFile, arr.toString())
    }

    @Synchronized private fun saveHistory() {
        val root = JSONObject()
        for ((network, byKey) in history) {
            val o = JSONObject()
            for ((key, e) in byKey) {
                o.put(key, JSONObject().put("score", e.score).put("last_ok", e.lastOk))
            }
            root.put(network, o)
        }
        atomicWrite(historyFile, root.toString())
    }

    @Synchronized private fun saveSubs() {
        val arr = JSONArray()
        for ((_, s) in subs) {
            arr.put(
                JSONObject().put("id", s.id).put("name", s.name).put("url", s.url)
                    .put("enabled", s.enabled).put("updated_at", s.updatedAt).put("count", s.count),
            )
        }
        atomicWrite(subsFile, arr.toString())
    }

    // -------------------------------------------------------------- reads

    @Synchronized fun all(): List<Server> = servers.values.sortedWith(
        compareBy<Server> { if (it.favorite) 0 else 1 }
            .thenBy { if (it.delayMs < 0) 1 else 0 }.thenBy { if (it.delayMs < 0) Int.MAX_VALUE else it.delayMs },
    )

    @Synchronized fun byKey(key: String): Server? = servers[key]

    @Synchronized fun byKeys(keys: Collection<String>): List<Server> = keys.mapNotNull { servers[it] }

    @Synchronized fun userServers(): List<Server> =
        servers.values.filter { it.isUser }.sortedWith(compareByDescending<Server> { it.favorite }.thenBy { it.name })

    @Synchronized fun inSubscription(id: String): List<Server> =
        servers.values.filter { it.source == Server.SOURCE_SUB_PREFIX + id }
            .sortedWith(compareBy({ if (it.delayMs < 0) 1 else 0 }.thenBy { if (it.delayMs < 0) Int.MAX_VALUE else it.delayMs }))

    @Synchronized fun inCountry(code: String): List<Server> =
        servers.values.filter { it.country == code }
            .sortedWith(compareBy({ if (it.delayMs < 0) 1 else 0 }.thenBy { if (it.delayMs < 0) Int.MAX_VALUE else it.delayMs }))

    @Synchronized fun historyLinks(network: String, limit: Int): List<String> =
        history[network]?.entries?.filter { servers[it.key]?.excluded != true }
            ?.sortedWith(compareByDescending<Map.Entry<String, HistEntry>> { it.value.score }.thenByDescending { it.value.lastOk })
            ?.take(limit)?.mapNotNull { servers[it.key]?.link } ?: emptyList()

    @Synchronized fun subscriptions(): List<Subscription> = subs.values.sortedBy { it.name }

    @Synchronized fun count(): Int = servers.size

    @Synchronized fun excludedKeys(): Set<String> = servers.values.filter { it.excluded }.map { it.key }.toSet()

    // -------------------------------------------------------------- writes

    /** Insert or refresh descriptive fields; keeps favourite/stats of an existing row. */
    @Synchronized fun upsert(newServers: Collection<Server>) {
        if (newServers.isEmpty()) return
        val now = System.currentTimeMillis()
        for (s in newServers) {
            val existing = servers[s.key]
            if (existing == null) {
                servers[s.key] = s
                firstSeen[s.key] = now
            } else {
                // Never downgrade a user config to a feed one.
                val source = if (s.source.startsWith(Server.SOURCE_FEED_PREFIX)) existing.source else s.source
                servers[s.key] = s.copy(
                    favorite = existing.favorite, excluded = existing.excluded,
                    delayMs = existing.delayMs, lastTestedAt = existing.lastTestedAt,
                    aliveCount = existing.aliveCount, failCount = existing.failCount,
                    lastError = existing.lastError, source = source,
                    fingerprint = s.fingerprint.ifBlank { existing.fingerprint },
                )
            }
        }
        saveServers()
    }

    /** Record a test result, and credit/debit the per-network history. */
    @Synchronized fun recordResult(key: String, delayMs: Int, network: String?, error: String? = null) {
        val s = servers[key] ?: return
        val now = System.currentTimeMillis()
        servers[key] = if (delayMs >= 0) {
            s.copy(delayMs = delayMs, lastTestedAt = now, aliveCount = s.aliveCount + 1, lastError = null)
        } else {
            s.copy(delayMs = -1, lastTestedAt = now, failCount = s.failCount + 1, lastError = error?.take(300))
        }
        if (network != null) {
            val byKey = history.getOrPut(network) { HashMap() }
            val entry = byKey[key]
            if (delayMs >= 0) {
                val score = successScore(delayMs)
                if (entry == null) byKey[key] = HistEntry(score, now)
                else {
                    entry.score = entry.score * 0.5 + score
                    entry.lastOk = now
                }
            } else if (entry != null) {
                entry.score *= 0.25
            }
            saveHistory()
        }
        saveServers()
    }

    @Synchronized fun setFavorite(key: String, favorite: Boolean) {
        servers[key]?.let { servers[key] = it.copy(favorite = favorite); saveServers() }
    }

    @Synchronized fun setExcluded(key: String, excluded: Boolean) {
        servers[key]?.let { servers[key] = it.copy(excluded = excluded); saveServers() }
    }

    @Synchronized fun delete(keys: Collection<String>) {
        for (k in keys) {
            servers.remove(k)
            history.values.forEach { it.remove(k) }
        }
        saveServers()
        saveHistory()
    }

    @Synchronized fun upsertSubscription(sub: Subscription) {
        subs[sub.id] = sub
        saveSubs()
    }

    @Synchronized fun deleteSubscription(id: String) {
        subs.remove(id)
        servers.values.filter { it.source == Server.SOURCE_SUB_PREFIX + id && !it.favorite }
            .forEach { servers.remove(it.key) }
        saveSubs()
        saveServers()
    }

    /** Keep the table bounded: evict the least reliable discovered configs. */
    @Synchronized fun prune(maxDiscovered: Int = MAX_DISCOVERED) {
        val feedServers = servers.values.filter { !it.favorite && it.source.startsWith("feed:") }
            .sortedWith(compareByDescending<Server> { it.aliveCount - it.failCount }.thenByDescending { it.lastTestedAt })
        feedServers.drop(maxDiscovered).forEach { servers.remove(it.key) }
        history.keys.toList().forEach { network -> history[network]?.keys?.retainAll(servers.keys) }
        saveServers()
        saveHistory()
    }

    /** Update a subscription's meta after a refresh. */
    @Synchronized fun touchSubscription(sub: Subscription, count: Int) {
        subs[sub.id] = sub.copy(updatedAt = System.currentTimeMillis(), count = count)
        saveSubs()
    }

    companion object {
        const val MAX_DISCOVERED = 2000

        /** Faster answers earn more; any success earns at least 1. */
        fun successScore(delayMs: Int): Double = 1.0 + 1000.0 / (delayMs.coerceAtLeast(50) + 250.0)

        @Volatile private var instance: ServerStore? = null
        fun get(): ServerStore = instance ?: synchronized(this) { instance ?: ServerStore().also { instance = it } }

        fun atomicWrite(file: File, text: String) {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.writeText(text)
                tmp.delete()
            }
        }
    }
}
