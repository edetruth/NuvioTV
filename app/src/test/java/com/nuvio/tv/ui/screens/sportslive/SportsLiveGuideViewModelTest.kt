package com.nuvio.tv.ui.screens.sportslive

import android.view.KeyEvent
import androidx.lifecycle.SavedStateHandle
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.debrid.DirectDebridResolver
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sportslive.SportsLiveEspnStarts
import com.nuvio.tv.core.sportslive.SportsLiveGuideStyle
import com.nuvio.tv.core.sportslive.SportsLiveOkOnLive
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.LiveScoresRepository
import com.nuvio.tv.core.sportslive.scores.ScoreProvider
import com.nuvio.tv.core.sportslive.scores.ScoreSettings
import com.nuvio.tv.core.sportslive.scores.ScoreboardResult
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.domain.model.ClaimedCatalog
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.domain.repository.SportsLiveLoad
import com.nuvio.tv.domain.repository.SportsLiveRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** live.21 B2: OK opens the new in-player guide, a pick switches and closes it, and it closes itself when idle. */
@OptIn(ExperimentalCoroutinesApi::class)
class SportsLiveGuideViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Before
    fun setUp() = SportsLiveZapSessions.clear()

    @After
    fun tearDown() = SportsLiveZapSessions.clear()

    private val now = System.currentTimeMillis()

    private fun okEvent(act: Int, repeat: Int = 0): KeyEvent = mockk {
        every { keyCode } returns KeyEvent.KEYCODE_DPAD_CENTER
        every { action } returns act
        every { repeatCount } returns repeat
        every { eventTime } returns 0L
        every { downTime } returns 0L
        every { isLongPress } returns false
    }

    @Test
    fun `with the new guide OK opens it on release, and it closes itself after 20 s without a key`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = guideViewModel(guide = true)
            runCurrent()
            assertTrue(viewModel.guideTable.value)

            assertEquals(SportsLiveShortPress.CONSUMED, viewModel.onShortPressKey(okEvent(KeyEvent.ACTION_DOWN), partyActive = false))
            assertFalse(viewModel.panelOpen.value)
            assertEquals(SportsLiveShortPress.OPEN_PANEL, viewModel.onShortPressKey(okEvent(KeyEvent.ACTION_UP), partyActive = false))
            assertTrue(viewModel.panelOpen.value)

            advanceTimeBy(GUIDE_IDLE_CLOSE_MS - 1_000L)
            runCurrent()
            assertTrue(viewModel.panelOpen.value)
            // A key restarts the wait.
            viewModel.onPanelKey(KeyEvent.KEYCODE_DPAD_DOWN)
            advanceTimeBy(GUIDE_IDLE_CLOSE_MS - 1_000L)
            runCurrent()
            assertTrue(viewModel.panelOpen.value)
            // The Hold OK menu keeps it open as long as it is up.
            viewModel.onGuideMenu(held = true)
            advanceTimeBy(GUIDE_IDLE_CLOSE_MS * 2)
            runCurrent()
            assertTrue(viewModel.panelOpen.value)
            viewModel.onGuideMenu(held = false)
            advanceTimeBy(GUIDE_IDLE_CLOSE_MS + 1_000L)
            runCurrent()
            assertFalse(viewModel.panelOpen.value)
        }

    @Test
    fun `a game picked in the guide closes it, the switch going ahead`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = guideViewModel(guide = true)
        runCurrent()
        viewModel.openPanel()
        assertTrue(viewModel.panelOpen.value)
        viewModel.onPanelPick(other)
        assertFalse(viewModel.panelOpen.value)
        // No idle close is left waiting.
        advanceTimeBy(GUIDE_IDLE_CLOSE_MS * 2)
        runCurrent()
        assertFalse(viewModel.panelOpen.value)
    }

    @Test
    fun `with the side panel, or OK on live set to Pause, OK stays the player's and nothing closes by itself`() =
        runTest(mainDispatcherRule.dispatcher) {
            val panel = guideViewModel(guide = false)
            runCurrent()
            assertEquals(SportsLiveShortPress.NONE, panel.onShortPressKey(okEvent(KeyEvent.ACTION_DOWN), partyActive = false))
            assertEquals(SportsLiveShortPress.NONE, panel.onShortPressKey(okEvent(KeyEvent.ACTION_UP), partyActive = false))
            assertFalse(panel.panelOpen.value)
            panel.openPanel()
            advanceTimeBy(GUIDE_IDLE_CLOSE_MS * 2)
            runCurrent()
            assertTrue(panel.panelOpen.value)
            panel.closePanel()

            SportsLiveZapSessions.clear()
            val pause = guideViewModel(guide = true, okOnLive = SportsLiveOkOnLive.PAUSE)
            runCurrent()
            assertEquals(SportsLiveShortPress.NONE, pause.onShortPressKey(okEvent(KeyEvent.ACTION_DOWN), partyActive = false))
            assertEquals(SportsLiveShortPress.NONE, pause.onShortPressKey(okEvent(KeyEvent.ACTION_UP), partyActive = false))
            assertFalse(pause.panelOpen.value)
            // During a watch party OK is never the guide's.
            assertEquals(SportsLiveShortPress.NONE, pause.onShortPressKey(okEvent(KeyEvent.ACTION_DOWN), partyActive = true))
        }

    private val playing = item("a", now - HOUR)
    private val other = item("b", now - HOUR / 2)

    private fun TestScope.guideViewModel(
        guide: Boolean,
        okOnLive: SportsLiveOkOnLive = SportsLiveOkOnLive.DEFAULT
    ): SportsLiveZapViewModel {
        val latest = MutableStateFlow(SportsLiveLoad(listOf(playing, other), emptyList(), 0L, setOf(CATALOG)))
        SportsLiveZapSessions.start(
            SportsLiveZapSession.of(latest.value.items, playing.streamKey, SportsLiveTab.LIVE_NOW, null, returnToHomeOnBack = false)!!
        )
        val scores = LiveScoresRepository(
            provider = object : ScoreProvider {
                override suspend fun scoreboard(league: EspnLeague, date: LocalDate) = ScoreboardResult(emptyList())
            },
            settings = MutableStateFlow(ScoreSettings(mode = ScoresMode.OFF)), scope = backgroundScope,
            now = { now + testScheduler.currentTime }, zone = { ZoneOffset.UTC }, jitter = { 0L },
            espnStarts = SportsLiveEspnStarts()
        )
        val claimed = mockk<ClaimedCatalog>(relaxed = true)
        every { claimed.key } returns CATALOG
        val repo = mockk<SportsLiveRepository>(relaxed = true)
        every { repo.latest } returns latest
        every { repo.claimedCatalogs } returns flowOf(listOf(claimed))
        every { repo.endedKeys } returns MutableStateFlow(emptySet())
        every { repo.followedTeams } returns flowOf(emptyList())
        val profiles = mockk<ProfileManager>()
        every { profiles.activeProfileId } returns MutableStateFlow(1)
        val resolver = mockk<SportsLiveZapResolver>()
        coEvery { resolver.resolve(any()) } returns null
        return SportsLiveZapViewModel(
            SavedStateHandle(mapOf("contentId" to playing.id)), resolver, mockk<DirectDebridResolver>(relaxed = true),
            profiles, repo, scores, mainDispatcherRule.dispatcher,
            okOnLive = flowOf(okOnLive),
            guideStyleSetting = flowOf(if (guide) SportsLiveGuideStyle.TABLE_SMOOTH else SportsLiveGuideStyle.PANEL)
        )
    }

    private fun item(id: String, startMs: Long?) = SportsLiveItem(
        meta = MetaPreview(
            id = id, type = ContentType.UNKNOWN, rawType = "tv", name = "Team $id vs Other $id", poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = "addon", addonName = "Sports", addonBaseUrl = "https://example.invalid", catalogKey = CATALOG,
        catalogName = "Live", kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false,
        startMs = startMs, sport = "Basketball", startHasTimeOfDay = startMs != null,
        teams = SportsLiveTeams.parse("Team $id vs Other $id")
    )

    private companion object {
        const val HOUR = 3_600_000L
        const val CATALOG = "addon:tv:live"
    }
}
