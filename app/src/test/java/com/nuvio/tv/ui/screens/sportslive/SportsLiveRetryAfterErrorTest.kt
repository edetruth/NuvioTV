package com.nuvio.tv.ui.screens.sportslive

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** TV report #14: the in-player guide's lineup must never stop updating because one update threw. */
@OptIn(ExperimentalCoroutinesApi::class)
class SportsLiveRetryAfterErrorTest {

    @Test
    fun `a list built from live scores keeps updating after one update throws`() = runTest {
        val scores = MutableStateFlow(1)
        var failNext = false
        val errors = mutableListOf<Throwable>()
        val lineup = scores
            .map { score -> if (failNext) { failNext = false; error("one bad update") } else "score $score" }
            .retryAfterError(1_000L) { errors += it }
            .stateIn(backgroundScope, SharingStarted.Eagerly, null)
        runCurrent()
        assertEquals("score 1", lineup.value)

        failNext = true
        scores.value = 2
        runCurrent()
        // The failed update leaves the last list up, and is told.
        assertEquals("score 1", lineup.value)
        assertEquals(1, errors.size)

        // Collected again after the wait: the newest scores, and every later change, come through.
        advanceTimeBy(1_001L); runCurrent()
        assertEquals("score 2", lineup.value)
        scores.value = 3
        runCurrent()
        assertEquals("score 3", lineup.value)
    }
}
