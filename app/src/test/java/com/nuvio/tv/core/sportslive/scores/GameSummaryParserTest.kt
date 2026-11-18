package com.nuvio.tv.core.sportslive.scores

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ESPN game summaries (trimmed real responses) read into the Game page's model. */
class GameSummaryParserTest {

    private fun summary(file: String, league: EspnLeague, id: String): GameSummary =
        GameSummaryParser.parse(GameSummaryFixtures.dto(file), league, id)!!

    @Test
    fun `NBA final - header, linescore, team stats, performers, box score and standings`() {
        // ORL 115 @ BOS 105, Final, TD Garden.
        val game = summary(GameSummaryFixtures.NBA, EspnLeagueMap.NBA, "401909994")
        assertEquals(ScoreState.POST, game.state)
        assertTrue(game.isFinal)
        assertEquals("Final", game.clockText)
        assertEquals("TD Garden", game.venue)
        assertEquals("ORL", game.away.abbreviation)
        assertEquals("Orlando Magic", game.away.fullName)
        assertEquals(115, game.away.score)
        assertEquals(listOf(20, 30, 25, 40), game.away.linescores)
        assertEquals("6-7", game.away.record)
        assertEquals("BOS", game.home.abbreviation)
        assertEquals(105, game.home.score)
        assertEquals("11-3", game.home.record)
        assertTrue(game.home.logo!!.endsWith("/bos.png"))

        assertEquals(
            listOf(GameStat.FIELD_GOAL_PCT, GameStat.THREE_POINT_PCT, GameStat.REBOUNDS, GameStat.ASSISTS, GameStat.TURNOVERS),
            game.teamStats.map { it.stat }
        )
        val fg = game.teamStats.first { it.stat == GameStat.FIELD_GOAL_PCT }
        assertEquals("50", fg.away)
        assertEquals("42", fg.home)
        assertEquals(ScoreSide.AWAY, fg.better)
        // Fewer turnovers is better: BOS 11 beats ORL 13.
        assertEquals(ScoreSide.HOME, game.teamStats.first { it.stat == GameStat.TURNOVERS }.better)

        // 2×2: away 1, home 1, away 2, home 2; a player's categories are grouped.
        assertEquals(listOf("D. Bane", "J. Tatum", "F. Wagner", "P. Pritchard"), game.performers.map { it.player })
        assertEquals(listOf(ScoreSide.AWAY, ScoreSide.HOME, ScoreSide.AWAY, ScoreSide.HOME), game.performers.map { it.side })
        assertEquals(listOf(PerformerStat("24", "PTS"), PerformerStat("12", "REB")), game.performers[1].stats)

        assertEquals(listOf(ScoreSide.AWAY, ScoreSide.HOME), game.boxScore.map { it.side })
        val orl = game.boxScore.first().tables.single()
        assertNull(orl.title)
        assertEquals(listOf("MIN", "PTS", "FG", "3PT", "FT", "REB", "AST", "STL", "BLK", "TO"), orl.labels)
        assertEquals("F. Wagner", orl.rows.first().player)
        assertEquals("115", orl.totals!![1])

        assertEquals(listOf("2026-27 Southeast Standings", "2026-27 Atlantic Standings"), game.standings.map { it.title })
        assertEquals(listOf("W", "L", "PCT", "GB", "STRK"), game.standings.first().columns)
        assertEquals(listOf("Orlando"), game.standings.first().rows.filter { it.inGame }.map { it.team })
        assertEquals(listOf("Boston"), game.standings[1].rows.filter { it.inGame }.map { it.team })

        // This copy's three clips were trimmed without their links: none is playable, so no Highlights tab.
        assertTrue(game.highlights.isEmpty())
        assertEquals(listOf(GameSection.GAME, GameSection.BOX_SCORE, GameSection.STANDINGS), game.sections)
    }

    @Test
    fun `NBA final with highlights - each clip's links, HLS first then MP4, and the Highlights tab`() {
        // NY 111 @ TOR 102: ESPN's clips with their links.source (trimmed).
        val game = summary(GameSummaryFixtures.NBA_VIDEOS, EspnLeagueMap.NBA, "401910002")
        assertEquals(4, game.highlights.size)
        val recap = game.highlights.first()
        assertEquals("50516726", recap.id)
        assertEquals("New York Knicks vs. Toronto Raptors: Game Highlights", recap.title)
        assertEquals(59, recap.durationSeconds)
        assertTrue(recap.thumbnail!!.endsWith(".jpg"))
        // HLS.HD and HLS are the same playlist, HD and plain the same MP4: one of each, HLS first.
        assertEquals(2, recap.links.size)
        assertTrue(recap.links[0].startsWith("https://cmp-espn.media.dssott.com/") && recap.links[0].endsWith("/playlist.m3u8"))
        assertTrue(recap.links[1].startsWith("https://espnmedia-cdn.akamaized.net/") && recap.links[1].endsWith(".mp4"))
        assertEquals(listOf("50515367", "50515447", "50515574"), game.highlights.drop(1).map { it.id })
        // This trimmed copy has only the clips: Highlights is its one section.
        assertEquals(listOf(GameSection.HIGHLIGHTS), game.sections)
    }

    @Test
    fun `highlight links - HLS HD, HLS, HD MP4, MP4 in that order, http only, no repeats`() {
        val all = EspnVideoSourceDto(
            href = "https://cdn.example/clip.mp4",
            hd = EspnHrefDto("https://cdn.example/clip_hd.mp4"),
            hls = EspnVideoHlsDto(href = "https://cdn.example/clip.m3u8", hd = EspnHrefDto("https://cdn.example/clip_hd.m3u8"))
        )
        assertEquals(
            listOf("https://cdn.example/clip_hd.m3u8", "https://cdn.example/clip.m3u8", "https://cdn.example/clip_hd.mp4", "https://cdn.example/clip.mp4"),
            GameSummaryParser.highlightLinks(all)
        )
        // MP4 only: HD first.
        assertEquals(
            listOf("https://cdn.example/clip_hd.mp4", "https://cdn.example/clip.mp4"),
            GameSummaryParser.highlightLinks(all.copy(hls = null))
        )
        // Not a web link, or blank: left out.
        assertEquals(
            listOf("https://cdn.example/clip.mp4"),
            GameSummaryParser.highlightLinks(EspnVideoSourceDto(href = "https://cdn.example/clip.mp4", hd = EspnHrefDto("  "), hls = EspnVideoHlsDto(href = "rtmp://x/clip")))
        )
        assertTrue(GameSummaryParser.highlightLinks(null).isEmpty())
    }

    @Test
    fun `a clip ESPN lists twice shows once - the cards are keyed by clip id`() {
        val dto = GameSummaryFixtures.dto(GameSummaryFixtures.NBA_VIDEOS)
        val twice = dto.copy(videos = dto.videos!! + dto.videos!!.first().copy(headline = "Same clip again"))
        val game = GameSummaryParser.parse(twice, EspnLeagueMap.NBA, "401910002")!!
        assertEquals(listOf("50516726", "50515367", "50515447", "50515574"), game.highlights.map { it.id })
        assertEquals("New York Knicks vs. Toronto Raptors: Game Highlights", game.highlights.first().title)
    }

    @Test
    fun `a clip without a title or a playable link is not a highlight`() {
        val links = EspnVideoLinksDto(EspnVideoSourceDto(href = "https://cdn.example/clip.mp4"))
        val clip = GameSummaryParser.highlight(EspnVideoDto(id = "7", headline = " Dunk ", duration = 12.6, links = links))!!
        assertEquals(GameHighlight("7", "Dunk", null, 12, listOf("https://cdn.example/clip.mp4")), clip)
        assertNull(GameSummaryParser.highlight(EspnVideoDto(id = "7", headline = "Dunk")))
        assertNull(GameSummaryParser.highlight(EspnVideoDto(id = "7", headline = " ", links = links)))
        // No id: the link stands in for it, so a failed clip can still be hidden.
        assertEquals("https://cdn.example/clip.mp4", GameSummaryParser.highlight(EspnVideoDto(headline = "Dunk", links = links))!!.id)
    }

    @Test
    fun `NFL final - football lines, time of possession and one table per unit`() {
        // MIN 30 @ GB 10.
        val game = summary(GameSummaryFixtures.NFL, EspnLeagueMap.NFL, "401873060")
        assertEquals(30, game.away.score)
        assertEquals(10, game.home.score)
        assertEquals(listOf(0, 0, 10, 0), game.home.linescores)
        val possession = game.teamStats.first { it.stat == GameStat.POSSESSION_TIME }
        assertEquals("35:16", possession.away)
        assertEquals(35 * 60 + 16.0, possession.awayValue)
        assertEquals(ScoreSide.AWAY, possession.better)
        assertEquals(ScoreSide.AWAY, game.teamStats.first { it.stat == GameStat.TURNOVERS }.better)
        // A football leader's value is already a line: no label is added.
        val cousins = game.performers.first()
        assertEquals("K. Cousins", cousins.player)
        assertEquals(listOf(PerformerStat("21/31, 207 YDS, 2 TD, 1 INT", null)), cousins.stats)
        val minnesota = game.boxScore.first()
        assertEquals("MIN", minnesota.team)
        // The punt returns table lists nobody, so it is left out.
        assertEquals(9, minnesota.tables.size)
        assertEquals("Minnesota Passing", minnesota.tables.first().title)
        assertEquals(listOf("C/ATT", "YDS", "AVG", "TD", "INT", "SACKS", "QBR", "RTG"), minnesota.tables.first().labels)
        assertEquals(listOf("W", "L", "T", "PCT", "PF", "PA"), game.standings.single().columns)
    }

    @Test
    fun `NHL final - compact skater and goalie columns, both divisions`() {
        // MTL 1 @ NYR 3.
        val game = summary(GameSummaryFixtures.NHL, EspnLeagueMap.NHL, "401892726")
        assertEquals(listOf(0, 2, 1), game.home.linescores)
        assertEquals("12-5-4", game.away.record)
        val faceoffs = game.teamStats.first { it.stat == GameStat.FACEOFF_PCT }
        assertEquals(ScoreSide.AWAY, faceoffs.better)
        assertEquals(ScoreSide.AWAY, game.teamStats.first { it.stat == GameStat.PENALTY_MINUTES }.better)
        // Leaders without a main-stat label use the category's name.
        assertEquals(listOf(PerformerStat("1", "Goals"), PerformerStat("1", "Points")), game.performers.first().stats)
        val montreal = game.boxScore.first()
        // The empty "skaters" table is left out.
        assertEquals(listOf("Forwards", "Defenses", "Goalies"), montreal.tables.map { it.title })
        assertEquals(listOf("G", "A", "+/-", "SOG", "TOI", "PIM"), montreal.tables.first().labels)
        assertEquals(listOf("SA", "GA", "SV", "SV%", "TOI", "PIM"), montreal.tables.last().labels)
        assertEquals(listOf("W", "L", "OTL", "PTS"), game.standings.first().columns)
        assertEquals(listOf("Montreal", "NY Rangers"), game.standings.flatMap { it.rows }.filter { it.inGame }.map { it.team })
    }

    @Test
    fun `soccer final - scorers, possession, table, no box score or highlights so those sections hide`() {
        // Crystal Palace (home) 1–3 Liverpool (away), FT.
        val game = summary(GameSummaryFixtures.SOCCER, EspnFixtures.ENG1, "401879243")
        assertEquals("FT", game.clockText)
        assertEquals("Liverpool", game.away.fullName)
        assertEquals(3, game.away.score)
        assertEquals(listOf("A. Mac Allister", "F. Wirtz", "A. Isak", "E. Nketiah"), game.scorers.map { it.name })
        assertEquals(listOf(ScoreSide.AWAY, ScoreSide.AWAY, ScoreSide.AWAY, ScoreSide.HOME), game.scorers.map { it.side })
        val possession = game.teamStats.first { it.stat == GameStat.POSSESSION }
        assertEquals("46.9", possession.away)
        assertEquals(ScoreSide.HOME, possession.better)
        // Fewer fouls is better: Palace 10, Liverpool 18.
        assertEquals(ScoreSide.HOME, game.teamStats.first { it.stat == GameStat.FOULS }.better)
        assertEquals(listOf("GP", "W", "D", "L", "GD", "P"), game.standings.single().columns)
        assertTrue(game.boxScore.isEmpty())
        assertTrue(game.highlights.isEmpty())
        assertEquals(listOf(GameSection.GAME, GameSection.STANDINGS), game.sections)

        val details = game.scoreDetails()!!
        assertEquals("LIV", details.first.short)
        assertEquals(listOf(1, 2), details.first.linescores)
        assertEquals(3, details.first.scorers.size)
        assertEquals(listOf(PeriodLabel.Number(1), PeriodLabel.Number(2)), details.periods)
    }

    @Test
    fun `pregame - no score and no season averages as game stats, only standings show`() {
        val game = summary(GameSummaryFixtures.NBA_PRE, EspnLeagueMap.NBA, "401910002")
        assertEquals(ScoreState.PRE, game.state)
        assertFalse(game.isFinal)
        assertNull(game.away.score)
        assertTrue(game.away.linescores.isEmpty())
        assertTrue(game.teamStats.isEmpty())
        assertTrue(game.performers.isEmpty())
        assertTrue(game.boxScore.isEmpty())
        assertNull(game.scoreDetails())
        assertEquals(listOf(GameSection.STANDINGS), game.sections)
    }

    @Test
    fun `a summary without two named sides is nothing`() {
        assertNull(GameSummaryParser.parse(EspnSummaryDto(), EspnLeagueMap.NBA, "1"))
        val oneSide = EspnSummaryDto(
            header = EspnSummaryHeaderDto(
                competitions = listOf(EspnSummaryCompetitionDto(competitors = listOf(EspnSummaryCompetitorDto(homeAway = "home", team = EspnSummaryTeamDto(abbreviation = "BOS")))))
            )
        )
        assertNull(GameSummaryParser.parse(oneSide, EspnLeagueMap.NBA, "1"))
    }

    @Test
    fun `a summary for another event id is never shown as the requested game`() {
        assertNull(GameSummaryParser.parse(GameSummaryFixtures.dto(GameSummaryFixtures.NBA), EspnLeagueMap.NBA, "401909995"))
        assertEquals("401909994", GameSummaryParser.parse(GameSummaryFixtures.dto(GameSummaryFixtures.NBA), EspnLeagueMap.NBA, "401909994")!!.eventId)
    }

    @Test
    fun `better side - higher wins, lower for turnovers-like stats, none on a tie or a missing value`() {
        assertEquals(ScoreSide.HOME, GameSummaryParser.better(GameStat.REBOUNDS, 41.0, 47.0))
        assertEquals(ScoreSide.AWAY, GameSummaryParser.better(GameStat.REBOUNDS, 50.0, 47.0))
        assertEquals(ScoreSide.HOME, GameSummaryParser.better(GameStat.TURNOVERS, 14.0, 10.0))
        assertEquals(ScoreSide.AWAY, GameSummaryParser.better(GameStat.FOULS, 9.0, 12.0))
        assertNull(GameSummaryParser.better(GameStat.ASSISTS, 26.0, 26.0))
        assertNull(GameSummaryParser.better(GameStat.ASSISTS, null, 26.0))
        assertEquals(51.2, GameSummaryParser.statValue("51.2%", GameStat.FIELD_GOAL_PCT)!!, 0.0)
        assertNull(GameSummaryParser.statValue("--", GameStat.ASSISTS))
        assertNull(GameSummaryParser.statValue("35", GameStat.POSSESSION_TIME))
    }

    @Test
    fun `the DTO round trips through the disk copy unchanged`() {
        val adapter = EspnFixtures.moshi.adapter(EspnSummaryDto::class.java)
        val dto = GameSummaryFixtures.dto(GameSummaryFixtures.NHL)
        val again = adapter.fromJson(adapter.toJson(dto))!!
        assertEquals(dto, again)
        assertTrue(GameSummaryParser.isFinal(again))
        assertFalse(GameSummaryParser.isFinal(GameSummaryFixtures.dto(GameSummaryFixtures.NBA_PRE)))
    }
}

/** Trimmed real ESPN summaries under test resources `sportslive/scores/`. */
internal object GameSummaryFixtures {
    const val NBA = "espn_summary_nba_401909994.json"
    const val NBA_PRE = "espn_summary_nba_pre_401910002.json"
    const val NBA_VIDEOS = "espn_summary_nba_videos_401910002.json"
    const val NFL = "espn_summary_nfl_401873060.json"
    const val NHL = "espn_summary_nhl_401892726.json"
    const val SOCCER = "espn_summary_eng1_401879243.json"

    fun dto(file: String): EspnSummaryDto = EspnFixtures.moshi.adapter(EspnSummaryDto::class.java).fromJson(EspnFixtures.json(file))!!

    fun summary(file: String, league: EspnLeague, id: String): GameSummary = GameSummaryParser.parse(dto(file), league, id)!!
}
