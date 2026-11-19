package com.nuvio.tv.ui.screens.sportslive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #175: a lookup that lands after focus moved on never starts playback. */
class SportsLiveRequestGateTest {

    @Test
    fun `the current request plays`() {
        val gate = SportsLiveRequestGate()
        val fox = gate.begin("addon|fox")
        assertTrue(gate.mayPlay(fox))
    }

    @Test
    fun `a late result for the previous item does not play`() {
        val gate = SportsLiveRequestGate()
        val disney = gate.begin("addon|disney")
        val fox = gate.begin("addon|fox")
        assertFalse(gate.mayPlay(disney))
        assertTrue(gate.mayPlay(fox))
    }

    @Test
    fun `focus moving away makes every request stale`() {
        val gate = SportsLiveRequestGate()
        val disney = gate.begin("addon|disney")
        gate.invalidate()
        assertFalse(gate.mayPlay(disney))
    }

    @Test
    fun `back on the same item an older request is still stale`() {
        val gate = SportsLiveRequestGate()
        val first = gate.begin("addon|disney")
        gate.begin("addon|fox")
        val again = gate.begin("addon|disney")
        assertFalse(gate.mayPlay(first))
        assertTrue(gate.mayPlay(again))
    }

    @Test
    fun `a token plays only with its own generation and key`() {
        val token = SportsLiveRequestToken("addon|fox", 3)
        assertTrue(sportsLiveMayPlay(token, generation = 3, currentKey = "addon|fox"))
        assertFalse(sportsLiveMayPlay(token, generation = 4, currentKey = "addon|fox"))
        assertFalse(sportsLiveMayPlay(token, generation = 3, currentKey = "addon|disney"))
        assertFalse(sportsLiveMayPlay(token, generation = 3, currentKey = null))
    }
}
