package com.nuvio.tv.ui.screens.sportslive

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** #211: Sports & Live sends the user Home only once it has stayed unavailable for the grace period. */
@OptIn(ExperimentalCoroutinesApi::class)
class SportsLiveRouteGraceTest {

    private val state = MutableStateFlow<Pair<Boolean?, Boolean>>(true to true)

    /** Counts redirects; collected in backgroundScope, so it stops when the test ends. */
    private fun TestScope.redirects(): () -> Int {
        var count = 0
        backgroundScope.launch { sportsLiveUnavailableRedirects(state).collect { count++ } }
        runCurrent()
        return { count }
    }

    @Test
    fun `the section staying unavailable on its route goes Home after the grace period`() = runTest {
        val count = redirects()
        state.value = false to true
        advanceTimeBy(SPORTS_LIVE_UNAVAILABLE_GRACE_MS - 1)
        runCurrent()
        assertEquals(0, count())
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, count())
    }

    @Test
    fun `a short drop that recovers within the grace period never goes Home`() = runTest {
        val count = redirects()
        state.value = false to true
        advanceTimeBy(3_000)
        state.value = true to true
        advanceTimeBy(SPORTS_LIVE_UNAVAILABLE_GRACE_MS * 2)
        runCurrent()
        assertEquals(0, count())
    }

    @Test
    fun `leaving the route meanwhile, as for a game, cancels it, and coming back starts the wait again`() = runTest {
        val count = redirects()
        state.value = false to true
        advanceTimeBy(3_000)
        state.value = false to false
        advanceTimeBy(SPORTS_LIVE_UNAVAILABLE_GRACE_MS * 2)
        runCurrent()
        assertEquals(0, count())
        // Back from the game onto the section, still unavailable: a full grace period again.
        state.value = false to true
        advanceTimeBy(SPORTS_LIVE_UNAVAILABLE_GRACE_MS - 1)
        runCurrent()
        assertEquals(0, count())
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, count())
    }

    @Test
    fun `availability not known yet, or another route, never goes Home`() = runTest {
        val count = redirects()
        state.value = null to true
        advanceTimeBy(SPORTS_LIVE_UNAVAILABLE_GRACE_MS * 2)
        state.value = false to false
        advanceTimeBy(SPORTS_LIVE_UNAVAILABLE_GRACE_MS * 2)
        runCurrent()
        assertEquals(0, count())
    }
}
