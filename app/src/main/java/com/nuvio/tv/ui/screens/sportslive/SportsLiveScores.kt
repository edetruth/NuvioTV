package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.sportslive.scores.LiveScoreTracking
import com.nuvio.tv.core.sportslive.scores.LiveScoresRepository
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.domain.model.SportsLiveItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import javax.inject.Inject

/**
 * Live scores for a screen that lists Sports & Live cards (the section, Home's Live now row). Collect
 * [scores] with the lifecycle (`collectAsStateWithLifecycle`): the repository polls only while someone
 * does. Hide until focused reveals a card's score after [ResultSpoilerReveal.REVEAL_AFTER_MS] of focus.
 */
@HiltViewModel
class SportsLiveScoresViewModel @Inject constructor(private val repo: LiveScoresRepository) : ViewModel() {

    /** Delayed scores by card key (a merged card's primary streamKey); empty while Scores is Off. */
    val scores: StateFlow<Map<String, ScoreSnapshot>> = repo.scores

    /** The Scores setting; null until read, so nothing is tracked or shown before it is known. */
    val mode: StateFlow<ScoresMode?> = repo.mode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val tracking: LiveScoreTracking get() = repo

    private val reveal = ResultSpoilerReveal(viewModelScope)

    /** Card keys revealed under Hide until focused; they stay revealed while the screen lives. */
    val revealed: StateFlow<Set<String>> = reveal.revealed

    /** The focused card's key, or null when no card is focused. */
    fun onCardFocus(key: String?) = reveal.onFocus(key)
}

/**
 * A screen's tracked cards, gated by its lifecycle and the Scores setting: the cards are tracked only
 * while the screen is started and Scores is not Off; stopping, disposing or turning Scores Off
 * untracks the screen, so nothing polls for a screen no one sees.
 */
internal class LiveScoresTracker(private val screenId: String, private val target: LiveScoreTracking) {
    private var cards: List<SportsLiveItem> = emptyList()
    private var enabled = false
    private var started = false
    private var trackedCards: List<SportsLiveItem>? = null

    fun update(cards: List<SportsLiveItem>, enabled: Boolean) {
        this.cards = cards
        this.enabled = enabled
        sync()
    }

    fun onStart() {
        started = true
        sync()
    }

    fun onStop() {
        started = false
        sync()
    }

    private fun sync() {
        val wanted = cards.takeIf { started && enabled && it.isNotEmpty() }
        if (wanted == trackedCards) return
        trackedCards = wanted
        if (wanted == null) target.untrack(screenId) else target.track(screenId, wanted)
    }
}

/**
 * Tracks [cards] for [screenId] while this composition's lifecycle is started (ON_START tracks, ON_STOP
 * and disposal untrack) and [enabled] (Scores not Off).
 */
@Composable
internal fun TrackLiveScores(screenId: String, cards: List<SportsLiveItem>, enabled: Boolean, tracking: LiveScoreTracking) {
    val tracker = remember(screenId, tracking) { LiveScoresTracker(screenId, tracking) }
    SideEffect { tracker.update(cards, enabled) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, tracker) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> tracker.onStart()
                Lifecycle.Event.ON_STOP -> tracker.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            tracker.onStop()
        }
    }
}

/** The wall clock, updated every [stepMs] while composed: moves Live now cards whose game ended (#60). */
@Composable
internal fun rememberSportsLiveClockMs(stepMs: Long = 30_000L): State<Long> =
    produceState(System.currentTimeMillis(), stepMs) {
        while (true) {
            delay(stepMs)
            value = System.currentTimeMillis()
        }
    }
