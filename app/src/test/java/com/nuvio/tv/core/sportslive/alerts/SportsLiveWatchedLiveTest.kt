package com.nuvio.tv.core.sportslive.alerts

import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.START
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.live
import com.nuvio.tv.core.sportslive.scores.ResultSeen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** live.23 review S5: zapping past a followed team's live game does not end its spoiler protection. */
class SportsLiveWatchedLiveTest {

    private val event = live(10, 12)

    @Test
    fun `a followed team's game zapped past for a few seconds keeps its protection`() {
        val seen = ResultSeen()
        // Two alert ticks 5 s apart while the zap passes over it.
        seen.onLiveTick(listOf(event.eventId), START)
        seen.onLiveTick(listOf(event.eventId), START + 5_000L)
        assertTrue(SportsLiveAlerts.watchedLongEnough(listOf(event), seen::liveWatchedMs).isEmpty())
    }

    @Test
    fun `a followed team's game played for 2 minutes ends its protection`() {
        val seen = ResultSeen()
        var at = START
        while (at <= START + SportsLiveAlerts.PROTECTION_WATCHED_MS) {
            seen.onLiveTick(listOf(event.eventId), at)
            at += 5_000L
        }
        assertEquals(listOf(event), SportsLiveAlerts.watchedLongEnough(listOf(event), seen::liveWatchedMs))
    }
}
