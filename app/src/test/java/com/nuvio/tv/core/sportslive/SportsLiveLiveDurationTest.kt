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
    fun `an unknown sport gets three and a half hours`() {
        assertEquals(3 * HOUR + 30 * MINUTE, SportsLiveLiveDuration.maxLiveMs(null))
        assertEquals(3 * HOUR + 30 * MINUTE, SportsLiveLiveDuration.maxLiveMs("Football"))
        assertFalse(SportsLiveLiveDuration.isOverdue(game("a", 3 * HOUR + 29 * MINUTE), now))
        assertTrue(SportsLiveLiveDuration.isOverdue(game("a", 3 * HOUR + 31 * MINUTE), now))
    }

    @Test
    fun `known sports get their own limit`() {
        assertEquals(3 * HOUR + 15 * MINUTE, SportsLiveLiveDuration.maxLiveMs("Soccer"))
        assertEquals(3 * HOUR, SportsLiveLiveDuration.maxLiveMs("NBA"))
        assertEquals(4 * HOUR + 30 * MINUTE, SportsLiveLiveDuration.maxLiveMs("NFL"))
        assertEquals(4 * HOUR + 30 * MINUTE, SportsLiveLiveDuration.maxLiveMs("NCAAF"))
        assertEquals(8 * HOUR, SportsLiveLiveDuration.maxLiveMs("Tennis"))
        assertEquals(9 * HOUR, SportsLiveLiveDuration.maxLiveMs("Cricket"))
        assertEquals(12 * HOUR, SportsLiveLiveDuration.maxLiveMs("Golf"))
        assertFalse(SportsLiveLiveDuration.isOverdue(game("s", 3 * HOUR, sport = "Soccer"), now))
        assertTrue(SportsLiveLiveDuration.isOverdue(game("s", 3 * HOUR + 20 * MINUTE, sport = "Soccer"), now))
        assertFalse(SportsLiveLiveDuration.isOverdue(game("c", 5 * HOUR, sport = "Cricket"), now))
    }

    @Test
    fun `#358 - the limit follows the inferred sport - a bare Football or an untagged soccer game gets soccer's`() {
        // Tagged "Football" with a soccer clue (its genre): soccer's 3 h 15, not the 3 h 30 default.
        val football = game("f", 3 * HOUR + 20 * MINUTE, sport = "Football").let { it.copy(meta = it.meta.copy(genres = listOf("Soccer"))) }
        assertEquals(3 * HOUR + 15 * MINUTE, SportsLiveLiveDuration.maxLiveMsOf(football))
        assertTrue(SportsLiveLiveDuration.isOverdue(football, now))
        // No sport word, soccer only in its genres.
        val untagged = game("u", 3 * HOUR + 20 * MINUTE).let { it.copy(meta = it.meta.copy(genres = listOf("Soccer"))) }
        assertTrue(SportsLiveLiveDuration.isOverdue(untagged, now))
        // American football named by its league keeps its own limit; nothing known keeps the default.
        assertEquals(4 * HOUR + 30 * MINUTE, SportsLiveLiveDuration.maxLiveMsOf(game("n", HOUR, sport = "NFL")))
        assertEquals(3 * HOUR + 30 * MINUTE, SportsLiveLiveDuration.maxLiveMsOf(game("x", HOUR)))
    }

    @Test
    fun `L2 - a bare Football never cuts an American football game short - unsure means the longer limit`() {
        // "NFL" in its title: American football's 4 h 30.
        val nfl = game("n", 3 * HOUR + 20 * MINUTE, sport = "Football").let { it.copy(meta = it.meta.copy(name = "NFL: Chiefs vs Bills")) }
        assertEquals(4 * HOUR + 30 * MINUTE, SportsLiveLiveDuration.maxLiveMsOf(nfl))
        assertFalse(SportsLiveLiveDuration.isOverdue(nfl, now))
        // Nothing but a bare "Football": unsure, so the longer limit.
        val bare = game("b", 3 * HOUR + 20 * MINUTE, sport = "Football")
        assertEquals(4 * HOUR + 30 * MINUTE, SportsLiveLiveDuration.maxLiveMsOf(bare))
        // A bare "Football" with a soccer league named: soccer's.
        val epl = bare.copy(catalogName = "Premier League")
        assertEquals(3 * HOUR + 15 * MINUTE, SportsLiveLiveDuration.maxLiveMsOf(epl))
    }

    @Test
    fun `games without a timed kickoff and channels are never hidden by age`() {
        assertFalse(SportsLiveLiveDuration.isOverdue(game("a", null), now))
        assertFalse(SportsLiveLiveDuration.isOverdue(game("a", 10 * HOUR, timeOfDay = false), now))
        assertFalse(SportsLiveLiveDuration.isOverdue(game("ch", 10 * HOUR).copy(isChannel = true), now))
    }

    @Test
    fun `a merged card stays while any source started recently enough`() {
        val old = game("a", 4 * HOUR)
        val fresh = game("b", 1 * HOUR)
        assertFalse(SportsLiveLiveDuration.isOverdue(old.copy(alternates = listOf(fresh)), now))
        assertTrue(SportsLiveLiveDuration.isOverdue(old.copy(alternates = listOf(game("c", 5 * HOUR))), now))
        // A source without a known kickoff does not keep an overdue card.
        assertTrue(SportsLiveLiveDuration.isOverdue(old.copy(alternates = listOf(game("d", null))), now))
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
