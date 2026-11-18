package com.nuvio.tv.core.sportslive.scores

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Individual sports (#55 part 2) parsed from trimmed real ESPN scoreboards, and their result texts. */
class IndividualResultsTest {

    private fun parse(file: String, league: EspnLeague, day: LocalDate): List<ScoreSnapshot> =
        EspnIndividualResults.toSnapshots(
            EspnFixtures.moshi.adapter(EspnScoreboardDto::class.java).fromJson(EspnFixtures.json(file))!!,
            league,
            day
        )

    private fun tennis(file: String, league: EspnLeague, day: LocalDate) = parse(file, league, day).associate { it.eventId to it.individual as TennisMatch }

    // ---- Tennis ----

    @Test
    fun `ATP keeps that day's singles matches of a tournament, as finals with no sides`() {
        val snapshots = parse(ATP_FINALS, EspnLeagueMap.ATP, LocalDate.of(2026, 11, 16))
        assertEquals(listOf("tennis:187511", "tennis:187512"), snapshots.map { it.eventId })
        val first = snapshots.first()
        assertEquals("Nitto ATP Finals · Men's Singles", first.leagueName)
        assertEquals("339-2026/mens-singles", first.competitionId)
        assertEquals(ScoreState.POST, first.state)
        assertTrue(first.isFinal)
        assertNull(first.home.displayName)
        assertNull(first.away.displayName)
        val match = first.individual as TennisMatch
        assertEquals("Group Stage", match.title)
        // ESPN's listing order, which never gives away the result.
        assertEquals(listOf("Carlos Alcaraz", "Frances Tiafoe"), match.players.map { it.name })
        assertEquals("Carlos Alcaraz", match.winner?.name)
        assertEquals("6–2 6–2", IndividualFormat.setScore(match))
    }

    @Test
    fun `a match is kept only on its own ESPN day, never on the tournament's other days`() {
        assertEquals(listOf("tennis:187505", "tennis:187506"), parse(ATP_FINALS, EspnLeagueMap.ATP, LocalDate.of(2026, 11, 15)).map { it.eventId })
        assertTrue(parse(ATP_FINALS, EspnLeagueMap.ATP, LocalDate.of(2026, 11, 14)).isEmpty())
    }

    @Test
    fun `tiebreaks show the losing points, from the winner's side`() {
        val match = tennis(ATP_FINALS, EspnLeagueMap.ATP, LocalDate.of(2026, 11, 16)).getValue("tennis:187512")
        assertEquals("Daniil Medvedev", match.winner?.name)
        assertEquals("7–6(4) 6–3", IndividualFormat.setScore(match))
        val wta = tennis(WTA_20261108, EspnLeagueMap.WTA, LocalDate.of(2026, 11, 8)).getValue("tennis:187374")
        assertEquals("Coco Gauff", wta.winner?.name)
        assertEquals("6–4 6–7(5) 6–2", IndividualFormat.setScore(wta))
        val marathon = tennis(WIMBLEDON, EspnLeagueMap.ATP, LocalDate.of(2026, 6, 30)).getValue("tennis:177363")
        assertEquals("6–7(7) 7–6(16) 7–6(7) 7–6(5)", IndividualFormat.setScore(marathon))
    }

    @Test
    fun `retirements keep the sets played, walkovers have none`() {
        val day = tennis(WIMBLEDON, EspnLeagueMap.ATP, LocalDate.of(2026, 6, 29))
        val carreno = day.getValue("tennis:177320")
        assertEquals(TennisEnding.RETIRED, carreno.ending)
        assertEquals("Pablo Carreno Busta", carreno.winner?.name)
        assertEquals("6–3 7–6(7)", IndividualFormat.setScore(carreno))
        assertEquals("4–6 3–6 6–2 3–0", IndividualFormat.setScore(day.getValue("tennis:177338")))
        val walkover = tennis(WIMBLEDON, EspnLeagueMap.ATP, LocalDate.of(2026, 7, 17)).getValue("tennis:178630")
        assertEquals(TennisEnding.WALKOVER, walkover.ending)
        assertEquals("Alejandro Tabilo", walkover.winner?.name)
        assertEquals("", IndividualFormat.setScore(walkover))
    }

    @Test
    fun `each tour keeps its own singles draw at a Grand Slam, and no doubles`() {
        val atp = parse(WIMBLEDON, EspnLeagueMap.ATP, LocalDate.of(2026, 6, 29))
        assertEquals(setOf("tennis:177320", "tennis:177357", "tennis:177338"), atp.map { it.eventId }.toSet())
        // The ATP feed lists Wimbledon's women's singles too: only the WTA feed keeps it.
        assertEquals(listOf("tennis:177532"), parse(WIMBLEDON, EspnLeagueMap.WTA, LocalDate.of(2026, 6, 29)).map { it.eventId })
        assertTrue(parse(WIMBLEDON, EspnLeagueMap.ATP, LocalDate.of(2026, 7, 2)).isEmpty())
        val wta = parse(WTA_20261108, EspnLeagueMap.WTA, LocalDate.of(2026, 11, 8))
        assertEquals(setOf("tennis:186642", "tennis:187373", "tennis:187374"), wta.map { it.eventId }.toSet())
    }

    @Test
    fun `a match tiebreak reads as its points`() {
        // ATP Finals doubles 187515 as ESPN sends it: 6-7 (5-7) 6-3 1-0 (10-4).
        val match = TennisMatch(
            title = null,
            players = listOf(
                TennisPlayer("Heliovaara / Patten", winner = false, sets = listOf(TennisSet(7, 7), TennisSet(3), TennisSet(0, 4))),
                TennisPlayer("Arevalo / Pavic", winner = true, sets = listOf(TennisSet(6, 5), TennisSet(6), TennisSet(1, 10)))
            ),
            ending = TennisEnding.COMPLETED
        )
        assertEquals("6–7(5) 6–3 [10–4]", IndividualFormat.setScore(match))
        assertTrue(IndividualFormat.isMatchTiebreak(TennisSet(1, 10), TennisSet(0, 4)))
        assertFalse(IndividualFormat.isMatchTiebreak(TennisSet(7, 7), TennisSet(6, 5)))
    }

    private val finished = EspnStatusDto(EspnStatusTypeDto(name = "STATUS_FINAL", state = "post", completed = true))

    private fun player(name: String, order: Int, winner: Boolean, vararg games: Double?) = EspnCompetitorDto(
        order = order, winner = winner, athlete = EspnAthleteDto(displayName = name), linescores = games.map { EspnLinescoreDto(value = it) }
    )

    private fun match(id: String, hour: Int, round: String?, type: EspnCompetitionTypeDto? = null, a: Array<Double?> = arrayOf(6.0, 6.0), b: Array<Double?> = arrayOf(3.0, 4.0)) =
        EspnCompetitionDto(
            id = id, date = "2026-06-29T${hour}:00Z", status = finished, type = type, round = round?.let { EspnRoundDto(it) },
            competitors = listOf(player("A$id", 1, true, *a), player("B$id", 2, false, *b))
        )

    private fun draw(vararg matches: EspnCompetitionDto) = EspnIndividualResults.toSnapshots(
        EspnScoreboardDto(
            events = listOf(
                EspnEventDto(
                    id = "188-2026", name = "Wimbledon",
                    groupings = listOf(EspnGroupingDto(EspnGroupingInfoDto("mens-singles", "Men's Singles"), matches.toList()))
                )
            )
        ),
        EspnLeagueMap.ATP,
        LocalDate.of(2026, 6, 29)
    )

    @Test
    fun `a set with no games drops the match, rather than shifting the set columns`() {
        val snapshots = draw(
            match("1", 14, "Round 1", a = arrayOf(6.0, null, 6.0), b = arrayOf(3.0, 6.0, 2.0)),
            match("2", 15, "Round 1")
        )
        assertEquals(listOf("tennis:2"), snapshots.map { it.eventId })
    }

    @Test
    fun `qualifying matches are tagged Q and listed after the main draw`() {
        val snapshots = draw(
            match("q1", 12, "Qualifying 1st Round"),
            match("m1", 16, "Round 1"),
            match("q2", 13, "Round 2", type = EspnCompetitionTypeDto(text = "Men's Singles Qualifying", slug = "mens-singles-qualifying")),
            match("q3", 14, "Final Qualifying Round")
        )
        val titles = snapshots.associate { it.eventId to (it.individual as TennisMatch).let { m -> m.title to m.qualifying } }
        assertEquals("Round 1" to false, titles["tennis:m1"])
        assertEquals("Q1" to true, titles["tennis:q1"])
        assertEquals("Q2" to true, titles["tennis:q2"])
        assertEquals("Q" to true, titles["tennis:q3"])
        // One section: the main draw first, then qualifying by start time.
        val rows = SportsResults.rows(snapshots, emptyList())
        assertEquals(listOf("tennis:m1", "tennis:q1", "tennis:q2", "tennis:q3"), rows.map { it.event.eventId })
        assertEquals(1, rows.map { it.event.competitionId }.distinct().size)
    }

    // ---- UFC ----

    @Test
    fun `a UFC card lists every fight with fighters, winner, method, round and time`() {
        val fights = parse(UFC_20261114, EspnLeagueMap.UFC, LocalDate.of(2026, 11, 14))
        assertEquals(13, fights.size)
        assertTrue(fights.all { it.leagueName == "UFC 334: Gane vs. Hokit" && it.competitionId == "600061400" })
        val main = fights.single { it.eventId == "mma:401913646" }.individual as Fight
        assertEquals(0, main.bout)
        assertEquals("Heavyweight", main.title)
        assertEquals(listOf("Ciryl Gane", "Josh Hokit"), main.fighters.map { it.name })
        assertEquals("Ciryl Gane", main.winner?.name)
        assertEquals(FightMethod.DECISION, main.method)
        assertEquals(5, main.round)
        assertFalse(main.showsRound)
        val ko = fights.single { it.eventId == "mma:401922643" }.individual as Fight
        assertEquals(FightMethod.KO_TKO, ko.method)
        assertEquals("Terrance McKinney", ko.winner?.name)
        assertEquals(1, ko.round)
        assertEquals("1:53", ko.clock)
        assertTrue(ko.showsRound)
        val sub = fights.single { it.eventId == "mma:401922638" }.individual as Fight
        assertEquals(FightMethod.SUBMISSION, sub.method)
        assertEquals(4, sub.round)
        assertEquals("1:29", sub.clock)
        // No winner and no method from ESPN: nothing is guessed.
        val none = fights.single { it.eventId == "mma:401922640" }.individual as Fight
        assertNull(none.winner)
        assertNull(none.method)
    }

    @Test
    fun `the card is kept whole on its own day only`() {
        // The main card runs past midnight UTC; the card is on its US Eastern day.
        assertTrue(parse(UFC_20261114, EspnLeagueMap.UFC, LocalDate.of(2026, 11, 15)).isEmpty())
    }

    @Test
    fun `method words map, and an unknown one is no method`() {
        fun method(text: String) = EspnIndividualResults.method(listOf(EspnDetailDto(type = EspnDetailTypeDto(text))))
        assertEquals(FightMethod.KO_TKO, method("Unofficial Winner Kotko"))
        assertEquals(FightMethod.SUBMISSION, method("Unofficial Winner Submission"))
        assertEquals(FightMethod.DECISION, method("Unofficial Winner Decision"))
        assertNull(method("Unofficial Winner Dq"))
        assertNull(method("Knockdown"))
        assertNull(EspnIndividualResults.method(emptyList()))
    }

    // ---- F1 ----

    @Test
    fun `F1 shows the race podium on race day, never practice or qualifying`() {
        val races = parse(F1_20261108, EspnLeagueMap.F1, LocalDate.of(2026, 11, 8))
        val race = races.single()
        assertEquals("racing:401839132", race.eventId)
        assertEquals("F1", race.leagueName)
        val result = race.individual as RaceResult
        assertEquals("MSC Cruises São Paulo Grand Prix", result.title)
        assertEquals("1. Norris · 2. Verstappen · 3. Hadjar", IndividualFormat.podium(result))
        // Qualifying was on the 7th: no row that day.
        assertTrue(parse(F1_20261108, EspnLeagueMap.F1, LocalDate.of(2026, 11, 7)).isEmpty())
    }

    @Test
    fun `a podium missing a place is not shown`() {
        val dto = EspnScoreboardDto(
            events = listOf(
                EspnEventDto(
                    id = "1", name = "GP",
                    competitions = listOf(
                        EspnCompetitionDto(
                            id = "9", date = "2026-11-08T17:00Z", type = EspnCompetitionTypeDto(abbreviation = "Race"),
                            status = EspnStatusDto(EspnStatusTypeDto(state = "post", completed = true)),
                            competitors = listOf(
                                EspnCompetitorDto(order = 1, athlete = EspnAthleteDto("Lando Norris", "L. Norris")),
                                EspnCompetitorDto(order = 3, athlete = EspnAthleteDto("Isack Hadjar", "I. Hadjar"))
                            )
                        )
                    )
                )
            )
        )
        assertTrue(EspnIndividualResults.toSnapshots(dto, EspnLeagueMap.F1, LocalDate.of(2026, 11, 8)).isEmpty())
    }

    // ---- Golf ----

    @Test
    fun `golf shows a finished tournament on its final round's day, top five with ties`() {
        val pga = parse(PGA_20261116, EspnLeagueMap.PGA, LocalDate.of(2026, 11, 16)).single()
        assertEquals("golf:401850980", pga.eventId)
        assertEquals("PGA", pga.leagueName)
        assertTrue(pga.isFinal)
        val result = pga.individual as GolfResult
        assertEquals("Austin Championship pres. by Compliance Solutions", result.title)
        assertEquals("Chad Ramey", result.winner?.name)
        assertEquals(listOf("1", "2", "3", "T4", "T4"), result.leaders.map(IndividualFormat::position))
        assertEquals(listOf("-20", "-18", "-17", "-16", "-16"), result.leaders.map { it.toPar })
        assertEquals("−20", IndividualFormat.toPar(result.leaders.first()))
        // A Monday finish: not on the Sunday.
        assertTrue(parse(PGA_20261116, EspnLeagueMap.PGA, LocalDate.of(2026, 11, 15)).isEmpty())
        val lpga = parse(LPGA_20261115, EspnLeagueMap.LPGA, LocalDate.of(2026, 11, 15)).single().individual as GolfResult
        assertEquals("Lindy Duncan", lpga.winner?.name)
        assertEquals(listOf("1", "2", "T3", "T3", "5"), lpga.leaders.map(IndividualFormat::position))
    }

    @Test
    fun `a playoff loser on the winner's score is 2nd, not tied first`() {
        fun player(order: Int, name: String, score: String) = EspnCompetitorDto(order = order, score = score, athlete = EspnAthleteDto(name, null))
        val dto = EspnScoreboardDto(
            events = listOf(
                EspnEventDto(
                    id = "7", name = "Open", date = "2026-11-12T05:00Z", endDate = "2026-11-15T05:00Z",
                    competitions = listOf(
                        EspnCompetitionDto(
                            status = EspnStatusDto(EspnStatusTypeDto(state = "post", completed = true)),
                            competitors = listOf(player(1, "A", "-12"), player(2, "B", "-12"), player(3, "C", "-12"), player(4, "D", "-10"), player(5, "E", "E"), player(6, "F", "E"))
                        )
                    )
                )
            )
        )
        val golf = EspnIndividualResults.toSnapshots(dto, EspnLeagueMap.PGA, LocalDate.of(2026, 11, 15)).single().individual as GolfResult
        assertEquals(listOf("1", "T2", "T2", "4", "T5"), golf.leaders.map(IndividualFormat::position))
        assertEquals("A", golf.winner?.name)
    }

    @Test
    fun `missing fields never throw, they drop the result`() {
        val empty = EspnScoreboardDto(events = listOf(EspnEventDto(), EspnEventDto(groupings = listOf(EspnGroupingDto())), EspnEventDto(competitions = listOf(EspnCompetitionDto()))))
        listOf(EspnLeagueMap.ATP, EspnLeagueMap.UFC, EspnLeagueMap.F1, EspnLeagueMap.PGA).forEach { league ->
            assertTrue(EspnIndividualResults.toSnapshots(empty, league, LocalDate.of(2026, 11, 15)).isEmpty())
        }
        assertTrue(EspnIndividualResults.toSnapshots(EspnScoreboardDto(), EspnLeagueMap.WTA, LocalDate.of(2026, 11, 15)).isEmpty())
    }

    private companion object {
        const val ATP_FINALS = "espn_tennis_atp_20261116.json"
        const val WIMBLEDON = "espn_tennis_atp_wimbledon_2026.json"
        const val WTA_20261108 = "espn_tennis_wta_20261108.json"
        const val UFC_20261114 = "espn_ufc_20261114.json"
        const val F1_20261108 = "espn_f1_20261108.json"
        const val PGA_20261116 = "espn_golf_pga_20261116.json"
        const val LPGA_20261115 = "espn_golf_lpga_20261115.json"
    }
}
