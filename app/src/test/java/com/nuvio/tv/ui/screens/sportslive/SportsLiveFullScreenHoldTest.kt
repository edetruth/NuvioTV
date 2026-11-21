package com.nuvio.tv.ui.screens.sportslive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** #39: a dual pane handed to full screen plays on until the full player's first frame, or until it fails. */
class SportsLiveFullScreenHoldTest {

    private class FakePlayer(val name: String)

    private val released = mutableListOf<FakePlayer>()
    private val hold = SportsLiveHandoverHold<FakePlayer> { released += it }
    private val paneA = FakePlayer("a")

    @Test
    fun `the pane plays on until the full player's first frame, then crosses over once`() {
        hold.hold("game-a", paneA, nowMs = 1_000L)

        // Starting: held, nothing released.
        assertEquals(SportsLiveHoldStep.HOLD, hold.step("game-a", firstFrame = false, failed = false, nextLink = false, nowMs = 2_000L))
        assertEquals(SportsLiveHoldStep.HOLD, hold.step("game-a", firstFrame = false, failed = false, nextLink = false, nowMs = 9_000L))
        assertSame(paneA, hold.held.value!!.player)
        assertEquals(emptyList<FakePlayer>(), released)

        // First frame: the pane's player goes, exactly once.
        assertEquals(SportsLiveHoldStep.CROSS_OVER, hold.step("game-a", firstFrame = true, failed = false, nextLink = false, nowMs = 9_050L))
        assertNull(hold.held.value)
        assertEquals(listOf(paneA), released)
        hold.releaseIfHeld(paneA)
        hold.release()
        assertEquals(listOf(paneA), released)
    }

    @Test
    fun `a full player that fails, moves to another link or never starts falls back to today's start`() {
        hold.hold("game-a", paneA, nowMs = 0L)
        assertEquals(SportsLiveHoldStep.FALL_BACK, hold.step("game-a", firstFrame = false, failed = true, nextLink = false, nowMs = 3_000L))
        assertEquals(listOf(paneA), released)

        val second = FakePlayer("a2")
        hold.hold("game-a", second, nowMs = 0L)
        assertEquals(SportsLiveHoldStep.FALL_BACK, hold.step("game-a", firstFrame = false, failed = false, nextLink = true, nowMs = 3_000L))
        assertEquals(listOf(paneA, second), released)

        val third = FakePlayer("a3")
        hold.hold("game-a", third, nowMs = 0L)
        val cap = 30_000L
        assertEquals(SportsLiveHoldStep.HOLD, sportsLiveHoldStep(firstFrame = false, failed = false, nextLink = false, heldMs = cap - 1, capMs = cap))
        assertEquals(SportsLiveHoldStep.FALL_BACK, sportsLiveHoldStep(firstFrame = false, failed = false, nextLink = false, heldMs = cap, capMs = cap))
        assertEquals(
            SportsLiveHoldStep.FALL_BACK,
            hold.step("game-a", firstFrame = false, failed = false, nextLink = false, nowMs = com.nuvio.tv.core.sportslive.SportsLiveStreamThresholds.STARTUP_HARD_CAP_MS)
        )
        assertEquals(listOf(paneA, second, third), released)
    }

    @Test
    fun `the held picture covers only its own game's player, and only while held (L1)`() {
        assertEquals(false, hold.covers("game-a"))
        hold.hold("game-a", paneA, nowMs = 0L)
        assertEquals(true, hold.covers("game-a"))
        assertEquals(false, hold.covers("game-b"))
        assertEquals(false, hold.covers(null))
        hold.step("game-a", firstFrame = true, failed = false, nextLink = false, nowMs = 1_000L)
        assertEquals(false, hold.covers("game-a"))
    }

    @Test
    fun `the app going to the background lets the held player go at once, a configuration change does not (L2)`() {
        hold.hold("game-a", paneA, nowMs = 0L)
        hold.onAppStop(changingConfigurations = true)
        assertSame(paneA, hold.held.value!!.player)
        assertEquals(emptyList<FakePlayer>(), released)
        hold.onAppStop(changingConfigurations = false)
        assertNull(hold.held.value)
        assertEquals(listOf(paneA), released)
        // Nothing held: nothing to release.
        hold.onAppStop(changingConfigurations = false)
        assertEquals(listOf(paneA), released)
    }

    @Test
    fun `a first frame wins over a failure seen at the same check`() {
        assertEquals(SportsLiveHoldStep.CROSS_OVER, sportsLiveHoldStep(firstFrame = true, failed = true, nextLink = true, heldMs = 0L, capMs = 1L))
    }

    @Test
    fun `another game's player, a new hold or a late timer never leave a player running or release one twice`() {
        hold.hold("game-a", paneA, nowMs = 0L)
        // A full player for another game: the held one goes.
        assertEquals(SportsLiveHoldStep.FALL_BACK, hold.step("game-b", firstFrame = false, failed = false, nextLink = false, nowMs = 10L))
        assertEquals(listOf(paneA), released)

        // A new hold lets the older one go first.
        val b = FakePlayer("b")
        val c = FakePlayer("c")
        hold.hold("game-b", b, nowMs = 0L)
        hold.hold("game-c", c, nowMs = 0L)
        assertEquals(listOf(paneA, b), released)

        // The late cap timer of b does nothing to c.
        hold.releaseIfHeld(b)
        assertSame(c, hold.held.value!!.player)
        assertEquals(listOf(paneA, b), released)

        // Nothing held: nothing to hold on to.
        hold.release()
        assertEquals(SportsLiveHoldStep.FALL_BACK, hold.step("game-c", firstFrame = false, failed = false, nextLink = false, nowMs = 0L))
        assertEquals(listOf(paneA, b, c), released)
    }
}
