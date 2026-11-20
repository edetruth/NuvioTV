package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.scores.TeamStanding
import com.nuvio.tv.core.sportslive.scores.StandingsRepository
import com.nuvio.tv.core.sportslive.scores.EspnLeague
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.sportslive.SportsLiveDataSaver
import com.nuvio.tv.core.sportslive.EndedGame
import com.nuvio.tv.core.sportslive.EndedInputs
import com.nuvio.tv.core.sportslive.EndedSignal
import com.nuvio.tv.core.sportslive.SportsLiveEndedGames
import com.nuvio.tv.core.sportslive.LinkCheckRequest
import com.nuvio.tv.core.sportslive.SportsLiveHiddenTitles
import com.nuvio.tv.core.sportslive.SportsLiveLinkChecker
import com.nuvio.tv.core.sportslive.SportsLiveLinkVerdicts
import com.nuvio.tv.core.sportslive.SportsLiveManifestVerdict
import com.nuvio.tv.core.sportslive.SportsLiveItemBucketer
import com.nuvio.tv.core.sportslive.SportsLiveLiveDuration
import com.nuvio.tv.core.sportslive.SportsLiveSportKind
import com.nuvio.tv.core.sportslive.SportsLiveMySportsScope
import com.nuvio.tv.core.sportslive.scores.FollowTarget
import com.nuvio.tv.core.sportslive.scores.MySports
import com.nuvio.tv.core.sportslive.scores.ExcitementHistory
import com.nuvio.tv.core.sportslive.scores.LiveScoresRepository
import com.nuvio.tv.core.sportslive.scores.ScoreChips
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.core.sportslive.scores.TeamFollows
import com.nuvio.tv.core.sportslive.scores.TeamLogoBook
import com.nuvio.tv.core.sportslive.scores.TeamDirectory
import com.nuvio.tv.core.sportslive.SportsLiveSportInference
import com.nuvio.tv.core.sportslive.SportsLiveDupes
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.sportslive.identify.SportsLiveTeamIdentity
import com.nuvio.tv.core.sportslive.identify.TeamIdentifier
import com.nuvio.tv.core.sportslive.identify.TeamIdentityBook
import com.nuvio.tv.core.sportslive.SportsLiveChannelRegions
import com.nuvio.tv.core.sportslive.scores.WhyTag
import com.nuvio.tv.data.local.SportsLivePreferencesDataStore
import com.nuvio.tv.data.repository.SportsLiveManifestFetcher
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.domain.repository.SportsLiveFailure
import com.nuvio.tv.domain.repository.SportsLiveRepository
import com.nuvio.tv.ui.screens.sportslive.preview.SportsLivePreviewController
import com.nuvio.tv.ui.screens.sportslive.preview.SportsLivePreviewLookup
import com.nuvio.tv.ui.screens.sportslive.preview.SportsLivePreviewState
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveMiniPlayers
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveMiniState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SportsLiveViewModel internal constructor(
    private val repo: SportsLiveRepository,
    /** The hover preview's lookup for an item: a stream a light player can play as it is, or why not. */
    previewResolve: suspend (SportsLiveItem) -> SportsLivePreviewLookup = { SportsLivePreviewLookup.NotPreviewable },
    hoverPreviewEnabled: Flow<Boolean> = flowOf(false),
    /** The mini player plays: it takes priority, so no preview starts meanwhile. */
    miniPlayerActive: Flow<Boolean> = flowOf(false),
    /** Data saver: when on, the on-focus ended-probe is skipped (hover preview is already gated off). */
    dataSaver: Flow<Boolean> = flowOf(false),
    /** Drops [item]'s cached resolve after its preview failed, so the next play/preview re-resolves. */
    private val onResolveFailed: (SportsLiveItem) -> Unit = {},
    /** The delayed live scores and the estimate gate, for ended games (#60). */
    endedInputs: Flow<EndedInputs> = flowOf(EndedInputs()),
    private val endedGames: SportsLiveEndedGames = SportsLiveEndedGames(),
    /** Ticks while the list is watched, so a game's grace or estimate running out moves its card. */
    clock: Flow<Unit> = flowOf(Unit),
    /** Removes the retired View and Sort choices (#132) from the active profile's settings; harmless when gone. */
    private val dropRetiredPrefs: suspend () -> Unit = {},
    /** The Scores setting (null until read): only Show draws the excitement badges (#43). */
    scoresMode: Flow<ScoresMode?> = flowOf(null),
    /** Looks once at the resolved link of a game past its estimated end (#60 phase 2); null: no checks. */
    private val linkChecker: SportsLiveLinkChecker? = null,
    /** [item]'s check on its already-resolved link, or null (nothing resolved, not an HLS link). */
    private val linkCheckOf: (SportsLiveItem) -> LinkCheckRequest? = { null },
    /** Learned logos' revision (#66): the drawn cards are built again when it moves. */
    logoRevision: Flow<Int> = flowOf(0),
    /** A two-team card with no league, as its league's card when its teams name exactly one (#132 P4). */
    private val inferSport: (SportsLiveItem) -> SportsLiveItem = { it },
    /** Offers the tab's games of unknown sport for a background team lookup (#168); called only while on screen. */
    private val identify: (List<SportsLiveItem>) -> Unit = {},
    /**
     * Emits when the team directory or a #168 identity changes (live.18): the current load is bucketed and merged
     * again, [REBUCKET_DEBOUNCE_MS] after the last change, on the compute dispatcher.
     */
    directoryChanges: Flow<Unit> = emptyFlow(),
    /** My sports (#146): Live now, Upcoming and Replays list only its games, unless All sports is picked. */
    mySports: Flow<MySports> = flowOf(MySports.DEFAULT),
    /** Titles hidden from Live Now (#134): left off Live Now's list. */
    hiddenTitles: Flow<Set<String>> = flowOf(emptySet()),
    /** Hides a title from Live Now for the profile (#134). */
    private val hideTitle: suspend (String) -> Unit = {},
    /** The catalog refresh cadence's clock (#71), monotonic; tests pass virtual time. */
    private val refreshClock: () -> Long = { System.nanoTime() / 1_000_000L },
    /** Standings by league (#137), for the big-game score: what is known, cached on the device. */
    standings: Flow<Map<EspnLeague, Map<String, TeamStanding>>> = flowOf(emptyMap()),
    /** Asks for [EspnLeague]s' standings (fetched at most every few hours; the precision rule is applied here). */
    private val ensureStandings: (Collection<EspnLeague>) -> Unit = {},
    /** True the first time the profile opens Upcoming (live.20's keys tip), marking it seen. */
    private val claimUpcomingTip: suspend () -> Boolean = { false },
    // Where the list is bucketed and pinned, and the boards built; tests pass their dispatcher, so it runs in virtual time.
    private val computeDispatcher: CoroutineDispatcher
) : ViewModel() {

    @Inject
    constructor(
        repo: SportsLiveRepository,
        resolver: SportsLiveZapResolver,
        prefs: SportsLivePreferencesDataStore,
        liveScores: LiveScoresRepository,
        resolveCache: SportsLiveResolveCache,
        manifestFetcher: SportsLiveManifestFetcher,
        identifier: TeamIdentifier,
        standingsRepository: StandingsRepository
    ) : this(
        repo = repo,
        // The zap's pick, which joins the stream search the ended-games probe started on settle; a
        // cached resolve, else at most one resolve per game per window, never during a cool-down (C1).
        previewResolve = resolver::previewLookup,
        hoverPreviewEnabled = combine(prefs.hoverPreview, prefs.dataSaver) { hover, saver ->
            SportsLiveDataSaver.hoverPreviewAllowed(hover, saver)
        },
        miniPlayerActive = SportsLiveMiniPlayers.controller.state.map { it is SportsLiveMiniState.Playing },
        dataSaver = prefs.dataSaver,
        onResolveFailed = resolver::invalidate,
        endedInputs = combine(liveScores.scores, liveScores.mode, liveScores.unmatched) { scores, mode, unmatched ->
            EndedInputs(scores, SportsLiveEndedGames.estimateGate(mode == ScoresMode.OFF, unmatched))
        },
        endedGames = SportsLiveEndedGames.shared,
        clock = flow {
            while (true) {
                emit(Unit)
                delay(ENDED_TICK_MS)
            }
        },
        // V2 (#132): one view per tab, in start order; the View menu and Sort are gone with their stored choices.
        dropRetiredPrefs = prefs::dropRetiredKeys,
        claimUpcomingTip = prefs::claimUpcomingKeysTip,
        scoresMode = liveScores.mode,
        linkChecker = SportsLiveLinkChecker(fetch = manifestFetcher::fetch, verdicts = SportsLiveLinkVerdicts.shared),
        // Only a fresh resolve (the resolve cache's, at most five minutes old): never an older link that
        // once played, and never a resolve just to test.
        linkCheckOf = { item ->
            resolveCache.getFresh(item.streamKey)?.let { (stream, resolvedAtMs) ->
                SportsLiveManifestVerdict.requestFor(item, stream, resolvedAtMs)
            }
        },
        // A game's teams identified in the background (#168) rebuild the lists as a learned logo does.
        logoRevision = combine(TeamLogoBook.shared.revision, TeamIdentityBook.shared.revision) { logos, identities -> logos * 31 + identities },
        inferSport = { item -> SportsLiveTeamIdentity.identified(SportsLiveSportInference.infer(item, TeamDirectory.shared)) },
        identify = identifier::offer,
        directoryChanges = merge(TeamDirectory.shared.revision.drop(1), TeamIdentityBook.shared.revision.drop(1)).map { },
        mySports = prefs.mySports,
        hiddenTitles = prefs.hiddenTitles,
        hideTitle = prefs::hideTitle,
        standings = standingsRepository.books,
        ensureStandings = standingsRepository::ensure,
        computeDispatcher = Dispatchers.Default
    )

    private data class Selection(
        val tab: SportsLiveTab = SportsLiveTab.LIVE_NOW,
        val filter: String? = null,
        /** The last chip picked on each tab (#79), for this session: a tab opens on it while it has games. */
        val filterByTab: Map<SportsLiveTab, String> = emptyMap(),
        val itemId: String? = null,
        /** Live now's list holds focus (F6): the rows a load dropped stay until it lets go. */
        val browsing: Boolean = false,
        val isRefreshing: Boolean = false,
        val failures: List<SportsLiveFailure> = emptyList(),
        val lastRefreshedMs: Long? = null,
        /** Each source's run of refreshes (#170): the dot on Refresh. */
        val sources: SportsLiveSources = SportsLiveSources()
    )

    /** A load sorted into tabs; recomputed only when the load or the claimed catalogs change. */
    private data class Bucketed(
        val hasClaimed: Boolean,
        val hasLoad: Boolean,
        val tabs: Map<SportsLiveTab, List<SportsLiveItem>>
    )

    @VisibleForTesting
    internal var nowMs: () -> Long = { System.currentTimeMillis() }

    @VisibleForTesting
    internal var bucket: (List<SportsLiveItem>, Long, List<String>) -> Map<SportsLiveTab, List<SportsLiveItem>> =
        SportsLiveItemBucketer::bucket

    private val selectionState = MutableStateFlow(Selection())

    // The excitement badges (#43): what changed lately in each game (a lead change, a score).
    private val excitementHistory = ExcitementHistory()
    private var autoRefreshJob: Job? = null

    // #71: when each kind of catalog was last fetched (refreshClock), so a tab refreshes only its own
    // catalogs, on its own cadence, and never when the last fetch is fresher than that.
    private val fetchedAtMs = HashMap<SportsLiveKind, Long>()
    private var fullLoadJob: Job? = null

    // Ambient mode, the screensaver or a dream is up: no preview and no catalog refresh meanwhile.
    private var ambientShowing = false

    // Live now as last built, so the focused card is kept when a load drops it (#145).
    private var shownLiveNow: List<SportsLiveItem> = emptyList()

    // The titles hidden from Live Now (#134), as last read.
    @Volatile
    private var hiddenNow: Set<String> = emptySet()


    // The section is on screen (resumed): only then are links checked (#60 phase 2).
    @Volatile
    private var screenVisible = false
    private var focusProbeJob: Job? = null

    // Latest Data saver value; the on-focus probe is skipped while it is on.
    @Volatile
    private var dataSaverActive = false

    // Bucketing a large channel list is the expensive step, so selection changes do not repeat it,
    // and it runs off the main thread.
    // A team list landing or a team identified (live.18) buckets the same load again, so copies that resolve to
    // the same teams fold without waiting for the next catalog refresh; Live now's focused card stays put
    // ([sportsLiveKeepFocused]).
    @OptIn(FlowPreview::class)
    private val rebucket: Flow<Unit> = directoryChanges.debounce(REBUCKET_DEBOUNCE_MS).onStart { emit(Unit) }

    private val bucketed = combine(repo.claimedCatalogs, repo.latest, rebucket) { claimed, latest, _ ->
        // Only items of catalogs claimed right now: the cache may still hold another profile's load.
        val claimedKeys = claimed.mapTo(HashSet()) { it.key }
        val items = latest?.items.orEmpty().filter { it.catalogKey in claimedKeys }
        // Claimed catalogs come in the user's addon order, which picks a merged game's primary.
        val addonOrder = claimed.map { it.addon.id }.distinct()
        Bucketed(hasClaimed = claimed.isNotEmpty(), hasLoad = latest != null, tabs = bucket(items, nowMs(), addonOrder))
    }.flowOn(computeDispatcher)

    // Followed teams' games first in every tab but Channels, where they are only marked (for the
    // card star); a follow change does not re-bucket.
    // Live now's, Upcoming's and Replays' unrouted two-team cards take the league their teams name (#132 P4),
    // again whenever the team directory changes (it bumps the logo revision). Channels are never inferred.
    // Titles hidden from Live Now (#134) are left off its list only.
    private val pinned = combine(bucketed, repo.followedTeams, logoRevision, hiddenTitles) { bucketed, followed, _, hidden ->
        hiddenNow = hidden
        bucketed.copy(
            tabs = bucketed.tabs.mapValues { (tab, all) ->
                val listed = if (tab == SportsLiveTab.LIVE_NOW) SportsLiveHiddenTitles.filter(all, hidden) else all
                val items = if (tab in INFERRED_TABS) listed.map(inferSport) else listed
                if (tab == SportsLiveTab.CHANNELS) {
                    SportsLiveItemBucketer.markFollowed(items, followed)
                } else {
                    SportsLiveItemBucketer.pinFollowed(items, followed).also { rows ->
                        // Debug builds: rows that look like one game but did not merge (live.18).
                        if (BuildConfig.IS_DEBUG_BUILD) SportsLiveDupes.log(tab.name, rows, nowMs())
                    }
                }
            }
        )
    }.flowOn(computeDispatcher)

    val uiState: StateFlow<SportsLiveUiState> =
        combine(
            pinned,
            repo.endedKeys,
            combine(selectionState, scoresMode, mySports) { selection, mode, sports -> Triple(selection, mode, sports) },
            combine(endedInputs, linkChecker?.dead ?: flowOf(emptyMap())) { inputs, linkDead -> inputs to linkDead },
            clock
        ) { pinned, endedKeys, (selection, mode, sports), (inputs, linkDead), _ ->
            buildState(pinned, endedKeys, selection, inputs, mode, linkDead, sports)
        }
            // A focus move rebuilds the state: never on the main thread (#143).
            .flowOn(computeDispatcher)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SportsLiveUiState())

    private val boardScores = MutableStateFlow(SportsLiveBoardScores())

    /** The scores, Scores setting and reveals the screen shows: the Scoreboard and drawn cards read them. */
    fun setBoardScores(scores: SportsLiveBoardScores) {
        boardScores.value = scores
        // The big-game score's standings (#137): only leagues in My sports with games on screen (the precision rule).
        if (scores.mode != null && scores.mode != ScoresMode.OFF) {
            val sports = lastMySports
            val leagues = scores.scores.values.asSequence()
                .filter { it.individual == null && (it.state == ScoreState.PRE || it.state == ScoreState.IN) && sports.includes(it) }
                .map(StandingsRepository::leagueOf)
                .filter(StandingsRepository::covers)
                .distinct()
                .toList()
            if (leagues.isNotEmpty()) ensureStandings(leagues)
        }
    }

    /** My sports as the last state build read it (#146), for the standings' precision rule. */
    @Volatile
    private var lastMySports: MySports = MySports.DEFAULT

    /** Builds a board; tests swap it to see where it runs. */
    @VisibleForTesting
    internal var buildBoard: suspend (SportsLiveBoardInputs) -> SportsLiveBoard = { SportsLiveBoard.build(it) }

    /**
     * What the list draws (#71): the selected tab's items in the order shown, with the Scoreboard's rows
     * and the cards' drawn art, built on the compute dispatcher and published ready.
     * Only a change of what it shows builds again: a focus move does not. Null until the first one is built.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val board: StateFlow<SportsLiveBoard?> =
        combine(uiState, boardScores, repo.followedTeams, logoRevision, standings) { state, scores, followed, revision, books ->
            val tab = state.selectedTab
            val live = tab == SportsLiveTab.LIVE_NOW
            SportsLiveBoardInputs(
                tab = tab,
                filter = state.selectedFilter,
                items = state.visibleItems,
                scores = scores,
                ended = if (live) state.justEnded else emptyMap(),
                noStreams = if (live) state.noStreams else emptySet(),
                excitement = if (live) state.whyTags else emptyMap(),
                followed = followed,
                clockMs = state.nowMs / MINUTE_MS * MINUTE_MS,
                logoRevision = revision,
                // Upcoming's big games (#137) read them.
                standings = if (tab == SportsLiveTab.UPCOMING) books else emptyMap()
            )
        }
            .distinctUntilChanged()
            // F21: an update of the same tab and filter lets the board being built finish (the newest follows); another
            // tab or filter cancels it.
            .mapFinishing({ running, next -> running.tab != next.tab || running.filter != next.filter }) { inputs ->
                withContext(computeDispatcher) { buildBoard(inputs) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The active profile's followed teams, for the card menu's Follow / Unfollow labels. */
    val followedTeams: StateFlow<List<FollowedTeam>> =
        repo.followedTeams.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val previewController = SportsLivePreviewController(viewModelScope, previewResolve).apply {
        onTab(selectionState.value.tab)
    }

    /** The details pane's hover preview. */
    val preview: StateFlow<SportsLivePreviewState> = previewController.state

    private val _upcomingTip = MutableStateFlow(false)

    /** Upcoming's one-time keys tip is due (live.20): the screen shows it, then calls [onUpcomingTipShown]. */
    val upcomingTip: StateFlow<Boolean> = _upcomingTip

    fun onUpcomingTipShown() {
        _upcomingTip.value = false
    }

    init {
        fullLoadJob = launchFullLoad(force = false)
        // An active mini player counts as the setting off; once it stops, a fresh settle starts.
        viewModelScope.launch {
            combine(hoverPreviewEnabled, miniPlayerActive) { enabled, mini -> enabled && !mini }
                .collect(previewController::onEnabled)
        }
        viewModelScope.launch { dataSaver.collect { dataSaverActive = it } }
        viewModelScope.launch { runCatching { dropRetiredPrefs() } }
    }

    /**
     * The card the preview may play: the focused card while the list or the details pane holds
     * focus and no dialog, menu or pick mode is open; null otherwise.
     */
    fun setPreviewFocus(item: SportsLiveItem?) {
        previewController.onFocus(item)
    }

    fun onPreviewFirstFrame(item: SportsLiveItem) = previewController.onFirstFrame(item)

    fun onPreviewError(item: SportsLiveItem) {
        // The previewed stream failed to play: drop its cached resolve so it is re-resolved next time.
        onResolveFailed(item)
        previewController.onError(item)
    }

    /** [item]'s preview when it is playing on screen, stopped so the full player can take its stream over. */
    fun takeOverPreview(item: SportsLiveItem): SportsLivePreviewState.Playing? = previewController.takeOver(item)

    /** More… → Follow ‹team› (#134): follows [target]'s ESPN team, or unfollows every followed team that is it. */
    fun toggleFollow(target: FollowTarget) {
        viewModelScope.launch { repo.toggleFollowTarget(target) }
    }

    /** More… → Hide from Live Now (#134): [item]'s title leaves Live Now; Settings → More → Hidden brings it back. */
    fun hideFromLiveNow(item: SportsLiveItem) {
        viewModelScope.launch { hideTitle(SportsLiveHiddenTitles.titleOf(item.meta.name)) }
    }

    fun selectTab(tab: SportsLiveTab) {
        // The tab opens on its last chip (#79) while that chip still has games there, else on All.
        val remembered = selectionState.value.filterByTab[tab]?.takeIf { value ->
            val tabItems = uiState.value.tabs[tab].orEmpty()
            tabItems.any { it.matchesZapFilter(value, tab) }
        }
        val changed = tab != selectionState.value.tab
        // F23: focus coming back to the tab already selected changes nothing (it used to clear the selection, and the
        // stage jumped to the addon's first item).
        if (!changed) return
        selectionState.update { it.copy(tab = tab, filter = remembered, itemId = null, browsing = false) }
        if (tab == SportsLiveTab.UPCOMING) viewModelScope.launch { if (claimUpcomingTip()) _upcomingTip.value = true }
        previewController.onTab(tab)
        // The new tab's catalogs on its own cadence (#71).
        if (changed) restartAutoRefresh()
    }

    /** [value] is a sport, a group on the Channels tab, or [SportsLiveUiState.MY_TEAMS_FILTER]; null means All. */
    fun selectFilter(value: String?) {
        // Another filter lists other cards: the preview stops until a card is focused again.
        if (value != selectionState.value.filter) previewController.onFocus(null)
        selectionState.update {
            val remembered = if (value == null) it.filterByTab - it.tab else it.filterByTab + (it.tab to value)
            it.copy(filter = value, filterByTab = remembered)
        }
    }

    /**
     * The user pressed a key on the section (#79): a selected chip whose games have all gone, kept
     * shown with 0 until now, falls back to All. True when it did.
     */
    fun onUserMoved(): Boolean {
        if (!uiState.value.selectedFilterEmpty) return false
        selectFilter(null)
        return true
    }

    /** Live now's list holds focus ([focused]) or lets it go (F6): rows a load dropped stay put meanwhile. */
    fun setBrowsing(focused: Boolean) {
        if (selectionState.value.browsing != focused) selectionState.update { it.copy(browsing = focused) }
    }

    /**
     * A Live now item the user settles on (selected for [FOCUS_PROBE_DELAY_MS]) has its streams
     * re-probed when the last probe is stale; scrolling past items probes nothing.
     */
    fun selectItem(id: String?) {
        selectionState.update { it.copy(itemId = id) }
        focusProbeJob?.cancel()
        if (id == null || selectionState.value.tab != SportsLiveTab.LIVE_NOW) return
        // Data saver: resolve/probe only on explicit play or zap-settle, never on mere focus.
        if (!SportsLiveDataSaver.onFocusProbeAllowed(dataSaverActive)) return
        focusProbeJob = viewModelScope.launch {
            delay(FOCUS_PROBE_DELAY_MS)
            val selection = selectionState.value
            if (selection.tab != SportsLiveTab.LIVE_NOW || selection.itemId != id) return@launch
            uiState.value.tabs[SportsLiveTab.LIVE_NOW]?.firstOrNull { it.id == id }?.let(repo::reprobeIfStale)
        }
    }

    /**
     * Before [item] is played: the player may zap through this tab, the filter's games first. On Live
     * now the session has every game of the tab, whatever the filter, so the player's panel opens on
     * All (#68); other tabs keep the list shown. With no session to start, any earlier one ends.
     */
    fun startZapSession(item: SportsLiveItem) {
        val state = uiState.value
        val session = SportsLiveZapSession.of(
            items = state.zapItems,
            itemKey = item.streamKey,
            tab = state.selectedTab,
            filter = state.selectedFilter,
            returnToHomeOnBack = false
        )
        if (session != null) SportsLiveZapSessions.start(session) else SportsLiveZapSessions.clear()
    }

    fun refresh(force: Boolean = true) {
        // A manual refresh also forgets ended verdicts: those games show and are re-checked on focus.
        if (force) repo.clearEnded()
        fullLoadJob = launchFullLoad(force)
    }

    /** Every claimed catalog (opening the section, a manual refresh); each kind counts as fetched now. */
    private fun launchFullLoad(force: Boolean): Job {
        // Set before launching so the first frame already shows loading rather than an empty section.
        selectionState.update { it.copy(isRefreshing = true) }
        return viewModelScope.launch {
            SportsLiveKind.entries.forEach { fetchedAtMs[it] = refreshClock() }
            load(force)
        }
    }

    /**
     * The section is on screen (resumed) or not. While it is, and no ambient mode or screensaver is up, the
     * selected tab's catalogs refresh on that tab's cadence ([SportsLiveRefreshPlan], #71): only its own
     * catalogs, and never while the last fetch is fresher than its interval. Hidden (the player, another
     * screen, the background), nothing refreshes.
     */
    fun onScreenVisible(visible: Boolean) {
        // The preview stops while the screen is hidden; coming back, it waits for a fresh settle.
        previewController.onVisible(visible)
        if (visible == screenVisible) return
        screenVisible = visible
        restartAutoRefresh()
    }

    /** Ambient mode, the screensaver or a dream shows over the section (#71): no preview, no refresh. */
    fun onAmbient(showing: Boolean) {
        previewController.onAmbient(showing)
        if (showing == ambientShowing) return
        ambientShowing = showing
        restartAutoRefresh()
    }

    private fun restartAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
        if (!screenVisible || ambientShowing) return
        val plan = SportsLiveRefreshPlan.of(selectionState.value.tab) ?: return
        autoRefreshJob = viewModelScope.launch {
            // The section's opening load (or a manual refresh) first: it fetched this tab's catalogs too.
            fullLoadJob?.join()
            val every = plan.everyMs
            if (every == null) {
                // Opened, not on a timer: only when its last fetch is older than its openStaleMs.
                if (ageOf(plan.kinds) >= plan.openStaleMs) refreshKinds(plan.kinds)
                return@launch
            }
            while (isActive) {
                val age = ageOf(plan.kinds)
                if (age >= every) {
                    refreshKinds(plan.kinds)
                    delay(every)
                } else {
                    delay(every - age)
                }
            }
        }
    }

    /** How long ago the least recently fetched of [kinds] was fetched; never: as old as can be. */
    private fun ageOf(kinds: Set<SportsLiveKind>): Long {
        var oldest = Long.MAX_VALUE
        for (kind in kinds) oldest = minOf(oldest, fetchedAtMs[kind] ?: return Long.MAX_VALUE)
        return refreshClock() - oldest
    }

    private suspend fun refreshKinds(kinds: Set<SportsLiveKind>) {
        // Stamped when asked, so a failing catalog is not asked again before its interval.
        kinds.forEach { fetchedAtMs[it] = refreshClock() }
        load(force = true, includeChannels = SportsLiveKind.CHANNELS in kinds, onlyKinds = kinds)
    }

    /** A failed load keeps the previous failures and items. */
    private suspend fun load(force: Boolean, includeChannels: Boolean = true, onlyKinds: Set<SportsLiveKind>? = null) {
        selectionState.update { it.copy(isRefreshing = true) }
        try {
            val result = repo.load(force, includeChannels, onlyKinds)
            selectionState.update {
                // A cached load (no fetch) counts once (#170).
                val sources = if (result.loadedAtMs == it.lastRefreshedMs) {
                    it.sources
                } else {
                    it.sources.after(
                        fetched = result.fetchedSources,
                        failed = result.failures.associate { failure -> failure.catalog.addon.id to failure.catalog.addon.displayName },
                        claimed = result.claimedSources,
                        nowMs = result.loadedAtMs
                    )
                }
                it.copy(failures = result.failures, lastRefreshedMs = result.loadedAtMs, sources = sources)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        } finally {
            selectionState.update { it.copy(isRefreshing = false) }
        }
    }

    private fun buildState(
        bucketed: Bucketed,
        endedKeys: Set<String>,
        selection: Selection,
        inputs: EndedInputs,
        /** The Scores setting: only Show draws the excitement badges. */
        mode: ScoresMode? = null,
        /** Cards whose link check found them dead (#60 phase 2), with when. */
        linkDead: Map<String, SportsLiveLinkVerdicts.Dead> = emptyMap(),
        /** My sports (#146): scopes Live now, Upcoming and Replays. */
        mySports: MySports = MySports.DEFAULT
    ): SportsLiveUiState {
        lastMySports = mySports
        val hasClaimed = bucketed.hasClaimed
        // A live game whose lookup found no streams is not over (#145): it stays where it is, dimmed as
        // "no streams yet", and is looked up again later. Games found over (#60: ESPN, or their addon) move
        // to the Just ended strip at the end: ESPN finals leave half an hour after they ended, estimated
        // ones stay (playable) until their addon drops them or six hours pass.
        val now = nowMs()
        var justEnded = emptyMap<String, EndedGame>()
        var noStreams = emptySet<String>()
        var finals = emptySet<String>()
        excitementHistory.observe(inputs.scores.values, now)
        var whyTags = emptyMap<String, WhyTag>()
        val tabs = bucketed.tabs.mapValues { (tab, tabItems) ->
            if (tab == SportsLiveTab.LIVE_NOW) {
                val scores = inputs.scores
                noStreams = tabItems.filter { it.hasEnded(endedKeys) }.mapTo(HashSet()) { it.streamKey }
                val split = endedGames.split(tabItems, scores, now, inputs.canEstimate)
                finals = split.finals.keys
                // Live far longer than its sport runs: moved to the strip, never hidden (a time estimate
                // never hides a game), unless links were found lately or ESPN shows it in play.
                val (overdue, notOverdue) = split.live.partition {
                    SportsLiveLiveDuration.isOverdue(it, now) && !repo.hasRecentLinks(it) &&
                        ScoreChips.matchedEvent(it, scores[it.streamKey])?.state != ScoreState.IN
                }
                val overdueEnded = overdue.mapNotNull { item ->
                    val start = SportsLiveEndedGames.timedStart(item) ?: return@mapNotNull null
                    val ended = EndedGame(EndedSignal.ESTIMATE, start + SportsLiveLiveDuration.maxLiveMs(item.sport))
                    (item to ended).takeIf { now < ended.dropsAtMs }
                }
                // #60 phase 2: a game past its estimated end that ESPN does not decide has its freshly resolved
                // link looked at, while Live now is on screen; a dead link moves it to the strip as "Probably
                // ended" (playable, never hidden). The verdict goes when fresh links come back (a later
                // resolve) or a later check finds the link alive, and a game ESPN takes up again comes back.
                // Checks go on under Scores Off: they make no ESPN request, only the stream host's.
                val linkEnded = ArrayList<SportsLiveItem>()
                val toCheck = ArrayList<LinkCheckRequest>()
                val live = notOverdue.filter { item ->
                    if (endedGames.linkCheckEnd(item, scores[item.streamKey], now) == null) return@filter true
                    val request = linkCheckOf(item)
                    val superseded = request != null && linkChecker?.clearIfSuperseded(request) == true
                    if (item.streamKey in linkDead && !superseded) {
                        linkEnded += item
                        false
                    } else {
                        request?.let(toCheck::add)
                        true
                    }
                }
                if (toCheck.isNotEmpty() && screenVisible && selection.tab == SportsLiveTab.LIVE_NOW) {
                    linkChecker?.offer(toCheck, viewModelScope)
                }
                justEnded = split.ended + overdueEnded.associate { (item, ended) -> item.streamKey to ended } +
                    linkEnded.associate { it.streamKey to SportsLiveEndedGames.linkDead(linkDead.getValue(it.streamKey).atMs) }
                // The excitement badge (#43, V2): shown with the scores only, and it never re-orders the list.
                if (mode == ScoresMode.SHOW) {
                    whyTags = buildMap {
                        live.forEach { item ->
                            // A spoiler-protected game (#151) gets no "close game" badge either.
                            val snapshot = scores[item.streamKey]?.takeUnless { it.spoilerProtected }
                            excitementOf(item, snapshot, excitementHistory, now)?.tag?.let { put(item.streamKey, it) }
                        }
                    }
                }
                val built = live + split.justEnded + overdueEnded.map { it.first } + linkEnded
                // The focused card is never taken from under the user: kept in place until focus moves on. While the list
                // holds focus no row leaves at all (F6): they go once it lets focus go.
                val focusedId = selection.itemId.takeIf { selection.tab == SportsLiveTab.LIVE_NOW }
                val kept = if (selection.browsing && selection.tab == SportsLiveTab.LIVE_NOW) {
                    sportsLiveKeepShown(shownLiveNow, built)
                } else {
                    sportsLiveKeepFocused(shownLiveNow, built, focusedId)
                }
                // A title just hidden (#134) leaves at once, even with focus on it: the keep step must not bring it back.
                SportsLiveHiddenTitles.filter(kept, hiddenNow).also { shownLiveNow = it }
            } else {
                tabItems
            }
        }
        // #146: My sports scopes Live now, Upcoming and Replays (never Channels): followed teams' games stay, games of
        // no identifiable sport go (#167). All sports lifts it on the tab it is picked on; the chips and counts follow My sports.
        val allSports = selection.filter == SportsLiveUiState.ALL_SPORTS_FILTER
        val scopedTabs = tabs.mapValues { (tab, items) ->
            if (tab in SCOPED_TABS) SportsLiveMySportsScope.scope(items, mySports) else items
        }
        val fullItems = tabs[selection.tab].orEmpty()
        // #168: the listed games of unknown sport (shown under All sports, counted as hidden under My sports) are
        // looked up in the background, only while the section is on screen and only for Live now and Upcoming.
        if (screenVisible && (selection.tab == SportsLiveTab.LIVE_NOW || selection.tab == SportsLiveTab.UPCOMING)) identify(fullItems)
        val scopedItems = scopedTabs[selection.tab].orEmpty()
        val mySportsScoped = scopedItems.size < fullItems.size
        val tabItems = if (allSports) fullItems else scopedItems
        // Channels are filtered by region (P4: country codes, long names, the rest under Other), every other tab
        // by sport: one value per sport ("American Football" and "NFL" are both Football), a catch-all
        // ("Sports", a catalog's name) only under All (V2 P3).
        val channels = selection.tab == SportsLiveTab.CHANNELS
        val filterOf: (SportsLiveItem) -> String? =
            if (channels) SportsLiveChannelRegions::regionOf else ({ item -> SportsLiveSportKind.filterKeyOf(item) })
        val values = scopedItems.mapNotNull(filterOf).distinct()
        // The chips' counts (#79): Live now counts live, zappable games (#145), as the player's panel and CH banner
        // do: its Just ended strip, finals in their grace and "no streams yet" games are left out.
        fun counted(items: List<SportsLiveItem>) = if (selection.tab == SportsLiveTab.LIVE_NOW) {
            items.filter { it.streamKey !in justEnded && it.streamKey !in finals && it.streamKey !in noStreams }
        } else {
            items
        }
        val counts = sportsLiveFilterCounts(counted(scopedItems), filterOf, myTeams = selection.tab != SportsLiveTab.CHANNELS)
            .let { if (mySportsScoped) it + (SportsLiveUiState.ALL_SPORTS_FILTER to counted(fullItems).size) else it }
        // "★ My teams" leads whenever a followed team plays in the tab (never on Channels); chips with no
        // games hide, except the selected one, kept with 0 until the user moves (onUserMoved). All sports
        // follows it while My sports hides any of the tab's games (#146).
        val filter = selection.filter
        val filters = sportsLiveShownFilters(values, counts, filter.takeIf { !allSports })
            .let { if (channels) SportsLiveChannelRegions.ordered(it) else it }
            .let { shown -> if (mySportsScoped || allSports) withAllSports(shown) else shown }
        val visible = when (filter) {
            null, SportsLiveUiState.ALL_SPORTS_FILTER -> tabItems
            SportsLiveUiState.MY_TEAMS_FILTER -> tabItems.filter { it.followed }
            else -> tabItems.filter { filterOf(it) == filter }
        }
        val selectedId = selection.itemId?.takeIf { id -> visible.any { it.id == id } }
            ?: visible.firstOrNull()?.id
        return SportsLiveUiState(
            // Only a load with no data yet shows as loading, so a refresh does not blank the list.
            isLoading = hasClaimed && !bucketed.hasLoad && selection.isRefreshing,
            hasClaimedCatalogs = hasClaimed,
            tabs = if (allSports) scopedTabs + (selection.tab to fullItems) else scopedTabs,
            selectedTab = selection.tab,
            filters = filters,
            selectedFilter = filter,
            mySportsScoped = selection.tab in SCOPED_TABS && !mySports.isDefault,
            // #167: under My sports (All picked), Live now ends with the games it hides, one press from All sports.
            hiddenByMySports = if (selection.tab == SportsLiveTab.LIVE_NOW && filter == null) {
                (counted(fullItems).size - counted(scopedItems).size).coerceAtLeast(0)
            } else {
                0
            },
            filterCounts = counts,
            visibleItems = visible,
            selectedItemId = selectedId,
            failures = selection.failures,
            lastRefreshedMs = selection.lastRefreshedMs,
            sources = selection.sources,
            justEnded = justEnded,
            noStreams = noStreams,
            nowMs = now,
            whyTags = whyTags
        )
    }

    private companion object {
        /** How long the team directory must be still before the load is merged again (live.18). */
        const val REBUCKET_DEBOUNCE_MS = 2_000L
        const val MINUTE_MS = 60_000L
        const val FOCUS_PROBE_DELAY_MS = 700L
        const val ENDED_TICK_MS = 30_000L

        /** The tabs My sports scopes (#146); never Channels or Results (which has its own My sports). */
        val SCOPED_TABS = setOf(SportsLiveTab.LIVE_NOW, SportsLiveTab.UPCOMING, SportsLiveTab.REPLAYS)

        /** The tabs whose game cards take the league their teams name; never Channels or Results. */
        val INFERRED_TABS = setOf(SportsLiveTab.LIVE_NOW, SportsLiveTab.UPCOMING, SportsLiveTab.REPLAYS)
    }
}

/**
 * How a tab's addon catalogs refresh while it is on screen (#71): Live now every 3 minutes and Upcoming every
 * 10 (both from the events catalogs), Replays never on a timer (when opened, only if its last fetch is over
 * 10 minutes old), Channels and Results never (Results reads ESPN; channels reload on a manual refresh).
 * Scores keep their own cadence.
 */
internal data class SportsLiveRefreshPlan(
    val kinds: Set<SportsLiveKind>,
    /** The interval; null: no timer. */
    val everyMs: Long?,
    /** With no timer: opening the tab refreshes it when its last fetch is this old. */
    val openStaleMs: Long = Long.MAX_VALUE
) {
    companion object {
        const val LIVE_NOW_MS = 3 * 60_000L
        const val UPCOMING_MS = 10 * 60_000L
        const val REPLAYS_OPEN_STALE_MS = 10 * 60_000L

        fun of(tab: SportsLiveTab): SportsLiveRefreshPlan? = when (tab) {
            SportsLiveTab.LIVE_NOW -> SportsLiveRefreshPlan(setOf(SportsLiveKind.EVENTS), LIVE_NOW_MS)
            SportsLiveTab.UPCOMING -> SportsLiveRefreshPlan(setOf(SportsLiveKind.EVENTS), UPCOMING_MS)
            SportsLiveTab.REPLAYS -> SportsLiveRefreshPlan(setOf(SportsLiveKind.REPLAY), everyMs = null, openStaleMs = REPLAYS_OPEN_STALE_MS)
            SportsLiveTab.CHANNELS, SportsLiveTab.RESULTS -> null
        }
    }
}

/**
 * Follow ‹team› (the section's and the player panel's menus, #134): unfollows every followed team that is
 * [target]'s team ([TeamFollows.followersOf]), or, when none is, follows it by ESPN's name, id and league
 * ([TeamFollows.teamToFollow]). Only real teams ([FollowTargets]) are ever offered, never a raw title's side.
 */
internal suspend fun SportsLiveRepository.toggleFollowTarget(target: FollowTarget) {
    val matching = TeamFollows.followersOf(followedTeams.first(), target.event, target.side)
    if (matching.isEmpty()) {
        TeamFollows.teamToFollow(target.event, target.side)?.let { follow(it) }
    } else {
        matching.forEach { unfollow(it) }
    }
}
