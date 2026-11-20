package com.nuvio.tv.ui.screens.sportslive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.PlayerCard
import com.nuvio.tv.core.sportslive.scores.PlayerCardRepository
import com.nuvio.tv.core.sportslive.scores.PlayerCardTarget
import com.nuvio.tv.core.sportslive.scores.PlayerCards
import com.nuvio.tv.core.sportslive.scores.ScoreSettings
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.core.sportslive.scores.TeamDirectory
import com.nuvio.tv.data.local.SportsLivePreferencesDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * What opens a Player card (#63): the athlete, and [spoilerSafe] when the Game page it came from was
 * (Catch up, #58): the last games then stay behind Reveal even under Scores Show. [revealed] when the page it
 * came from already shows the score (live.20 BUG19, Q11): the card starts revealed, no second Reveal.
 */
data class SportsLivePlayerCardRequest(
    val target: PlayerCardTarget,
    val spoilerSafe: Boolean = false,
    val revealed: Boolean = false
)

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

    /** Hide until focused (#134): each last game's result and line show while its row has focus; no Reveal needed. */
    val revealsOnFocus: Boolean get() = mode == ScoresMode.HIDE_UNTIL_FOCUSED && !spoilerSafe && !revealed

    /** Whether a last game's result and stat line show, its row [focused] or not. */
    fun showsGame(focused: Boolean): Boolean = showsLastGames || (revealsOnFocus && focused)

    /** Reveal is offered while something is hidden (Off, or spoiler-safe) and not known to be unavailable. */
    val showsReveal: Boolean get() = !showsLastGames && !revealsOnFocus && status != PlayerCardStatus.FAILED
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
        // The athlete and game log JSON parse off the main thread (#107).
        loadCard = { league, athleteId ->
            withContext(Dispatchers.Default) {
                cards.load(league, athleteId)?.let { PlayerCards.namedOpponents(it, TeamDirectory.shared.teams(league)) }
            }
        },
        settings = prefs.scoreSettings
    )

    private val state = MutableStateFlow(
        SportsLivePlayerCardUiState(target = request?.target, spoilerSafe = request?.spoilerSafe == true)
    )
    val uiState: StateFlow<SportsLivePlayerCardUiState> = state.asStateFlow()

    private val revealRequested = MutableStateFlow(request?.revealed == true)

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
