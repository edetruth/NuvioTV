package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.debrid.DirectDebridResolver
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sportslive.SportsLiveOpenGame
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.SportsLivePreferencesDataStore
import com.nuvio.tv.data.local.StreamAutoPlayMode
import androidx.lifecycle.SavedStateHandle
import com.nuvio.tv.domain.model.ClaimedCatalog
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.domain.repository.SportsLiveLoad
import com.nuvio.tv.domain.repository.SportsLiveRepository
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
    private var modeChosen = true
    private val openGame = MutableStateFlow<SportsLiveOpenGame?>(null)

    private fun newViewModel(repo: SportsLiveRepository? = null): SportsLivePlayViewModel {
        val settings = mockk<PlayerSettingsDataStore>()
        every { settings.playerSettings } answers { flowOf(PlayerSettings(streamAutoPlayMode = mode, streamAutoPlayModeChosen = modeChosen)) }
        val debrid = mockk<DirectDebridResolver>()
        coEvery { debrid.shouldResolveToPlayableStream(any()) } returns false
        val profiles = mockk<ProfileManager>()
        every { profiles.activeProfileId } returns MutableStateFlow(1)
        val sportsPrefs = mockk<SportsLivePreferencesDataStore>()
        every { sportsPrefs.openGame } returns openGame
        return SportsLivePlayViewModel(resolver, settings, debrid, profiles, sportsPrefs, repo)
    }

    private fun TestScope.eventsOf(viewModel: SportsLivePlayViewModel): List<SportsLivePlayEvent> {
        val events = mutableListOf<SportsLivePlayEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.collect { events += it } }
        return events
    }

    @Test
    fun `dismiss while a stream is looked up plays nothing`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { resolver.lookup(any()) } coAnswers { awaitCancellation() }
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
        coVerify(exactly = 0) { resolver.lookup(any()) }
    }

    @Test
    fun `an event not yet collected when dismissed is never delivered`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { resolver.lookup(any()) } returns SportsLiveLookup.Found(STREAM)
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
            coEvery { resolver.lookup(any()) } returns SportsLiveLookup.Found(STREAM)
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
            coEvery { resolver.lookup(any()) } returns SportsLiveLookup.Found(STREAM)
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
            coVerify(exactly = 1) { resolver.lookup(SINGLE) }
            coVerify(exactly = 0) { resolver.resolveAll(any()) }
        }

    @Test
    fun `OK on a channel plays its best stream too`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { resolver.lookup(any()) } returns SportsLiveLookup.Found(STREAM)
        val viewModel = newViewModel()
        val events = eventsOf(viewModel)

        viewModel.play(CHANNEL, returnToHomeOnBack = true)
        advanceUntilIdle()

        val play = events.single() as SportsLivePlayEvent.Play
        assertEquals(CHANNEL, play.target.item)
        assertTrue(play.target.returnToHomeOnBack)
    }

    @Test
    fun `no streams at all shows a toast and opens nothing, so no addon is asked again`() =
        runTest(mainDispatcherRule.dispatcher) {
            coEvery { resolver.lookup(any()) } returns SportsLiveLookup.NothingFound
            val viewModel = newViewModel()
            val events = eventsOf(viewModel)

            viewModel.play(SINGLE)
            advanceUntilIdle()
            viewModel.play(MERGED)
            advanceUntilIdle()

            assertEquals(listOf(SportsLivePlayEvent.NothingFound, SportsLivePlayEvent.NothingFound), events)
            assertEquals(SportsLivePlayState.Idle, viewModel.state.value)
            coVerify(exactly = 2) { resolver.lookup(any()) }
            coVerify(exactly = 0) { resolver.resolveAll(any()) }
        }

    @Test
    fun `streams with none playable open the single source's Stream screen to pick, with a message`() =
        runTest(mainDispatcherRule.dispatcher) {
            coEvery { resolver.lookup(any()) } returns SportsLiveLookup.NonePlayable(listOf(STREAM))
            val viewModel = newViewModel()
            val events = eventsOf(viewModel)

            viewModel.play(SINGLE)
            advanceUntilIdle()

            assertEquals(
                listOf(SportsLivePlayEvent.OpenStreamScreen(SINGLE, manualSelection = true, noStreamFound = true)),
                events
            )
            coVerify(exactly = 1) { resolver.lookup(any()) }
            coVerify(exactly = 0) { resolver.resolveAll(any()) }
        }

    @Test
    fun `a merged game with none playable lists its already fetched streams in the picker`() =
        runTest(mainDispatcherRule.dispatcher) {
            coEvery { resolver.lookup(any()) } returns SportsLiveLookup.NonePlayable(listOf(STREAM))
            val viewModel = newViewModel()
            val events = eventsOf(viewModel)

            viewModel.play(MERGED)
            advanceUntilIdle()

            assertEquals(SportsLivePlayState.Picking(MERGED, listOf(STREAM)), viewModel.state.value)
            assertEquals(emptyList<SportsLivePlayEvent>(), events)
            // The picker's streams come from the one lookup: no all-sources fetch.
            coVerify(exactly = 1) { resolver.lookup(any()) }
            coVerify(exactly = 0) { resolver.resolveAll(any()) }
        }

    @Test
    fun `an unset setting is Let me choose for a manual-autoplay player, and an explicit choice wins`() =
        runTest(mainDispatcherRule.dispatcher) {
            mode = StreamAutoPlayMode.MANUAL
            coEvery { resolver.lookup(any()) } returns SportsLiveLookup.Found(STREAM)
            val viewModel = newViewModel()
            val events = eventsOf(viewModel)

            viewModel.play(SINGLE)
            advanceUntilIdle()
            assertEquals(listOf(SportsLivePlayEvent.OpenStreamScreen(SINGLE, manualSelection = true)), events)
            coVerify(exactly = 0) { resolver.lookup(any()) }

            openGame.value = SportsLiveOpenGame.PLAY_BEST
            viewModel.play(SINGLE)
            advanceUntilIdle()
            assertTrue(events.last() is SportsLivePlayEvent.Play)
        }

    @Test
    fun `the player's untouched manual default still plays the best stream`() = runTest(mainDispatcherRule.dispatcher) {
        mode = StreamAutoPlayMode.MANUAL
        modeChosen = false
        coEvery { resolver.lookup(any()) } returns SportsLiveLookup.Found(STREAM)
        val viewModel = newViewModel()
        val events = eventsOf(viewModel)

        viewModel.play(SINGLE)
        advanceUntilIdle()

        assertTrue(events.single() is SportsLivePlayEvent.Play)
    }

    @Test
    fun `let me choose opens the Stream screen for picking without a lookup`() = runTest(mainDispatcherRule.dispatcher) {
        openGame.value = SportsLiveOpenGame.LET_ME_CHOOSE
        val viewModel = newViewModel()
        val events = eventsOf(viewModel)

        viewModel.play(SINGLE)
        advanceUntilIdle()

        assertEquals(listOf(SportsLivePlayEvent.OpenStreamScreen(SINGLE, manualSelection = true)), events)
        coVerify(exactly = 0) { resolver.lookup(any()) }
    }

    @Test
    fun `choose stream on a single-source card opens the Stream screen for picking`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        val events = eventsOf(viewModel)

        viewModel.chooseStream(SINGLE)
        advanceUntilIdle()

        assertEquals(listOf(SportsLivePlayEvent.OpenStreamScreen(SINGLE, manualSelection = true)), events)
        coVerify(exactly = 0) { resolver.lookup(any()) }
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
        coVerify(exactly = 0) { resolver.lookup(any()) }
    }

    @Test
    fun `replays and upcoming single-source cards open their Stream screen as before`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        val events = eventsOf(viewModel)

        viewModel.play(REPLAY)
        advanceUntilIdle()
        viewModel.play(UPCOMING)
        advanceUntilIdle()

        assertEquals(
            listOf(SportsLivePlayEvent.OpenStreamScreen(REPLAY), SportsLivePlayEvent.OpenStreamScreen(UPCOMING)),
            events
        )
        coVerify(exactly = 0) { resolver.lookup(any()) }
    }

    @Test
    fun `OK on a card takes over its playing preview only when the stored setting plays the best stream`() =
        runTest(mainDispatcherRule.dispatcher) {
            // Stored before the view model exists: the very first OK must honour it.
            openGame.value = SportsLiveOpenGame.LET_ME_CHOOSE
            var takeOvers = 0
            val viewModel = newViewModel()
            val events = eventsOf(viewModel)

            viewModel.playCard(SINGLE) { takeOvers++; STREAM }
            advanceUntilIdle()
            assertEquals(0, takeOvers)
            assertEquals(listOf(SportsLivePlayEvent.OpenStreamScreen(SINGLE, manualSelection = true)), events)

            openGame.value = SportsLiveOpenGame.PLAY_BEST
            viewModel.playCard(SINGLE) { takeOvers++; STREAM }
            advanceUntilIdle()
            assertEquals(1, takeOvers)
            val play = events.last() as SportsLivePlayEvent.Play
            assertEquals(STREAM.url, play.target.playback?.url)
            // Instant start: the preview's stream, no lookup.
            coVerify(exactly = 0) { resolver.lookup(any()) }
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
        coVerify(exactly = 0) { resolver.lookup(any()) }
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

    // --- Every Watch opens the player with a zap session (#173 follow-up): the panel and CH± work ---

    private fun lineupRepo(items: List<SportsLiveItem>?): SportsLiveRepository {
        val claimed = mockk<ClaimedCatalog>(relaxed = true)
        every { claimed.key } returns SINGLE.catalogKey
        every { claimed.addon.id } returns "one"
        val repo = mockk<SportsLiveRepository>(relaxed = true)
        every { repo.latest } returns MutableStateFlow(items?.let { SportsLiveLoad(it, emptyList(), 0L, setOf(SINGLE.catalogKey)) })
        every { repo.claimedCatalogs } returns flowOf(listOf(claimed))
        every { repo.endedKeys } returns MutableStateFlow(emptySet())
        every { repo.followedTeams } returns flowOf(emptyList())
        return repo
    }

    private fun zapViewModelFor(contentId: String, repo: SportsLiveRepository): SportsLiveZapViewModel {
        val debrid = mockk<DirectDebridResolver>()
        coEvery { debrid.shouldResolveToPlayableStream(any()) } returns false
        val profiles = mockk<ProfileManager>()
        every { profiles.activeProfileId } returns MutableStateFlow(1)
        return SportsLiveZapViewModel(SavedStateHandle(mapOf("contentId" to contentId)), resolver, debrid, profiles, repo, mockk(relaxed = true))
    }

    @Test
    fun `an alert Watch from outside Sports Live opens the player with a session over the shared list`() =
        runTest(mainDispatcherRule.dispatcher) {
            SportsLiveZapSessions.clear()
            val other = item("other", "one").copy(meta = item("other", "one").meta.copy(name = "C vs D"))
            val repo = lineupRepo(listOf(other, SINGLE))
            val viewModel = newViewModel(repo)
            val events = eventsOf(viewModel)

            viewModel.playResolved(SINGLE, STREAM)
            advanceUntilIdle()

            assertTrue(events.single() is SportsLivePlayEvent.Play)
            val session = SportsLiveZapSessions.activeFor(SINGLE.id)!!
            assertEquals(SINGLE.streamKey, session.current.streamKey)
            assertEquals(setOf(SINGLE.id, other.id), session.items.map { it.id }.toSet())
            // The player that opens has its panel and CH±.
            assertTrue(zapViewModelFor(SINGLE.id, repo).hasSession)
            SportsLiveZapSessions.clear()
        }

    @Test
    fun `with the shared list not loaded yet the game still gets a session, and the player loads the list`() =
        runTest(mainDispatcherRule.dispatcher) {
            SportsLiveZapSessions.clear()
            coEvery { resolver.lookup(any()) } returns SportsLiveLookup.Found(STREAM)
            val repo = lineupRepo(null)
            val viewModel = newViewModel(repo)
            eventsOf(viewModel)

            viewModel.play(SINGLE)
            advanceUntilIdle()

            assertEquals(listOf(SINGLE.id), SportsLiveZapSessions.activeFor(SINGLE.id)!!.items.map { it.id })
            val zap = zapViewModelFor(SINGLE.id, repo)
            advanceUntilIdle()
            assertTrue(zap.hasSession)
            coVerify { repo.load(any(), false, any()) }
            SportsLiveZapSessions.clear()
        }

    @Test
    fun `a replay starts no session, and a session already on the game is kept`() = runTest(mainDispatcherRule.dispatcher) {
        SportsLiveZapSessions.clear()
        val repo = lineupRepo(listOf(SINGLE))
        val viewModel = newViewModel(repo)
        eventsOf(viewModel)
        coEvery { resolver.lookup(any()) } returns SportsLiveLookup.Found(STREAM)
        viewModel.playReplay(listOf(REPLAY), "A vs B")
        advanceUntilIdle()
        assertNull(SportsLiveZapSessions.current())

        // The section's Live now session (its origin, the section, as the hover preview's Watch, #211).
        val fromLiveNow = SportsLiveZapSession.of(listOf(SINGLE), SINGLE.streamKey, SportsLiveTab.LIVE_NOW, "x", returnToHomeOnBack = false)!!
        SportsLiveZapSessions.start(fromLiveNow)
        viewModel.playResolved(SINGLE, STREAM)
        advanceUntilIdle()
        assertTrue(SportsLiveZapSessions.current() === fromLiveNow)
        SportsLiveZapSessions.clear()
    }

    // --- Catch up replays (#58): played at once, never the addon's page or Stream screen ---

    @Test
    fun `a catch-up replay with one source plays its stream at once, under ESPN's matchup`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { resolver.lookup(any()) } returns SportsLiveLookup.Found(STREAM)
        val viewModel = newViewModel()
        val events = eventsOf(viewModel)

        viewModel.playReplay(listOf(REPLAY.copy(meta = REPLAY.meta.copy(name = "A 101-99 B"))), "Away at Home")
        advanceUntilIdle()

        val play = events.single() as SportsLivePlayEvent.Play
        assertEquals(STREAM.url, play.target.playback?.url)
        assertEquals("Away at Home", play.target.item.meta.name)
        assertEquals(SportsLivePlayState.Idle, viewModel.state.value)
    }

    @Test
    fun `a catch-up replay from several sources offers the sources, and the pick plays`() = runTest(mainDispatcherRule.dispatcher) {
        val other = item("replay-2", "two").copy(kind = SportsLiveKind.REPLAY, isLive = false)
        coEvery { resolver.lookup(any()) } returns SportsLiveLookup.Found(STREAM)
        val viewModel = newViewModel()
        val events = eventsOf(viewModel)

        viewModel.playReplay(listOf(REPLAY, other), "Away at Home")
        advanceUntilIdle()
        val picking = viewModel.state.value as SportsLivePlayState.PickingReplaySource
        assertEquals(listOf("one", "two"), picking.sources.map { it.first.addonName })
        assertTrue(events.isEmpty())

        viewModel.pickReplaySource(1)
        advanceUntilIdle()
        val play = events.single() as SportsLivePlayEvent.Play
        assertEquals("replay-2", play.target.item.id)
    }

    @Test
    fun `a catch-up replay with no playable stream says so and opens no Stream screen`() = runTest(mainDispatcherRule.dispatcher) {
        coEvery { resolver.lookup(any()) } returns SportsLiveLookup.NonePlayable(listOf(STREAM))
        val viewModel = newViewModel()
        val events = eventsOf(viewModel)

        viewModel.playReplay(listOf(REPLAY), "Away at Home")
        advanceUntilIdle()

        assertEquals(listOf(SportsLivePlayEvent.NoStream("Away at Home")), events)
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
