package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #139: what the in-player game info overlay shows, from the delayed snapshot and the Game page's summary. */
class GameInfoTest {

    private val nba = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA)
    // IND @ PHI: home Philadelphia 76ers, away Indiana Pacers.
    private val pacers = nba.first { it.eventId == "401909985" }
    private val nfl = EspnFixtures.events(EspnFixtures.NFL_LIVE_SITUATION_SAMPLE, EspnLeagueMap.NFL)
    // SEA @ LV, 3rd quarter: Seattle has the ball, 2nd & 6 at the LV 34.
    private val seahawks = nfl.first { it.eventId == "401873101" }

    private fun item(name: String, sport: String = "Basketball") = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
        kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false,
        startMs = null, sport = sport, teams = SportsLiveTeams.parse(name)
    )

    private val card = item("Pacers at 76ers")

    private fun live(event: ScoreSnapshot, home: Int?, away: Int?, lines: Pair<List<Int>, List<Int>> = emptyList<Int>() to emptyList()) = event.copy(
        state = ScoreState.IN, isFinal = false, clockText = "4:12 - 4th",
        home = event.home.copy(score = home, linescores = lines.first), away = event.away.copy(score = away, linescores = lines.second)
    )

    private val haliburton = Performer(ScoreSide.AWAY, "IND", "T. Haliburton", listOf(PerformerStat("28", "PTS"), PerformerStat("11", "AST")))
    private val embiid = Performer(ScoreSide.HOME, "PHI", "J. Embiid", listOf(PerformerStat("31", "PTS")))
    private val siakam = Performer(ScoreSide.AWAY, "IND", "P. Siakam", listOf(PerformerStat("9", "REB")))

    private fun summary(
        event: ScoreSnapshot,
        home: Int?,
        away: Int?,
        final: Boolean = false,
        performers: List<Performer> = listOf(haliburton, embiid, siakam)
    ) = GameSummary(
        eventId = event.eventId, league = event.league,
        state = if (final) ScoreState.POST else ScoreState.IN, isFinal = final, clockText = null, startMs = null, venue = null,
        away = GameTeam(null, "IND", null, null, null, away, emptyList(), null),
        home = GameTeam(null, "PHI", null, null, null, home, emptyList(), null),
        scorers = emptyList(), teamStats = emptyList(), performers = performers,
        boxScore = emptyList(), standings = emptyList(), highlights = emptyList()
    )

    @Test
    fun `a live game shows its scoreline, clock and linescore in the card's team order`() {
        val event = live(pacers, home = 88, away = 91, lines = listOf(20, 22, 25, 21) to listOf(25, 24, 22, 20))
        val info = GameInfoBuilder.of(card, event, ScoresMode.SHOW, summary = null)!!
        val details = info.details as ScoreDetails.Shown
        assertEquals("IND", details.first.short)
        assertEquals(91, details.first.score)
        assertEquals(88, details.second.score)
        assertEquals("4:12 - 4th", details.status)
        assertTrue(details.live)
        assertEquals(listOf(25, 24, 22, 20), details.first.linescores)
        assertEquals(4, details.periods.size)
        assertTrue(info.leaders.isEmpty())
    }

    @Test
    fun `the top performers are one per team, the card's first team first`() {
        val event = live(pacers, home = 88, away = 91)
        val info = GameInfoBuilder.of(card, event, ScoresMode.SHOW, summary(event, home = 88, away = 91))!!
        assertEquals(listOf(haliburton, embiid), info.leaders)
        // Named home first, the home leader comes first.
        val homeFirst = GameInfoBuilder.of(item("76ers vs Pacers"), event, ScoresMode.SHOW, summary(event, home = 88, away = 91))!!
        assertEquals(listOf(embiid, haliburton), homeFirst.leaders)
    }

    @Test
    fun `a summary ahead of the delayed score shows no performers, so they never spoil a basket`() {
        val delayed = live(pacers, home = 88, away = 91)
        assertTrue(GameInfoBuilder.leadersOf(card, delayed, summary(delayed, home = 90, away = 91)).isEmpty())
        // The summary says final while the delayed score is still live.
        assertTrue(GameInfoBuilder.leadersOf(card, delayed, summary(delayed, home = 88, away = 91, final = true)).isEmpty())
        // Another game's summary.
        assertTrue(GameInfoBuilder.leadersOf(card, delayed, summary(delayed, 88, 91).copy(eventId = "other")).isEmpty())
        assertTrue(GameInfoBuilder.leadersOf(card, delayed, null).isEmpty())
    }

    @Test
    fun `a final shows the final score and its performers, with no situation`() {
        val info = GameInfoBuilder.of(card, pacers, ScoresMode.SHOW, summary(pacers, home = 131, away = 124, final = true))!!
        val details = info.details as ScoreDetails.Shown
        assertFalse(details.live)
        assertEquals(124, details.first.score)
        assertNull(info.situation)
        assertEquals(listOf(haliburton, embiid), info.leaders)
    }

    @Test
    fun `a live football game carries its situation for the situation line`() {
        val info = GameInfoBuilder.of(item("Seahawks at Raiders", "American Football"), seahawks, ScoresMode.SHOW, null)!!
        assertNotNull(info.situation)
        assertEquals("2nd & 6", info.situation!!.downDistance)
        assertEquals(seahawks, info.event)
    }

    @Test
    fun `hide until focused shows the score on demand, as Reveal does`() {
        val event = live(pacers, home = 88, away = 91)
        val info = GameInfoBuilder.of(card, event, ScoresMode.HIDE_UNTIL_FOCUSED, summary(event, 88, 91))!!
        assertTrue(info.details is ScoreDetails.Shown)
        assertEquals(2, info.leaders.size)
    }

    @Test
    fun `a postponed game shows its status only`() {
        val postponed = pacers.copy(state = ScoreState.OFF, isFinal = false, clockText = "Postponed")
        val info = GameInfoBuilder.of(card, postponed, ScoresMode.SHOW, summary(postponed, null, null))!!
        assertEquals(ScoreDetails.Status("Postponed"), info.details)
        assertTrue(info.leaders.isEmpty())
    }

    @Test
    fun `no overlay under Scores Off, before the setting is read, or without a strict match`() {
        val event = live(pacers, home = 88, away = 91)
        assertNull(GameInfoBuilder.of(card, event, ScoresMode.OFF, null))
        assertNull(GameInfoBuilder.of(card, event, null, null))
        assertNull(GameInfoBuilder.of(card, null, ScoresMode.SHOW, null))
        assertNull(GameInfoBuilder.of(item("Celtics vs 76ers"), event, ScoresMode.SHOW, null))
        assertNull(GameInfoBuilder.of(card, pacers.copy(state = ScoreState.PRE, isFinal = false), ScoresMode.SHOW, null))
        assertFalse(GameInfoBuilder.available(card, event, ScoresMode.OFF))
        assertFalse(GameInfoBuilder.available(null, event, ScoresMode.SHOW))
        assertTrue(GameInfoBuilder.available(card, event, ScoresMode.SHOW))
    }
}
