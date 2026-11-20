package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** TV QA 54-panel: the panel opened with no focus (no ring, blank footer). Where focus goes as it opens. */
class SportsLivePanelOpenFocusTest {
    @Test
    fun `the panel opens on Now playing once the game playing is known`() {
        assertEquals(PanelOpenFocus.NOW_PLAYING, panelOpenFocus(nowPlaying = true, firstLine = true, userKeyed = false, hasFocus = false))
        // The panel had put focus on the filter (or a line) while the game playing was unknown: it moves on.
        assertEquals(PanelOpenFocus.NOW_PLAYING, panelOpenFocus(nowPlaying = true, firstLine = true, userKeyed = false, hasFocus = true))
    }

    @Test
    fun `with the game playing unknown it opens on the first line, else the empty line`() {
        assertEquals(PanelOpenFocus.FIRST_LINE, panelOpenFocus(nowPlaying = false, firstLine = true, userKeyed = false, hasFocus = false))
        assertEquals(PanelOpenFocus.EMPTY_LINE, panelOpenFocus(nowPlaying = false, firstLine = false, userKeyed = false, hasFocus = false))
    }

    @Test
    fun `focus the user moved is kept`() {
        assertEquals(PanelOpenFocus.KEEP, panelOpenFocus(nowPlaying = true, firstLine = true, userKeyed = true, hasFocus = true))
        // A key pressed but the panel lost focus: it is asked for again.
        assertEquals(PanelOpenFocus.NOW_PLAYING, panelOpenFocus(nowPlaying = true, firstLine = true, userKeyed = true, hasFocus = false))
    }
}
