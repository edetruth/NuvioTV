package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.scores.ScoreChips
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.SportsResults
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import java.time.Instant

/** How a Live now game was found to be over (#60). */
enum class EndedSignal {
    /** ESPN says final, on the card's strict match, in the score-delayed view. */
    FINAL,

    /** No ESPN match: its start plus its sport's length ([SportsLiveGameLength]) has passed. */
    ESTIMATE
}

/** A Live now game that is over: [endedAtMs] is when, [event] the ESPN final (null for an estimate). */
data class EndedGame(val signal: EndedSignal, val endedAtMs: Long, val event: ScoreSnapshot? = null) {
    /** When the card leaves the main list for Just ended: after the postgame grace for an ESPN final. */
    val movesAtMs: Long get() = endedAtMs + if (signal == EndedSignal.FINAL) SportsLiveEndedGames.GRACE_MS else 0L

    /** When the card leaves Live now altogether. */
    val dropsAtMs: Long get() = endedAtMs + SportsLiveEndedGames.LINGER_MS
}

/** Live now cut in three: still [live], [justEnded] (the dimmed strip, in list order) and the [ended] verdicts of those. */
data class LiveNowSplit(
    val live: List<SportsLiveItem>,
    val justEnded: List<SportsLiveItem>,
    /** By card key ([SportsLiveItem.streamKey]), for the [justEnded] cards only. */
    val ended: Map<String, EndedGame>
)

/**
 * Detects ended Live now games (#60) from data already at hand, with no request of its own.
 * Precision first: a wrong "ended" is worse than none, and a game ESPN shows live never moves.
 *
 * 1. ESPN final on a strict match ([ScoreChips.matchedEvent]): from the score-delayed snapshot only,
 *    with both scores out (a final the delay still withholds does not count). It moves after
 *    [GRACE_MS] (postgame). Postponed, cancelled, suspended games never count. A strict match decides
 *    alone: live, scheduled or anything else short of final means not ended, never estimated.
 * 2. No snapshot for the card: an estimate from its timed start (a merged card: the latest of its
 *    sources) plus [SportsLiveGameLength]. Never for channels, cards without a time of day, or sports
 *    with no length.
 *
 * Remembers when each card's final was first seen, so the grace and the drop count from then even
 * after the card stops being tracked for scores (Home and the section share the [shared] instance).
 * A final first seen without the game ever seen in play is dated no later than its estimated end, so
 * a game long over at app start does not get a fresh half hour. Thread-safe.
 */
class SportsLiveEndedGames {

    private class SeenFinal(val atMs: Long, val event: ScoreSnapshot)

    private val finals = HashMap<String, SeenFinal>()
    /** Cards whose strict match was seen scheduled or in play: their final is dated when first seen. */
    private val seenUnfinished = HashSet<String>()

    /** [item]'s verdict at [nowMs] given its delayed [snapshot] (null: none); null while not ended. */
    @Synchronized
    fun verdict(item: SportsLiveItem, snapshot: ScoreSnapshot?, nowMs: Long): EndedGame? {
        // A channel (24/7) is never moved, whatever its bracketed game did.
        if (item.isChannel) return null
        val key = item.streamKey
        if (snapshot != null) {
            // Sides not on the event one way round: no ESPN verdict, and ESPN's presence blocks the estimate.
            val event = ScoreChips.matchedEvent(item, snapshot) ?: return finals[key]?.toEnded()
            return when {
                event.state == ScoreState.IN || event.state == ScoreState.PRE -> {
                    finals.remove(key)
                    if (seenUnfinished.size >= MAX_REMEMBERED) seenUnfinished.clear()
                    seenUnfinished += key
                    null
                }
                event.state == ScoreState.OFF -> {
                    finals.remove(key)
                    null
                }
                isFinalWithScore(event) -> {
                    val at = finals[key]?.atMs ?: firstFinalAt(item, event, key, nowMs)
                    if (finals.size >= MAX_REMEMBERED) prune(nowMs)
                    finals[key] = SeenFinal(at, event)
                    EndedGame(EndedSignal.FINAL, at, event)
                }
                // Withheld by the delay, or a state ESPN did not name: as it was.
                else -> finals[key]?.toEnded()
            }
        }
        finals[key]?.let { return it.toEnded() }
        return estimate(item, nowMs)
    }

    /**
     * Splits Live now [items] (in order) by their verdicts at [nowMs]; [scores] are the delayed
     * snapshots by card key. A card whose drop time has passed is in neither list, unless [keepLinked]
     * says its links were found lately and it was only estimated: it then stays in Just ended.
     */
    fun split(
        items: List<SportsLiveItem>,
        scores: Map<String, ScoreSnapshot>,
        nowMs: Long,
        keepLinked: (SportsLiveItem) -> Boolean = { false }
    ): LiveNowSplit {
        val live = ArrayList<SportsLiveItem>(items.size)
        val justEnded = ArrayList<SportsLiveItem>()
        val ended = HashMap<String, EndedGame>()
        for (item in items) {
            val verdict = verdict(item, scores[item.streamKey], nowMs)
            when {
                verdict == null || nowMs < verdict.movesAtMs -> live += item
                nowMs < verdict.dropsAtMs || (verdict.signal == EndedSignal.ESTIMATE && keepLinked(item)) -> {
                    justEnded += item
                    ended[item.streamKey] = verdict
                }
                else -> Unit
            }
        }
        return LiveNowSplit(live, justEnded, ended)
    }

    /** The card keys of [items] that have moved out of the live list at [nowMs] (Home drops them). */
    fun endedKeys(items: List<SportsLiveItem>, scores: Map<String, ScoreSnapshot>, nowMs: Long): Set<String> =
        items.mapNotNullTo(HashSet()) { item ->
            item.streamKey.takeIf { verdict(item, scores[item.streamKey], nowMs)?.let { nowMs >= it.movesAtMs } == true }
        }

    private fun SeenFinal.toEnded() = EndedGame(EndedSignal.FINAL, atMs, event)

    private fun firstFinalAt(item: SportsLiveItem, event: ScoreSnapshot, key: String, nowMs: Long): Long {
        if (key in seenUnfinished) return nowMs
        val start = event.startMs?.takeIf { event.timeValid } ?: timedStart(item) ?: return nowMs
        val length = SportsLiveGameLength.of(item) ?: return nowMs
        return minOf(nowMs, start + length)
    }

    private fun estimate(item: SportsLiveItem, nowMs: Long): EndedGame? {
        if (item.kind != SportsLiveKind.EVENTS) return null
        val start = timedStart(item) ?: return null
        val length = SportsLiveGameLength.of(item) ?: return null
        val end = start + length
        return if (nowMs >= end) EndedGame(EndedSignal.ESTIMATE, end) else null
    }

    private fun prune(nowMs: Long) {
        finals.values.removeAll { nowMs - it.atMs > FORGET_AFTER_MS }
        if (finals.size >= MAX_REMEMBERED) finals.clear()
    }

    companion object {
        /** After ESPN's final, the card stays in the main list this long (postgame). */
        const val GRACE_MS = 10 * 60_000L

        /** After it ended, the card leaves Live now. */
        const val LINGER_MS = 30 * 60_000L

        private const val FORGET_AFTER_MS = 6 * 60 * 60_000L
        private const val MAX_REMEMBERED = 2_000

        /** What every surface uses, so Home, the section and the player panel agree. */
        val shared = SportsLiveEndedGames()

        fun isFinalWithScore(event: ScoreSnapshot): Boolean =
            event.state == ScoreState.POST && event.isFinal && event.homeScore != null && event.awayScore != null

        /**
         * The latest timed start among [item]'s sources (not channels), or null when none has a time of
         * day: a game without one gets no estimate.
         */
        fun timedStart(item: SportsLiveItem): Long? =
            (listOf(item) + item.alternates)
                .filter { !it.isChannel && it.startHasTimeOfDay }
                .mapNotNull { it.startMs }
                .maxOrNull()

        /** The strict replay match of an ESPN-ended [event] among [replays] (as Results tags them), or null. */
        fun replayFor(event: ScoreSnapshot, replays: List<SportsLiveItem>, nowMs: Long): SportsLiveItem? {
            val start = event.startMs ?: return null
            if (replays.isEmpty()) return null
            val day = Instant.ofEpochMilli(start).atZone(SportsResults.EASTERN).toLocalDate()
            return SportsResults.replaysByEvent(listOf(event), day, replays, nowMs)[event.eventId]
        }
    }
}
