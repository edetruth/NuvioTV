package com.nuvio.tv.core.sportslive.scores

import java.time.LocalDate

/** A source of scoreboards. Implementations never throw: a failure is [ScoreboardResult.FAILED]. */
interface ScoreProvider {
    suspend fun scoreboard(league: EspnLeague, date: LocalDate): ScoreboardResult
}

/** A source of one game's summary (the Game page). Never throws: a failure is null. */
interface GameSummarySource {
    /** ESPN's summary JSON of [eventId] on [league]'s feed, or null on any failure. */
    suspend fun summary(league: EspnLeague, eventId: String): String?
}
