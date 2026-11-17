package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** #50: the Sources panel opens from the play path's results and fills in source by source. */
class SportsLiveSourcesPanelProgressTest {

    private val fetched = mutableListOf<String>()

    @Before
    fun setUp() {
        SportsLiveExtraSources.reset()
        // "fast" answers after 1 s, "slow" after 5 s, "empty" after 2 s with nothing.
        SportsLiveExtraSources.install { _, source ->
            fetched += source.id
            when (source.id) {
                "fast" -> { delay(1_000); SportsLiveExtraLookup(listOf(FAST_STREAM), complete = true) }
                "slow" -> { delay(5_000); SportsLiveExtraLookup(listOf(SLOW_STREAM), complete = true) }
                else -> { delay(2_000); SportsLiveExtraLookup(emptyList(), complete = true) }
            }
        }
    }

    @After
    fun tearDown() = SportsLiveExtraSources.reset()

    @Test
    fun `a seed from the play path fills the panel, so opening it fetches nothing`() = runTest {
        // Seeded before the player registers the game (the alternates were awaited)...
        SportsLiveExtraSources.seed(CARD, listOf(FAST_STREAM, SLOW_STREAM), complete = true)
        SportsLiveExtraSources.register(CARD.id, CARD)
        assertEquals(listOf(FAST_STREAM, SLOW_STREAM), SportsLiveExtraSources.extraStreamsFor(CARD.id))

        // ...or after (the alternates finished in the background while the primary played).
        SportsLiveExtraSources.reset()
        SportsLiveExtraSources.install { _, source -> fetched += source.id; SportsLiveExtraLookup(emptyList(), true) }
        SportsLiveExtraSources.register(CARD.id, CARD)
        SportsLiveExtraSources.seed(CARD, listOf(FAST_STREAM), complete = true)
        assertEquals(listOf(FAST_STREAM), SportsLiveExtraSources.cachedStreamsFor(CARD.id))
        assertEquals(listOf(FAST_STREAM), SportsLiveExtraSources.extraStreamsFor(CARD.id))

        assertTrue(fetched.isEmpty())
    }

    @Test
    fun `an incomplete or empty play-path result seeds nothing`() = runTest {
        SportsLiveExtraSources.seed(CARD, listOf(FAST_STREAM), complete = false)
        SportsLiveExtraSources.seed(CARD, emptyList(), complete = true)
        SportsLiveExtraSources.register(CARD.id, CARD)

        assertTrue(SportsLiveExtraSources.cachedStreamsFor(CARD.id).isEmpty())
    }

    @Test
    fun `a playback failure drops the seed, and a stale seed is not used`() = runTest {
        SportsLiveExtraSources.seed(CARD, listOf(FAST_STREAM), complete = true)
        SportsLiveExtraSources.invalidateResolve(CARD.id)
        SportsLiveExtraSources.register(CARD.id, CARD)
        assertTrue(SportsLiveExtraSources.cachedStreamsFor(CARD.id).isEmpty())

        var now = 0L
        SportsLiveExtraSources.nowMs = { now }
        SportsLiveExtraSources.seed(CARD, listOf(FAST_STREAM), complete = true)
        SportsLiveExtraSources.clear(CARD.id)
        now = SportsLiveResolveCache.TTL_MS
        SportsLiveExtraSources.register(CARD.id, CARD)
        assertTrue(SportsLiveExtraSources.cachedStreamsFor(CARD.id).isEmpty())
    }

    @Test
    fun `a forced reload fetches again despite the seed`() = runTest {
        SportsLiveExtraSources.seed(CARD, listOf(FAST_STREAM, SLOW_STREAM), complete = true)
        SportsLiveExtraSources.register(CARD.id, CARD)

        val streams = SportsLiveExtraSources.extraStreamsFor(CARD.id, refresh = true)

        assertEquals(setOf("fast", "slow", "empty"), fetched.toSet())
        assertEquals(setOf(FAST_STREAM, SLOW_STREAM), streams.toSet())
        // The seed is gone too: registering again does not bring the old links back.
        SportsLiveExtraSources.clear(CARD.id)
        SportsLiveExtraSources.register(CARD.id, CARD)
        assertTrue(SportsLiveExtraSources.cachedStreamsFor(CARD.id).isEmpty())
    }

    @Test
    fun `each source's group shows as soon as it answers, the fast one before the slow one`() = runTest {
        SportsLiveExtraSources.register(CARD.id, CARD)
        val search: Flow<NetworkResult<List<AddonStreams>>> =
            flowOf(NetworkResult.Success(listOf(AddonStreams("Primary", null, listOf(PRIMARY_STREAM)))))
        val groupsSeen = mutableListOf<List<String>>()

        val job = launch {
            search.withSportsLiveExtras(CARD.id).collect { result ->
                (result as? NetworkResult.Success)?.let { groupsSeen += it.data.map { group -> group.addonName } }
            }
        }
        advanceTimeBy(1_001)
        assertEquals(listOf("Primary", "Fast"), groupsSeen.last())
        advanceTimeBy(5_000)
        job.join()

        assertEquals(listOf("Primary", "Fast", "Slow"), groupsSeen.last())
        assertEquals(listOf(listOf("Primary"), listOf("Primary", "Fast"), listOf("Primary", "Fast", "Slow")), groupsSeen.distinct())
        assertEquals(listOf(FAST_STREAM, SLOW_STREAM), SportsLiveExtraSources.cachedStreamsFor(CARD.id))
    }

    @Test
    fun `a still-fetching source has a loading row until it answers or comes back empty`() = runTest {
        SportsLiveExtraSources.register(CARD.id, CARD)
        val job = launch { SportsLiveExtraSources.extraStreamsProgress(CARD.id).collect {} }

        runCurrent()
        assertEquals(setOf("Fast", "Slow", "Empty"), SportsLiveExtraSources.pendingSourcesFor(CARD.id).toSet())
        advanceTimeBy(1_001)
        assertEquals(setOf("Slow", "Empty"), SportsLiveExtraSources.pendingSourcesFor(CARD.id).toSet())
        // Empty: its row goes, and no group is added for it.
        advanceTimeBy(1_000)
        assertEquals(listOf("Slow"), SportsLiveExtraSources.pendingSourcesFor(CARD.id))
        job.join()

        assertTrue(SportsLiveExtraSources.pendingSourcesFor(CARD.id).isEmpty())
    }

    @Test
    fun `content with nothing registered is passed through untouched`() = runTest {
        val search: Flow<NetworkResult<List<AddonStreams>>> = flowOf(NetworkResult.Loading)

        assertSame(search, search.withSportsLiveExtras("movie"))
        assertTrue(SportsLiveExtraSources.pendingSourcesFor("movie").isEmpty())
        assertEquals(listOf(true), SportsLiveExtraSources.extraStreamsProgress("movie").toList().map { it.done })
        assertTrue(fetched.isEmpty())
    }

    private companion object {
        fun stream(name: String, host: String, addon: String) = Stream(
            name = name,
            title = null,
            description = null,
            url = "https://$host.example.invalid/$name.m3u8",
            ytId = null,
            infoHash = null,
            fileIdx = null,
            externalUrl = null,
            behaviorHints = null,
            addonName = addon,
            addonLogo = null
        )

        fun item(id: String, addon: String) = SportsLiveItem(
            meta = MetaPreview(
                id = id,
                type = ContentType.UNKNOWN,
                rawType = "sport",
                name = "A vs B",
                poster = null,
                posterShape = PosterShape.LANDSCAPE,
                background = null,
                logo = null,
                description = null,
                releaseInfo = "LIVE",
                imdbRating = null,
                genres = emptyList()
            ),
            addonId = addon.lowercase(),
            addonName = addon,
            addonBaseUrl = "https://${addon.lowercase()}.example.invalid",
            catalogKey = "${addon}_sport_live",
            catalogName = "Live",
            kind = SportsLiveKind.EVENTS,
            isLive = true,
            isChannel = false,
            startMs = null,
            sport = null
        )

        val PRIMARY_STREAM = stream("p", "primary", "Primary")
        val FAST_STREAM = stream("f", "fast", "Fast")
        val SLOW_STREAM = stream("s", "slow", "Slow")
        val CARD = item("game", "Primary").copy(
            alternates = listOf(item("slow", "Slow"), item("fast", "Fast"), item("empty", "Empty"))
        )
    }
}
