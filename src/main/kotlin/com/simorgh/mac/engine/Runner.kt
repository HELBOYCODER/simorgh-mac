package com.simorgh.mac.engine

import com.simorgh.mac.data.Rankings
import com.simorgh.mac.data.ServerStore
import com.simorgh.mac.data.SettingsStore
import com.simorgh.mac.data.Sources
import com.simorgh.mac.data.Subscription
import com.simorgh.mac.model.CheckStatus
import com.simorgh.mac.model.ConnState
import com.simorgh.mac.model.ConnectTarget
import com.simorgh.mac.model.ConnectionMode
import com.simorgh.mac.model.ConnectionProfile
import com.simorgh.mac.model.DiagCheck
import com.simorgh.mac.model.Diagnosis
import com.simorgh.mac.model.DiscoveryProgress
import com.simorgh.mac.model.DiscoveryStage
import com.simorgh.mac.model.EvasionLevel
import com.simorgh.mac.model.FailReason
import com.simorgh.mac.model.ImportResult
import com.simorgh.mac.model.LaneFail
import com.simorgh.mac.model.LaneOutcome
import com.simorgh.mac.model.RaceLane
import com.simorgh.mac.model.RaceState
import com.simorgh.mac.model.ScanResult
import com.simorgh.mac.model.ScanState
import com.simorgh.mac.model.Server
import com.simorgh.mac.model.ServerKind
import com.simorgh.mac.model.Settings
import com.simorgh.mac.model.TrafficStats
import com.simorgh.mac.model.WarpPhase
import com.simorgh.mac.model.WarpState
import com.simorgh.mac.model.classic
import com.simorgh.mac.platform.SystemProxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The desktop connection engine — the ported shape of the Android
 * `service/Engine.kt` state machine, talking HTTP to simorghd instead of JNI.
 *
 * Connect is a race, not a ranking: test this network's past winners, then
 * stream discovery, and bring the runtime up on the first config that really
 * carries traffic; further finds hot-reload into the balancer pool.
 */
class Runner(
    private val client: EngineClient,
    internal val store: ServerStore,
    internal val settings: SettingsStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("runner"))
    private val mutex = Mutex()

    // ---- Android Engine.kt constants, verbatim
    private val WANT_ALIVE = 5
    private val STOP_DISCOVERY_AT = 3
    private val BACKGROUND_WANT = 2
    private val BACKGROUND_SEARCH_MS = 20_000L
    private val PROBE_URL = "http://cp.cloudflare.com/generate_204"
    private val OWN_TIMEOUT_MS = 5_000
    private val HEALTH_INTERVAL_MS = 45_000L
    private val FRONT_VARIANTS = 18
    private val WARP_STEPS_KEPT = 8
    private val REASON_CHOSEN_DOWN = "chosen_down"

    /** Single desktop network identity (no per-ISP tables on macOS yet). */
    private val network: String get() = "mac"

    private val _state = MutableStateFlow<ConnState>(ConnState.Idle)
    val state: StateFlow<ConnState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(TrafficStats())
    val stats: StateFlow<TrafficStats> = _stats.asStateFlow()

    private val _scan = MutableStateFlow(ScanState())
    val scan: StateFlow<ScanState> = _scan.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _testProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val testProgress: StateFlow<Pair<Int, Int>?> = _testProgress.asStateFlow()

    private val _warp = MutableStateFlow(WarpState())
    val warp: StateFlow<WarpState> = _warp.asStateFlow()

    private val _race = MutableStateFlow(RaceState())
    val race: StateFlow<RaceState> = _race.asStateFlow()

    private val _diagnosis = MutableStateFlow(Diagnosis())
    val diagnosis: StateFlow<Diagnosis> = _diagnosis.asStateFlow()

    private val _serversChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val serversChanged: SharedFlow<Unit> = _serversChanged.asSharedFlow()

    private var target = ConnectTarget.Fastest
    private var running = false
    private var since = 0L
    private val pool = ArrayList<Alive>()
    private var rung: Ladder.Rung? = null
    private var connectJob: Job? = null
    private var monitorJob: Job? = null
    private var refreshJob: Job? = null
    private var testJob: Job? = null
    private var scanJob: Job? = null
    private var warpJob: Job? = null
    private var diagJob: Job? = null
    private var lastBackgroundFindAt = 0L
    private val crowdResults = HashMap<String, Int>()

    private class Alive(val server: Server, var delayMs: Int)

    // ------------------------------------------------------------ profiles

    private fun wantAlive(p: ConnectionProfile) = when (p) {
        ConnectionProfile.Normal, ConnectionProfile.Legacy -> WANT_ALIVE
        ConnectionProfile.Fast -> 1
        ConnectionProfile.Gaming -> 3
    }

    private fun stopDiscoveryAt(p: ConnectionProfile) = when (p) {
        ConnectionProfile.Normal, ConnectionProfile.Legacy -> STOP_DISCOVERY_AT
        ConnectionProfile.Fast -> 1
        ConnectionProfile.Gaming -> 2
    }

    private fun linksInConfig(p: ConnectionProfile) = when (p) {
        ConnectionProfile.Normal, ConnectionProfile.Legacy -> WANT_ALIVE
        ConnectionProfile.Fast, ConnectionProfile.Gaming -> 1
    }

    private fun suits(server: Server, p: ConnectionProfile): Boolean {
        if (server.excluded) return false
        if (rung?.relaxed == true) return true
        return when (p) {
            ConnectionProfile.Normal, ConnectionProfile.Legacy ->
                server.security == "tls" || server.security == "reality" || server.protocol == "hysteria2" || server.protocol == "tuic"
            ConnectionProfile.Fast -> true
            ConnectionProfile.Gaming -> server.kind != ServerKind.Cdn &&
                server.transport !in setOf("ws", "httpupgrade", "xhttp", "splithttp")
        }
    }

    // ------------------------------------------------------------- connect

    fun connect(t: ConnectTarget = ConnectTarget.decode(settings.current.lastTarget)) {
        if (connectJob?.isActive == true || running) return
        target = t
        connectJob = scope.launch { runConnection() }
    }

    private suspend fun runConnection() {
        pool.clear()
        rung = null
        crowdResults.clear()
        publish(ConnState.Searching(DiscoveryProgress()))
        try {
            when (val t = target) {
                is ConnectTarget.Specific -> {
                    val server = store.byKey(t.key)
                    if (server == null) { fail(FailReason.ServerUnavailable, ""); return }
                    publish(ConnState.Connecting(server))
                    pool += Alive(server, server.delayMs)
                    if (!bringUp()) return
                }
                is ConnectTarget.Country -> {
                    val candidates = store.inCountry(t.code)
                    if (candidates.isEmpty()) { fail(FailReason.ServerUnavailable, t.code); return }
                    testAndCollect(candidates)
                    if (!running) { fail(FailReason.NoWorkingServer, t.code); return }
                }
                is ConnectTarget.Subscription -> {
                    val candidates = store.inSubscription(t.id)
                    if (candidates.isEmpty()) { fail(FailReason.ServerUnavailable, ""); return }
                    testAndCollect(candidates)
                    if (!running) { fail(FailReason.NoWorkingServer, ""); return }
                }
                ConnectTarget.Fastest -> {
                    if (settings.current.profile == ConnectionProfile.Normal) {
                        climbLadder()
                    } else {
                        val tried = tryKnownFirst()
                        if (pool.size < stopDiscoveryAt(settings.current.profile)) discover(excludeKeys = tried)
                    }
                    if (!running) { fail(FailReason.NoWorkingServer, ""); return }
                }
            }
            reportCrowd()
            lastBackgroundFindAt = System.currentTimeMillis()
            monitorJob = scope.launch { monitor() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            teardown()
            fail(FailReason.CoreError, e.message.orEmpty())
        }
    }

    /** The ways of the ladder, remembered per network, fastest first. */
    private suspend fun climbLadder() {
        val prefs = File(com.simorgh.mac.Paths.dataDir, "ladder.json")
        val hasWarp = store.userServers().any { it.fingerprint.isNotEmpty() && !it.excluded }
        val remembered = runCatching { JSONObject(prefs.readText()).optString("net", "").ifBlank { null } }.getOrNull()
        val order = Ladder.order(Ladder.indexOf(remembered), hasWarp = hasWarp)
        var tried = emptySet<String>()
        for ((step, index) in order.withIndex()) {
            val way = Ladder.rungs[index]
            rung = way
            publish(ConnState.Searching(DiscoveryProgress(method = way.id)))
            when {
                way.known -> tried = tryKnownFirst()
                way.warp -> testAndCollect(store.userServers().filter { it.fingerprint.isNotEmpty() && !it.excluded })
                else -> discover(excludeKeys = tried)
            }
            if (running) {
                runCatching { prefs.writeText(JSONObject().put("net", way.id).toString()) }
                if (pool.size < stopDiscoveryAt(settings.current.profile) && way.known) {
                    rung = Ladder.rungs[Ladder.SEARCH]
                    discover(excludeKeys = tried)
                }
                return
            }
        }
        rung = null
    }

    /** Test what worked before on this network; bring the tunnel up on the first answer. */
    private suspend fun tryKnownFirst(): Set<String> {
        val historyKeys = historyRanked()
        val known = (historyKeys + store.userServers().filter { !it.excluded } + store.all().filter { it.favorite })
            .distinctBy { if (it is Server) it.key else it }
            .filterIsInstance<Server>()
            .take(24)
        if (known.isEmpty()) return emptySet()
        testAndCollect(known)
        return known.map { it.key }.toSet()
    }

    private fun historyRanked(): List<Server> =
        store.historyLinks(network, 24).mapNotNull { store.all().firstOrNull { s -> s.link == it } }

    /** Test a set of servers, keep the responders, bring the runtime up on the first. */
    private suspend fun testAndCollect(servers: List<Server>) {
        if (servers.isEmpty()) return
        val tested = HashMap<String, Pair<Int, String?>>()
        testServers(servers) { key, delayMs, error -> tested[key] = delayMs to error }
        for (s in servers) {
            val (delayMs, error) = tested[s.key] ?: continue
            store.recordResult(s.key, delayMs, network, error)
            if (delayMs >= 0) {
                val updated = s.copy(delayMs = delayMs)
                if (suits(updated, settings.current.profile) && pool.none { it.server.key == s.key }) {
                    pool += Alive(updated, delayMs)
                    if (!running) {
                        publish(ConnState.Connecting(updated))
                        if (!bringUp()) return
                    }
                }
                if (s.source.startsWith(Server.SOURCE_FEED_PREFIX)) crowdResults[s.key] = delayMs
            }
        }
        _serversChanged.tryEmit(Unit)
    }

    // ------------------------------------------------------------ discovery

    private suspend fun discover(excludeKeys: Set<String>) {
        val s = settings.current
        val history = store.historyLinks(network, 12)
        val userServers = store.userServers()
        val request = JSONObject()
            .put("sources", Sources.toJson(Sources.enabled(s.disabledSources, if (rung?.relaxed == true) 3 else 3)))
            .put("cache_dir", com.simorgh.mac.Paths.feedsCacheDir.absolutePath)
            .put("priority_links", JSONArray(history + frontedVariants(history, userServers)))
            .put("extra_links", JSONArray(userServers.filter { !it.excluded }.map { it.link }))
            .put("exclude_keys", JSONArray((excludeKeys + store.excludedKeys()).toList()))
            .put("want_alive", wantAlive(s.profile))
            .put("max_seconds", rung?.budgetSeconds ?: 75)
            .put("tcp_concurrency", 256).put("tcp_timeout_ms", 1500).put("tcp_stop_after_open", 1500)
            .put("real_concurrency", 64).put("real_timeout_ms", 3000)
            .put("probe_url", PROBE_URL)
            .put("next_tier_if_alive_below", 3)
            .put("fetch", true)
        var progress = DiscoveryProgress(method = rung?.id.orEmpty())
        var enough = false
        var upAt = if (running) System.currentTimeMillis() else 0L
        val stopAt = if (running) BACKGROUND_WANT else stopDiscoveryAt(s.profile)
        var lastReload = 0L
        var pendingReload = false
        val jobId = runCatching { client.startJob("discover", request) }.getOrNull() ?: return
        client.jobEvents(jobId).collect { e ->
            if (enough) return@collect
            if (upAt > 0L && pool.isNotEmpty() && System.currentTimeMillis() - upAt > BACKGROUND_SEARCH_MS) return@collect
            when (e.optString("t")) {
                "stage" -> {
                    progress = progress.copy(stage = stageOf(e.optString("stage")))
                    if (!running) publish(ConnState.Searching(progress))
                }
                "progress" -> {
                    progress = progress.copy(
                        candidates = e.optInt("candidates"), tcpDone = e.optInt("tcp_done"), tcpOpen = e.optInt("tcp_open"),
                        realDone = e.optInt("real_done"), alive = e.optInt("alive"),
                    )
                    if (!running) publish(ConnState.Searching(progress))
                }
                "alive" -> {
                    val info = e.optJSONObject("info") ?: return@collect
                    val delayMs = e.optInt("delay_ms", -1)
                    val server = Server.fromLinkInfo(info, Server.SOURCE_FEED_PREFIX + "discovered").copy(delayMs = delayMs)
                    store.upsert(listOf(server))
                    store.recordResult(server.key, delayMs, network)
                    _serversChanged.tryEmit(Unit)
                    if (server.source.startsWith(Server.SOURCE_FEED_PREFIX)) crowdResults[server.key] = delayMs
                    if (!suits(server, settings.current.profile)) return@collect
                    if (pool.none { it.server.key == server.key || (it.server.host == server.host && it.server.port == server.port) }) {
                        pool += Alive(server, delayMs)
                    }
                    if (!running || settings.current.profile == ConnectionProfile.Gaming) {
                        pool.sortBy { if (it.delayMs < 0) Int.MAX_VALUE else it.delayMs }
                    }
                    if (running && pool.size >= stopAt) enough = true
                    if (!running) {
                        publish(ConnState.Connecting(server))
                        if (!bringUp()) return@collect
                        upAt = System.currentTimeMillis()
                        lastReload = upAt
                    } else {
                        pendingReload = true
                        if (System.currentTimeMillis() - lastReload > 2_000) {
                            reloadPool(); pendingReload = false; lastReload = System.currentTimeMillis()
                        }
                    }
                }
                "done" -> {
                    if (pendingReload && running) reloadPool()
                }
            }
        }
        runCatching { client.cancelJob(jobId) }
        store.prune()
    }

    private fun stageOf(name: String): DiscoveryStage = when (name) {
        "fetch" -> DiscoveryStage.Fetch
        "parse" -> DiscoveryStage.Parse
        "tcp" -> DiscoveryStage.Tcp
        "real" -> DiscoveryStage.Real
        else -> DiscoveryStage.History
    }

    /** CDN-fronted variants of past finds, via the engine's front_links job. */
    private suspend fun frontedVariants(history: List<String>, userServers: List<Server>, max: Int = FRONT_VARIANTS): List<String> {
        if (history.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        runCatching {
            val fronted = HashMap<String, ArrayList<String>>()
            val jobId = client.startJob(
                "front_links",
                JSONObject().put("links", JSONArray(history)).put("seed", System.currentTimeMillis() / 86_400_000L).put("max", max),
            )
            client.jobEvents(jobId).collect { e ->
                if (e.optString("t") == "result") {
                    val arr = e.optJSONArray("links") ?: return@collect
                    for (i in 0 until arr.length()) out.add(arr.getString(i))
                }
            }
        }
        return out.take(max)
    }

    // ---------------------------------------------------------- test links

    /** Real-delay test of the given servers (or all stored ones when empty). */
    fun test(keys: Collection<String> = emptyList()) {
        testJob?.cancel()
        testJob = scope.launch {
            val servers = if (keys.isEmpty()) (store.userServers() + store.all()).distinctBy { it.key } else store.byKeys(keys)
            val limited = servers.take(400)
            if (limited.isEmpty()) return@launch
            var done = 0
            _testProgress.value = 0 to limited.size
            var lastEmit = 0L
            try {
                testServers(limited, timeoutMs = 4_000, concurrency = 16) { key, delayMs, error ->
                    store.recordResult(key, delayMs, network, error)
                    done++
                    _testProgress.value = done to limited.size
                    val now = System.currentTimeMillis()
                    if (now - lastEmit > 500) { _serversChanged.tryEmit(Unit); lastEmit = now }
                }
            } finally {
                _testProgress.value = null
                _serversChanged.tryEmit(Unit)
            }
        }
    }

    internal fun isRunning(): Boolean = running
    internal fun proxyHttpPort(): Int = settings.current.httpPort
    internal fun notifyServersChanged() { _serversChanged.tryEmit(Unit) }
    internal fun setDiagnosis(d: Diagnosis) { _diagnosis.value = d }

    internal suspend fun testServersPublic(
        servers: List<Server>,
        timeoutMs: Int,
        concurrency: Int,
        onResult: suspend (key: String, delayMs: Int, error: String?) -> Unit,
    ) = testServers(servers, timeoutMs, concurrency, onResult)

    private suspend fun testServers(
        servers: List<Server>,
        timeoutMs: Int = 4_000,
        concurrency: Int = 16,
        onResult: suspend (key: String, delayMs: Int, error: String?) -> Unit,
    ) {
        val (own, feed) = servers.partition { it.isUser }
        for ((group, lenient) in listOf(own to true, feed to false)) {
            if (group.isEmpty()) continue
            val request = JSONObject()
                .put("links", JSONArray(group.map { it.link }))
                .put("concurrency", concurrency.coerceIn(1, group.size))
                .put("timeout_ms", if (lenient) maxOf(timeoutMs, OWN_TIMEOUT_MS) else timeoutMs)
                .put("probe_url", PROBE_URL)
            if (lenient) request.put("confirm_tls", false)
            runJobCollect("test_links", request) { e ->
                if (e.optString("t") != "result") return@runJobCollect
                onResult(e.optString("key"), e.optInt("delay_ms", -1), e.optString("error").ifBlank { null })
            }
        }
    }

    private suspend fun runJobCollect(method: String, body: JSONObject, onEvent: suspend (JSONObject) -> Unit) {
        val jobId = runCatching { client.startJob(method, body) }.getOrElse { err ->
            onEvent(JSONObject().put("t", "error").put("message", err.message))
            return
        }
        runCatching { client.jobEvents(jobId).collect(onEvent) }
            .onFailure { onEvent(JSONObject().put("t", "error").put("message", it.message ?: "stream failed")) }
    }

    // ----------------------------------------------------------- bring up

    private suspend fun bringUp(): Boolean = withContext(NonCancellable) {
        val config = buildConfig() ?: return@withContext false
        val s = settings.current
        val err = client.rpc("start", JSONObject().put("config", JSONObject(config)))
            .optString("error").takeIf { it.isNotEmpty() }
        if (err != null) {
            if (err.contains("requires root")) {
                fail(
                    FailReason.VpnPermission,
                    "simorghd must run as root for TUN mode: sudo simorghd --data-dir ${com.simorgh.mac.Paths.dataDir.absolutePath}",
                )
            } else {
                fail(FailReason.CoreError, err)
            }
            return@withContext false
        }
        running = true
        since = System.currentTimeMillis()
        if (s.useSystemProxyDesktop) SystemProxy.apply(s.socksPort, s.httpPort)
        publishConnected()
        true
    }

    private suspend fun reloadPool() {
        if (!running || pool.isEmpty()) return
        val config = buildConfig(failOnError = false) ?: return
        val err = client.rpc("reload", JSONObject().put("config", JSONObject(config))).optString("error").takeIf { it.isNotEmpty() }
        if (err != null) {
            restartCore()
            return
        }
        publishConnected()
    }

    private suspend fun restartCore() {
        if (!running) return
        client.rpc("stop", JSONObject())
        val config = buildConfig(failOnError = false) ?: return
        client.rpc("start", JSONObject().put("config", JSONObject(config)))
        publishConnected()
    }

    private suspend fun buildConfig(failOnError: Boolean = true): String? {
        val s = settings.current
        val links = usablePool().take(linksInConfig(s.profile))
        if (links.isEmpty()) {
            if (failOnError) fail(FailReason.NoWorkingServer, "")
            return null
        }
        val request = JSONObject()
            .put("links", JSONArray(links.map { it.server.link }))
            .put("mode", if (s.mode == ConnectionMode.Vpn) "vpn" else "proxy")
            .put("tun", JSONObject().put("mtu", s.mtu).put("ipv6", s.ipv6))
            .put("socks_port", s.socksPort).put("http_port", s.httpPort)
            .put("lan", JSONObject().put("enabled", s.lanShare).put("listen", "0.0.0.0").put("user", s.lanUser).put("pass", s.lanPass))
            .put("iran_direct", s.iranDirect).put("block_ads", s.blockAds)
            .put("block_quic", s.blockQuic && s.profile != ConnectionProfile.Gaming && rung?.allowQuic != true)
            .put("evasion", if (!s.profile.classic) "off" else when (rung?.evasion ?: s.evasion) {
                EvasionLevel.Off -> "off"; EvasionLevel.Auto -> "auto"; EvasionLevel.Strong -> "strong"
            })
            .put("dns", JSONObject()
                .put("remote", s.remoteDns.name.lowercase())
                .put("custom", s.customDns.trim())
                .put("local", "google")
                .put("anti_sanction", s.antiSanctionDns.name.lowercase())
                .put("custom_anti_sanction", s.customAntiSanction.trim())
                .put("fakedns", s.fakeDns))
            .put("clean_ips", JSONArray(scan.value.results.take(10).map { "${it.ip}:${it.port}" }.distinct().take(20)))
            .put("log_level", if (s.logs) "info" else "warning")
        val answer = try {
            client.rpc("build_config", request)
        } catch (e: Exception) {
            if (failOnError) fail(FailReason.CoreError, e.message.orEmpty())
            return null
        }
        answer.optString("error").takeIf { it.isNotEmpty() }?.let {
            if (failOnError) fail(FailReason.CoreError, it)
            return null
        }
        return answer.optJSONObject("config")?.toString()
            .also { if (failOnError && it == null) fail(FailReason.CoreError, "build_config returned no config") }
    }

    private fun usablePool(): List<Alive> = pool

    private fun publishConnected() {
        val best = usablePool().firstOrNull() ?: return
        publish(ConnState.Connected(best.server, since, best.delayMs, pool.size))
    }

    // ------------------------------------------------------- monitor/stats

    private suspend fun monitor() {
        var lastUp = 0L
        var lastDown = 0L
        val downHistory = ArrayDeque<Long>(60)
        val upHistory = ArrayDeque<Long>(60)
        var tick = 0L
        while (currentCoroutineActive() && running) {
            delay(1000)
            tick++
            val answer = runCatching { client.rpc("stats") }
                .getOrElse {
                    // Daemon unreachable: the connection is gone with it; the client restarts it.
                    if (!client.isAlive) handleDaemonLost()
                    return@getOrElse null
                }
            if (answer != null && !answer.isNull("stats")) {
                val o = answer.optJSONObject("stats")
                if (o != null) {
                    val up = o.optLong("up")
                    val down = o.optLong("down")
                    val sessions = o.optInt("sessions", 0)
                    val upRate = (up - lastUp).coerceAtLeast(0)
                    val downRate = (down - lastDown).coerceAtLeast(0)
                    lastUp = up; lastDown = down
                    if (downHistory.size == 60) downHistory.removeFirst()
                    if (upHistory.size == 60) upHistory.removeFirst()
                    downHistory.addLast(downRate); upHistory.addLast(upRate)
                    o.optJSONObject("race")?.let { r -> _race.value = raceFromJson(r) }
                    _stats.value = TrafficStats(upRate, downRate, up, down, downHistory.toList(), upHistory.toList(), o.optString("cdn"))
                    if (sessions > 0) { /* live traffic: the runtime answers with sessions over time */ }
                }
            }
            if (settings.current.autoSwitch && tick * 1000 % HEALTH_INTERVAL_MS < 1000) {
                healthCheck()
            }
            maybeBackgroundFind()
        }
    }

    private suspend fun healthCheck() {
        mutex.withLock {
            if (!running) return
            val alive = client.rpc("is_running").optBoolean("running", true)
            if (!alive) {
                publish(ConnState.Reconnecting(REASON_CHOSEN_DOWN))
                discover(excludeKeys = pool.map { it.server.key }.toSet())
                if (running) publishConnected()
                return
            }
            // The primary stopped answering: test the pool, drop dead members, switch.
            val members = pool.toList()
            if (members.isEmpty()) return
            val answered = HashMap<String, Int>()
            testServers(members.map { it.server }, timeoutMs = 5_000, concurrency = members.size.coerceAtMost(8)) { key, delayMs, _ ->
                if (delayMs >= 0) answered[key] = delayMs
            }
            if (answered.containsKey(members.first().server.key)) return
            val replacement = members.drop(1).firstOrNull { answered.containsKey(it.server.key) }
            if (replacement != null) {
                pool.remove(replacement)
                pool.add(0, replacement)
                replacement.delayMs = answered[replacement.server.key] ?: replacement.delayMs
                reloadPool()
            } else {
                publish(ConnState.Reconnecting(REASON_CHOSEN_DOWN))
                pool.clear()
                discover(excludeKeys = members.map { it.server.key }.toSet())
                if (!running && pool.isNotEmpty() && !bringUp()) return
            }
        }
    }

    private fun handleDaemonLost() {
        running = false
        monitorJob?.cancel()
        SystemProxy.restore()
        publish(ConnState.Reconnecting("daemon"))
        scope.launch {
            delay(2000)
            if (pool.isNotEmpty()) {
                if (bringUp()) monitorJob = scope.launch { monitor() }
            } else {
                connect(target)
            }
        }
    }

    private fun currentCoroutineActive(): Boolean = scope.coroutineContext[Job]?.isActive == true && running

    private suspend fun maybeBackgroundFind() {
        if (!settings.current.autoRefresh) return
        if (System.currentTimeMillis() - lastBackgroundFindAt < 15 * 60_000) return
        if (pool.size >= STOP_DISCOVERY_AT) return
        lastBackgroundFindAt = System.currentTimeMillis()
        discover(excludeKeys = pool.map { it.server.key }.toSet())
    }

    // ------------------------------------------------------------ teardown

    fun disconnect() {
        connectJob?.cancel()
        monitorJob?.cancel()
        val wasRunning = running
        scope.launch {
            publish(ConnState.Disconnecting)
            if (wasRunning) runCatching { client.rpc("stop", JSONObject()) }
            teardown()
            publish(ConnState.Idle)
        }
    }

    private fun teardown() {
        running = false
        pool.clear()
        SystemProxy.restore()
    }

    private fun fail(reason: FailReason, detail: String) {
        running = false
        monitorJob?.cancel()
        SystemProxy.restore()
        publish(ConnState.Failed(reason, detail))
    }

    private fun publish(s: ConnState) {
        _state.value = s
    }

    private fun raceFromJson(o: JSONObject): RaceState {
        val arr = o.optJSONArray("lanes") ?: JSONArray()
        val outcomeWords = mapOf(
            "waiting" to LaneOutcome.Waiting, "trying" to LaneOutcome.Trying, "won" to LaneOutcome.Won,
            "lost" to LaneOutcome.Lost, "skipped" to LaneOutcome.Skipped,
        )
        val failWords = mapOf(
            "no_answer" to LaneFail.NoAnswer, "refused" to LaneFail.Refused, "no_traffic" to LaneFail.NoTraffic,
            "beaten" to LaneFail.Beaten, "other" to LaneFail.Other,
        )
        return RaceState(
            serial = o.optLong("serial"), done = o.optBoolean("done", true), nowMs = o.optInt("now"),
            lanes = List(arr.length()) { i ->
                arr.getJSONObject(i).let { l ->
                    RaceLane(
                        route = l.optString("r"),
                        outcome = outcomeWords[l.optString("s")] ?: LaneOutcome.Skipped,
                        fail = failWords[l.optString("f")],
                        startMs = l.optInt("a"), endMs = l.optInt("b"),
                    )
                }
            },
        )
    }

    // -------------------------------------------------------------- scan

    fun startScan(count: Int = 2000) {
        if (scanJob?.isActive == true) return
        _scan.value = ScanState(running = true, total = count)
        scanJob = scope.launch {
            val request = JSONObject()
                .put("preset", "cloudflare")
                .put("ports", JSONArray(listOf(443, 2053, 8443)))
                .put("host", "www.speedtest.net")
                .put("count", count)
                .put("concurrency", 128)
                .put("timeout_ms", 1500)
            runJobCollect("scan", request) { e ->
                when (e.optString("t")) {
                    "progress" -> _scan.value = _scan.value.copy(
                        running = true, scanned = e.optInt("scanned"), responsive = e.optInt("responsive"), total = e.optInt("total"),
                    )
                    "ip" -> _scan.value = _scan.value.copy(
                        results = (_scan.value.results + ScanResult(e.optString("ip"), e.optInt("port"), e.optInt("rtt_ms"))).take(400),
                    )
                    "error" -> _scan.value = _scan.value.copy(error = e.optString("message"))
                    "done" -> _scan.value = _scan.value.copy(
                        running = false, scanned = e.optInt("scanned", _scan.value.scanned),
                        responsive = e.optInt("responsive", _scan.value.responsive),
                    )
                }
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        _scan.value = ScanState()
    }

    // -------------------------------------------------------------- warp

    fun warpStart() {
        if (warpJob?.isActive == true) return
        _warp.value = WarpState(WarpPhase.Working)
        warpJob = scope.launch {
            var steps = emptyList<String>()
            var finished = false
            try {
                val request = JSONObject().put("direct", true)
                if (running) request.put("proxy", "127.0.0.1:${settings.current.httpPort}")
                runJobCollect("warp_register", request) { e ->
                    when (e.optString("t")) {
                        "step" -> {
                            steps = (steps + e.optString("line")).takeLast(WARP_STEPS_KEPT)
                            _warp.value = WarpState(WarpPhase.Working, steps)
                        }
                        "done" -> {
                            finished = true
                            if (e.optBoolean("ok")) {
                                val link = e.optString("link")
                                val result = importBlocking(link)
                                _warp.value = if (result.added + result.duplicates > 0) {
                                    WarpState(WarpPhase.Done, steps, e.optString("fingerprint"), e.optInt("exits"), e.optString("route"))
                                } else {
                                    WarpState(WarpPhase.Failed, steps, error = result.error ?: "import")
                                }
                            } else {
                                _warp.value = WarpState(WarpPhase.Failed, steps, error = e.optString("error"))
                            }
                        }
                    }
                }
                if (!finished) _warp.value = WarpState(WarpPhase.Failed, steps, error = "cancelled")
            } catch (e: CancellationException) {
                _warp.value = WarpState()
                throw e
            } catch (e: Throwable) {
                _warp.value = WarpState(WarpPhase.Failed, _warp.value.steps, error = e.message.orEmpty())
            }
        }
    }

    fun warpCancel() {
        warpJob?.cancel()
        warpJob = null
        _warp.value = WarpState()
    }

    // ---------------------------------------------------------- servers

    fun refresh() {
        if (refreshJob?.isActive == true || (connectJob?.isActive == true && !running)) return
        refreshJob = scope.launch {
            _refreshing.value = true
            try {
                updateSubscriptions()
                runCatching { Rankings.refreshIfNeeded() }
                val s = settings.current
                val request = JSONObject()
                    .put("sources", Sources.toJson(Sources.enabled(s.disabledSources)))
                    .put("cache_dir", com.simorgh.mac.Paths.feedsCacheDir.absolutePath)
                    .put("priority_links", JSONArray())
                    .put("extra_links", JSONArray())
                    .put("exclude_keys", JSONArray())
                    .put("want_alive", 20).put("max_seconds", 90)
                    .put("tcp_concurrency", 256).put("tcp_timeout_ms", 1500).put("tcp_stop_after_open", 1500)
                    .put("real_concurrency", 64).put("real_timeout_ms", 3000)
                    .put("probe_url", PROBE_URL).put("next_tier_if_alive_below", 10).put("fetch", true)
                runJobCollect("discover", request) { e ->
                    if (e.optString("t") != "alive") return@runJobCollect
                    val info = e.optJSONObject("info") ?: return@runJobCollect
                    val delayMs = e.optInt("delay_ms", -1)
                    val server = Server.fromLinkInfo(info, Server.SOURCE_FEED_PREFIX + "discovered")
                    store.upsert(listOf(server))
                    store.recordResult(server.key, delayMs, network)
                    _serversChanged.tryEmit(Unit)
                }
                store.prune()
            } finally {
                _refreshing.value = false
                _serversChanged.tryEmit(Unit)
            }
        }
    }

    // ----------------------------------------------------------- import

    suspend fun import(text: String): ImportResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ImportResult(0, 0, 0, "empty")
        if ((trimmed.startsWith("https://") || trimmed.startsWith("http://")) && trimmed.lines().size == 1) {
            return addSubscription("", trimmed)
        }
        return importText(trimmed, Server.SOURCE_USER)
    }

    /** Blocking variant used by the warp flow (it already runs in a job). */
    private fun importBlocking(text: String): ImportResult =
        kotlinx.coroutines.runBlocking { import(text) }

    private suspend fun importText(text: String, source: String): ImportResult {
        val parsed = client.rpc("parse_links", JSONObject().put("text", text))
        val items = parsed.optJSONArray("items") ?: JSONArray()
        val servers = List(items.length()) { Server.fromLinkInfo(items.getJSONObject(it), source) }
        val existing = store.byKeys(servers.map { it.key }).map { it.key }.toSet()
        store.upsert(servers)
        _serversChanged.tryEmit(Unit)
        return ImportResult(
            added = servers.count { it.key !in existing },
            duplicates = servers.count { it.key in existing },
            rejected = parsed.optInt("rejected"),
        )
    }

    fun addSubscription(name: String, url: String): ImportResult {
        val address = url.trim().substringBefore('#')
        val suggested = url.trim().substringAfter('#', "").let { fragment ->
            runCatching { java.net.URLDecoder.decode(fragment.replace("+", "%2B"), "UTF-8") }.getOrDefault(fragment).trim()
        }
        val sub = Subscription(
            subscriptionId(address),
            name.ifBlank { suggested }.ifBlank { hostOf(address) },
            address, true, 0, 0,
        )
        store.upsertSubscription(sub)
        return fetchSubscription(sub)
    }

    fun removeSubscription(id: String) {
        store.deleteSubscription(id)
        _serversChanged.tryEmit(Unit)
    }

    private fun fetchSubscription(sub: Subscription): ImportResult = runCatching {
        val finalUrl = kotlinx.coroutines.runBlocking {
            var url = sub.url
            val jobId = runCatching { client.startJob("subscription_fetch", JSONObject().put("url", sub.url)) }.getOrNull()
            if (jobId != null) {
                client.jobEvents(jobId).collect { e ->
                    if (e.optString("t") == "result") url = e.optString("url", url)
                }
            }
            url
        }
        val body = kotlinx.coroutines.runBlocking { withContext(Dispatchers.IO) { httpGet(finalUrl) } }
        val result = kotlinx.coroutines.runBlocking { importText(body, Server.SOURCE_SUB_PREFIX + sub.id) }
        store.touchSubscription(sub, result.added + result.duplicates)
        _serversChanged.tryEmit(Unit)
        result
    }.getOrElse { ImportResult(0, 0, 0, it.message ?: "fetch failed") }

    private suspend fun updateSubscriptions() {
        for (sub in store.subscriptions().filter { it.enabled }) {
            runCatching { fetchSubscription(sub) }
        }
    }

    private fun subscriptionId(address: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(address.toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }

    private fun hostOf(address: String): String =
        runCatching { java.net.URI(address).host ?: address }.getOrDefault(address)

    private fun httpGet(url: String): String {
        val conn = java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "v2rayN/9")
        try {
            return conn.inputStream.use { String(it.readBytes()) }
        } finally {
            conn.disconnect()
        }
    }

    // --------------------------------------------------------- settings

    /** Apply a settings change to the live runtime where possible. */
    fun pushSettings() {
        scope.launch {
            runCatching { client.rpc("set_log_level", JSONObject().put("level", if (settings.current.logs) "info" else "warn")) }
            if (running) {
                if (settings.current.useSystemProxyDesktop) SystemProxy.apply(settings.current.socksPort, settings.current.httpPort)
                reloadPool()
            }
            applyLoginItem(settings.current.autoConnect.name)
        }
    }

    private fun applyLoginItem(auto: String) {
        // Boot auto-connect on macOS = add the app as a login item via System Events.
        val want = auto == "OnBoot"
        val here = System.getProperty("user.dir")
        val current = exec("osascript", "-e", "tell application \"System Events\" to get the name of every login item").contains("Simorgh")
        if (want && !current) {
            val jar = System.getProperty("java.class.path")?.substringBefore(':')
            val appPath = System.getProperty("sun.boot.library.path")?.let { File(it).parentFile?.parentFile?.path }
            val target = appPath?.let { File(File(it).parentFile ?: File(here), "Simorgh.app").path } ?: jar ?: here
            exec("osascript", "-e", "tell application \"System Events\" to make login item at end with path \"$target\"")
        } else if (!want && current) {
            exec("osascript", "-e", "tell application \"System Events\" to delete login item \"Simorgh\"")
        }
    }

    private fun exec(vararg cmd: String): String = com.simorgh.mac.platform.exec(*cmd)

    // ------------------------------------------------------ crowd report

    private suspend fun reportCrowd() {
        val feedResults = crowdResults.mapNotNull { (key, delayMs) -> store.byKey(key)?.let { it to delayMs } }
        if (feedResults.isNotEmpty()) {
            Rankings.reportResults(feedResults, scan.value.results.map { it.ip to it.rttMs })
        }
    }

    // ---------------------------------------------------------- diagnose

    fun diagnose() {
        if (diagJob?.isActive == true) return
        diagJob = scope.launch { com.simorgh.mac.platform.Diagnostics.run(this@Runner) }
    }
}
