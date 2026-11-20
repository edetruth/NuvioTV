package com.nuvio.tv.ui.screens.sportslive

import androidx.lifecycle.viewModelScope
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.MySports
import com.nuvio.tv.core.sportslive.scores.ResultDay
import com.nuvio.tv.core.sportslive.scores.ResultsLoad
import com.nuvio.tv.core.sportslive.scores.ResultsRepository
import com.nuvio.tv.core.sportslive.scores.ScoreFeed
import com.nuvio.tv.core.sportslive.scores.ScoreProvider
import com.nuvio.tv.core.sportslive.scores.ScoreSettings
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoreboardResult
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.core.sportslive.scores.SportsResults
import com.nuvio.tv.data.local.SportsLivePreferencesDataStore
import com.nuvio.tv.domain.model.FollowedTeam
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Results' Today (#67): the opening day, the Earlier days, the 2-minute refresh and the live line. */
class SportsLiveResultsTodayTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeProvider : ScoreProvider {
        val calls = mutableListOf<ScoreFeed>()
        var respond: (ScoreFeed) -> ScoreboardResult = { ScoreboardResult(emptyList()) }
        override suspend fun scoreboard(league: EspnLeague, date: LocalDate): ScoreboardResult {
            val feed = ScoreFeed(league, date)
            calls += feed
            return respond(feed)
        }
    }

    private val provider = FakeProvider()
    private val settings = MutableStateFlow(ScoreSettings())
    private val followed = MutableStateFlow<List<FollowedTeam>>(emptyList())
    private val mySports = MutableStateFlow(MySports.decode(setOf("basketball")))
    private val prefs = mockk<SportsLivePreferencesDataStore> {
        every { scoreSettings } returns settings
        every { followedTeams } returns followed
        every { mySports } returns this@SportsLiveResultsTodayTest.mySports
        every { spoilerProtection } returns kotlinx.coroutines.flow.flowOf(com.nuvio.tv.core.sportslive.scores.SpoilerProtection())
    }
    private val base = Instant.parse("2026-11-16T22:00:00Z").toEpochMilli()
    private val today = LocalDate.of(2026, 11, 16)
    private val aFinal = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA).first { it.state == ScoreState.POST && it.isFinal }

    private fun TestScope.repository() = ResultsRepository(
        provider = provider,
        settings = settings,
        followedTeams = followed,
        mySports = mySports,
        now = { base + testScheduler.currentTime },
        zone = { ZoneOffset.UTC }
    )

    private fun todayHasFinal(has: Boolean) {
        provider.respond = { feed ->
            ScoreboardResult(if (has && feed.date == today) listOf(aFinal.copy(eventId = "final", startMs = base - 20 * 60 * 60_000L)) else emptyList())
        }
    }

    private fun TestScope.viewModel(repo: ResultsRepository = repository()) = SportsLiveResultsViewModel(repo, prefs, mainDispatcherRule.dispatcher)

    private fun SportsLiveResultsViewModel.close() = viewModelScope.cancel()

    @Test
    fun `opens on Today once it has a final, else on Yesterday`() = runTest {
        todayHasFinal(true)
        val vm = viewModel()
        assertNull(vm.selectedDay.value)
        vm.setActive(true)
        runCurrent()
        assertEquals(SportsResults.TODAY, vm.selectedDay.value)
        // Today's load decided the day and is shown as is: one request.
        assertEquals(1, provider.calls.size)
        vm.close()

        todayHasFinal(false)
        val before = viewModel()
        before.setActive(true)
        runCurrent()
        assertEquals(SportsResults.YESTERDAY, before.selectedDay.value)
        before.close()
    }

    @Test
    fun `the opening rule needs a final in My sports`() {
        val date = today
        assertEquals(SportsResults.YESTERDAY, defaultResultsDay(ResultsLoad.Loaded(date, emptyList(), emptyList())))
        assertEquals(SportsResults.YESTERDAY, defaultResultsDay(ResultsLoad.Failed))
        assertEquals(SportsResults.YESTERDAY, defaultResultsDay(ResultsLoad.Off))
        val row = SportsResults.rows(listOf(aFinal), emptyList())
        assertEquals(SportsResults.TODAY, defaultResultsDay(ResultsLoad.Loaded(date, row, listOf(aFinal), MySports.decode(setOf("basketball")))))
        // A final outside My sports does not open Today.
        assertEquals(SportsResults.YESTERDAY, defaultResultsDay(ResultsLoad.Loaded(date, row, listOf(aFinal), MySports.decode(setOf("hockey")))))
    }

    @Test
    fun `the chosen day is remembered for the process, and an Earlier day opens Earlier`() = runTest {
        val repo = repository()
        val vm = viewModel(repo)
        vm.selectDay(4)
        assertEquals(4, repo.chosenDay)
        vm.close()
        val again = viewModel(repo)
        assertEquals(4, again.selectedDay.value)
        assertTrue(again.earlierShown.value)
        again.close()
    }

    @Test
    fun `the chosen day is kept under 10 min away on the same ESPN day`() {
        val at = base // 17 00 US Eastern
        assertFalse(resultsDayExpired(at, at + 60_000L))
        assertFalse(resultsDayExpired(at, at + RESULTS_DAY_KEPT_MS - 1))
        assertTrue(resultsDayExpired(at, at + RESULTS_DAY_KEPT_MS))
        // 23 58 US Eastern: a minute later is the same ESPN day, three minutes later a new one.
        val beforeMidnight = Instant.parse("2026-11-17T04:58:00Z").toEpochMilli()
        assertFalse(resultsDayExpired(beforeMidnight, beforeMidnight + 60_000L))
        assertTrue(resultsDayExpired(beforeMidnight, beforeMidnight + 3 * 60_000L))
    }

    @Test
    fun `a quick hop away from Results keeps the chosen day (live20 user report)`() = runTest {
        todayHasFinal(true)
        val repo = repository()
        val vm = viewModel(repo)
        backgroundScope.launch { vm.uiState.collect {} }
        vm.selectDay(3)
        vm.setActive(true)
        advanceTimeBy(1_000); runCurrent()
        // Live now for a few minutes, then back.
        vm.setActive(false)
        advanceTimeBy(RESULTS_DAY_KEPT_MS - 60_000L); runCurrent()
        vm.setActive(true)
        runCurrent()
        assertEquals(3, vm.selectedDay.value)
        assertEquals(3, repo.chosenDay)
        assertEquals(0, vm.uiState.value.freshOpens)
        vm.setActive(false)
        vm.close()
    }

    @Test
    fun `back on Results after 10 min away it opens afresh at the top (live20 user report)`() = runTest {
        todayHasFinal(true)
        val repo = repository()
        val vm = viewModel(repo)
        backgroundScope.launch { vm.uiState.collect {} }
        val basketball = mySports.value.effectiveSports.first { it.espnSport == aFinal.league.sport }
        vm.selectDay(SportsResults.YESTERDAY)
        vm.selectSport(basketball)
        vm.setActive(true)
        advanceTimeBy(1_000); runCurrent()
        // Watching games on Live now, then back to Results.
        vm.setActive(false)
        advanceTimeBy(RESULTS_DAY_KEPT_MS); runCurrent()
        vm.setActive(true)
        advanceTimeBy(1_000); runCurrent()
        // Today (it has a final), ★ My sports, a new list (its key changes, so it starts at its top).
        assertEquals(SportsResults.TODAY, vm.selectedDay.value)
        assertNull(repo.chosenDay)
        assertFalse(vm.earlierShown.value)
        val state = vm.uiState.value
        assertEquals(1, state.freshOpens)
        assertNull(state.selectedSport)
        assertEquals(ResultsStatus.READY, state.status)
        // A screen built later (another visit) opens on the opening day too.
        vm.setActive(false)
        vm.close()
        assertNull(viewModel(repo).also { it.close() }.selectedDay.value)
    }

    @Test
    fun `a new ESPN day opens Results afresh even after a quick hop`() = runTest {
        todayHasFinal(true)
        val repo = repository()
        val vm = viewModel(repo)
        backgroundScope.launch { vm.uiState.collect {} }
        // 23 57 US Eastern.
        advanceTimeBy(7 * 60 * 60_000L - 3 * 60_000L); runCurrent()
        vm.selectDay(3)
        vm.setActive(true)
        advanceTimeBy(1_000); runCurrent()
        vm.setActive(false)
        // Back at 00 03: "3 days ago" would now be another date.
        advanceTimeBy(5 * 60_000L); runCurrent()
        vm.setActive(true)
        advanceTimeBy(1_000); runCurrent()
        assertNull(repo.chosenDay)
        assertEquals(1, vm.uiState.value.freshOpens)
        // The new Today has no final yet: the opening rule picks Yesterday.
        assertEquals(SportsResults.YESTERDAY, vm.selectedDay.value)
        vm.setActive(false)
        vm.close()
    }

    @Test
    fun `Earlier opens the 5 older days in place and closing it goes back to Yesterday`() = runTest {
        val days = (0..6).map { ResultDay(it, today.minusDays(it.toLong()), it == 1) }
        assertEquals(listOf(0, 1), resultRailDays(days, earlierOpen = false).map { it.daysAgo })
        assertEquals((0..6).toList(), resultRailDays(days, earlierOpen = true).map { it.daysAgo })

        val vm = viewModel()
        vm.selectDay(SportsResults.TODAY)
        assertFalse(vm.earlierShown.value)
        vm.toggleEarlier()
        assertTrue(vm.earlierShown.value)
        // Opening it selects nothing.
        assertEquals(SportsResults.TODAY, vm.selectedDay.value)
        vm.selectDay(5)
        vm.toggleEarlier()
        assertFalse(vm.earlierShown.value)
        assertEquals(SportsResults.YESTERDAY, vm.selectedDay.value)
        vm.close()
    }

    @Test
    fun `Today refreshes every 2 min only while Results is shown`() = runTest {
        todayHasFinal(true)
        val vm = viewModel()
        vm.setActive(true)
        runCurrent()
        assertEquals(1, provider.calls.size)
        advanceTimeBy(119_000); runCurrent()
        assertEquals(1, provider.calls.size)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(2, provider.calls.size)
        // Another tab, the player or the background: nothing.
        vm.setActive(false)
        runCurrent()
        advanceTimeBy(30 * 60_000L); runCurrent()
        assertEquals(2, provider.calls.size)
        // Back on Results: one fetch, then every 2 min again.
        vm.setActive(true)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(3, provider.calls.size)
        advanceTimeBy(120_000); runCurrent()
        assertEquals(4, provider.calls.size)
        vm.close()
    }

    @Test
    fun `back on Results the day keeps its list while it reloads (#67 review)`() = runTest {
        todayHasFinal(true)
        val vm = viewModel()
        vm.selectDay(SportsResults.YESTERDAY)
        vm.setActive(true)
        advanceTimeBy(1_000); runCurrent()
        val shown = vm.shownLoad.value
        assertTrue(shown is ResultsLoad.Loaded)
        // The Game page or a replay, then back: the list never empties.
        vm.setActive(false)
        runCurrent()
        vm.setActive(true)
        runCurrent()
        assertTrue(vm.shownLoad.value is ResultsLoad.Loaded)
        // A different day starts empty.
        vm.selectDay(3)
        runCurrent()
        assertNull(vm.shownLoad.value)
        vm.close()
    }

    @Test
    fun `Yesterday refreshes every 2 min while one of its games is live, then stops (#67 review)`() = runTest {
        var live = true
        provider.respond = { feed ->
            ScoreboardResult(if (feed.date == today.minusDays(1)) listOf(aFinal.copy(eventId = "late", state = if (live) ScoreState.IN else ScoreState.POST)) else emptyList())
        }
        val vm = viewModel()
        vm.selectDay(SportsResults.YESTERDAY)
        vm.setActive(true)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, provider.calls.size)
        live = false
        advanceTimeBy(120_000); runCurrent()
        assertEquals(2, provider.calls.size)
        // Over: no more refreshes.
        advanceTimeBy(60 * 60_000L); runCurrent()
        assertEquals(2, provider.calls.size)
        vm.close()
    }

    @Test
    fun `a past day is not refreshed`() = runTest {
        val vm = viewModel()
        vm.selectDay(SportsResults.YESTERDAY)
        vm.setActive(true)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, provider.calls.size)
        advanceTimeBy(60 * 60_000L); runCurrent()
        assertEquals(1, provider.calls.size)
        vm.close()
    }

    @Test
    fun `Scores Off makes no request at all`() = runTest {
        settings.value = ScoreSettings(mode = ScoresMode.OFF)
        val vm = viewModel()
        vm.setActive(true)
        advanceTimeBy(30 * 60_000L); runCurrent()
        assertTrue(provider.calls.isEmpty())
        vm.close()
    }

    @Test
    fun `the live line shows on Today only, once loaded, while Live now has live games`() {
        val ready = SportsLiveResultsUiState(daysAgo = SportsResults.TODAY, status = ResultsStatus.READY, liveNow = 3)
        assertTrue(ready.showsLiveNow(liveGames = 2))
        // ESPN counts a game live, but Live now has none: no line that would land on an empty tab (QA pass 3).
        assertFalse(ready.showsLiveNow(liveGames = 0))
        assertFalse(ready.copy(daysAgo = SportsResults.YESTERDAY).showsLiveNow(liveGames = 2))
        assertFalse(ready.copy(status = ResultsStatus.LOADING).showsLiveNow(liveGames = 2))
    }
}
