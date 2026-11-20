package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** live.20 B2: streams looked up ahead of OK and CH±, lightly (dwell, cancel, warm, cooling, budget). */
@OptIn(ExperimentalCoroutinesApi::class)
class SportsLiveStreamPrefetchTest {

    private val prefetched = mutableListOf<String>()
    private val verified = mutableListOf<String>()
    private val warm = mutableSetOf<String>()
    private val cooling = mutableSetOf<String>()
    private var now = 0L
    private val budget = SportsLivePrefetchBudget(nowMs = { now })

    private fun TestScope.prefetcher() = SportsLiveStreamPrefetcher(
        scope = backgroundScope,
        hooks = SportsLivePrefetchHooks(
            prefetch = { item ->
                prefetched += item.id
                SportsLiveLookup.Found(stream(item.id))
            },
            isWarm = { it.id in warm },
            isCooling = { it.id in cooling },
            verify = { item, _ -> verified += item.id }
        ),
        budget = budget
    )

    @Test
    fun `a row focused for the dwell is looked up, once`() = runTest {
        val prefetcher = prefetcher()

        prefetcher.onFocus(item("a"))
        advanceTimeBy(SportsLiveStreamPrefetcher.FOCUS_DWELL_MS - 1)
        runCurrent()
        assertTrue(prefetched.isEmpty())

        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("a"), prefetched)
        // A focused row is not looked up for a preview check: no probe.
        assertTrue(verified.isEmpty())

        // The same row refocused (a refreshed copy) changes nothing.
        prefetcher.onFocus(item("a"))
        advanceTimeBy(SportsLiveStreamPrefetcher.FOCUS_DWELL_MS * 2)
        runCurrent()
        assertEquals(listOf("a"), prefetched)
        assertEquals(600L, SportsLiveStreamPrefetcher.FOCUS_DWELL_MS)
    }

    @Test
    fun `scrolling past rows sends nothing, focus moving on cancels the dwell`() = runTest {
        val prefetcher = prefetcher()

        // A D-pad burst: a new row every 200 ms, then the viewer stops on e.
        listOf("a", "b", "c", "d", "e").forEach { id ->
            prefetcher.onFocus(item(id))
            advanceTimeBy(200)
            runCurrent()
        }
        advanceTimeBy(SportsLiveStreamPrefetcher.FOCUS_DWELL_MS)
        runCurrent()
        assertEquals(listOf("e"), prefetched)

        // Focus leaving the list (or the screen) cancels a pending dwell.
        prefetcher.onFocus(item("f"))
        advanceTimeBy(300)
        prefetcher.stop()
        advanceTimeBy(SportsLiveStreamPrefetcher.FOCUS_DWELL_MS)
        runCurrent()
        assertEquals(listOf("e"), prefetched)
    }

    @Test
    fun `a game a start would not ask for, or on a cooling addon, is never looked up`() = runTest {
        val prefetcher = prefetcher()
        warm += "ready"
        cooling += "limited"

        assertFalse(prefetcher.start(item("ready"), reason = "test", verify = false))
        assertFalse(prefetcher.start(item("limited"), reason = "test", verify = false))
        runCurrent()
        assertTrue(prefetched.isEmpty())
    }

    @Test
    fun `the budget allows six lookups a minute and a game once per resolve cache life`() = runTest {
        val prefetcher = prefetcher()

        (1..SportsLivePrefetchBudget.PER_MINUTE).forEach { assertTrue(prefetcher.start(item("g$it"), "test", verify = false)) }
        assertFalse(prefetcher.start(item("g7"), "test", verify = false))
        runCurrent()
        assertEquals(6, prefetched.size)

        // A minute on: new games again, but not one already looked up within the cache's life.
        now += 60_000L
        assertFalse(prefetcher.start(item("g1"), "test", verify = false))
        assertTrue(prefetcher.start(item("g7"), "test", verify = false))
        now += SportsLiveResolveCache.TTL_MS
        assertTrue(prefetcher.start(item("g1"), "test", verify = false))
    }

    @Test
    fun `zap neighbours are looked up at once and their found link probed once`() = runTest {
        val prefetcher = prefetcher()

        prefetcher.prefetchNow(listOf(item("up"), item("down"), item("up")))
        runCurrent()

        assertEquals(listOf("up", "down"), prefetched)
        assertEquals(listOf("up", "down"), verified)
    }

    private fun stream(id: String) = Stream(
        name = id, title = null, description = null, url = "https://cdn.example.invalid/$id.m3u8", ytId = null,
        infoHash = null, fileIdx = null, externalUrl = null, behaviorHints = null, addonName = "Source", addonLogo = null
    )

    private fun item(id: String) = SportsLiveItem(
        meta = MetaPreview(
            id = id, type = ContentType.UNKNOWN, rawType = "sport", name = id, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = "LIVE", imdbRating = null, genres = emptyList()
        ),
        addonId = "a", addonName = "Source", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
        kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false, startMs = null, sport = "Soccer"
    )
}
