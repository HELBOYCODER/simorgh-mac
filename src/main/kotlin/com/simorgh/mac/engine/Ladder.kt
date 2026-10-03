package com.simorgh.mac.engine

import com.simorgh.mac.model.EvasionLevel

/**
 * The ways the recommended mode tries to connect, fastest first — ported
 * verbatim from the Android app's `service/Ladder.kt`.
 */
object Ladder {
    /** What one rung changes about the way servers are found and used. */
    data class Rung(
        val id: String,
        /** Only test what is already known; no lists. */
        val known: Boolean = false,
        /** Test the user's own WARP account(s); no lists. */
        val warp: Boolean = false,
        /** Split the ClientHello whatever the setting says, or null for the setting. */
        val evasion: EvasionLevel? = null,
        /** Accept servers of any protocol and security, not only encrypted ones. */
        val relaxed: Boolean = false,
        /** Let QUIC through instead of blocking it. */
        val allowQuic: Boolean = false,
        /** How many fronted variants of past finds to test. */
        val fronts: Int = 18,
        /** Seconds the search may take. */
        val budgetSeconds: Int = 75,
    )

    const val KNOWN = 0
    const val SEARCH = 1
    const val WARP = 2
    const val DISGUISE = 3
    const val OPEN = 4

    val rungs: List<Rung> = listOf(
        Rung("known", known = true),
        Rung("search", budgetSeconds = 45),
        Rung("warp", warp = true),
        Rung("disguise", evasion = EvasionLevel.Strong, fronts = 36, budgetSeconds = 40),
        Rung("open", evasion = EvasionLevel.Strong, relaxed = true, allowQuic = true, fronts = 36, budgetSeconds = 40),
    )

    /** The order to try the rungs in: quick known-servers first, then the rung that
     *  last worked here, then the rest from the top. */
    fun order(remembered: Int?, hasWarp: Boolean): List<Int> {
        val all = rungs.indices.filter { hasWarp || it != WARP }
        val first = listOf(KNOWN)
        val next = listOfNotNull(remembered?.takeIf { it != KNOWN && it in all })
        return (first + next + all).distinct()
    }

    /** The rung with this id, or null. */
    fun indexOf(id: String?): Int? = rungs.indexOfFirst { it.id == id }.takeIf { it >= 0 }
}
