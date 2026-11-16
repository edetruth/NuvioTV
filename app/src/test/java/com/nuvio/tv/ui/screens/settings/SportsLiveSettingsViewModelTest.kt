package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.TestPreferencesStore
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sportslive.SportsLiveCatalogPolicy
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.SportsLivePreferencesDataStore
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogOption
import com.nuvio.tv.domain.model.CatalogStats
import com.nuvio.tv.domain.model.CatalogExtra
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveOverride
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.SportsLiveLoad
import com.nuvio.tv.domain.repository.SportsLiveRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SportsLiveSettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val prefsStore = TestPreferencesStore()
    private val factory = mockk<ProfileDataStoreFactory> {
        every { get(1, SportsLivePreferencesDataStore.FEATURE) } returns prefsStore
    }
    private val profiles = mockk<ProfileManager> {
        every { activeProfileId } returns MutableStateFlow(1)
    }
    private val prefs = SportsLivePreferencesDataStore(factory, profiles)
    private val addonsFlow = MutableStateFlow(listOf(SPORTS_ADDON, PLAIN_ADDON, DISABLED_ADDON))
    private val addonRepository = mockk<AddonRepository> {
        every { getInstalledAddons() } returns addonsFlow
    }

    private val claimedKeys = MutableStateFlow(setOf(SportsLiveCatalogPolicy.keyFor(SPORTS_ADDON, EVENTS)))
    private val catalogStats = MutableStateFlow<Map<String, CatalogStats>>(emptyMap())
    private val sportsLiveRepository = mockk<SportsLiveRepository> {
        every { claimedCatalogKeys } returns claimedKeys
        every { catalogStats } returns this@SportsLiveSettingsViewModelTest.catalogStats
        coEvery { load(any(), any()) } returns SportsLiveLoad(emptyList(), emptyList(), 0L, emptySet())
    }

    private fun TestScope.newViewModel(): SportsLiveSettingsViewModel {
        val viewModel = SportsLiveSettingsViewModel(prefs, addonRepository, sportsLiveRepository)
        backgroundScope.launch { viewModel.uiState.collect {} }
        return viewModel
    }

    @Test
    fun `followed teams are listed in follow order and unfollow removes one`() = runTest {
        prefs.follow(FollowedTeam("Celtics", "Basketball"))
        prefs.follow(FollowedTeam("Arsenal", null))
        val viewModel = newViewModel()
        advanceUntilIdle()
        assertEquals(
            listOf(FollowedTeam("Celtics", "Basketball"), FollowedTeam("Arsenal", null)),
            viewModel.uiState.value.followedTeams
        )

        viewModel.unfollow(FollowedTeam("Celtics", "Basketball"))
        advanceUntilIdle()
        assertEquals(listOf(FollowedTeam("Arsenal", null)), viewModel.uiState.value.followedTeams)
    }

    @Test
    fun `cycleOverride goes Auto Include Exclude Auto`() = runTest {
        val viewModel = newViewModel()
        val key = SportsLiveCatalogPolicy.keyFor(PLAIN_ADDON, MOVIES)
        advanceUntilIdle()

        val seen = mutableListOf(prefs.overrides.first()[key])
        repeat(3) {
            viewModel.cycleOverride(key)
            advanceUntilIdle()
            seen += prefs.overrides.first()[key]
        }

        assertEquals(
            listOf(null, SportsLiveOverride.INCLUDE, SportsLiveOverride.EXCLUDE, null),
            seen
        )
    }

    @Test
    fun `catalogs list every catalog of enabled addons with auto claim ignoring overrides`() = runTest {
        prefs.setOverride(SportsLiveCatalogPolicy.keyFor(SPORTS_ADDON, EVENTS), SportsLiveOverride.EXCLUDE)
        val viewModel = newViewModel()
        advanceUntilIdle()

        val catalogs = viewModel.uiState.value.catalogs
        assertEquals(listOf("Events", "Movies", "Trending"), catalogs.map { it.catalogName })
        assertEquals(listOf("Sports", "Plain", "Plain"), catalogs.map { it.addonName })

        val events = catalogs[0]
        assertEquals(SportsLiveCatalogPolicy.keyFor(SPORTS_ADDON, EVENTS), events.key)
        assertEquals("sport", events.apiType)
        assertEquals(SportsLiveKind.EVENTS, events.autoClaim?.kind)
        assertEquals("type:sport", events.autoClaim?.reason)
        assertEquals(SportsLiveOverride.EXCLUDE, events.override)

        assertNull(catalogs[1].autoClaim)
        assertNull(catalogs[1].override)
    }

    @Test
    fun `catalogs with a required extra are not listed`() = runTest {
        val searchOnly = CatalogDescriptor(
            type = ContentType.MOVIE,
            id = "search",
            name = "Search",
            extra = listOf(CatalogExtra(name = "search", isRequired = true))
        )
        addonsFlow.value = listOf(addon("mixed", "Mixed", listOf(searchOnly, MOVIES)))
        val viewModel = newViewModel()
        advanceUntilIdle()

        assertEquals(listOf("Movies"), viewModel.uiState.value.catalogs.map { it.catalogName })
    }

    @Test
    fun `stats are shown only for claimed catalogs that were loaded`() = runTest {
        val eventsKey = SportsLiveCatalogPolicy.keyFor(SPORTS_ADDON, EVENTS)
        val moviesKey = SportsLiveCatalogPolicy.keyFor(PLAIN_ADDON, MOVIES)
        val eventsStats = CatalogStats(live = 12, timed = 8, unrecognised = 3)
        catalogStats.value = mapOf(eventsKey to eventsStats, moviesKey to CatalogStats(live = 1))
        val viewModel = newViewModel()
        advanceUntilIdle()

        val byKey = viewModel.uiState.value.catalogs.associateBy { it.key }
        assertEquals(eventsStats, byKey.getValue(eventsKey).stats)
        assertNull(byKey.getValue(moviesKey).stats)
        assertNull(byKey.getValue(SportsLiveCatalogPolicy.keyFor(PLAIN_ADDON, TRENDING)).stats)

        claimedKeys.value = emptySet()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.catalogs.single { it.key == eventsKey }.stats)
    }

    @Test
    fun `switches reflect stored preferences`() = runTest {
        val viewModel = newViewModel()
        viewModel.setShowSection(false)
        viewModel.setShowHomeRow(false)
        viewModel.setHoverPreview(false)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.showSection)
        assertFalse(state.showHomeRow)
        assertFalse(state.hoverPreview)
        assertFalse(prefs.showSection.first())
        assertFalse(prefs.showHomeRow.first())
        assertFalse(prefs.hoverPreview.first())
    }

    @Test
    fun `filter exposes the eligible extra, its options and the pinned value`() = runTest {
        addonsFlow.value = listOf(addon("tv", "TV", listOf(COUNTRY_TV, MOVIES)))
        val tvKey = SportsLiveCatalogPolicy.keyFor(addonsFlow.value[0], COUNTRY_TV)
        prefs.setCatalogOption(tvKey, CatalogOption("genre", "United States"))
        val viewModel = newViewModel()
        advanceUntilIdle()

        val (tv, movies) = viewModel.uiState.value.catalogs
        assertEquals("genre", tv.filterExtra)
        assertEquals(COUNTRIES, tv.filterOptions)
        assertEquals("United States", tv.filterValue)
        assertNull(movies.filterExtra)
        assertEquals(emptyList<String>(), movies.filterOptions)
        assertNull(movies.filterValue)
    }

    @Test
    fun `filter is offered only for catalogs the section uses and keeps the stored pin`() = runTest {
        val genreMovies = MOVIES.copy(extra = listOf(CatalogExtra(name = "genre", options = listOf("Drama", "Comedy"))))
        addonsFlow.value = listOf(addon("plain", "Plain", listOf(genreMovies)))
        val key = SportsLiveCatalogPolicy.keyFor(addonsFlow.value[0], genreMovies)
        prefs.setCatalogOption(key, CatalogOption("genre", "Drama"))
        val viewModel = newViewModel()
        advanceUntilIdle()

        val unclaimed = viewModel.uiState.value.catalogs.single()
        assertNull(unclaimed.autoClaim)
        assertNull(unclaimed.filterExtra)
        assertEquals(emptyList<String>(), unclaimed.filterOptions)
        assertNull(unclaimed.filterValue)

        prefs.setOverride(key, SportsLiveOverride.INCLUDE)
        advanceUntilIdle()
        val included = viewModel.uiState.value.catalogs.single()
        assertEquals("genre", included.filterExtra)
        assertEquals(listOf("Drama", "Comedy"), included.filterOptions)
        assertEquals("Drama", included.filterValue)

        prefs.setOverride(key, SportsLiveOverride.EXCLUDE)
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.catalogs.single().filterExtra)
        assertEquals(CatalogOption("genre", "Drama"), prefs.catalogOptions.first()[key])
    }

    @Test
    fun `a pin the addon no longer offers shows as All`() = runTest {
        addonsFlow.value = listOf(addon("tv", "TV", listOf(COUNTRY_TV)))
        val tvKey = SportsLiveCatalogPolicy.keyFor(addonsFlow.value[0], COUNTRY_TV)
        prefs.setCatalogOption(tvKey, CatalogOption("genre", "Atlantis"))
        val viewModel = newViewModel()
        advanceUntilIdle()

        val tv = viewModel.uiState.value.catalogs.single()
        assertEquals("genre", tv.filterExtra)
        assertNull(tv.filterValue)
    }

    @Test
    fun `setCatalogFilter stores the value exactly, clears with null and reloads each time`() = runTest {
        addonsFlow.value = listOf(addon("tv", "TV", listOf(COUNTRY_TV)))
        val tvKey = SportsLiveCatalogPolicy.keyFor(addonsFlow.value[0], COUNTRY_TV)
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.setCatalogFilter(tvKey, "genre", BOXING)
        advanceUntilIdle()
        assertEquals(CatalogOption("genre", BOXING), prefs.catalogOptions.first()[tvKey])
        assertEquals(BOXING, viewModel.uiState.value.catalogs.single().filterValue)
        coVerify(exactly = 1) { sportsLiveRepository.load(force = false, includeChannels = false) }

        viewModel.setCatalogFilter(tvKey, "genre", null)
        advanceUntilIdle()
        assertNull(prefs.catalogOptions.first()[tvKey])
        assertNull(viewModel.uiState.value.catalogs.single().filterValue)
        coVerify(exactly = 2) { sportsLiveRepository.load(force = false, includeChannels = false) }
    }

    @Test
    fun `a failed reload after setting a filter keeps the pin`() = runTest {
        coEvery { sportsLiveRepository.load(any(), any()) } throws IllegalStateException("offline")
        addonsFlow.value = listOf(addon("tv", "TV", listOf(COUNTRY_TV)))
        val tvKey = SportsLiveCatalogPolicy.keyFor(addonsFlow.value[0], COUNTRY_TV)
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.setCatalogFilter(tvKey, "genre", "United States")
        advanceUntilIdle()
        assertEquals(CatalogOption("genre", "United States"), prefs.catalogOptions.first()[tvKey])
    }

    private companion object {
        /** Addons can give values with stray whitespace; they are stored and sent as given. */
        const val BOXING = "TV Boxing "
        val COUNTRIES = listOf("United Kingdom", "United States", BOXING)
        val COUNTRY_TV = CatalogDescriptor(
            type = ContentType.TV,
            id = "live",
            name = "Live TV",
            extra = listOf(CatalogExtra(name = "skip"), CatalogExtra(name = "genre", options = COUNTRIES))
        )

        val EVENTS = CatalogDescriptor(type = ContentType.UNKNOWN, rawType = "sport", id = "events", name = "Events")
        val MOVIES = CatalogDescriptor(type = ContentType.MOVIE, id = "movies", name = "Movies")
        val TRENDING = CatalogDescriptor(type = ContentType.SERIES, id = "trending", name = "Trending")

        val SPORTS_ADDON = addon("sports", "Sports", listOf(EVENTS))
        val PLAIN_ADDON = addon("plain", "Plain", listOf(MOVIES, TRENDING))
        val DISABLED_ADDON = addon("off", "Off", listOf(EVENTS), enabled = false)

        fun addon(id: String, name: String, catalogs: List<CatalogDescriptor>, enabled: Boolean = true) = Addon(
            id = id,
            name = name,
            version = "1.0.0",
            description = null,
            logo = null,
            baseUrl = "https://$id.example",
            catalogs = catalogs,
            types = emptyList(),
            resources = emptyList(),
            enabled = enabled
        )
    }
}
