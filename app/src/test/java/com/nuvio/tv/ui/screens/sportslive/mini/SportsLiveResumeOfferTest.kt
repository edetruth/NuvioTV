package com.nuvio.tv.ui.screens.sportslive.mini

import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.ui.screens.sportslive.SportsLiveZapSession
import com.nuvio.tv.ui.screens.sportslive.dual.dualItem
import com.nuvio.tv.ui.screens.sportslive.dual.dualStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #148: Back from a paused game starts no mini player; Live now offers Resume for 30 min instead. */
@OptIn(ExperimentalCoroutinesApi::class)
class SportsLiveResumeOfferTest {

    private val a = dualItem("a")
    private val b = dualItem("b")
    private val stream = dualStream("a")
    private val session = SportsLiveZapSession(listOf(a, b), 0, SportsLiveTab.LIVE_NOW, null, false)

    private fun TestScope.controller(): SportsLiveMiniPlayerController = SportsLiveMiniPlayerController(backgroundScope)

    @Test
    fun `exit while paused starts no mini player and offers Resume on the same game and link`() = runTest {
        val mini = controller()
        var released = 0
        mini.onStopped = { released++ }

        mini.onBackFromPlayer("a", session, stream, paused = true, nowMs = 1_000L)

        assertEquals(SportsLiveMiniState.Idle, mini.state.value)
        assertFalse(mini.isActive)
        assertEquals(0, released)
        assertEquals(SportsLiveResumeOffer(a, stream, 1_000L), mini.resume.value)
    }

    @Test
    fun `exit while playing starts the mini player and offers no Resume`() = runTest {
        val mini = controller()

        mini.onBackFromPlayer("a", session, stream, paused = false)

        assertTrue(mini.isActive)
        assertNull(mini.resume.value)
    }

    @Test
    fun `a playing exit ends an earlier Resume offer`() = runTest {
        val mini = controller()
        mini.onBackFromPlayer("a", session, stream, paused = true)

        mini.onBackFromPlayer("a", session, stream, paused = false)

        assertTrue(mini.isActive)
        assertNull(mini.resume.value)
    }

    @Test
    fun `a paused exit without a session or a playable stream offers nothing`() = runTest {
        val mini = controller()

        mini.onBackFromPlayer("movie", null, stream, paused = true)
        mini.onBackFromPlayer("a", session, null, paused = true)

        assertNull(mini.resume.value)
    }

    @Test
    fun `the Resume offer expires after 30 min`() = runTest {
        val mini = controller()
        mini.onBackFromPlayer("a", session, stream, paused = true)

        advanceTimeBy(SportsLiveMiniPlayerController.RESUME_TTL_MS - 1)
        runCurrent()
        assertNotNull(mini.resume.value)

        advanceTimeBy(2)
        runCurrent()
        assertNull(mini.resume.value)
    }

    @Test
    fun `an offer also reads as expired from its own time`() {
        val offer = SportsLiveResumeOffer(a, stream, atMs = 0L)

        assertFalse(offer.expiredAt(SportsLiveMiniPlayerController.RESUME_TTL_MS - 1))
        assertTrue(offer.expiredAt(SportsLiveMiniPlayerController.RESUME_TTL_MS))
    }

    @Test
    fun `a new offer gets its own 30 min and the old timer does not end it`() = runTest {
        val mini = controller()
        mini.onBackFromPlayer("a", session, stream, paused = true, nowMs = 0L)
        advanceTimeBy(20 * 60 * 1000L)

        mini.onBackFromPlayer("a", session, stream, paused = true, nowMs = 20 * 60 * 1000L)
        advanceTimeBy(15 * 60 * 1000L)
        runCurrent()

        assertEquals(20 * 60 * 1000L, mini.resume.value?.atMs)
    }

    @Test
    fun `dismiss ends the offer`() = runTest {
        val mini = controller()
        mini.onBackFromPlayer("a", session, stream, paused = true)

        mini.dismissResume()

        assertNull(mini.resume.value)
    }
}
