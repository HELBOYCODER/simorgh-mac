package com.simorgh.mac.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Community config feeds, tiered as in the Android app (see FEEDS-NOTES.md):
 * tier 0 is this project's own tested list — served from all three mirrors,
 * mirrors first — tier 1 the small trusted subscriptions, tier 2 the crowd
 * lists and community collectors, tier 3 the big public dumps.
 */
data class FeedSource(
    val id: String,
    val name: String,
    val repo: String,
    val url: String,
    val tier: Int,
    /** Detached-signature URL (`<url>.sig`); set for the project's own signed lists. */
    val sigUrl: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("url", url).put("tier", tier)
        .apply { if (sigUrl != null) put("sig_url", sigUrl) }
}

object Sources {
    private const val RAW = "https://raw.githubusercontent.com"
    private const val JS = "https://cdn.jsdelivr.net/gh"
    private const val FASTLY = "https://fastly.jsdelivr.net/gh"

    private const val CROWD_DATA = "HELBOYCODER/simorgh-servers@crowd-data"
    private const val CROWD_MAIN = "HELBOYCODER/simorgh-servers@main"

    val builtIn: List<FeedSource> = listOf(
        // The project's own tested list, published by the harvest workflow at
        // most ~2 h stale; mirrors first because jsDelivr is often reachable
        // when raw.githubusercontent.com is not. Signed at every location.
        FeedSource("verified-js", "Simorgh verified", "HELBOYCODER/simorgh-servers", "$JS/$CROWD_DATA/verified.txt", 0, "$JS/$CROWD_DATA/verified.txt.sig"),
        FeedSource("verified-fastly", "Simorgh verified (Fastly)", "HELBOYCODER/simorgh-servers", "$FASTLY/$CROWD_DATA/verified.txt", 0, "$FASTLY/$CROWD_DATA/verified.txt.sig"),
        FeedSource("verified", "Simorgh verified (GitHub)", "HELBOYCODER/simorgh-servers", "$RAW/HELBOYCODER/simorgh-servers/crowd-data/verified.txt", 0, "$RAW/HELBOYCODER/simorgh-servers/crowd-data/verified.txt.sig"),
        // Community lists (simorgh-crowd), published by the crowd workflows.
        FeedSource("crowd-community", "Simorgh crowd: community", "HELBOYCODER/simorgh-crowd", "$RAW/HELBOYCODER/simorgh-crowd/main/lists/community.txt", 2),
        FeedSource("crowd-curated", "Simorgh crowd: curated", "HELBOYCODER/simorgh-crowd", "$RAW/HELBOYCODER/simorgh-crowd/main/lists/curated.txt", 2),
        FeedSource("crowd-fresh", "Simorgh crowd: fresh", "HELBOYCODER/simorgh-crowd", "$RAW/HELBOYCODER/simorgh-crowd/main/lists/fresh-submissions.txt", 2),
        // Third-party community feeds, kept from the Android app's ladder.
        FeedSource("limilco", "liMilCo", "liMilCo/v2r", "$RAW/liMilCo/v2r/main/new_configs.txt", 1),
        FeedSource("sinavm", "SVM", "sinavm/SVM", "$RAW/sinavm/SVM/main/lite/subscriptions/xray/base64/mix", 1),
        FeedSource("anonymou3", "Multi Proxy (tested)", "4n0nymou3/multi-proxy-config-fetcher", "$RAW/4n0nymou3/multi-proxy-config-fetcher/main/configs/proxy_configs_tested.txt", 1),
        FeedSource("solvpn", "SolVPN (tested)", "SoliSpirit/SolVPN", "$RAW/SoliSpirit/SolVPN/main/all_configs.txt", 2),
        FeedSource("miladtahanian", "Config-Collector (Iran)", "miladtahanian/Config-Collector", "$RAW/miladtahanian/Config-Collector/main/mixed_iran.txt", 2),
        FeedSource("radikal", "0xRadikal", "0xRadikal/Free-v2ray-Configs", "$RAW/0xRadikal/Free-v2ray-Configs/main/all/configs.txt", 2),
        FeedSource("epodonios", "Epodonios", "Epodonios/v2ray-configs", "$RAW/Epodonios/v2ray-configs/main/All_Configs_Sub.txt", 2),
        FeedSource("freedom", "Freedom-V2Ray", "MahanKenway/Freedom-V2Ray", "$RAW/MahanKenway/Freedom-V2Ray/main/configs/mix.txt", 3),
        FeedSource("ebrasha", "EbraSha (VLESS)", "ebrasha/free-v2ray-public-list", "$RAW/ebrasha/free-v2ray-public-list/main/vless_configs.txt", 3),
        FeedSource("delta", "Delta-Kronecker", "Delta-Kronecker/V2ray-Config", "$RAW/Delta-Kronecker/V2ray-Config/main/config/all_configs.txt", 3),
        FeedSource("mheidari", "mheidari98", "mheidari98/.proxy", "$RAW/mheidari98/.proxy/main/all", 3),
        FeedSource("f0rc3run", "F0rc3Run", "F0rc3Run/F0rc3Run", "$RAW/F0rc3Run/F0rc3Run/main/Best-Results/sub.txt", 3),
    )

    /** The verified-source.json the crowd workflows describe the primary list as. */
    const val VERIFIED_SOURCE_JSON = "$RAW/$CROWD_MAIN/deploy/crowd/verified-source.json"

    /** rankings.json mirrors, tried in order; signatures at `<url>.sig`. */
    val RANKINGS_URLS: List<String> = listOf(
        "$RAW/HELBOYCODER/simorgh-servers/crowd-data/rankings.json",
        "$JS/$CROWD_DATA/rankings.json",
        "$FASTLY/$CROWD_DATA/rankings.json",
    )

    fun enabled(disabled: Set<String>, maxTier: Int = 3): List<FeedSource> =
        builtIn.filter { it.id !in disabled && it.tier <= maxTier }

    fun toJson(sources: List<FeedSource>): JSONArray = JSONArray().also { a -> sources.forEach { a.put(it.toJson()) } }
}
