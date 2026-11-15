package com.nuvio.tv.ui.screens.addon

import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.catalogRowLegacyKey
import com.nuvio.tv.domain.repository.SportsLiveRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Catalogs shown in Sports & Live are hidden here but keep their stored order and disabled flag. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CatalogOrderSportsLiveTest {

    private val created = mutableListOf<CatalogOrderViewModel>()

    @Before
    fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @After
    fun tearDown() {
        created.forEach { it.viewModelScope.cancel() }
        created.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `toggling a visible catalog keeps a claimed catalog's disabled flag`() {
        val store = layoutStore(savedOrder = emptyList(), disabled = listOf(SPORTS_KEY))
        val viewModel = newViewModel(store)
        assertEquals(listOf(POPULAR_KEY, TOP_KEY), viewModel.uiState.value.items.map { it.key })

        viewModel.toggleCatalogEnabled(POPULAR_KEY)

        coVerify(timeout = 2_000) {
            store.setDisabledHomeCatalogKeys(match { it.toSet() == setOf(SPORTS_KEY, POPULAR_KEY) })
        }
    }

    @Test
    fun `moving a catalog preserves claimed catalogs' saved positions`() {
        val store = layoutStore(savedOrder = listOf(POPULAR_KEY, SPORTS_KEY, TOP_KEY), disabled = emptyList())
        val viewModel = newViewModel(store)

        viewModel.moveDown(POPULAR_KEY)

        coVerify(timeout = 2_000) {
            store.setHomeCatalogOrderKeys(listOf(TOP_KEY, SPORTS_KEY, POPULAR_KEY))
        }
    }

    private fun layoutStore(savedOrder: List<String>, disabled: List<String>) =
        mockk<LayoutPreferenceDataStore>(relaxed = true) {
            every { homeCatalogOrderKeys } returns flowOf(savedOrder)
            every { disabledHomeCatalogKeys } returns flowOf(disabled)
            every { customCatalogTitles } returns flowOf(emptyMap())
            every { followAddonsOrder } returns flowOf(false)
        }

    private fun newViewModel(store: LayoutPreferenceDataStore): CatalogOrderViewModel {
        val sportsLiveRepository = mockk<SportsLiveRepository>(relaxed = true) {
            every { claimedCatalogKeys } returns flowOf(setOf(SPORTS_KEY))
        }
        val viewModel = CatalogOrderViewModel(
            addonRepository = mockk(relaxed = true) { every { getInstalledAddons() } returns flowOf(listOf(addon())) },
            collectionsDataStore = mockk(relaxed = true) { every { collections } returns flowOf(emptyList()) },
            layoutPreferenceDataStore = store,
            homeCatalogSettingsSyncService = mockk(relaxed = true),
            serverCatalog = mockk(relaxed = true) { every { addons } returns flowOf(emptyList()) },
            sportsLiveRepository = sportsLiveRepository
        )
        created += viewModel
        return viewModel
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
            CatalogDescriptor(type = ContentType.UNKNOWN, rawType = "tv", id = "live", name = "Live sports"),
            CatalogDescriptor(type = ContentType.MOVIE, id = "top", name = "Top")
        ),
        types = listOf(ContentType.MOVIE),
        resources = emptyList()
    )

    private companion object {
        const val ADDON_ID = "mixed.addon"
        val POPULAR_KEY = catalogRowLegacyKey(ADDON_ID, "movie", "popular")
        val TOP_KEY = catalogRowLegacyKey(ADDON_ID, "movie", "top")
        val SPORTS_KEY = catalogRowLegacyKey(ADDON_ID, "tv", "live")
    }
}
