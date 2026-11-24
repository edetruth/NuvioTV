package com.nuvio.tv.core.sportslive

import com.nuvio.tv.ui.screens.sportslive.dual.dualItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsLiveLiveDurationTest {

    private val now = 100 * HOUR

    private fun game(id: String, startedAgoMs: Long?, sport: String? = null, timeOfDay: Boolean = true) =
        dualItem(id).copy(
            startMs = startedAgoMs?.let { now - it },
            startHasTimeOfDay = startedAgoMs != null && timeOfDay,
            sport = sport
        )

    @Test
    fun `an unknown sport gets its expected 4 h, the late start and the buffer - 4 h 50`() {
        assertEquals(4 * HOUR + 50 * MINUTE, SportsLiveLiveDuration.maxLiveMs(null))
        // A bare "Football" may be American football: its 4 h 35 (unsure means longer).
        assertEquals(4 * HOUR + 35 * MINUTE, SportsLiveLiveDuration.maxLiveMs("Football"))
        assertFalse(SportsLiveLiveDuration.isOverdue(game("a", 4 * HOUR + 49 * MINUTE), now))
        assertTrue(SportsLiveLiveDuration.isOverdue(game("a", 4 * HOUR + 51 * MINUTE), now))
    }

    @Test
    fun `known sports get their own limit`() {
        // #454: expected length + 20 min late start + 30 min buffer for the team sports.
        assertEquals(3 * HOUR + 45 * MINUTE, SportsLiveLiveDuration.maxLiveMs("Soccer"))
        assertEquals(3 * HOUR + 50 * MINUTE, SportsLiveLiveDuration.maxLiveMs("NBA"))
        assertEquals(4 * HOUR + 35 * MINUTE, SportsLiveLiveDuration.maxLiveMs("NFL"))
        assertEquals(4 * HOUR + 35 * MINUTE, SportsLiveLiveDuration.maxLiveMs("NCAAF"))
        assertEquals(8 * HOUR, SportsLiveLiveDuration.maxLiveMs("Tennis"))
        assertEquals(9 * HOUR, SportsLiveLiveDuration.maxLiveMs("Cricket"))
        assertEquals(12 * HOUR, SportsLiveLiveDuration.maxLiveMs("Golf"))
        assertFalse(SportsLiveLiveDuration.isOverdue(game("s", 3 * HOUR + 44 * MINUTE, sport = "Soccer"), now))
        assertTrue(SportsLiveLiveDuration.isOverdue(game("s", 3 * HOUR + 46 * MINUTE, sport = "Soccer"), now))
        assertFalse(SportsLiveLiveDuration.isOverdue(game("c", 5 * HOUR, sport = "Cricket"), now))
    }

    @Test
    fun `#358 - the limit follows the inferred sport - a bare Football or an untagged soccer game gets soccer's`() {
        // Tagged "Football" with a soccer clue (its genre): soccer's 3 h 45, not the unknown sport's 4 h 50.
        val football = game("f", 3 * HOUR + 50 * MINUTE, sport = "Football").let { it.copy(meta = it.meta.copy(genres = listOf("Soccer"))) }
        assertEquals(3 * HOUR + 45 * MINUTE, SportsLiveLiveDuration.maxLiveMsOf(football))
        assertTrue(SportsLiveLiveDuration.isOverdue(football, now))
        // No sport word, soccer only in its genres.
        val untagged = game("u", 3 * HOUR + 50 * MINUTE).let { it.copy(meta = it.meta.copy(genres = listOf("Soccer"))) }
        assertTrue(SportsLiveLiveDuration.isOverdue(untagged, now))
        // American football named by its league keeps its own limit; nothing known gets the unknown sport's.
        assertEquals(4 * HOUR + 35 * MINUTE, SportsLiveLiveDuration.maxLiveMsOf(game("n", HOUR, sport = "NFL")))
        assertEquals(4 * HOUR + 50 * MINUTE, SportsLiveLiveDuration.maxLiveMsOf(game("x", HOUR)))
    }

    @Test
    fun `L2 - a bare Football never cuts an American football game short - unsure means the longer limit`() {
        // "NFL" in its title: American football's 4 h 35.
        val nfl = game("n", 3 * HOUR + 50 * MINUTE, sport = "Football").let { it.copy(meta = it.meta.copy(name = "NFL: Chiefs vs Bills")) }
        assertEquals(4 * HOUR + 35 * MINUTE, SportsLiveLiveDuration.maxLiveMsOf(nfl))
        assertFalse(SportsLiveLiveDuration.isOverdue(nfl, now))
        // Nothing but a bare "Football": unsure, so the longer limit.
        val bare = game("b", 3 * HOUR + 20 * MINUTE, sport = "Football")
        assertEquals(4 * HOUR + 35 * MINUTE, SportsLiveLiveDuration.maxLiveMsOf(bare))
        // A bare "Football" with a soccer league named: soccer's.
        val epl = bare.copy(catalogName = "Premier League")
        assertEquals(3 * HOUR + 45 * MINUTE, SportsLiveLiveDuration.maxLiveMsOf(epl))
    }

    @Test
    fun `L6 - a merged card keeps the longest run any of its sources names`() {
        // The primary names no sport; an untimed alternate comes from a golf catalog.
        val primary = game("p", 4 * HOUR)
        val golf = game("g", null).copy(catalogName = "Golf")
        val merged = primary.copy(alternates = listOf(golf))
        assertEquals(12 * HOUR, SportsLiveLiveDuration.cardMaxLiveMs(merged))
        assertFalse(SportsLiveLiveDuration.isOverdue(merged, now))
        assertTrue(SportsLiveLiveDuration.isOverdue(game("p", 5 * HOUR), now))
    }

    @Test
    fun `#454 - no sport's limit is below its expected length plus the late start plus 30 minutes`() {
        val floor = { espnSport: String -> SportsLiveGameLength.ofEspnSport(espnSport) + 30 * MINUTE }
        val cases = listOf(
            "Soccer" to "soccer", "NBA" to "basketball", "Basketball" to "basketball", "NHL" to "hockey", "Hockey" to "hockey",
            "NFL" to "football", "NCAAF" to "football", "Football" to "football", "MLB" to "baseball", "Baseball" to "baseball"
        )
        for ((sport, espn) in cases) {
            assertTrue(sport, SportsLiveLiveDuration.maxLiveMs(sport) >= floor(espn))
            assertTrue(sport, SportsLiveLiveDuration.maxLiveMsOf(game("g", HOUR, sport = sport)) >= floor(espn))
        }
        // Basketball and hockey: 3 h 50, as decided.
        assertEquals(3 * HOUR + 50 * MINUTE, SportsLiveLiveDuration.maxLiveMsOf(game("b", HOUR, sport = "Basketball")))
        assertEquals(3 * HOUR + 50 * MINUTE, SportsLiveLiveDuration.maxLiveMsOf(game("h", HOUR, sport = "Hockey")))
        // The one formula: nothing below its floor, for every kind and an unknown sport.
        for (kind in SportsLiveSportKind.entries.map<SportsLiveSportKind, SportsLiveSportKind?> { it } + null) {
            assertEquals(kind.toString(), SportsLiveLiveDuration.floorOf(kind), SportsLiveLiveDuration.limit(0L, kind))
            assertTrue(kind.toString(), SportsLiveLiveDuration.floorOf(kind) >= floor("soccer"))
        }
        for (sport in listOf(null, "Rugby", "Darts", "Volleyball", "Tennis", "Golf", "Cricket", "UFC")) {
            assertTrue(sport.toString(), SportsLiveLiveDuration.maxLiveMs(sport) >= floor(""))
        }
    }

    @Test
    fun `games without a timed kickoff and channels are never hidden by age`() {
        assertFalse(SportsLiveLiveDuration.isOverdue(game("a", null), now))
        assertFalse(SportsLiveLiveDuration.isOverdue(game("a", 10 * HOUR, timeOfDay = false), now))
        assertFalse(SportsLiveLiveDuration.isOverdue(game("ch", 10 * HOUR).copy(isChannel = true), now))
    }

    @Test
    fun `a merged card stays while any source started recently enough`() {
        val old = game("a", 5 * HOUR)
        val fresh = game("b", 1 * HOUR)
        assertFalse(SportsLiveLiveDuration.isOverdue(old.copy(alternates = listOf(fresh)), now))
        assertTrue(SportsLiveLiveDuration.isOverdue(old.copy(alternates = listOf(game("c", 6 * HOUR))), now))
        // A source without a known kickoff does not keep an overdue card.
        assertTrue(SportsLiveLiveDuration.isOverdue(old.copy(alternates = listOf(game("d", null))), now))
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
