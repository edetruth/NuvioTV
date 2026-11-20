package com.nuvio.tv.core.sportslive.report

import com.nuvio.tv.ui.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Report a problem (#172, coordinator decision 4): the QR dialog opens after a remote save only with adb off and no video. */
class SportsLiveReportQrPromptTest {

    @Test
    fun `adb off and no video opens the qr dialog`() {
        assertTrue(SportsLiveReportQrPrompt.opensAfterSave(adbOn = false, route = "home", miniPlaying = false))
        assertTrue(SportsLiveReportQrPrompt.opensAfterSave(adbOn = false, route = "sports_live", miniPlaying = false))
        assertTrue(SportsLiveReportQrPrompt.opensAfterSave(adbOn = false, route = null, miniPlaying = false))
    }

    @Test
    fun `adb on never opens it`() {
        assertFalse(SportsLiveReportQrPrompt.opensAfterSave(adbOn = true, route = "home", miniPlaying = false))
    }

    @Test
    fun `the full player, dual screen and the mini player never open it`() {
        assertFalse(SportsLiveReportQrPrompt.opensAfterSave(adbOn = false, route = Screen.Player.route, miniPlaying = false))
        assertFalse(SportsLiveReportQrPrompt.opensAfterSave(adbOn = false, route = Screen.SportsLiveDual.route, miniPlaying = false))
        assertFalse(SportsLiveReportQrPrompt.opensAfterSave(adbOn = false, route = "home", miniPlaying = true))
    }

    @Test
    fun `the playback routes match the app's own`() {
        assertTrue(Screen.Player.route.startsWith(SportsLiveReportQrPrompt.PLAYER_ROUTE_PREFIX))
        assertEquals(SportsLiveReportQrPrompt.DUAL_ROUTE, Screen.SportsLiveDual.route)
        assertFalse(SportsLiveReportQrPrompt.isPlayback(Screen.SportsLiveGame.route))
    }
}
