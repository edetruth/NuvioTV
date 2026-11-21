package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.SportsLiveGameReadiness
import com.nuvio.tv.core.sportslive.SportsLiveMyGamesToday
import com.nuvio.tv.core.sportslive.SportsLiveReadyMark
import com.nuvio.tv.core.sportslive.SportsLiveStreamThresholds
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.MINUTE
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.START
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.item
import com.nuvio.tv.core.sportslive.alerts.GameReminders
import com.nuvio.tv.core.sportslive.alerts.RemindersState
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.ContinuationInterceptor

/** live.20 1b: the keeper checks my games only, on their schedule, and keeps a held link fresh. */
@OptIn(ExperimentalCoroutinesApi::class)
class SportsLiveReadyKeeperTest {

    private var now = START - 10 * MINUTE
    private val readiness = SportsLiveGameReadiness(nowMs = { now })
    private val checks = ArrayList<String>()
    // What rows read of each check while it ran (live.20: the faint checking mark).
    private val checkingDuring = ArrayList<Set<String>>()
    private val refreshes = ArrayList<String>()
    private val skipped = HashSet<String>()
    private var saved: Set<String> = emptySet()

    private val followed = item(name = "Pacers @ 76ers", id = "mine", followed = true)
    private val reminded = item(name = "Lakers @ Suns", id = "reminded")
    private val random = item(name = "Knicks @ Celtics", id = "random", isLive = true)
    private var cards = listOf(random, followed, reminded)
    private var reminders = RemindersState(reminders = listOf(GameReminders.of(reminded)!!))
    private var scores: Map<String, ScoreSnapshot> = emptyMap()
    private var verified: (SportsLiveItem) -> Stream? = { null }

    private fun link(item: SportsLiveItem) = Stream(
        name = item.id, title = null, description = null, url = "https://cdn.example.invalid/${item.id}.m3u8", ytId = null,
        infoHash = null, fileIdx = null, externalUrl = null, behaviorHints = null, addonName = "a", addonLogo = null
    )

    private val keeper = SportsLiveReadyKeeper(
        cards = { cards },
        reminders = { reminders },
        scores = { scores },
        savedForLater = { { it.id in saved } },
        check = { item ->
            checks += item.id
            checkingDuring += readiness.checking.value
            verified(item)?.let { readiness.ready(item.id, it) }
        },
        refresh = { item ->
            refreshes += item.id
            readiness.heldLink(item)?.let { readiness.ready(item.id, it) }
        },
        skips = { it.id in skipped },
        readiness = readiness,
        nowMs = { now }
    )

    @Test
    fun `only my games are checked, never a random one`() = runTest {
        keeper.tick()
        assertEquals(listOf("mine", "reminded"), checks.sorted())
    }

    @Test
    fun `a game saved for later is not checked`() = runTest {
        saved = setOf("mine")
        keeper.tick()
        assertEquals(listOf("reminded"), checks)
    }

    @Test
    fun `a game on a rate-limited addon is left alone`() = runTest {
        skipped += "mine"
        keeper.tick()
        assertEquals(listOf("reminded"), checks)
    }

    @Test
    fun `nothing is checked before the ten minute lead, then every five minutes while live`() = runTest {
        now = START - 11 * MINUTE
        keeper.tick()
        assertTrue(checks.isEmpty())

        now = START - 10 * MINUTE
        keeper.tick()
        assertEquals(2, checks.size)
        // A tick soon after checks nothing again.
        now += 30_000L
        keeper.tick()
        assertEquals(2, checks.size)
        now = START - 5 * MINUTE + 30_000L
        keeper.tick()
        assertEquals(4, checks.size)
    }

    @Test
    fun `a Ready game's held link is refreshed before it lapses and stays Ready`() = runTest {
        verified = { if (it.id == "mine") link(it) else null }
        keeper.tick()
        assertEquals(link(followed), readiness.heldLink(followed))

        // Ticks until long past the hold's life: the held link is verified again each time before it lapses.
        repeat(40) {
            now += SportsLiveReadyKeeper.TICK_MS
            keeper.tick()
            assertEquals(link(followed), readiness.heldLink(followed))
        }
        assertTrue(40 * SportsLiveReadyKeeper.TICK_MS > SportsLiveStreamThresholds.READY_HOLD_MS)
        assertTrue(refreshes.isNotEmpty() && refreshes.all { it == "mine" })
        // A held game is refreshed, never searched again meanwhile.
        assertEquals(1, checks.count { it == "mine" })
    }

    @Test
    fun `a game reads as checking only while its check runs`() = runTest {
        keeper.tick()
        assertEquals(listOf(setOf("mine"), setOf("reminded")), checkingDuring.sortedBy { it.firstOrNull() })
        assertTrue(readiness.checking.value.isEmpty())
        assertFalse(readiness.isChecking(followed))
    }

    @Test
    fun `Home's line counts my games today and how many are Ready`() = runTest {
        // At kickoff, so the games start today in any time zone.
        now = START
        verified = { if (it.id == "mine") link(it) else null }
        keeper.tick()
        assertEquals(SportsLiveMyGamesToday(games = 2, ready = 1), keeper.today.first())
    }

    @Test
    fun `only my games' rows read Ready, a prefetched random game's row does not`() = runTest {
        verified = { if (it.id == "mine") link(it) else null }
        keeper.tick()
        // A zap neighbour's prefetch verified the random game too.
        readiness.ready(random.id, link(random))
        assertEquals(setOf("mine", "reminded"), readiness.mine.value)
        assertEquals(SportsLiveReadyMark.READY, readiness.rowOf(followed))
        assertNull(readiness.rowOf(random))
        // The stage of the selected game may still say "Stream ready".
        assertEquals(SportsLiveReadyMark.READY, readiness.of(random))
    }

    @Test
    fun `a game no longer mine loses its row Ready at the next pass`() = runTest {
        verified = { if (it.id == "mine") link(it) else null }
        keeper.tick()
        cards = listOf(random, reminded)
        keeper.tick()
        assertNull(readiness.rowOf(followed))
    }

    @Test
    fun `the keeper's passes run on the compute dispatcher, never on the thread that started it (#207)`() = runTest {
        val compute = StandardTestDispatcher(testScheduler, name = "compute")
        val ranOn = ArrayList<ContinuationInterceptor?>()
        val offMain = SportsLiveReadyKeeper(
            cards = {
                ranOn += currentCoroutineContext()[ContinuationInterceptor]
                cards
            },
            reminders = { reminders },
            scores = { scores },
            savedForLater = { { false } },
            check = { },
            refresh = { },
            skips = { true },
            readiness = readiness,
            nowMs = { now },
            compute = compute
        )
        // Started from the test's own dispatcher, as the host starts it from the main thread.
        backgroundScope.launch { offMain.runInForeground() }
        runCurrent()
        advanceTimeBy(SportsLiveReadyKeeper.TICK_MS)
        runCurrent()
        assertEquals(2, ranOn.size)
        assertTrue(ranOn.all { it === compute })
    }
}
