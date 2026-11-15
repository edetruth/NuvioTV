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
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveOverride
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

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

    private fun newRepository() =
        SportsLiveRepositoryImpl(addonRepository, catalogRepository, prefs).also {
            it.nowMs = { now }
            it.ioDispatcher = mainDispatcherRule.dispatcher
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

    private class FakeCatalogRepository : CatalogRepository {
        val results = mutableMapOf<Pair<String, String>, NetworkResult<CatalogRow>>()
        val throwing = mutableSetOf<Pair<String, String>>()
        val hanging = mutableSetOf<Pair<String, String>>()
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
            emit(NetworkResult.Loading)
            val key = addonId to catalogId
            if (key in throwing) throw IllegalStateException("boom")
            if (key in hanging) awaitCancellation()
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

        fun meta(id: String, live: Boolean = false) = MetaPreview(
            id = id,
            type = ContentType.UNKNOWN,
            rawType = "sport",
            name = id,
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
