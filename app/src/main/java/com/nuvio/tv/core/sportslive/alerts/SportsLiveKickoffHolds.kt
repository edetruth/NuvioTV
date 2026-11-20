package com.nuvio.tv.core.sportslive.alerts

import com.nuvio.tv.core.sportslive.SportsLiveLiveCheck
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.Stream

/**
 * Kickoff alerts wait for a live stream (#162). At kickoff the game's links are probed
 * ([SportsLiveLiveCheck], one link per addon) before "Watch now?" shows:
 * - a live link: the alert fires at once with that link ([Event.Fire]), which Watch plays first;
 * - no link can be checked (none is HLS, e.g. only torrents): the alert fires as before, unchecked;
 * - otherwise it is held and probed again every [RECHECK_MS], for up to [GIVE_UP_MS]; then a quiet note
 *   ([Event.GiveUp]) "‹game› started, but no stream is live yet", whose Remind me in 10 min asks for one
 *   more probe ([remindLater]).
 * A probe that failed outright is retried on the next tick. One game is held once. Main-thread or
 * otherwise serialised use only; [tick] may suspend for the probes.
 */
class SportsLiveKickoffHolds(
    private val probe: suspend (SportsLiveItem) -> SportsLiveLiveCheck.Outcome?,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    sealed interface Event {
        val alert: SportsLiveAlert.Kickoff

        /** Show [alert]; [stream] is the live link Watch plays first (null: unchecked). */
        data class Fire(override val alert: SportsLiveAlert.Kickoff, val stream: Stream?) : Event

        /** Nothing came up within [GIVE_UP_MS]: the quiet note. */
        data class GiveUp(override val alert: SportsLiveAlert.Kickoff) : Event
    }

    private class Held(
        val alert: SportsLiveAlert.Kickoff,
        val sinceMs: Long,
        var nextAtMs: Long,
        /** A last probe asked by Remind me in 10 min: nothing more after it. */
        val last: Boolean = false
    )

    private val held = LinkedHashMap<String, Held>()

    val isEmpty: Boolean get() = held.isEmpty()

    /** Whether [item]'s game is being held. */
    fun isHeld(item: SportsLiveItem): Boolean = SportsLiveAlertEngine.gameKey(item) in held

    /** Holds [alert] until its game has a live link; its first probe runs on the next [tick]. */
    fun hold(alert: SportsLiveAlert.Kickoff) {
        val key = SportsLiveAlertEngine.gameKey(alert.item)
        if (key in held) return
        val now = nowMs()
        held[key] = Held(alert, sinceMs = now, nextAtMs = now)
    }

    /** Remind me in 10 min on the note: one more probe then, firing the alert when a link is live. */
    fun remindLater(alert: SportsLiveAlert.Kickoff) {
        val now = nowMs()
        held[SportsLiveAlertEngine.gameKey(alert.item)] = Held(alert.copy(again = true), sinceMs = now, nextAtMs = now + REMIND_LATER_MS, last = true)
    }

    /** [item]'s game no longer needs a probe (it plays, or the viewer opened it). */
    fun drop(item: SportsLiveItem) {
        held.remove(SportsLiveAlertEngine.gameKey(item))
    }

    /**
     * Probes the held games that are due and returns what to show. A game [onScreen] (playing in any player) is
     * dropped unprobed: its reminder is satisfied.
     */
    suspend fun tick(onScreen: (SportsLiveAlert.Kickoff) -> Boolean = { false }): List<Event> {
        val events = ArrayList<Event>()
        held.values.removeAll { onScreen(it.alert) }
        val due = held.filterValues { nowMs() >= it.nextAtMs }
        for ((key, entry) in due) {
            val outcome = probe(entry.alert.item)
            if (held[key] !== entry) continue
            val now = nowMs()
            when {
                outcome?.live != null -> {
                    held.remove(key)
                    events += Event.Fire(entry.alert, outcome.live)
                }
                outcome != null && !outcome.checkable && !entry.last -> {
                    held.remove(key)
                    events += Event.Fire(entry.alert, null)
                }
                entry.last -> held.remove(key)
                now - entry.sinceMs >= GIVE_UP_MS -> {
                    held.remove(key)
                    events += Event.GiveUp(entry.alert)
                }
                else -> entry.nextAtMs = now + RECHECK_MS
            }
        }
        return events
    }

    companion object {
        const val RECHECK_MS = 60_000L
        const val GIVE_UP_MS = 20 * 60_000L
        const val REMIND_LATER_MS = 10 * 60_000L
    }
}
