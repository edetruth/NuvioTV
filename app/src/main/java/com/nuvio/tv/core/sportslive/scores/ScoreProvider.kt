package com.nuvio.tv.core.sportslive.scores

import java.time.LocalDate

/** A source of scoreboards. Implementations never throw: a failure is [ScoreboardResult.FAILED]. */
interface ScoreProvider {
    suspend fun scoreboard(league: EspnLeague, date: LocalDate): ScoreboardResult

    /**
     * The last copy of [league]'s scoreboard for [date] fetched within [RECENT_MS], from local storage
     * only (no request), or null: a warm start after an app restart (#66).
     */
    suspend fun recentScoreboard(league: EspnLeague, date: LocalDate): ScoreboardResult? = null

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
