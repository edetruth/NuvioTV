package com.nuvio.tv.data.repository

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.TestPreferencesStore
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.poster.CustomPosterScreen
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sportslive.SportsLiveCatalogPolicy
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.SportsLivePreferencesDataStore
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogExtra
import com.nuvio.tv.domain.model.CatalogOption
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.CatalogStats
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveOverride
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.domain.repository.StreamRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SportsLiveRepositoryImplTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val liveCatalog = catalog("sport", "live", "Live Now")
    private val upcomingCatalog = catalog("sport", "upcoming", "Upcoming")
    private val channelsCatalog = catalog("tv", "chan", "Channels")
    private val popularCatalog = catalog("movie", "popular", "Popular")
    private val sportsAddon = addon("sports", "Sports Streams", liveCatalog, upcomingCatalog)
    private val genericAddon = addon("generic", "Generic Addon", popularCatalog, channelsCatalog)
    private val disabledAddon = addon("off", "Off Sports", catalog("sport", "off_live", "Live"), enabled = false)
    private val countryCatalog = catalog("tv", "country", "Live TV").copy(
        extra = listOf(
            CatalogExtra("genre", options = listOf("United States", "Canada")),
            CatalogExtra("search")
        )
    )
    private val tvAddon = addon("tvaddon", "TV Addon", countryCatalog)
    private val countryKey = SportsLiveCatalogPolicy.keyFor(tvAddon, countryCatalog)
    private val us = CatalogOption("genre", "United States")

    private val addons = MutableStateFlow(listOf(sportsAddon, genericAddon, disabledAddon))
    private val addonRepository = mockk<AddonRepository> {
        every { getInstalledAddons() } returns addons
    }
    private val catalogRepository = FakeCatalogRepository()
    private val prefsStore = TestPreferencesStore()
    private val factory = mockk<ProfileDataStoreFactory> {
        every { get(1, SportsLivePreferencesDataStore.FEATURE) } returns prefsStore
    }
    private val profiles = mockk<ProfileManager> {
        every { activeProfileId } returns MutableStateFlow(1)
    }
    private val prefs = SportsLivePreferencesDataStore(factory, profiles)
    private var now = 1_700_000_000_000L
    private val streamRepository = FakeStreamRepository()
    private val probe = SportsLiveStreamProbe(streamRepository).also {
        it.nowMs = { now }
        it.dispatcher = mainDispatcherRule.dispatcher
    }

    private fun newRepository() =
        SportsLiveRepositoryImpl(addonRepository, catalogRepository, prefs, probe).also {
            it.nowMs = { now }
            it.ioDispatcher = mainDispatcherRule.dispatcher
            it.computeDispatcher = mainDispatcherRule.dispatcher
        }

    @Test
    fun `claimed catalogs come only from enabled addons that the policy claims`() = runTest {
        val repository = newRepository()

        val claimed = repository.claimedCatalogs.first()

        assertEquals(
            setOf("sports" to "live", "sports" to "upcoming", "generic" to "chan"),
            claimed.map { it.addon.id to it.catalog.id }.toSet()
        )
        assertEquals(
            setOf(
                SportsLiveCatalogPolicy.keyFor(sportsAddon, liveCatalog),
                SportsLiveCatalogPolicy.keyFor(sportsAddon, upcomingCatalog),
                SportsLiveCatalogPolicy.keyFor(genericAddon, channelsCatalog)
            ),
            repository.claimedCatalogKeys.first()
        )
        assertTrue(repository.sectionAvailable.first())
    }

    @Test
    fun `disabled master switch yields no claimed catalogs and sectionAvailable false`() = runTest {
        val repository = newRepository()
        prefs.setShowSection(false)

        assertFalse(repository.enabled.first())
        assertEquals(emptyList<Any>(), repository.claimedCatalogs.first())
        assertEquals(emptySet<String>(), repository.claimedCatalogKeys.first())
        assertFalse(repository.sectionAvailable.first())
    }

    @Test
    fun `load returns items from all claimed catalogs and records failures separately`() = runTest {
        catalogRepository.results["sports" to "live"] = success("sports", "live", meta("a", live = true))
        catalogRepository.results["sports" to "upcoming"] = success("sports", "upcoming", meta("b"), meta("c"))
        catalogRepository.results["generic" to "chan"] = NetworkResult.Error("boom")
        val repository = newRepository()

        val load = repository.load()

        assertEquals(setOf("a", "b", "c"), load.items.map { it.id }.toSet())
        assertEquals(1, load.failures.size)
        assertEquals("chan", load.failures.single().catalog.catalog.id)
        assertEquals("boom", load.failures.single().message)
        assertEquals(now, load.loadedAtMs)
        assertEquals(load, repository.latest.value)
    }

    @Test
    fun `load publishes recognition stats for the catalogs it loaded`() = runTest {
        catalogRepository.results["sports" to "live"] = success("sports", "live", meta("a", live = true), meta("b"))
        catalogRepository.results["sports" to "upcoming"] = success("sports", "upcoming", meta("c"))
        catalogRepository.results["generic" to "chan"] = NetworkResult.Error("boom")
        val repository = newRepository()
        assertEquals(emptyMap<String, CatalogStats>(), repository.catalogStats.value)

        repository.load()

        assertEquals(
            mapOf(
                SportsLiveCatalogPolicy.keyFor(sportsAddon, liveCatalog) to CatalogStats(live = 1, timed = 1),
                SportsLiveCatalogPolicy.keyFor(sportsAddon, upcomingCatalog) to CatalogStats(timed = 1)
            ),
            repository.catalogStats.value
        )
    }

    @Test
    fun `load within 60 seconds returns cached result unless forced`() = runTest {
        val repository = newRepository()

        val first = repository.load()
        assertEquals(3, catalogRepository.calls)

        now += 59_000
        assertEquals(first, repository.load())
        assertEquals(3, catalogRepository.calls)

        repository.load(force = true)
        assertEquals(6, catalogRepository.calls)

        now += 61_000
        repository.load()
        assertEquals(9, catalogRepository.calls)
    }

    @Test
    fun `a load without channels carries the previous channels items and refetches the rest`() = runTest {
        catalogRepository.results["sports" to "live"] = success("sports", "live", meta("a", live = true))
        catalogRepository.results["generic" to "chan"] = success("generic", "chan", meta("ch1"))
        val repository = newRepository()
        repository.load()
        assertEquals(3, catalogRepository.calls)

        catalogRepository.results["sports" to "live"] = success("sports", "live", meta("a2", live = true))
        catalogRepository.results["generic" to "chan"] = success("generic", "chan", meta("ch2"))
        val partial = repository.load(force = true, includeChannels = false)

        assertEquals(5, catalogRepository.calls)
        assertEquals(setOf("a2", "ch1"), partial.items.map { it.id }.toSet())
        assertEquals(partial, repository.latest.value)

        val full = repository.load(force = true)
        assertEquals(8, catalogRepository.calls)
        assertTrue(full.items.any { it.id == "ch2" })
        assertFalse(full.items.any { it.id == "ch1" })
    }

    @Test
    fun `routine loads without channels fetch channels cold, then reuse them once the cache is stale`() = runTest {
        catalogRepository.results["generic" to "chan"] = success("generic", "chan", meta("ch1"))
        val repository = newRepository()
        // Home's load and the section's on-visible load: nothing cached yet, so channels are fetched.
        val cold = repository.load(includeChannels = false)
        assertEquals(3, catalogRepository.calls)
        assertTrue(cold.items.any { it.id == "ch1" })

        // Returning after the 60 s cache went stale refetches events, not the channels catalog.
        now += 61_000
        catalogRepository.results["generic" to "chan"] = success("generic", "chan", meta("ch2"))
        val home = repository.load(includeChannels = false)
        assertEquals(5, catalogRepository.calls)
        assertTrue(home.items.any { it.id == "ch1" })
        assertFalse(home.items.any { it.id == "ch2" })

        now += 61_000
        repository.load(force = false, includeChannels = false)
        assertEquals(7, catalogRepository.calls)
    }

    @Test
    fun `a load without channels refetches channels from another install of the same addon`() = runTest {
        catalogRepository.results["generic" to "chan"] = success("generic", "chan", meta("ch1"))
        val repository = newRepository()
        repository.load()
        assertEquals(3, catalogRepository.calls)

        // Another profile installs the same addon under a different manifest URL: same catalog keys.
        addons.value = listOf(sportsAddon, genericAddon.copy(baseUrl = "https://generic-b.example.test"), disabledAddon)
        catalogRepository.results["generic" to "chan"] = success("generic", "chan", meta("ch2"))
        val reload = repository.load(force = true, includeChannels = false)

        assertEquals(6, catalogRepository.calls)
        val channel = reload.items.single { it.catalogKey == SportsLiveCatalogPolicy.keyFor(genericAddon, channelsCatalog) }
        assertEquals("ch2", channel.id)
        assertEquals("https://generic-b.example.test", channel.addonBaseUrl)
    }

    @Test
    fun `a load without channels refetches channels when the claimed catalogs changed`() = runTest {
        catalogRepository.results["generic" to "chan"] = success("generic", "chan", meta("ch1"))
        val repository = newRepository()
        repository.load()
        assertEquals(3, catalogRepository.calls)

        prefs.setOverride(SportsLiveCatalogPolicy.keyFor(sportsAddon, upcomingCatalog), SportsLiveOverride.EXCLUDE)
        catalogRepository.results["generic" to "chan"] = success("generic", "chan", meta("ch2"))
        val reload = repository.load(force = true, includeChannels = false)

        assertEquals(5, catalogRepository.calls)
        assertTrue(reload.items.any { it.id == "ch2" })
        assertFalse(reload.items.any { it.id == "ch1" })
    }

    @Test
    fun `a load without channels fetches channels that were never loaded or failed`() = runTest {
        catalogRepository.results["generic" to "chan"] = NetworkResult.Error("too big")
        val repository = newRepository()
        repository.load(includeChannels = false)
        assertEquals(3, catalogRepository.calls)

        catalogRepository.results["generic" to "chan"] = success("generic", "chan", meta("ch1"))
        val retried = repository.load(force = true, includeChannels = false)

        assertEquals(6, catalogRepository.calls)
        assertTrue(retried.items.any { it.id == "ch1" })
        assertTrue(retried.failures.isEmpty())
    }

    @Test
    fun `excluding a catalog via override within 60 seconds makes the next non-forced load refetch`() = runTest {
        catalogRepository.results["sports" to "live"] = success("sports", "live", meta("a", live = true))
        catalogRepository.results["sports" to "upcoming"] = success("sports", "upcoming", meta("b"))
        val repository = newRepository()
        assertEquals(setOf("a", "b"), repository.load().items.map { it.id }.toSet())
        assertEquals(3, catalogRepository.calls)

        now += 10_000
        prefs.setOverride(SportsLiveCatalogPolicy.keyFor(sportsAddon, liveCatalog), SportsLiveOverride.EXCLUDE)
        val reloaded = repository.load()

        assertEquals(5, catalogRepository.calls)
        assertEquals(setOf("b"), reloaded.items.map { it.id }.toSet())
    }

    @Test
    fun `a catalog that throws becomes one failure and the others still load`() = runTest {
        catalogRepository.results["sports" to "live"] = success("sports", "live", meta("a", live = true))
        catalogRepository.results["sports" to "upcoming"] = success("sports", "upcoming", meta("b"))
        catalogRepository.throwing += "generic" to "chan"
        val repository = newRepository()

        val load = repository.load()

        assertEquals(setOf("a", "b"), load.items.map { it.id }.toSet())
        assertEquals("chan", load.failures.single().catalog.catalog.id)
        assertEquals("boom", load.failures.single().message)
    }

    @Test
    fun `a catalog that never completes becomes a failure after the timeout`() = runTest {
        catalogRepository.results["sports" to "live"] = success("sports", "live", meta("a", live = true))
        catalogRepository.hanging += "generic" to "chan"
        val repository = newRepository()
        val startedAt = testScheduler.currentTime

        val load = repository.load()

        assertEquals(15_000L, testScheduler.currentTime - startedAt)
        assertEquals(listOf("a"), load.items.map { it.id })
        assertEquals("chan", load.failures.single().catalog.catalog.id)
    }

    @Test
    fun `liveNow is empty when showHomeRow is false`() = runTest {
        catalogRepository.results["sports" to "live"] = success("sports", "live", meta("a", live = true))
        val repository = newRepository()
        repository.load()
        assertEquals(listOf("a"), repository.liveNow().first().map { it.id })

        prefs.setShowHomeRow(false)

        assertEquals(emptyList<Any>(), repository.liveNow().first())
    }

    @Test
    fun `liveNow puts followed teams' games first and follows a change at once`() = runTest {
        catalogRepository.results["sports" to "live"] = success(
            "sports", "live",
            meta("a", live = true, name = "Arsenal vs Chelsea"),
            meta("b", live = true, name = "Bulls vs Celtics"),
            meta("c", live = true, name = "Clippers vs Lakers")
        )
        val repository = newRepository()
        repository.load()
        assertEquals(listOf("a", "b", "c"), repository.liveNow().first().map { it.id })

        prefs.follow(FollowedTeam("Boston Celtics", null))

        val live = repository.liveNow().first()
        assertEquals(listOf("b", "a", "c"), live.map { it.id })
        assertEquals(listOf(true, false, false), live.map { it.followed })
        assertEquals(listOf(FollowedTeam("Boston Celtics", null)), repository.followedTeams.first())
    }

    @Test
    fun `liveNow is capped at limit and only contains live items`() = runTest {
        val liveItems = (1..25).map { meta("live$it", live = true) }
        val otherItems = (1..5).map { meta("other$it") }
        catalogRepository.results["sports" to "upcoming"] =
            success("sports", "upcoming", *(liveItems + otherItems).toTypedArray())
        val repository = newRepository()
        repository.load()

        val live = repository.liveNow(limit = 20).first()

        assertEquals(20, live.size)
        assertTrue(live.all { it.isLive && it.id.startsWith("live") })
    }

    @Test
    fun `overrides change reclassifies without restart`() = runTest {
        val repository = newRepository()
        val key = SportsLiveCatalogPolicy.keyFor(sportsAddon, liveCatalog)
        assertTrue(key in repository.claimedCatalogKeys.first())

        prefs.setOverride(key, SportsLiveOverride.EXCLUDE)

        assertFalse(key in repository.claimedCatalogKeys.first())
    }

    @Test
    fun `profile switch mid-load does not publish the old profile's items`() = runTest {
        catalogRepository.results["sports" to "live"] = success("sports", "live", meta("a", live = true))
        val gate = CompletableDeferred<Unit>()
        catalogRepository.gates["sports" to "live"] = gate
        val repository = newRepository()

        val oldLoad = async { repository.load() }
        runCurrent()
        // The other profile has a different sports addon installed.
        val otherAddon = addon("other", "Other Sports", catalog("sport", "live", "Live Now"))
        addons.value = listOf(otherAddon)
        gate.complete(Unit)
        oldLoad.await()

        assertNull(repository.latest.value)
        assertEquals(emptyList<Any>(), repository.liveNow().first())

        catalogRepository.results["other" to "live"] = success("other", "live", meta("b", live = true))
        val newLoad = repository.load()
        assertEquals(listOf("b"), newLoad.items.map { it.id })
        assertEquals(newLoad, repository.latest.value)
        assertEquals(listOf("b"), repository.liveNow().first().map { it.id })
    }

    @Test
    fun `liveNow drops items whose catalog is no longer claimed`() = runTest {
        catalogRepository.results["sports" to "live"] = success("sports", "live", meta("a", live = true))
        catalogRepository.results["sports" to "upcoming"] = success("sports", "upcoming", meta("b", live = true))
        val repository = newRepository()
        repository.load()
        assertEquals(setOf("a", "b"), repository.liveNow().first().map { it.id }.toSet())

        prefs.setOverride(SportsLiveCatalogPolicy.keyFor(sportsAddon, liveCatalog), SportsLiveOverride.EXCLUDE)

        assertEquals(listOf("b"), repository.liveNow().first().map { it.id })
    }

    @Test
    fun `an include override marks the claimed catalog and its items`() = runTest {
        prefs.setOverride(SportsLiveCatalogPolicy.keyFor(genericAddon, popularCatalog), SportsLiveOverride.INCLUDE)
        catalogRepository.results["generic" to "popular"] = success("generic", "popular", meta("m"))
        val repository = newRepository()

        val claimed = repository.claimedCatalogs.first().associateBy { it.catalog.id }
        assertTrue(claimed.getValue("popular").viaOverride)
        assertFalse(claimed.getValue("live").viaOverride)
        assertTrue(repository.load().items.single { it.id == "m" }.viaOverride)
    }

    @Test
    fun `a load issues no stream probes`() = runTest {
        catalogRepository.results["sports" to "live"] =
            success("sports", "live", meta("a", live = true), meta("b", live = true))
        val repository = newRepository()

        repository.load()
        repository.load(force = true)
        advanceUntilIdle()

        assertEquals(emptyList<String>(), streamRepository.calls)
    }

    @Test
    fun `liveNow leaves out live items whose streams are gone`() = runTest {
        catalogRepository.results["sports" to "live"] =
            success("sports", "live", meta("a", live = true), meta("b", live = true))
        streamRepository.noStreams += "b"
        val repository = newRepository()
        val items = repository.load().items

        items.forEach(repository::reprobeIfStale)
        advanceTimeBy(1)

        assertEquals(setOf("sports|b"), repository.endedKeys.value)
        assertEquals(listOf("a"), repository.liveNow().first().map { it.id })
    }

    @Test
    fun `a pinned option is sent as the catalog's extra args and recorded on the load`() = runTest {
        addons.value = listOf(sportsAddon, genericAddon, tvAddon)
        prefs.setCatalogOption(countryKey, us)
        val repository = newRepository()

        assertEquals(us, repository.claimedCatalogs.first().single { it.key == countryKey }.option)
        val load = repository.load()

        assertEquals(mapOf("genre" to "United States"), catalogRepository.requestedExtraArgs["tvaddon" to "country"])
        assertEquals(emptyMap<String, String>(), catalogRepository.requestedExtraArgs["generic" to "chan"])
        assertEquals(emptyMap<String, String>(), catalogRepository.requestedExtraArgs["sports" to "live"])
        assertEquals(mapOf(countryKey to us), load.catalogOptions)
    }

    @Test
    fun `a stored pin the catalog no longer offers loads everything`() = runTest {
        addons.value = listOf(sportsAddon, genericAddon, tvAddon)
        prefs.setCatalogOption(countryKey, CatalogOption("genre", "Atlantis"))
        val repository = newRepository()

        assertNull(repository.claimedCatalogs.first().single { it.key == countryKey }.option)
        val load = repository.load()

        assertEquals(emptyMap<String, String>(), catalogRepository.requestedExtraArgs["tvaddon" to "country"])
        assertEquals(emptyMap<String, CatalogOption>(), load.catalogOptions)
    }

    @Test
    fun `changing a pin within 60 seconds refetches that channels catalog and carries the others`() = runTest {
        addons.value = listOf(sportsAddon, genericAddon, tvAddon)
        catalogRepository.results["generic" to "chan"] = success("generic", "chan", meta("ch1"))
        catalogRepository.results["tvaddon" to "country"] = success("tvaddon", "country", meta("all1"))
        val repository = newRepository()
        repository.load()
        assertEquals(4, catalogRepository.calls)

        now += 10_000
        prefs.setCatalogOption(countryKey, us)
        catalogRepository.results["generic" to "chan"] = success("generic", "chan", meta("ch2"))
        catalogRepository.results["tvaddon" to "country"] = success("tvaddon", "country", meta("us1"))
        val pinned = repository.load(includeChannels = false)

        // The two events catalogs and the re-pinned channels catalog; the other channels catalog is carried.
        assertEquals(7, catalogRepository.calls)
        assertEquals(mapOf("genre" to "United States"), catalogRepository.requestedExtraArgs["tvaddon" to "country"])
        assertEquals(setOf("ch1", "us1"), pinned.items.filter { it.kind == SportsLiveKind.CHANNELS }.map { it.id }.toSet())
        assertEquals(pinned, repository.latest.value)

        // Unchanged pin within 60 s: the cached load.
        assertEquals(pinned, repository.load(includeChannels = false))
        assertEquals(7, catalogRepository.calls)
    }

    @Test
    fun `a pinned load's channels are never carried into an unpinned load`() = runTest {
        addons.value = listOf(sportsAddon, genericAddon, tvAddon)
        prefs.setCatalogOption(countryKey, us)
        catalogRepository.results["generic" to "chan"] = success("generic", "chan", meta("ch1"))
        catalogRepository.results["tvaddon" to "country"] = success("tvaddon", "country", meta("us1"))
        val repository = newRepository()
        repository.load()
        assertEquals(4, catalogRepository.calls)

        prefs.setCatalogOption(countryKey, null)
        catalogRepository.results["tvaddon" to "country"] = success("tvaddon", "country", meta("all1"))
        val unpinned = repository.load(force = true, includeChannels = false)

        assertEquals(7, catalogRepository.calls)
        assertEquals(emptyMap<String, String>(), catalogRepository.requestedExtraArgs["tvaddon" to "country"])
        assertTrue(unpinned.items.any { it.id == "all1" })
        assertFalse(unpinned.items.any { it.id == "us1" })
        assertEquals(emptyMap<String, CatalogOption>(), unpinned.catalogOptions)
    }

    @Test
    fun `a pin changed mid-load does not publish the load`() = runTest {
        addons.value = listOf(sportsAddon, genericAddon, tvAddon)
        val gate = CompletableDeferred<Unit>()
        catalogRepository.gates["tvaddon" to "country"] = gate
        val repository = newRepository()

        val load = async { repository.load() }
        runCurrent()
        prefs.setCatalogOption(countryKey, us)
        gate.complete(Unit)
        load.await()

        assertNull(repository.latest.value)
    }

    private class FakeStreamRepository : StreamRepository {
        val noStreams = mutableSetOf<String>()
        val calls = mutableListOf<String>()

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
            calls += videoId
            emit(NetworkResult.Loading)
            val streams = if (videoId in noStreams) emptyList() else listOf(playableStream())
            emit(NetworkResult.Success(listOf(AddonStreams(addonName = "Sports", addonLogo = null, streams = streams))))
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

        private fun playableStream() = Stream(
            name = "Stream",
            title = null,
            description = null,
            url = "https://cdn.example/live.m3u8",
            ytId = null,
            infoHash = null,
            fileIdx = null,
            externalUrl = null,
            behaviorHints = null,
            addonName = "Sports",
            addonLogo = null
        )
    }

    private class FakeCatalogRepository : CatalogRepository {
        val results = mutableMapOf<Pair<String, String>, NetworkResult<CatalogRow>>()
        val throwing = mutableSetOf<Pair<String, String>>()
        val hanging = mutableSetOf<Pair<String, String>>()
        val gates = mutableMapOf<Pair<String, String>, CompletableDeferred<Unit>>()
        /** The extra args of each catalog's latest request. */
        val requestedExtraArgs = mutableMapOf<Pair<String, String>, Map<String, String>>()
        var calls = 0

        override fun getCatalog(
            addonBaseUrl: String,
            addonId: String,
            addonName: String,
            catalogId: String,
            catalogName: String,
            type: String,
            skip: Int,
            skipStep: Int,
            extraArgs: Map<String, String>,
            supportsSkip: Boolean,
            posterScreen: CustomPosterScreen
        ): Flow<NetworkResult<CatalogRow>> = flow {
            calls++
            val key = addonId to catalogId
            requestedExtraArgs[key] = extraArgs
            emit(NetworkResult.Loading)
            if (key in throwing) throw IllegalStateException("boom")
            if (key in hanging) awaitCancellation()
            gates[key]?.await()
            emit(results[key] ?: success(addonId, catalogId))
        }
    }

    private companion object {
        fun catalog(type: String, id: String, name: String) = CatalogDescriptor(
            type = ContentType.fromString(type),
            rawType = type,
            id = id,
            name = name
        )

        fun addon(id: String, name: String, vararg catalogs: CatalogDescriptor, enabled: Boolean = true) = Addon(
            id = id,
            name = name,
            version = "1.0.0",
            description = null,
            logo = null,
            baseUrl = "https://$id.example.test",
            catalogs = catalogs.toList(),
            types = emptyList(),
            resources = emptyList(),
            enabled = enabled
        )

        fun meta(id: String, live: Boolean = false, name: String = id) = MetaPreview(
            id = id,
            type = ContentType.UNKNOWN,
            rawType = "sport",
            name = name,
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = if (live) "LIVE" else "2099-01-01",
            imdbRating = null,
            genres = emptyList()
        )

        fun success(addonId: String, catalogId: String, vararg items: MetaPreview): NetworkResult<CatalogRow> =
            NetworkResult.Success(
                CatalogRow(
                    addonId = addonId,
                    addonName = addonId,
                    addonBaseUrl = "https://$addonId.example.test",
                    catalogId = catalogId,
                    catalogName = catalogId,
                    type = ContentType.UNKNOWN,
                    rawType = "sport",
                    items = items.toList()
                )
            )
    }
}
