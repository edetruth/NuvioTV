package com.nuvio.tv.core.sportslive.scores

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pre-game Preview (#171) from trimmed real ESPN summaries, block omission, and standings (#137). */
class GamePreviewTest {

    /** GS (8-7) at MEM (11-4), scheduled, 2026-11-19. */
    private val nba by lazy { GameSummaryFixtures.summary(NBA_PREVIEW, EspnLeagueMap.NBA, "401910019") }

    /** Fulham at Man City, scheduled, 2026-11-21: no predictor, no injuries. */
    private val epl by lazy { GameSummaryFixtures.summary(EPL_PREVIEW, EspnLeague("soccer", "eng.1"), "401879233") }

    @Test
    fun `an upcoming NBA game opens on its Preview with every block`() {
        assertEquals(ScoreState.PRE, nba.state)
        assertEquals(GameSection.PREVIEW, nba.sections.first())
        val preview = nba.preview!!
        assertEquals(
            listOf(PreviewBlock.HEADER, PreviewBlock.FORM, PreviewBlock.HEAD_TO_HEAD, PreviewBlock.LEADERS, PreviewBlock.INJURIES, PreviewBlock.WIN_CHANCE),
            preview.blocks
        )
        assertEquals("8-7", preview.away.record)
        assertEquals("11-4", preview.home.record)
    }

    @Test
    fun `form - each side's last five, oldest first, with the opponent`() {
        val form = nba.preview!!.form
        assertEquals(listOf(ScoreSide.AWAY, ScoreSide.HOME), form.map { it.side })
        val memphis = form.first { it.side == ScoreSide.HOME }
        assertEquals("MEM", memphis.team)
        // ESPN lists the latest first (a loss to Denver): the chips read oldest to latest.
        assertEquals(listOf("W", "W", "W", "W", "L"), memphis.games.map { it.result })
        assertEquals("DEN", memphis.games.last().opponent)
        assertEquals(true, memphis.games.last().home)
        assertEquals(listOf("L", "L", "L", "L", "W"), form.first { it.side == ScoreSide.AWAY }.games.map { it.result })
    }

    @Test
    fun `head-to-head - ESPN's line and the meetings`() {
        val h2h = nba.preview!!.headToHead!!
        assertEquals("MEM leads series 1-0", h2h.summary)
        val meeting = h2h.meetings.single()
        assertEquals("MEM", meeting.away)
        assertEquals("GS", meeting.home)
        assertEquals(ScoreSide.AWAY, meeting.winner)
    }

    @Test
    fun `leaders, injuries and win chance per side`() {
        val preview = nba.preview!!
        assertEquals(6, preview.leaders.size)
        assertEquals(listOf(ScoreSide.AWAY, ScoreSide.AWAY, ScoreSide.AWAY, ScoreSide.HOME, ScoreSide.HOME, ScoreSide.HOME), preview.leaders.map { it.side })
        assertEquals("27.8", preview.leaders.first().value)
        assertEquals(GamePreviewParser.INJURIES_PER_TEAM * 2, preview.injuries.size)
        assertTrue(preview.injuries.all { it.status == "Out" })
        assertEquals("S. Curry", preview.injuries.first { it.side == ScoreSide.AWAY && it.detail == "Abdomen" }.player)
        assertEquals(WinChance(away = 37.8, home = 62.2), preview.winChance)
    }

    @Test
    fun `soccer - form with draws, head-to-head and leaders, no injury or win chance block`() {
        val preview = epl.preview!!
        assertEquals(listOf(PreviewBlock.HEADER, PreviewBlock.FORM, PreviewBlock.HEAD_TO_HEAD, PreviewBlock.LEADERS), preview.blocks)
        assertTrue(preview.form.first { it.side == ScoreSide.AWAY }.games.any { it.result == "D" })
        assertEquals("MNC leads series 5-0", preview.headToHead!!.summary)
        assertEquals(GamePreviewParser.MEETINGS, preview.headToHead!!.meetings.size)
        assertNull(preview.winChance)
        assertTrue(preview.injuries.isEmpty())
    }

    @Test
    fun `a summary with no preview data has no Preview tab, a header alone included`() {
        // The older trimmed pre-game fixture: header, leaders and standings only.
        val bare = GamePreviewParser.parse(
            EspnSummaryDto(),
            GameTeam("1", "A", "A", null, null, null, emptyList(), "1-0"),
            GameTeam("2", "B", "B", null, null, null, emptyList(), null)
        )
        assertEquals(listOf(PreviewBlock.HEADER), bare.blocks)
        assertFalse(bare.hasContent)
        val empty = GamePreviewParser.parse(
            EspnSummaryDto(),
            GameTeam("1", "A", "A", null, null, null, emptyList(), null),
            GameTeam("2", "B", "B", null, null, null, emptyList(), null)
        )
        assertTrue(empty.blocks.isEmpty())
    }

    @Test
    fun `a broken predictor or an unknown result is left out`() {
        val home = GameTeam("1", "A", "A", null, null, null, emptyList(), null)
        val away = GameTeam("2", "B", "B", null, null, null, emptyList(), null)
        val dto = EspnSummaryDto(
            predictor = EspnPredictorDto(EspnPredictorTeamDto("1", "n/a"), EspnPredictorTeamDto("2", "40")),
            lastFiveGames = listOf(EspnLastFiveDto(EspnSummaryTeamDto(id = "1"), listOf(EspnLastFiveEventDto(gameResult = "?"))))
        )
        val preview = GamePreviewParser.parse(dto, home, away)
        assertNull(preview.winChance)
        assertTrue(preview.form.isEmpty())
    }

    @Test
    fun `a live or final game has no Preview`() {
        val final = GameSummaryFixtures.summary(GameSummaryFixtures.NBA, EspnLeagueMap.NBA, "401909994")
        assertNull(final.preview)
        assertFalse(GameSection.PREVIEW in final.sections)
    }

    @Test
    fun `under Hide until focused only the spoiler-neutral Preview shows before Reveal`() {
        val hidden = com.nuvio.tv.ui.screens.sportslive.SportsLiveGameUiState(summary = nba, mode = ScoresMode.HIDE_UNTIL_FOCUSED)
        assertFalse(hidden.showsScore)
        assertEquals(listOf(GameSection.PREVIEW), hidden.sections)
        assertEquals(nba.sections, hidden.copy(revealed = true).sections)
    }

    // ---- Standings (#137) ----

    private fun standings(file: String): Map<String, TeamStanding> =
        StandingsParser.parse(EspnFixtures.moshi.adapter(EspnStandingsNodeDto::class.java).fromJson(EspnFixtures.json(file))!!)

    @Test
    fun `NBA standings place each team in its conference by seed`() {
        val book = standings("espn_standings_nba_20261119.json")
        assertEquals(30, book.size)
        // Milwaukee, listed first, is 15th of 15 in the East.
        assertEquals(TeamStanding(15, 15, "East"), book["15"])
        assertTrue(book.values.all { it.position in 1..15 })
    }

    @Test
    fun `Premier League standings place each team in the table by rank`() {
        val book = standings("espn_standings_eng1_20261119.json")
        assertEquals(20, book.size)
        assertEquals(1, book["359"]!!.position)
        assertEquals(20, book["359"]!!.groupSize)
    }

    @Test
    fun `standings are asked for pro leagues and soccer competitions only`() {
        assertTrue(StandingsRepository.covers(EspnLeagueMap.NBA))
        assertTrue(StandingsRepository.covers(EspnLeague("soccer", "eng.1")))
        assertFalse(StandingsRepository.covers(EspnLeagueMap.SOCCER_ALL))
        assertFalse(StandingsRepository.covers(EspnLeagueMap.COLLEGE_BASKETBALL))
        assertFalse(StandingsRepository.covers(EspnLeagueMap.ATP))
    }

    private companion object {
        const val NBA_PREVIEW = "espn_summary_nba_preview_401910019.json"
        const val EPL_PREVIEW = "espn_summary_eng1_preview_401879233.json"
    }

    @Test
    fun `without results the Preview drops the form and the head-to-head and keeps the rest`() {
        val hidden = nba.preview!!.withoutResults()
        assertEquals(
            listOf(PreviewBlock.HEADER, PreviewBlock.LEADERS, PreviewBlock.INJURIES, PreviewBlock.WIN_CHANCE),
            hidden.blocks
        )
        assertTrue(hidden.hasContent)
    }

    @Test
    fun `a summary with its preview results hidden keeps its Preview section while other blocks remain`() {
        val shown = nba.withPreviewResultsHidden()
        assertEquals(GameSection.PREVIEW, shown.sections.first())
        assertFalse(shown.preview!!.blocks.contains(PreviewBlock.FORM))
        assertFalse(shown.preview!!.blocks.contains(PreviewBlock.HEAD_TO_HEAD))
        // The original is untouched: Show and a Reveal keep the full Preview.
        assertTrue(nba.preview!!.blocks.contains(PreviewBlock.FORM))
    }

    @Test
    fun `a Preview with only form and head-to-head has no content once its results are hidden`() {
        val bare = nba.preview!!.copy(leaders = emptyList(), injuries = emptyList(), winChance = null).withoutResults()
        assertFalse(bare.hasContent)
        assertFalse(nba.copy(preview = bare).sections.contains(GameSection.PREVIEW))
    }
}
