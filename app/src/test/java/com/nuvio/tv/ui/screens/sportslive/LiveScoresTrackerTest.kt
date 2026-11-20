package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.SportsLiveEspnStarts
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.LiveScoreTracking
import com.nuvio.tv.core.sportslive.scores.LiveScoresRepository
import com.nuvio.tv.core.sportslive.scores.ScoreProvider
import com.nuvio.tv.core.sportslive.scores.ScoreSettings
import com.nuvio.tv.core.sportslive.scores.ScoreboardResult
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class LiveScoresTrackerTest {

    private class Recorder : LiveScoreTracking {
        val calls = mutableListOf<String>()
        override fun track(screenId: String, cards: List<SportsLiveItem>) {
            calls += "track $screenId ${cards.map { it.id }}"
        }
        override fun untrack(screenId: String) {
            calls += "untrack $screenId"
        }
    }

    private fun item(name: String) = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
        kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false,
        startMs = Instant.parse("2026-11-15T18:00:00Z").toEpochMilli(), sport = "Basketball",
        startHasTimeOfDay = true, teams = SportsLiveTeams.parse(name)
    )

    private val pacers = item("Indiana Pacers at Philadelphia 76ers")
    private val lakers = item("Los Angeles Lakers at Phoenix Suns")

    @Test
    fun `cards are tracked only while the screen is started`() {
        val recorder = Recorder()
        val tracker = LiveScoresTracker("sports_live", recorder)
        tracker.update(listOf(pacers), enabled = true)
        assertTrue(recorder.calls.isEmpty())
        tracker.onStart()
        tracker.update(listOf(pacers, lakers), enabled = true)
        // The same cards again (a recomposition) are not re-sent.
        tracker.update(listOf(pacers, lakers), enabled = true)
        tracker.onStop()
        tracker.update(listOf(lakers), enabled = true)
        tracker.onStart()
        assertEquals(
            listOf(
                "track sports_live [${pacers.id}]",
                "track sports_live [${pacers.id}, ${lakers.id}]",
                "untrack sports_live",
                "track sports_live [${lakers.id}]"
            ),
            recorder.calls
        )
    }

    @Test
    fun `no cards, or leaving the tab, untracks the screen`() {
        val recorder = Recorder()
        val tracker = LiveScoresTracker("home", recorder)
        tracker.onStart()
        tracker.update(listOf(pacers), enabled = true)
        tracker.update(emptyList(), enabled = true)
        assertEquals(listOf("track home [${pacers.id}]", "untrack home"), recorder.calls)
    }

    @Test
    fun `Scores Off tracks nothing and untracks a tracked screen`() {
        val recorder = Recorder()
        val tracker = LiveScoresTracker("home", recorder)
        tracker.onStart()
        tracker.update(listOf(pacers), enabled = false)
        assertTrue(recorder.calls.isEmpty())
        tracker.update(listOf(pacers), enabled = true)
        tracker.update(listOf(pacers), enabled = false)
        assertEquals(listOf("track home [${pacers.id}]", "untrack home"), recorder.calls)
    }

    @Test
    fun `each screen keeps its own id`() {
        val recorder = Recorder()
        val home = LiveScoresTracker(LiveScoresRepository.SCREEN_HOME, recorder)
        val section = LiveScoresTracker(LiveScoresRepository.SCREEN_SECTION, recorder)
        home.onStart(); home.update(listOf(pacers), enabled = true)
        section.onStart(); section.update(listOf(lakers), enabled = true)
        home.onStop()
        assertEquals(
            listOf(
                "track ${LiveScoresRepository.SCREEN_HOME} [${pacers.id}]",
                "track ${LiveScoresRepository.SCREEN_SECTION} [${lakers.id}]",
                "untrack ${LiveScoresRepository.SCREEN_HOME}"
            ),
            recorder.calls
        )
    }

    @Test
    fun `Scores Off makes zero requests through the real repository, and stopping stops polling`() = runTest {
        val requests = mutableListOf<LocalDate>()
        val provider = object : ScoreProvider {
            override suspend fun scoreboard(league: EspnLeague, date: LocalDate): ScoreboardResult {
                requests += date
                return ScoreboardResult(emptyList())
            }
        }
        val settings = MutableStateFlow(ScoreSettings(mode = ScoresMode.OFF))
        val base = Instant.parse("2026-11-15T17:00:00Z").toEpochMilli()
        val repo = LiveScoresRepository(
            provider = provider, settings = settings, scope = backgroundScope,
            now = { base + testScheduler.currentTime }, zone = { ZoneOffset.UTC }, jitter = { 0L },
            espnStarts = SportsLiveEspnStarts()
        )
        backgroundScope.launch { repo.scores.collect {} }
        val tracker = LiveScoresTracker(LiveScoresRepository.SCREEN_SECTION, repo)
        tracker.onStart()
        tracker.update(listOf(pacers), enabled = settings.value.mode != ScoresMode.OFF)
        runCurrent()
        advanceTimeBy(30 * 60_000L); runCurrent()
        assertTrue(requests.isEmpty())

        // Scores on: the NBA feed, then (it lists no game) the college feed for the bare "Basketball"
        // card (#66); then the screen stops and nothing more is fetched.
        settings.value = ScoreSettings()
        tracker.update(listOf(pacers), enabled = true)
        runCurrent()
        assertEquals(2, requests.size)
        tracker.onStop()
        runCurrent()
        advanceTimeBy(30 * 60_000L); runCurrent()
        assertEquals(2, requests.size)
    }
}
