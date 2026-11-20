package com.nuvio.tv.core.sportslive.alerts

import com.nuvio.tv.core.sportslive.SportsLiveLiveCheck
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.MINUTE
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.START
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.item
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsLiveKickoffHoldsTest {

    private var now = START
    private val probes = ArrayList<SportsLiveItem>()
    private var outcome: SportsLiveLiveCheck.Outcome? = SportsLiveLiveCheck.Outcome(live = null, checkable = true)
    private val holds = SportsLiveKickoffHolds(probe = { item -> probes += item; outcome }, nowMs = { now })
    private val alert = SportsLiveAlert.Kickoff(item(followed = true), null)
    private val link = Stream(
        name = "A", title = null, description = null, url = "https://h.invalid/live.m3u8", ytId = null, infoHash = null,
        fileIdx = null, externalUrl = null, behaviorHints = null, addonName = "A", addonLogo = null
    )

    @Test
    fun `the alert is held while no link is live, then fires with the live link`() = runTest {
        holds.hold(alert)
        assertEquals(emptyList<SportsLiveKickoffHolds.Event>(), holds.tick())
        assertEquals(1, probes.size)
        // Not due yet: no new probe.
        now += 30_000L
        assertEquals(emptyList<SportsLiveKickoffHolds.Event>(), holds.tick())
        assertEquals(1, probes.size)
        // A minute on: probed again, and a link is live now.
        now += 30_000L
        outcome = SportsLiveLiveCheck.Outcome(live = link, checkable = true)
        assertEquals(listOf(SportsLiveKickoffHolds.Event.Fire(alert, link)), holds.tick())
        assertEquals(2, probes.size)
        assertTrue(holds.isEmpty)
    }

    @Test
    fun `nothing live within 20 minutes gives the quiet note once`() = runTest {
        holds.hold(alert)
        var events = emptyList<SportsLiveKickoffHolds.Event>()
        while (now <= START + SportsLiveKickoffHolds.GIVE_UP_MS) {
            events = events + holds.tick()
            now += SportsLiveKickoffHolds.RECHECK_MS
        }
        assertEquals(listOf(SportsLiveKickoffHolds.Event.GiveUp(alert)), events)
        // Re-checked every minute, from the kickoff to the 20th minute.
        assertEquals(21, probes.size)
        assertTrue(holds.isEmpty)
    }

    @Test
    fun `Remind me in 10 min probes once more then, and fires only when live`() = runTest {
        holds.remindLater(alert)
        assertEquals(emptyList<SportsLiveKickoffHolds.Event>(), holds.tick())
        now += 10 * MINUTE
        assertEquals(emptyList<SportsLiveKickoffHolds.Event>(), holds.tick())
        assertEquals(1, probes.size)
        // Still nothing: done, no second note.
        assertTrue(holds.isEmpty)

        holds.remindLater(alert)
        now += 10 * MINUTE
        outcome = SportsLiveLiveCheck.Outcome(live = link, checkable = true)
        assertEquals(listOf(SportsLiveKickoffHolds.Event.Fire(alert.copy(again = true), link)), holds.tick())
    }

    @Test
    fun `a game whose links cannot be checked fires unchecked at once`() = runTest {
        outcome = SportsLiveLiveCheck.Outcome(live = null, checkable = false)
        holds.hold(alert)
        assertEquals(listOf(SportsLiveKickoffHolds.Event.Fire(alert, null)), holds.tick())
    }

    @Test
    fun `a failed probe is retried on the next minute, and a game on screen is dropped unprobed`() = runTest {
        outcome = null
        holds.hold(alert)
        assertEquals(emptyList<SportsLiveKickoffHolds.Event>(), holds.tick())
        now += SportsLiveKickoffHolds.RECHECK_MS
        assertEquals(emptyList<SportsLiveKickoffHolds.Event>(), holds.tick { true })
        assertEquals(1, probes.size)
        assertTrue(holds.isEmpty)
    }

    @Test
    fun `one game is held once`() = runTest {
        holds.hold(alert)
        holds.hold(alert.copy(item = item(id = "other-copy", addonId = "b", followed = true)))
        holds.tick()
        assertEquals(1, probes.size)
    }
}
