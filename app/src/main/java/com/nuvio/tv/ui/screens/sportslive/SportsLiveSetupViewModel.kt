package com.nuvio.tv.ui.screens.sportslive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.sportslive.scores.MyLeague
import com.nuvio.tv.core.sportslive.scores.MySport
import com.nuvio.tv.core.sportslive.scores.MySports
import com.nuvio.tv.core.sportslive.scores.ScoreDelayChoices
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.data.local.SportsLivePreferencesDataStore
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.ui.screens.settings.pressAllCompetitions
import com.nuvio.tv.ui.screens.settings.pressLeague
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** First-run setup's steps (#134 phase 7), in order. */
enum class SetupStep {
    WELCOME, SPORTS, TEAMS, SCORES, DONE;

    val number: Int get() = ordinal + 1
    val previous: SetupStep? get() = entries.getOrNull(ordinal - 1)
    val next: SetupStep? get() = entries.getOrNull(ordinal + 1)
}

/** How setup ended: Skip setup (or Back on Welcome), or Go to Live Now on Done. */
enum class SetupExit { SKIPPED, FINISHED }

/** Where setup goes when it ends: back to what opened it, or Live Now. */
enum class SportsLiveSetupExitTarget { BACK, LIVE_NOW }

/**
 * Opened by the section, setup returns to it (it opens on Live Now). Run again from Settings, Skip returns to
 * Settings and Go to Live Now opens the section.
 */
fun sportsLiveSetupExitTarget(exit: SetupExit, fromSettings: Boolean): SportsLiveSetupExitTarget =
    if (fromSettings && exit == SetupExit.FINISHED) SportsLiveSetupExitTarget.LIVE_NOW else SportsLiveSetupExitTarget.BACK

/** What first-run setup draws. Steps 2 and 4 edit a draft that their Next saves. */
data class SportsLiveSetupUiState(
    val step: SetupStep = SetupStep.WELCOME,
    /** Step 2's sports and leagues, from My sports as saved. */
    val sports: MySports = MySports.DEFAULT,
    /** Step 4: Show / Hide until focused / Off, and the score delay in seconds. */
    val scoresMode: ScoresMode = ScoresMode.SHOW,
    val delaySeconds: Int = 0,
    /** For Done's summary: the followed teams and whether kickoff reminders are on for them. */
    val followed: List<FollowedTeam> = emptyList(),
    val remindMyTeams: Boolean = true,
    /** Set once: the screen leaves. */
    val exit: SetupExit? = null
)

private data class SetupDraft(
    val step: SetupStep = SetupStep.WELCOME,
    val sports: MySports = MySports.DEFAULT,
    val scoresMode: ScoresMode = ScoresMode.SHOW,
    val delaySeconds: Int = 0,
    val exit: SetupExit? = null
)

/**
 * First-run setup (#134 phase 7): five skippable steps. Each Next saves its step (My sports on step 2, the
 * score settings on step 4; teams are followed in place on step 3), so leaving halfway keeps what was picked.
 * Skip saves nothing more, so what was not reached keeps today's defaults.
 */
@HiltViewModel
class SportsLiveSetupViewModel @Inject constructor(
    private val prefs: SportsLivePreferencesDataStore
) : ViewModel() {

    private val draft = MutableStateFlow(SetupDraft())
    private var saving = false

    val uiState: StateFlow<SportsLiveSetupUiState> = combine(draft, prefs.followedTeams, prefs.alertSettings) { d, followed, alerts ->
        SportsLiveSetupUiState(
            step = d.step,
            sports = d.sports,
            scoresMode = d.scoresMode,
            delaySeconds = d.delaySeconds,
            followed = followed,
            remindMyTeams = alerts.remindMyTeams,
            exit = d.exit
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SportsLiveSetupUiState())

    init {
        // The drafts start from what is saved, so a re-run from Settings shows the current choices.
        viewModelScope.launch {
            val sports = prefs.mySports.first()
            val scores = prefs.scoreSettings.first()
            draft.update { it.copy(sports = sports, scoresMode = scores.mode, delaySeconds = scores.delaySeconds) }
        }
    }

    /** Step 2: a sport on or off (the last sport stays on). */
    fun toggleSport(sport: MySport) = draft.update { it.copy(sports = it.sports.toggleSport(sport)) }

    /** Step 2: a league pressed; no league left turns its sport off, as in Settings. */
    fun pressLeague(league: MyLeague) = draft.update { it.copy(sports = it.sports.pressLeague(league)) }

    /** Step 2: soccer's "All competitions" pressed. */
    fun pressAllCompetitions() = draft.update { it.copy(sports = it.sports.pressAllCompetitions()) }

    /** Step 4: the scores card picked. */
    fun selectScoresMode(mode: ScoresMode) = draft.update { it.copy(scoresMode = mode) }

    /** Step 4: the score delay picked. */
    fun selectDelay(seconds: Int) = draft.update { it.copy(delaySeconds = ScoreDelayChoices.coerce(seconds)) }

    /** Next: saves this step, then shows the next one; on Done, leaves for Live Now. */
    fun next() {
        if (saving || draft.value.exit != null) return
        saving = true
        viewModelScope.launch {
            try {
                val current = draft.value
                save(current)
                draft.update { it.copy(step = current.step.next ?: it.step, exit = if (current.step.next == null) SetupExit.FINISHED else null) }
            } finally {
                saving = false
            }
        }
    }

    /** Back: the previous step (its picks kept); on Welcome, the same as Skip. */
    fun back() {
        if (saving || draft.value.exit != null) return
        val previous = draft.value.step.previous ?: return skip()
        draft.update { it.copy(step = previous) }
    }

    /** Skip setup: leaves without saving this step; what earlier Nexts saved stays. */
    fun skip() {
        if (saving) return
        draft.update { if (it.exit == null) it.copy(exit = SetupExit.SKIPPED) else it }
    }

    private suspend fun save(current: SetupDraft) {
        when (current.step) {
            SetupStep.SPORTS -> prefs.updateMySports { current.sports }
            SetupStep.SCORES -> {
                prefs.setScoresMode(current.scoresMode)
                prefs.setScoreDelaySeconds(current.delaySeconds)
            }
            SetupStep.WELCOME, SetupStep.TEAMS, SetupStep.DONE -> Unit
        }
    }
}

/** Sports & Live opening: claims first-run setup for the active profile ([SportsLivePreferencesDataStore.claimFirstRunSetup]). */
@HiltViewModel
class SportsLiveSetupGateViewModel @Inject constructor(
    private val prefs: SportsLivePreferencesDataStore
) : ViewModel() {
    suspend fun claim(): Boolean = prefs.claimFirstRunSetup()
}
