package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.FinalNote
import com.nuvio.tv.core.sportslive.scores.GolfResult
import com.nuvio.tv.core.sportslive.scores.MySports
import com.nuvio.tv.core.sportslive.scores.ResultRow
import com.nuvio.tv.core.sportslive.scores.ResultsNextGames
import com.nuvio.tv.core.sportslive.scores.SpoilerProtection
import com.nuvio.tv.core.sportslive.scores.TennisEnding
import com.nuvio.tv.core.sportslive.scores.TennisMatch
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoreTeam
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.core.sportslive.scores.SportsResults
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.FollowedTeam
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
import org.junit.Assert.assertNull
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
    fun `Jump to lists all leagues, then My sports' leagues of the day in order, sports outside My sports under Other sports`() {
        val rows = SportsResults.rows(soccerAll + nba, emptyList())
        val entries = resultsJumpEntries(rows, MySports.decode(setOf("basketball")))
        assertEquals(listOf(null, "NBA"), entries.map { it.name })
        assertEquals(nba.size, entries.first().count)
        // Soccer is not in My sports: every soccer competition sits under Other sports, all of them first.
        val others = resultsOtherRows(rows, emptyList(), MySports.decode(setOf("basketball")))
        val other = resultsOtherEntries(others)
        assertEquals(ResultsJump.OtherSports, other.first().jump)
        assertEquals(others.size, other.first().count)
        assertTrue(others.all { it.event.league.sport == "soccer" })
        assertEquals(others.size, other.drop(1).sumOf { it.count })
    }

    @Test
    fun `a league picked in Jump to lists that league whole, and Back to all leagues lists them all again`() {
        val both = SportsResults.rows(nba + cbb, emptyList())
        val college = resultsJumpEntries(both, withCollege).single { it.name == "NCAAM" }.jump as ResultsJump.League
        // Never collapsed: the league is what the viewer asked for.
        val items = resultListItems(both, withCollege, college, expanded = emptySet(), replays = emptySet())
        assertEquals(SportsResults.rows(cbb, emptyList()).map { it.key }.toSet(), items.rows().toSet())
        assertEquals(listOf("NCAAM"), items.headers().map { it.name })
        assertTrue(items.none { it is ResultListItem.More })
        // Back (jump null): every league again, long tails collapsed.
        val all = resultListItems(both, withCollege, jump = null, expanded = emptySet(), replays = emptySet())
        assertEquals(listOf("NBA", "NCAAM"), all.headers().map { it.name })
        assertTrue(all.any { it is ResultListItem.More })
        // A league of another sport lists from the other sports' rows.
        val hockey = row("h1", "hockey", league = "nhl", competition = "90", name = "NHL")
        val others = resultsOtherRows(both, listOf(hockey), withCollege)
        val nhl = ResultsJump.League(SportsResults.groupKey(hockey.event))
        assertTrue(resultsJumpValid(nhl, both, others))
        assertFalse(resultsJumpValid(nhl, both, emptyList()))
        assertEquals(listOf("h1"), resultListItems(both, withCollege, nhl, emptySet(), emptySet(), others).rows())
    }

    @Test
    fun `Your teams in Jump to lists the followed teams' games and the teams that did not play`() {
        val rows = listOf(
            row("1", "basketball", followed = true, league = "nba", competition = "46", name = "NBA"),
            row("2", "basketball", league = "nba", competition = "46", name = "NBA")
        )
        val items = resultListItems(
            rows, MySports.DEFAULT, ResultsJump.YourTeams, emptySet(), emptySet(),
            notPlayed = listOf(ResultsNotPlayed("Chicago Bears", 1_000L))
        )
        assertEquals(listOf("1"), items.rows())
        assertEquals(listOf(true), items.headers().map { it.yourTeams })
        assertEquals(ResultListItem.NotPlayed("Chicago Bears", 1_000L, today = false), items.last())
        // Under every league too, right under YOUR TEAMS' games, even when none played.
        val none = resultListItems(
            rows.drop(1), MySports.DEFAULT, null, emptySet(), emptySet(),
            notPlayed = listOf(ResultsNotPlayed("Chicago Bears", null)), today = true
        )
        assertEquals(ResultListItem.NotPlayed("Chicago Bears", null, today = true), none[1])
        assertTrue((none.first() as ResultListItem.Header).yourTeams)
    }

    @Test
    fun `a followed team with no game that day reads did not play, only when followed from ESPN`() {
        val bears = FollowedTeam("Chicago Bears", "American Football", espnId = "3", league = "football/nfl")
        val celtics = FollowedTeam("Boston Celtics", "Basketball", espnId = "2", league = "basketball/nba")
        val nameOnly = FollowedTeam("Arsenal", "Soccer")
        val base = row("9", "basketball", league = "nba").event
        val game = base.copy(home = base.home.copy(id = "2", displayName = "Boston Celtics"))
        assertEquals(listOf("Chicago Bears"), resultsNotPlayedTeams(listOf(bears, celtics, nameOnly), listOf(game)).map { it.name })
        // An upcoming or live game that day counts as playing.
        assertTrue(resultsNotPlayedTeams(listOf(celtics), listOf(game.copy(state = ScoreState.PRE))).isEmpty())
        // At most three lines.
        val many = (1..5).map { FollowedTeam("Team $it", "Hockey", espnId = "$it", league = "hockey/nhl") }
        assertEquals(RESULTS_NOT_PLAYED_MAX, resultsNotPlayedTeams(many, emptyList()).size)
    }

    @Test
    fun `the Jump to pill keeps a long league name short`() {
        assertEquals("Jump to", resultsJumpFace("Jump to"))
        val face = resultsJumpFace("Nitto ATP Finals · Men's Singles")
        assertEquals(RESULTS_JUMP_FACE_MAX, face.length)
        assertTrue(face.endsWith("…"))
    }

    @Test
    fun `other sports fetch every feed My sports leaves out, never one already fetched`() {
        val fetched = MySports.decode(setOf("basketball", "soccer")).feeds()
        val other = SportsResults.otherFeeds(fetched)
        assertTrue(other.none { it in fetched })
        assertFalse(EspnLeagueMap.SOCCER_ALL in other)
        assertTrue(EspnLeagueMap.NHL in other && EspnLeagueMap.COLLEGE_BASKETBALL in other)
        // Every sport picked with every league: nothing else to fetch but the leagues off by default.
        assertTrue(SportsResults.otherFeeds(MySports.DEFAULT.feeds()).none { it == EspnLeagueMap.NBA || it == EspnLeagueMap.SOCCER_ALL })
    }

    @Test
    fun `next game is looked up only for a team followed from ESPN in its own league`() {
        assertEquals(EspnLeague("football", "nfl"), ResultsNextGames.leagueOf(FollowedTeam("Chicago Bears", league = "football/nfl")))
        assertNull(ResultsNextGames.leagueOf(FollowedTeam("Arsenal", league = "soccer/all")))
        assertNull(ResultsNextGames.leagueOf(FollowedTeam("Arsenal")))
    }

    @Test
    fun `the next game is left out while the team's last result is still hidden`() {
        // Review S4: after a knockout game, "next: Sat" means they won and no next game means they lost.
        val last = nba.first().copy(state = ScoreState.POST, isFinal = true)
        val line = ResultsNotPlayed("Chicago Bears", 1_000L, lastFinal = last)
        val ended = SpoilerProtection(games = listOf(SpoilerProtection.of(last, 0L).copy(endedAtMs = 1L)))
        assertNull(resultsNotPlayedShown(listOf(line), ScoresMode.SHOW, SpoilerProtection()).single().nextStartMs)
        assertNull(resultsNotPlayedShown(listOf(line), ScoresMode.OFF, ended).single().nextStartMs)
        // Revealed or watched: the next game shows.
        assertEquals(1_000L, resultsNotPlayedShown(listOf(line), ScoresMode.SHOW, ended).single().nextStartMs)
        // No final within Results' days: as it was.
        val noFinal = ResultsNotPlayed("Chicago Bears", 1_000L)
        assertEquals(1_000L, resultsNotPlayedShown(listOf(noFinal), ScoresMode.SHOW, SpoilerProtection()).single().nextStartMs)
        // Only a final within the last 8 days counts.
        val startMs = last.startMs!!
        assertEquals(last, ResultsNextGames.lastFinalOf(listOf(last), startMs + 60_000L))
        assertNull(ResultsNextGames.lastFinalOf(listOf(last), startMs + ResultsNextGames.LAST_FINAL_MS))
    }

    @Test
    fun `other sports are fetched when asked, for a jump that needs them, or on a past day with nothing from My sports`() {
        val nbaRows = SportsResults.rows(nba, emptyList())
        val nbaKey = SportsResults.groupKey(nbaRows.first().event)
        assertTrue(resultsOtherWanted(asked = true, jump = null, rows = nbaRows, hasMyFinals = true, today = true))
        assertTrue(resultsOtherWanted(false, ResultsJump.OtherSports, nbaRows, hasMyFinals = true, today = false))
        assertTrue(resultsOtherWanted(false, ResultsJump.League("hockey-90"), nbaRows, hasMyFinals = true, today = false))
        assertFalse(resultsOtherWanted(false, ResultsJump.League(nbaKey), nbaRows, hasMyFinals = true, today = false))
        assertFalse(resultsOtherWanted(false, ResultsJump.YourTeams, nbaRows, hasMyFinals = false, today = false))
        assertTrue(resultsOtherWanted(false, null, emptyList(), hasMyFinals = false, today = false))
        // Today offers Yesterday instead: no fetch on its own.
        assertFalse(resultsOtherWanted(false, null, emptyList(), hasMyFinals = false, today = true))
        assertFalse(resultsOtherWanted(false, null, nbaRows, hasMyFinals = true, today = false))
    }

    @Test
    fun `left and right on a row jump to the previous and next league's first game`() {
        val rows = listOf(
            row("t1", "basketball", followed = true, league = "nba", competition = "46", name = "NBA"),
            row("n1", "basketball", league = "nba", competition = "46", name = "NBA"),
            row("n2", "basketball", league = "nba", competition = "46", name = "NBA"),
            row("e1", "soccer", league = "all", competition = "700", name = "English Premier League"),
            row("e2", "soccer", league = "all", competition = "700", name = "English Premier League")
        )
        val items = resultListItems(rows, MySports.DEFAULT, null, emptySet(), emptySet(), notPlayed = listOf(ResultsNotPlayed("Bears", null)))
        // YOUR TEAMS, then NBA, then Premier League, each landing on the section's first game.
        assertEquals("n1", resultsLeagueJump(items, "t1", forward = true))
        assertEquals("e1", resultsLeagueJump(items, "n2", forward = true))
        assertEquals("n1", resultsLeagueJump(items, "e2", forward = false))
        assertEquals("t1", resultsLeagueJump(items, "n1", forward = false))
        // At either end: null (right then enters the stage, left does nothing).
        assertNull(resultsLeagueJump(items, "e1", forward = true))
        assertNull(resultsLeagueJump(items, "t1", forward = false))
        // A collapsed section's more row jumps like its games.
        val long = (1..10).map { row("c$it", "basketball", league = "mens-college-basketball", competition = "41", name = "NCAAM") }
        val withMore = resultListItems(rows + long, withCollege, null, emptySet(), emptySet())
        val more = withMore.filterIsInstance<ResultListItem.More>().single()
        assertEquals("e1", resultsLeagueJump(withMore, more.key, forward = true))
    }

    @Test
    fun `Hide until focused never reveals on entry or when focus comes back, only after a key press in the list`() {
        val guard = ResultsRevealGuard()
        // Entry from the header, a jump, or focus restored after a replay: no reveal timer.
        assertNull(guard.focusTarget("a"))
        guard.onKey()
        assertEquals("b", guard.focusTarget("b"))
        // Leaving the list (a replay, the Game page, the header) disarms it again.
        guard.onLeave()
        assertNull(guard.focusTarget("a"))
    }

    @Test
    fun `a followed team's score never shows on focus, open or return, only once revealed, in every mode`() = runTest {
        val now = 10_000_000_000L
        val team = ScoreTeam("7", "BOS", "Boston Celtics", null, null, null, null, 101, emptyList())
        val other = ScoreTeam("8", "NYK", "New York Knicks", null, null, null, null, 99, emptyList())
        val event = ScoreSnapshot(
            eventId = "g", league = EspnLeague("basketball", "nba"), startMs = now - 3 * 3_600_000L, state = ScoreState.POST,
            clockText = "Final", isFinal = true, home = team, away = other
        )
        val mine = ResultRow(event, followed = true, note = FinalNote.FT)
        val followed = listOf(FollowedTeam("Boston Celtics", "Basketball", espnId = "7", league = "basketball/nba"))
        // The dwell reveal ran (focus long enough): still hidden, as on open and after a replay.
        val reveal = ResultSpoilerReveal(backgroundScope)
        reveal.onFocus("g")
        advanceTimeBy(ResultSpoilerReveal.REVEAL_AFTER_MS); runCurrent()
        assertEquals(setOf("g"), reveal.revealed.value)
        for (mode in listOf(ScoresMode.SHOW, ScoresMode.HIDE_UNTIL_FOCUSED)) {
            for (myTeams in listOf(true, false)) {
                val protection = SpoilerProtection(myTeams = myTeams, followed = followed, nowMs = now)
                val state = SportsLiveResultsUiState(mode = mode, revealed = reveal.revealed.value, protection = protection)
                assertFalse("$mode $myTeams", state.showsScore(mine))
                assertEquals(ResultHiddenReason.YOUR_TEAM, state.hiddenReason(mine))
                // The Game page it opens keeps it hidden under Show too.
                assertFalse(state.gameRequestFor(mine).revealed)
                assertTrue(state.gameRequestFor(mine).spoilerSafe)
                // Reveal result (or watching it) ends protection: then it shows.
                val revealed = protection.copy(games = SpoilerProtection.ended(emptyList(), event, now))
                assertTrue("$mode $myTeams", state.copy(protection = revealed).showsScore(mine))
            }
        }
        // Another team's game still follows the setting: Show at once, Hide until focused once dwelled.
        val theirs = mine.copy(followed = false)
        assertTrue(SportsLiveResultsUiState(mode = ScoresMode.SHOW).showsScore(theirs))
        assertFalse(SportsLiveResultsUiState(mode = ScoresMode.HIDE_UNTIL_FOCUSED).showsScore(theirs))
        assertEquals(ResultHiddenReason.SETTING, SportsLiveResultsUiState(mode = ScoresMode.HIDE_UNTIL_FOCUSED).hiddenReason(theirs))
        assertTrue(SportsLiveResultsUiState(mode = ScoresMode.HIDE_UNTIL_FOCUSED, revealed = setOf("g")).showsScore(theirs))
        // Saved for later says so.
        val saved = SpoilerProtection(games = SpoilerProtection.saved(emptyList(), event, now), nowMs = now)
        assertEquals(ResultHiddenReason.SAVED, SportsLiveResultsUiState(protection = saved).hiddenReason(theirs))
    }

    @Test
    fun `OK on an individual row toggles golf, plays a replay, else says there is no game page`() {
        val golf = GolfResult("PGA", emptyList())
        val match = TennisMatch("Open", emptyList(), TennisEnding.COMPLETED)
        assertEquals(ResultsIndividualOk.TOGGLE_GOLF, resultsIndividualOk(golf, null))
        assertEquals(ResultsIndividualOk.NO_PAGE, resultsIndividualOk(match, null))
        assertEquals(ResultsIndividualOk.WATCH_REPLAY, resultsIndividualOk(match, replayItem("Open final")))
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
        val items = resultListItems(rows, epl, jump = null, expanded = emptySet(), replays = emptySet())
        assertEquals(listOf(true, false, false), items.headers().map { it.yourTeams })
        assertEquals(listOf(null, "NBA", "English Premier League"), items.headers().map { it.name })
        assertEquals(listOf("2", "4", "1"), items.rows())
        // Other sports lists the soccer competition I did not pick.
        val other = resultListItems(rows, epl, ResultsJump.OtherSports, emptySet(), emptySet(), resultsOtherRows(rows, emptyList(), epl))
        assertEquals(listOf("LALIGA 2"), other.headers().map { it.name })
    }

    @Test
    fun `a long section collapses to its first games and a more row that expands`() {
        val rows = SportsResults.rows(nba + cbb, emptyList())
        val items = resultListItems(rows, withCollege, jump = null, expanded = emptySet(), replays = emptySet())
        val more = items.filterIsInstance<ResultListItem.More>().single()
        val collegeFinals = SportsResults.rows(cbb, emptyList()).size // 43: one game of 44 is not final
        assertEquals(collegeFinals - RESULTS_SHOWN_COLLAPSED, more.count)
        assertEquals("NCAAM", more.name)
        // NBA (5 games) is short: shown whole.
        assertEquals(nba.size + RESULTS_SHOWN_COLLAPSED, items.rows().size)
        assertEquals(items.last(), more)
        val collegeRows = rows.filter { it.event.league == EspnLeagueMap.COLLEGE_BASKETBALL }
        assertEquals(collegeRows[RESULTS_SHOWN_COLLAPSED].key, more.firstHiddenKey)

        val expanded = resultListItems(rows, withCollege, jump = null, expanded = setOf(more.groupKey), replays = emptySet())
        assertTrue(expanded.none { it is ResultListItem.More })
        assertEquals(nba.size + collegeFinals, expanded.rows().size)
        // The first game the row held comes right after the games shown before.
        assertEquals(expanded.rows().indexOf(collegeRows[RESULTS_SHOWN_COLLAPSED - 1].key) + 1, expanded.rows().indexOf(more.firstHiddenKey))
        // Short sections never collapse.
        assertTrue(resultListItems(SportsResults.rows(nba, emptyList()), withCollege, null, emptySet(), emptySet()).none { it is ResultListItem.More })
    }

    @Test
    fun `college is left out of My sports unless picked`() {
        val rows = SportsResults.rows(nba + cbb, emptyList())
        val items = resultListItems(rows, MySports.DEFAULT, jump = null, expanded = emptySet(), replays = emptySet())
        assertEquals(listOf("NBA"), items.headers().map { it.name })
    }

    private fun replayItem(name: String) = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = listOf("Tennis")
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Replays",
        kind = SportsLiveKind.REPLAY, isLive = false, isChannel = false, startMs = 0L, sport = "Tennis"
    )

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
        val items = resultListItems(SportsResults.rows(nba, emptyList()), MySports.DEFAULT, null, emptySet(), replays.keys)
            .filterIsInstance<ResultListItem.Row>()
        assertEquals(listOf(game.eventId), items.filter { it.hasReplay }.map { it.row.key })
        assertEquals(nba.size - 1, items.count { !it.hasReplay })
    }
}
