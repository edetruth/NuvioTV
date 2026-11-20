package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.core.sportslive.TeamPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Tennis live scores (#160): surname matching, the live copy of the ATP scoreboard and the set columns. */
class TennisMatchingTest {

    private val day = LocalDate.of(2026, 11, 15)

    private val live: List<ScoreSnapshot> by lazy {
        EspnIndividualResults.tennisLive(
            EspnFixtures.moshi.adapter(EspnScoreboardDto::class.java).fromJson(EspnFixtures.json(FIXTURE))!!,
            EspnLeagueMap.ATP,
            day
        )
    }

    private fun card(home: String, away: String, startMs: Long? = null) = ScoreCard("k|$home|$away", TeamPair(home, away), startMs, startMs != null)

    private fun matched(home: String, away: String): ScoreSnapshot? = TennisMatcher.match(card(home, away), live)

    // ---- Surnames ----

    @Test
    fun `a surname alone, a full name, an initial before or after all name the player`() {
        for (side in listOf("Sinner", "Jannik Sinner", "J. Sinner", "Sinner J.", "SINNER", "J Sinner")) {
            assertTrue(side, TennisNames.sideMatches(side, listOf("Jannik Sinner")))
        }
    }

    @Test
    fun `accents, hyphens and particles fold away`() {
        assertTrue(TennisNames.sideMatches("Felix Auger Aliassime", listOf("Félix Auger-Aliassime")))
        assertTrue(TennisNames.sideMatches("De Minaur", listOf("Alex de Minaur")))
        assertTrue(TennisNames.sideMatches("A. de Minaur", listOf("Alex de Minaur")))
        assertTrue(TennisNames.sideMatches("Bautista Agut", listOf("Roberto Bautista Agut")))
        assertTrue(TennisNames.sideMatches("Rafael Jódar", listOf("Rafael Jodar")))
    }

    @Test
    fun `a wrong first name or initial is another player`() {
        assertFalse(TennisNames.sideMatches("Mischa Zverev", listOf("Alexander Zverev")))
        assertFalse(TennisNames.sideMatches("M. Zverev", listOf("Alexander Zverev")))
        assertFalse(TennisNames.sideMatches("Sinner", listOf("Carlos Alcaraz")))
        // Initials alone name nobody.
        assertFalse(TennisNames.sideMatches("J. S.", listOf("Jannik Sinner")))
    }

    @Test
    fun `a doubles pair matches its two players either way round, never a singles player`() {
        val pair = listOf("Harri Heliovaara", "Henry Patten")
        assertTrue(TennisNames.sideMatches("Heliovaara/Patten", pair))
        assertTrue(TennisNames.sideMatches("Patten / Heliovaara", pair))
        assertTrue(TennisNames.sideMatches("H. Heliovaara & H. Patten", pair))
        assertTrue(TennisNames.sideMatches("Heliovaara and Patten", pair))
        assertFalse(TennisNames.sideMatches("Heliovaara", pair))
        assertFalse(TennisNames.sideMatches("Heliovaara/Patten", listOf("Harri Heliovaara")))
        assertFalse(TennisNames.sideMatches("Heliovaara/Matos", pair))
    }

    @Test
    fun `a leading tournament and bracketed seeds are dropped`() {
        assertTrue(TennisNames.sideMatches("ATP Finals: Sinner", listOf("Jannik Sinner")))
        assertTrue(TennisNames.sideMatches("(1) Sinner [ITA]", listOf("Jannik Sinner")))
    }

    @Test
    fun `short names are surnames, a pair's joined`() {
        assertEquals("Sinner", TennisNames.shortName(listOf("Jannik Sinner")))
        assertEquals("de Minaur", TennisNames.shortName(listOf("Alex de Minaur")))
        assertEquals("Heliovaara/Patten", TennisNames.shortName(listOf("Harri Heliovaara", "Henry Patten")))
    }

    // ---- The live copy and the matcher ----

    @Test
    fun `the live copy keeps singles and doubles with sides, ranks and the serve`() {
        assertEquals(
            listOf("tennis-live:187517", "tennis-live:187518", "tennis-live:187505", "tennis-live:187506", "tennis-live:999001"),
            live.map { it.eventId }
        )
        val doubles = live.first { it.eventId == "tennis-live:187517" }
        assertTrue((doubles.individual as TennisMatch).doubles)
        assertEquals("Heliovaara/Patten", doubles.home.shortDisplayName)
        assertEquals(1, doubles.home.rank)
        val sinner = live.first { it.eventId == "tennis-live:999001" }
        val match = sinner.individual as TennisMatch
        assertEquals(ScoreState.IN, sinner.state)
        assertTrue(match.live)
        assertEquals("Semifinal", match.title)
        assertTrue(match.players[0].serving)
        assertEquals(listOf(6, 3, 2), sinner.home.linescores)
        // A set still being played counts for neither side.
        assertEquals(1, sinner.home.score)
        assertEquals(1, sinner.away.score)
        assertEquals(2, sinner.home.rank)
    }

    @Test
    fun `Results never lists the live copy`() {
        val finals = live.map { it.copy(state = ScoreState.POST, isFinal = true) }
        assertTrue(SportsResults.rows(finals, emptyList()).isEmpty())
    }

    @Test
    fun `a card finds its match by surnames, either order, and knows which side is which`() {
        val event = matched("Alcaraz", "Sinner")!!
        assertEquals("tennis-live:999001", event.eventId)
        assertEquals(false, ScoreMatcher.firstIsHome("Alcaraz", "Sinner", event))
        assertEquals(true, ScoreMatcher.firstIsHome("J. Sinner", "C. Alcaraz", event))
        assertEquals("tennis-live:187505", matched("Alexander Zverev", "Rafael Jodar")?.eventId)
    }

    @Test
    fun `a doubles card finds its doubles match`() {
        assertEquals("tennis-live:187517", matched("Heliovaara/Patten", "Luz/Matos")?.eventId)
        assertEquals("tennis-live:187518", matched("Arevalo & Pavic", "Arribage & Olivetti")?.eventId)
        // A singles card never lands on a doubles match of the same players.
        assertNull(matched("Heliovaara", "Luz"))
    }

    @Test
    fun `no match without both sides, and none for other cards`() {
        assertNull(matched("Sinner", "Djokovic"))
        assertNull(matched("Celtics", "Knicks"))
    }

    @Test
    fun `the same pair twice on a day goes to the one in play, else the nearest start, else none`() {
        val first = live.first { it.eventId == "tennis-live:187505" }
        val again = first.copy(eventId = "tennis-live:2", startMs = first.startMs!! + 6 * 3_600_000L)
        val events = listOf(first, again)
        assertEquals(again.eventId, TennisMatcher.match(card("Zverev", "Jodar"), listOf(first, again.copy(state = ScoreState.IN)))?.eventId)
        assertEquals(again.eventId, TennisMatcher.match(card("Zverev", "Jodar", startMs = again.startMs), events)?.eventId)
        assertNull(TennisMatcher.match(card("Zverev", "Jodar"), events))
    }

    // ---- Set formatting ----

    @Test
    fun `the row's set columns read in the card's order, with the serve and the leader`() {
        val match = live.first { it.eventId == "tennis-live:999001" }.individual as TennisMatch
        assertEquals(TennisColumns(listOf(6, 3, 2), listOf(4, 6, 1), servingFirst = true, leader = null), TennisSets.columns(match, firstIsFirstListed = true))
        assertEquals(TennisColumns(listOf(4, 6, 1), listOf(6, 3, 2), servingFirst = false, leader = null), TennisSets.columns(match, firstIsFirstListed = false))
        val final = live.first { it.eventId == "tennis-live:187506" }.individual as TennisMatch
        // Shelton (listed first) won 6-2 3-6 6-3.
        assertEquals(0, TennisSets.columns(final, true)!!.leader)
        assertNull(TennisSets.columns(final, true)!!.servingFirst)
    }

    @Test
    fun `the set line shows tiebreaks and a match tiebreak from the first side's view`() {
        val doubles = live.first { it.eventId == "tennis-live:187517" }.individual as TennisMatch
        // Heliovaara and Patten: 6-3 5-7 and a 10-4 match tiebreak.
        assertEquals("6–3 5–7 [10–4]", TennisSets.line(doubles, true))
        assertEquals("3–6 7–5 [4–10]", TennisSets.line(doubles, false))
        val tiebreak = live.first { it.eventId == "tennis-live:187518" }.individual as TennisMatch
        assertEquals("7–6(7) 6–4", TennisSets.line(tiebreak, true))
    }

    @Test
    fun `a match with no sets yet has no columns`() {
        val pre = TennisMatch("Final", listOf(TennisPlayer("A B", false, emptyList()), TennisPlayer("C D", false, emptyList())), TennisEnding.COMPLETED, live = true)
        assertNull(TennisSets.columns(pre, true))
    }

    private companion object {
        const val FIXTURE = "espn_tennis_atp_live_20261115.json"
    }
}
