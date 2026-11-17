package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreDetailsTest {

    private val strings = ScoreDetailsStrings(
        scoreline = "%1\$s %2\$d–%3\$d %4\$s",
        sides = "%1\$s – %2\$s",
        overtime = "OT",
        overtimeN = "%1\$dOT",
        extraTime = "ET%1\$d",
        shootout = "SO",
        emptyPeriod = "–",
        scorer = "%1\$s %2\$s",
        penalty = "%1\$s (pen)",
        ownGoal = "%1\$s (OG)",
        separator = ", "
    )

    private val nba = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA)
    // IND @ PHI, final: PHI 131 (29 29 39 34), IND 124 (35 22 30 37).
    private val pacers = nba.first { it.eventId == "401909985" }
    // NE @ DET, final: DET 35 (7 14 7 7), NE 10 (3 0 0 7).
    private val patriots = EspnFixtures.events(EspnFixtures.NFL_20261115, EspnLeagueMap.NFL).first { it.eventId == "401873053" }
    // Liverpool at Crystal Palace, FT 1–3: LIV Mac Allister 40', Wirtz 54', Isak 89'; CRY Nketiah 90'+5' (pen).
    private val palace = EspnFixtures.events(EspnFixtures.ENG1_20261108, EspnFixtures.ENG1).first { it.eventId == "401879243" }

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

    private fun shown(item: SportsLiveItem, event: ScoreSnapshot): ScoreDetails.Shown =
        ScoreDetailsBuilder.of(item, event, ScoresMode.SHOW, revealed = false) as ScoreDetails.Shown

    @Test
    fun `basketball final shows the scoreline and quarters in the title's team order`() {
        val details = shown(item("Indiana Pacers at Philadelphia 76ers"), pacers)
        assertEquals("IND 124–131 PHI", ScoreDetailsBuilder.scorelineText(details, strings))
        assertEquals(listOf(35, 22, 30, 37), details.first.linescores)
        assertEquals(listOf(29, 29, 39, 34), details.second.linescores)
        assertEquals(listOf("1", "2", "3", "4"), details.periods.map { ScoreDetailsBuilder.periodText(it, strings) })
        assertEquals(false, details.live)
        assertTrue(details.first.scorers.isEmpty())

        val homeFirst = shown(item("76ers vs Pacers"), pacers)
        assertEquals("PHI 131–124 IND", ScoreDetailsBuilder.scorelineText(homeFirst, strings))
        assertEquals(listOf(29, 29, 39, 34), homeFirst.first.linescores)
    }

    @Test
    fun `football shows four quarters`() {
        val details = shown(item("New England Patriots at Detroit Lions", "American Football"), patriots)
        assertEquals("NE 10–35 DET", ScoreDetailsBuilder.scorelineText(details, strings))
        assertEquals(listOf(3, 0, 0, 7), details.first.linescores)
        assertEquals(listOf(7, 14, 7, 7), details.second.linescores)
    }

    @Test
    fun `overtime, extra time and innings are labelled per sport`() {
        fun labels(sport: String, league: String, count: Int) =
            ScoreDetailsBuilder.periodLabels(EspnLeague(sport, league), count).map { ScoreDetailsBuilder.periodText(it, strings) }
        assertEquals(listOf("1", "2", "3", "4", "OT", "2OT"), labels("basketball", "nba", 6))
        assertEquals(listOf("1", "2", "OT"), labels("basketball", "mens-college-basketball", 3))
        assertEquals(listOf("1", "2", "3", "4", "OT"), labels("basketball", "wnba", 5))
        assertEquals(listOf("1", "2", "3", "4", "OT"), labels("football", "nfl", 5))
        assertEquals(listOf("1", "2", "3", "OT"), labels("hockey", "nhl", 4))
        assertEquals(listOf("1", "2", "ET1", "ET2"), labels("soccer", "eng.1", 4))
        assertEquals((1..11).map { it.toString() }, labels("baseball", "mlb", 11))
        assertEquals(
            listOf("1", "2", "3", "OT", "SO"),
            ScoreDetailsBuilder.periodLabels(EspnLeague("hockey", "nhl"), 5, shootout = true).map { ScoreDetailsBuilder.periodText(it, strings) }
        )
    }

    @Test
    fun `a hockey game decided in a shootout labels its last column SO, a playoff double overtime does not`() {
        val nhl = EspnLeague("hockey", "nhl")
        fun game(clock: String) = pacers.copy(
            league = nhl, leagueSlug = "nhl", clockText = clock,
            home = pacers.home.copy(linescores = listOf(1, 1, 0, 0, 1)),
            away = pacers.away.copy(linescores = listOf(0, 2, 0, 0, 0))
        )
        fun heads(clock: String) = shown(item("Pacers @ 76ers", "Hockey"), game(clock)).periods.map { ScoreDetailsBuilder.periodText(it, strings) }
        assertEquals(listOf("1", "2", "3", "OT", "SO"), heads("Final/SO"))
        assertEquals(listOf("1", "2", "3", "OT", "2OT"), heads("Final/2OT"))
    }

    @Test
    fun `a live game without a clock has no status`() {
        val live = pacers.copy(state = ScoreState.IN, isFinal = false, clockText = null)
        val details = shown(item("Pacers @ 76ers"), live)
        assertEquals(true, details.live)
        assertNull(details.status)
    }

    @Test
    fun `a live game pads the side yet to play its period and shows the clock`() {
        val live = pacers.copy(
            state = ScoreState.IN, isFinal = false, clockText = "Q3 4:12",
            home = pacers.home.copy(score = 88, linescores = listOf(29, 29, 30)),
            away = pacers.away.copy(score = 80, linescores = listOf(35, 22))
        )
        val details = shown(item("Pacers @ 76ers"), live)
        assertEquals("Q3 4:12", details.status)
        assertEquals(true, details.live)
        assertEquals(listOf(35, 22, null), details.first.linescores)
        assertEquals(listOf(29, 29, 30), details.second.linescores)
        assertEquals(3, details.periods.size)
    }

    @Test
    fun `no linescore row when one side has none`() {
        val event = pacers.copy(away = pacers.away.copy(linescores = emptyList()))
        val details = shown(item("Pacers @ 76ers"), event)
        assertTrue(details.periods.isEmpty())
        assertTrue(details.first.linescores.isEmpty())
        assertTrue(details.second.linescores.isEmpty())
    }

    @Test
    fun `soccer lists each side's scorers with minutes, penalties and own goals`() {
        val details = shown(item("Crystal Palace vs Liverpool", "Soccer"), palace)
        assertEquals("CRY 1–3 LIV", ScoreDetailsBuilder.scorelineText(details, strings))
        assertEquals("E. Nketiah 90'+5' (pen)", ScoreDetailsBuilder.scorersText(details.first, strings))
        assertEquals("A. Mac Allister 40', F. Wirtz 54', A. Isak 89'", ScoreDetailsBuilder.scorersText(details.second, strings))

        val brace = palace.copy(
            scorers = listOf(
                ScoreScorer(ScoreSide.HOME, "Saka", "23'", penalty = false, ownGoal = false),
                ScoreScorer(ScoreSide.AWAY, "Mac Allister", "30'", penalty = false, ownGoal = false),
                ScoreScorer(ScoreSide.HOME, "Saka", "67'", penalty = true, ownGoal = false),
                ScoreScorer(ScoreSide.HOME, "Van Dijk", "80'", penalty = false, ownGoal = true),
                ScoreScorer(null, "Nobody", "85'", penalty = false, ownGoal = false)
            )
        )
        val withBrace = shown(item("Crystal Palace vs Liverpool", "Soccer"), brace)
        assertEquals("Saka 23', 67' (pen), Van Dijk 80' (OG)", ScoreDetailsBuilder.scorersText(withBrace.first, strings))
        assertEquals("Mac Allister 30'", ScoreDetailsBuilder.scorersText(withBrace.second, strings))
    }

    @Test
    fun `scorers are only listed for soccer`() {
        val event = pacers.copy(scorers = listOf(ScoreScorer(ScoreSide.HOME, "Embiid", "Q1", penalty = false, ownGoal = false)))
        val details = shown(item("Pacers @ 76ers"), event)
        assertEquals(false, details.soccer)
        assertNull(ScoreDetailsBuilder.scorersText(details.second, strings))
    }

    @Test
    fun `hide until focused shows no score until revealed, Off shows nothing`() {
        val game = item("Crystal Palace vs Liverpool", "Soccer")
        assertEquals(ScoreDetails.Hidden(live = false), ScoreDetailsBuilder.of(game, palace, ScoresMode.HIDE_UNTIL_FOCUSED, revealed = false))
        assertTrue(ScoreDetailsBuilder.of(game, palace, ScoresMode.HIDE_UNTIL_FOCUSED, revealed = true) is ScoreDetails.Shown)
        val live = palace.copy(state = ScoreState.IN, isFinal = false, clockText = "67'")
        assertEquals(ScoreDetails.Hidden(live = true), ScoreDetailsBuilder.of(game, live, ScoresMode.HIDE_UNTIL_FOCUSED, revealed = false))
        assertNull(ScoreDetailsBuilder.of(game, palace, ScoresMode.OFF, revealed = true))
        assertNull(ScoreDetailsBuilder.of(game, palace, null, revealed = true))
    }

    @Test
    fun `unmatched, upcoming and postponed games show no score`() {
        // Another game's event on this card: the sides do not sit on it, so nothing.
        assertNull(ScoreDetailsBuilder.of(item("Celtics vs Knicks"), pacers, ScoresMode.SHOW, revealed = true))
        assertNull(ScoreDetailsBuilder.of(item("Pacers @ 76ers"), null, ScoresMode.SHOW, revealed = true))
        val pre = pacers.copy(state = ScoreState.PRE, isFinal = false, clockText = "7:00 PM")
        assertNull(ScoreDetailsBuilder.of(item("Pacers @ 76ers"), pre, ScoresMode.SHOW, revealed = true))
        val off = pacers.copy(state = ScoreState.OFF, isFinal = false, clockText = "Postponed")
        assertEquals(ScoreDetails.Status("Postponed"), ScoreDetailsBuilder.of(item("Pacers @ 76ers"), off, ScoresMode.SHOW, revealed = true))
    }
}
