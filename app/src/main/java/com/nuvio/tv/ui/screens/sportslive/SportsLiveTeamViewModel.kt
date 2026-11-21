package com.nuvio.tv.ui.screens.sportslive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.sportslive.SportsLiveItemBucketer
import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.ScoreCard
import com.nuvio.tv.core.sportslive.scores.ScoreChips
import com.nuvio.tv.core.sportslive.scores.ScoreMatcher
import com.nuvio.tv.core.sportslive.scores.ScoreSettings
import com.nuvio.tv.core.sportslive.scores.SpoilerProtection
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.core.sportslive.scores.TeamFollows
import com.nuvio.tv.core.sportslive.scores.TeamPageData
import com.nuvio.tv.core.sportslive.scores.TeamPageLoad
import com.nuvio.tv.core.sportslive.scores.TeamPageRepository
import com.nuvio.tv.core.sportslive.scores.TeamPageSpoilers
import com.nuvio.tv.core.sportslive.scores.TeamPages
import com.nuvio.tv.core.sportslive.scores.TeamRef
import com.nuvio.tv.data.local.SportsLivePreferencesDataStore
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.domain.repository.SportsLiveRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** What opens a Team page (#62): the team and the game it was opened from. */
data class SportsLiveTeamRequest(val team: TeamRef)

/** Hands the request to the Team page's ViewModel, once (like [SportsLiveGameRequests]). */
internal object SportsLiveTeamRequests {
    @Volatile
    private var request: SportsLiveTeamRequest? = null

    fun open(request: SportsLiveTeamRequest) {
        this.request = request
    }

    fun take(): SportsLiveTeamRequest? = request.also { request = null }
}

data class SportsLiveTeamUiState(
    /** The team as opened (null: nothing to show, the page closes). */
    val team: TeamRef? = null,
    val data: TeamPageData? = null,
    val status: GameLoadStatus = GameLoadStatus.LOADING,
    /** ESPN could not be reached: the finals saved last time, nothing upcoming. */
    val stale: Boolean = false,
    val mode: ScoresMode = ScoresMode.SHOW,
    val revealed: Boolean = false,
    val followed: Boolean = false,
    /** The strict replay match of each of the last results, by event id (the Replay pill). */
    val replays: Map<String, SportsLiveItem> = emptyMap(),
    /** The card of each upcoming game (strict match), by event id: Watch and Remind me. */
    val cards: Map<String, SportsLiveItem> = emptyMap(),
    /** Spoiler protection (#151): a protected game's result row stays hidden. */
    val protection: SpoilerProtection = SpoilerProtection()
) {
    /** Record, standing, upcoming games and leaders (decision 5: shown under Hide until focused). */
    val showsRecord: Boolean get() = TeamPageSpoilers.showsRecord(mode, revealed)

    /** The last results' W/L and scores: Show, or once revealed. */
    val showsResults: Boolean get() = TeamPageSpoilers.showsResults(mode, revealed)

    /** One of the last results is spoiler-protected (#151): whatever sums it up would give it away. */
    val hasProtectedResult: Boolean get() = data?.recent.orEmpty().any { protection.protects(it) }

    /**
     * The header's record · standing · streak line (G5): as [showsRecord], never while one of the last results is
     * protected ("L1" would tell yesterday's loss), whatever the page's Reveal, as the result row itself.
     */
    val showsRecordLine: Boolean get() = showsRecord && !hasProtectedResult
}

/**
 * The Team page (#62): one team's record, standing, last results, next games and leaders, through
 * [TeamPageRepository] (cached 10 min; the finals also on disk). It loads when the page is on screen and
 * never polls. Scores Off: no request until Reveal. Follow writes the profile's My Teams list, as the
 * Game page does; the replays and the cards of the next games come from the section's latest load.
 */
@HiltViewModel
class SportsLiveTeamViewModel internal constructor(
    private val request: SportsLiveTeamRequest?,
    private val loadTeam: suspend (EspnLeague, String) -> TeamPageLoad,
    settings: Flow<ScoreSettings>,
    private val followedTeams: Flow<List<FollowedTeam>>,
    private val follow: suspend (FollowedTeam) -> Unit,
    private val unfollow: suspend (FollowedTeam) -> Unit,
    /** The section's items (claimed catalogs) from its latest load. */
    items: Flow<List<SportsLiveItem>>,
    private val now: () -> Long,
    /** Where the replay and card matching runs: off the main thread in the app (#107). */
    matchContext: CoroutineContext = EmptyCoroutineContext,
    protection: Flow<SpoilerProtection> = flowOf(SpoilerProtection())
) : ViewModel() {

    @Inject
    constructor(
        repository: TeamPageRepository,
        prefs: SportsLivePreferencesDataStore,
        sportsLive: SportsLiveRepository
    ) : this(
        request = SportsLiveTeamRequests.take(),
        // The team and schedule JSON parse off the main thread (#107).
        loadTeam = { league, teamId -> withContext(Dispatchers.Default) { repository.load(league, teamId) } },
        settings = prefs.scoreSettings,
        followedTeams = prefs.followedTeams,
        follow = prefs::follow,
        unfollow = prefs::unfollow,
        items = combine(sportsLive.latest, sportsLive.claimedCatalogs) { load, claimed ->
            val keys = claimed.mapTo(HashSet()) { it.key }
            load?.items.orEmpty().filter { it.catalogKey in keys }
        },
        now = System::currentTimeMillis,
        matchContext = Dispatchers.Default,
        protection = prefs.spoilerProtection
    )

    private data class Page(
        val data: TeamPageData? = null,
        val status: GameLoadStatus = GameLoadStatus.LOADING,
        val stale: Boolean = false,
        val mode: ScoresMode = ScoresMode.SHOW,
        val revealed: Boolean = false
    )

    private val page = MutableStateFlow(Page())
    private val visible = MutableStateFlow(false)
    private val revealRequested = MutableStateFlow(false)

    val uiState: StateFlow<SportsLiveTeamUiState> = combine(page, followedTeams, items, protection) { page, followed, items, protection ->
        val team = request?.team
        val isFollowed = team != null && TeamFollows.followersOf(followed, team.event, team.team).isNotEmpty()
        val data = page.data
        val nowMs = now()
        SportsLiveTeamUiState(
            team = team,
            data = data,
            status = page.status,
            stale = page.stale,
            mode = page.mode,
            revealed = page.revealed,
            followed = isFollowed,
            replays = data?.let { TeamPages.replaysFor(it.recent, SportsLiveItemBucketer.bucketTab(items, SportsLiveTab.REPLAYS, nowMs), nowMs) }.orEmpty(),
            cards = data?.let { teamPageCards(it.upcoming, items, isFollowed, nowMs) }.orEmpty(),
            protection = protection
        )
    }.flowOn(matchContext).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SportsLiveTeamUiState(team = request?.team))

    init {
        val team = request?.team
        if (team != null) {
            viewModelScope.launch {
                combine(settings, revealRequested, visible) { settings, revealed, visible -> Triple(settings.mode, revealed, visible) }
                    .distinctUntilChanged()
                    .collectLatest { (mode, revealed, isVisible) ->
                        page.update { it.copy(mode = mode, revealed = revealed) }
                        if (!TeamPageSpoilers.mayLoad(mode, revealed)) {
                            page.update { it.copy(status = GameLoadStatus.WAITING_REVEAL) }
                            return@collectLatest
                        }
                        if (page.value.status == GameLoadStatus.WAITING_REVEAL) page.update { it.copy(status = GameLoadStatus.LOADING) }
                        // Loaded once per open; the repository serves a return from a Game page.
                        if (!isVisible || page.value.status == GameLoadStatus.READY) return@collectLatest
                        when (val load = loadTeam(team.league, team.teamId)) {
                            is TeamPageLoad.Loaded -> page.update { it.copy(data = load.data, stale = load.stale, status = GameLoadStatus.READY) }
                            TeamPageLoad.Failed -> page.update { it.copy(status = GameLoadStatus.FAILED) }
                        }
                    }
            }
        }
    }

    /** The page is on screen (started): only then does it load. */
    fun setVisible(isVisible: Boolean) {
        visible.value = isVisible
    }

    /** Reveal: Off → the page loads and shows; Hide until focused → the last results show. */
    fun reveal() {
        revealRequested.value = true
    }

    /** Follow the team, or unfollow every followed team that is it. */
    fun toggleFollow() {
        val team = request?.team ?: return
        viewModelScope.launch {
            val matching = TeamFollows.followersOf(followedTeams.first(), team.event, team.team)
            if (matching.isEmpty()) {
                TeamFollows.teamToFollow(team.event, team.team)?.let { follow(it) }
            } else {
                matching.forEach { unfollow(it) }
            }
        }
    }
}

/**
 * The card each of [upcoming] plays and reminds from: a live or upcoming card of the section whose
 * BOTH teams match strongly ([ScoreMatcher.BOTH_STRONG]), in its time window, with its sides on the game
 * one way round. A wrong game is worse than none, so a tie gives none. [followed]: the page's team is
 * followed, so its games get the automatic reminder (#44), as on the section's cards.
 */
internal fun teamPageCards(
    upcoming: List<ScoreSnapshot>,
    items: List<SportsLiveItem>,
    followed: Boolean,
    nowMs: Long
): Map<String, SportsLiveItem> {
    if (upcoming.isEmpty() || items.isEmpty()) return emptyMap()
    val matcher = ScoreMatcher(minScore = ScoreMatcher.BOTH_STRONG)
    val cards = SportsLiveItemBucketer.bucketTab(items, SportsLiveTab.LIVE_NOW, nowMs) +
        SportsLiveItemBucketer.bucketTab(items, SportsLiveTab.UPCOMING, nowMs)
    val found = LinkedHashMap<String, SportsLiveItem>()
    for (card in cards) {
        if (card.isChannel) continue
        val scoreCard = ScoreCard.from(card) ?: continue
        val event = matcher.match(scoreCard, upcoming, nowMs) ?: continue
        if (ScoreChips.matchedEvent(card, event) == null) continue
        found.putIfAbsent(event.eventId, if (followed) card.copy(followed = true) else card)
    }
    return found
}
