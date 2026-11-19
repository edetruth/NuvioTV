package com.nuvio.tv.ui.screens.sportslive.mini

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** V2 (#132): on Live Now the mini player takes the stage's media slot; elsewhere it keeps its corner. */
class SportsLiveMiniPlacementTest {

    @After
    fun tearDown() {
        // Leaves the shared spot as other tests expect it.
        val token = Any()
        SportsLiveMiniPlacement.claim(token, SportsLiveMiniSpot.CORNER)
        SportsLiveMiniPlacement.release(token)
        val section = Any()
        SportsLiveMiniPlacement.enterSection(section)
        SportsLiveMiniPlacement.leaveSection(section)
    }

    private val liveNowSlot = SportsLiveMiniSlot(x = 1104, y = 150, width = 720, height = 405)
    private val resultsSlot = SportsLiveMiniSlot(x = 1104, y = 170, width = 720, height = 405)

    @Test
    fun `inside Sports Live a tab switch keeps the mini player docked, never the corner`() {
        // Live Now's stage has left, Upcoming's is not laid out yet, 100 ms into the switch.
        val dock = SportsLiveMiniDocking.decide(inside = true, slot = null, lastSlot = liveNowSlot, lostAtMs = 1_000, nowMs = 1_100)
        assertEquals(SportsLiveMiniDock.Docked(liveNowSlot, held = true), dock)
        // The next stage laid out: docked over it.
        assertEquals(
            SportsLiveMiniDock.Docked(resultsSlot),
            SportsLiveMiniDocking.decide(inside = true, slot = resultsSlot, lastSlot = liveNowSlot, lostAtMs = null, nowMs = 1_200)
        )
    }

    @Test
    fun `outside Sports Live the mini player is in the corner at once`() {
        assertEquals(
            SportsLiveMiniDock.Corner,
            SportsLiveMiniDocking.decide(inside = false, slot = null, lastSlot = liveNowSlot, lostAtMs = 1_000, nowMs = 1_001)
        )
        assertEquals(
            SportsLiveMiniDock.Corner,
            SportsLiveMiniDocking.decide(inside = false, slot = liveNowSlot, lastSlot = liveNowSlot, lostAtMs = null, nowMs = 1_001)
        )
    }

    @Test
    fun `a Sports Live screen with no stage falls back to the corner only after the hold`() {
        val hold = SportsLiveMiniDocking.HOLD_MS
        assertTrue(SportsLiveMiniDocking.decide(true, null, liveNowSlot, 1_000, 1_000 + hold - 1) is SportsLiveMiniDock.Docked)
        assertEquals(SportsLiveMiniDock.Corner, SportsLiveMiniDocking.decide(true, null, liveNowSlot, 1_000, 1_000 + hold))
        // Never laid out at all, nothing to hold.
        assertEquals(SportsLiveMiniDock.Corner, SportsLiveMiniDocking.decide(true, null, null, null, 1_000))
        assertEquals(hold - 100, SportsLiveMiniDocking.holdLeftMs(1_000, 1_100))
        assertEquals(0L, SportsLiveMiniDocking.holdLeftMs(1_000, 1_000 + hold + 5))
        assertEquals(0L, SportsLiveMiniDocking.holdLeftMs(null, 1_000))
    }

    @Test
    fun `the placement holds the last slot through a missing one`() {
        val section = Any()
        val liveNow = Any()
        val upcoming = Any()
        SportsLiveMiniPlacement.enterSection(section)
        SportsLiveMiniPlacement.claim(liveNow, SportsLiveMiniSpot.STAGE, nowMs = 0)
        SportsLiveMiniPlacement.moveTo(liveNow, liveNowSlot)
        // The tab switch: the old stage leaves before the new one is laid out.
        SportsLiveMiniPlacement.release(liveNow, nowMs = 2_000)
        assertNull(SportsLiveMiniPlacement.slot.value)
        assertEquals(liveNowSlot, SportsLiveMiniPlacement.lastSlot)
        assertEquals(2_000L, SportsLiveMiniPlacement.slotLostAtMs)
        assertEquals(
            SportsLiveMiniDock.Docked(liveNowSlot, held = true),
            SportsLiveMiniDocking.decide(true, null, SportsLiveMiniPlacement.lastSlot, SportsLiveMiniPlacement.slotLostAtMs, 2_300)
        )
        // The new stage claims, then is laid out a frame later: the hold ends with it.
        SportsLiveMiniPlacement.claim(upcoming, SportsLiveMiniSpot.STAGE, nowMs = 2_310)
        assertEquals(2_000L, SportsLiveMiniPlacement.slotLostAtMs)
        SportsLiveMiniPlacement.moveTo(upcoming, resultsSlot)
        assertNull(SportsLiveMiniPlacement.slotLostAtMs)
        assertEquals(resultsSlot, SportsLiveMiniPlacement.lastSlot)
    }

    @Test
    fun `a new stage claiming before the old one leaves starts the hold too`() {
        val a = Any()
        val b = Any()
        SportsLiveMiniPlacement.claim(a, SportsLiveMiniSpot.STAGE, liveNowSlot, nowMs = 0)
        SportsLiveMiniPlacement.claim(b, SportsLiveMiniSpot.STAGE, nowMs = 500)
        SportsLiveMiniPlacement.release(a, nowMs = 510)
        assertEquals(500L, SportsLiveMiniPlacement.slotLostAtMs)
        assertEquals(liveNowSlot, SportsLiveMiniPlacement.lastSlot)
    }

    @Test
    fun `back in Sports Live the mini player holds the last stage slot until one is laid out`() {
        val first = Any()
        val stage = Any()
        SportsLiveMiniPlacement.enterSection(first)
        SportsLiveMiniPlacement.claim(stage, SportsLiveMiniSpot.STAGE, liveNowSlot, nowMs = 0)
        SportsLiveMiniPlacement.release(stage, nowMs = 10)
        SportsLiveMiniPlacement.leaveSection(first)
        assertTrue(!SportsLiveMiniPlacement.inside.value)

        val again = Any()
        SportsLiveMiniPlacement.enterSection(again, nowMs = 60_000)
        assertTrue(SportsLiveMiniPlacement.inside.value)
        assertEquals(60_000L, SportsLiveMiniPlacement.slotLostAtMs)
        assertEquals(
            SportsLiveMiniDock.Docked(liveNowSlot, held = true),
            SportsLiveMiniDocking.decide(true, null, SportsLiveMiniPlacement.lastSlot, SportsLiveMiniPlacement.slotLostAtMs, 60_050)
        )
    }

    @Test
    fun `a screen claims its spot and only its own release gives it back`() {
        val stage = Any()
        val other = Any()
        val slot = SportsLiveMiniSlot(x = 1104, y = 150, width = 720, height = 405)
        SportsLiveMiniPlacement.claim(stage, SportsLiveMiniSpot.STAGE, slot)
        assertEquals(SportsLiveMiniSpot.STAGE, SportsLiveMiniPlacement.spot.value)
        assertEquals(slot, SportsLiveMiniPlacement.slot.value)
        SportsLiveMiniPlacement.release(other)
        assertEquals(SportsLiveMiniSpot.STAGE, SportsLiveMiniPlacement.spot.value)
        SportsLiveMiniPlacement.release(stage)
        assertEquals(SportsLiveMiniSpot.CORNER, SportsLiveMiniPlacement.spot.value)
        assertNull(SportsLiveMiniPlacement.slot.value)
    }

    @Test
    fun `only the owner moves the stage slot`() {
        val stage = Any()
        val first = SportsLiveMiniSlot(1104, 150, 720, 405)
        val moved = SportsLiveMiniSlot(1104, 160, 720, 405)
        SportsLiveMiniPlacement.claim(stage, SportsLiveMiniSpot.STAGE, first)
        SportsLiveMiniPlacement.moveTo(Any(), moved)
        assertEquals(first, SportsLiveMiniPlacement.slot.value)
        SportsLiveMiniPlacement.moveTo(stage, moved)
        assertEquals(moved, SportsLiveMiniPlacement.slot.value)
    }

    @Test
    fun `at 1080p the details pane ends above the corner mini player`() {
        // 960 x 540 dp, the screen's 24 dp bottom padding: the pane's bottom must sit a gap above the video.
        val window = 960f
        val miniTop = 540f - SportsLiveMiniGeometry.EDGE_DP - SportsLiveMiniGeometry.heightDp(window)
        val paneBottom = 540f - 24f - SportsLiveMiniGeometry.cornerReserveBottomDp(window, screenBottomPaddingDp = 24f)
        assertTrue("pane ends at $paneBottom, mini starts at $miniTop", paneBottom + SportsLiveMiniGeometry.GAP_DP <= miniTop + 0.01f)
        assertEquals(0f, SportsLiveMiniGeometry.cornerReserveBottomDp(10f, screenBottomPaddingDp = 500f), 0f)
        assertEquals(0.24f, SportsLiveMiniGeometry.CORNER_WIDTH_FRACTION, 0f)
    }
}
