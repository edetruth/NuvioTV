package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.repository.SportsLiveStreamProbe
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.StreamRepository
import com.nuvio.tv.core.debrid.DirectDebridResolver
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import com.nuvio.tv.core.sportslive.SportsLiveSourceReliability
import com.nuvio.tv.core.sportslive.SportsLiveWorkingLinks
import com.nuvio.tv.core.sportslive.ProbeVerdict
import com.nuvio.tv.core.sportslive.SportsLiveReadyMark
import com.nuvio.tv.core.sportslive.SportsLiveGameReadiness
import com.nuvio.tv.core.sportslive.SportsLiveLinkSpeed
import com.nuvio.tv.core.sportslive.SportsLiveSourceCooldown

@OptIn(ExperimentalCoroutinesApi::class)
class SportsLiveZapResolverTest {

    private val streams = FakeStreamRepository()
    private val resolveCache = SportsLiveResolveCache()
    private val probe = mockk<SportsLiveStreamProbe>(relaxed = true)
    private var settings = PlayerSettings()
    private var preparationCheckThrows = false
    private var linkNow = 0L
    private val links = SportsLiveWorkingLinks(nowMs = { linkNow })
    private val speed = SportsLiveLinkSpeed()
    private val readiness = SportsLiveGameReadiness()
    private val debrid = mockk<DirectDebridResolver>().also {
        coEvery { it.shouldResolveToPlayableStream(any()) } answers {
            if (preparationCheckThrows && firstArg<Stream>() == PREPARE_ALT) error("debrid check failed")
            firstArg<Stream>() in NEEDS_PREPARATION
        }
    }

    private fun TestScope.newResolver(probe: SportsLiveStreamProbe = this@SportsLiveZapResolverTest.probe): SportsLiveZapResolver {
        // Each fresh resolver starts with an empty cache so tests that re-resolve an item re-fetch it.
        resolveCache.clear()
        val dataStore = mockk<PlayerSettingsDataStore>()
        every { dataStore.playerSettings } answers { flowOf(settings) }
        val addons = mockk<AddonRepository>()
        every { addons.getInstalledAddons() } returns flowOf(listOf(addon("Sports"), addon("Off", enabled = false)))
        return SportsLiveZapResolver(streams, dataStore, addons, probe, debrid, resolveCache).also {
            it.fetchScope = backgroundScope
            it.workingLinks = links
            it.linkSpeed = speed
            it.readiness = readiness
        }
    }

    @After
    fun tearDown() {
        SportsLiveSourceReliability.reset()
        SportsLiveExtraSources.reset()
    }

    @Test
    fun `a merged game tries its most reliable source first`() = runTest {
        SportsLiveSourceReliability.record(SportsLiveSourceReliability.keyOf(MERGED), SportsLiveSourceReliability.Outcome.FAILURE)
        SportsLiveSourceReliability.record(SportsLiveSourceReliability.keyOf(ALT2), SportsLiveSourceReliability.Outcome.SUCCESS)
        streams.responses["game"] = { flowOf(success(SD)) }
        streams.responses["alt1"] = { flowOf(success(HD, addon = "Other")) }
        streams.responses["alt2"] = { flowOf(success(OTHER_HD, addon = "Third")) }

        assertEquals(OTHER_HD, newResolver().resolve(MERGED))
        assertEquals(listOf(OTHER_HD, HD, SD), newResolver().resolveAll(MERGED))
        // The stream is credited to the source it came from, for the player's outcome.
        assertEquals(SportsLiveSourceReliability.keyOf(ALT2), SportsLiveExtraSources.sourceKeyFor(MERGED, OTHER_HD))
    }

    @Test
    fun `reliability never puts an addon the user did not choose ahead of a chosen one`() = runTest {
        settings = PlayerSettings(streamAutoPlaySelectedAddons = setOf("Sports", "Other"))
        SportsLiveSourceReliability.record(SportsLiveSourceReliability.keyOf(ALT2), SportsLiveSourceReliability.Outcome.SUCCESS)
        SportsLiveSourceReliability.record(SportsLiveSourceReliability.keyOf(MERGED), SportsLiveSourceReliability.Outcome.FAILURE)
        streams.responses["game"] = { flowOf(success(SD)) }
        streams.responses["alt1"] = { flowOf(success(HD, addon = "Other")) }
        streams.responses["alt2"] = { flowOf(success(OTHER_HD, addon = "Third")) }

        // Among the chosen, the failing primary drops behind; the unchosen, reliable one stays last.
        assertEquals(listOf(HD, SD, OTHER_HD), newResolver().resolveAll(MERGED))
    }

    @Test
    fun `picks the stream the user's autoplay settings select`() = runTest {
        settings = PlayerSettings(streamAutoPlayMode = StreamAutoPlayMode.REGEX_MATCH, streamAutoPlayRegex = "1080")
        streams.response = { result(SD, HD) }

        assertEquals(HD, newResolver().resolve(ITEM))
        assertEquals(listOf(Triple("sport", "game", false)), streams.calls)
        verify { probe.recordResult(ITEM, any()) }
    }

    @Test
    fun `manual mode plays the first playable stream`() = runTest {
        settings = PlayerSettings(streamAutoPlayMode = StreamAutoPlayMode.MANUAL)
        streams.response = { result(WEB, SD, HD) }

        assertEquals(SD, newResolver().resolve(ITEM))
    }

    @Test
    fun `a regex with no match falls back to the first playable stream`() = runTest {
        settings = PlayerSettings(streamAutoPlayMode = StreamAutoPlayMode.REGEX_MATCH, streamAutoPlayRegex = "4K")
        streams.response = { result(WEB, SD, HD) }

        assertEquals(SD, newResolver().resolve(ITEM))
    }

    @Test
    fun `only external links resolves to nothing and is reported to the probe`() = runTest {
        val final = success(WEB)
        streams.response = { flowOf(NetworkResult.Loading, final) }

        assertNull(newResolver().resolve(ITEM))
        verify { probe.recordResult(ITEM, final) }
    }

    @Test
    fun `a search that never finishes times out to nothing and reports nothing`() = runTest {
        streams.response = { flow { emit(NetworkResult.Loading); awaitCancellation() } }

        assertNull(newResolver().resolve(ITEM))
        assertEquals(SportsLiveZapResolver.TIMEOUT_MS, testScheduler.currentTime)
        verify(exactly = 0) { probe.recordResult(any(), any()) }
    }

    @Test
    fun `a search that ends while still loading resolves to nothing`() = runTest {
        streams.response = { flowOf(NetworkResult.Loading) }

        assertNull(newResolver().resolve(ITEM))
    }

    @Test
    fun `a partial result is not picked before the search finishes`() = runTest {
        settings = PlayerSettings(streamAutoPlayMode = StreamAutoPlayMode.REGEX_MATCH, streamAutoPlayRegex = "1080")
        streams.response = {
            flow {
                emit(success(SD))
                delay(2_000)
                emit(success(SD, HD))
            }
        }

        assertEquals(HD, newResolver().resolve(ITEM))
        assertEquals(2_000L, testScheduler.currentTime)
    }

    @Test
    fun `a selection already found plays once the early pick delay passes`() = runTest {
        settings = PlayerSettings(streamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM)
        streams.response = {
            flow {
                emit(success(SD))
                awaitCancellation()
            }
        }

        assertEquals(SD, newResolver().resolve(ITEM))
        assertEquals(SportsLiveZapResolver.EARLY_PICK_MS, testScheduler.currentTime)
    }

    @Test
    fun `a slow answer within the hard cap still plays`() = runTest {
        // #158: cold answers took up to 15 s; live.18 gave up at 6 s and showed no streams.
        streams.response = { flow { emit(NetworkResult.Loading); delay(15_000); emit(success(SD)) } }

        assertEquals(SD, newResolver().resolve(ITEM))
        assertEquals(15_000L, testScheduler.currentTime)
        assertEquals(20_000L, SportsLiveZapResolver.TIMEOUT_MS)
    }

    @Test
    fun `past the soft deadline what came back plays without waiting for a slow addon`() = runTest {
        settings = PlayerSettings(streamAutoPlayMode = StreamAutoPlayMode.REGEX_MATCH, streamAutoPlayRegex = "4K")
        streams.response = { flow { emit(success(SD)); awaitCancellation() } }

        assertEquals(SD, newResolver().resolve(ITEM))
        assertEquals(SportsLiveZapResolver.SOFT_DEADLINE_MS, testScheduler.currentTime)
    }

    @Test
    fun `with nothing in at the soft deadline the lookup keeps waiting`() = runTest {
        streams.response = { flow { emit(success(WEB)); delay(9_000); emit(success(WEB, SD)) } }

        assertEquals(SD, newResolver().resolve(ITEM))
        assertEquals(9_000L, testScheduler.currentTime)
    }

    @Test
    fun `a merged game's source with links plays at the soft deadline while another addon is slow`() = runTest {
        streams.responses["game"] = { flow { emit(success(SD)); awaitCancellation() } }
        streams.responses["alt1"] = { flowOf(success(HD, addon = "Other")) }
        streams.responses["alt2"] = { flowOf(success(OTHER_HD, addon = "Third")) }

        assertEquals(listOf(SD, HD, OTHER_HD), newResolver().resolveAll(MERGED))
        assertEquals(SportsLiveZapResolver.SOFT_DEADLINE_MS, testScheduler.currentTime)
    }

    @Test
    fun `resolveAll lists the primary's streams first, then each alternate's, fetched in parallel`() = runTest {
        streams.responses["game"] = { flow { delay(2_000); emit(success(SD)) } }
        streams.responses["alt1"] = { flowOf(success(HD, addon = "Other")) }
        streams.responses["alt2"] = { flow { delay(1_000); emit(success(OTHER_HD, JUNK, addon = "Third")) } }

        val all = newResolver().resolveAll(MERGED)

        assertEquals(listOf(SD, HD, OTHER_HD), all)
        assertEquals(2_000L, testScheduler.currentTime)
        verify { probe.recordResult(MERGED, any()) }
        verify { probe.recordResult(ALT1, any()) }
        verify { probe.recordResult(ALT2, any()) }
    }

    @Test
    fun `resolveAll uses a slow source's streams at the soft deadline and reports them`() = runTest {
        // #158: streams already in are a verdict (the game is on); the source is not waited for.
        streams.responses["game"] = { flow { emit(success(SD)); awaitCancellation() } }
        streams.responses["alt1"] = { flowOf(success(HD, addon = "Other")) }
        streams.responses["alt2"] = { flowOf(success(addon = "Third")) }

        assertEquals(listOf(SD, HD), newResolver().resolveAll(MERGED))
        assertEquals(SportsLiveZapResolver.SOFT_DEADLINE_MS, testScheduler.currentTime)
        verify { probe.recordResult(MERGED, any()) }
        verify { probe.recordResult(ALT1, any()) }
        verify { probe.recordResult(ALT2, any()) }
    }

    @Test
    fun `resolveAll does not report a source that timed out with nothing`() = runTest {
        streams.responses["game"] = { flow { emit(NetworkResult.Loading); awaitCancellation() } }
        streams.responses["alt1"] = { flowOf(success(HD, addon = "Other")) }
        streams.responses["alt2"] = { flowOf(success(addon = "Third")) }

        assertEquals(listOf(HD), newResolver().resolveAll(MERGED))
        assertEquals(SportsLiveZapResolver.TIMEOUT_MS, testScheduler.currentTime)
        verify(exactly = 0) { probe.recordResult(MERGED, any()) }
        verify { probe.recordResult(ALT1, any()) }
    }

    @Test
    fun `a merged game plays the primary's best stream when it has one`() = runTest {
        settings = PlayerSettings(streamAutoPlayMode = StreamAutoPlayMode.REGEX_MATCH, streamAutoPlayRegex = "1080")
        streams.responses["game"] = { flowOf(success(SD, HD)) }
        streams.responses["alt1"] = { flowOf(success(OTHER_HD, addon = "Other")) }
        streams.responses["alt2"] = { flowOf(success(addon = "Third")) }

        assertEquals(HD, newResolver().resolve(MERGED))
    }

    @Test
    fun `a merged game never plays a stream the user's regex rules out`() = runTest {
        settings = PlayerSettings(streamAutoPlayMode = StreamAutoPlayMode.REGEX_MATCH, streamAutoPlayRegex = "1080")
        // The most reliable source has only a 720p stream; the regex wants 1080p.
        SportsLiveSourceReliability.record(SportsLiveSourceReliability.keyOf(MERGED), SportsLiveSourceReliability.Outcome.SUCCESS)
        streams.responses["game"] = { flowOf(success(SD)) }
        streams.responses["alt1"] = { flowOf(success(HD, addon = "Other")) }
        streams.responses["alt2"] = { flowOf(success(addon = "Third")) }

        assertEquals(HD, newResolver().resolve(MERGED))

        // Nothing matches anywhere: nothing plays rather than a stream the regex excludes.
        streams.responses["alt1"] = { flowOf(success(addon = "Other")) }
        assertNull(newResolver().resolve(MERGED))
    }

    @Test
    fun `lookup tells nothing found from none playable with one request per source`() = runTest {
        // Every addon empty: nothing found, after one request.
        streams.response = { flowOf(NetworkResult.Loading, success()) }
        assertEquals(SportsLiveLookup.NothingFound, newResolver().lookup(ITEM))
        assertEquals(1, streams.calls.size)

        // A failed search: nothing found too.
        streams.calls.clear()
        streams.response = { flowOf(NetworkResult.Error("server", 500)) }
        assertEquals(SportsLiveLookup.NothingFound, newResolver().lookup(ITEM))
        assertEquals(1, streams.calls.size)

        // Only an external link: streams came back but none may play, still one request.
        streams.calls.clear()
        streams.response = { result(WEB) }
        assertEquals(SportsLiveLookup.NonePlayable(listOf(WEB)), newResolver().lookup(ITEM))
        assertEquals(1, streams.calls.size)
    }

    @Test
    fun `a merged lookup with none playable lists every source's fetched streams without fetching again`() = runTest {
        settings = PlayerSettings(streamAutoPlayMode = StreamAutoPlayMode.REGEX_MATCH, streamAutoPlayRegex = "4K")
        streams.responses["game"] = { flowOf(success(SD)) }
        streams.responses["alt1"] = { flowOf(success(HD, addon = "Other")) }
        streams.responses["alt2"] = { flowOf(success(addon = "Third")) }

        val found = newResolver().lookup(MERGED)

        assertEquals(SportsLiveLookup.NonePlayable(listOf(SD, HD)), found)
        assertEquals(3, streams.calls.size)
    }

    @Test
    fun `a merged lookup where every source is empty finds nothing`() = runTest {
        streams.responses["game"] = { flowOf(success()) }
        streams.responses["alt1"] = { flowOf(NetworkResult.Error("none", NetworkResult.STREAMS_NOT_FOUND_CODE)) }
        streams.responses["alt2"] = { flowOf(success(addon = "Third")) }

        assertEquals(SportsLiveLookup.NothingFound, newResolver().lookup(MERGED))
        assertEquals(3, streams.calls.size)
    }

    @Test
    fun `a found lookup is cached, so the next one asks no addon`() = runTest {
        streams.response = { result(SD) }
        val resolver = newResolver()

        assertEquals(SportsLiveLookup.Found(SD), resolver.lookup(ITEM))
        assertEquals(SportsLiveLookup.Found(SD), resolver.lookup(ITEM))
        assertEquals(1, streams.calls.size)
    }

    @Test
    fun `a merged game falls back to the first alternate with a playable stream`() = runTest {
        streams.responses["game"] = { flowOf(success(WEB, JUNK)) }
        streams.responses["alt1"] = { flowOf(success(addon = "Other")) }
        streams.responses["alt2"] = { flowOf(success(OTHER_HD, addon = "Third")) }

        assertEquals(OTHER_HD, newResolver().resolve(MERGED))
        verify { probe.recordResult(MERGED, any()) }
        verify { probe.recordResult(ALT1, any()) }
        verify { probe.recordResult(ALT2, any()) }
    }

    @Test
    fun `a merged game with only empty or junk sources resolves to nothing`() = runTest {
        streams.responses["game"] = { flowOf(success(JUNK)) }
        streams.responses["alt1"] = { flowOf(NetworkResult.Error("none", NetworkResult.STREAMS_NOT_FOUND_CODE)) }
        streams.responses["alt2"] = { flow { throw IllegalStateException("boom") } }
        streams.responses["alt3"] = { flowOf(NetworkResult.Error("server", 500)) }
        val realProbe = SportsLiveStreamProbe(streams, resolveCache).apply { dispatcher = StandardTestDispatcher(testScheduler) }
        val merged = MERGED.copy(alternates = MERGED.alternates + ALT3)

        val resolver = newResolver(realProbe)
        assertNull(resolver.resolve(merged))
        assertEquals(emptyList<Stream>(), resolver.resolveAll(merged))
        // "Not found" is proof of an end; a throwing source records nothing and a server error is
        // inconclusive. The primary answered with only a "Support us" entry, a notice since live.20 (F9):
        // nothing playable, so it reads as ended like any notice-only answer (C1).
        assertEquals(setOf(MERGED.streamKey, ALT1.streamKey), realProbe.ended.value)
    }

    @Test
    fun `a throwing source records nothing`() = runTest {
        streams.responses["game"] = { flowOf(success(SD)) }
        streams.responses["alt1"] = { flow { throw IllegalStateException("boom") } }
        streams.responses["alt2"] = { flowOf(success(HD, addon = "Third")) }

        newResolver().resolveAll(MERGED)

        verify(exactly = 0) { probe.recordResult(ALT1, any()) }
        verify { probe.recordResult(ALT2, any()) }
    }

    @Test
    fun `the primary's stream plays without waiting for the alternates, which are still recorded`() = runTest {
        streams.responses["game"] = { flowOf(success(SD)) }
        streams.responses["alt1"] = { flow { delay(5_000); emit(success(HD, addon = "Other")) } }
        streams.responses["alt2"] = { flow { delay(8_000); emit(success(OTHER_HD, addon = "Third")) } }

        assertEquals(SD, newResolver().resolve(MERGED))
        assertEquals(0L, testScheduler.currentTime)

        advanceTimeBy(8_001)
        verify { probe.recordResult(ALT1, any()) }
        verify { probe.recordResult(ALT2, any()) }
    }

    @Test
    fun `an alternate's stream that needs the Stream screen is skipped, a primary's is not`() = runTest {
        streams.responses["game"] = { flowOf(success(WEB)) }
        streams.responses["alt1"] = { flowOf(success(PREPARE_ALT, addon = "Other")) }
        streams.responses["alt2"] = { flowOf(success(OTHER_HD, addon = "Third")) }
        assertEquals(OTHER_HD, newResolver().resolve(MERGED))
        assertEquals(listOf(WEB, OTHER_HD), newResolver().resolveAll(MERGED))

        streams.responses["game"] = { flowOf(success(PREPARE_PRIMARY)) }
        assertEquals(PREPARE_PRIMARY, newResolver().resolve(MERGED))
    }

    @Test
    fun `resolveAlternates lists only the alternates' streams and keeps those that need preparation`() = runTest {
        streams.responses["game"] = { flowOf(success(WEB)) }
        streams.responses["alt1"] = { flowOf(success(PREPARE_ALT, addon = "Other")) }
        streams.responses["alt2"] = { flowOf(success(OTHER_HD, addon = "Third")) }

        val lookup = newResolver().resolveAlternates(MERGED)
        assertEquals(listOf(PREPARE_ALT, OTHER_HD), lookup.streams)
        assertTrue(lookup.complete)
        assertEquals(emptyList<Stream>(), newResolver().resolveAlternates(MERGED.copy(alternates = emptyList())).streams)
    }

    @Test
    fun `resolveAlternates is incomplete when a source times out`() = runTest {
        streams.responses["alt1"] = { flow { awaitCancellation() } }
        streams.responses["alt2"] = { flowOf(success(OTHER_HD, addon = "Third")) }

        val lookup = newResolver().resolveAlternates(MERGED)

        assertEquals(listOf(OTHER_HD), lookup.streams)
        assertFalse(lookup.complete)
    }

    @Test
    fun `playing a merged game seeds the Sources panel, which then opens without fetching`() = runTest {
        streams.responses["game"] = { flowOf(success(SD)) }
        streams.responses["alt1"] = { flow { delay(3_000); emit(success(PREPARE_ALT, addon = "Other")) } }
        streams.responses["alt2"] = { flowOf(success(OTHER_HD, addon = "Third")) }
        val resolver = newResolver()
        SportsLiveExtraSources.install { card, source -> resolver.resolveAlternate(card, source) }

        // The primary plays at once; the player registers the game before the alternates finish.
        assertEquals(SD, resolver.resolve(MERGED))
        SportsLiveExtraSources.register(MERGED.id, MERGED)
        advanceTimeBy(3_001)
        val calls = streams.calls.size

        // Same as the panel's own lookup would list: alternates only, preparation streams kept.
        assertEquals(listOf(PREPARE_ALT, OTHER_HD), SportsLiveExtraSources.extraStreamsFor(MERGED.id))
        assertEquals(calls, streams.calls.size)
        // A forced reload still fetches.
        SportsLiveExtraSources.extraStreamsFor(MERGED.id, refresh = true)
        assertEquals(calls + 2, streams.calls.size)
    }

    @Test
    fun `the picker's lookup seeds the Sources panel too, but a timed out alternate seeds nothing`() = runTest {
        streams.responses["game"] = { flowOf(success(SD)) }
        streams.responses["alt1"] = { flowOf(success(HD, addon = "Other")) }
        streams.responses["alt2"] = { flowOf(success(OTHER_HD, addon = "Third")) }
        newResolver().resolveAll(MERGED)
        SportsLiveExtraSources.register(MERGED.id, MERGED)
        // Labeled by source, as the panel lists them.
        assertEquals(listOf(HD.copy(addonName = "Other"), OTHER_HD), SportsLiveExtraSources.cachedStreamsFor(MERGED.id))

        SportsLiveExtraSources.reset()
        streams.responses["alt1"] = { flow { awaitCancellation() } }
        newResolver().resolveAll(MERGED)
        SportsLiveExtraSources.register(MERGED.id, MERGED)
        assertTrue(SportsLiveExtraSources.cachedStreamsFor(MERGED.id).isEmpty())
    }

    @Test
    fun `a failing preparation check on one alternate does not lose the others`() = runTest {
        preparationCheckThrows = true
        streams.responses["game"] = { flowOf(success(WEB)) }
        streams.responses["alt1"] = { flowOf(success(PREPARE_ALT, addon = "Other")) }
        streams.responses["alt2"] = { flowOf(success(OTHER_HD, addon = "Third")) }

        assertEquals(OTHER_HD, newResolver().resolve(MERGED))
    }

    @Test
    fun `resolving another merged game cancels the previous game's running fetches`() = runTest {
        var cancelled = 0
        streams.responses["game"] = { flowOf(success(SD)) }
        streams.responses["game2"] = { flowOf(success(HD)) }
        streams.responses["alt1"] = { flow { try { awaitCancellation() } finally { cancelled++ } } }
        streams.responses["alt2"] = { flowOf(success(OTHER_HD, addon = "Third")) }
        val resolver = newResolver()

        assertEquals(SD, resolver.resolve(MERGED))
        runCurrent()
        // The same game again keeps its fetches.
        assertEquals(SD, resolver.resolve(MERGED))
        runCurrent()
        assertEquals(0, cancelled)

        assertEquals(HD, resolver.resolve(MERGED.copy(meta = MERGED.meta.copy(id = "game2"))))
        runCurrent()
        // Only the last fetch of the previous game was still running; the new game's alt1 runs on.
        assertEquals(1, cancelled)
    }

    @Test
    fun `a cached resolve is reused within the TTL and re-fetches after it expires`() = runTest {
        var now = 0L
        resolveCache.nowMs = { now }
        streams.response = { result(SD) }
        val resolver = newResolver()

        assertEquals(SD, resolver.resolve(ITEM))
        assertEquals(SD, resolver.resolve(ITEM))
        assertEquals(1, streams.calls.size) // second resolve served from cache

        now += SportsLiveResolveCache.TTL_MS - 1
        assertEquals(SD, resolver.resolve(ITEM))
        assertEquals(1, streams.calls.size) // still within the TTL

        now += 1
        assertEquals(SD, resolver.resolve(ITEM))
        assertEquals(2, streams.calls.size) // entry expired, re-fetched
    }

    @Test
    fun `forceRefresh ignores the cache and refreshes the entry`() = runTest {
        streams.response = { result(SD) }
        val resolver = newResolver()

        assertEquals(SD, resolver.resolve(ITEM))
        assertEquals(SD, resolver.resolve(ITEM, forceRefresh = true))
        assertEquals(2, streams.calls.size)
    }

    @Test
    fun `invalidate drops the cached resolve so the next resolve re-fetches`() = runTest {
        streams.response = { result(SD) }
        val resolver = newResolver()

        resolver.resolve(ITEM)
        resolver.invalidate(ITEM)
        resolver.resolve(ITEM)
        assertEquals(2, streams.calls.size)
    }

    @Test
    fun `an errored key invalidated by content id is re-resolved next time`() = runTest {
        streams.response = { result(SD) }
        val resolver = newResolver()

        resolver.resolve(ITEM)
        // The full player, which knows only the content id, evicts the stream that just failed.
        resolver.invalidateByContentId(ITEM.id)
        resolver.resolve(ITEM)
        assertEquals(2, streams.calls.size)
    }

    @Test
    fun `the pick follows the working links - quarantined skipped, known good preferred, all quarantined still plays`() = runTest {
        streams.response = { result(SD, HD) }
        // The ordering rules themselves are SportsLiveWorkingLinksTest's; this is the resolver's wiring.
        // Each case starts from empty links and a fresh resolver (empty cache).
        links.quarantine(ITEM.id, SD.url)
        assertEquals("a quarantined link is skipped", HD, newResolver().resolve(ITEM))

        links.clear()
        links.markGood(ITEM.id, HD.url)
        assertEquals("a link known to work is preferred", HD, newResolver().resolve(ITEM))

        links.clear()
        links.quarantine(ITEM.id, SD.url)
        links.quarantine(ITEM.id, HD.url)
        assertEquals("every link quarantined: the usual pick plays", SD, newResolver().resolve(ITEM))
    }

    @Test
    fun `a link known to play the game is served first, with no search (#173 Watch)`() = runTest {
        streams.response = { result(SD, HD) }
        links.noteLaunch(ITEM.id, HD)
        links.markGood(ITEM.id, HD.url)
        assertEquals(HD, newResolver().resolve(ITEM))
        assertTrue(streams.calls.isEmpty())
    }

    @Test
    fun `a cached link quarantined since is looked up again`() = runTest {
        streams.response = { result(SD, HD) }
        val resolver = newResolver()
        assertEquals(SD, resolver.resolve(ITEM))
        links.quarantine(ITEM.id, SD.url)

        assertEquals(HD, resolver.resolve(ITEM))
        assertEquals(2, streams.calls.size)
    }

    @Test
    fun `a game with only quarantined links is searched at most once per quarantine window`() = runTest {
        streams.response = { result(SD, HD) }
        links.quarantine(ITEM.id, SD.url)
        links.quarantine(ITEM.id, HD.url)
        val resolver = newResolver()
        resolveCache.nowMs = { linkNow }

        repeat(3) {
            assertEquals(SD, resolver.resolve(ITEM))
            // Past the resolve cache's life, still inside the quarantine window.
            linkNow += SportsLiveResolveCache.TTL_MS + 1
        }
        assertEquals(1, streams.calls.size)

        // Now past the quarantine window: one more search.
        assertTrue(linkNow > SportsLiveWorkingLinks.QUARANTINE_MS)
        assertEquals(SD, resolver.resolve(ITEM))
        assertEquals(2, streams.calls.size)
    }

    @Test
    fun `a merged game skips the primary's quarantined link for another source's`() = runTest {
        streams.responses["game"] = { flowOf(success(SD)) }
        streams.responses["alt1"] = { flowOf(success(HD, addon = "Other")) }
        streams.responses["alt2"] = { flowOf(success()) }
        links.quarantine(MERGED.id, SD.url)

        assertEquals(HD, newResolver().resolve(MERGED))
    }

    @Test
    fun `a merged game with only quarantined links plays one of them`() = runTest {
        streams.responses["game"] = { flowOf(success(SD)) }
        streams.responses["alt1"] = { flowOf(success()) }
        streams.responses["alt2"] = { flowOf(success()) }
        links.quarantine(MERGED.id, SD.url)

        assertEquals(SD, newResolver().resolve(MERGED))
    }

    // ---- #147: learned order, the probe race and the check-ahead ----

    private val raceSd = stream(url = "https://cdn.example.invalid/sd.m3u8", name = "Sports 720p")
    private val raceHd = stream(url = "https://cdn.example.invalid/hd.m3u8", name = "Sports 1080p")
    private val raceOther = stream(url = "https://other.example.invalid/live.m3u8", name = "Other live", addonName = "Other")

    private fun twoAddons(): Flow<NetworkResult<List<AddonStreams>>> = flowOf(
        NetworkResult.Loading,
        NetworkResult.Success(
            listOf(
                AddonStreams(addonName = "Sports", addonLogo = null, streams = listOf(raceSd, raceHd)),
                AddonStreams(addonName = "Other", addonLogo = null, streams = listOf(raceOther))
            )
        )
    )

    /** Probes answer per link after the given delay; every probe started is counted per addon. */
    private val probed = mutableListOf<String>()

    private fun TestScope.racingResolver(answers: Map<Stream, Pair<Long, ProbeVerdict>>): SportsLiveZapResolver {
        settings = PlayerSettings(streamAutoPlayMode = StreamAutoPlayMode.MANUAL)
        return newResolver().also { resolver ->
            resolver.probe = { link ->
                probed += link.addonName
                val (wait, verdict) = answers[link] ?: (0L to ProbeVerdict.UNKNOWN)
                delay(wait)
                verdict
            }
        }
    }

    @Test
    fun `a start races one link per addon and plays the first that proves live`() = runTest {
        streams.response = { twoAddons() }
        val resolver = racingResolver(mapOf(raceSd to (1_500L to ProbeVerdict.OK), raceOther to (300L to ProbeVerdict.OK)))

        assertEquals(raceOther, resolver.resolve(ITEM))
        assertEquals(listOf("Other", "Sports"), probed.sorted())
        assertEquals(SportsLiveReadyMark.READY, readiness.of(ITEM))
        // The winner's probe teaches the order; the cancelled loser teaches nothing.
        assertTrue(speed.rank(ITEM.id, "Other").rate > 0.5)
        assertEquals(0.5, speed.rank(ITEM.id, "Sports").rate, 1e-9)
    }

    @Test
    fun `a dead first link is skipped for a link that may play`() = runTest {
        streams.response = { twoAddons() }
        val resolver = racingResolver(mapOf(raceSd to (50L to ProbeVerdict.DEAD), raceOther to (3_500L to ProbeVerdict.OK)))

        // Other never answered in time and Sports' best was dead: the start goes to the next link not found dead.
        assertEquals(raceHd, resolver.resolve(ITEM))
        assertTrue(links.isQuarantined(ITEM.id, raceSd.url))
        assertTrue(speed.rank(ITEM.id, "Sports").rate < 0.5)
    }

    @Test
    fun `no race with one addon, under autoplay filters, or for a link known to work`() = runTest {
        streams.response = { result(raceSd, raceHd) }
        assertEquals(raceSd, racingResolver(emptyMap()).resolve(ITEM))
        assertTrue(probed.isEmpty())

        streams.response = { twoAddons() }
        val filtered = racingResolver(emptyMap())
        settings = PlayerSettings(streamAutoPlayMode = StreamAutoPlayMode.REGEX_MATCH, streamAutoPlayRegex = "720p")
        assertEquals(raceSd, filtered.resolve(ITEM))
        assertTrue(probed.isEmpty())

        val known = racingResolver(emptyMap())
        links.markGood(ITEM.id, raceSd.url)
        assertEquals(raceSd, known.resolve(ITEM))
        assertTrue(probed.isEmpty())
    }

    @Test
    fun `links of the addon that starts fastest are tried first`() = runTest {
        settings = PlayerSettings(streamAutoPlayMode = StreamAutoPlayMode.MANUAL)
        streams.response = { twoAddons() }
        val resolver = newResolver()
        speed.recordFailure("yesterday", "Sports")
        speed.recordStart("yesterday", "Other", 1_200)

        assertEquals(raceOther, resolver.resolve(ITEM))
    }

    @Test
    fun `a check-ahead marks a game ready and caches the live link for OK and CH`() = runTest {
        streams.response = { twoAddons() }
        val resolver = racingResolver(mapOf(raceSd to (200L to ProbeVerdict.DEAD), raceOther to (400L to ProbeVerdict.OK)))

        resolver.checkAhead(ITEM)

        assertEquals(SportsLiveReadyMark.READY, readiness.of(ITEM))
        assertEquals(raceOther, resolveCache.get(ITEM.streamKey))
        // The zap that follows plays it with no new search.
        val searches = streams.calls.size
        assertEquals(raceOther, resolver.resolve(ITEM))
        assertEquals(searches, streams.calls.size)
    }

    @Test
    fun `a check-ahead marks no working stream only after a full search`() = runTest {
        streams.response = { flowOf(NetworkResult.Loading, success()) }
        racingResolver(emptyMap()).checkAhead(ITEM)
        assertEquals(SportsLiveReadyMark.NO_STREAM, readiness.of(ITEM))

        // Every best link probed dead: no working stream either.
        readiness.clear()
        streams.response = { twoAddons() }
        racingResolver(mapOf(raceSd to (10L to ProbeVerdict.DEAD), raceOther to (10L to ProbeVerdict.DEAD))).checkAhead(ITEM)
        assertEquals(SportsLiveReadyMark.NO_STREAM, readiness.of(ITEM))

        // A search that did not finish says nothing.
        readiness.clear()
        streams.response = { flow { emit(NetworkResult.Loading); awaitCancellation() } }
        racingResolver(emptyMap()).checkAhead(ITEM)
        assertNull(readiness.of(ITEM))
    }

    // ---- live.20 1b: the HARD RULE, "▶ Ready" holds its verified link and OK plays it ----

    @Test
    fun `OK on a Ready game plays the verified link`() = runTest {
        streams.response = { twoAddons() }
        val resolver = racingResolver(mapOf(raceSd to (200L to ProbeVerdict.DEAD), raceOther to (400L to ProbeVerdict.OK)))
        resolver.checkAhead(ITEM)
        assertEquals(SportsLiveReadyMark.READY, readiness.of(ITEM))
        assertEquals(raceOther, readiness.heldLink(ITEM))

        // Even with the resolve cache emptied and another link known good for the game, OK plays the held link,
        // at once: no search and no race.
        resolveCache.clear()
        links.startedPlaying(ITEM.id, raceHd)
        links.markGood(ITEM.id, raceHd.url)
        val searches = streams.calls.size
        val probes = probed.size
        assertEquals(SportsLiveLookup.Found(raceOther), resolver.lookup(ITEM))
        assertEquals(searches, streams.calls.size)
        assertEquals(probes, probed.size)
    }

    @Test
    fun `a Ready game whose held link failed is no longer Ready and is looked up again`() = runTest {
        streams.response = { twoAddons() }
        val resolver = racingResolver(mapOf(raceOther to (100L to ProbeVerdict.OK)))
        resolver.checkAhead(ITEM)
        assertEquals(raceOther, readiness.heldLink(ITEM))

        readiness.linkFailed(ITEM.id, raceOther.url)
        links.failed(ITEM.id, raceOther.url)
        assertNull(readiness.of(ITEM))
        assertTrue(resolver.lookup(ITEM) is SportsLiveLookup.Found)
        assertTrue((resolver.lookup(ITEM) as SportsLiveLookup.Found).stream != raceOther)
    }

    @Test
    fun `refreshing a Ready game probes only its held link and keeps it held`() = runTest {
        streams.response = { twoAddons() }
        val resolver = racingResolver(mapOf(raceSd to (100L to ProbeVerdict.DEAD), raceOther to (100L to ProbeVerdict.OK)))
        resolver.checkAhead(ITEM)
        val searches = streams.calls.size
        probed.clear()

        resolver.refreshReady(ITEM)

        assertEquals(listOf("Other"), probed)
        assertEquals(searches, streams.calls.size)
        assertEquals(raceOther, readiness.heldLink(ITEM))
        assertEquals(raceOther, resolveCache.get(ITEM.streamKey))
    }

    @Test
    fun `a held link that no longer proves live loses Ready and the game is checked afresh`() = runTest {
        streams.response = { twoAddons() }
        var otherVerdict = ProbeVerdict.OK
        val resolver = racingResolver(emptyMap())
        resolver.probe = { link ->
            probed += link.addonName
            if (link == raceOther) otherVerdict else ProbeVerdict.DEAD
        }
        resolver.checkAhead(ITEM)
        assertEquals(raceOther, readiness.heldLink(ITEM))

        otherVerdict = ProbeVerdict.DEAD
        resolver.refreshReady(ITEM)

        assertNull(readiness.heldLink(ITEM))
        assertEquals(SportsLiveReadyMark.NO_STREAM, readiness.of(ITEM))
        assertTrue(links.isQuarantined(ITEM.id, raceOther.url))
    }

    @Test
    fun `a kickoff link verified as the player opens it is held, a dead one is not`() = runTest {
        val resolver = racingResolver(mapOf(raceSd to (10L to ProbeVerdict.OK), raceOther to (10L to ProbeVerdict.DEAD)))

        assertEquals(ProbeVerdict.DEAD, resolver.verifyAndHold(ITEM, raceOther))
        assertNull(readiness.of(ITEM))
        assertEquals(ProbeVerdict.OK, resolver.verifyAndHold(ITEM, raceSd))
        assertEquals(raceSd, readiness.heldLink(ITEM))
        assertEquals(SportsLiveLookup.Found(raceSd), resolver.lookup(ITEM))
        assertTrue(streams.calls.isEmpty())
    }

    @Test
    fun `a check-ahead leaves a game on a rate-limited addon alone`() = runTest {
        streams.response = { twoAddons() }
        val resolver = racingResolver(mapOf(raceSd to (10L to ProbeVerdict.OK)))
        resolver.cooldown = SportsLiveSourceCooldown(nowMs = { 0L }).also { it.note(ITEM.addonBaseUrl, "Sports", 5) }

        assertTrue(resolver.checkAheadSkips(ITEM))
        resolver.checkAhead(ITEM)
        assertTrue(streams.calls.isEmpty())
        assertTrue(probed.isEmpty())
        assertNull(readiness.of(ITEM))
    }

    private class FakeStreamRepository : StreamRepository {
        var response: () -> Flow<NetworkResult<List<AddonStreams>>> = { flowOf() }

        /** Per video id; ids not listed get [response]. */
        val responses = mutableMapOf<String, () -> Flow<NetworkResult<List<AddonStreams>>>>()
        val calls = mutableListOf<Triple<String, String, Boolean>>()

        override fun getStreamsFromAllAddons(
            type: String,
            videoId: String,
            season: Int?,
            episode: Int?,
            forceRefresh: Boolean,
            priorityBingeGroup: String?,
            preferredPluginOnly: Boolean,
            continuePluginOnly: Boolean
        ): Flow<NetworkResult<List<AddonStreams>>> = flow {
            calls += Triple(type, videoId, forceRefresh)
            emitAll((responses[videoId] ?: response)())
        }

        override fun setLocalPluginSearchPaused(paused: Boolean) = Unit

        override suspend fun getCachedStreamsFromAllAddons(
            type: String,
            videoId: String,
            season: Int?,
            episode: Int?
        ): List<AddonStreams>? = null

        override suspend fun getStreamsFromAddon(addon: Addon, type: String, videoId: String) =
            NetworkResult.Success(emptyList<Stream>())
    }

    private companion object {
        val SD = stream(url = "https://cdn.example/sd.m3u8", name = "Sports 720p")
        val HD = stream(url = "https://cdn.example/hd.m3u8", name = "Sports 1080p")
        val WEB = stream(externalUrl = "https://site.example/watch", name = "Watch on web")
        val OTHER_HD = stream(url = "https://cdn3.example/hd.m3u8", name = "Third 1080p", addonName = "Third")
        val JUNK = stream(url = "https://site.example/", name = "Support us")
        val PREPARE_ALT = stream(url = "https://cdn2.example/debrid", name = "Other debrid", addonName = "Other")
        val PREPARE_PRIMARY = stream(url = "https://cdn.example/debrid", name = "Sports debrid")
        val NEEDS_PREPARATION = setOf(PREPARE_ALT, PREPARE_PRIMARY)

        fun stream(url: String? = null, externalUrl: String? = null, name: String, addonName: String = "Sports") = Stream(
            name = name,
            title = null,
            description = null,
            url = url,
            ytId = null,
            infoHash = null,
            fileIdx = null,
            externalUrl = externalUrl,
            behaviorHints = null,
            addonName = addonName,
            addonLogo = null
        )

        fun success(vararg streams: Stream, addon: String = "Sports"): NetworkResult<List<AddonStreams>> =
            NetworkResult.Success(listOf(AddonStreams(addonName = addon, addonLogo = null, streams = streams.toList())))

        fun result(vararg streams: Stream): Flow<NetworkResult<List<AddonStreams>>> =
            flowOf(NetworkResult.Loading, success(*streams))

        fun addon(name: String, enabled: Boolean = true) = Addon(
            id = name,
            name = name,
            version = "1",
            description = null,
            logo = null,
            baseUrl = "https://$name.example",
            catalogs = emptyList(),
            types = emptyList(),
            resources = emptyList(),
            enabled = enabled
        )

        val ITEM = SportsLiveItem(
            meta = MetaPreview(
                id = "game",
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
            addonId = "sports",
            addonName = "Sports",
            addonBaseUrl = "https://sports.example",
            catalogKey = "sports_sport_live",
            catalogName = "Live",
            kind = SportsLiveKind.EVENTS,
            isLive = true,
            isChannel = false,
            startMs = null,
            sport = null
        )

        val ALT1 = ITEM.copy(meta = ITEM.meta.copy(id = "alt1"), addonId = "other", addonName = "Other")
        val ALT2 = ITEM.copy(meta = ITEM.meta.copy(id = "alt2"), addonId = "third", addonName = "Third")
        val ALT3 = ITEM.copy(meta = ITEM.meta.copy(id = "alt3"), addonId = "fourth", addonName = "Fourth")
        val MERGED = ITEM.copy(alternates = listOf(ALT1, ALT2))
    }
}
