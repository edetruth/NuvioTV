package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.screens.player.nextSourceFallbackCandidate
import com.nuvio.tv.ui.screens.player.sourceFallbackKey
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SportsLiveExtraSourcesTest {

    private var fetches = 0

    @Before
    fun setUp() {
        SportsLiveExtraSources.reset()
        SportsLiveExtraSources.install { _, alt ->
            fetches++
            delay(100)
            SportsLiveExtraLookup(
                listOf(stream("${alt.id} stream", "https://${alt.addonName}.example/${alt.id}.m3u8", alt.addonName)),
                complete = true
            )
        }
    }

    @After
    fun tearDown() = SportsLiveExtraSources.reset()

    // --- holder ---

    @Test
    fun `a registered merged game resolves its alternates' streams once`() = runTest {
        SportsLiveExtraSources.register("game", MERGED)

        val first = async { SportsLiveExtraSources.extraStreamsFor("game") }
        val second = async { SportsLiveExtraSources.extraStreamsFor("game") }

        assertEquals(listOf("alt stream"), first.await().map { it.name })
        assertEquals(first.await(), second.await())
        assertEquals(first.await(), SportsLiveExtraSources.extraStreamsFor("game"))
        assertEquals(first.await(), SportsLiveExtraSources.cachedStreamsFor("game"))
        assertEquals("two", first.await().single().addonName)
        assertEquals(1, fetches)
    }

    @Test
    fun `nothing registered gives no streams and no lookup`() = runTest {
        assertFalse(SportsLiveExtraSources.isRegistered("movie"))
        assertTrue(SportsLiveExtraSources.extraStreamsFor("movie").isEmpty())
        assertTrue(SportsLiveExtraSources.extraStreamsFor(null).isEmpty())
        assertTrue(SportsLiveExtraSources.cachedStreamsFor("movie").isEmpty())
        assertEquals(0, fetches)
    }

    @Test
    fun `nothing is cached before the lookup`() {
        SportsLiveExtraSources.register("game", MERGED)

        assertTrue(SportsLiveExtraSources.isRegistered("game"))
        assertTrue(SportsLiveExtraSources.cachedStreamsFor("game").isEmpty())
    }

    @Test
    fun `clear drops the game and its cache`() = runTest {
        SportsLiveExtraSources.register("game", MERGED)
        SportsLiveExtraSources.extraStreamsFor("game")

        SportsLiveExtraSources.clear("game")

        assertFalse(SportsLiveExtraSources.isRegistered("game"))
        assertTrue(SportsLiveExtraSources.cachedStreamsFor("game").isEmpty())
        assertTrue(SportsLiveExtraSources.extraStreamsFor("game").isEmpty())
    }

    @Test
    fun `a cancelled lookup caches nothing`() = runTest {
        SportsLiveExtraSources.install { _, _ -> awaitCancellation() }
        SportsLiveExtraSources.register("game", MERGED)

        val lookup = launch { SportsLiveExtraSources.extraStreamsFor("game") }
        runCurrent()
        lookup.cancel()
        runCurrent()

        assertTrue(SportsLiveExtraSources.cachedStreamsFor("game").isEmpty())
        SportsLiveExtraSources.install { _, _ -> SportsLiveExtraLookup(listOf(EXTRA), complete = true) }
        assertEquals(listOf(EXTRA), SportsLiveExtraSources.extraStreamsFor("game"))
    }

    @Test
    fun `an empty or incomplete lookup is not cached, and a refresh looks again`() = runTest {
        var next = SportsLiveExtraLookup(emptyList(), complete = true)
        SportsLiveExtraSources.install { _, _ -> fetches++; next }
        SportsLiveExtraSources.register("game", MERGED)

        assertTrue(SportsLiveExtraSources.extraStreamsFor("game").isEmpty())
        next = SportsLiveExtraLookup(listOf(EXTRA), complete = false)
        // A timed-out lookup's streams are shown but not kept.
        assertEquals(listOf(EXTRA), SportsLiveExtraSources.extraStreamsFor("game"))
        assertTrue(SportsLiveExtraSources.cachedStreamsFor("game").isEmpty())
        next = SportsLiveExtraLookup(listOf(EXTRA), complete = true)
        assertEquals(listOf(EXTRA), SportsLiveExtraSources.extraStreamsFor("game"))
        assertEquals(listOf(EXTRA), SportsLiveExtraSources.cachedStreamsFor("game"))
        assertEquals(3, fetches)

        SportsLiveExtraSources.extraStreamsFor("game")
        assertEquals(3, fetches)
        SportsLiveExtraSources.extraStreamsFor("game", refresh = true)
        assertEquals(4, fetches)
    }

    @Test
    fun `registering a single-source item clears the content id`() {
        SportsLiveExtraSources.register("game", MERGED)

        SportsLiveExtraSources.register("game", SINGLE)

        assertFalse(SportsLiveExtraSources.isRegistered("game"))
    }

    @Test
    fun `the oldest game is evicted beyond the limit`() {
        val ids = (1..SportsLiveExtraSources.MAX_ENTRIES + 1).map { "game$it" }
        ids.dropLast(1).forEach { SportsLiveExtraSources.register(it, MERGED) }
        // Registering again makes a game the newest.
        SportsLiveExtraSources.register("game1", MERGED)

        SportsLiveExtraSources.register(ids.last(), MERGED)

        assertTrue(SportsLiveExtraSources.isRegistered("game1"))
        assertFalse(SportsLiveExtraSources.isRegistered("game2"))
        ids.drop(2).forEach { assertTrue(it, SportsLiveExtraSources.isRegistered(it)) }
    }

    // --- Sources panel ---

    @Test
    fun `the player's search is untouched for content with nothing registered`() {
        val search: Flow<NetworkResult<List<AddonStreams>>> = flowOf(NetworkResult.Loading)

        assertSame(search, search.withSportsLiveExtras("movie"))
        assertSame(search, search.withSportsLiveExtras(null))
    }

    @Test
    fun `the extras are appended under their addon names`() = runTest {
        SportsLiveExtraSources.register("game", MERGED)
        val primary = stream("p", "https://one.example/p.m3u8", "one")
        val search = flow {
            emit(NetworkResult.Success(listOf(AddonStreams("one", null, listOf(primary)))))
        }

        val last = search.withSportsLiveExtras("game").toList().last() as NetworkResult.Success

        assertEquals(listOf("one", "two"), last.data.map { it.addonName })
        assertEquals(listOf("p"), last.data[0].streams.map { it.name })
        assertEquals(listOf("alt stream"), last.data[1].streams.map { it.name })
    }

    @Test
    fun `a search still loading shows Loading, then the primary's streams with the extras`() = runTest {
        SportsLiveExtraSources.register("game", MERGED)
        val primary = stream("p", "https://one.example/p.m3u8", "one")
        val search = flow {
            emit(NetworkResult.Loading)
            delay(500)
            emit(NetworkResult.Success(listOf(AddonStreams("one", null, listOf(primary)))))
        }

        val results = search.withSportsLiveExtras("game").toList()

        assertEquals(NetworkResult.Loading, results.first())
        val last = results.last() as NetworkResult.Success
        assertEquals(listOf("p", "alt stream"), last.data.flatMap { g -> g.streams.map { it.name } })
    }

    @Test
    fun `extras the search already lists are not added again`() {
        val shared = stream("shared", "https://cdn.example/shared.m3u8", "two")
        val extra = stream("extra", "https://two.example/extra.m3u8", "two")

        val result = withExtraStreams(
            NetworkResult.Success(listOf(AddonStreams("one", null, listOf(shared.copy(addonName = "one"))))),
            listOf(shared, extra)
        )

        assertEquals(listOf(listOf("shared"), listOf("extra")), (result as NetworkResult.Success).data.map { g -> g.streams.map { it.name } })
    }

    @Test
    fun `a single-addon multi-provider card reads by provider, primary group included`() {
        // Primary from the shared addon (its search group is named by the addon); alternates already
        // carry provider labels. The primary relabel folds its group in under its provider too.
        val primary = stream("p", "https://cnc.example/p.m3u8", "CNCVerse")
        val livXow = stream("alt1", "https://cnc.example/alt1.m3u8", "LivXow")
        val skTech = stream("alt2", "https://cnc.example/alt2.m3u8", "SKTech")

        val result = withExtraStreams(
            NetworkResult.Success(listOf(AddonStreams("CNCVerse", null, listOf(primary)))),
            listOf(livXow, skTech),
            primaryRelabel = "CNCVerse" to "SportzX"
        ) as NetworkResult.Success

        assertEquals(listOf("SportzX", "LivXow", "SKTech"), result.data.map { it.addonName })
        // No source still shows the raw addon name.
        assertTrue(result.data.flatMap { it.streams }.none { it.addonName == "CNCVerse" })
    }

    @Test
    fun `extras join an existing group of the same addon`() {
        val own = stream("own", "https://two.example/own.m3u8", "two")
        val extra = stream("extra", "https://two.example/extra.m3u8", "two")

        val result = withExtraStreams(NetworkResult.Success(listOf(AddonStreams("two", null, listOf(own)))), listOf(extra))

        assertEquals(listOf(listOf("own", "extra")), (result as NetworkResult.Success).data.map { g -> g.streams.map { it.name } })
    }

    @Test
    fun `a failed search shows the extras, and waits for them`() {
        val extra = stream("extra", "https://two.example/extra.m3u8", "two")
        val error = NetworkResult.Error("none")

        assertEquals(NetworkResult.Loading, withExtraStreams(error, null))
        assertEquals(error, withExtraStreams(error, emptyList()))
        assertEquals(listOf("two"), (withExtraStreams(error, listOf(extra)) as NetworkResult.Success).data.map { it.addonName })
    }

    // --- fallback ---

    private val settings = PlayerSettings(
        streamAutoPlayFallbackOnFailure = true,
        streamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM
    )
    private val primary = (1..2).map { stream("P$it", "https://one.example/p$it.m3u8", "one") }
    private val extras = (1..2).map { stream("E$it", "https://two.example/e$it.m3u8", "two") }

    private fun candidate(
        streams: List<Stream>,
        extras: List<Stream>,
        tried: Set<String> = emptySet(),
        extrasAllowed: Boolean = true
    ) = sportsLiveFallbackCandidate(
        streams = streams,
        extras = extras,
        extrasAllowed = extrasAllowed,
        settings = settings,
        installedAddonNames = setOf("one", "two"),
        triedKeys = tried,
        failedAddonName = "one",
        failedInfoHash = null
    )

    @Test
    fun `without extras the candidate is upstream's`() {
        val tried = setOf(primary[0].sourceFallbackKey())

        assertEquals(
            nextSourceFallbackCandidate(primary, settings, setOf("one", "two"), tried, "one", null),
            candidate(primary, emptyList(), tried)
        )
        assertEquals(primary[1], candidate(primary, emptyList(), tried))
    }

    @Test
    fun `the primary's streams are tried before the extras, even when the list holds both`() {
        // The Sources panel's list puts the extras in sourceAllStreams, here ahead of the primary's.
        val panelList = extras + primary

        assertEquals(primary[0], candidate(panelList, extras))
        assertEquals(primary[1], candidate(panelList, extras, setOf(primary[0].sourceFallbackKey())))
    }

    @Test
    fun `extras follow in order once the primary's streams are tried, skipping tried keys`() {
        val primaryTried = primary.map { it.sourceFallbackKey() }.toSet()

        assertEquals(extras[0], candidate(primary + extras, extras, primaryTried))
        assertEquals(extras[1], candidate(primary + extras, extras, primaryTried + extras[0].sourceFallbackKey()))
        assertNull(candidate(primary + extras, extras, primaryTried + extras.map { it.sourceFallbackKey() }))
    }

    @Test
    fun `extras wait until the primary's search is done`() {
        val primaryTried = primary.map { it.sourceFallbackKey() }.toSet()

        assertNull(candidate(primary, extras, primaryTried, extrasAllowed = false))
    }

    @Test
    fun `an extra that is the torrent that just failed is skipped`() {
        val failed = stream("t", url = null, addon = "two").copy(infoHash = "ABC")
        val primaryTried = primary.map { it.sourceFallbackKey() }.toSet()

        val next = sportsLiveFallbackCandidate(
            streams = primary,
            extras = listOf(failed, extras[0]),
            extrasAllowed = true,
            settings = settings,
            installedAddonNames = setOf("one", "two"),
            triedKeys = primaryTried,
            failedAddonName = "two",
            failedInfoHash = "abc"
        )

        assertEquals(extras[0], next)
    }

    @Test
    fun `with a regex or chosen sources, only extras the user's settings pick are tried`() {
        val primaryTried = primary.map { it.sourceFallbackKey() }.toSet()
        fun next(settings: PlayerSettings) = sportsLiveFallbackCandidate(
            streams = primary,
            extras = extras,
            extrasAllowed = true,
            settings = settings,
            installedAddonNames = setOf("one", "two"),
            triedKeys = primaryTried,
            failedAddonName = "one",
            failedInfoHash = null
        )

        // No extra matches the regex: none is tried.
        assertNull(next(settings.copy(streamAutoPlayMode = StreamAutoPlayMode.REGEX_MATCH, streamAutoPlayRegex = "4K")))
        assertEquals(extras[1], next(settings.copy(streamAutoPlayMode = StreamAutoPlayMode.REGEX_MATCH, streamAutoPlayRegex = "E2")))
        // Addon "two" is not among the chosen addons.
        assertNull(next(settings.copy(streamAutoPlaySelectedAddons = setOf("one"))))
        // Manual mode has no choice of its own: the first playable extra.
        assertEquals(extras[0], next(settings.copy(streamAutoPlayMode = StreamAutoPlayMode.MANUAL)))
    }

    @Test
    fun `extras the fallback cannot prepare are skipped`() {
        val youTube = stream("yt", url = null, addon = "two").copy(ytId = "abc")
        val primaryTried = primary.map { it.sourceFallbackKey() }.toSet()

        assertEquals(extras[0], candidate(primary, listOf(youTube, extras[0]), primaryTried))
        assertNull(candidate(primary, listOf(youTube), primaryTried))
    }

    private companion object {
        fun stream(name: String, url: String?, addon: String) = Stream(
            name = name,
            title = null,
            description = null,
            url = url,
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
            addonId = addon,
            addonName = addon,
            addonBaseUrl = "https://$addon.example",
            catalogKey = "${addon}_sport_live",
            catalogName = "Live",
            kind = SportsLiveKind.EVENTS,
            isLive = true,
            isChannel = false,
            startMs = null,
            sport = null
        )

        val EXTRA = stream("extra", "https://two.example/extra.m3u8", "two")
        val SINGLE = item("game", "one")
        val MERGED = SINGLE.copy(alternates = listOf(item("alt", "two")))
    }
}
