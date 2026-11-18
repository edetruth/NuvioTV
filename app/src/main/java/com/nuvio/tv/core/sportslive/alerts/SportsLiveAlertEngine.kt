package com.nuvio.tv.core.sportslive.alerts

import com.nuvio.tv.core.sportslive.scores.ScoreSide
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.domain.model.SportsLiveItem

/** Settings → Sports & Live → Alerts (#42, #44); each on by default. */
data class SportsLiveAlertSettings(
    /** Followed teams' games get the kickoff prompt without "Remind me". */
    val remindMyTeams: Boolean = true,
    /** "‹Team› score" toasts while watching something else (Scores = Show only). */
    val scoreAlerts: Boolean = true,
    /** The full-time toast (names only under Hide until focused; none under Off). */
    val fullTimeAlerts: Boolean = true
)

/** What is on screen now, as far as alerts care. */
data class SportsLiveWatching(
    /** Content ids playing (the full player's, the mini player's): their games never alert. */
    val playingIds: Set<String> = emptySet(),
    /** The full player is on screen: the kickoff prompt is the small one, and score toasts may show. */
    val inPlayer: Boolean = false,
    /** When the stream on screen started (null: none): nothing shows in its first 10 s. */
    val streamStartedAtMs: Long? = null,
    /** Dual screen is on: prompts wait, toasts are dropped. */
    val dual: Boolean = false,
    /** The title playing in the full player, for "you're watching ‹title›". */
    val playingTitle: String? = null
)

/** A card the alerts look at: reminded, or a followed team's game, with its delayed ESPN game. */
data class AlertGame(
    val item: SportsLiveItem,
    /** The card's strict ESPN match from the delayed feed; null when none. */
    val snapshot: ScoreSnapshot?,
    /** Reminded by hand, or automatically as a followed team's game ([RemindersState.isReminded]). */
    val reminded: Boolean,
    /** The sides of [snapshot] a followed team is, by ESPN data ([com.nuvio.tv.core.sportslive.scores.TeamFollows]). */
    val followedSides: Set<ScoreSide> = emptySet()
)

/** One overlay to show. */
sealed interface SportsLiveAlert {
    val item: SportsLiveItem

    /** "Watch now?" at kickoff (#44, and #42 for followed teams). [again]: the one re-ask after Later. */
    data class Kickoff(override val item: SportsLiveItem, val snapshot: ScoreSnapshot?, val again: Boolean = false) : SportsLiveAlert

    /** A followed team's game changed score (#42). [scorer]: the side that scored; null when both changed. */
    data class ScoreChange(override val item: SportsLiveItem, val snapshot: ScoreSnapshot, val scorer: ScoreSide?) : SportsLiveAlert

    /** A followed team's game ended (#42). [namesOnly]: Hide until focused, no score. */
    data class FullTime(override val item: SportsLiveItem, val snapshot: ScoreSnapshot, val namesOnly: Boolean) : SportsLiveAlert
}

/**
 * Decides, from the delayed snapshots alone, which alerts are due (#42, #44). Main-thread or otherwise
 * serialised use only.
 *
 * - Kickoff: a reminded game whose ESPN state turns live (from pre, or within [KICKOFF_WINDOW_MS] of
 *   the start), or, with no ESPN game, whose timed start has just passed. Asked once; [later] asks once
 *   more after [LATER_MS]. Never for the game playing (it counts as answered).
 * - Score change: a followed side's game, Scores = Show, in the full player, not the game playing; at
 *   most one per game per [SCORE_ALERT_GAP_MS]. Never under Hide until focused (a "scored" toast already
 *   tells part of the score) or Off.
 * - Full time: a followed side's game seen live, then final: the score under Show, names only under
 *   Hide until focused, nothing under Off.
 * - Nothing in a stream's first [STREAM_SETTLE_MS] (prompts wait; toasts are dropped), and only a game
 *   seen before changes is announced (a first look never alerts).
 */
class SportsLiveAlertEngine {
    private class Prompt(var shown: Boolean = false, var laterAt: Long? = null, var laterUsed: Boolean = false, var done: Boolean = false)

    private class Seen(val state: ScoreState, val home: Int?, val away: Int?)

    /** By card key. */
    private val prompts = HashMap<String, Prompt>()

    /** By ESPN event id: what was last seen. */
    private val seen = HashMap<String, Seen>()
    private val sawPregame = HashSet<String>()
    private val lastScoreAlertAt = HashMap<String, Long>()
    private val finals = HashSet<String>()

    fun update(
        nowMs: Long,
        games: List<AlertGame>,
        settings: SportsLiveAlertSettings,
        mode: ScoresMode,
        watching: SportsLiveWatching
    ): List<SportsLiveAlert> {
        trim()
        val settling = watching.streamStartedAtMs?.let { nowMs - it < STREAM_SETTLE_MS } == true
        val alerts = ArrayList<SportsLiveAlert>()
        for (game in games.distinctBy { it.item.streamKey }) {
            val playing = isPlaying(game.item, watching)
            if (game.reminded) kickoff(game, nowMs, playing, holding = settling || watching.dual)?.let(alerts::add)
            val event = game.snapshot ?: continue
            val before = seen[event.eventId]
            seen[event.eventId] = Seen(event.state, event.homeScore, event.awayScore)
            if (event.state == ScoreState.PRE) sawPregame += event.eventId
            if (before == null || game.followedSides.isEmpty() || playing || settling || watching.dual) continue
            scoreChange(game, event, before, nowMs, settings, mode, watching)?.let(alerts::add)
            fullTime(game, event, before, settings, mode)?.let(alerts::add)
        }
        return alerts
    }

    /** Later (or the prompt closed by itself): asked once more after [LATER_MS]; the second time, done. */
    fun later(item: SportsLiveItem, nowMs: Long) {
        val prompt = prompts.getOrPut(item.streamKey) { Prompt(shown = true) }
        if (prompt.laterUsed) {
            prompt.done = true
        } else {
            prompt.laterUsed = true
            prompt.laterAt = nowMs + LATER_MS
        }
    }

    /** Watch, or Back on the prompt: not asked again. */
    fun answered(item: SportsLiveItem) {
        prompts.getOrPut(item.streamKey) { Prompt() }.done = true
    }

    private fun kickoff(game: AlertGame, nowMs: Long, playing: Boolean, holding: Boolean): SportsLiveAlert.Kickoff? {
        val prompt = prompts.getOrPut(game.item.streamKey) { Prompt() }
        if (prompt.done) return null
        val event = game.snapshot
        // Already watching it, or it is over: nothing to ask.
        if (playing || event?.state == ScoreState.POST || event?.state == ScoreState.OFF) {
            prompt.done = true
            return null
        }
        if (holding) return null
        val laterAt = prompt.laterAt
        if (laterAt != null) {
            if (nowMs < laterAt) return null
            prompt.laterAt = null
            return SportsLiveAlert.Kickoff(game.item, event, again = true)
        }
        if (prompt.shown || !kickedOff(game, nowMs)) return null
        prompt.shown = true
        return SportsLiveAlert.Kickoff(game.item, event)
    }

    private fun kickedOff(game: AlertGame, nowMs: Long): Boolean {
        val event = game.snapshot
        val start = event?.takeIf { it.timeValid }?.startMs ?: game.item.startMs?.takeIf { game.item.startHasTimeOfDay }
        return when (event?.state) {
            // ESPN says it is on: seen before kick-off, or just started (not a game long under way).
            ScoreState.IN -> event.eventId in sawPregame || start == null || nowMs - start <= KICKOFF_WINDOW_MS
            null -> start != null && nowMs >= start && nowMs - start <= KICKOFF_WINDOW_MS
            // Pre-game (ESPN's start may slip past the listed one), or anything else: wait.
            else -> false
        }
    }

    private fun scoreChange(
        game: AlertGame,
        event: ScoreSnapshot,
        before: Seen,
        nowMs: Long,
        settings: SportsLiveAlertSettings,
        mode: ScoresMode,
        watching: SportsLiveWatching
    ): SportsLiveAlert.ScoreChange? {
        if (!settings.scoreAlerts || mode != ScoresMode.SHOW || !watching.inPlayer) return null
        if (event.state != ScoreState.IN || before.state != ScoreState.IN) return null
        val home = event.homeScore ?: return null
        val away = event.awayScore ?: return null
        val homeBefore = before.home ?: return null
        val awayBefore = before.away ?: return null
        // Only a score going up: a correction downwards is not news.
        if (home < homeBefore || away < awayBefore || (home == homeBefore && away == awayBefore)) return null
        val last = lastScoreAlertAt[event.eventId]
        if (last != null && nowMs - last < SCORE_ALERT_GAP_MS) return null
        lastScoreAlertAt[event.eventId] = nowMs
        val scorer = when {
            home > homeBefore && away == awayBefore -> ScoreSide.HOME
            away > awayBefore && home == homeBefore -> ScoreSide.AWAY
            else -> null
        }
        return SportsLiveAlert.ScoreChange(game.item, event, scorer)
    }

    private fun fullTime(
        game: AlertGame,
        event: ScoreSnapshot,
        before: Seen,
        settings: SportsLiveAlertSettings,
        mode: ScoresMode
    ): SportsLiveAlert.FullTime? {
        if (before.state != ScoreState.IN || event.state != ScoreState.POST || !event.isFinal) return null
        if (!finals.add(event.eventId)) return null
        if (!settings.fullTimeAlerts || mode == ScoresMode.OFF) return null
        return SportsLiveAlert.FullTime(game.item, event, namesOnly = mode == ScoresMode.HIDE_UNTIL_FOCUSED)
    }

    private fun trim() {
        if (prompts.size > MAX_REMEMBERED) prompts.clear()
        if (seen.size > MAX_REMEMBERED) seen.clear()
        if (sawPregame.size > MAX_REMEMBERED) sawPregame.clear()
        if (lastScoreAlertAt.size > MAX_REMEMBERED) lastScoreAlertAt.clear()
        if (finals.size > MAX_REMEMBERED) finals.clear()
    }

    companion object {
        /** "Later" asks again this much later, once (decision 2). */
        const val LATER_MS = 5 * 60_000L

        /** At most one score toast per game this often (decision 4). */
        const val SCORE_ALERT_GAP_MS = 2 * 60_000L

        /** A game first seen live this long after its start is not "kicking off" any more. */
        const val KICKOFF_WINDOW_MS = 20 * 60_000L

        /** Nothing shows in a stream's first seconds (the design's 10 s). */
        const val STREAM_SETTLE_MS = 10_000L

        private const val MAX_REMEMBERED = 500

        /** True when [item] (or one of its sources) is what plays now. */
        fun isPlaying(item: SportsLiveItem, watching: SportsLiveWatching): Boolean =
            watching.playingIds.isNotEmpty() && (listOf(item) + item.alternates).any { it.id in watching.playingIds }
    }
}
