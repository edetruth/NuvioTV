package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.EspnIndividualResults
import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.EspnScoreboardDto
import com.nuvio.tv.core.sportslive.scores.Fight
import com.nuvio.tv.core.sportslive.scores.FightMethod
import com.nuvio.tv.core.sportslive.scores.Fighter
import com.nuvio.tv.core.sportslive.scores.MySports
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.core.sportslive.scores.SportsResults
import com.nuvio.tv.core.sportslive.scores.TennisEnding
import com.nuvio.tv.core.sportslive.scores.TennisMatch
import com.nuvio.tv.core.sportslive.scores.TennisPlayer
import com.nuvio.tv.core.sportslive.scores.TennisSet
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
    fun `after a retirement the unfinished last set is nobody's`() {
        val day = parse("espn_tennis_atp_wimbledon_2026.json", EspnLeagueMap.ATP, LocalDate.of(2026, 6, 29))
        fun bold(id: String): List<List<Boolean>> {
            val match = day.single { it.eventId == id }.individual as TennisMatch
            val (winner, loser) = match.winner!! to match.players.single { !it.winner }
            return listOf(winner to loser, loser to winner).map { (player, opponent) ->
                (0 until match.setCount).map { tennisWonSet(match, player, opponent, it) }
            }
        }
        // "4–6 3–6 6–2 3–0 ret.": the 3–0 set was not won.
        assertEquals(listOf(listOf(false, false, true, false), listOf(true, true, false, false)), bold("tennis:177338"))
        // "6–3 7–6 ret." after two finished sets: both stay won.
        assertEquals(listOf(listOf(true, true), listOf(false, false)), bold("tennis:177320"))
        // A completed match's last set is won as usual.
        val done = TennisMatch(
            null,
            listOf(TennisPlayer("A", true, listOf(TennisSet(6), TennisSet(3))), TennisPlayer("B", false, listOf(TennisSet(4), TennisSet(1)))),
            TennisEnding.COMPLETED
        )
        assertTrue(tennisWonSet(done, done.players[0], done.players[1], 1))
        assertFalse(tennisWonSet(done.copy(ending = TennisEnding.RETIRED), done.players[0], done.players[1], 1))
    }

    @Test
    fun `the Results stage names a match's or a fight's sides in row order, never the round (BUG17)`() {
        // The loser listed first stays first: the headline never gives the winner away.
        val match = TennisMatch(
            "Group Stage",
            listOf(TennisPlayer("B", false, listOf(TennisSet(4))), TennisPlayer("A", true, listOf(TennisSet(6)))),
            TennisEnding.COMPLETED
        )
        assertEquals("B" to "A", individualSides(match))
        val fight = Fight("Lightweight", listOf(Fighter("C", false), Fighter("D", true)), method = null, round = null, clock = null, bout = 0)
        assertEquals("C" to "D", individualSides(fight))
        // A match missing a side keeps its title.
        assertNull(individualSides(match.copy(players = match.players.take(1))))
    }

    @Test
    fun `OK on a golf row lists and folds its top five, never while the result is hidden`() {
        assertEquals(setOf("g"), toggleGolf(emptySet(), "g", showScore = true))
        assertEquals(emptySet<String>(), toggleGolf(setOf("g"), "g", showScore = true))
        assertEquals(emptySet<String>(), toggleGolf(emptySet(), "g", showScore = false))
        assertEquals(setOf("g"), toggleGolf(setOf("g"), "g", showScore = false))
    }

    @Test
    fun `individual sports get chips in My sports order and sections per tournament`() {
        val day = LocalDate.of(2026, 11, 16)
        val rows = SportsResults.rows(
            parse("espn_tennis_atp_20261116.json", EspnLeagueMap.ATP, day) + parse("espn_golf_pga_20261116.json", EspnLeagueMap.PGA, day),
            emptyList()
        )
        assertEquals(listOf("tennis", "golf"), resultsJumpEntries(rows, MySports.DEFAULT).mapNotNull { it.sport })
        // A profile that did not pick them gets no Jump to league and no row under every league: Other sports has them.
        val picked = MySports.decode(setOf("basketball"))
        assertTrue(resultsJumpEntries(rows, picked).none { it.jump is ResultsJump.League })
        assertTrue(resultListItems(rows, picked, jump = null, expanded = emptySet(), replays = emptySet()).isEmpty())
        assertEquals(rows.size, resultsOtherEntries(resultsOtherRows(rows, emptyList(), picked)).first().count)

        val items = resultListItems(rows, MySports.DEFAULT, jump = null, expanded = emptySet(), replays = emptySet())
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
