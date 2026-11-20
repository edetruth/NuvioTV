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
    private var verified: Stream? = null
    private val holds = SportsLiveKickoffHolds(probe = { item -> probes += item; outcome }, nowMs = { now }, heldLink = { verified })
    private val alert = SportsLiveAlert.Kickoff(item(followed = true), null)
    private val link = Stream(
        name = "A", title = null, description = null, url = "https://h.invalid/live.m3u8", ytId = null, infoHash = null,
        fileIdx = null, externalUrl = null, behaviorHints = null, addonName = "A", addonLogo = null
    )

    @Test
    fun `the alert is held while no link is live, then fires with the live link`() = runTest {
        holds.hold(alert)
        // Kickoff with nothing live: the tip-off heads-up, once (1b).
        assertEquals(listOf(SportsLiveKickoffHolds.Event.HeadsUp(alert)), holds.tick())
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
        assertEquals(listOf(SportsLiveKickoffHolds.Event.HeadsUp(alert), SportsLiveKickoffHolds.Event.GiveUp(alert)), events)
        // Re-checked every minute, from the kickoff to the 20th minute.
        assertEquals(21, probes.size)
        assertTrue(holds.isEmpty)
    }

    @Test
    fun `Remind me in 10 min probes once more then, and always answers`() = runTest {
        holds.remindLater(alert)
        assertEquals(emptyList<SportsLiveKickoffHolds.Event>(), holds.tick())
        now += 10 * MINUTE
        // Still nothing: the note says so again (research 4, never silence), and the hold is done.
        assertEquals(listOf(SportsLiveKickoffHolds.Event.GiveUp(alert.copy(again = true))), holds.tick())
        assertEquals(1, probes.size)
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
    fun `the heads-up comes once, never for a Remind me in 10 min probe nor a failed probe`() = runTest {
        holds.hold(alert)
        assertEquals(listOf(SportsLiveKickoffHolds.Event.HeadsUp(alert)), holds.tick())
        now += SportsLiveKickoffHolds.RECHECK_MS
        assertEquals(emptyList<SportsLiveKickoffHolds.Event>(), holds.tick())

        val other = SportsLiveKickoffHolds(probe = { null }, nowMs = { now })
        other.hold(alert)
        assertEquals(emptyList<SportsLiveKickoffHolds.Event>(), other.tick())

        val later = SportsLiveKickoffHolds(probe = { outcome }, nowMs = { now })
        later.remindLater(alert)
        now += SportsLiveKickoffHolds.REMIND_LATER_MS
        assertTrue(later.tick().none { it is SportsLiveKickoffHolds.Event.HeadsUp })
    }

    @Test
    fun `after the heads-up the hold always follows up, even when every later probe fails`() = runTest {
        holds.hold(alert)
        assertEquals(listOf(SportsLiveKickoffHolds.Event.HeadsUp(alert)), holds.tick())
        // The addon stops answering after the heads-up: still the note at the 20th minute, never silence.
        outcome = null
        val events = ArrayList<SportsLiveKickoffHolds.Event>()
        while (!holds.isEmpty && now <= START + 2 * SportsLiveKickoffHolds.GIVE_UP_MS) {
            now += SportsLiveKickoffHolds.RECHECK_MS
            events += holds.tick()
        }
        assertEquals(listOf(SportsLiveKickoffHolds.Event.GiveUp(alert)), events)
    }

    @Test
    fun `the heads-up's only quiet end is the game going on screen`() = runTest {
        holds.hold(alert)
        assertEquals(listOf(SportsLiveKickoffHolds.Event.HeadsUp(alert)), holds.tick())
        now += SportsLiveKickoffHolds.RECHECK_MS
        // The viewer is watching it: the promise is kept by the game itself.
        assertEquals(emptyList<SportsLiveKickoffHolds.Event>(), holds.tick { true })
        assertTrue(holds.isEmpty)
    }

    @Test
    fun `a link verified meanwhile turns the heads-up into the Watch now prompt at once`() = runTest {
        holds.hold(alert)
        assertEquals(listOf(SportsLiveKickoffHolds.Event.HeadsUp(alert)), holds.tick())
        // The my-games checks verify a link 10 s later: the prompt fires on the next tick, with no probe of its own.
        now += 10_000L
        verified = link
        assertEquals(listOf(SportsLiveKickoffHolds.Event.Fire(alert, link)), holds.tick())
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
