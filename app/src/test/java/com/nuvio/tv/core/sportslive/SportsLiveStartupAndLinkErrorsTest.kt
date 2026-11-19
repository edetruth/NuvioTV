package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.sportslive.SportsLiveStreamHealth.Problem
import com.nuvio.tv.domain.model.Stream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #138: start-up timeout, behind-live-window, dead hosts, viewer-safe text and the per-addon lookup timeout. */
@OptIn(ExperimentalCoroutinesApi::class)
class SportsLiveStartupAndLinkErrorsTest {

    // --- Start-up timeout ---

    @Test
    fun `a link with no first frame times out after 15 s`() = runTest {
        val waited = async { SportsLiveStartupTimeout.await { false } }
        advanceTimeBy(14_999)
        runCurrent()
        assertFalse(waited.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(waited.isCompleted)
        assertEquals(15_000L, currentTime)
    }

    @Test
    fun `a link whose request already failed times out after 8 s`() = runTest {
        var failed = false
        val waited = async { SportsLiveStartupTimeout.await { failed } }
        advanceTimeBy(3_000)
        failed = true
        advanceTimeBy(4_999)
        runCurrent()
        assertFalse(waited.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(waited.isCompleted)
        assertEquals(8_000L, currentTime)
    }

    @Test
    fun `a request failing after 8 s times the link out at once`() = runTest {
        var failed = false
        val waited = async { SportsLiveStartupTimeout.await { failed } }
        advanceTimeBy(10_000)
        runCurrent()
        assertFalse(waited.isCompleted)
        failed = true
        advanceTimeBy(SportsLiveStartupTimeout.CHECK_EVERY_MS)
        runCurrent()
        assertTrue(waited.isCompleted)
        assertTrue(currentTime < SportsLiveStreamThresholds.NO_FIRST_FRAME_MS)
    }

    @Test
    fun `the start-up limits`() {
        assertEquals(15_000L, SportsLiveStartupTimeout.limitMs(requestFailed = false))
        assertEquals(8_000L, SportsLiveStartupTimeout.limitMs(requestFailed = true))
        assertFalse(SportsLiveStartupTimeout.isExpired(7_999, requestFailed = true))
        assertTrue(SportsLiveStartupTimeout.isExpired(8_000, requestFailed = true))
    }

    // --- Behind the live window ---

    private var now = 1_000_000L

    @Test
    fun `falling behind the live window recovers in place and is not an error`() {
        val health = SportsLiveStreamHealth(nowMs = { now })
        health.onFirstFrame("https://example.invalid/a.m3u8")
        assertNull(health.onBehindLiveWindow())
        now += 20_000
        assertNull(health.onBehindLiveWindow())
        // Not counted as an error: one real error later is still a single error, not a burst.
        assertEquals(Problem.ERROR, health.onError())
    }

    @Test
    fun `falling behind three times in 60 s is a stall`() {
        val health = SportsLiveStreamHealth(nowMs = { now })
        health.onFirstFrame("https://example.invalid/a.m3u8")
        assertNull(health.onBehindLiveWindow())
        now += 25_000
        assertNull(health.onBehindLiveWindow())
        now += 25_000
        val problem = health.onBehindLiveWindow()
        assertEquals(Problem.BEHIND_LIVE_WINDOW_REPEATED, problem)
        assertTrue(problem!!.isStall)
        assertTrue(problem.retriesSameLink)
    }

    @Test
    fun `three falls spread over more than 60 s stay in place`() {
        val health = SportsLiveStreamHealth(nowMs = { now })
        health.onFirstFrame("https://example.invalid/a.m3u8")
        assertNull(health.onBehindLiveWindow())
        now += 35_000
        assertNull(health.onBehindLiveWindow())
        now += 35_000
        assertNull(health.onBehindLiveWindow())
    }

    @Test
    fun `another link forgets earlier falls`() {
        val health = SportsLiveStreamHealth(nowMs = { now })
        health.onFirstFrame("https://example.invalid/a.m3u8")
        health.onBehindLiveWindow()
        health.onBehindLiveWindow()
        health.onFirstFrame("https://example.invalid/b.m3u8")
        assertNull(health.onBehindLiveWindow())
    }

    // --- Dead hosts ---

    @Test
    fun `an unknown host anywhere in the causes is a dead link`() {
        assertTrue(SportsLiveLinkErrors.isDeadHost(UnknownHostException("x")))
        assertTrue(SportsLiveLinkErrors.isDeadHost(IOException("load", IOException("wrap", UnknownHostException()))))
        assertFalse(SportsLiveLinkErrors.isDeadHost(SocketTimeoutException()))
        assertFalse(SportsLiveLinkErrors.isDeadHost(null))
    }

    @Test
    fun `a dead link skips to the next link with no retry`() {
        val ladder = SportsLiveRecoveryLadder(SportsLiveRecoveryBudget(nowMs = { now }), nowMs = { now })
        assertEquals(
            SportsLiveRecoveryLadder.Step.NEXT_LINK,
            ladder.decide("game", "https://example.invalid/a.m3u8", Problem.DEAD_LINK)
        )
        assertFalse(Problem.DEAD_LINK.retriesSameLink)
    }

    // --- Viewer-safe error text ---

    @Test
    fun `an error naming a host or url is replaced`() {
        val fallback = "This link isn't working"
        assertEquals(fallback, SportsLiveViewerText.forViewer("Unable to resolve host \"cdn.example.invalid\"", fallback))
        assertEquals(fallback, SportsLiveViewerText.forViewer("Failed https://example.invalid/a.m3u8", fallback))
        assertEquals(fallback, SportsLiveViewerText.forViewer("connect to 10.0.0.2 failed", fallback))
        assertEquals(fallback, SportsLiveViewerText.forViewer(null, fallback))
    }

    @Test
    fun `a message with no host is kept, class names included`() {
        val fallback = "x"
        assertEquals("Source stalled", SportsLiveViewerText.forViewer("Source stalled", fallback))
        assertEquals(
            "java.net.SocketTimeoutException: timeout",
            SportsLiveViewerText.forViewer("java.net.SocketTimeoutException: timeout", fallback)
        )
    }

    // --- Per-addon lookup timeout ---

    private fun stream(name: String) = Stream(
        name = name, title = null, description = null, url = "https://example.invalid/$name.m3u8",
        ytId = null, infoHash = null, fileIdx = null, externalUrl = null, behaviorHints = null,
        addonName = "Addon", addonLogo = null
    )

    @Test
    fun `an addon slower than 6 s gives nothing`() = runTest {
        val fetch = SportsLiveAddonFetch()
        val result = fetch.fetch(
            addonKey = "slow",
            streams = { delay(15_000); NetworkResult.Success(listOf(stream("late"))) },
            inlineFromMeta = { emptyList() }
        )
        assertNull(result)
        assertEquals(SportsLiveStreamThresholds.ADDON_LOOKUP_TIMEOUT_MS, currentTime)
    }

    @Test
    fun `an addon within 6 s answers`() = runTest {
        val fetch = SportsLiveAddonFetch()
        val result = fetch.fetch(
            addonKey = "ok",
            streams = { delay(5_900); NetworkResult.Success(listOf(stream("a"))) },
            inlineFromMeta = { delay(10_000); emptyList() }
        )
        assertEquals(NetworkResult.Success(listOf(stream("a"))), result)
        // The meta fallback was not waited for.
        assertEquals(5_900L, currentTime)
    }

    @Test
    fun `an empty answer's meta fallback runs alongside, not after`() = runTest {
        val fetch = SportsLiveAddonFetch()
        val result = fetch.fetch(
            addonKey = "inline",
            streams = { delay(3_000); NetworkResult.Success(emptyList()) },
            inlineFromMeta = { delay(4_000); listOf(stream("inline")) }
        )
        assertEquals(NetworkResult.Success(listOf(stream("inline"))), result)
        assertEquals(4_000L, currentTime)
    }

    @Test
    fun `an addon whose meta had no streams is not asked for it again`() = runTest {
        val fetch = SportsLiveAddonFetch()
        var metaCalls = 0
        repeat(2) {
            fetch.fetch(
                addonKey = "empty",
                streams = { NetworkResult.Success(emptyList()) },
                inlineFromMeta = { metaCalls++; emptyList() }
            )
        }
        assertEquals(1, metaCalls)
    }

    @Test
    fun `sports ids are remembered for the repository`() {
        val fetch = SportsLiveAddonFetch()
        assertFalse(fetch.isSports("game"))
        fetch.markSports("game")
        assertTrue(fetch.isSports("game"))
    }
}
