package com.nuvio.tv.core.sportslive.scores

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Player card (#63) parsing on trimmed real ESPN athlete and game log responses (captured 2026-11-18). */
class PlayerCardsTest {

    private val athleteAdapter = EspnFixtures.moshi.adapter(EspnAthleteResponseDto::class.java)
    private val logAdapter = EspnFixtures.moshi.adapter(EspnGameLogDto::class.java)

    private fun athlete(file: String) = athleteAdapter.fromJson(EspnFixtures.json("espn_athlete_$file.json"))!!
    private fun log(file: String) = logAdapter.fromJson(EspnFixtures.json("espn_gamelog_$file.json"))!!
    private fun card(file: String, sport: String) = PlayerCards.parse(athlete(file), log(file), sport)!!

    private fun line(game: PlayerGame) = game.line.map { "${it.value} ${it.label}" }

    @Test
    fun `NBA - bio, season stats and the last 5 games newest first`() {
        val card = card("nba_3059318", "basketball")
        assertEquals(
            PlayerBio(
                name = "Joel Embiid",
                team = "Philadelphia 76ers",
                teamLogo = "https://a.espncdn.com/i/teamlogos/nba/500/phi.png",
                position = "Center",
                jersey = "21",
                headshot = "https://a.espncdn.com/i/headshots/nba/players/full/3059318.png",
                details = listOf("7' 0\"", "270 lbs", "11th Season")
            ),
            card.bio
        )
        assertEquals("2026-27 regular season stats", card.seasonTitle)
        assertEquals(
            listOf(PlayerStat("PTS", "19.5"), PlayerStat("REB", "8.5"), PlayerStat("AST", "3.5"), PlayerStat("FG%", "40.0")),
            card.season
        )
        // Seven games in the log: the newest five.
        assertEquals(5, card.lastGames.size)
        val last = card.lastGames.first()
        assertEquals("401909997", last.eventId)
        assertEquals("@", last.atVs)
        assertEquals("BKN", last.opponent)
        assertEquals("W", last.result)
        assertEquals("135–123", last.score)
        assertEquals(listOf("37 PTS", "5 REB", "1 AST"), line(last))
        val starts = card.lastGames.map { it.startMs!! }
        assertEquals(starts.sortedDescending(), starts)
    }

    @Test
    fun `the last games are ordered by date, whatever the order ESPN lists them in`() {
        val log = log("nba_3059318")
        val shuffled = log.copy(events = log.events!!.entries.reversed().associate { it.key to it.value })
        val games = PlayerCards.lastGames(shuffled, "basketball")
        assertEquals("401909997", games.first().eventId)
        assertEquals(PlayerCards.lastGames(log, "basketball"), games)
    }

    @Test
    fun `NFL quarterback - passing line`() {
        val card = card("nfl_3139477", "football")
        assertEquals("Quarterback", card.bio.position)
        assertEquals(listOf("YDS", "TD", "INT", "QBR"), card.season.map { it.label })
        val last = card.lastGames.first()
        assertEquals("ATL", last.opponent)
        assertEquals("35–13", last.score)
        assertEquals(listOf("348 YDS", "5 TD", "0 INT"), line(last))
    }

    @Test
    fun `NFL receiver - receiving line`() {
        val game = PlayerCards.lastGames(log("nfl_4241389"), "football").first()
        assertEquals(listOf("7 REC", "72 YDS", "1 TD"), line(game))
        assertEquals("L", game.result)
    }

    @Test
    fun `NHL skater and goalie lines`() {
        val card = card("nhl_3895074", "hockey")
        assertEquals("97", card.bio.jersey)
        assertEquals(listOf("13", "42", "55", "29"), card.season.map { it.value })
        assertEquals(listOf("1 G", "1 A", "0 +/-"), line(card.lastGames.first()))

        val goalie = PlayerCards.lastGames(log("nhl_3042109"), "hockey").first()
        assertEquals(listOf("17 SV", "1 GA", ".944 SV%"), line(goalie))
    }

    @Test
    fun `MLB batter and pitcher lines, extra innings kept in the score`() {
        val card = card("mlb_33192", "baseball")
        assertEquals("Right Fielder", card.bio.position)
        assertEquals(listOf(PlayerStat("AVG", ".241"), PlayerStat("HR", "18"), PlayerStat("RBI", "41"), PlayerStat("OPS", ".871")), card.season)
        val last = card.lastGames.first()
        assertEquals("L", last.result)
        // A loss: the player's team's score first, as on the Team page (QA pass 3).
        assertEquals("4–5 F/13", last.score)
        assertEquals(listOf("0 H", "0 HR", "0 RBI"), line(last))

        val pitcher = PlayerCards.lastGames(log("mlb_32081"), "baseball").first()
        assertEquals(listOf("5.0 IP", "5 K", "1 ER"), line(pitcher))
    }

    @Test
    fun `soccer - no headshot or experience, results as ESPN gives them`() {
        val card = card("eng1_219362", "soccer")
        assertEquals("Daichi Kamada", card.bio.name)
        assertNull(card.bio.headshot)
        assertEquals(listOf("5' 11\"", "159 lbs"), card.bio.details)
        assertEquals("2026-27 Premier League Stats", card.seasonTitle)
        val last = card.lastGames.first()
        assertEquals("vs", last.atVs)
        assertEquals("LIV", last.opponent)
        assertEquals(listOf("0 G", "0 A", "2 SHOT"), line(last))
        assertEquals(listOf("L", "L", "L", "L", "W"), card.lastGames.map { it.result })
    }

    @Test
    fun `scores read own team first, as on the Team page`() {
        assertEquals("93–105", PlayerCards.ownScoreFirst("L", "105-93"))
        assertEquals("105–93", PlayerCards.ownScoreFirst("W", "105-93"))
        assertEquals("105–93", PlayerCards.ownScoreFirst("W", "93-105"))
        assertEquals("1–1", PlayerCards.ownScoreFirst("D", "1-1"))
        assertEquals("4–5 F/13", PlayerCards.ownScoreFirst("L", "5-4 F/13"))
        assertEquals("2–3", PlayerCards.ownScoreFirst(null, "2-3"))
        assertEquals("Postponed", PlayerCards.ownScoreFirst("L", "Postponed"))
    }

    @Test
    fun `tolerant - no athlete is no card, no game log is no last games, unknown columns fall back`() {
        assertNull(PlayerCards.parse(athleteAdapter.fromJson("{}")!!, null, "basketball"))
        assertNull(PlayerCards.parse(athleteAdapter.fromJson("""{"athlete":{"id":"1"}}""")!!, null, "basketball"))

        val bare = PlayerCards.parse(athleteAdapter.fromJson("""{"athlete":{"displayName":"A Player","jersey":"#7"}}""")!!, null, "basketball")!!
        assertEquals("7", bare.bio.jersey)
        assertTrue(bare.season.isEmpty())
        assertTrue(bare.lastGames.isEmpty())
        assertTrue(PlayerCards.parse(athlete("nba_3059318"), logAdapter.fromJson("{}"), "basketball")!!.lastGames.isEmpty())

        // A sport without a line: the first three columns.
        assertEquals(listOf(0, 1, 2), PlayerCards.lineColumns(listOf("a", "b", "c", "d"), "cricket"))
    }

    @Test
    fun `entry - pro leagues only, and only with an ESPN athlete id`() {
        assertTrue(PlayerCards.supports(EspnLeagueMap.NBA))
        assertTrue(PlayerCards.supports(EspnLeagueMap.NFL))
        assertTrue(PlayerCards.supports(EspnLeagueMap.NHL))
        assertTrue(PlayerCards.supports(EspnLeagueMap.MLB))
        assertTrue(PlayerCards.supports(EspnFixtures.ENG1))
        assertTrue(PlayerCards.supports(EspnLeagueMap.SOCCER_ALL, "eng.1"))
        assertFalse(PlayerCards.supports(EspnLeagueMap.COLLEGE_BASKETBALL))
        assertFalse(PlayerCards.supports(EspnLeagueMap.COLLEGE_FOOTBALL))
        assertFalse(PlayerCards.supports(EspnLeagueMap.SOCCER_ALL, "usa.ncaa.m.1"))
        assertFalse(PlayerCards.supports(EspnLeagueMap.ATP))
        assertFalse(PlayerCards.supports(EspnLeagueMap.UFC))

        // The summary's leaders keep their ESPN athlete ids.
        val summary = GameSummaryFixtures.summary(GameSummaryFixtures.NBA, EspnLeagueMap.NBA, "401909994")
        val tatum = summary.performers.first { it.player == "J. Tatum" }
        assertEquals("4065648", tatum.athleteId)
        assertEquals(
            PlayerCardTarget(EspnLeagueMap.NBA, "4065648", "J. Tatum", "Boston Celtics"),
            PlayerCards.targetFor(tatum, EspnLeagueMap.NBA, "nba", "Boston Celtics")
        )
        assertNull(PlayerCards.targetFor(tatum.copy(athleteId = null), EspnLeagueMap.NBA, "nba", null))
        assertNull(PlayerCards.targetFor(tatum.copy(athleteId = " "), EspnLeagueMap.NBA, "nba", null))
        assertNull(PlayerCards.targetFor(tatum, EspnLeagueMap.COLLEGE_BASKETBALL, null, null))
    }

    @Test
    fun `entry - a Team page leader opens a card on a pro league, with the team's name`() {
        val leaders = TeamPages.leaders(TeamFixtures.summary(TeamFixtures.NBA_NEXT_SUMMARY), "20")
        val event = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA).first()
        val sixers = TeamRef(EspnLeagueMap.NBA, "20", event, ScoreSide.HOME)
        val embiid = leaders.first { it.player == "J. Embiid" }
        assertEquals(
            PlayerCardTarget(EspnLeagueMap.NBA, "3059318", "J. Embiid", sixers.name),
            PlayerCards.targetFor(embiid, sixers)
        )
        assertNull(PlayerCards.targetFor(embiid.copy(athleteId = null), sixers))
        assertNull(PlayerCards.targetFor(embiid, sixers.copy(league = EspnLeagueMap.COLLEGE_BASKETBALL)))
    }
}
