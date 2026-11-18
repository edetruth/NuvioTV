package com.nuvio.tv.ui.screens.sportslive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.PlayerCard
import com.nuvio.tv.core.sportslive.scores.PlayerCardRepository
import com.nuvio.tv.core.sportslive.scores.PlayerCardTarget
import com.nuvio.tv.core.sportslive.scores.ScoreSettings
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.data.local.SportsLivePreferencesDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * What opens a Player card (#63): the athlete, and [spoilerSafe] when the Game page it came from was
 * (Catch up, #58): the last games then stay behind Reveal even under Scores Show.
 */
data class SportsLivePlayerCardRequest(val target: PlayerCardTarget, val spoilerSafe: Boolean = false)

/** Hands the request to the Player card's ViewModel, once (like the Game page's). */
internal object SportsLivePlayerCardRequests {
    @Volatile
    private var request: SportsLivePlayerCardRequest? = null

    fun open(request: SportsLivePlayerCardRequest) {
        this.request = request
    }

    fun take(): SportsLivePlayerCardRequest? = request.also { request = null }
}

enum class PlayerCardStatus {
    /** Scores are Off and Reveal has not been pressed: nothing was requested. */
    WAITING_REVEAL,
    LOADING,
    READY,
    FAILED
}

data class SportsLivePlayerCardUiState(
    /** The player as opened (null: nothing to show, the card closes). */
    val target: PlayerCardTarget? = null,
    val card: PlayerCard? = null,
    val status: PlayerCardStatus = PlayerCardStatus.LOADING,
    val mode: ScoresMode = ScoresMode.SHOW,
    val revealed: Boolean = false,
    val spoilerSafe: Boolean = false
) {
    /** The last games' results and stat lines show: Scores Show (unless [spoilerSafe]), or revealed. */
    val showsLastGames: Boolean get() = (mode == ScoresMode.SHOW && !spoilerSafe) || revealed

    /** Reveal is offered while something is hidden and not known to be unavailable. */
    val showsReveal: Boolean get() = !showsLastGames && status != PlayerCardStatus.FAILED
}

/**
 * The Player card (#63): one ESPN athlete + game log, requested only once the card is open, through
 * [PlayerCardRepository] (kept 10 min). Scores Off: no request until Reveal. Hide until focused: season
 * stats show, the last games (results and lines) wait for Reveal.
 */
@HiltViewModel
class SportsLivePlayerCardViewModel internal constructor(
    private val request: SportsLivePlayerCardRequest?,
    private val loadCard: suspend (EspnLeague, String) -> PlayerCard?,
    settings: Flow<ScoreSettings>
) : ViewModel() {

    @Inject
    constructor(cards: PlayerCardRepository, prefs: SportsLivePreferencesDataStore) : this(
        request = SportsLivePlayerCardRequests.take(),
        loadCard = cards::load,
        settings = prefs.scoreSettings
    )

    private val state = MutableStateFlow(
        SportsLivePlayerCardUiState(target = request?.target, spoilerSafe = request?.spoilerSafe == true)
    )
    val uiState: StateFlow<SportsLivePlayerCardUiState> = state.asStateFlow()

    private val revealRequested = MutableStateFlow(false)

    init {
        val target = request?.target
        if (target != null) {
            viewModelScope.launch {
                combine(settings.map { it.mode }, revealRequested) { mode, revealed -> mode to revealed }
                    .distinctUntilChanged()
                    .collectLatest { (mode, revealed) ->
                        state.update { it.copy(mode = mode, revealed = revealed) }
                        if (mode == ScoresMode.OFF && !revealed && state.value.card == null) {
                            state.update { it.copy(status = PlayerCardStatus.WAITING_REVEAL) }
                            return@collectLatest
                        }
                        if (state.value.card != null) return@collectLatest
                        state.update { it.copy(status = PlayerCardStatus.LOADING) }
                        val card = loadCard(target.league, target.athleteId)
                        state.update {
                            if (card != null) it.copy(card = card, status = PlayerCardStatus.READY)
                            else it.copy(status = PlayerCardStatus.FAILED)
                        }
                    }
            }
        }
    }

    /** Reveal: the last games show; under Scores Off this is when the card is first requested. */
    fun reveal() {
        revealRequested.value = true
    }
}
