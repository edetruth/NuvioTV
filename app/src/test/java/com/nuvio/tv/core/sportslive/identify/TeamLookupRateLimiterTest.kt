package com.nuvio.tv.core.sportslive.identify

import org.junit.Assert.assertEquals
import org.junit.Test

/** #168: lookups are spaced, capped per day and paused after errors. */
class TeamLookupRateLimiterTest {

    private var clock = 1_000_000L
    private val limiter = TeamLookupRateLimiter(now = { clock })

    @Test
    fun `requests are two seconds apart`() {
        assertEquals(0L, limiter.waitMs())
        limiter.onRequest()
        assertEquals(2_000L, limiter.waitMs())
        clock += 500
        assertEquals(1_500L, limiter.waitMs())
        clock += 1_500
        assertEquals(0L, limiter.waitMs())
    }

    @Test
    fun `the daily cap holds until a day after the first request`() {
        val capped = TeamLookupRateLimiter(now = { clock }, dailyCap = 3)
        val first = clock
        repeat(3) {
            assertEquals(0L, capped.waitMs())
            capped.onRequest()
            capped.onSuccess()
            clock += 2_000
        }
        val day = 24 * 60 * 60_000L
        assertEquals(first + day - clock, capped.waitMs())
        clock = first + day
        assertEquals(0L, capped.waitMs())
        capped.onRequest()
        assertEquals(2_000L, capped.waitMs())
    }

    @Test
    fun `three failures in a row pause the lookups for half an hour`() {
        repeat(2) {
            limiter.onRequest()
            limiter.onFailure(rateLimited = false)
            clock += 2_000
        }
        assertEquals(0L, limiter.waitMs())
        // A success in between starts the count again.
        limiter.onRequest()
        limiter.onSuccess()
        clock += 2_000
        repeat(3) {
            limiter.onRequest()
            limiter.onFailure(rateLimited = false)
            if (it < 2) clock += 2_000
        }
        assertEquals(TeamLookupRateLimiter.PAUSE_MS, limiter.waitMs())
        clock += TeamLookupRateLimiter.PAUSE_MS
        assertEquals(0L, limiter.waitMs())
    }

    @Test
    fun `a too many requests answer pauses at once`() {
        limiter.onRequest()
        limiter.onFailure(rateLimited = true)
        assertEquals(TeamLookupRateLimiter.PAUSE_MS, limiter.waitMs())
    }
}
