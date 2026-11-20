package com.nuvio.tv.ui.screens.sportslive.dual

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** live.20: dual screen opened from the full player keeps the game on screen (its last frame), never black. */
class DualHandoffFrameTest {

    private val game = dualItem("a")
    private val other = dualItem("b")
    private val shotAt = 1_000_000L

    private fun shows(pane: DualPane, nowMs: Long = shotAt + 500L, key: String? = game.streamKey, atMs: Long? = shotAt) =
        dualShowsHandoffFrame(atMs, key, pane, nowMs)

    @Test
    fun `the game's pane shows the player's last frame while its link starts`() {
        assertTrue(shows(DualPane(game)))
        assertTrue(shows(DualPane(game, stream = null, status = PaneStatus.Loading)))
    }

    @Test
    fun `the frame goes at the pane's first frame, a failure, a reconnect or the end`() {
        assertFalse(shows(DualPane(game, status = PaneStatus.Playing)))
        assertFalse(shows(DualPane(game, status = PaneStatus.Error)))
        assertFalse(shows(DualPane(game, status = PaneStatus.DeviceLimit)))
        assertFalse(shows(DualPane(game, status = PaneStatus.Ended)))
        assertFalse(shows(DualPane(game, reconnecting = true)))
    }

    @Test
    fun `the other game's pane, a stale frame or no frame shows nothing`() {
        assertFalse(shows(DualPane(other)))
        assertFalse(shows(DualPane(game), nowMs = shotAt + SportsLiveDualHandoffFrame.MAX_AGE_MS + 1))
        assertFalse(shows(DualPane(game), atMs = null))
        assertFalse(shows(DualPane(game), key = null))
    }

    @Test
    fun `the copy is at most 960 px wide and keeps the video's shape`() {
        assertEquals(960 to 540, copySize(1920, 1080, 960))
        assertEquals(640 to 360, copySize(640, 360, 960))
        assertNull(copySize(0, 1080, 960))
    }
}
