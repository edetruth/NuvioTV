package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.TestPreferencesStore
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sportslive.scores.MyLeague
import com.nuvio.tv.core.sportslive.scores.MySport
import com.nuvio.tv.core.sportslive.scores.MySports
import com.nuvio.tv.core.sportslive.scores.ScoreDelayChoices
import com.nuvio.tv.core.sportslive.scores.ScoreSettings
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.SportsLivePreferencesDataStore
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.ui.screens.settings.SportsLiveSetting
import com.nuvio.tv.ui.screens.settings.SportsLiveSettingsItem
import com.nuvio.tv.ui.screens.settings.sportsLiveSettingsItems
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SportsLiveSetupViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val prefsStore = TestPreferencesStore()
    private val factory = mockk<ProfileDataStoreFactory> {
        every { get(1, SportsLivePreferencesDataStore.FEATURE) } returns prefsStore
    }
    private val profiles = mockk<ProfileManager> {
        every { activeProfileId } returns MutableStateFlow(1)
    }
    private val prefs = SportsLivePreferencesDataStore(factory, profiles)

    private fun kotlinx.coroutines.test.TestScope.newViewModel(): SportsLiveSetupViewModel =
        SportsLiveSetupViewModel(prefs).also { advanceUntilIdle() }

    @Test
    fun `setup starts on Welcome with the saved defaults`() = runTest {
        val vm = newViewModel()
        val state = vm.uiState.value
        assertEquals(SetupStep.WELCOME, state.step)
        assertEquals(MySports.DEFAULT, state.sports)
        assertEquals(ScoresMode.SHOW, state.scoresMode)
        // Score delay never set: the 60 s default (#85).
        assertEquals(60, state.delaySeconds)
        assertNull(state.exit)
    }

    @Test
    fun `Next on Sports saves My sports before step 3 shows`() = runTest {
        val vm = newViewModel()
        vm.next()
        advanceUntilIdle()
        assertEquals(SetupStep.SPORTS, vm.uiState.value.step)

        vm.toggleSport(MySport.HOCKEY)
        vm.pressLeague(MyLeague.WNBA)
        // Nothing is saved until Next.
        assertEquals(MySports.DEFAULT, prefs.mySports.first())

        vm.next()
        advanceUntilIdle()
        assertEquals(SetupStep.TEAMS, vm.uiState.value.step)
        val saved = prefs.mySports.first()
        assertEquals(vm.uiState.value.sports, saved)
        assertFalse(MySport.HOCKEY in saved.effectiveSports)
        assertTrue(MyLeague.WNBA in saved.leaguesOf(MySport.BASKETBALL))
    }

    @Test
    fun `the new leagues are picked in step 2 like the others`() = runTest {
        val vm = newViewModel()
        vm.pressLeague(MyLeague.WOMENS_COLLEGE_BASKETBALL)
        vm.pressLeague(MyLeague.NWSL)
        advanceUntilIdle()
        val sports = vm.uiState.value.sports
        assertTrue(MyLeague.WOMENS_COLLEGE_BASKETBALL in sports.leaguesOf(MySport.BASKETBALL))
        assertEquals(setOf(MyLeague.NWSL), sports.namedLeagues(MySport.SOCCER))
    }

    @Test
    fun `Next on Scores saves the mode and the delay`() = runTest {
        val vm = newViewModel()
        repeat(3) { vm.next(); advanceUntilIdle() }
        assertEquals(SetupStep.SCORES, vm.uiState.value.step)

        vm.selectScoresMode(ScoresMode.HIDE_UNTIL_FOCUSED)
        vm.selectDelay(30)
        assertEquals(ScoreSettings(delaySeconds = ScoreDelayChoices.DEFAULT_SECONDS), prefs.scoreSettings.first())

        vm.next()
        advanceUntilIdle()
        assertEquals(SetupStep.DONE, vm.uiState.value.step)
        assertEquals(ScoreSettings(ScoresMode.HIDE_UNTIL_FOCUSED, 30), prefs.scoreSettings.first())
    }

    @Test
    fun `Go to Live Now on Done finishes`() = runTest {
        val vm = newViewModel()
        repeat(4) { vm.next(); advanceUntilIdle() }
        assertEquals(SetupStep.DONE, vm.uiState.value.step)
        vm.next()
        advanceUntilIdle()
        assertEquals(SetupExit.FINISHED, vm.uiState.value.exit)
    }

    @Test
    fun `leaving halfway keeps what earlier steps saved, and Skip saves nothing of this step`() = runTest {
        val vm = newViewModel()
        vm.next(); advanceUntilIdle()
        vm.toggleSport(MySport.GOLF)
        vm.next(); advanceUntilIdle()
        val afterSports = prefs.mySports.first()

        vm.next(); advanceUntilIdle()
        assertEquals(SetupStep.SCORES, vm.uiState.value.step)
        vm.selectScoresMode(ScoresMode.OFF)
        vm.skip()
        advanceUntilIdle()

        assertEquals(SetupExit.SKIPPED, vm.uiState.value.exit)
        assertEquals(afterSports, prefs.mySports.first())
        assertFalse(MySport.GOLF in prefs.mySports.first().effectiveSports)
        assertEquals(ScoreSettings(delaySeconds = ScoreDelayChoices.DEFAULT_SECONDS), prefs.scoreSettings.first())
    }

    @Test
    fun `Skip on Welcome keeps every default`() = runTest {
        val vm = newViewModel()
        vm.skip()
        advanceUntilIdle()
        assertEquals(SetupExit.SKIPPED, vm.uiState.value.exit)
        assertEquals(MySports.DEFAULT, prefs.mySports.first())
        assertEquals(ScoreSettings(delaySeconds = ScoreDelayChoices.DEFAULT_SECONDS), prefs.scoreSettings.first())
        assertEquals(emptyList<FollowedTeam>(), prefs.followedTeams.first())
    }

    @Test
    fun `Back goes a step back keeping the picks, and on Welcome skips`() = runTest {
        val vm = newViewModel()
        vm.next(); advanceUntilIdle()
        vm.toggleSport(MySport.MMA)
        advanceUntilIdle()
        val picked = vm.uiState.value.sports
        assertFalse(MySport.MMA in picked.effectiveSports)
        vm.back()
        advanceUntilIdle()
        assertEquals(SetupStep.WELCOME, vm.uiState.value.step)
        vm.next(); advanceUntilIdle()
        assertEquals(picked, vm.uiState.value.sports)
        vm.back()
        vm.back()
        advanceUntilIdle()
        assertEquals(SetupExit.SKIPPED, vm.uiState.value.exit)
        assertEquals(MySports.DEFAULT, prefs.mySports.first())
    }

    @Test
    fun `run again from Settings it starts from the saved choices`() = runTest {
        prefs.updateMySports { MySports(mapOf(MySport.SOCCER to setOf(MyLeague.PREMIER_LEAGUE))) }
        prefs.setScoresMode(ScoresMode.OFF)
        prefs.setScoreDelaySeconds(60)
        prefs.follow(FollowedTeam("Arsenal", "Soccer"))

        val vm = newViewModel()
        val state = vm.uiState.value
        assertEquals(SetupStep.WELCOME, state.step)
        assertEquals(prefs.mySports.first(), state.sports)
        assertEquals(ScoresMode.OFF, state.scoresMode)
        assertEquals(60, state.delaySeconds)
        assertEquals(listOf("Arsenal"), state.followed.map { it.name })

        // Skip changes nothing of what was set.
        vm.skip()
        advanceUntilIdle()
        assertEquals(ScoreSettings(ScoresMode.OFF, 60), prefs.scoreSettings.first())
        assertEquals(setOf(MyLeague.PREMIER_LEAGUE), prefs.mySports.first().namedLeagues(MySport.SOCCER))
    }

    @Test
    fun `Settings shows Run setup again once setup can open, and its exits`() {
        val run = SportsLiveSettingsItem.Setting(SportsLiveSetting.RUN_SETUP)
        assertTrue(run in sportsLiveSettingsItems(moreExpanded = false, setupAvailable = true, pipSystemAvailable = false, catalogCount = 0))
        assertFalse(run in sportsLiveSettingsItems(moreExpanded = false, setupAvailable = false, pipSystemAvailable = false, catalogCount = 0))

        // From Settings: Go to Live Now opens the section, Skip returns to Settings.
        assertEquals(SportsLiveSetupExitTarget.LIVE_NOW, sportsLiveSetupExitTarget(SetupExit.FINISHED, fromSettings = true))
        assertEquals(SportsLiveSetupExitTarget.BACK, sportsLiveSetupExitTarget(SetupExit.SKIPPED, fromSettings = true))
        // From the section: either way back to it.
        assertEquals(SportsLiveSetupExitTarget.BACK, sportsLiveSetupExitTarget(SetupExit.FINISHED, fromSettings = false))
        assertEquals(SportsLiveSetupExitTarget.BACK, sportsLiveSetupExitTarget(SetupExit.SKIPPED, fromSettings = false))
    }

    @Test
    fun `a run from Settings does not bring first-run back`() = runTest {
        assertTrue(prefs.claimFirstRunSetup())
        val vm = newViewModel()
        repeat(5) { vm.next(); advanceUntilIdle() }
        assertEquals(SetupExit.FINISHED, vm.uiState.value.exit)
        assertFalse(prefs.claimFirstRunSetup())
    }
}
