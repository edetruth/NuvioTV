package com.nuvio.tv.ui.screens.sportslive.mini

import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.ui.screens.sportslive.SportsLiveZapSession
import com.nuvio.tv.ui.screens.sportslive.SportsLiveZapSessions
import com.nuvio.tv.ui.screens.sportslive.dual.dualItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsLivePipTest {

    @After
    fun tearDown() {
        SportsLiveZapSessions.clear()
        SportsLivePip.onModeChanged(false)
    }

    @Test
    fun `eligible only with the feature and Sports and Live playing`() {
        assertTrue(sportsLivePipEligible(36, hasPipFeature = true, sportsFullPlayerShowing = true, miniPlayerActive = false))
        assertTrue(sportsLivePipEligible(36, hasPipFeature = true, sportsFullPlayerShowing = false, miniPlayerActive = true))
        assertFalse("no sports playback", sportsLivePipEligible(36, true, sportsFullPlayerShowing = false, miniPlayerActive = false))
        assertFalse("no PiP feature", sportsLivePipEligible(36, false, sportsFullPlayerShowing = true, miniPlayerActive = true))
        assertFalse("before API 26", sportsLivePipEligible(25, true, sportsFullPlayerShowing = true, miniPlayerActive = true))
    }

    @Test
    fun `a full player counts only while it plays the zap session's game`() {
        val a = dualItem("a")
        val token = SportsLivePip.attachFullPlayer("a") {}
        assertFalse("no session", SportsLivePip.sportsFullPlayerShowing())

        SportsLiveZapSessions.start(SportsLiveZapSession(listOf(a), 0, SportsLiveTab.LIVE_NOW, null, false))
        assertTrue(SportsLivePip.sportsFullPlayerShowing())

        val movie = SportsLivePip.attachFullPlayer("movie") {}
        assertFalse("a movie player", SportsLivePip.sportsFullPlayerShowing())

        // The older player's late detach leaves the newer one registered.
        SportsLivePip.detachFullPlayer(token)
        SportsLivePip.detachFullPlayer(movie)
        assertFalse(SportsLivePip.sportsFullPlayerShowing())
    }

    @Test
    fun `closing the PiP window closes the full player and leaves PiP`() {
        var closed = 0
        val token = SportsLivePip.attachFullPlayer("a") { closed++ }
        SportsLivePip.onModeChanged(true)

        SportsLivePip.onClosed()

        assertEquals(1, closed)
        assertFalse(SportsLivePip.inPip.value)
        SportsLivePip.detachFullPlayer(token)
    }
}
