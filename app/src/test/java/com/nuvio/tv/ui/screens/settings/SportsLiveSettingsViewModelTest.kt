package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.TestPreferencesStore
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sportslive.SportsLiveCatalogPolicy
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.SportsLivePreferencesDataStore
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveOverride
import com.nuvio.tv.domain.repository.AddonRepository
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

    private fun TestScope.newViewModel(): SportsLiveSettingsViewModel {
        val viewModel = SportsLiveSettingsViewModel(prefs, addonRepository)
        backgroundScope.launch { viewModel.uiState.collect {} }
        return viewModel
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
    fun `switches reflect stored preferences`() = runTest {
        val viewModel = newViewModel()
        viewModel.setShowSection(false)
        viewModel.setShowHomeRow(false)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.showSection)
        assertFalse(state.showHomeRow)
        assertFalse(prefs.showSection.first())
        assertFalse(prefs.showHomeRow.first())
    }

    private companion object {
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
