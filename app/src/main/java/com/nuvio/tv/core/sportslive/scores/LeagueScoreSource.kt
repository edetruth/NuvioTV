package com.nuvio.tv.core.sportslive.scores

import java.time.LocalDate

/**
 * A score feed beside ESPN for leagues ESPN does not cover (#169): EuroLeague / EuroCup first, more one at a
 * time. Its games come out as the same [ScoreSnapshot]s ESPN's do, under the [EspnLeague] key cards route to
 * ([EspnLeagueMap]), so the matcher, cadence and pollers treat them alike: a feed is polled only while a card
 * routed to it is on screen. Never throws: a failure is [ScoreboardResult.FAILED].
 */
interface LeagueScoreSource {
    /** True when this source answers for [league] instead of ESPN. */
    fun handles(league: EspnLeague): Boolean

    suspend fun scoreboard(league: EspnLeague, date: LocalDate): ScoreboardResult
}

/**
 * The app's [ScoreProvider] (#169): a league some [sources] handle goes to the first of them, every other
 * league to [espn]. Team lists and warm-start copies are ESPN's only.
 */
class RoutedScoreProvider(
    private val espn: ScoreProvider,
    private val sources: List<LeagueScoreSource>
) : ScoreProvider {

    private fun sourceFor(league: EspnLeague): LeagueScoreSource? = sources.firstOrNull { it.handles(league) }

    override suspend fun scoreboard(league: EspnLeague, date: LocalDate): ScoreboardResult =
        sourceFor(league)?.scoreboard(league, date) ?: espn.scoreboard(league, date)

    override suspend fun recentScoreboard(league: EspnLeague, date: LocalDate): ScoreboardResult? =
        if (sourceFor(league) != null) null else espn.recentScoreboard(league, date)

    override suspend fun teamList(league: EspnLeague): List<DirectoryTeam>? =
        if (sourceFor(league) != null) null else espn.teamList(league)
}
