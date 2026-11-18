package com.nuvio.tv.ui.screens.sportslive

import android.view.KeyEvent
import androidx.lifecycle.SavedStateHandle
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.debrid.DirectDebridResolver
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sportslive.SportsLiveFullTimeAnnouncements
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.ChipScore
import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.LiveScoresRepository
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.repository.SportsLiveRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SportsLiveZapViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Before
    fun setUp() {
        SportsLiveExtraSources.reset()
        SportsLiveZapSessions.clear()
    }

    @After
    fun tearDown() {
        SportsLiveExtraSources.reset()
        SportsLiveZapSessions.clear()
        SportsLiveFullTimeAnnouncements.clear()
    }

    // "Ended while you're watching" (#60)

    private val pacersEvent = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA).first { it.eventId == "401909985" }
    private val inPlay = pacersEvent.copy(
        state = ScoreState.IN, isFinal = false, clockText = "Q4 1:00",
        home = pacersEvent.home.copy(score = 99), away = pacersEvent.away.copy(score = 101)
    )
    private val final = inPlay.copy(state = ScoreState.POST, isFinal = true, clockText = "Final")

    private fun game(name: String) = item(name, "one").copy(
        meta = item(name, "one").meta.copy(name = name),
        teams = SportsLiveTeams.parse(name)
    )

    private fun fullTimeViewModel(
        scores: MutableStateFlow<Map<String, ScoreSnapshot>>,
        mode: ScoresMode,
        playing: SportsLiveItem,
        others: List<SportsLiveItem>
    ): SportsLiveZapViewModel {
        SportsLiveZapSessions.start(
            SportsLiveZapSession.of(listOf(playing) + others, playing.streamKey, SportsLiveTab.LIVE_NOW, null, returnToHomeOnBack = false)!!
        )
        val resolver = mockk<SportsLiveZapResolver>()
        coEvery { resolver.resolve(any()) } returns STREAM
        val debrid = mockk<DirectDebridResolver>()
        coEvery { debrid.shouldResolveToPlayableStream(any()) } returns false
        val profiles = mockk<ProfileManager>()
        every { profiles.activeProfileId } returns MutableStateFlow(1)
        val repo = mockk<SportsLiveRepository>(relaxed = true)
        every { repo.endedKeys } returns MutableStateFlow(emptySet())
        val liveScores = mockk<LiveScoresRepository>(relaxed = true)
        every { liveScores.scores } returns scores
        every { liveScores.mode } returns flowOf(mode)
        return SportsLiveZapViewModel(SavedStateHandle(mapOf("contentId" to playing.id)), resolver, debrid, profiles, repo, liveScores)
    }

    @Test
    fun `the game playing ending shows the full-time toast for a while, the score per the Scores setting`() = runTest(mainDispatcherRule.dispatcher) {
        val pacers = game("Indiana Pacers at Philadelphia 76ers")
        val scores = MutableStateFlow(mapOf(pacers.streamKey to inPlay))
        val viewModel = fullTimeViewModel(scores, ScoresMode.SHOW, pacers, emptyList())
        runCurrent()
        assertNull(viewModel.fullTime.value)
        scores.value = mapOf(pacers.streamKey to final)
        runCurrent()
        assertEquals(ChipScore(101, 99), viewModel.fullTime.value!!.score)
        advanceTimeBy(FULL_TIME_TOAST_MS + 1)
        assertNull(viewModel.fullTime.value)

        // Hide until focused: "Full time" with no score.
        SportsLiveFullTimeAnnouncements.clear()
        val hiddenScores = MutableStateFlow(mapOf(pacers.streamKey to inPlay))
        val hidden = fullTimeViewModel(hiddenScores, ScoresMode.HIDE_UNTIL_FOCUSED, pacers, emptyList())
        runCurrent()
        hiddenScores.value = mapOf(pacers.streamKey to final)
        runCurrent()
        assertNull(hidden.fullTime.value!!.score)
        hidden.dismissFullTime()

        // Off: nothing.
        SportsLiveFullTimeAnnouncements.clear()
        val offScores = MutableStateFlow(mapOf(pacers.streamKey to inPlay))
        val off = fullTimeViewModel(offScores, ScoresMode.OFF, pacers, emptyList())
        runCurrent()
        offScores.value = mapOf(pacers.streamKey to final)
        runCurrent()
        assertNull(off.fullTime.value)
    }

    @Test
    fun `a full time already announced by the alerts is not shown again, nor a game opened already over`() = runTest(mainDispatcherRule.dispatcher) {
        val pacers = game("Indiana Pacers at Philadelphia 76ers")
        // The followed-team toast (#42) claimed this game's end first.
        assertTrue(SportsLiveFullTimeAnnouncements.claim(final.eventId))
        val scores = MutableStateFlow(mapOf(pacers.streamKey to inPlay))
        val viewModel = fullTimeViewModel(scores, ScoresMode.SHOW, pacers, emptyList())
        runCurrent()
        scores.value = mapOf(pacers.streamKey to final)
        runCurrent()
        assertNull(viewModel.fullTime.value)

        SportsLiveFullTimeAnnouncements.clear()
        val already = fullTimeViewModel(MutableStateFlow(mapOf(pacers.streamKey to final)), ScoresMode.SHOW, pacers, emptyList())
        runCurrent()
        assertNull(already.fullTime.value)
    }

    @Test
    fun `Next live game moves to the pick of Watch with another game, never a finished one`() = runTest(mainDispatcherRule.dispatcher) {
        val pacers = game("Indiana Pacers at Philadelphia 76ers")
        val lakers = game("Los Angeles Lakers at Phoenix Suns")
        val celtics = game("Boston Celtics at New York Knicks")
        val lakersFinal = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA).first { it.eventId == "401909986" }
            .let { it.copy(state = ScoreState.POST, isFinal = true, home = it.home.copy(score = 90), away = it.away.copy(score = 88)) }
        val scores = MutableStateFlow(mapOf(pacers.streamKey to inPlay, lakers.streamKey to lakersFinal))
        val viewModel = fullTimeViewModel(scores, ScoresMode.SHOW, pacers, listOf(lakers, celtics))
        val events = mutableListOf<SportsLiveZapEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.collect { events += it } }
        // No toast: Next does nothing.
        assertFalse(viewModel.nextLiveGame())
        runCurrent()
        scores.value = mapOf(pacers.streamKey to final, lakers.streamKey to lakersFinal)
        runCurrent()
        assertTrue(viewModel.nextLiveGame())
        advanceUntilIdle()
        assertNull(viewModel.fullTime.value)
        assertEquals(celtics, (events.single() as SportsLiveZapEvent.Navigate).target.item)
    }

    @Test
    fun `a zap to a merged game drops this player's extras and registers the target's`() = runTest(mainDispatcherRule.dispatcher) {
        SportsLiveExtraSources.register(PLAYING.id, PLAYING)
        SportsLiveZapSessions.start(
            SportsLiveZapSession.of(listOf(PLAYING, NEXT), PLAYING.streamKey, SportsLiveTab.LIVE_NOW, null, returnToHomeOnBack = false)!!
        )
        val resolver = mockk<SportsLiveZapResolver>()
        coEvery { resolver.resolve(any()) } returns STREAM
        val debrid = mockk<DirectDebridResolver>()
        coEvery { debrid.shouldResolveToPlayableStream(any()) } returns false
        val profiles = mockk<ProfileManager>()
        every { profiles.activeProfileId } returns MutableStateFlow(1)
        val repo = mockk<SportsLiveRepository>(relaxed = true)
        every { repo.endedKeys } returns MutableStateFlow(emptySet())
        val viewModel = SportsLiveZapViewModel(SavedStateHandle(mapOf("contentId" to PLAYING.id)), resolver, debrid, profiles, repo, mockk(relaxed = true))
        val events = mutableListOf<SportsLiveZapEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.collect { events += it } }

        assertTrue(viewModel.onKey(KeyEvent.KEYCODE_DPAD_DOWN, partyActive = false))
        advanceUntilIdle()

        assertEquals(NEXT, (events.single() as SportsLiveZapEvent.Navigate).target.item)
        assertFalse(SportsLiveExtraSources.isRegistered(PLAYING.id))
        assertTrue(SportsLiveExtraSources.isRegistered(NEXT.id))
    }

    private companion object {
        fun item(id: String, addon: String) = SportsLiveItem(
            meta = MetaPreview(
                id = id,
                type = ContentType.UNKNOWN,
                rawType = "sport",
                name = "$id vs Rivals",
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

        val PLAYING = item("playing", "one").copy(alternates = listOf(item("playing-alt", "two")))
        val NEXT = item("next", "one").copy(alternates = listOf(item("next-alt", "two")))

        val STREAM = Stream(
            name = "One 720p",
            title = null,
            description = null,
            url = "https://cdn.example/one.m3u8",
            ytId = null,
            infoHash = null,
            fileIdx = null,
            externalUrl = null,
            behaviorHints = null,
            addonName = "one",
            addonLogo = null
        )
    }
}
