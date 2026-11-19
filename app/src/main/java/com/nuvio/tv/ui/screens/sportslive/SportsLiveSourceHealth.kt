package com.nuvio.tv.ui.screens.sportslive

/**
 * One source's run of refreshes (#170): its display name (never a host or URL), the refreshes in a row it failed,
 * when it last answered and when the current run of failures began.
 */
data class SportsLiveSourceHealth(
    val name: String,
    val failuresInRow: Int = 0,
    val lastOkMs: Long? = null,
    val failingSinceMs: Long? = null
) {
    /** Since when it has not answered: its last answer, else its first failure; null while it answers. */
    val silentSinceMs: Long? get() = if (failuresInRow == 0) null else lastOkMs ?: failingSinceMs
}

/** The sources' health (#170), by source id, and whether every source asked by the last refresh failed. */
data class SportsLiveSources(
    val health: Map<String, SportsLiveSourceHealth> = emptyMap(),
    val allFailed: Boolean = false
) {
    /**
     * After a refresh at [nowMs] that asked [fetched] (id → name) and heard nothing back from [failed] (id → name).
     * A source not asked keeps its run (a tab's refresh asks only its own catalogs); one no longer [claimed] is
     * forgotten (an empty [claimed] keeps all).
     */
    fun after(fetched: Map<String, String>, failed: Map<String, String>, claimed: Set<String>, nowMs: Long): SportsLiveSources {
        val asked = fetched + failed
        val next = health.filterKeys { claimed.isEmpty() || it in claimed || it in asked }.toMutableMap()
        asked.forEach { (id, name) ->
            val before = next[id] ?: SportsLiveSourceHealth(name)
            next[id] = if (id in failed) {
                before.copy(name = name, failuresInRow = before.failuresInRow + 1, failingSinceMs = before.failingSinceMs ?: nowMs)
            } else {
                SportsLiveSourceHealth(name, lastOkMs = nowMs)
            }
        }
        val allFailed = if (asked.isEmpty()) this.allFailed else asked.keys.all { it in failed }
        return SportsLiveSources(next, allFailed)
    }

    /**
     * The sources worth a word (#170): failed two refreshes or more in a row, or any failing one when every source
     * failed. A one-off miss is never named. Longest silent first.
     */
    fun flagged(): List<SportsLiveSourceHealth> =
        health.values
            .filter { it.failuresInRow >= FLAG_AFTER_FAILURES || (allFailed && it.failuresInRow >= 1) }
            .sortedBy { it.silentSinceMs ?: Long.MAX_VALUE }

    /** The dot on Refresh (#170): none, a calm grey, or amber when a source has been silent over 10 min or all failed. */
    fun badge(nowMs: Long): SportsLiveSourceBadge {
        val flagged = flagged()
        return when {
            flagged.isEmpty() -> SportsLiveSourceBadge.NONE
            allFailed || flagged.any { nowMs - (it.silentSinceMs ?: nowMs) > AMBER_AFTER_MS } -> SportsLiveSourceBadge.AMBER
            else -> SportsLiveSourceBadge.GREY
        }
    }

    companion object {
        const val FLAG_AFTER_FAILURES = 2
        const val AMBER_AFTER_MS = 10 * 60_000L
    }
}

enum class SportsLiveSourceBadge { NONE, GREY, AMBER }
