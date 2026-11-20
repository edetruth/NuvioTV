package com.nuvio.tv.ui.screens.sportslive.mini

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `every arrow hands focus back to the content, held or not`() {
        for (key in listOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_RIGHT
        )) {
            assertEquals(SportsLiveMiniKey.RELEASE, sportsLiveMiniKey(key, down = true, repeatCount = 0))
            assertEquals(SportsLiveMiniKey.RELEASE, sportsLiveMiniKey(key, down = true, repeatCount = 3))
            assertEquals(SportsLiveMiniKey.CONSUME, sportsLiveMiniKey(key, down = false, repeatCount = 0))
        }
    }

    @Test
    fun `other keys keep their normal handling`() {
        assertNull(sportsLiveMiniKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, down = true, repeatCount = 0))
        assertNull(sportsLiveMiniKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, down = false, repeatCount = 0))
    }

    @Test
    fun `focus reaches the mini player only from a move out of the content`() {
        // The content still has focus (its change comes after the mini player's), or just lost it.
        assertTrue(sportsLiveMiniFocusInvited(contentFocused = true, contentLeftAtMs = 0, nowMs = 10_000))
        assertTrue(sportsLiveMiniFocusInvited(contentFocused = false, contentLeftAtMs = 9_800, nowMs = 10_000))
        // From nowhere: the screen had nothing focused for a while (BUG13: a page that opened with no focus).
        assertFalse(sportsLiveMiniFocusInvited(contentFocused = false, contentLeftAtMs = 2_000, nowMs = 10_000))
        assertFalse(sportsLiveMiniFocusInvited(contentFocused = false, contentLeftAtMs = Long.MIN_VALUE / 2, nowMs = 10_000))
    }
}
