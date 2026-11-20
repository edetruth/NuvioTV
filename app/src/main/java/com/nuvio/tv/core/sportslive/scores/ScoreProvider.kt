package com.nuvio.tv.core.sportslive.scores

import java.time.LocalDate

/** A source of scoreboards. Implementations never throw: a failure is [ScoreboardResult.FAILED]. */
interface ScoreProvider {
    suspend fun scoreboard(league: EspnLeague, date: LocalDate): ScoreboardResult

    /**
     * [league]'s scoreboard for [date] read for its schedule only (Scores Off, live.20): the answer is never
     * written to disk (no HTTP cache entry) and never counts toward the feeds' health in Scores status.
     */
    suspend fun scheduleScoreboard(league: EspnLeague, date: LocalDate): ScoreboardResult = scoreboard(league, date)

    /**
     * The last copy of [league]'s scoreboard for [date] fetched within [RECENT_MS], from local storage
     * only (no request), or null: a warm start after an app restart (#66).
     */
    suspend fun recentScoreboard(league: EspnLeague, date: LocalDate): ScoreboardResult? = null

    /** [league]'s teams for the logo directory (#82); null on any failure. */
    suspend fun teamList(league: EspnLeague): List<DirectoryTeam>? = null

    companion object {
        /** How old a warm-start copy may be. */
        const val RECENT_MS = 2 * 60_000L
    }
}

/** A source of one game's summary (the Game page). Never throws: a failure is null. */
interface GameSummarySource {
    /** ESPN's summary JSON of [eventId] on [league]'s feed, or null on any failure. */
    suspend fun summary(league: EspnLeague, eventId: String): String?
}
