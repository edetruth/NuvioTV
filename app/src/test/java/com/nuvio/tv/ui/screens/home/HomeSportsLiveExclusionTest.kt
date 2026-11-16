package com.nuvio.tv.ui.screens.home

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.TmdbSettings
import com.nuvio.tv.domain.model.catalogRowLegacyKey
import com.nuvio.tv.domain.repository.SportsLiveLoad
import com.nuvio.tv.domain.repository.SportsLiveRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Catalogs claimed by Sports & Live leave the Home rows, and live items get a Live now row. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HomeSportsLiveExclusionTest {

    private val created = mutableListOf<HomeViewModel>()
    private val claimedKeys = MutableStateFlow<Set<String>>(emptySet())
    private val liveNow = MutableStateFlow<List<SportsLiveItem>>(emptyList())
    private lateinit var sportsLiveRepository: SportsLiveRepository

    @Before
    fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @After
    fun tearDown() {
        created.forEach { it.viewModelScope.cancel() }
        created.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `claimed catalog keys are left out of the home catalog order`() {
        claimedKeys.value = setOf(SPORTS_KEY)
        val viewModel = newViewModel()

        viewModel.rebuildCatalogOrder(listOf(addon()))

        assertEquals(listOf(MOVIES_KEY), viewModel.snapshotCatalogState().first)
    }

    @Test
    fun `clearing claimed keys brings the catalog back without restart`() {
        claimedKeys.value = setOf(SPORTS_KEY)
        val viewModel = newViewModel()
        val addons = listOf(addon())
        // Home has finished a load while the sports catalog was claimed.
        viewModel.addonsCache = addons
        viewModel.rebuildCatalogOrder(addons)
        synchronized(viewModel.catalogStateLock) { viewModel.catalogsMap[MOVIES_KEY] = moviesRow() }
        viewModel.activeCatalogLoadSignature = viewModel.buildHomeCatalogLoadSignature(addons)
        val generationBefore = viewModel.catalogLoadGeneration

        claimedKeys.value = emptySet()

        assertEquals(listOf(MOVIES_KEY, SPORTS_KEY), viewModel.snapshotCatalogState().first)
        assertTrue(
            "the home catalogs are loaded again",
            viewModel.catalogLoadGeneration > generationBefore
        )
    }

    @Test
    fun `live now items produce a first HomeRow Catalog with the sentinel addon id`() {
        val viewModel = newViewModel()
        viewModel.rebuildCatalogOrder(listOf(addon()))
        synchronized(viewModel.catalogStateLock) { viewModel.catalogsMap[MOVIES_KEY] = moviesRow() }
        val items = listOf(SportsLiveHomeRowTest.item("match-1"))

        liveNow.value = items
        runBlocking { viewModel.updateCatalogRowsPipeline() }

        val rows = viewModel._uiState.value.homeRows
        val first = rows.first() as HomeRow.Catalog
        assertEquals(SportsLiveHomeRow.ADDON_ID, first.row.addonId)
        assertEquals("Live now", first.row.catalogName)
        assertEquals(items.map { it.meta }, first.row.items)
        assertEquals(2, rows.size)
        assertEquals(items.first(), viewModel.findLiveNowItem("match-1"))
    }

    @Test
    fun `focusing a live now card tells the hero to stop waiting for enrichment`() {
        val viewModel = newViewModel()
        val item = SportsLiveHomeRowTest.item("match-1")
        liveNow.value = listOf(item)

        viewModel.onItemFocus(item.meta)

        assertTrue(item.id in viewModel.failedEnrichmentIds.value)
    }

    @Test
    fun `the Home Sports & Live load leaves channels catalogs to be carried over`() {
        val viewModel = newViewModel()

        viewModel.refreshSportsLiveInBackground()

        coVerify { sportsLiveRepository.load(false, false) }
        coVerify(exactly = 0) { sportsLiveRepository.load(any(), true) }
    }

    @Test
    fun `no live now row when liveNow is empty`() {
        val viewModel = newViewModel()
        viewModel.rebuildCatalogOrder(listOf(addon()))
        synchronized(viewModel.catalogStateLock) { viewModel.catalogsMap[MOVIES_KEY] = moviesRow() }

        runBlocking { viewModel.updateCatalogRowsPipeline() }

        val rows = viewModel._uiState.value.homeRows
        assertEquals(1, rows.size)
        assertTrue(rows.none { it is HomeRow.Catalog && it.row.addonId == SportsLiveHomeRow.ADDON_ID })
    }

    private fun addon() = Addon(
        id = ADDON_ID,
        name = "Mixed",
        version = "1.0.0",
        description = null,
        logo = null,
        baseUrl = "https://mixed.example",
        catalogs = listOf(
            CatalogDescriptor(type = ContentType.MOVIE, id = "popular", name = "Popular"),
            CatalogDescriptor(type = ContentType.UNKNOWN, rawType = "tv", id = "live", name = "Live sports")
        ),
        types = listOf(ContentType.MOVIE),
        resources = emptyList()
    )

    private fun moviesRow() = CatalogRow(
        addonId = ADDON_ID,
        addonName = "Mixed",
        addonBaseUrl = "https://mixed.example",
        catalogId = "popular",
        catalogName = "Popular",
        type = ContentType.MOVIE,
        items = listOf(SportsLiveHomeRowTest.item("movie-1").meta)
    )

    private fun newViewModel(): HomeViewModel {
        val profileManager = mockk<com.nuvio.tv.core.profile.ProfileManager>(relaxed = true) {
            every { activeProfileReady } returns MutableStateFlow(false)
            every { activeProfileId } returns MutableStateFlow(1)
        }
        val cwEnrichmentCache =
            mockk<com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache>(relaxed = true) {
                every { cacheCleared } returns MutableStateFlow(0)
            }
        val watchProgressRepository = mockk<WatchProgressRepository>(relaxed = true) {
            every { getAllEpisodeProgress(any()) } returns flowOf(emptyMap())
            every { observeWatchedMovieIds() } returns MutableStateFlow(emptySet())
        }
        sportsLiveRepository = mockk<SportsLiveRepository>(relaxed = true) {
            every { claimedCatalogKeys } returns claimedKeys
            every { liveNow(any()) } returns liveNow
            every { enabled } returns flowOf(true)
            coEvery { load(any(), any()) } returns SportsLiveLoad(emptyList(), emptyList(), 0L, emptySet())
        }
        val appContext = mockk<Context>(relaxed = true) {
            every { getString(any()) } returns "Live now"
        }
        val viewModel = HomeViewModel(
            episodeShuffleStore = mockk(relaxed = true),
            episodeShuffle = com.nuvio.tv.domain.model.EpisodeShuffle(),
            appContext = appContext,
            addonRepository = mockk(relaxed = true),
            startupSyncService = mockk(relaxed = true),
            catalogRepository = mockk(relaxed = true),
            watchProgressRepository = watchProgressRepository,
            libraryRepository = mockk(relaxed = true),
            metaRepository = mockk(relaxed = true),
            collectionsDataStore = mockk(relaxed = true),
            layoutPreferenceDataStore = mockk(relaxed = true),
            playerSettingsDataStore = mockk(relaxed = true),
            tmdbSettingsDataStore = mockk(relaxed = true),
            mdbListSettingsDataStore = mockk(relaxed = true),
            traktSettingsDataStore = mockk(relaxed = true),
            authSessionNoticeDataStore = mockk(relaxed = true),
            tmdbService = mockk(relaxed = true),
            tmdbMetadataService = mockk(relaxed = true),
            mdbListRepository = mockk(relaxed = true),
            imdbEpisodeRatingsRepository = mockk(relaxed = true),
            trailerService = mockk(relaxed = true),
            watchedSeriesStateHolder = mockk(relaxed = true),
            cwEnrichmentCache = cwEnrichmentCache,
            profileManager = profileManager,
            tvRecommendationManager = mockk(relaxed = true),
            serverCatalog = mockk(relaxed = true),
            sportsLiveRepository = sportsLiveRepository
        )
        viewModel.startupGracePeriodActive = false
        viewModel.currentTmdbSettings = TmdbSettings(enabled = false)
        created += viewModel
        return viewModel
    }

    private companion object {
        const val ADDON_ID = "mixed.addon"
        val MOVIES_KEY = catalogRowLegacyKey(ADDON_ID, "movie", "popular")
        val SPORTS_KEY = catalogRowLegacyKey(ADDON_ID, "tv", "live")
    }
}
