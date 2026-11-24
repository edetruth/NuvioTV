package com.nuvio.tv.ui.screens.sportslive

import androidx.lifecycle.SavedStateHandle
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.debrid.DirectDebridResolver
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sportslive.SportsLiveEspnStarts
import com.nuvio.tv.core.sportslive.SportsLiveScoreWait
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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * #375: the open panel's wait for ESPN updates the lineup once at its deadline, never again for that deadline. The panel
 * reads the wall clock, which barely moves under virtual time: the pinned-clock case that looped before the fix.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ZapPanelScoreWaitTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Before
    fun setUp() = SportsLiveZapSessions.clear()

    @After
    fun tearDown() = SportsLiveZapSessions.clear()

    private val now = System.currentTimeMillis()

    @Test
    fun `the panel's one-shot wait update never re-arms for the same deadline when the clock does not move`() = runTest(mainDispatcherRule.dispatcher) {
        // ESPN-covered games whose feed never answers: they keep waiting, their deadline stays the same.
        val playing = item("p", "Indiana Pacers at Philadelphia 76ers")
        val other = item("o", "Boston Celtics at New York Knicks")
        val latest = MutableStateFlow(SportsLiveLoad(listOf(playing, other), emptyList(), 0L, setOf(CATALOG)))
        SportsLiveZapSessions.start(
            SportsLiveZapSession.of(latest.value.items, playing.streamKey, SportsLiveTab.LIVE_NOW, null, returnToHomeOnBack = false)!!
        )
        val scores = LiveScoresRepository(
            provider = object : ScoreProvider {
                override suspend fun scoreboard(league: EspnLeague, date: LocalDate): ScoreboardResult = awaitCancellation()
            },
            settings = MutableStateFlow(ScoreSettings(mode = ScoresMode.SHOW)), scope = backgroundScope,
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
        val viewModel = SportsLiveZapViewModel(
            SavedStateHandle(mapOf("contentId" to playing.id)), resolver, mockk<DirectDebridResolver>(relaxed = true),
            profiles, repo, scores, mainDispatcherRule.dispatcher
        )
        runCurrent()
        // The open panel ticks every 30 s for ever, so the run cannot go to idle: it runs to just before the next tick.
        viewModel.openPanel()
        try {
            runCurrent()
            advanceTimeBy(3 * SportsLiveScoreWait.MAX_WAIT_MS - 1_000L)
            runCurrent()
            // Once, at the deadline; before the fix each wake armed the same deadline again (10 s, 20 s, ...).
            assertEquals(1, viewModel.panelWaitWakeCount)
        } finally {
            viewModel.closePanel()
            runCurrent()
        }
    }

    private fun item(id: String, name: String) = SportsLiveItem(
        meta = MetaPreview(
            id = id, type = ContentType.UNKNOWN, rawType = "tv", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = listOf("Basketball")
        ),
        addonId = "addon", addonName = "Sports", addonBaseUrl = "https://example.invalid", catalogKey = CATALOG,
        catalogName = "Live", kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false,
        startMs = now - 60 * 60_000L, sport = "Basketball", startHasTimeOfDay = true,
        teams = SportsLiveTeams.parse(name)
    )

    private companion object {
        const val CATALOG = "addon:tv:live"
    }
}
