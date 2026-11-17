package com.nuvio.tv.core.sportslive.scores

import java.time.LocalDate

/** A source of scoreboards. Implementations never throw: a failure is [ScoreboardResult.FAILED]. */
interface ScoreProvider {
    suspend fun scoreboard(league: EspnLeague, date: LocalDate): ScoreboardResult
}
