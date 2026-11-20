package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.ui.screens.player.SportsLivePlayerLayer.END_CARD
import com.nuvio.tv.ui.screens.player.SportsLivePlayerLayer.ERROR
import com.nuvio.tv.ui.screens.player.SportsLivePlayerLayer.KICKOFF_PROMPT
import com.nuvio.tv.ui.screens.player.SportsLivePlayerLayer.PAUSE_INFO
import com.nuvio.tv.ui.screens.player.SportsLivePlayerLayer.STARTING
import com.nuvio.tv.ui.screens.player.SportsLivePlayerLayer.WAITING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** live.19 QA F10, F11: one Sports & Live card owns the player's screen and keys at a time. */
class SportsLivePlayerLayersTest {

    private fun up(
        prompt: Boolean = false,
        error: Boolean = false,
        end: Boolean = false,
        waiting: Boolean = false,
        starting: Boolean = false,
        pause: Boolean = false
    ) = SportsLivePlayerLayers.up(prompt, error, end, waiting, starting, pause)

    @Test
    fun `the priority runs prompt, error, end card, waiting, starting, then the pause screen`() {
        assertEquals(listOf(KICKOFF_PROMPT, ERROR, END_CARD, WAITING, STARTING, PAUSE_INFO), SportsLivePlayerLayers.PRIORITY)
    }

    @Test
    fun `nothing up owns nothing`() {
        assertNull(SportsLivePlayerLayers.owner(up()))
        assertFalse(SportsLivePlayerLayers.covered(STARTING, up()))
    }

    @Test
    fun `the kickoff prompt and the starting card never both show (F11)`() {
        val layers = up(prompt = true, starting = true)
        assertEquals(KICKOFF_PROMPT, SportsLivePlayerLayers.owner(layers))
        assertFalse(SportsLivePlayerLayers.shows(STARTING, layers))
        // The prompt closes: the starting card comes back and takes the keys.
        assertTrue(SportsLivePlayerLayers.shows(STARTING, up(starting = true)))
    }

    @Test
    fun `the end card hides the pause screen under it (F10)`() {
        val layers = up(end = true, pause = true)
        assertTrue(SportsLivePlayerLayers.shows(END_CARD, layers))
        assertFalse(SportsLivePlayerLayers.shows(PAUSE_INFO, layers))
        assertTrue(SportsLivePlayerLayers.covered(PAUSE_INFO, layers))
    }

    @Test
    fun `the pause screen shows only with no Sports and Live card up`() {
        assertTrue(SportsLivePlayerLayers.shows(PAUSE_INFO, up(pause = true)))
        listOf(up(prompt = true), up(error = true), up(end = true), up(waiting = true), up(starting = true)).forEach {
            assertTrue(SportsLivePlayerLayers.covered(PAUSE_INFO, it))
        }
    }

    @Test
    fun `a failure wins over the end card and the waiting card, the prompt wins over a failure`() {
        assertEquals(ERROR, SportsLivePlayerLayers.owner(up(error = true, end = true, waiting = true)))
        assertEquals(KICKOFF_PROMPT, SportsLivePlayerLayers.owner(up(prompt = true, error = true)))
        assertTrue(SportsLivePlayerLayers.covered(END_CARD, up(error = true)))
        assertFalse(SportsLivePlayerLayers.covered(ERROR, up(end = true, starting = true)))
    }

    @Test
    fun `exactly one layer shows whatever is up`() {
        val all = SportsLivePlayerLayer.entries
        for (mask in 1 until (1 shl all.size)) {
            val layers = all.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
            assertEquals(1, all.count { SportsLivePlayerLayers.shows(it, layers) })
        }
    }
}
