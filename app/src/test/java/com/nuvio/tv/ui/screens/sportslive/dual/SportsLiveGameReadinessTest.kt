package com.nuvio.tv.ui.screens.sportslive.dual

import com.nuvio.tv.core.sportslive.SportsLiveAddonFetch
import com.nuvio.tv.core.sportslive.SportsLiveReadyMark
import com.nuvio.tv.core.sportslive.SportsLiveGameReadiness
import com.nuvio.tv.core.sportslive.SportsLiveStreamThresholds
import com.nuvio.tv.domain.model.SportsLiveItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #147: the check-ahead's ✓ / ⚠ marks, and how they feed "Next live game". */
class SportsLiveGameReadinessTest {

    private val lineup = listOf("a", "b", "c", "d").map(::dualItem)
    private fun item(id: String) = lineup.first { it.id == id }
    private var now = 5_000_000L
    private val readiness = SportsLiveGameReadiness(nowMs = { now })

    /** Next live game after [current] failed, with the marks as the real source of ✓ and ⚠. */
    private fun next(current: SportsLiveItem): SportsLiveItem? = SportsLiveSecondGame.nextAfterFailure(
        current = current,
        lineup = lineup,
        scores = emptyMap(),
        finishedKeys = emptySet(),
        tried = setOf(current.id),
        knownGood = { readiness.of(it) == SportsLiveReadyMark.READY },
        noStream = readiness.marks.value.filterValues { it == SportsLiveReadyMark.NO_STREAM }.keys
    )

    @Test
    fun `next live game goes to a game marked ready first`() {
        readiness.set("d", SportsLiveReadyMark.READY)
        assertEquals("d", next(item("a"))?.id)
    }

    @Test
    fun `next live game passes over a game with no working stream`() {
        readiness.set("b", SportsLiveReadyMark.NO_STREAM)
        assertEquals("c", next(item("a"))?.id)
    }

    @Test
    fun `a game with no working stream is still offered when no other is left`() {
        listOf("b", "c", "d").forEach { readiness.set(it, SportsLiveReadyMark.NO_STREAM) }
        assertEquals("b", next(item("a"))?.id)
    }

    @Test
    fun `marks stand about three minutes`() {
        readiness.set("b", SportsLiveReadyMark.NO_STREAM)
        readiness.set("c", SportsLiveReadyMark.READY)
        now += SportsLiveStreamThresholds.CHECK_AHEAD_CACHE_MS - 1
        assertEquals(SportsLiveReadyMark.READY, readiness.of(item("c")))

        now += 1
        assertNull(readiness.of(item("c")))
        assertNull(readiness.markedAt("b"))
        readiness.prune()
        assertTrue(readiness.marks.value.isEmpty())
        // With the marks gone, b is the next game again.
        assertEquals("b", next(item("a"))?.id)
    }

    @Test
    fun `a merged card is ready when any of its sources is`() {
        val merged = item("a").copy(alternates = listOf(item("b")))
        readiness.set("b", SportsLiveReadyMark.READY)
        assertEquals(SportsLiveReadyMark.READY, readiness.of(merged))
    }

    @Test
    fun `an addon whose median lookup is over six seconds counts as slow`() {
        val fetch = SportsLiveAddonFetch()
        listOf(6_100L, 900L, 6_200L).forEach { fetch.noteLookupMs("https://slow.example.invalid/", it) }
        listOf(500L, 14_000L, 700L).forEach { fetch.noteLookupMs("https://fast.example.invalid", it) }

        assertEquals(setOf("https://slow.example.invalid"), fetch.slowAddonKeys())
    }
}
