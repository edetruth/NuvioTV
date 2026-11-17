package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.FinalNote
import com.nuvio.tv.core.sportslive.scores.MySport
import com.nuvio.tv.core.sportslive.scores.MySports
import com.nuvio.tv.core.sportslive.scores.ResultRow
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoreTeam
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.core.sportslive.scores.SportsResults
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class SportsLiveResultsTest {

    @Test
    fun `hide until focused reveals a row's score after 1 s of focus`() = runTest {
        val reveal = ResultSpoilerReveal(backgroundScope)
        reveal.onFocus("a")
        advanceTimeBy(999); runCurrent()
        assertFalse(ResultSpoilerReveal.showsScore(ScoresMode.HIDE_UNTIL_FOCUSED, reveal.revealed.value, "a"))
        advanceTimeBy(1); runCurrent()
        assertTrue(ResultSpoilerReveal.showsScore(ScoresMode.HIDE_UNTIL_FOCUSED, reveal.revealed.value, "a"))
        // Revealed stays revealed after focus moves on.
        reveal.onFocus("b")
        runCurrent()
        assertTrue(ResultSpoilerReveal.showsScore(ScoresMode.HIDE_UNTIL_FOCUSED, reveal.revealed.value, "a"))
    }

    @Test
    fun `moving on before 1 s reveals nothing`() = runTest {
        val reveal = ResultSpoilerReveal(backgroundScope)
        reveal.onFocus("a")
        advanceTimeBy(600); runCurrent()
        reveal.onFocus("b")
        advanceTimeBy(600); runCurrent()
        reveal.onFocus(null)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(emptySet<String>(), reveal.revealed.value)
    }

    @Test
    fun `refocusing the same row does not restart its timer`() = runTest {
        val reveal = ResultSpoilerReveal(backgroundScope)
        reveal.onFocus("a")
        advanceTimeBy(700); runCurrent()
        reveal.onFocus("a")
        advanceTimeBy(300); runCurrent()
        assertEquals(setOf("a"), reveal.revealed.value)
    }

    @Test
    fun `Show shows every score at once and Off none`() {
        assertTrue(ResultSpoilerReveal.showsScore(ScoresMode.SHOW, emptySet(), "a"))
        assertFalse(ResultSpoilerReveal.showsScore(ScoresMode.HIDE_UNTIL_FOCUSED, emptySet(), "a"))
        assertFalse(ResultSpoilerReveal.showsScore(ScoresMode.OFF, setOf("a"), "a"))
    }

    private fun row(
        id: String,
        sport: String,
        followed: Boolean = false,
        league: String = "x",
        competition: String? = null,
        name: String? = null
    ): ResultRow {
        val team = ScoreTeam(null, null, "Team $id", null, null, null, null, 1, emptyList())
        val event = ScoreSnapshot(
            eventId = id, league = EspnLeague(sport, league), startMs = 0L, state = ScoreState.POST, clockText = "Final",
            isFinal = true, home = team, away = team, competitionId = competition, leagueName = name
        )
        return ResultRow(event, followed, FinalNote.FT)
    }

    private val nba = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA)
    private val cbb = EspnFixtures.events(EspnFixtures.CBB_20261115, EspnLeagueMap.COLLEGE_BASKETBALL)
    private val soccerAll = EspnFixtures.events(EspnFixtures.SOCCER_ALL_FULL_20261116, EspnLeagueMap.SOCCER_ALL)
    private val withCollege = MySports.decode(setOf("basketball", "basketball/nba", "basketball/college-basketball", "soccer"))

    private fun List<ResultListItem>.headers() = filterIsInstance<ResultListItem.Header>()
    private fun List<ResultListItem>.rows() = filterIsInstance<ResultListItem.Row>().map { it.row.key }

    @Test
    fun `sport chips are my sports with results that day, in My sports order`() {
        val rows = SportsResults.rows(soccerAll + nba, emptyList())
        assertEquals(listOf(MySport.BASKETBALL, MySport.SOCCER), resultSportChips(rows, MySports.DEFAULT))
        assertEquals(listOf(MySport.SOCCER), resultSportChips(rows, MySports.decode(setOf("soccer", "hockey"))))
    }

    @Test
    fun `league chips show only the leagues present that day, and none for one league or My sports`() {
        val both = SportsResults.rows(nba + cbb, emptyList())
        val chips = resultLeagueChips(both, MySport.BASKETBALL)
        assertEquals(listOf("NBA", "NCAAM"), chips.map { it.name })
        assertEquals(emptyList<ResultLeagueChip>(), resultLeagueChips(SportsResults.rows(nba, emptyList()), MySport.BASKETBALL))
        assertEquals(emptyList<ResultLeagueChip>(), resultLeagueChips(both, null))
        assertEquals(emptyList<ResultLeagueChip>(), resultLeagueChips(both, MySport.HOCKEY))
        // A chosen league lists that league only.
        val college = resultListItems(both, withCollege, MySport.BASKETBALL, chips[1].key, setOf(chips[1].key), emptySet())
        assertEquals(SportsResults.rows(cbb, emptyList()).map { it.key }.toSet(), college.rows().toSet())
        assertEquals(listOf("NCAAM"), college.headers().map { it.name })
    }

    @Test
    fun `My sports lists my leagues under headers, YOUR TEAMS first, and leaves out what I did not pick`() {
        val rows = listOf(
            row("1", "soccer", league = "all", competition = "700", name = "English Premier League"),
            row("2", "basketball", followed = true, league = "nba", competition = "46", name = "NBA"),
            row("3", "soccer", league = "all", competition = "3921", name = "LALIGA 2"),
            row("4", "basketball", league = "nba", competition = "46", name = "NBA")
        ).map { it.copy(event = it.event.copy(leagueSlug = if (it.key == "1") "eng.1" else it.event.leagueSlug)) }
        val epl = MySports.decode(setOf("basketball", "soccer/eng.1"))
        val items = resultListItems(rows, epl, sport = null, league = null, expanded = emptySet(), replays = emptySet())
        assertEquals(listOf(true, false, false), items.headers().map { it.yourTeams })
        assertEquals(listOf(null, "NBA", "English Premier League"), items.headers().map { it.name })
        assertEquals(listOf("2", "4", "1"), items.rows())
        // The Soccer chip lists every soccer competition fetched.
        val soccer = resultListItems(rows, epl, MySport.SOCCER, league = null, expanded = emptySet(), replays = emptySet())
        assertEquals(listOf("English Premier League", "LALIGA 2"), soccer.headers().map { it.name })
    }

    @Test
    fun `a long section collapses to its first games and a more row that expands`() {
        val rows = SportsResults.rows(nba + cbb, emptyList())
        val items = resultListItems(rows, withCollege, sport = null, league = null, expanded = emptySet(), replays = emptySet())
        val more = items.filterIsInstance<ResultListItem.More>().single()
        val collegeFinals = SportsResults.rows(cbb, emptyList()).size // 43: one game of 44 is not final
        assertEquals(collegeFinals - RESULTS_SHOWN_COLLAPSED, more.count)
        assertEquals("NCAAM", more.name)
        // NBA (5 games) is short: shown whole.
        assertEquals(nba.size + RESULTS_SHOWN_COLLAPSED, items.rows().size)
        assertEquals(items.last(), more)
        val collegeRows = rows.filter { it.event.league == EspnLeagueMap.COLLEGE_BASKETBALL }
        assertEquals(collegeRows[RESULTS_SHOWN_COLLAPSED].key, more.firstHiddenKey)

        val expanded = resultListItems(rows, withCollege, sport = null, league = null, expanded = setOf(more.groupKey), replays = emptySet())
        assertTrue(expanded.none { it is ResultListItem.More })
        assertEquals(nba.size + collegeFinals, expanded.rows().size)
        // The first game the row held comes right after the games shown before.
        assertEquals(expanded.rows().indexOf(collegeRows[RESULTS_SHOWN_COLLAPSED - 1].key) + 1, expanded.rows().indexOf(more.firstHiddenKey))
        // Short sections never collapse.
        assertTrue(resultListItems(SportsResults.rows(nba, emptyList()), withCollege, null, null, emptySet(), emptySet()).none { it is ResultListItem.More })
    }

    @Test
    fun `college is left out of My sports unless picked`() {
        val rows = SportsResults.rows(nba + cbb, emptyList())
        val items = resultListItems(rows, MySports.DEFAULT, sport = null, league = null, expanded = emptySet(), replays = emptySet())
        assertEquals(listOf("NBA"), items.headers().map { it.name })
    }

    @Test
    fun `the Replay tag is on strict replay matches only`() {
        val day = LocalDate.of(2026, 11, 15)
        val game = nba.single { it.eventId == "401909990" }
        fun replay(name: String, start: String, sport: String = "Basketball") = SportsLiveItem(
            meta = MetaPreview(
                id = name, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
                posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
                releaseInfo = null, imdbRating = null, genres = listOf(sport)
            ),
            addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Replays",
            kind = SportsLiveKind.REPLAY, isLive = false, isChannel = false, startMs = Instant.parse(start).toEpochMilli(),
            sport = sport, startHasTimeOfDay = false, teams = SportsLiveTeams.parse(name)
        )
        val strict = replay("New York Knicks vs Toronto Raptors", "2026-11-15T00:00:00Z")
        // One side only, and a bare date of the day after: neither matches.
        val oneSide = replay("Indiana Pacers vs Somebody United", "2026-11-15T00:00:00Z")
        val nextDay = replay("Indiana Pacers vs Philadelphia 76ers", "2026-11-16T00:00:00Z")
        val replays = SportsResults.replaysByEvent(nba, day, listOf(strict, oneSide, nextDay), Instant.parse("2026-11-17T12:00:00Z").toEpochMilli())
        assertEquals(setOf(game.eventId), replays.keys)
        val items = resultListItems(SportsResults.rows(nba, emptyList()), MySports.DEFAULT, null, null, emptySet(), replays.keys)
            .filterIsInstance<ResultListItem.Row>()
        assertEquals(listOf(game.eventId), items.filter { it.hasReplay }.map { it.row.key })
        assertEquals(nba.size - 1, items.count { !it.hasReplay })
    }
}
