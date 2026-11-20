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
import java.time.Instant

/** Trimmed real ESPN team pages (2026-11-18) under test resources `sportslive/scores/`. */
internal object TeamFixtures {
    const val NBA_TEAM = "espn_team_nba_phi.json"
    const val NBA_SCHEDULE = "espn_team_nba_phi_schedule.json"
    const val NBA_NEXT_SUMMARY = "espn_summary_nba_next_leaders.json"
    const val ENG1_TEAM = "espn_team_eng1_ars.json"
    const val ENG1_SCHEDULE = "espn_team_eng1_ars_schedule.json"
    const val ENG1_FIXTURES = "espn_team_eng1_ars_fixtures.json"

    /** When the fixtures were taken: PHI's next game is the evening of 2026-11-18 (ET). */
    val NOW: Long = Instant.parse("2026-11-18T11:00:00Z").toEpochMilli()

    fun team(file: String): EspnTeamInfoDto = EspnFixtures.moshi.adapter(EspnTeamResponseDto::class.java).fromJson(EspnFixtures.json(file))!!.team!!

    fun schedule(file: String): EspnTeamScheduleDto = EspnFixtures.moshi.adapter(EspnTeamScheduleDto::class.java).fromJson(EspnFixtures.json(file))!!

    fun summary(file: String): EspnSummaryDto = EspnFixtures.moshi.adapter(EspnSummaryDto::class.java).fromJson(EspnFixtures.json(file))!!

    fun replay(name: String, start: String, kind: SportsLiveKind = SportsLiveKind.REPLAY, isLive: Boolean = false) = SportsLiveItem(
        meta = MetaPreview(
            id = "$name@$start", type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = listOf("Basketball")
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Sports",
        kind = kind, isLive = isLive, isChannel = false, startMs = Instant.parse(start).toEpochMilli(), sport = "Basketball",
        startHasTimeOfDay = true, teams = SportsLiveTeams.parse(name)
    )
}

class TeamPagesTest {

    private val nbaEvents = TeamPages.snapshots(TeamFixtures.schedule(TeamFixtures.NBA_SCHEDULE).events!!, EspnLeagueMap.NBA)
    private val eng1 = EspnLeague("soccer", "eng.1")

    // --- DTO parsing on real fixtures ---

    @Test
    fun `the NBA team endpoint gives the header - record, standing, streak, home record and logo`() {
        val header = TeamPages.header(TeamFixtures.team(TeamFixtures.NBA_TEAM), null)!!
        assertEquals("Philadelphia 76ers", header.name)
        assertEquals("PHI", header.abbreviation)
        assertEquals("12-3", header.record)
        assertEquals("1st in Atlantic Division", header.standing)
        assertEquals(3, header.streak)
        assertEquals("6-2", header.homeRecord)
        assertEquals("https://a.espncdn.com/i/teamlogos/nba/500/phi.png", header.logo)
    }

    @Test
    fun `without the team endpoint the schedule's team line still names the team and its record`() {
        val header = TeamPages.header(null, TeamFixtures.schedule(TeamFixtures.NBA_SCHEDULE).team)!!
        assertEquals("Philadelphia 76ers", header.name)
        assertEquals("12-3", header.record)
        assertEquals("1st in Atlantic Division", header.standing)
        assertNull(header.streak)
        assertNull(TeamPages.header(null, null))
    }

    @Test
    fun `the schedule's object scores are read, and the last five finals come newest first`() {
        val recent = TeamPages.recent(nbaEvents)
        assertEquals(listOf("401909997", "401909985", "401909976", "401909961", "401909945"), recent.map { it.eventId })
        val brooklyn = recent.first()
        assertEquals(EspnLeagueMap.NBA, brooklyn.league)
        assertEquals("NBA", brooklyn.leagueName)
        assertEquals(135, brooklyn.awayScore)
        assertEquals(123, brooklyn.homeScore)
        assertEquals(TeamResult(TeamOutcome.WIN, 135, 123), TeamPages.resultOf(brooklyn, "20"))
        assertEquals(TeamResult(TeamOutcome.LOSS, 101, 123), TeamPages.resultOf(recent[3], "20"))
        assertFalse(TeamPages.isHome(brooklyn, "20"))
        assertEquals("BKN", TeamPages.opponentOf(brooklyn, "20").abbreviation)
    }

    @Test
    fun `upcoming games come by start, without a score, a live game first`() {
        val upcoming = TeamPages.upcoming(nbaEvents, TeamFixtures.NOW)
        assertEquals(listOf("401910010", "401910023", "401910037", "401909317"), upcoming.map { it.eventId })
        assertTrue(upcoming.all { it.state == ScoreState.PRE && it.homeScore == null && it.awayScore == null })

        val live = nbaEvents.map { if (it.eventId == "401910023") it.copy(state = ScoreState.IN) else it }
        assertEquals("401910023", TeamPages.upcoming(live, TeamFixtures.NOW).first().eventId)
        // A scheduled game ESPN never moved on, 6 h past its start, is left out.
        val later = Instant.parse("2026-11-19T07:00:00Z").toEpochMilli()
        assertEquals("401910023", TeamPages.upcoming(nbaEvents, later).first().eventId)
    }

    @Test
    fun `soccer - played games from the schedule, fixtures from the fixture list, on the club's own league`() {
        val played = TeamPages.snapshots(TeamFixtures.schedule(TeamFixtures.ENG1_SCHEDULE).events!!, eng1)
        val fixtures = TeamPages.snapshots(TeamFixtures.schedule(TeamFixtures.ENG1_FIXTURES).events!!, eng1)
        val recent = TeamPages.recent(played)
        assertEquals(listOf("401878760", "401879246", "401878768", "401878769", "401879268"), recent.map { it.eventId })
        assertEquals("eng.1", recent.first().leagueSlug)
        assertEquals("Premier League", recent.first().leagueName)
        assertEquals(TeamResult(TeamOutcome.WIN, 1, 0), TeamPages.resultOf(recent.first(), "359"))
        assertEquals("401879232", TeamPages.upcoming(played + fixtures, TeamFixtures.NOW).first().eventId)

        val header = TeamPages.header(TeamFixtures.team(TeamFixtures.ENG1_TEAM), null)!!
        assertEquals("Arsenal", header.name)
        assertEquals("1st in English Premier League", header.standing)
        assertEquals("9-0-1", header.record)
    }

    @Test
    fun `scores are read loosely - object, string or number - and anything else is no score`() {
        assertEquals(131, TeamPages.scoreOf(mapOf("value" to 131.0, "displayValue" to "131")))
        assertEquals(3, TeamPages.scoreOf(mapOf("displayValue" to "3")))
        assertEquals(2, TeamPages.scoreOf("2"))
        assertEquals(7, TeamPages.scoreOf(7.0))
        assertNull(TeamPages.scoreOf(null))
        assertNull(TeamPages.scoreOf(listOf(1)))
        assertNull(TeamPages.scoreOf("—"))
    }

    @Test
    fun `an event missing a side or its id is skipped, never guessed`() {
        val event = TeamFixtures.schedule(TeamFixtures.NBA_SCHEDULE).events!!.first()
        val competition = event.competitions!!.first()
        assertNull(TeamPages.snapshot(event.copy(id = null), EspnLeagueMap.NBA))
        assertNull(TeamPages.snapshot(event.copy(competitions = listOf(competition.copy(competitors = competition.competitors!!.take(1)))), EspnLeagueMap.NBA))
        assertNull(TeamPages.snapshot(EspnScheduleEventDto(), EspnLeagueMap.NBA))
    }

    @Test
    fun `season leaders come from the next game's summary, for the team's id only, three at most`() {
        val leaders = TeamPages.leaders(TeamFixtures.summary(TeamFixtures.NBA_NEXT_SUMMARY), "20")
        assertEquals(
            listOf(
                TeamLeader("Points", "T. Maxey", "G", "26.5", athleteId = "4431678"),
                TeamLeader("Assists", "L. James", "F", "6.9", athleteId = "1966"),
                TeamLeader("Rebounds", "J. Embiid", "C", "8.5", athleteId = "3059318")
            ),
            leaders
        )
        assertTrue(TeamPages.leaders(TeamFixtures.summary(TeamFixtures.NBA_NEXT_SUMMARY), "999").isEmpty())
        assertTrue(TeamPages.leaders(null, "20").isEmpty())
    }

    @Test
    fun `leaders are asked for only from a game not started yet, and never for soccer`() {
        val upcoming = TeamPages.upcoming(nbaEvents, TeamFixtures.NOW)
        assertEquals("401910010", TeamPages.leadersEvent(upcoming, EspnLeagueMap.NBA)?.eventId)
        assertNull(TeamPages.leadersEvent(upcoming.map { it.copy(state = ScoreState.IN) }, EspnLeagueMap.NBA))
        assertNull(TeamPages.leadersEvent(upcoming, eng1))
    }

    @Test
    fun `the disk keeps the latest finals only, ten at most`() {
        val events = TeamFixtures.schedule(TeamFixtures.NBA_SCHEDULE).events!!
        val kept = TeamPages.finalsToKeep(events, EspnLeagueMap.NBA)
        assertEquals(7, kept.size)
        assertTrue(TeamPages.snapshots(kept, EspnLeagueMap.NBA).all { it.state == ScoreState.POST })
    }

    // --- Replay pill: strict matches only ---

    @Test
    fun `a replay naming both teams on the game's day gets the Replay pill`() {
        val recent = TeamPages.recent(nbaEvents)
        val replay = TeamFixtures.replay("Indiana Pacers vs Philadelphia 76ers", "2026-11-15T18:30:00Z")
        assertEquals(mapOf("401909985" to replay), TeamPages.replaysFor(recent, listOf(replay), TeamFixtures.NOW))
    }

    @Test
    fun `a replay on another day, of another opponent, or naming one team only gets none`() {
        val recent = TeamPages.recent(nbaEvents)
        val replays = listOf(
            TeamFixtures.replay("Indiana Pacers vs Philadelphia 76ers", "2026-11-20T18:30:00Z"),
            TeamFixtures.replay("Indiana Pacers vs Boston Celtics", "2026-11-15T18:30:00Z"),
            TeamFixtures.replay("Philadelphia 76ers highlights", "2026-11-15T18:30:00Z")
        )
        assertTrue(TeamPages.replaysFor(recent, replays, TeamFixtures.NOW).isEmpty())
    }

    // --- Entry visibility ---

    private val nbaGame = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA).first { it.eventId == "401909985" }

    @Test
    fun `NBA, NFL, NHL and MLB games offer both teams' pages, by ESPN's team id`() {
        val home = TeamPages.refOf(nbaGame, ScoreSide.HOME)!!
        assertEquals(EspnLeagueMap.NBA, home.league)
        assertEquals(nbaGame.home.id, home.teamId)
        assertEquals("Philadelphia 76ers", home.name)
        assertEquals(listOf(ScoreSide.AWAY, ScoreSide.HOME), TeamPages.refsOf(nbaGame, homeFirst = false).map { it.side })
        assertEquals(listOf(ScoreSide.HOME, ScoreSide.AWAY), TeamPages.refsOf(nbaGame, homeFirst = true).map { it.side })
        for (league in listOf(EspnLeagueMap.NFL, EspnLeagueMap.NHL, EspnLeagueMap.MLB)) {
            assertEquals(league, TeamPages.refOf(nbaGame.copy(league = league, leagueSlug = league.league), ScoreSide.AWAY)?.league)
        }
    }

    @Test
    fun `a side without ESPN's team id has no page`() {
        val noId = nbaGame.copy(home = nbaGame.home.copy(id = null), away = nbaGame.away.copy(id = " "))
        assertNull(TeamPages.refOf(noId, ScoreSide.HOME))
        assertTrue(TeamPages.refsOf(noId, homeFirst = false).isEmpty())
    }

    @Test
    fun `college basketball and football and the WNBA have team pages (BUG12)`() {
        val college = EspnFixtures.events(EspnFixtures.CBB_20261115, EspnLeagueMap.COLLEGE_BASKETBALL).first()
        assertEquals(listOf(EspnLeagueMap.COLLEGE_BASKETBALL), TeamPages.refsOf(college, homeFirst = false).map { it.league }.distinct())
        assertEquals(2, TeamPages.refsOf(college, homeFirst = false).size)
        for (league in listOf(EspnLeagueMap.COLLEGE_FOOTBALL, EspnLeagueMap.COLLEGE_BASKETBALL_WOMEN, EspnLeagueMap.WNBA)) {
            assertEquals(league, TeamPages.leagueOf(nbaGame.copy(league = league, leagueSlug = league.league)))
        }
    }

    @Test
    fun `women's soccer, cups, other leagues and individual sports have no team page`() {
        assertNull(TeamPages.leagueOf(nbaGame.copy(league = EspnLeagueMap.EUROLEAGUE, leagueSlug = "euroleague")))
        assertNull(TeamPages.leagueOf(nbaGame.copy(league = EspnLeagueMap.NWSL, leagueSlug = "usa.nwsl")))
        val soccerAll = nbaGame.copy(league = EspnLeagueMap.SOCCER_ALL)
        for (slug in listOf("uefa.champions", "eng.w.1", "fifa.friendly", "eng.2")) {
            assertNull(slug, TeamPages.leagueOf(soccerAll.copy(leagueSlug = slug)))
        }
        val race = nbaGame.copy(league = EspnLeagueMap.F1, leagueSlug = "f1", individual = RaceResult("Grand Prix", emptyList()))
        assertNull(TeamPages.leagueOf(race))
        assertNull(TeamPages.leagueOf(race.copy(league = EspnLeagueMap.NBA, leagueSlug = "nba")))
    }

    @Test
    fun `a major soccer league's game offers its clubs' pages on that league, from its own feed or soccer all`() {
        val palace = EspnFixtures.events(EspnFixtures.ENG1_20261108, EspnFixtures.ENG1).first { it.eventId == "401879243" }
        assertEquals(eng1, TeamPages.refOf(palace, ScoreSide.HOME)?.league)
        val viaAll = palace.copy(league = EspnLeagueMap.SOCCER_ALL, leagueSlug = "esp.1")
        assertEquals(EspnLeague("soccer", "esp.1"), TeamPages.refOf(viaAll, ScoreSide.AWAY)?.league)
        assertNotNull(TeamPages.refOf(palace.copy(leagueSlug = "usa.1", league = EspnLeagueMap.SOCCER_ALL), ScoreSide.HOME))
    }

    // --- Spoilers (decision 5) ---

    @Test
    fun `Show shows everything - Hide until focused shows the record but holds the results for Reveal`() {
        assertTrue(TeamPageSpoilers.mayLoad(ScoresMode.SHOW, false))
        assertTrue(TeamPageSpoilers.showsRecord(ScoresMode.SHOW, false))
        assertTrue(TeamPageSpoilers.showsResults(ScoresMode.SHOW, false))

        assertTrue(TeamPageSpoilers.mayLoad(ScoresMode.HIDE_UNTIL_FOCUSED, false))
        assertTrue(TeamPageSpoilers.showsRecord(ScoresMode.HIDE_UNTIL_FOCUSED, false))
        assertFalse(TeamPageSpoilers.showsResults(ScoresMode.HIDE_UNTIL_FOCUSED, false))
        assertTrue(TeamPageSpoilers.showsResults(ScoresMode.HIDE_UNTIL_FOCUSED, true))
    }

    @Test
    fun `a last result shows while its row has focus under Hide until focused only`() {
        assertFalse(TeamPageSpoilers.showsResult(ScoresMode.HIDE_UNTIL_FOCUSED, revealed = false, focused = false))
        assertTrue(TeamPageSpoilers.showsResult(ScoresMode.HIDE_UNTIL_FOCUSED, revealed = false, focused = true))
        assertFalse(TeamPageSpoilers.showsResult(ScoresMode.OFF, revealed = false, focused = true))
        assertTrue(TeamPageSpoilers.showsResult(ScoresMode.OFF, revealed = true, focused = false))
        assertTrue(TeamPageSpoilers.showsResult(ScoresMode.SHOW, revealed = false, focused = false))
    }

    @Test
    fun `Off loads and shows nothing until Reveal`() {
        assertFalse(TeamPageSpoilers.mayLoad(ScoresMode.OFF, false))
        assertFalse(TeamPageSpoilers.showsRecord(ScoresMode.OFF, false))
        assertFalse(TeamPageSpoilers.showsResults(ScoresMode.OFF, false))
        assertTrue(TeamPageSpoilers.mayLoad(ScoresMode.OFF, true))
        assertTrue(TeamPageSpoilers.showsRecord(ScoresMode.OFF, true))
        assertTrue(TeamPageSpoilers.showsResults(ScoresMode.OFF, true))
    }
}
