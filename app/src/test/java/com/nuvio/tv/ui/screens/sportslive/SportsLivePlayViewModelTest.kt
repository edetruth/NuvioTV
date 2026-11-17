package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.debrid.DirectDebridResolver
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sportslive.SportsLiveOpenGame
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.SportsLivePreferencesDataStore
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SportsLivePlayViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val resolver = mockk<SportsLiveZapResolver>()
    private var mode = StreamAutoPlayMode.FIRST_STREAM
    private val openGame = MutableStateFlow(SportsLiveOpenGame.PLAY_BEST)

    private fun newViewModel(): SportsLivePlayViewModel {
        val settings = mockk<PlayerSettingsDataStore>()
        every { settings.playerSettings } answers { flowOf(PlayerSettings(streamAutoPlayMode = mode)) }
        val debrid = mockk<DirectDebridResolver>()
        coEvery { debrid.shouldResolveToPlayableStream(any()) } returns false
        val profiles = mockk<ProfileManager>()
        every { profiles.activeProfileId } returns MutableStateFlow(1)
        val sportsPrefs = mockk<SportsLivePreferencesDataStore>()
        every { sportsPrefs.openGame } returns openGame
        return SportsLivePlayViewModel(resolver, settings, debrid, profiles, sportsPrefs)
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
    fun `an upcoming merged game with no streams in manual mode shows the no stream toast`() = runTest(mainDispatcherRule.dispatcher) {
        mode = StreamAutoPlayMode.MANUAL
        coEvery { resolver.resolveAll(any()) } returns emptyList()
        val viewModel = newViewModel()
        val events = eventsOf(viewModel)

        viewModel.play(UPCOMING_MERGED)
        advanceUntilIdle()

        assertEquals(listOf(SportsLivePlayEvent.NoStream("A vs B")), events)
        assertEquals(SportsLivePlayState.Idle, viewModel.state.value)
    }

    @Test
    fun `a second pick from the picker does nothing`() = runTest(mainDispatcherRule.dispatcher) {
        openGame.value = SportsLiveOpenGame.LET_ME_CHOOSE
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
        // A stream the user picked is not announced.
        assertNull(play.source)
        coVerify(exactly = 0) { resolver.resolve(any()) }
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
    fun `OK on a live single-source game plays its best stream with one lookup and names the source`() =
        runTest(mainDispatcherRule.dispatcher) {
            coEvery { resolver.resolve(any()) } returns STREAM
            val viewModel = newViewModel()
            val events = eventsOf(viewModel)

            viewModel.play(SINGLE)
            advanceUntilIdle()

            val play = events.single() as SportsLivePlayEvent.Play
            assertEquals(SINGLE, play.target.item)
            assertEquals(STREAM.url, play.target.playback?.url)
            // As a zap plays a single-source game: its own links fail over via the sports marking.
            assertFalse(play.target.autoPicked)
            assertEquals("two", play.source)
            assertEquals(SportsLivePlayState.Idle, viewModel.state.value)
            // One lookup (the resolver's cached one), never the all-sources fetch.
            coVerify(exactly = 1) { resolver.resolve(SINGLE) }
            coVerify(exactly = 0) { resolver.resolveAll(any()) }
        }

    @Test
    fun `OK on a channel plays its best stream too`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { resolver.resolve(any()) } returns STREAM
        val viewModel = newViewModel()
        val events = eventsOf(viewModel)

        viewModel.play(CHANNEL, returnToHomeOnBack = true)
        advanceUntilIdle()

        val play = events.single() as SportsLivePlayEvent.Play
        assertEquals(CHANNEL, play.target.item)
        assertTrue(play.target.returnToHomeOnBack)
    }

    @Test
    fun `a lookup with nothing playable falls back to picking on the Stream screen with a message`() =
        runTest(mainDispatcherRule.dispatcher) {
            coEvery { resolver.resolve(any()) } returns null
            val viewModel = newViewModel()
            val events = eventsOf(viewModel)

            viewModel.play(SINGLE)
            advanceUntilIdle()

            assertEquals(
                listOf(SportsLivePlayEvent.OpenStreamScreen(SINGLE, manualSelection = true, noStreamFound = true)),
                events
            )
            assertEquals(SportsLivePlayState.Idle, viewModel.state.value)
            coVerify(exactly = 1) { resolver.resolve(any()) }
            coVerify(exactly = 0) { resolver.resolveAll(any()) }
        }

    @Test
    fun `a live merged game with nothing playable falls back to its Stream screen too`() = runTest(mainDispatcherRule.dispatcher) {
        SportsLiveExtraSources.reset()
        coEvery { resolver.resolve(any()) } returns null
        val viewModel = newViewModel()
        val events = eventsOf(viewModel)

        viewModel.play(MERGED)
        advanceUntilIdle()

        assertEquals(
            listOf(SportsLivePlayEvent.OpenStreamScreen(MERGED, manualSelection = true, noStreamFound = true)),
            events
        )
        assertTrue(SportsLiveExtraSources.isRegistered(MERGED.id))
        coVerify(exactly = 0) { resolver.resolveAll(any()) }
        SportsLiveExtraSources.reset()
    }

    @Test
    fun `let me choose opens the Stream screen for picking without a lookup`() = runTest(mainDispatcherRule.dispatcher) {
        openGame.value = SportsLiveOpenGame.LET_ME_CHOOSE
        val viewModel = newViewModel()
        val events = eventsOf(viewModel)

        viewModel.play(SINGLE)
        advanceUntilIdle()

        assertEquals(listOf(SportsLivePlayEvent.OpenStreamScreen(SINGLE, manualSelection = true)), events)
        coVerify(exactly = 0) { resolver.resolve(any()) }
    }

    @Test
    fun `choose stream on a single-source card opens the Stream screen for picking`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        val events = eventsOf(viewModel)

        viewModel.chooseStream(SINGLE)
        advanceUntilIdle()

        assertEquals(listOf(SportsLivePlayEvent.OpenStreamScreen(SINGLE, manualSelection = true)), events)
        coVerify(exactly = 0) { resolver.resolve(any()) }
    }

    @Test
    fun `choose stream on a merged card opens the picker across its sources`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { resolver.resolveAll(any()) } returns listOf(STREAM)
        val viewModel = newViewModel()
        val events = eventsOf(viewModel)

        viewModel.chooseStream(MERGED)
        advanceUntilIdle()

        assertEquals(SportsLivePlayState.Picking(MERGED, listOf(STREAM)), viewModel.state.value)
        assertEquals(emptyList<SportsLivePlayEvent>(), events)
        coVerify(exactly = 0) { resolver.resolve(any()) }
    }

    @Test
    fun `replays and upcoming single-source cards open their Stream screen as before`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        val events = eventsOf(viewModel)

        viewModel.play(REPLAY)
        viewModel.play(UPCOMING)
        advanceUntilIdle()

        assertEquals(
            listOf(SportsLivePlayEvent.OpenStreamScreen(REPLAY), SportsLivePlayEvent.OpenStreamScreen(UPCOMING)),
            events
        )
        coVerify(exactly = 0) { resolver.resolve(any()) }
    }

    @Test
    fun `userChooses follows the setting for live cards only`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        advanceUntilIdle()
        assertFalse(viewModel.userChooses(SINGLE))

        openGame.value = SportsLiveOpenGame.LET_ME_CHOOSE
        advanceUntilIdle()

        assertTrue(viewModel.userChooses(SINGLE))
        assertFalse(viewModel.userChooses(REPLAY))
    }

    @Test
    fun `a previewed stream plays at once, without a lookup or the Stream screen`() = runTest(mainDispatcherRule.dispatcher) {
        SportsLiveExtraSources.reset()
        val viewModel = newViewModel()
        val events = eventsOf(viewModel)

        viewModel.playResolved(SINGLE, STREAM)
        viewModel.playResolved(MERGED, STREAM)
        advanceUntilIdle()

        val play = events.single() as SportsLivePlayEvent.Play
        val target = play.target
        assertEquals(MERGED, target.item)
        assertEquals(STREAM.url, target.playback?.url)
        assertTrue(target.autoPicked)
        assertTrue(!target.returnToHomeOnBack)
        assertEquals("two", play.source)
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
            addonBaseUrl = "https://$addon.example.invalid",
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
        val CHANNEL = item("channel", "one").copy(kind = SportsLiveKind.CHANNELS, isLive = false, isChannel = true)
        val UPCOMING = item("later", "one").copy(isLive = false, startMs = 4_000_000_000_000L, startHasTimeOfDay = true)
        val UPCOMING_MERGED = MERGED.copy(isLive = false, startMs = 4_000_000_000_000L, startHasTimeOfDay = true)
        val REPLAY = item("replay", "one").copy(kind = SportsLiveKind.REPLAY, isLive = false)

        val STREAM = Stream(
            name = "Two 720p",
            title = null,
            description = null,
            url = "https://cdn.example.invalid/two.m3u8",
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
