package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
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

/**
 * A Live now game that is over: [endedAtMs] is when, [event] the ESPN final (null for an estimate).
 * [graced]: an ESPN final keeps the postgame grace; one whose end could not be dated (no start, or a
 * sport with no length) has none and goes straight to Just ended.
 */
data class EndedGame(
    val signal: EndedSignal,
    val endedAtMs: Long,
    val event: ScoreSnapshot? = null,
    val graced: Boolean = true
) {
    /** When the card leaves the main list for Just ended: after the postgame grace for an ESPN final. */
    val movesAtMs: Long
        get() = endedAtMs + if (signal == EndedSignal.FINAL && graced) SportsLiveEndedGames.GRACE_MS else 0L

    /**
     * When the card leaves Live now altogether: half an hour after an ESPN final. An estimate never hides
     * a game: it stays in the strip (playable) until its addon drops it, or [SportsLiveEndedGames.ESTIMATE_LINGER_MS].
     */
    val dropsAtMs: Long
        get() = endedAtMs + if (signal == EndedSignal.FINAL) SportsLiveEndedGames.LINGER_MS else SportsLiveEndedGames.ESTIMATE_LINGER_MS
}

/**
 * What the ended verdicts need from the score feed: the delayed [scores] by card key, and which cards
 * may be estimated ([SportsLiveEndedGames.estimateGate]).
 */
data class EndedInputs(
    val scores: Map<String, ScoreSnapshot> = emptyMap(),
    val canEstimate: (SportsLiveItem) -> Boolean = { true }
)

/** Live now cut in three: still [live], [justEnded] (the dimmed strip, in list order) and the [ended] verdicts of those. */
data class LiveNowSplit(
    val live: List<SportsLiveItem>,
    val justEnded: List<SportsLiveItem>,
    /** By card key ([SportsLiveItem.streamKey]), for the [justEnded] cards only. */
    val ended: Map<String, EndedGame>,
    /**
     * ESPN finals still in [live] (the postgame grace), by card key: shown as finals, never LIVE (C3),
     * though they keep their place in the main list until [EndedGame.movesAtMs].
     */
    val finals: Map<String, EndedGame> = emptyMap()
) {
    /**
     * [scores] plus, for an ESPN-final card ([finals] or [ended]) with no snapshot now, the final it was
     * found on: a surface grouping by the event then never takes a finished game for a live one.
     */
    /**
     * F4: the card keys of [items] (what this split was made from) that are not live now: Just ended, dropped (an ESPN
     * final past its half hour, an estimate or overdue game past its drop) or an ESPN final in its grace. What live counts
     * leave out.
     */
    fun overKeys(items: List<SportsLiveItem>): Set<String> {
        val liveKeys = live.mapTo(HashSet()) { it.streamKey }
        return items.mapNotNullTo(HashSet()) { it.streamKey.takeIf { key -> key !in liveKeys } } + finals.keys
    }

    /** This split with [more]'s cards ([SportsLiveEndedGames.moreEnds], #354) out of [live]: in the strip after its own, or gone. */
    fun with(more: MoreEnds): LiveNowSplit {
        if (more.moved.isEmpty()) return this
        return copy(
            live = live.filter { it.streamKey !in more.moved },
            justEnded = justEnded + live.filter { it.streamKey in more.ended },
            ended = ended + more.ended
        )
    }

    fun scoresWithFinals(scores: Map<String, ScoreSnapshot>): Map<String, ScoreSnapshot> {
        val missing = (finals + ended).filter { (key, game) -> key !in scores && game.event != null }
        return if (missing.isEmpty()) scores else scores + missing.mapValues { it.value.event!! }
    }
}

/**
 * The end rules beyond ESPN's and the estimate's ([SportsLiveEndedGames.moreEnds], #354): [moved] holds every card they
 * take out of the live list; [ended] those still shown in the strip ("Probably ended"), in list order.
 */
data class MoreEnds(val moved: Set<String>, val ended: Map<String, EndedGame>)

/**
 * Detects ended Live now games (#60) from data already at hand, with no request of its own.
 * Precision first: a wrong "ended" is worse than none, and a game ESPN shows live never moves.
 *
 * 1. ESPN final on a strict match ([ScoreChips.matchedEvent]): from the score-delayed snapshot only,
 *    with both scores out (a final the delay still withholds does not count). It moves after
 *    [GRACE_MS] (postgame). Postponed, cancelled, suspended games never count. A strict match decides
 *    alone: live, scheduled or anything else short of final means not ended, never estimated.
 * 2. No snapshot for the card, and the caller allows an estimate (Scores Off, no feed covers it, or
 *    its feeds answered without it: [LiveScoresRepository.unmatched], see [estimateGate]): its timed
 *    start (a merged card: the latest of its sources) plus [SportsLiveGameLength]. Never for channels,
 *    cards without a time of day, sports with no length, or a card ESPN was ever seen playing. An
 *    estimate only moves a card to the strip; it never hides it.
 *
 * Remembers when each card's final was first seen, so the grace and the drop count from then even
 * after the card stops being tracked for scores (Home and the section share the [shared] instance).
 * Those times are kept across restarts too ([install]; pruned after [FORGET_AFTER_MS]). A final first
 * seen without the game ever seen in play is dated at its estimated end (start + [SportsLiveGameLength],
 * capped at now), so a game long over at app start does not get a fresh half hour; with no start or no
 * length it is dated now with no grace (straight to Just ended). Thread-safe.
 */
class SportsLiveEndedGames {

    private class SeenFinal(val atMs: Long, val event: ScoreSnapshot, val graced: Boolean)

    /**
     * When a card's final was first seen, whether it keeps the grace, and the ESPN [eventId] it was seen
     * on: what is kept across restarts. Reused only for that same event, within [FORGET_AFTER_MS].
     */
    data class FinalTime(val atMs: Long, val graced: Boolean = true, val eventId: String = "")

    private val finals = HashMap<String, SeenFinal>()
    /** Every remembered final's time by card key, restored ones included; the stored book. */
    private val times = HashMap<String, FinalTime>()
    private var onTimesChanged: ((Map<String, FinalTime>) -> Unit)? = null
    /** Cards whose strict match was seen scheduled or in play: their final is dated when first seen. */
    private val seenUnfinished = HashSet<String>()

    /** [item]'s verdict at [nowMs] given its delayed [snapshot] (null: none); null while not ended. */
    @Synchronized
    fun verdict(item: SportsLiveItem, snapshot: ScoreSnapshot?, nowMs: Long, canEstimate: Boolean = true): EndedGame? {
        // A channel (24/7) is never moved, whatever its bracketed game did.
        if (item.isChannel) return null
        val key = item.streamKey
        if (snapshot != null) {
            // Sides not on the event one way round: no ESPN verdict, and ESPN's presence blocks the estimate.
            val event = ScoreChips.matchedEvent(item, snapshot) ?: return finals[key]?.toEnded()
            return when {
                event.state == ScoreState.IN || event.state == ScoreState.PRE -> {
                    forget(key)
                    if (seenUnfinished.size >= MAX_REMEMBERED) seenUnfinished.clear()
                    seenUnfinished += key
                    null
                }
                event.state == ScoreState.OFF -> {
                    forget(key)
                    null
                }
                isFinalWithScore(event) -> {
                    // A stored time counts only for this same ESPN event and while young enough.
                    val stored = times[key]?.takeIf { it.eventId == event.eventId && nowMs - it.atMs <= FORGET_AFTER_MS }
                    val at = stored ?: firstFinalAt(item, event, key, nowMs).also { first ->
                        if (times.size >= MAX_REMEMBERED) prune(nowMs)
                        times[key] = first
                        publishTimes()
                    }
                    if (finals.size >= MAX_REMEMBERED) prune(nowMs)
                    finals[key] = SeenFinal(at.atMs, event, at.graced)
                    EndedGame(EndedSignal.FINAL, at.atMs, event, at.graced)
                }
                // Withheld by the delay, or a state ESPN did not name: as it was.
                else -> finals[key]?.toEnded()
            }
        }
        finals[key]?.let { return it.toEnded() }
        // #267: a final seen before a restart stands until a snapshot says otherwise (a cold open, ESPN not answered yet).
        remembered(item, key, nowMs)?.let { return it }
        // ESPN saw it in play: only ESPN may end it, even once it is no longer tracked.
        if (key in seenUnfinished || !canEstimate) return null
        return estimate(item, nowMs)
    }

    /**
     * #60 phase 2: [item]'s estimated end when its link may be checked to confirm it is over: a game
     * past its start plus its sport's length that ESPN does not decide. Null for a channel, a card with
     * no timed start or no length, a strict ESPN match in [snapshot] (whatever its state: ESPN alone
     * ends it), a card ESPN was ever seen playing, or a final already seen.
     */
    @Synchronized
    fun linkCheckEnd(item: SportsLiveItem, snapshot: ScoreSnapshot?, nowMs: Long): Long? {
        if (item.isChannel) return null
        val key = item.streamKey
        if (key in seenUnfinished || key in finals || remembered(item, key, nowMs) != null) return null
        if (ScoreChips.matchedEvent(item, snapshot) != null) return null
        return estimate(item, nowMs)?.endedAtMs
    }

    /**
     * The end rules Live now adds to [split]'s, shared with the player's panel (#354), over [live] (a split's live cards)
     * at [nowMs], each a "Probably ended" estimate, playable, never hidden while in the strip:
     * - live far longer than its sport runs ([SportsLiveLiveDuration.isOverdue]), unless links were found lately
     *   ([hasRecentLinks]) or ESPN strictly matches it (whatever its state): dated at its start plus that longest run;
     * - #275: past its estimated end, ESPN not deciding ([linkCheckEnd]), and every lookup found no stream ([noStreams],
     *   card keys as [SportsLiveItem.hasEnded] reads them): dated at its estimated end.
     * A card whose drop time has passed is moved but not in [MoreEnds.ended]. Dead links stay Live now's own (its checker).
     */
    fun moreEnds(
        live: List<SportsLiveItem>,
        scores: Map<String, ScoreSnapshot>,
        nowMs: Long,
        noStreams: Set<String>,
        hasRecentLinks: (SportsLiveItem) -> Boolean
    ): MoreEnds {
        val moved = HashSet<String>()
        val ended = LinkedHashMap<String, EndedGame>()
        for (item in live) {
            val key = item.streamKey
            val snapshot = scores[key]
            // F9: a strict ESPN match decides alone (in play, scheduled, postponed or a final in its grace): never overdue.
            val verdict = if (SportsLiveLiveDuration.isOverdue(item, nowMs) && !hasRecentLinks(item) &&
                ScoreChips.matchedEvent(item, snapshot) == null
            ) {
                moved += key
                timedStart(item)?.let { start -> EndedGame(EndedSignal.ESTIMATE, start + SportsLiveLiveDuration.cardMaxLiveMs(item)) }
            } else if (key in noStreams) {
                linkCheckEnd(item, snapshot, nowMs)?.let { end -> moved += key; EndedGame(EndedSignal.ESTIMATE, end) }
            } else {
                null
            }
            if (verdict != null && nowMs < verdict.dropsAtMs) ended[key] = verdict
        }
        return MoreEnds(moved, ended)
    }

    /**
     * #375: the card keys of [items] Live now shows "Probably ended" at [nowMs]: an estimate ([verdict] with [canEstimate]),
     * or an overdue game ([moreEnds], links found lately ([hasRecentLinks]) excepted). Never an ESPN final, never a
     * channel. What Home labels so (it never hides one), so one game reads the same on every surface.
     */
    fun probablyEndedKeys(
        items: List<SportsLiveItem>,
        scores: Map<String, ScoreSnapshot>,
        nowMs: Long,
        canEstimate: (SportsLiveItem) -> Boolean,
        hasRecentLinks: (SportsLiveItem) -> Boolean
    ): Set<String> {
        val (over, live) = items.partition { item -> verdict(item, scores[item.streamKey], nowMs, canEstimate(item)) != null }
        val estimated = over.filter { item -> verdict(item, scores[item.streamKey], nowMs, canEstimate(item))?.signal == EndedSignal.ESTIMATE }
            .mapTo(HashSet()) { it.streamKey }
        return estimated + moreEnds(live, scores, nowMs, emptySet(), hasRecentLinks).moved
    }

    /**
     * Splits Live now [items] (in order) by their verdicts at [nowMs]; [scores] are the delayed
     * snapshots by card key, [canEstimate] which cards may be estimated. A card whose drop time has
     * passed is in neither list.
     */
    fun split(
        items: List<SportsLiveItem>,
        scores: Map<String, ScoreSnapshot>,
        nowMs: Long,
        canEstimate: (SportsLiveItem) -> Boolean = { true }
    ): LiveNowSplit {
        val live = ArrayList<SportsLiveItem>(items.size)
        val justEnded = ArrayList<SportsLiveItem>()
        val ended = HashMap<String, EndedGame>()
        val finals = HashMap<String, EndedGame>()
        for (item in items) {
            val verdict = verdict(item, scores[item.streamKey], nowMs, canEstimate(item))
            when {
                verdict == null -> live += item
                nowMs < verdict.movesAtMs -> {
                    live += item
                    if (verdict.signal == EndedSignal.FINAL) finals[item.streamKey] = verdict
                }
                nowMs < verdict.dropsAtMs -> {
                    justEnded += item
                    ended[item.streamKey] = verdict
                }
                else -> Unit
            }
        }
        return LiveNowSplit(live, justEnded, ended, finals)
    }

    /**
     * The card keys of [items] ESPN confirmed final, past the grace, at [nowMs]: what Home drops. Never
     * an estimate, so with no scores (Hide until focused, Off) Home drops only finals already seen.
     */
    fun confirmedEndedKeys(items: List<SportsLiveItem>, scores: Map<String, ScoreSnapshot>, nowMs: Long): Set<String> =
        items.mapNotNullTo(HashSet()) { item ->
            val verdict = verdict(item, scores[item.streamKey], nowMs, canEstimate = false)
            item.streamKey.takeIf { verdict?.signal == EndedSignal.FINAL && nowMs >= verdict.movesAtMs }
        }

    /**
     * The card keys of [items] ESPN shows final at [nowMs], in the postgame grace too: what Home's Live now row leaves
     * out (l24 polish P1), so a finished game never reads "Live now" over its final score, not even at a cold start
     * from the scores kept on disk. Never an estimate.
     */
    fun finalKeys(items: List<SportsLiveItem>, scores: Map<String, ScoreSnapshot>, nowMs: Long): Set<String> =
        items.mapNotNullTo(HashSet()) { item ->
            item.streamKey.takeIf { verdict(item, scores[item.streamKey], nowMs, canEstimate = false)?.signal == EndedSignal.FINAL }
        }

    /**
     * Restores the final times kept from an earlier run ([saved], as [decodeTimes] reads them) and from
     * then on hands every change to [onChange] (the store's save). A time already seen this run keeps
     * the earlier of the two. Times older than [FORGET_AFTER_MS] at [nowMs] are dropped.
     */
    @Synchronized
    fun install(saved: Map<String, FinalTime>, nowMs: Long, onChange: (Map<String, FinalTime>) -> Unit) {
        for ((key, time) in saved) {
            if (nowMs - time.atMs > FORGET_AFTER_MS) continue
            val current = times[key]
            if (current != null && current.atMs <= time.atMs) continue
            if (times.size >= MAX_REMEMBERED) break
            times[key] = time
            finals[key]?.let { finals[key] = SeenFinal(time.atMs, it.event, time.graced) }
        }
        onTimesChanged = onChange
        publishTimes()
    }

    private fun publishTimes() {
        onTimesChanged?.invoke(HashMap(times))
    }

    private fun forget(key: String) {
        finals.remove(key)
        if (times.remove(key) != null) publishTimes()
    }

    /**
     * [confirmedEndedKeys] with every source of each such card (a merged card's alternates too), as
     * [SportsLiveItem.hasEnded] reads them: what the dual-screen picker leaves out.
     */
    fun confirmedEndedSourceKeys(items: List<SportsLiveItem>, scores: Map<String, ScoreSnapshot>, nowMs: Long): Set<String> {
        val ended = confirmedEndedKeys(items, scores, nowMs)
        return items.filter { it.streamKey in ended }.flatMapTo(HashSet()) { item -> (listOf(item) + item.alternates).map { it.streamKey } }
    }

    /**
     * The source keys of [items] Live now shows over at [nowMs] (live.19 QA F2): an ESPN final (in its grace
     * too), Just ended, or "Probably ended" (an estimate), with every source of a merged card, as
     * [SportsLiveItem.hasEnded] reads them. What the end card's Up next and "Next live game" never pick.
     * [scores] and [canEstimate] as [split] takes them.
     */
    fun overSourceKeys(
        items: List<SportsLiveItem>,
        scores: Map<String, ScoreSnapshot>,
        nowMs: Long,
        /** F8: given, overdue games ([moreEnds], links found lately excepted) count as over too, as Live now and the panel show them. */
        hasRecentLinks: ((SportsLiveItem) -> Boolean)? = null,
        canEstimate: (SportsLiveItem) -> Boolean = { true }
    ): Set<String> {
        val (over, live) = items.partition { item -> verdict(item, scores[item.streamKey], nowMs, canEstimate(item)) != null }
        val moved = hasRecentLinks?.let { moreEnds(live, scores, nowMs, emptySet(), it).moved }.orEmpty()
        return (over + live.filter { it.streamKey in moved })
            .flatMapTo(HashSet()) { item -> (listOf(item) + item.alternates).map { it.streamKey } }
    }

    private fun SeenFinal.toEnded() = EndedGame(EndedSignal.FINAL, atMs, event, graced)

    /**
     * #267: [item]'s final as remembered from an earlier run ([install]) when no snapshot is in hand: the score feeds have
     * not answered since the restart, so the list would otherwise call a game already seen over LIVE. With no ESPN game in
     * hand it carries no event (no score to show). Only for a card with a timed start no later than that final (never an
     * addon reusing its id for a later or untimed game), never past [FORGET_AFTER_MS]; a snapshot in play or scheduled
     * clears it ([forget]).
     */
    private fun remembered(item: SportsLiveItem, key: String, nowMs: Long): EndedGame? {
        val time = times[key]?.takeIf { nowMs - it.atMs in 0..FORGET_AFTER_MS } ?: return null
        // F3: only a card with a timed start no later than that final: one with none could be a later game under a reused
        // key, and a dropped card is no longer tracked, so no snapshot would ever clear it.
        val start = timedStart(item) ?: return null
        if (start > time.atMs) return null
        // F1: no grace. With no ESPN game in hand there is no final to show in place, and a final in its grace stays in
        // the live list, where a surface with no snapshot reads it LIVE: straight to Just ended.
        return EndedGame(EndedSignal.FINAL, time.atMs, event = null, graced = false)
    }

    /**
     * When a final first seen at [nowMs] ended. Seen in play before: now. Else its start plus its sport's
     * length, capped at now; with no start or no length: now, with no grace (never a fresh LIVE spell).
     */
    private fun firstFinalAt(item: SportsLiveItem, event: ScoreSnapshot, key: String, nowMs: Long): FinalTime {
        if (key in seenUnfinished) return FinalTime(nowMs, eventId = event.eventId)
        val start = event.startMs?.takeIf { event.timeValid } ?: timedStart(item)
        val length = SportsLiveGameLength.of(item)
        if (start == null || length == null) return FinalTime(nowMs, graced = false, eventId = event.eventId)
        return FinalTime(minOf(nowMs, start + length), eventId = event.eventId)
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
        val before = times.size
        times.values.removeAll { nowMs - it.atMs > FORGET_AFTER_MS }
        if (times.size >= MAX_REMEMBERED) times.clear()
        if (times.size != before) publishTimes()
    }

    companion object {
        /** After ESPN's final, the card stays in the main list this long (postgame). */
        const val GRACE_MS = 10 * 60_000L

        /** After an ESPN final, the card leaves Live now. */
        const val LINGER_MS = 30 * 60_000L

        /** An estimated card stays in the strip at most this long after its estimated end. */
        const val ESTIMATE_LINGER_MS = 6 * 60 * 60_000L

        /** A remembered final (and its stored time) is forgotten this long after it ended. */
        const val FORGET_AFTER_MS = 6 * 60 * 60_000L
        private const val MAX_REMEMBERED = 2_000

        /** What every surface uses, so Home, the section and the player panel agree. */
        val shared = SportsLiveEndedGames()

        /**
         * Which cards may be estimated: all while Scores is Off ([scoresOff]); otherwise a card no ESPN
         * feed covers, or one the score repository confirmed [unmatched]. A coverable card waits for
         * its feeds' first answer, so it never flickers into the strip and back.
         */
        fun estimateGate(scoresOff: Boolean, unmatched: Set<String>): (SportsLiveItem) -> Boolean = { item ->
            scoresOff || item.streamKey in unmatched ||
                (listOf(item) + item.alternates).all { EspnLeagueMap.leaguesFor(it).isEmpty() }
        }

        fun isFinalWithScore(event: ScoreSnapshot): Boolean =
            event.state == ScoreState.POST && event.isFinal && event.homeScore != null && event.awayScore != null

        /**
         * ESPN's delayed view says [item]'s strictly matched game is over, with its score out (a final the
         * delay still withholds is not, so "Final" never shows early): the card is labelled final, never
         * LIVE, whatever its addon says (C3). Never a channel (24/7): it keeps its LIVE pill, as [verdict]
         * never moves one.
         */
        fun isEspnFinal(item: SportsLiveItem, snapshot: ScoreSnapshot?): Boolean {
            if (item.isChannel) return false
            val event = ScoreChips.matchedEvent(item, snapshot) ?: return false
            return isFinalWithScore(event)
        }

        /**
         * A card whose link check found it dead (#60 phase 2), at [deadAtMs]: "Probably ended", like an
         * estimate, dated by the check so its six hours in the strip start then.
         */
        fun linkDead(deadAtMs: Long): EndedGame = EndedGame(EndedSignal.ESTIMATE, deadAtMs)

        /** One line per time: "atMs,g|n,eventId,key" (the key last, so it may hold commas). */
        fun encodeTimes(times: Map<String, FinalTime>): String =
            times.entries.joinToString("\n") { (key, time) ->
                "${time.atMs},${if (time.graced) 'g' else 'n'},${time.eventId},$key"
            }

        /** Reads [encodeTimes]' text; a line it cannot read is skipped. */
        fun decodeTimes(stored: String?): Map<String, FinalTime> =
            stored.orEmpty().lineSequence().mapNotNull { line ->
                val parts = line.split(',', limit = 4)
                val atMs = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                val graced = when (parts.getOrNull(1)) {
                    "g" -> true
                    "n" -> false
                    else -> return@mapNotNull null
                }
                val eventId = parts.getOrNull(2)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val key = parts.getOrNull(3)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                key to FinalTime(atMs, graced, eventId)
            }.toMap()

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
