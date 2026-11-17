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
        assertEquals(2 * HOUR + 45 * MINUTE, SportsLiveLiveDuration.maxLiveMs("Soccer"))
        assertEquals(3 * HOUR, SportsLiveLiveDuration.maxLiveMs("NBA"))
        assertEquals(9 * HOUR, SportsLiveLiveDuration.maxLiveMs("Cricket"))
        assertTrue(SportsLiveLiveDuration.isOverdue(game("s", 3 * HOUR, sport = "Soccer"), now))
        assertFalse(SportsLiveLiveDuration.isOverdue(game("c", 5 * HOUR, sport = "Cricket"), now))
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
