package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.SportsLiveItem

/**
 * The games tried and failed in this zap session (#138 follow-up): a game whose links all failed, or whose lookup
 * found nothing, is never offered again by "Next live game" (the player's error screen, a dual pane) or the CH±
 * auto-skip, so two dead games can no longer send the viewer back and forth. Kept by game id (content id), each
 * for [windowMs]; a new session, Try again, or one of them playing after all clears them. Thread-safe.
 */
class SportsLiveTriedGames(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val windowMs: Long = WINDOW_MS
) {
    companion object {
        /** A failed game is left out this long, then may be tried again. */
        const val WINDOW_MS = 10 * 60_000L

        /** The process-wide one the player, dual screen and the zaps share. */
        val shared = SportsLiveTriedGames()
    }

    private val failedAt = HashMap<String, Long>()

    /** [item] (each of its sources) was tried and failed. */
    @Synchronized
    fun noteFailed(item: SportsLiveItem) {
        val now = nowMs()
        (listOf(item) + item.alternates).forEach { failedAt[it.id] = now }
    }

    /** The ids of the games tried and failed within the window. */
    @Synchronized
    fun ids(): Set<String> {
        prune()
        return failedAt.keys.toSet()
    }

    /** True when [item] (any of its sources) was tried and failed within the window. */
    fun contains(item: SportsLiveItem, ids: Set<String> = ids()): Boolean =
        item.id in ids || item.alternates.any { it.id in ids }

    /** Game [gameId] plays now: when it was one of them, the set is cleared (the trouble may have passed). */
    @Synchronized
    fun onPlaying(gameId: String) {
        prune()
        if (gameId in failedAt) failedAt.clear()
    }

    @Synchronized
    fun clear() = failedAt.clear()

    private fun prune() {
        val now = nowMs()
        failedAt.values.removeAll { now - it >= windowMs }
    }
}
