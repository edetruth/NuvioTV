package com.nuvio.tv.ui.screens.sportslive.mini

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SportsLiveMiniFocusKeysTest {

    @Test
    fun `OK expands once and its release and repeats are eaten`() {
        for (ok in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            assertEquals(SportsLiveMiniKey.EXPAND, sportsLiveMiniKey(ok, down = true, repeatCount = 0))
            assertEquals(SportsLiveMiniKey.CONSUME, sportsLiveMiniKey(ok, down = true, repeatCount = 1))
            assertEquals(SportsLiveMiniKey.CONSUME, sportsLiveMiniKey(ok, down = false, repeatCount = 0))
        }
    }

    @Test
    fun `Back closes on release and its press is eaten`() {
        assertEquals(SportsLiveMiniKey.CONSUME, sportsLiveMiniKey(KeyEvent.KEYCODE_BACK, down = true, repeatCount = 0))
        assertEquals(SportsLiveMiniKey.CLOSE, sportsLiveMiniKey(KeyEvent.KEYCODE_BACK, down = false, repeatCount = 0))
    }

    @Test
    fun `D-pad moves and other keys keep their normal handling`() {
        for (key in listOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        )) {
            assertNull(sportsLiveMiniKey(key, down = true, repeatCount = 0))
            assertNull(sportsLiveMiniKey(key, down = false, repeatCount = 0))
        }
    }
}
