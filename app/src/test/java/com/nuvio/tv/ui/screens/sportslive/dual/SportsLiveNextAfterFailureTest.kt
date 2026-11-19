package com.nuvio.tv.ui.screens.sportslive.dual

import com.nuvio.tv.core.sportslive.SportsLiveTriedGames
import com.nuvio.tv.domain.model.SportsLiveItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #138 follow-up: Next live game after a failure never ping-pongs between dead games. */
class SportsLiveNextAfterFailureTest {

    private val lineup = listOf("a", "b", "c", "d", "e").map(::dualItem)
    private var now = 1_000_000L
    private val tried = SportsLiveTriedGames(nowMs = { now })

    /** Next live game pressed on [current]'s error screen: it counts as failed, then the pick. */
    private fun nextFrom(
        current: SportsLiveItem,
        dead: Set<String> = emptySet(),
        good: Set<String> = emptySet(),
        games: List<SportsLiveItem> = lineup
    ): SportsLiveItem? {
        tried.noteFailed(current)
        return SportsLiveSecondGame.nextAfterFailure(
            current = current,
            lineup = games,
            scores = emptyMap(),
            finishedKeys = dead,
            tried = tried.ids(),
            knownGood = { it.id in good }
        )
    }

    private fun item(id: String) = lineup.first { it.id == id }

    @Test
    fun `two dead games never send the viewer back and forth`() {
        val games = listOf(item("a"), item("b"))
        val first = nextFrom(item("a"), games = games)
        assertEquals("b", first?.id)
        // b is dead too: Next live game finds nothing rather than going back to a.
        assertNull(nextFrom(item("b"), games = games))
    }

    @Test
    fun `a run of three dead games is passed over in lineup order and never revisited`() {
        assertEquals("b", nextFrom(item("a"))?.id)
        assertEquals("c", nextFrom(item("b"))?.id)
        assertEquals("d", nextFrom(item("c"))?.id)
        // From d: e, then forward round the lineup, skipping a, b and c.
        assertEquals("e", nextFrom(item("d"))?.id)
        assertNull(nextFrom(item("e")))
        assertEquals(setOf("a", "b", "c", "d", "e"), tried.ids())
    }

    @Test
    fun `it moves forward from the failed game, wrapping, and skips games marked with no working stream`() {
        assertEquals("a", nextFrom(item("e"), dead = setOf(item("b").streamKey))?.id)
        assertEquals("d", nextFrom(item("c"), dead = setOf(item("b").streamKey))?.id)
        // Marked dead and tried: only b would be left, and it is marked.
        tried.noteFailed(item("d"))
        assertNull(nextFrom(item("a"), dead = setOf(item("b").streamKey)))
    }

    @Test
    fun `a game with a known-good link goes first`() {
        assertEquals("d", nextFrom(item("a"), good = setOf("d"))?.id)
        // Among several, the first known-good one forward.
        assertEquals("e", nextFrom(item("c"), good = setOf("b", "e"))?.id)
    }

    @Test
    fun `when every game is used up, Try again clears the tried games`() {
        lineup.forEach(tried::noteFailed)
        assertNull(nextFrom(item("c")))
        tried.clear()
        assertEquals("d", nextFrom(item("c"))?.id)
    }

    @Test
    fun `tried games are forgotten after ten minutes, or when one of them plays after all`() {
        tried.noteFailed(item("a"))
        now += SportsLiveTriedGames.WINDOW_MS - 1
        assertTrue("a" in tried.ids())
        now += 1
        assertFalse("a" in tried.ids())

        tried.noteFailed(item("b"))
        tried.noteFailed(item("c"))
        // Another game playing changes nothing; a tried one playing clears them all.
        tried.onPlaying("z")
        assertEquals(setOf("b", "c"), tried.ids())
        tried.onPlaying("b")
        assertTrue(tried.ids().isEmpty())
    }
}
