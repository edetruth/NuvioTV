package com.nuvio.tv.ui.screens.sportslive

import android.view.KeyEvent
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SportsLiveShortPressKeysTest {

    private val keys = SportsLiveShortPressKeys(timeoutMillis = 500L)

    private fun event(code: Int, act: Int, repeat: Int = 0, atMs: Long = 0L, downAtMs: Long = 0L): KeyEvent =
        mockk {
            every { keyCode } returns code
            every { action } returns act
            every { repeatCount } returns repeat
            every { eventTime } returns atMs
            every { downTime } returns downAtMs
            every { isLongPress } returns false
        }

    private fun down(code: Int, repeat: Int = 0, atMs: Long = 0L) =
        keys.onKey(event(code, KeyEvent.ACTION_DOWN, repeat, atMs), active = true)

    private fun up(code: Int, atMs: Long) = keys.onKey(event(code, KeyEvent.ACTION_UP, atMs = atMs), active = true)

    @Test
    fun `short right shows the controls and short left opens the panel, only on release, consuming both events`() {
        assertEquals(SportsLiveShortPress.CONSUMED, down(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(SportsLiveShortPress.SHOW_CONTROLS, up(KeyEvent.KEYCODE_DPAD_RIGHT, atMs = 100L))
        assertEquals(SportsLiveShortPress.CONSUMED, down(KeyEvent.KEYCODE_DPAD_LEFT, atMs = 1_000L))
        assertEquals(SportsLiveShortPress.OPEN_PANEL, up(KeyEvent.KEYCODE_DPAD_LEFT, atMs = 1_100L))
    }

    @Test
    fun `held right never acts and passes the repeats and release to the player`() {
        assertEquals(SportsLiveShortPress.CONSUMED, down(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(SportsLiveShortPress.NONE, down(KeyEvent.KEYCODE_DPAD_RIGHT, repeat = 1, atMs = 550L))
        assertEquals(SportsLiveShortPress.NONE, down(KeyEvent.KEYCODE_DPAD_RIGHT, repeat = 2, atMs = 600L))
        assertEquals(SportsLiveShortPress.NONE, up(KeyEvent.KEYCODE_DPAD_RIGHT, atMs = 700L))
        // The next short press acts again.
        assertEquals(SportsLiveShortPress.CONSUMED, down(KeyEvent.KEYCODE_DPAD_RIGHT, atMs = 1_000L))
        assertEquals(SportsLiveShortPress.SHOW_CONTROLS, up(KeyEvent.KEYCODE_DPAD_RIGHT, atMs = 1_100L))
    }

    @Test
    fun `a press held past the timeout without repeats does not act`() {
        assertEquals(SportsLiveShortPress.CONSUMED, down(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(SportsLiveShortPress.NONE, up(KeyEvent.KEYCODE_DPAD_LEFT, atMs = 600L))
    }

    @Test
    fun `without an active session every event, and other keys always, are left to the player`() {
        val inactive = { e: KeyEvent -> keys.onKey(e, active = false) }

        assertEquals(SportsLiveShortPress.NONE, inactive(event(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN)))
        assertEquals(SportsLiveShortPress.NONE, inactive(event(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_UP, atMs = 100L)))
        assertEquals(SportsLiveShortPress.NONE, inactive(event(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.ACTION_DOWN)))
        assertEquals(SportsLiveShortPress.NONE, inactive(event(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.ACTION_UP, atMs = 100L)))
        // Other keys, with a session too.
        assertEquals(SportsLiveShortPress.NONE, down(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(SportsLiveShortPress.NONE, up(KeyEvent.KEYCODE_DPAD_DOWN, atMs = 100L))
    }

    @Test
    fun `zap keys are off during a watch party`() {
        assertEquals(true, zapDirection(KeyEvent.KEYCODE_DPAD_DOWN, partyActive = false))
        assertEquals(false, zapDirection(KeyEvent.KEYCODE_DPAD_UP, partyActive = false))
        assertNull(zapDirection(KeyEvent.KEYCODE_DPAD_DOWN, partyActive = true))
        assertNull(zapDirection(KeyEvent.KEYCODE_CHANNEL_UP, partyActive = true))
    }
}
