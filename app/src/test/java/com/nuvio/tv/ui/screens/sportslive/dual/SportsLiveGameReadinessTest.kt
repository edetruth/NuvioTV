package com.nuvio.tv.ui.screens.sportslive.dual

import com.nuvio.tv.core.sportslive.SportsLiveAddonFetch
import com.nuvio.tv.core.sportslive.SportsLiveReadyMark
import com.nuvio.tv.core.sportslive.SportsLiveGameReadiness
import com.nuvio.tv.core.sportslive.SportsLiveStreamThresholds
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #147, 1b: the "▶ Ready" / "No stream yet" marks, the held link, and how they feed "Next live game". */
class SportsLiveGameReadinessTest {

    private fun link(name: String) = Stream(
        name = name, title = null, description = null, url = "https://cdn.example.invalid/$name.m3u8", ytId = null, infoHash = null,
        fileIdx = null, externalUrl = null, behaviorHints = null, addonName = "addon", addonLogo = null
    )

    private val lineup = listOf("a", "b", "c", "d").map(::dualItem)
    private fun item(id: String) = lineup.first { it.id == id }
    private var now = 5_000_000L
    private val readiness = SportsLiveGameReadiness(nowMs = { now })

    /** Next live game after [current] failed, with the marks as the real source of ✓ and ⚠. */
    private fun next(current: SportsLiveItem): SportsLiveItem? = SportsLiveSecondGame.nextAfterFailure(
        current = current,
        lineup = lineup,
        scores = emptyMap(),
        finishedKeys = emptySet(),
        tried = setOf(current.id),
        knownGood = { readiness.of(it) == SportsLiveReadyMark.READY },
        noStream = readiness.marks.value.filterValues { it == SportsLiveReadyMark.NO_STREAM }.keys
    )

    @Test
    fun `next live game goes to a game marked ready first`() {
        readiness.ready("d", link("d"))
        assertEquals("d", next(item("a"))?.id)
    }

    @Test
    fun `next live game passes over a game with no working stream`() {
        readiness.noStream("b")
        assertEquals("c", next(item("a"))?.id)
    }

    @Test
    fun `a game with no working stream is still offered when no other is left`() {
        listOf("b", "c", "d").forEach { readiness.noStream(it) }
        assertEquals("b", next(item("a"))?.id)
    }

    @Test
    fun `No stream yet stands about three minutes and Ready as long as its held link`() {
        readiness.noStream("b")
        readiness.ready("c", link("c"))
        now += SportsLiveStreamThresholds.CHECK_AHEAD_CACHE_MS
        assertNull(readiness.of(item("b")))
        assertEquals(SportsLiveReadyMark.READY, readiness.of(item("c")))
        assertEquals(link("c"), readiness.heldLink(item("c")))

        now = 5_000_000L + SportsLiveStreamThresholds.READY_HOLD_MS
        assertNull(readiness.of(item("c")))
        assertNull(readiness.heldLink(item("c")))
        assertNull(readiness.heldAt("c"))
        readiness.prune()
        assertTrue(readiness.marks.value.isEmpty())
        // With the marks gone, b is the next game again.
        assertEquals("b", next(item("a"))?.id)
    }

    @Test
    fun `Ready shows only while its verified link is held`() {
        readiness.ready("c", link("c"))
        assertEquals(SportsLiveReadyMark.READY, readiness.of(item("c")))

        // Another link of the game failing leaves it Ready; its held link failing takes the mark away at once.
        readiness.linkFailed("c", "https://cdn.example.invalid/other.m3u8")
        assertEquals(link("c"), readiness.heldLink(item("c")))
        readiness.linkFailed("c", link("c").url)
        assertNull(readiness.of(item("c")))
        assertNull(readiness.heldLink(item("c")))
        assertTrue(readiness.marks.value.isEmpty())
    }

    @Test
    fun `verifying the held link again keeps it Ready from then on`() {
        readiness.ready("c", link("c"))
        now += SportsLiveStreamThresholds.READY_REFRESH_MS
        readiness.ready("c", link("c"))
        assertEquals(now, readiness.heldAt("c"))
        now += SportsLiveStreamThresholds.READY_HOLD_MS - 1
        assertEquals(SportsLiveReadyMark.READY, readiness.of(item("c")))
    }

    @Test
    fun `a link failing in the shared working links takes its Ready mark away`() {
        val links = com.nuvio.tv.core.sportslive.SportsLiveWorkingLinks(nowMs = { now }, readiness = readiness)
        readiness.ready("c", link("c"))
        links.failed("c", link("c").url)
        assertNull(readiness.of(item("c")))
    }

    @Test
    fun `a merged card is ready when any of its sources is`() {
        val merged = item("a").copy(alternates = listOf(item("b")))
        readiness.noStream("a")
        readiness.ready("b", link("b"))
        assertEquals(SportsLiveReadyMark.READY, readiness.of(merged))
        assertEquals(link("b"), readiness.heldLink(merged))
    }

    @Test
    fun `an addon whose median lookup is over six seconds counts as slow`() {
        val fetch = SportsLiveAddonFetch()
        listOf(6_100L, 900L, 6_200L).forEach { fetch.noteLookupMs("https://slow.example.invalid/", it) }
        listOf(500L, 14_000L, 700L).forEach { fetch.noteLookupMs("https://fast.example.invalid", it) }

        assertEquals(setOf("https://slow.example.invalid"), fetch.slowAddonKeys())
    }

    @Test
    fun `a game is checking only while its check runs, a refresh inside a check included`() = kotlinx.coroutines.test.runTest {
        val merged = item("a").copy(alternates = listOf(item("b")))
        assertTrue(readiness.checking.value.isEmpty())
        readiness.whileChecking("b") {
            // A merged card reads as checking while any of its sources is.
            assertTrue(readiness.isChecking(merged))
            assertEquals(false, readiness.isChecking(item("c")))
            readiness.whileChecking("b") { assertEquals(setOf("b"), readiness.checking.value) }
            // The inner check ended, the outer one still runs.
            assertEquals(setOf("b"), readiness.checking.value)
        }
        assertTrue(readiness.checking.value.isEmpty())
        assertEquals(false, readiness.isChecking(merged))
    }

    @Test
    fun `a failed check stops reading as checking`() = kotlinx.coroutines.test.runTest {
        runCatching { readiness.whileChecking<Unit>("c") { error("probe failed") } }
        assertTrue(readiness.checking.value.isEmpty())
        // A checked game keeps its verdict apart: no mark is not the same as checking.
        assertNull(readiness.of(item("c")))
    }

    @Test
    fun `a row reads Ready only for my games, merged sources included, and No stream yet is never gated`() {
        readiness.ready("a", link("a"))
        readiness.ready("b", link("b"))
        readiness.noStream("c")
        readiness.setMine(setOf("b"))
        assertNull(readiness.rowOf(item("a")))
        assertEquals(SportsLiveReadyMark.READY, readiness.of(item("a")))
        assertEquals(SportsLiveReadyMark.READY, readiness.rowOf(item("b")))
        assertEquals(SportsLiveReadyMark.READY, readiness.rowOf(item("d").copy(alternates = listOf(item("b")))))
        assertEquals(SportsLiveReadyMark.NO_STREAM, readiness.rowOf(item("c")))
    }
}
