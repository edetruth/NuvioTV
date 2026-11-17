package com.nuvio.tv.core.sportslive.scores

import java.time.LocalDate

/** How often a scoreboard feed is fetched. Pure, so the timing rules are tested on their own. */
internal object ScoreCadence {
    const val LIVE_MS = 30_000L
    const val UPCOMING_MS = 5 * 60_000L
    const val SOON_MS = 5 * 60_000L
    /** `soccer/all` is the largest feed: never more often than this. */
    const val SOCCER_ALL_FLOOR_MS = 60_000L
    val BACKOFF_MS = listOf(30_000L, 60_000L, 120_000L, 300_000L)
    /** Consecutive failures after which a feed is paused and its scores hidden until a success. */
    const val PAUSE_AFTER_FAILURES = 3
    const val JITTER_MS = 3_000L

    /**
     * Millis from the last fetch to the next, or null to stop: [matched] are the tracked cards' games
     * found in the feed, [events] the whole feed.
     * - After a failure: the backoff for [failures] (30 s → 60 s → 2 min → 5 min), or [retryAfterMs] if longer.
     * - A day before yesterday is final: fetched once.
     * - Any matched game live: 30 s. All matched games final: stop. Only upcoming: 5 min, tightening to
     *   30 s from 5 min before the earliest start. Postponed/suspended/delayed (OFF) only: 5 min.
     * - No matched game: 5 min (a miss is retried), or stop when every game in the feed is final.
     * - `soccer/all` never under 60 s.
     */
    fun nextIntervalMs(
        feed: ScoreFeed,
        matched: Collection<ScoreSnapshot>,
        events: List<ScoreSnapshot>,
        failures: Int,
        retryAfterMs: Long?,
        today: LocalDate,
        nowMs: Long
    ): Long? {
        val interval = when {
            failures > 0 -> maxOf(BACKOFF_MS[(failures - 1).coerceAtMost(BACKOFF_MS.lastIndex)], retryAfterMs ?: 0L)
            feed.date.isBefore(today.minusDays(1)) -> return null
            else -> stateInterval(matched, events, nowMs) ?: return null
        }
        return if (feed.league == EspnLeagueMap.SOCCER_ALL) maxOf(interval, SOCCER_ALL_FLOOR_MS) else interval
    }

    private fun stateInterval(matched: Collection<ScoreSnapshot>, events: List<ScoreSnapshot>, nowMs: Long): Long? {
        if (matched.isEmpty()) {
            return if (events.isNotEmpty() && events.all { it.state == ScoreState.POST }) null else UPCOMING_MS
        }
        if (matched.any { it.state == ScoreState.IN }) return LIVE_MS
        val waiting = matched.filter { it.state == ScoreState.PRE || it.state == ScoreState.UNKNOWN }
        // A suspended or delayed game may resume: checked at the upcoming pace, never faster.
        if (waiting.isEmpty()) return if (matched.any { it.state == ScoreState.OFF }) UPCOMING_MS else null
        val earliest = waiting.mapNotNull { it.startMs }.minOrNull() ?: return UPCOMING_MS
        return (earliest - SOON_MS - nowMs).coerceIn(LIVE_MS, UPCOMING_MS)
    }
}

/** One scoreboard request: a league on a date. Shared by every card on it. */
data class ScoreFeed(val league: EspnLeague, val date: LocalDate)
