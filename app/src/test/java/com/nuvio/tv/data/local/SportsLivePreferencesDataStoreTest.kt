package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.TestPreferencesStore
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.CatalogOption
import com.nuvio.tv.domain.model.SportsLiveOverride
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsLivePreferencesDataStoreTest {
    private val profile1Store = TestPreferencesStore()
    private val profile2Store = TestPreferencesStore()
    private val activeProfile = MutableStateFlow(1)
    private val factory = mockk<ProfileDataStoreFactory> {
        every { get(1, SportsLivePreferencesDataStore.FEATURE) } returns profile1Store
        every { get(2, SportsLivePreferencesDataStore.FEATURE) } returns profile2Store
    }
    private val profiles = mockk<ProfileManager> {
        every { activeProfileId } returns this@SportsLivePreferencesDataStoreTest.activeProfile
    }

    @Test
    fun `defaults are on with no overrides`() = runTest {
        val store = SportsLivePreferencesDataStore(factory, profiles)
        assertTrue(store.showSection.first())
        assertTrue(store.showHomeRow.first())
        assertEquals(emptyMap<String, SportsLiveOverride>(), store.overrides.first())
    }

    @Test
    fun `setOverride round trips and null clears`() = runTest {
        val store = SportsLivePreferencesDataStore(factory, profiles)
        store.setOverride("a_sport_x", SportsLiveOverride.EXCLUDE)
        assertEquals(mapOf("a_sport_x" to SportsLiveOverride.EXCLUDE), store.overrides.first())
        store.setOverride("a_sport_x", null)
        assertEquals(emptyMap<String, SportsLiveOverride>(), store.overrides.first())
    }

    @Test
    fun `switching profile reads the other store`() = runTest {
        val store = SportsLivePreferencesDataStore(factory, profiles)
        store.setOverride("a_sport_x", SportsLiveOverride.EXCLUDE)
        activeProfile.value = 2
        assertEquals(emptyMap<String, SportsLiveOverride>(), store.overrides.first())
    }

    @Test
    fun `malformed overrides json yields empty map`() = runTest {
        profile1Store.updateData {
            mutablePreferencesOf(stringPreferencesKey("sports_live_overrides") to "not json")
        }
        val store = SportsLivePreferencesDataStore(factory, profiles)
        assertEquals(emptyMap<String, SportsLiveOverride>(), store.overrides.first())
    }

    @Test
    fun `catalog options default to none, round trip, and null clears`() = runTest {
        val store = SportsLivePreferencesDataStore(factory, profiles)
        assertEquals(emptyMap<String, CatalogOption>(), store.catalogOptions.first())

        val us = CatalogOption("genre", "United States")
        store.setCatalogOption("cnc_tv_live", us)
        store.setCatalogOption("cnc_tv_events", CatalogOption("genre", "Football"))
        assertEquals(
            mapOf("cnc_tv_live" to us, "cnc_tv_events" to CatalogOption("genre", "Football")),
            store.catalogOptions.first()
        )

        store.setCatalogOption("cnc_tv_events", null)
        assertEquals(mapOf("cnc_tv_live" to us), store.catalogOptions.first())
    }

    @Test
    fun `catalog options are stored as an extra and value map per catalog key`() = runTest {
        val store = SportsLivePreferencesDataStore(factory, profiles)
        store.setCatalogOption("cnc_tv_live", CatalogOption("genre", "United States"))

        val json = profile1Store.data.first()[stringPreferencesKey("sports_live_catalog_options")]
        assertEquals("""{"cnc_tv_live":{"extra":"genre","value":"United States"}}""", json)
    }

    @Test
    fun `catalog options follow the active profile`() = runTest {
        val store = SportsLivePreferencesDataStore(factory, profiles)
        store.setCatalogOption("cnc_tv_live", CatalogOption("genre", "United States"))

        activeProfile.value = 2
        assertEquals(emptyMap<String, CatalogOption>(), store.catalogOptions.first())
        store.setCatalogOption("cnc_tv_live", CatalogOption("genre", "Canada"))

        activeProfile.value = 1
        assertEquals(mapOf("cnc_tv_live" to CatalogOption("genre", "United States")), store.catalogOptions.first())
        activeProfile.value = 2
        assertEquals(mapOf("cnc_tv_live" to CatalogOption("genre", "Canada")), store.catalogOptions.first())
    }

    @Test
    fun `malformed or incomplete catalog options are dropped`() = runTest {
        profile1Store.updateData {
            mutablePreferencesOf(stringPreferencesKey("sports_live_catalog_options") to "not json")
        }
        val store = SportsLivePreferencesDataStore(factory, profiles)
        assertEquals(emptyMap<String, CatalogOption>(), store.catalogOptions.first())

        profile1Store.updateData {
            mutablePreferencesOf(
                stringPreferencesKey("sports_live_catalog_options") to
                    """{"a":{"extra":"genre"},"b":{"value":"X"},"c":{"extra":"genre","value":"Canada"}}"""
            )
        }
        assertEquals(mapOf("c" to CatalogOption("genre", "Canada")), store.catalogOptions.first())
    }
}
