package com.nuvio.tv.ui.screens.sportslive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.sportslive.scores.DelayedValues
import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.GameSection
import com.nuvio.tv.core.sportslive.scores.GameSummary
import com.nuvio.tv.core.sportslive.scores.GameSummaryLoad
import com.nuvio.tv.core.sportslive.scores.GameSummaryRepository
import com.nuvio.tv.core.sportslive.scores.ScoreSettings
import com.nuvio.tv.core.sportslive.scores.ScoreSide
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.core.sportslive.scores.TeamFollows
import com.nuvio.tv.data.local.SportsLivePreferencesDataStore
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.SportsLiveItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * What opens a Game page: the ESPN [event] (from a Results row or a strictly matched card), the
 * strictly matched [replay] when there is one, and whether the score was already revealed where the
 * page was opened (Hide until focused).
 */
data class SportsLiveGameRequest(val event: ScoreSnapshot, val replay: SportsLiveItem? = null, val revealed: Boolean = false)

/** Hands the request to the Game page's ViewModel, once (like dual screen's session). */
internal object SportsLiveGameRequests {
    @Volatile
    private var request: SportsLiveGameRequest? = null

    fun open(request: SportsLiveGameRequest) {
        this.request = request
    }

    fun take(): SportsLiveGameRequest? = request.also { request = null }
}

enum class GameLoadStatus {
    /** Scores are Off and Reveal has not been pressed: nothing was requested. */
    WAITING_REVEAL,
    LOADING,
    READY,
    FAILED
}

data class SportsLiveGameUiState(
    /** The game as opened (null: nothing to show, the page closes). */
    val event: ScoreSnapshot? = null,
    /** The summary shown: for a live game with a score delay, one that is at least the delay old. */
    val summary: GameSummary? = null,
    val status: GameLoadStatus = GameLoadStatus.LOADING,
    val mode: ScoresMode = ScoresMode.SHOW,
    val revealed: Boolean = false,
    val selected: GameSection? = null,
    val replay: SportsLiveItem? = null,
    val awayFollowed: Boolean = false,
    val homeFollowed: Boolean = false
) {
    /** Score, records and stats show: Scores Show, or revealed (Hide until focused / Off). */
    val showsScore: Boolean get() = mode == ScoresMode.SHOW || revealed

    /** The sections with something to show; none while the score is hidden. */
    val sections: List<GameSection> get() = if (showsScore) summary?.sections.orEmpty() else emptyList()

    val section: GameSection? get() = selected?.takeIf { it in sections } ?: sections.firstOrNull()
}

/**
 * The Game page (#18): one ESPN summary, loaded through [GameSummaryRepository] (finals cached for good,
 * a live game at most 30 s old) and refreshed every [REFRESH_MS] only while the page is visible and the
 * game is live. Scores Off: no request until Reveal. A game live when opened shows each summary only
 * once the score delay has passed. Follow ‹team› writes the profile's My Teams list (#54).
 */
@HiltViewModel
class SportsLiveGameViewModel internal constructor(
    private val request: SportsLiveGameRequest?,
    private val loadSummary: suspend (EspnLeague, String) -> GameSummaryLoad,
    settings: Flow<ScoreSettings>,
    private val followedTeams: Flow<List<FollowedTeam>>,
    private val follow: suspend (FollowedTeam) -> Unit,
    private val unfollow: suspend (FollowedTeam) -> Unit,
    private val now: () -> Long
) : ViewModel() {

    @Inject
    constructor(summaries: GameSummaryRepository, prefs: SportsLivePreferencesDataStore) : this(
        request = SportsLiveGameRequests.take(),
        loadSummary = summaries::load,
        settings = prefs.scoreSettings,
        followedTeams = prefs.followedTeams,
        follow = prefs::follow,
        unfollow = prefs::unfollow,
        now = System::currentTimeMillis
    )

    private data class Page(
        val summary: GameSummary? = null,
        val status: GameLoadStatus = GameLoadStatus.LOADING,
        val mode: ScoresMode = ScoresMode.SHOW,
        val revealed: Boolean = false,
        val selected: GameSection? = null
    )

    private val page = MutableStateFlow(Page(revealed = request?.revealed == true))
    private val visible = MutableStateFlow(false)
    private val revealRequested = MutableStateFlow(request?.revealed == true)

    val uiState: StateFlow<SportsLiveGameUiState> = combine(page, followedTeams) { page, followed ->
        val event = request?.event
        SportsLiveGameUiState(
            event = event,
            summary = page.summary,
            status = page.status,
            mode = page.mode,
            revealed = page.revealed,
            selected = page.selected,
            replay = request?.replay,
            awayFollowed = event != null && TeamFollows.followersOf(followed, event, event.away).isNotEmpty(),
            homeFollowed = event != null && TeamFollows.followersOf(followed, event, event.home).isNotEmpty()
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        SportsLiveGameUiState(event = request?.event, replay = request?.replay, revealed = request?.revealed == true)
    )

    // Summaries waiting out the score delay; kept across visibility changes while the delay is the same.
    private var buffer: DelayedValues<GameSummary>? = null
    private var bufferDelayMs = -1L

    init {
        val event = request?.event
        if (event != null) {
            viewModelScope.launch {
                combine(settings, revealRequested, visible) { settings, revealed, visible -> Triple(settings, revealed, visible) }
                    .distinctUntilChanged()
                    .collectLatest { (settings, revealed, isVisible) ->
                        page.update { it.copy(mode = settings.mode, revealed = revealed) }
                        if (settings.mode == ScoresMode.OFF && !revealed) {
                            page.update { it.copy(status = GameLoadStatus.WAITING_REVEAL) }
                            return@collectLatest
                        }
                        if (page.value.status == GameLoadStatus.WAITING_REVEAL) page.update { it.copy(status = GameLoadStatus.LOADING) }
                        if (!isVisible) return@collectLatest
                        val delayMs = if (event.state == ScoreState.IN) settings.delaySeconds * 1_000L else 0L
                        val values = buffer?.takeIf { bufferDelayMs == delayMs }
                            ?: DelayedValues<GameSummary>(delayMs).also {
                                buffer = it
                                bufferDelayMs = delayMs
                            }
                        refreshWhileVisible(event, values)
                    }
            }
        }
    }

    /** Loads, then (while the game is live) reloads every [REFRESH_MS]; shows each summary once it is due. */
    private suspend fun refreshWhileVisible(event: ScoreSnapshot, values: DelayedValues<GameSummary>) {
        var nextFetchAt = now()
        while (true) {
            if (now() >= nextFetchAt) {
                val live = when (val load = loadSummary(event.league, event.eventId)) {
                    is GameSummaryLoad.Loaded -> {
                        values.push(load.summary, now())
                        !load.final && load.summary.isLive
                    }
                    GameSummaryLoad.Failed -> {
                        if (page.value.summary == null && values.nextDueAt(now()) == null) {
                            page.update { it.copy(status = GameLoadStatus.FAILED) }
                        }
                        // A live game tries again at the next refresh; anything else stays as it is.
                        event.state == ScoreState.IN || page.value.summary?.isLive == true
                    }
                }
                nextFetchAt = if (live) now() + REFRESH_MS else Long.MAX_VALUE
            }
            values.current(now())?.let { shown ->
                page.update { it.copy(summary = shown, status = GameLoadStatus.READY) }
            }
            val wake = minOf(nextFetchAt, values.nextDueAt(now()) ?: Long.MAX_VALUE)
            if (wake == Long.MAX_VALUE) return
            delay((wake - now()).coerceAtLeast(0))
        }
    }

    /** The page is on screen (started): only then does it load and refresh. */
    fun setVisible(isVisible: Boolean) {
        visible.value = isVisible
    }

    /** Reveal: score and stats show; under Scores Off this is when the first request is made. */
    fun reveal() {
        revealRequested.value = true
    }

    fun selectSection(section: GameSection) {
        page.update { it.copy(selected = section) }
    }

    /** Follow [side]'s team, or unfollow every followed team that is that side. */
    fun toggleFollow(side: ScoreSide) {
        val event = request?.event ?: return
        val team = if (side == ScoreSide.HOME) event.home else event.away
        viewModelScope.launch {
            val matching = TeamFollows.followersOf(followedTeams.first(), event, team)
            if (matching.isEmpty()) {
                TeamFollows.teamToFollow(event, team)?.let { follow(it) }
            } else {
                matching.forEach { unfollow(it) }
            }
        }
    }

    companion object {
        /** A live game's summary is fetched again this often while the page is visible. */
        const val REFRESH_MS = 30_000L
    }
}
