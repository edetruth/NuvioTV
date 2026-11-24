package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveMiniGeometry
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsLiveSafeAreaTest {

    @Test
    fun `the safe-area frame never draws in a release build`() {
        assertFalse(SportsLiveSafeAreaFrame.draws(debugBuild = false, on = true))
        assertFalse(SportsLiveSafeAreaFrame.draws(debugBuild = true, on = false))
        assertTrue(SportsLiveSafeAreaFrame.draws(debugBuild = true, on = true))
    }

    @Test
    fun `the frame sits at 48 dp on the sides and 27 dp top and bottom`() {
        assertEquals(48f, SportsLiveSafeAreaFrame.sideDp, 0f)
        assertEquals(27f, SportsLiveSafeAreaFrame.topBottomDp, 0f)
    }

    @Test
    fun `the mini player sits inside the safe area`() {
        assertTrue(SportsLiveMiniGeometry.EDGE_END_DP >= 48f)
        assertTrue(SportsLiveMiniGeometry.EDGE_BOTTOM_DP >= 27f)
    }

    @Test
    fun `the in-player panel's content starts on the safe line and keeps its row width`() {
        assertEquals(48f, SportsLiveTokens.Size.PanelContentStart.value, 0f)
        assertEquals(320f, SportsLiveTokens.Size.PanelWidth.value, 0f)
        // About the rows' old text width (280 - 4 - 6 - 8 = 262 dp), within a few dp.
        val rowText = SportsLiveTokens.Size.PanelWidth.value - SportsLiveTokens.Size.PanelContentStart.value - 4f - 6f - 8f
        assertTrue("row text width $rowText", rowText in 250f..270f)
    }

    @Test
    fun `the player pills use the safe top and end insets`() {
        assertEquals(27f, SportsLiveTokens.Size.SafeTop.value, 0f)
        assertEquals(48f, SportsLiveTokens.Size.SafeEnd.value, 0f)
    }
}
