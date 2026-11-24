package com.nuvio.tv.ui.screens.sportslive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #461 (Fable RC F5): a late failure never pulls focus; recovery moves it only off the card that went away. */
class SportsLivePageFocusTest {

    @Test
    fun `Try again takes focus only while nothing on the page holds it`() {
        // First load failed: the page has no focus yet, Try again takes it.
        assertTrue(SportsLivePageFocus.failureTakesFocus(pageHasFocus = false))
        // Stats failing seconds later while the user is on Watch: focus stays there (Down reaches Try again).
        assertFalse(SportsLivePageFocus.failureTakesFocus(pageHasFocus = true))
    }

    @Test
    fun `on recovery focus moves to the header only if it was on the card that went away`() {
        assertTrue(SportsLivePageFocus.recoveryMovesFocus(cardHadFocus = true))
        assertFalse(SportsLivePageFocus.recoveryMovesFocus(cardHadFocus = false))
    }
}
