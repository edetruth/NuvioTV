package com.nuvio.tv.ui.screens.sportslive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsLiveAwakeHoldsTest {

    private val holds = SportsLiveAwakeHolds()
    private val leftPane = Any()
    private val rightPane = Any()
    private val miniPlayer = Any()

    @Test
    fun `dual with both panes playing holds the screen`() {
        holds.set(leftPane, playing = true)
        assertTrue(holds.set(rightPane, playing = true))
        assertTrue(holds.anyPlaying.value)
    }

    @Test
    fun `one pane paused and the other playing still holds the screen`() {
        holds.set(leftPane, playing = true)
        holds.set(rightPane, playing = true)
        assertTrue(holds.set(leftPane, playing = false))
        assertTrue(holds.anyPlaying.value)
    }

    @Test
    fun `both panes paused release the screen`() {
        holds.set(leftPane, playing = true)
        holds.set(rightPane, playing = true)
        holds.set(leftPane, playing = false)
        assertFalse(holds.set(rightPane, playing = false))
        assertFalse(holds.anyPlaying.value)
    }

    @Test
    fun `the mini player playing holds the screen`() {
        assertTrue(holds.set(miniPlayer, playing = true))
        assertTrue(holds.anyPlaying.value)
    }

    @Test
    fun `nothing playing releases the screen`() {
        assertFalse(holds.anyPlaying.value)
        holds.set(miniPlayer, playing = true)
        assertFalse(holds.set(miniPlayer, playing = false))
        assertFalse(holds.anyPlaying.value)
    }

    @Test
    fun `the mini player stopping leaves the dual panes hold`() {
        holds.set(leftPane, playing = true)
        holds.set(miniPlayer, playing = true)
        assertTrue(holds.set(miniPlayer, playing = false))
        assertFalse(holds.set(leftPane, playing = false))
    }

    @Test
    fun `reporting the same state twice or releasing without a hold is harmless`() {
        assertFalse(holds.set(leftPane, playing = false))
        assertTrue(holds.set(leftPane, playing = true))
        assertTrue(holds.set(leftPane, playing = true))
        assertFalse(holds.set(leftPane, playing = false))
        assertFalse(holds.set(leftPane, playing = false))
    }
}
