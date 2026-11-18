package com.nuvio.tv.ui.screens.sportslive.mini

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** N3: on the Timeline the mini player sits beside the bottom panel's buttons, never over them. */
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
        val timeline = Any()
        val other = Any()
        SportsLiveMiniPlacement.claim(timeline, SportsLiveMiniSpot.TIMELINE_PANEL)
        assertEquals(SportsLiveMiniSpot.TIMELINE_PANEL, SportsLiveMiniPlacement.spot.value)
        SportsLiveMiniPlacement.release(other)
        assertEquals(SportsLiveMiniSpot.TIMELINE_PANEL, SportsLiveMiniPlacement.spot.value)
        SportsLiveMiniPlacement.release(timeline)
        assertEquals(SportsLiveMiniSpot.CORNER, SportsLiveMiniPlacement.spot.value)
    }

    @Test
    fun `at 1080p the Timeline's mini player fits beside the panel and inside its height`() {
        // 960 × 540 dp (1080p at density 320), the screen's 48 dp side and 24 dp bottom padding, a 168 dp panel.
        val window = 960f
        val miniWidth = SportsLiveMiniGeometry.widthDp(SportsLiveMiniSpot.TIMELINE_PANEL, window)
        val miniLeft = window - SportsLiveMiniGeometry.EDGE_DP - miniWidth
        val panelRight = window - 48f - SportsLiveMiniGeometry.timelinePanelReserveDp(window, screenEndPaddingDp = 48f)
        assertTrue("panel ends at $panelRight, mini starts at $miniLeft", panelRight + SportsLiveMiniGeometry.GAP_DP <= miniLeft + 0.01f)
        val miniTop = 540f - SportsLiveMiniGeometry.EDGE_DP - SportsLiveMiniGeometry.heightDp(SportsLiveMiniSpot.TIMELINE_PANEL, window)
        val panelTop = 540f - 24f - 168f
        assertTrue("mini top $miniTop, panel top $panelTop", miniTop >= panelTop)
        // Elsewhere the corner size is kept.
        assertEquals(0.24f, SportsLiveMiniGeometry.widthFraction(SportsLiveMiniSpot.CORNER), 0f)
    }
}
