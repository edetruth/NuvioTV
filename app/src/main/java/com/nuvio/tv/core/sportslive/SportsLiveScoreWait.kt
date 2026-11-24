package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.domain.model.SportsLiveItem

/**
 * "Not LIVE until ESPN has answered" (live.28, user decision): a Live now game in a league an ESPN feed covers reads its
 * start time, not LIVE, until its feed has answered for it: a snapshot (whatever it says) or the feeds confirming they do
 * not have it. A game no feed covers, any game under Scores Off, and a channel show as before ([answered] is the estimate
 * gate, [SportsLiveEndedGames.estimateGate]: true for exactly those, and once the feeds confirmed no game). If no answer
 * comes within [maxWaitMs] of the wait starting (ESPN down, slow, failing), the game shows as before (LIVE): it is never
 * hidden nor held back. A game answered once that loses its snapshot (a feed paused while the player showed, then stale)
 * waits again, from then. Rows never move for it: the caller only words the status. Thread-safe.
 */
class SportsLiveScoreWait(private val maxWaitMs: Long = MAX_WAIT_MS) {

    private val since = HashMap<String, Long>()

    /**
     * The card keys of [items] waiting for their score at [nowMs]: not a channel, no snapshot in [scores], not [answered]
     * (see the class doc), and waiting for less than [maxWaitMs].
     */
    @Synchronized
    fun awaiting(
        items: List<SportsLiveItem>,
        scores: Map<String, ScoreSnapshot>,
        answered: (SportsLiveItem) -> Boolean,
        nowMs: Long
    ): Set<String> {
        val keys = items.mapTo(HashSet()) { it.streamKey }
        since.keys.retainAll(keys)
        val waiting = HashSet<String>()
        for (item in items) {
            val key = item.streamKey
            if (item.isChannel || scores[key] != null || answered(item)) {
                since.remove(key)
                continue
            }
            val start = since.getOrPut(key) { nowMs }
            if (nowMs - start < maxWaitMs) waiting += key
        }
        return waiting
    }

    companion object {
        /** Past this, a game whose feed has not answered shows as before (LIVE). */
        const val MAX_WAIT_MS = 10_000L
    }
}
