package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.scores.ScoreChip
import com.nuvio.tv.core.sportslive.scores.ScoreChips
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.domain.model.SportsLiveItem
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Settings → Sports & Live → "When a game ends" (#161): Move (default: after 10 s, only into one of my games showing
 * "▶ Ready", else it asks, [SportsLiveEndCountdown.move]), Ask, or Stay.
 */
enum class SportsLiveGameEndAction {
    MOVE, ASK, STAY;

    companion object {
        val DEFAULT = MOVE

        fun fromStored(value: String?): SportsLiveGameEndAction = entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}

/** How the game playing was found to be over (#161). */
enum class GameEndReason {
    /** ESPN's delayed view says final, with its score out. */
    FINAL,

    /** The game is in the Just ended window (#60): an ESPN final past its grace, or its time estimate. */
    JUST_ENDED,

    /** Its stream ended or failed around the game's expected end, with ESPN not showing it in play. */
    STREAM_ENDED
}

/**
 * When the game playing is over (#161), so a stream that stops then is the end of the game and not a
 * failure: the recovery ladder (#138) never runs for it, and the end card shows instead of an error.
 * Precision first: a game ESPN shows in play (or not started) is never over, however late it runs.
 */
object SportsLiveGameEnd {
    /** A stream ending this close to (or after) the game's expected end is the game ending. */
    const val NEAR_END_MS = 20 * 60_000L

    /**
     * [item]'s expected end: its timed start (the latest of its sources) plus its sport's known length
     * ([SportsLiveGameLength.knownOf]); null for a card with no timed start or a sport with no length.
     */
    fun expectedEndMs(item: SportsLiveItem): Long? {
        val start = SportsLiveEndedGames.timedStart(item) ?: return null
        val length = SportsLiveGameLength.knownOf(item) ?: return null
        return start + length
    }

    /**
     * [item] is over while its stream may still play: ESPN final with the score out on a strict match, or a
     * Just ended [verdict] (#60). Null otherwise, and always for a channel.
     */
    fun ended(item: SportsLiveItem, snapshot: ScoreSnapshot?, verdict: EndedGame?): GameEndReason? {
        if (item.isChannel) return null
        val event = ScoreChips.matchedEvent(item, snapshot)
        if (event != null && SportsLiveEndedGames.isFinalWithScore(event)) return GameEndReason.FINAL
        // A strict match in play or not started decides alone: never ended.
        if (event?.state == ScoreState.IN || event?.state == ScoreState.PRE) return null
        return if (verdict != null) GameEndReason.JUST_ENDED else null
    }

    /**
     * Whether [item]'s stream stopping or failing at [nowMs] is the game ending: [ended], or, with no
     * strict ESPN match in play or before the start, within [NEAR_END_MS] of its [expectedEndMs] or later.
     * Null: a real failure, the ladder runs.
     */
    fun failureIsEnd(item: SportsLiveItem, snapshot: ScoreSnapshot?, verdict: EndedGame?, nowMs: Long): GameEndReason? {
        if (item.isChannel) return null
        ended(item, snapshot, verdict)?.let { return it }
        val state = ScoreChips.matchedEvent(item, snapshot)?.state
        if (state == ScoreState.IN || state == ScoreState.PRE) return null
        val end = expectedEndMs(item) ?: return null
        return if (nowMs >= end - NEAR_END_MS) GameEndReason.STREAM_ENDED else null
    }

    /**
     * The end card's title (#161): "Final · Celtics 112–108 Knicks" under Scores Show with ESPN's final
     * score; "Game over" otherwise (Hide until focused, Off, or no score known).
     */
    fun title(item: SportsLiveItem, snapshot: ScoreSnapshot?, mode: ScoresMode?, strings: GameEndStrings): String {
        if (mode != ScoresMode.SHOW) return strings.gameOver
        val chip = ScoreChips.of(item, snapshot, mode, revealed = true) as? ScoreChip.Final ?: return strings.gameOver
        val (first, second) = ScoreChips.orderedSides(item) ?: return strings.gameOver
        return String.format(strings.finalScore, first, chip.score.first, chip.score.second, second)
    }
}

/** The end card's words, from string resources (tests pass the English ones). */
data class GameEndStrings(
    /** "Game over" */
    val gameOver: String,
    /** "Final · %1$s %2$d–%3$d %4$s" */
    val finalScore: String
)

/**
 * The 10 s "Up next" countdown of the end card (#161). It runs only while [endsAtMs] is set: any key or
 * Back [cancel]s it, and the card then waits for OK. Not thread-safe; main thread.
 */
class SportsLiveEndCountdown(private val durationMs: Long = DURATION_MS) {
    var endsAtMs: Long? = null
        private set

    val running: Boolean get() = endsAtMs != null

    fun start(nowMs: Long) {
        endsAtMs = nowMs + durationMs
    }

    /** True when it was running. */
    fun cancel(): Boolean = (endsAtMs != null).also { endsAtMs = null }

    /** Whole seconds left, rounded up; 0 once due or when not running. */
    fun secondsLeft(nowMs: Long): Int {
        val end = endsAtMs ?: return 0
        return ((end - nowMs + 999) / 1_000).toInt().coerceAtLeast(0)
    }

    /** True once it ran out: the card moves on. */
    fun isDue(nowMs: Long): Boolean = endsAtMs?.let { nowMs >= it } == true

    companion object {
        const val DURATION_MS = 10_000L

        /**
         * Whether the countdown runs (#161): the setting says Move, there is a game to move to, and this
         * player (or dual pane) has the sound; otherwise the card waits for OK.
         */
        fun runs(action: SportsLiveGameEndAction, hasNext: Boolean, hasSound: Boolean): Boolean =
            action == SportsLiveGameEndAction.MOVE && hasNext && hasSound

        /**
         * What the end card does about Up next [next] (#161, research 6: no leading sports app switches to an
         * unrelated game by itself). The countdown runs only when [runs] says so and [next] is one of my games
         * ([isMine]: [SportsLiveMyGames.isMine]) showing "▶ Ready" ([isReady]); otherwise the card waits for OK.
         * A move the still-watching guard holds back ([mayAutoMove] false) asks "Are you still watching?" instead.
         */
        fun move(
            action: SportsLiveGameEndAction,
            next: SportsLiveItem?,
            hasSound: Boolean,
            isMine: (SportsLiveItem) -> Boolean,
            isReady: (SportsLiveItem) -> Boolean,
            mayAutoMove: Boolean
        ): SportsLiveEndMove {
            if (next == null || !runs(action, hasNext = true, hasSound = hasSound)) return SportsLiveEndMove.WAIT
            if (!isMine(next) || !isReady(next)) return SportsLiveEndMove.WAIT
            return if (mayAutoMove) SportsLiveEndMove.COUNTDOWN else SportsLiveEndMove.STILL_WATCHING
        }
    }
}

/** What an end card does about its Up next game (#161). */
enum class SportsLiveEndMove {
    /** The 10 s countdown runs, then it moves. */
    COUNTDOWN,

    /** Up next waits for OK ("OK to switch"). */
    WAIT,

    /** It would have moved, but nobody pressed a key for long ([SportsLiveStillWatching]): "Are you still watching?". */
    STILL_WATCHING
}

/**
 * What every sports player knows about the game it plays (#161, #162), by game id (the content id): the
 * card, its last delayed ESPN snapshot (the full player's zap state keeps it current) and the end each
 * player reported, so the full player, a dual pane and the mini player decide the same way. Process-wide;
 * in memory only. Thread-safe.
 */
class SportsLiveGameWatch(
    private val ended: SportsLiveEndedGames = SportsLiveEndedGames.shared,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private class Entry(val item: SportsLiveItem, var snapshot: ScoreSnapshot?)

    private val games = LinkedHashMap<String, Entry>()

    /** The game a player watched last: the debug triggers act on it. */
    @Volatile
    private var lastWatched: String? = null

    /** Debug builds only (the emulator check): every stream stopping counts as its game ending. */
    @Volatile
    var debugEndAll = false

    /** Debug builds only: the end card names no next game (the nothing-live state). */
    @Volatile
    var debugNoNext = false

    /** Debug builds only: until then, every game counts as near its kickoff with no live link. */
    @Volatile
    var debugWaitUntilMs = 0L

    /** Debug builds only: the next sports link start goes straight to the waiting card. */
    @Volatile
    private var debugWaitNextStart = false

    private val _ends = MutableSharedFlow<Pair<String, GameEndReason>>(extraBufferCapacity = 8)

    /** A player's stream stopped because its game ended: (game id, why). The full player's end card listens. */
    val ends: SharedFlow<Pair<String, GameEndReason>> = _ends.asSharedFlow()

    /** [item] plays (or is about to) in some player. */
    @Synchronized
    fun watch(item: SportsLiveItem) {
        val entry = games[item.id]
        if (entry == null || entry.item.streamKey != item.streamKey) games[item.id] = Entry(item, entry?.snapshot)
        lastWatched = item.id
        while (games.size > MAX_GAMES) games.remove(games.keys.first())
    }

    /** [gameId]'s delayed ESPN snapshot now (null: none matched). */
    @Synchronized
    fun note(gameId: String, snapshot: ScoreSnapshot?) {
        games[gameId]?.snapshot = snapshot
    }

    @Synchronized
    fun item(gameId: String?): SportsLiveItem? = gameId?.let { games[it]?.item }

    /** The game a player watched last, while it is remembered (Report a problem, #172). */
    fun lastWatchedItem(): SportsLiveItem? = item(lastWatched)

    @Synchronized
    fun snapshot(gameId: String?): ScoreSnapshot? = gameId?.let { games[it]?.snapshot }

    /**
     * Whether a stream of [gameId] (or of [item], for a player that has the card) stopping now is its game
     * ending ([SportsLiveGameEnd.failureIsEnd]). Null for a game never watched, or a real failure.
     */
    fun failureIsEnd(gameId: String?, item: SportsLiveItem? = null): GameEndReason? {
        if (debugEndAll && com.nuvio.tv.BuildConfig.IS_DEBUG_BUILD) return GameEndReason.STREAM_ENDED
        val card = item ?: item(gameId) ?: return null
        val snapshot = snapshot(card.id)
        val now = nowMs()
        val verdict = ended.verdict(card, snapshot, now, canEstimate = snapshot == null)
        return SportsLiveGameEnd.failureIsEnd(card, snapshot, verdict, now)
    }

    /** Whether opening [gameId] now, with its links not live yet, waits for them (#162, [SportsLiveKickoffWait]). */
    fun waits(gameId: String?): Boolean {
        if (nowMs() < debugWaitUntilMs && com.nuvio.tv.BuildConfig.IS_DEBUG_BUILD) return true
        val card = item(gameId) ?: return false
        return SportsLiveKickoffWait.applies(card, snapshot(card.id), nowMs())
    }

    /** Debug builds only: the game watched last ends now, as if its stream had stopped at the end. */
    fun debugEndNow() {
        if (!com.nuvio.tv.BuildConfig.IS_DEBUG_BUILD) return
        lastWatched?.let { reportEnd(it, GameEndReason.STREAM_ENDED) }
    }

    /** Debug builds only: the next sports link start shows the waiting card, for [forMs]. */
    fun debugWaitNext(forMs: Long) {
        if (!com.nuvio.tv.BuildConfig.IS_DEBUG_BUILD) return
        debugWaitUntilMs = nowMs() + forMs
        debugWaitNextStart = true
    }

    /** True once after [debugWaitNext]. */
    fun consumeDebugWait(): Boolean = debugWaitNextStart.also { debugWaitNextStart = false }

    /** A player stopped [gameId]'s stream as its game ended. */
    fun reportEnd(gameId: String, reason: GameEndReason) {
        _ends.tryEmit(gameId to reason)
    }

    companion object {
        private const val MAX_GAMES = 50

        val shared = SportsLiveGameWatch()
    }
}

/**
 * "Waiting for the stream to start…" (#162): a game opened around its kickoff whose links are not live
 * yet waits for them, with no failure ladder or error. Only within [WINDOW_MS] of its scheduled start,
 * or while ESPN shows it not started; never once ESPN shows it in play or over.
 */
object SportsLiveKickoffWait {
    const val WINDOW_MS = 30 * 60_000L

    /** The waiting card re-checks the links this often. */
    const val RECHECK_MS = 30_000L

    fun applies(item: SportsLiveItem, snapshot: ScoreSnapshot?, nowMs: Long): Boolean {
        if (item.isChannel) return false
        when (ScoreChips.matchedEvent(item, snapshot)?.state) {
            ScoreState.PRE -> return true
            ScoreState.IN, ScoreState.POST, ScoreState.OFF -> return false
            else -> Unit
        }
        val start = item.startMs?.takeIf { item.startHasTimeOfDay } ?: return false
        return nowMs >= start - WINDOW_MS && nowMs <= start + WINDOW_MS
    }
}
