package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.debrid.DirectDebridResolver
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.Stream
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SportsLivePlayViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val resolver = mockk<SportsLiveZapResolver>()
    private var mode = StreamAutoPlayMode.FIRST_STREAM

    private fun newViewModel(): SportsLivePlayViewModel {
        val settings = mockk<PlayerSettingsDataStore>()
        every { settings.playerSettings } answers { flowOf(PlayerSettings(streamAutoPlayMode = mode)) }
        val debrid = mockk<DirectDebridResolver>()
        coEvery { debrid.shouldResolveToPlayableStream(any()) } returns false
        val profiles = mockk<ProfileManager>()
        every { profiles.activeProfileId } returns MutableStateFlow(1)
        return SportsLivePlayViewModel(resolver, settings, debrid, profiles)
    }

    private fun TestScope.eventsOf(viewModel: SportsLivePlayViewModel): List<SportsLivePlayEvent> {
        val events = mutableListOf<SportsLivePlayEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.collect { events += it } }
        return events
    }

    @Test
    fun `dismiss while a stream is looked up plays nothing`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { resolver.resolve(any()) } coAnswers { awaitCancellation() }
        val viewModel = newViewModel()
        val events = eventsOf(viewModel)

        viewModel.play(MERGED)
        runCurrent()
        assertTrue(viewModel.state.value is SportsLivePlayState.Resolving)
        viewModel.dismiss()
        advanceUntilIdle()

        assertEquals(SportsLivePlayState.Idle, viewModel.state.value)
        assertEquals(emptyList<SportsLivePlayEvent>(), events)
    }

    @Test
    fun `a merged game with no streams in manual mode shows the no stream toast`() = runTest(mainDispatcherRule.dispatcher) {
        mode = StreamAutoPlayMode.MANUAL
        coEvery { resolver.resolveAll(any()) } returns emptyList()
        val viewModel = newViewModel()
        val events = eventsOf(viewModel)

        viewModel.play(MERGED)
        advanceUntilIdle()

        assertEquals(listOf(SportsLivePlayEvent.NoStream("A vs B")), events)
        assertEquals(SportsLivePlayState.Idle, viewModel.state.value)
    }

    @Test
    fun `a second pick from the picker does nothing`() = runTest(mainDispatcherRule.dispatcher) {
        mode = StreamAutoPlayMode.MANUAL
        coEvery { resolver.resolveAll(any()) } returns listOf(STREAM)
        val viewModel = newViewModel()
        val events = eventsOf(viewModel)

        viewModel.play(MERGED)
        advanceUntilIdle()
        assertEquals(SportsLivePlayState.Picking(MERGED, listOf(STREAM)), viewModel.state.value)

        viewModel.pick(STREAM)
        viewModel.pick(STREAM)
        advanceUntilIdle()

        assertEquals(1, events.size)
        val play = events.single() as SportsLivePlayEvent.Play
        assertEquals(MERGED, play.target.item)
        assertEquals(STREAM.url, play.target.playback?.url)
    }

    @Test
    fun `an event not yet collected when dismissed is never delivered`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { resolver.resolve(any()) } returns STREAM
        val viewModel = newViewModel()

        // No collector: the screen is stopped while the stream is found.
        viewModel.play(MERGED)
        advanceUntilIdle()
        viewModel.dismiss()
        val events = eventsOf(viewModel)
        advanceUntilIdle()

        assertEquals(emptyList<SportsLivePlayEvent>(), events)
    }

    @Test
    fun `a merged game from Home plays its best stream, returns Home, and registers its other sources`() =
        runTest(mainDispatcherRule.dispatcher) {
            SportsLiveExtraSources.reset()
            coEvery { resolver.resolve(any()) } returns STREAM
            val viewModel = newViewModel()
            val events = eventsOf(viewModel)

            viewModel.play(MERGED, returnToHomeOnBack = true)
            advanceUntilIdle()

            val target = (events.single() as SportsLivePlayEvent.Play).target
            assertTrue(target.returnToHomeOnBack)
            assertTrue(target.autoPicked)
            assertTrue(SportsLiveExtraSources.isRegistered(MERGED.id))
            SportsLiveExtraSources.reset()
        }

    @Test
    fun `a single-source card opens its Stream screen`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        val events = eventsOf(viewModel)

        viewModel.play(SINGLE)
        advanceUntilIdle()

        assertEquals(listOf(SportsLivePlayEvent.OpenStreamScreen(SINGLE)), events)
    }

    @Test
    fun `a previewed stream plays at once, without a lookup or the Stream screen`() = runTest(mainDispatcherRule.dispatcher) {
        SportsLiveExtraSources.reset()
        val viewModel = newViewModel()
        val events = eventsOf(viewModel)

        viewModel.playResolved(SINGLE, STREAM)
        viewModel.playResolved(MERGED, STREAM)
        advanceUntilIdle()

        val target = (events.single() as SportsLivePlayEvent.Play).target
        assertEquals(MERGED, target.item)
        assertEquals(STREAM.url, target.playback?.url)
        assertTrue(target.autoPicked)
        assertTrue(!target.returnToHomeOnBack)
        assertTrue(SportsLiveExtraSources.isRegistered(MERGED.id))
        coVerify(exactly = 0) { resolver.resolve(any()) }
        SportsLiveExtraSources.reset()
    }

    @Test
    fun `a previewed single-source stream opens the player directly`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        val events = eventsOf(viewModel)

        viewModel.playResolved(SINGLE, STREAM)
        advanceUntilIdle()

        val target = (events.single() as SportsLivePlayEvent.Play).target
        assertEquals(SINGLE, target.item)
        assertEquals(STREAM.url, target.playback?.url)
        assertTrue(!target.autoPicked)
    }

    private companion object {
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

        val SINGLE = item("game", "one")
        val MERGED = SINGLE.copy(alternates = listOf(item("other-game", "two")))

        val STREAM = Stream(
            name = "Two 720p",
            title = null,
            description = null,
            url = "https://cdn.example/two.m3u8",
            ytId = null,
            infoHash = null,
            fileIdx = null,
            externalUrl = null,
            behaviorHints = null,
            addonName = "two",
            addonLogo = null
        )
    }
}
