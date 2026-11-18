package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.EspnIndividualResults
import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.EspnScoreboardDto
import com.nuvio.tv.core.sportslive.scores.Fight
import com.nuvio.tv.core.sportslive.scores.FightMethod
import com.nuvio.tv.core.sportslive.scores.Fighter
import com.nuvio.tv.core.sportslive.scores.MySport
import com.nuvio.tv.core.sportslive.scores.MySports
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.core.sportslive.scores.SportsResults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Individual sports' Results rows (#55 part 2): result lines, chips, sections and spoilers. */
class SportsLiveIndividualRowsTest {

    private fun parse(file: String, league: EspnLeague, day: LocalDate) = EspnIndividualResults.toSnapshots(
        EspnFixtures.moshi.adapter(EspnScoreboardDto::class.java).fromJson(EspnFixtures.json(file))!!, league, day
    )

    private val methods = mapOf(FightMethod.KO_TKO to "KO/TKO", FightMethod.SUBMISSION to "Submission", FightMethod.DECISION to "Decision")
    private fun line(fight: Fight) = fightResultLine(fight, methods::getValue) { round, clock -> "R$round $clock".trim() }

    private val card = parse("espn_ufc_20261114.json", EspnLeagueMap.UFC, LocalDate.of(2026, 11, 14))
    private fun fight(id: String) = card.single { it.eventId == id }.individual as Fight

    @Test
    fun `a UFC result reads winner, method, and round and time for a stoppage`() {
        assertEquals("Terrance McKinney · KO/TKO R1 1:53", line(fight("mma:401922643")))
        assertEquals("Kayla Harrison · Submission R4 1:29", line(fight("mma:401922638")))
        // A decision goes the distance: no round.
        assertEquals("Ciryl Gane · Decision", line(fight("mma:401913646")))
        // No winner from ESPN: no line (the row says the result was not given).
        assertNull(line(fight("mma:401922640")))
        // A winner without a method names only the winner.
        val bare = Fight(null, listOf(Fighter("A", true), Fighter("B", false)), method = null, round = 2, clock = "3:14", bout = 0)
        assertEquals("A", line(bare))
    }

    @Test
    fun `individual sports get chips in My sports order and sections per tournament`() {
        val day = LocalDate.of(2026, 11, 16)
        val rows = SportsResults.rows(
            parse("espn_tennis_atp_20261116.json", EspnLeagueMap.ATP, day) + parse("espn_golf_pga_20261116.json", EspnLeagueMap.PGA, day),
            emptyList()
        )
        assertEquals(listOf(MySport.TENNIS, MySport.GOLF), resultSportChips(rows, MySports.DEFAULT))
        // A profile that did not pick them gets no chip and no row under My sports.
        val picked = MySports.decode(setOf("basketball"))
        assertTrue(resultSportChips(rows, picked).isEmpty())
        assertTrue(resultListItems(rows, picked, sport = null, league = null, expanded = emptySet(), replays = emptySet()).isEmpty())

        val items = resultListItems(rows, MySports.DEFAULT, sport = null, league = null, expanded = emptySet(), replays = emptySet())
        val headers = items.filterIsInstance<ResultListItem.Header>().map { it.name }
        assertEquals(listOf("Nitto ATP Finals · Men's Singles", "PGA"), headers)
        assertTrue(items.filterIsInstance<ResultListItem.Row>().none { it.hasReplay })
    }

    @Test
    fun `spoiler modes apply to individual rows as to games`() {
        val rows = SportsResults.rows(parse("espn_f1_20261108.json", EspnLeagueMap.F1, LocalDate.of(2026, 11, 8)), emptyList())
        val race = rows.single()
        assertTrue(SportsLiveResultsUiState(mode = ScoresMode.SHOW).showsScore(race))
        assertFalse(SportsLiveResultsUiState(mode = ScoresMode.HIDE_UNTIL_FOCUSED).showsScore(race))
        assertTrue(SportsLiveResultsUiState(mode = ScoresMode.HIDE_UNTIL_FOCUSED, revealed = setOf(race.event.eventId)).showsScore(race))
        assertFalse(SportsLiveResultsUiState(mode = ScoresMode.OFF, revealed = setOf(race.event.eventId)).showsScore(race))
    }
}
