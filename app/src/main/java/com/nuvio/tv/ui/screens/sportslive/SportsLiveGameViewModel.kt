package com.nuvio.tv.ui.screens.sportslive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.sportslive.scores.CollegeRankingsRepository
import com.nuvio.tv.core.sportslive.scores.DelayedValues
import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.GameSection
import com.nuvio.tv.core.sportslive.scores.GameSummary
import com.nuvio.tv.core.sportslive.scores.GameSummaryLoad
import com.nuvio.tv.core.sportslive.scores.GameSummaryRepository
import com.nuvio.tv.core.sportslive.scores.RankDisplay
import com.nuvio.tv.core.sportslive.scores.ScoreChips
import com.nuvio.tv.core.sportslive.scores.ScoreMatcher
import com.nuvio.tv.core.sportslive.scores.ScoreSettings
import com.nuvio.tv.core.sportslive.scores.ScoreSide
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.core.sportslive.scores.SpoilerContext
import com.nuvio.tv.core.sportslive.scores.SpoilerPolicy
import com.nuvio.tv.core.sportslive.scores.SpoilerProtection
import com.nuvio.tv.core.sportslive.scores.SpoilerProtectionStore
import com.nuvio.tv.core.sportslive.scores.SpoilerSurface
import com.nuvio.tv.core.sportslive.scores.TeamFollows
import com.nuvio.tv.data.local.SportsLivePreferencesDataStore
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.ui.screens.sportslive.dual.SportsLiveDualSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * What opens a Game page: the ESPN [event] (from a Results row or a strictly matched card), the
 * strictly matched [replay] when there is one, whether the score was already revealed where the
 * page was opened (Hide until focused), and [homeFirst]: the card it was opened from names the home
 * team first, so the page does too (N10). Results rows are away first, as ESPN lists them.
 * [spoilerSafe] (from the Catch up row, #58): the score stays behind Reveal whatever the Scores setting.
 */
data class SportsLiveGameRequest(
    val event: ScoreSnapshot,
    val replay: SportsLiveItem? = null,
    val revealed: Boolean = false,
    val homeFirst: Boolean = false,
    /** The card the page was opened from (Game stats); an upcoming one offers Remind me (#44). */
    val card: SportsLiveItem? = null,
    val spoilerSafe: Boolean = false,
    /** Opened on a live Live now game: "Watch with another game" pairs it when pressed (#57); null: not offered. */
    val watchWith: (() -> SportsLiveDualSession?)? = null,
    /** The section the page opens on when it has one (a Just ended panel row's "Box score ›", #145); null: the first. */
    val section: GameSection? = null,
    /** Told when the page comes on screen and leaves it (opened from Results: its time is not time away, live.20). */
    val onShown: ((Boolean) -> Unit)? = null
)

/**
 * The Game page of a replay's game (#151: Replays' Game stats, a replay in the player): spoiler-free, its
 * result behind Reveal result whatever the Scores setting.
 */
internal fun sportsLiveReplayGameRequest(event: ScoreSnapshot, replay: SportsLiveItem?): SportsLiveGameRequest =
    SportsLiveGameRequest(event = event, replay = replay, revealed = false, spoilerSafe = true)

/** Whether [item]'s card names [event]'s home team first (N10); away first when that is not known. */
internal fun sportsLiveGameHomeFirst(item: SportsLiveItem, event: ScoreSnapshot): Boolean =
    ScoreChips.orderedSides(item)?.let { (first, second) -> ScoreMatcher.firstIsHome(first, second, event) } == true

/**
 * Opens the Game page on the request just put in [SportsLiveGameRequests], from the player (its live games
 * panel's Game stats, #140); provided around the player by the nav host, null elsewhere (not offered).
 */
val LocalOpenSportsLiveGame = androidx.compose.runtime.staticCompositionLocalOf<(() -> Unit)?> { null }

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
    /** The summary shown: one not final is at least the score delay old. */
    val summary: GameSummary? = null,
    val status: GameLoadStatus = GameLoadStatus.LOADING,
    val mode: ScoresMode = ScoresMode.SHOW,
    val revealed: Boolean = false,
    val selected: GameSection? = null,
    val replay: SportsLiveItem? = null,
    val awayFollowed: Boolean = false,
    val homeFollowed: Boolean = false,
    /** The header and team stats name the home team first, as the card the page was opened from (N10). */
    val homeFirst: Boolean = false,
    /** The card the page was opened from, when any. */
    val card: SportsLiveItem? = null,
    /** Opened from the Catch up row (#58): the score shows only once revealed, even under Show. */
    val spoilerSafe: Boolean = false,
    /** "Watch with another game" shows (#57): opened on a live Live now game, while it is live. */
    val offersWatchWith: Boolean = false,
    /** Opened on a chosen section (a panel row's "Box score ›", #145): its tab takes the first focus. */
    val openedOnSection: Boolean = false,
    /** Spoiler-protected (#151): nothing shows until Reveal result, which ends the protection. */
    val spoilerProtected: Boolean = false,
    /**
     * The Scores setting and the spoiler protection have been read. Before that nothing is known to be safe to
     * show, so the score stays hidden and the page has no actions yet (live.19 F18: a protected game opened from
     * Results first drew as a plain final, with its score and only More…).
     */
    val settled: Boolean = true
) {
    /** Score, records and stats show: Scores Show (unless [spoilerSafe]), or revealed; never while [spoilerProtected]. */
    val showsScore: Boolean
        get() = SpoilerPolicy.visibility(
            mode.takeIf { settled }, revealed, SpoilerContext(SpoilerSurface.GAME, spoilerSafe = spoilerSafe, spoilerProtected = spoilerProtected)
        ).showsScore

    /**
     * The sections with something to show. While the score is hidden only an upcoming game's Preview (#171), which is
     * spoiler-neutral; the rest wait for Reveal.
     */
    val sections: List<GameSection>
        get() = if (showsScore) summary?.sections.orEmpty() else summary?.sections.orEmpty().filter { it == GameSection.PREVIEW }

    val section: GameSection? get() = selected?.takeIf { it in sections } ?: sections.firstOrNull()
}

/**
 * The Game page (#18): one ESPN summary, loaded through [GameSummaryRepository] (finals cached for good,
 * a live game at most 30 s old) and refreshed every [REFRESH_MS] only while the page is visible and the
 * game is live. Scores Off: no request until Reveal. Every summary not final (and a final that follows
 * one) shows only once the score delay has passed. Follow ‹team› writes the profile's My Teams list (#54).
 */
@HiltViewModel
class SportsLiveGameViewModel internal constructor(
    private val request: SportsLiveGameRequest?,
    private val loadSummary: suspend (EspnLeague, String) -> GameSummaryLoad,
    settings: Flow<ScoreSettings>,
    private val followedTeams: Flow<List<FollowedTeam>>,
    private val follow: suspend (FollowedTeam) -> Unit,
    private val unfollow: suspend (FollowedTeam) -> Unit,
    private val now: () -> Long,
    /** Highlight clips that failed to play this session (#61): hidden. */
    failedHighlights: Flow<Set<String>> = SportsLiveHighlights.failedClipIds,
    /** Spoiler protection (#151) of the active profile. */
    protection: Flow<SpoilerProtection> = flowOf(SpoilerProtection()),
    /** Reveal result on a protected game: its protection ends. */
    private val endProtection: suspend (ScoreSnapshot) -> Unit = {},
    /** Watch later (no spoilers) from the page's More… (F22): the game is protected from now on. */
    private val saveForLater: suspend (ScoreSnapshot) -> Unit = {},
    /** Watch replay pressed: played to its end, it ends the game's protection. */
    private val replayStarted: suspend (SportsLiveItem, ScoreSnapshot) -> Unit = { _, _ -> },
    /** A college league's AP ranks by team id (BUG18), for every standings row. */
    private val loadRanks: suspend (EspnLeague) -> Map<String, Int> = { emptyMap() },
    /** The summary read under Scores Off after Reveal (live.20): no feed-health entry, no disk copy. */
    private val loadSummaryScoresOff: suspend (EspnLeague, String) -> GameSummaryLoad = loadSummary,
    /** Saves a report (#172) asked for by its trigger: [SportsLiveReporter.report]. */
    private val report: (String) -> Unit = {}
) : ViewModel() {

    @Inject
    constructor(
        summaries: GameSummaryRepository,
        prefs: SportsLivePreferencesDataStore,
        protection: SpoilerProtectionStore,
        rankings: CollegeRankingsRepository,
        reporter: SportsLiveReporter
    ) : this(
        request = SportsLiveGameRequests.take(),
        // Parsing a summary (hundreds of KB of JSON) stays off the main thread (#107).
        loadSummary = { league, eventId -> withContext(Dispatchers.Default) { summaries.load(league, eventId) } },
        settings = prefs.scoreSettings,
        followedTeams = prefs.followedTeams,
        follow = prefs::follow,
        unfollow = prefs::unfollow,
        now = System::currentTimeMillis,
        protection = protection.protection,
        endProtection = protection::reveal,
        saveForLater = protection::saveForLater,
        replayStarted = { replay, event -> protection.onReplayStarted(listOf(replay) + replay.alternates, event) },
        loadRanks = rankings::ranks,
        loadSummaryScoresOff = { league, eventId ->
            withContext(Dispatchers.Default) { summaries.load(league, eventId, scoresOff = true) }
        },
        report = reporter::report
    )

    private val ranks = MutableStateFlow<Map<String, Int>>(emptyMap())
    private var ranksAsked = false

    /** Every ranked college team's AP rank by ESPN team id (BUG18): asked for once the Standings tab shows. */
    val standingRanks: StateFlow<Map<String, Int>> = ranks.asStateFlow()

    /** The Standings tab is showing: a college league's poll is asked for once (one cached request a day). */
    fun onStandingsShown() {
        val league = page.value.summary?.league ?: return
        if (ranksAsked || !RankDisplay.isCollege(league)) return
        ranksAsked = true
        viewModelScope.launch { ranks.value = loadRanks(league) }
    }

    private data class Page(
        val summary: GameSummary? = null,
        val status: GameLoadStatus = GameLoadStatus.LOADING,
        /** Null until the Scores setting has been read. */
        val mode: ScoresMode? = null,
        val revealed: Boolean = false,
        val selected: GameSection? = null
    )

    private val page = MutableStateFlow(Page(revealed = request?.revealed == true, selected = request?.section))
    private val visible = MutableStateFlow(false)
    private val revealRequested = MutableStateFlow(request?.revealed == true)

    val uiState: StateFlow<SportsLiveGameUiState> = combine(page, followedTeams, failedHighlights, protection) { page, followed, failed, protection ->
        val event = request?.event
        SportsLiveGameUiState(
            event = event,
            // A clip that failed is gone; with none left the Highlights tab hides.
            summary = page.summary?.let { summary ->
                val kept = summary.highlights.filterNot { it.id in failed }
                if (kept.size == summary.highlights.size) summary else summary.copy(highlights = kept)
            },
            status = page.status,
            mode = page.mode ?: ScoresMode.SHOW,
            revealed = page.revealed,
            selected = page.selected,
            replay = request?.replay,
            awayFollowed = event != null && TeamFollows.followersOf(followed, event, event.away).isNotEmpty(),
            homeFollowed = event != null && TeamFollows.followersOf(followed, event, event.home).isNotEmpty(),
            homeFirst = request?.homeFirst == true,
            card = request?.card,
            spoilerSafe = request?.spoilerSafe == true,
            // Live games only: gone once the page's summary says the game is over.
            offersWatchWith = request?.watchWith != null && (page.summary?.state ?: event?.state) == ScoreState.IN,
            openedOnSection = request?.section != null,
            spoilerProtected = event != null && protection.protects(event),
            settled = page.mode != null
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        SportsLiveGameUiState(
            event = request?.event,
            replay = request?.replay,
            revealed = request?.revealed == true,
            homeFirst = request?.homeFirst == true,
            card = request?.card,
            spoilerSafe = request?.spoilerSafe == true,
            offersWatchWith = request?.watchWith != null && request.event.state == ScoreState.IN,
            selected = request?.section,
            openedOnSection = request?.section != null,
            // As stamped where the page was opened, until the protection is read.
            spoilerProtected = request?.event?.spoilerProtected == true,
            settled = false
        )
    )

    /** "Watch with another game" pressed (#57): the pair is picked now, from the section's games; null: none. */
    fun watchWithSession(): SportsLiveDualSession? = request?.watchWith?.invoke()

    // Summaries waiting out the score delay; kept across visibility changes.
    private val buffer = DelayedValues<GameSummary>()

    // A summary not yet final was fetched: the game may have ended while the page was open, so its
    // final is held for the delay too (a final that was already final when opened shows at once).
    private var sawUnfinished = false

    init {
        val event = request?.event
        if (event != null) {
            viewModelScope.launch {
                revealGatedLoad(
                    settings = settings,
                    revealed = revealRequested,
                    visible = visible,
                    mode = { it.mode },
                    context = SpoilerContext(SpoilerSurface.GAME, spoilerSafe = request?.spoilerSafe == true),
                    onChange = { current, revealed, mayLoad ->
                        page.update { it.copy(mode = current.mode, revealed = revealed) }
                        if (!mayLoad) {
                            page.update { it.copy(status = GameLoadStatus.WAITING_REVEAL) }
                        } else if (page.value.status == GameLoadStatus.WAITING_REVEAL) {
                            page.update { it.copy(status = GameLoadStatus.LOADING) }
                        }
                    }
                ) { current -> refreshWhileVisible(event, current.effectiveDelayMs, scoresOff = current.mode == ScoresMode.OFF) }
            }
        }
    }

    /**
     * Loads, then (while the game is live) reloads every [REFRESH_MS]; shows each summary once it is due.
     * The delay is decided per summary from its own state: every summary not final is held [delayMs]
     * (a card opened right after kick-off may still say "scheduled" while ESPN says "in play").
     */
    private suspend fun refreshWhileVisible(event: ScoreSnapshot, delayMs: Long, scoresOff: Boolean) {
        val fetch: suspend (EspnLeague, String) -> GameSummaryLoad = if (scoresOff) loadSummaryScoresOff else loadSummary
        val values = buffer
        var nextFetchAt = now()
        while (true) {
            if (now() >= nextFetchAt) {
                val live = when (val load = fetch(event.league, event.eventId)) {
                    is GameSummaryLoad.Loaded -> {
                        val hold = !load.final || sawUnfinished
                        if (!load.final) sawUnfinished = true
                        values.push(load.summary, now(), if (hold) delayMs else 0L)
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
        request?.onShown?.invoke(isVisible)
    }

    /** Reveal: score and stats show; under Scores Off this is when the first request is made. */
    fun reveal() {
        revealRequested.value = true
        val event = request?.event ?: return
        if (uiState.value.spoilerProtected) viewModelScope.launch { endProtection(event) }
    }

    /** Watch later (no spoilers) from More… (F22): score, records and stats hide until Reveal result. */
    fun watchLater() {
        val event = request?.event ?: return
        viewModelScope.launch { saveForLater(event) }
    }

    /** Watch replay pressed on this page (#151). */
    fun onReplayStarted(replay: SportsLiveItem) {
        val event = request?.event ?: return
        viewModelScope.launch { replayStarted(replay, event) }
    }

    fun selectSection(section: GameSection) {
        page.update { it.copy(selected = section) }
    }

    /** More… → Report a problem on the Game page (TV QA: remotes with no Play/Pause or Info to hold). */
    fun reportProblem() {
        report("Hold OK › More… › Report a problem (game page)")
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
