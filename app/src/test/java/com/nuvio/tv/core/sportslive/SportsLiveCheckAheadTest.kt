package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #147: the in-player panel's check-ahead schedule. */
@OptIn(ExperimentalCoroutinesApi::class)
class SportsLiveCheckAheadTest {

    private val games = listOf("a", "b", "c", "d", "e").map(::game)
    private fun g(id: String) = games.first { it.id == id }

    /** Each check: (game id, when it started). */
    private val checks = mutableListOf<Pair<String, Long>>()

    private fun TestScope.checkAhead(
        skip: (SportsLiveItem) -> Boolean = { false },
        markedAt: (SportsLiveItem) -> Long? = { null },
        checkMs: Long = 100L,
        hang: Set<String> = emptySet()
    ) = SportsLiveCheckAhead(
        scope = backgroundScope,
        check = { item ->
            checks += item.id to testScheduler.currentTime
            if (item.id in hang) awaitCancellation()
            delay(checkMs)
        },
        skip = skip,
        markedAt = markedAt,
        nowMs = { testScheduler.currentTime }
    )

    private val order get() = checks.map { it.first }

    @Test
    fun `the focused game and the next game are checked first, then the rest in panel order`() = runTest {
        val ahead = checkAhead()
        ahead.update(games)
        ahead.next(g("d"))
        ahead.focus(g("c"))
        ahead.open()
        advanceTimeBy(30_000)

        assertEquals(listOf("d", "c", "a", "b", "e"), order)
    }

    @Test
    fun `the rest are trickled one at a time about five seconds apart`() = runTest {
        val ahead = checkAhead()
        ahead.update(games)
        ahead.open()
        advanceTimeBy(60_000)

        val starts = checks.map { it.second }
        assertEquals(listOf("a", "b", "c", "d", "e"), order)
        starts.zipWithNext().forEach { (first, second) -> assertTrue(second - first >= SportsLiveStreamThresholds.CHECK_AHEAD_TRICKLE_MS) }
        assertTrue(starts.zipWithNext().all { (first, second) -> second - first <= SportsLiveStreamThresholds.CHECK_AHEAD_TRICKLE_MS + 200 })
    }

    @Test
    fun `checks never overlap`() = runTest {
        val ahead = checkAhead(checkMs = 7_000L)
        ahead.update(games.take(3))
        ahead.next(g("b"))
        ahead.open()
        advanceTimeBy(60_000)

        val starts = checks.map { it.second }
        starts.zipWithNext().forEach { (first, second) -> assertTrue(second - first >= 7_000L) }
    }

    @Test
    fun `focus scrolling past rows is not checked until it rests`() = runTest {
        val ahead = checkAhead()
        ahead.open()
        runCurrent()
        ahead.focus(g("a"))
        advanceTimeBy(200)
        ahead.focus(g("b"))
        advanceTimeBy(200)
        ahead.focus(g("c"))
        advanceTimeBy(SportsLiveStreamThresholds.CHECK_AHEAD_FOCUS_DWELL_MS + 50)

        assertEquals(listOf("c"), order)
    }

    @Test
    fun `games on rate-limited or slow addons are skipped`() = runTest {
        val ahead = checkAhead(skip = { it.id == "b" || it.id == "d" })
        ahead.update(games)
        ahead.focus(g("b"))
        ahead.open()
        advanceTimeBy(60_000)

        assertEquals(listOf("a", "c", "e"), order)
    }

    @Test
    fun `a game checked within three minutes is not checked again, then it is`() = runTest {
        val ahead = checkAhead()
        ahead.update(games.take(2))
        ahead.open()
        advanceTimeBy(30_000)
        assertEquals(listOf("a", "b"), order)

        ahead.focus(g("a"))
        advanceTimeBy(60_000)
        assertEquals(listOf("a", "b"), order)

        advanceTimeBy(SportsLiveStreamThresholds.CHECK_AHEAD_CACHE_MS)
        assertEquals(listOf("a", "b", "a", "b"), order)
    }

    @Test
    fun `a game already marked elsewhere is reused, not checked`() = runTest {
        val ahead = checkAhead(markedAt = { if (it.id == "a") 0L else null })
        ahead.update(games.take(2))
        ahead.open()
        advanceTimeBy(30_000)

        assertEquals(listOf("b"), order)
    }

    @Test
    fun `closing the panel stops every check, the one in flight too`() = runTest {
        var cancelled = false
        val ahead = SportsLiveCheckAhead(
            scope = backgroundScope,
            check = { item ->
                checks += item.id to testScheduler.currentTime
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            },
            nowMs = { testScheduler.currentTime }
        )
        ahead.update(games)
        ahead.open()
        runCurrent()
        assertEquals(listOf("a"), order)

        ahead.close()
        runCurrent()
        advanceTimeBy(60_000)

        assertTrue(cancelled)
        assertEquals(listOf("a"), order)
        assertTrue(!ahead.isOpen)
    }

    @Test
    fun `reopening starts again from the focused and next games`() = runTest {
        val ahead = checkAhead()
        ahead.update(games)
        ahead.open()
        advanceTimeBy(1_000)
        ahead.close()
        assertEquals(listOf("a"), order)

        ahead.next(g("e"))
        ahead.open()
        advanceTimeBy(1_000)
        assertEquals(listOf("a", "e"), order)
    }

    private fun game(id: String) = SportsLiveItem(
        meta = MetaPreview(
            id = id, type = ContentType.UNKNOWN, rawType = "tv", name = id, poster = null, posterShape = PosterShape.LANDSCAPE,
            background = null, logo = null, description = null, releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = "addon",
        addonName = "addon",
        addonBaseUrl = "https://addon.example.invalid",
        catalogKey = "addon:tv:live",
        catalogName = "Live",
        kind = SportsLiveKind.EVENTS,
        isLive = true,
        isChannel = false,
        startMs = null,
        sport = null
    )
}
