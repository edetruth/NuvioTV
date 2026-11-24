package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.core.debrid.DebridStreamPresentation
import com.nuvio.tv.core.debrid.LocalDebridAvailabilityService
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.plugin.PluginManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.usenet.BuiltInUsenetService
import com.nuvio.tv.core.usenet.UsenetSourceConfiguration
import com.nuvio.tv.core.usenet.UsenetSourceSettings
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.dto.StreamDto
import com.nuvio.tv.data.remote.dto.StreamResponseDto
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.repository.AddonRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response

/** QA C1: a Sports & Live search leaves out only the addons cooling down after a rate limit. */
class StreamRepositoryExcludedAddonsTest {

    private val api = mockk<AddonApi>().also { api ->
        coEvery { api.getStreams(any()) } answers {
            val url = firstArg<String>()
            Response.success(StreamResponseDto(streams = listOf(StreamDto(name = "Live", url = url.replace("/stream/", "/media/")))))
        }
    }

    private val repository = newRepository(listOf(addon("cooling", COOLING_URL), addon("other", OTHER_URL)))

    @Test
    fun `an excluded addon is not asked and every other addon still answers`() = runTest {
        val results = repository.getStreamsFromAllAddonsExcept("sport", "game", setOf("$COOLING_URL/")).toList()

        val groups = (results.last() as NetworkResult.Success).data
        assertEquals(listOf("other"), groups.map { it.addonName })
        assertEquals(listOf(OTHER_URL), groups.map { it.addonBaseUrl })
        coVerify(exactly = 0) { api.getStreams(match { it.startsWith(COOLING_URL) }) }
        coVerify(exactly = 1) { api.getStreams(match { it.startsWith(OTHER_URL) }) }
    }

    @Test
    fun `with nothing excluded every addon is asked, each group carrying its base url`() = runTest {
        val results = repository.getStreamsFromAllAddons("sport", "game").toList()

        val groups = (results.last() as NetworkResult.Success).data
        assertEquals(setOf(COOLING_URL, OTHER_URL), groups.mapNotNull { it.addonBaseUrl }.toSet())
    }

    private fun newRepository(addons: List<Addon>): StreamRepositoryImpl {
        val addonRepository = mockk<AddonRepository>()
        every { addonRepository.getInstalledAddons() } returns flowOf(addons)
        val pluginManager = mockk<PluginManager>(relaxed = true)
        every { pluginManager.enabledScrapers } returns flowOf(emptyList())
        every { pluginManager.pluginsEnabled } returns flowOf(false)
        every { pluginManager.groupStreamsByRepository } returns flowOf(false)
        every { pluginManager.repositories } returns flowOf(emptyList())
        val profileManager = mockk<ProfileManager>(relaxed = true)
        every { profileManager.activeProfileId } returns MutableStateFlow(1)
        val debridSettingsDataStore = mockk<DebridSettingsDataStore>()
        every { debridSettingsDataStore.settings } returns flowOf(DebridSettings())
        val presentation = mockk<DebridStreamPresentation>()
        every { presentation.apply(any(), any<DebridSettings>(), any(), any()) } answers { firstArg<List<AddonStreams>>() }
        val availability = mockk<LocalDebridAvailabilityService>()
        coEvery { availability.markChecking(any()) } coAnswers { firstArg<List<AddonStreams>>() }
        coEvery { availability.annotateCachedAvailability(any()) } coAnswers { firstArg<List<AddonStreams>>() }
        return StreamRepositoryImpl(
            context = mockk<Context>(relaxed = true),
            api = api,
            addonRepository = addonRepository,
            pluginManager = pluginManager,
            profileManager = profileManager,
            debridSettingsDataStore = debridSettingsDataStore,
            tmdbService = mockk<TmdbService>(relaxed = true),
            debridStreamPresentation = presentation,
            localDebridAvailabilityService = availability,
            usenetSourceSettings = mockk<UsenetSourceSettings> {
                every { read(any()) } returns UsenetSourceConfiguration()
            },
            builtInUsenetService = mockk<BuiltInUsenetService>(relaxed = true),
            serverStreams = mockk {
                every { isNativeRequest(any()) } returns false
                every { revision } returns 0
                every { sources(any(), any(), any(), any(), any()) } returns emptyList()
            }
        )
    }

    private fun addon(name: String, baseUrl: String) = Addon(
        id = name,
        name = name,
        version = "1.0.0",
        description = null,
        logo = null,
        baseUrl = baseUrl,
        catalogs = emptyList(),
        types = emptyList(),
        resources = listOf(AddonResource(name = "stream", types = listOf("sport"), idPrefixes = null))
    )

    private companion object {
        const val COOLING_URL = "https://cooling.example.invalid"
        const val OTHER_URL = "https://other.example.invalid"
    }
}
