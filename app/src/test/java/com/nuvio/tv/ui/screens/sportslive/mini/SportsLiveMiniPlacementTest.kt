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
