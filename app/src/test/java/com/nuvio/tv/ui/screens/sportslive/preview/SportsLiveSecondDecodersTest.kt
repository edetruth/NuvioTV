package com.nuvio.tv.ui.screens.sportslive.preview

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsLiveSecondDecodersTest {

    @After
    fun tearDown() = SportsLiveSecondDecoders.clear()

    private fun allowed(mainPlaying: Boolean = true, above1080p: Boolean = false, instances: Int, limitSeen: Boolean = false) =
        SportsLiveSecondDecoders.allowedBesideMain(
            mainPlaying = mainPlaying,
            mainAbove1080p = above1080p,
            hardwareInstances = instances,
            limitSeen = limitSeen
        )

    @Test
    fun `with nothing playing beside it a preview may start`() {
        assertTrue(allowed(mainPlaying = false, instances = 0, limitSeen = true))
    }

    @Test
    fun `beside a playing game a preview needs a spare hardware decoder`() {
        assertFalse(allowed(instances = 0))
        assertFalse(allowed(instances = 1))
        assertTrue(allowed(instances = 2))
    }

    @Test
    fun `a 4K game needs three decoder instances`() {
        assertFalse(allowed(above1080p = true, instances = 2))
        assertTrue(allowed(above1080p = true, instances = 3))
    }

    @Test
    fun `a decoder limit seen on this TV wins over the instances it reports`() {
        assertFalse(allowed(instances = 16, limitSeen = true))
    }

    @Test
    fun `holders yield once and are dropped`() {
        var yielded = 0
        SportsLiveSecondDecoders.hold(Any()) { yielded++ }
        assertTrue(SportsLiveSecondDecoders.active)
        assertTrue(SportsLiveSecondDecoders.yieldAll())
        assertEquals(1, yielded)
        assertFalse(SportsLiveSecondDecoders.active)
        assertFalse(SportsLiveSecondDecoders.yieldAll())
        assertEquals(1, yielded)
    }

    @Test
    fun `a dropped holder is not asked to yield`() {
        var yielded = false
        val owner = Any()
        SportsLiveSecondDecoders.hold(owner) { yielded = true }
        SportsLiveSecondDecoders.drop(owner)
        assertFalse(SportsLiveSecondDecoders.active)
        assertFalse(SportsLiveSecondDecoders.yieldAll())
        assertFalse(yielded)
    }

    @Test
    fun `a holder that drops itself while yielding does not stop the others`() {
        val first = Any()
        var secondYielded = false
        SportsLiveSecondDecoders.hold(first) { SportsLiveSecondDecoders.drop(first) }
        SportsLiveSecondDecoders.hold(Any()) { secondYielded = true }
        assertTrue(SportsLiveSecondDecoders.yieldAll())
        assertTrue(secondYielded)
    }
}
