package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameSituationsTest {

    private val strings = GameSituationStrings(
        separator = " · ",
        possession = "%1\$s ball",
        redZone = "Red zone",
        inning = "%1\$s %2\$d",
        outs = "%1\$d out",
        runnerOn = "Runner on %1\$s",
        twoRunners = "%1\$s & %2\$s",
        basesLoaded = "Bases loaded",
        first = "1st",
        second = "2nd",
        third = "3rd"
    )

    private val nfl = EspnFixtures.events(EspnFixtures.NFL_LIVE_SITUATION_SAMPLE, EspnLeagueMap.NFL)
    // SEA @ LV, 3rd quarter: Seattle has the ball, 2nd & 6 at the LV 34.
    private val seahawks = nfl.first { it.eventId == "401873101" }
    // NE @ BUF, 4th quarter: Buffalo, 1st & Goal at the NE 8 (no short text), red zone.
    private val bills = nfl.first { it.eventId == "401873102" }
    private val mlb = EspnFixtures.events(EspnFixtures.MLB_LIVE_SITUATION_SAMPLE, EspnLeagueMap.MLB)
    // BOS @ NYY, Bot 7th: 1-2 count, 2 out, runners on 1st and 3rd.
    private val yankees = mlb.first { it.eventId == "401809101" }
    // TOR @ TB, Top 3rd: 0 out, a runner on 2nd given as an object.
    private val rays = mlb.first { it.eventId == "401809102" }

    private fun item(name: String, sport: String = "American Football") = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
        kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false,
        startMs = null, sport = sport, teams = SportsLiveTeams.parse(name)
    )

    private fun line(event: ScoreSnapshot) = GameSituations.situationLine(event.situation, event, strings)

    @Test
    fun `parses a live football situation`() {
        val s = seahawks.situation!!
        assertEquals(ScoreSide.AWAY, s.possession)
        assertEquals("2nd & 6", s.downDistance)
        assertEquals("LV 34", s.yardLine)
        assertEquals(false, s.redZone)
        // ESPN's long play text is cut at a word to 80 characters.
        val lastPlay = s.lastPlay!!
        assertTrue(lastPlay, lastPlay.length <= 80 && lastPlay.endsWith("…"))
        assertTrue(lastPlay.startsWith("(Shotgun) S.Darnold pass short right"))

        val red = bills.situation!!
        assertEquals(ScoreSide.HOME, red.possession)
        assertEquals("1st & Goal at NE 8", red.downDistance)
        assertEquals(true, red.redZone)
    }

    @Test
    fun `parses a live baseball situation`() {
        val s = yankees.situation!!
        assertEquals(1, s.balls)
        assertEquals(2, s.strikes)
        assertEquals(2, s.outs)
        assertEquals(Triple(true, false, true), Triple(s.onFirst, s.onSecond, s.onThird))
        assertEquals("J. Chisholm Jr.", s.batter)
        assertEquals("G. Whitlock", s.pitcher)
        assertEquals("Volpe singled to left, Judge to third.", s.lastPlay)
        // A runner given as an object (not a boolean) still counts.
        assertEquals(Triple(false, true, false), rays.situation!!.let { Triple(it.onFirst, it.onSecond, it.onThird) })
    }

    @Test
    fun `parses a real live college basketball situation, last play only`() {
        val game = EspnFixtures.events(EspnFixtures.CBB_LIVE_20261117, EspnLeagueMap.COLLEGE_BASKETBALL)
            .first { it.eventId == "401920511" }
        assertEquals(GameSituation(lastPlay = "Rutgers Offensive Rebound."), game.situation)
        assertEquals("", line(game))
    }

    @Test
    fun `football line names the team with the ball, down and distance and the spot`() {
        assertEquals("SEA ball · 2nd & 6 · LV 34", line(seahawks))
        // The long form already names the spot; red zone is added.
        assertEquals("BUF ball · 1st & Goal at NE 8 · Red zone", line(bills))
        // An unknown possession id: no team, the rest stays.
        assertEquals("2nd & 6 · LV 34", line(seahawks.copy(situation = seahawks.situation!!.copy(possession = null))))
    }

    @Test
    fun `baseball line gives the inning, outs and runners`() {
        assertEquals("Bot 7 · 2 out · 1st & 3rd", line(yankees))
        assertEquals("Top 3 · 0 out · Runner on 2nd", line(rays))
        val loaded = yankees.situation!!.copy(onSecond = true)
        assertEquals("Bot 7 · 2 out · Bases loaded", GameSituations.situationLine(loaded, yankees, strings))
        val empty = yankees.situation!!.copy(onFirst = false, onThird = false, outs = 1)
        assertEquals("Bot 7 · 1 out", GameSituations.situationLine(empty, yankees, strings))
    }

    @Test
    fun `empty when there is no situation`() {
        val nba = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA)
        assertTrue(nba.all { it.situation == null })
        assertEquals("", line(nba.first()))
        assertNull(EspnScoreProvider.situation(null, "1", "2"))
        assertNull(EspnScoreProvider.situation(EspnSituationDto(lastPlay = EspnLastPlayDto("  ")), "1", "2"))
        // Soccer and hockey: the line has nothing to say even if a last play came through.
        val soccer = seahawks.copy(league = EspnFixtures.ENG1)
        assertEquals("", line(soccer))
    }

    @Test
    fun `only a game in play has a situation`() {
        val json = EspnFixtures.json(EspnFixtures.NFL_LIVE_SITUATION_SAMPLE)
            .replace("\"state\": \"in\", \"completed\": false", "\"state\": \"post\", \"completed\": true")
        val dto = EspnFixtures.moshi.adapter(EspnScoreboardDto::class.java).fromJson(json)!!
        val finals = EspnScoreProvider.toSnapshots(dto, EspnLeagueMap.NFL)
        assertEquals(2, finals.size)
        assertTrue(finals.all { it.state == ScoreState.POST && it.situation == null })
    }

    @Test
    fun `situation follows the score's spoiler rule`() {
        val card = item("Seahawks at Raiders")
        val shown = GameSituations.of(card, seahawks, ScoresMode.SHOW, revealed = false)
        assertEquals(seahawks.situation, shown)
        // Hide until focused: hidden whenever the score is hidden, back once revealed.
        assertNull(GameSituations.of(card, seahawks, ScoresMode.HIDE_UNTIL_FOCUSED, revealed = false))
        assertNotNull(GameSituations.of(card, seahawks, ScoresMode.HIDE_UNTIL_FOCUSED, revealed = true))
        // Scores Off or not loaded: nothing.
        assertNull(GameSituations.of(card, seahawks, ScoresMode.OFF, revealed = true))
        assertNull(GameSituations.of(card, seahawks, null, revealed = true))
    }

    @Test
    fun `never for an unmatched, final or postponed game`() {
        // Another game's event on this card: its sides do not sit on it.
        assertNull(GameSituations.of(item("Bills vs Jets"), seahawks, ScoresMode.SHOW, revealed = true))
        assertNull(GameSituations.of(item("Seahawks at Raiders"), null, ScoresMode.SHOW, revealed = true))
        val final = seahawks.copy(state = ScoreState.POST, isFinal = true, clockText = "Final")
        assertNull(GameSituations.of(item("Seahawks at Raiders"), final, ScoresMode.SHOW, revealed = true))
        val off = seahawks.copy(state = ScoreState.OFF, isFinal = false, clockText = "Delayed")
        assertNull(GameSituations.of(item("Seahawks at Raiders"), off, ScoresMode.SHOW, revealed = true))
    }

    @Test
    fun `the score delay holds the situation back with the score`() {
        val delay = ScoreDelay()
        val key = "card"
        val delayMs = 30_000L
        // First seen live: no score and no situation until the delay has passed.
        val first = delay.apply(mapOf(key to seahawks), delayMs, now = 0L).getValue(key)
        assertNull(first.situation)
        assertNull(first.homeScore)
        // The next down arrives at 10 s; at 30 s the first version shows, the new down only at 40 s.
        val next = seahawks.copy(situation = seahawks.situation!!.copy(downDistance = "3rd & 2", yardLine = "LV 30"))
        delay.apply(mapOf(key to next), delayMs, now = 10_000L)
        assertEquals("2nd & 6", delay.apply(mapOf(key to next), delayMs, now = 30_000L).getValue(key).situation?.downDistance)
        assertEquals("3rd & 2", delay.apply(mapOf(key to next), delayMs, now = 40_000L).getValue(key).situation?.downDistance)
        // Delay Off: the newest situation at once.
        assertEquals(next.situation, ScoreDelay().apply(mapOf(key to next), 0L, now = 0L).getValue(key).situation)
    }

    @Test
    fun `short text cuts long plays at a word`() {
        assertEquals("Rebound.", EspnScoreProvider.shortText("  Rebound.\n "))
        assertNull(EspnScoreProvider.shortText("   "))
        val cut = EspnScoreProvider.shortText("word ".repeat(40))!!
        assertTrue(cut, cut.length <= 80 && cut.endsWith("word…"))
    }
}
