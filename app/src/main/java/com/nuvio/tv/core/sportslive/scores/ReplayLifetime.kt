package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.domain.model.FollowedTeam
import kotlin.math.abs

/**
 * What decides how long a followed team's replay stays in Your teams and the Catch up row (live.20 1c), gathered by
 * [CatchUpRepository] for each game [CatchUp.select] lists (which already needs a replay link, rule 5).
 */
data class ReplayFacts(
    /** The game's estimated end ([CatchUp.estimatedEndMs]). */
    val endMs: Long,
    /** The game's length: a replay played past half of it counts as watched. */
    val lengthMs: Long,
    /** When a followed team of the game plays its next game, if known (ESPN's days loaded). */
    val nextGameStartMs: Long? = null,
    /** Real playing time of its replays (the longest one). */
    val replayPlayedMs: Long = 0L,
    /** Real time the game was watched live, this process ([ResultSeen.liveWatchedMs]). */
    val liveWatchedMs: Long = 0L,
    /** The result is known and remembered: its final score was shown, or it was watched live a while ([CatchUpMarks.known]). */
    val resultSeen: Boolean = false,
    /** A big game (#137: playoffs, rivalry, ranked) or a close finish. */
    val big: Boolean = false,
    /** Saved with Watch later (#151) and still protected: when. */
    val savedAtMs: Long? = null
)

/**
 * The smart replay lifetime (live.20 1c, user 2026-11-20 "dynamic, a little intelligent"). A replay stays while it is
 * useful and leaves when:
 * 1. a followed team of the game has started its next game (so it adapts per sport: NBA/NHL fast, NFL weekly);
 * 2. the result is known: watched live for [LIVE_WATCHED_MS], the replay played past halfway (or to its end), or the
 *    final score was shown (Results, a Full time alert, the Game page);
 * 3. it is over [NORMAL_MS] past its end, or [BIG_MS] for a big game;
 * 4. Watch later (#151) overrides 1–3: it stays until the replay is watched, or [WATCH_LATER_MS] after it was saved.
 * Pure: every input is in [ReplayFacts].
 */
object ReplayLifetime {
    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 60 * MINUTE_MS

    /** An ordinary game's replay stays this long past its end. */
    const val NORMAL_MS = 48 * HOUR_MS

    /** A big game's replay may stay this long past its end. */
    const val BIG_MS = 4 * 24 * HOUR_MS

    /** A game saved for later stays this long after it was saved (its protection's length). */
    const val WATCH_LATER_MS = SpoilerProtection.PROTECT_MS

    /** Watched live this long: the result is known. */
    const val LIVE_WATCHED_MS = 20 * MINUTE_MS

    fun keeps(facts: ReplayFacts, nowMs: Long): Boolean {
        if (replayWatched(facts.replayPlayedMs, facts.lengthMs)) return false
        facts.savedAtMs?.let { saved -> return nowMs - saved < WATCH_LATER_MS }
        if (facts.resultSeen || facts.liveWatchedMs >= LIVE_WATCHED_MS) return false
        if (facts.nextGameStartMs?.let { it <= nowMs } == true) return false
        return nowMs - facts.endMs < if (facts.big) BIG_MS else NORMAL_MS
    }

    /** A replay played [playedMs] of a game [lengthMs] long is watched once past halfway (not after 2 min). */
    fun replayWatched(playedMs: Long, lengthMs: Long): Boolean = lengthMs > 0L && playedMs * 2 >= lengthMs

    /** A big game (#137's rating, without the followed-team boost) or a close finish. */
    fun big(event: ScoreSnapshot): Boolean = BigGame.rate(event).isBig || closeFinish(event)

    /** A final decided by a close margin for its sport (Most exciting's "close", #43). */
    fun closeFinish(event: ScoreSnapshot): Boolean {
        if (event.state != ScoreState.POST || event.individual != null) return false
        val home = event.homeScore ?: return false
        val away = event.awayScore ?: return false
        val within = LiveExcitement.CLOSE_WITHIN[event.league.sport] ?: return false
        return abs(home - away) <= within
    }

    /**
     * The start of the next game, after [event], of a followed team that played in [event], among [events] (ESPN's
     * days loaded); null when none is known.
     */
    fun nextGameStartMs(event: ScoreSnapshot, events: List<ScoreSnapshot>, followed: List<FollowedTeam>): Long? {
        val start = event.startMs ?: return null
        val teams = followed.filter { SportsResults.follows(it, event) }
        if (teams.isEmpty()) return null
        return events.asSequence()
            .filter { it.eventId != event.eventId && it.individual == null && it.timeValid }
            .mapNotNull { other -> other.startMs?.takeIf { it > start && teams.any { team -> SportsResults.follows(team, other) } } }
            .minOrNull()
    }
}
