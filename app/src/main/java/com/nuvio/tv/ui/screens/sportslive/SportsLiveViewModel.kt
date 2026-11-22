package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.SportsLiveGameReadiness
import com.nuvio.tv.core.sportslive.scores.TeamStanding
import com.nuvio.tv.core.sportslive.scores.StandingsRepository
import com.nuvio.tv.core.sportslive.scores.EspnLeague
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.profile.ProfileManager
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
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.SportsLiveMySportsScope
import com.nuvio.tv.core.sportslive.SportsLiveLineup
import com.nuvio.tv.core.sportslive.SportsLiveCalmOpen
import com.nuvio.tv.core.sportslive.SportsLiveOtherEvents
import com.nuvio.tv.core.sportslive.SportsLiveMoreLive
import com.nuvio.tv.core.sportslive.SportsLiveAddSportPrompt
import com.nuvio.tv.core.sportslive.scores.MySport
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
import com.nuvio.tv.core.sportslive.SportsLiveDebugTiming
import com.nuvio.tv.core.sportslive.SportsLiveListCache
import com.nuvio.tv.core.sportslive.SportsLiveListSnapshot
import com.nuvio.tv.core.sportslive.SportsLiveListSnapshots
import com.nuvio.tv.core.sportslive.SportsLiveSharedBuckets
import com.nuvio.tv.core.sportslive.SportsLiveUpcomingBucket
import com.nuvio.tv.core.sportslive.SportsLiveUpcomingWindow
import com.nuvio.tv.data.local.SportsLiveListSnapshotStore
import com.nuvio.tv.domain.model.ClaimedCatalog
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.sportslive.identify.SportsLiveTeamIdentity
import com.nuvio.tv.core.sportslive.identify.TeamIdentifier
import com.nuvio.tv.core.sportslive.identify.TeamIdentityBook
import com.nuvio.tv.core.sportslive.SportsLiveChannelBrowse
import com.nuvio.tv.core.sportslive.SportsLiveChannelBrowseKind
import com.nuvio.tv.core.sportslive.SportsLiveChannelCountries
import com.nuvio.tv.core.sportslive.SportsLiveChannelFavorites
import com.nuvio.tv.core.sportslive.SportsLiveChannelLists
import com.nuvio.tv.core.sportslive.SportsLiveChannelPicks
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.combineTransform
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.IdentityHashMap
import javax.inject.Inject

@HiltViewModel
class SportsLiveViewModel internal constructor(
    private val repo: SportsLiveRepository,
    /** The hover preview's lookup for an item: a stream a light player can play as it is, or why not. */
    previewResolve: suspend (SportsLiveItem) -> SportsLivePreviewLookup = { SportsLivePreviewLookup.NotPreviewable },
    hoverPreviewEnabled: Flow<Boolean> = flowOf(false),
    /** The mini player plays: it takes priority, so no preview starts meanwhile. */
    miniPlayerActive: Flow<Boolean> = flowOf(false),
    /** The mini player's game (its card id) while it plays: never taken off Live now meanwhile (TV QA BUG7). */
    miniPlayingId: Flow<String?> = flowOf(null),
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
    /** The last list built for the profile (live.20 B1): shown at once on open, reused on re-entry while its load is the latest. */
    private val listCache: SportsLiveListCache = SportsLiveListCache.NONE,
    /** The team directory's and identities' revisions as one number: a list bucketed under another is not reused. */
    private val directoryStamp: () -> Long = { 0L },
    /** The one sorting per load shared with Home's row and the player's panel (live.20); null: [bucket] on its own (tests). */
    private val sharedBuckets: SportsLiveSharedBuckets? = null,
    // Where the list is bucketed and pinned, and the boards built; tests pass their dispatcher, so it runs in virtual time.
    private val computeDispatcher: CoroutineDispatcher,
    /** live.20 B2: looks up a Live now row rested on, ahead of OK; null (tests): none. */
    prefetchHooks: SportsLivePrefetchHooks? = null,
    /** Saves a report (#172) asked for by its trigger: [SportsLiveReporter.report]. */
    private val report: (String) -> Unit = {},
    /** Channels' favorites and recent plays of the profile (phase 1). */
    channelPicks: Flow<SportsLiveChannelPicks> = flowOf(SportsLiveChannelPicks.NONE),
    /** Saves a change of the profile's channel picks. */
    private val updateChannelPicks: suspend ((SportsLiveChannelPicks) -> SportsLiveChannelPicks) -> Unit = {},
    /** Channels phase 2: the countries chosen for the profile (null: the viewer's own). */
    channelCountries: Flow<Set<String>?> = flowOf(null),
    /** Saves the countries chosen (null: back to the viewer's own). */
    private val saveChannelCountries: suspend (Set<String>?) -> Unit = {},
    /** This TV's region, a viewer's own country (phase 2); null in tests. */
    tvRegion: String? = null,
    /** What the profile said to "Add ‹sport› to My sports?" (More live). */
    addSportMemory: Flow<SportsLiveAddSportPrompt.Memory> = flowOf(SportsLiveAddSportPrompt.Memory()),
    /** Saves a change of those answers. */
    private val saveAddSportMemory: suspend ((SportsLiveAddSportPrompt.Memory) -> SportsLiveAddSportPrompt.Memory) -> Unit = {},
    /** Puts a sport in My sports (Add on the card). */
    private val addMySport: suspend (MySport) -> Unit = {},
    /** #143: a burst of score updates rebuilds the board once, this long after its first (0: each, as in tests). */
    scoresSettleMs: Long = 0L,
    /** #143: Upcoming's ESPN scoreboards asked for ahead ([LiveScoresRepository.prefetchUpcoming]); the requests made. */
    private val prefetchUpcomingScores: suspend () -> Int = { 0 },
    /** Card keys the score feeds confirmed they do not have (#215): an addon-live one reads "Listed live". */
    unmatchedScores: Flow<Set<String>> = flowOf(emptySet()),
    /** The spoiler protection the kept boards depend on (live.23 review S2). */
    protectionScope: Flow<SportsLiveBoardCache.ProtectionScope> = flowOf(SportsLiveBoardCache.ProtectionScope.NONE),
    /** The active profile's id: part of every kept board's scope (live.23 review F3), so a switch drops them. */
    activeProfile: Flow<Int?> = flowOf(null),
    /** RC regression (#143): team directory changes bucket the load again at most once in this time (0: each, as in tests). */
    rebucketGapMs: Long = 0L,
    /** RC regression (#143): learned logos re-infer every card's sport (pinned) at most once in this time (0: each). */
    pinSettleMs: Long = 0L,
    /** live.20 1b: a row rested on is checked ahead only when it is one of my games ([SportsLiveMyGames]). */
    private val isMyGame: (SportsLiveItem) -> Boolean = { false },
    /**
     * live.24: Upcoming sorts only this many days ahead (plus followed teams' and untimed games, and My sports only) until
     * a later day or All sports is asked ([SportsLiveUpcomingWindow]); null: every game (tests). Needs [sharedBuckets].
     */
    private val upcomingWindowDays: Int? = null,
    /** The zone Upcoming's days are counted in (the Agenda's). */
    private val zone: ZoneId = ZoneId.systemDefault()
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
        standingsRepository: StandingsRepository,
        listStore: SportsLiveListSnapshotStore,
        reporter: SportsLiveReporter,
        profileManager: ProfileManager
    ) : this(
        repo = repo,
        // The zap's pick, which joins the stream search the ended-games probe started on settle; a
        // cached resolve, else at most one resolve per game per window, never during a cool-down (C1).
        previewResolve = resolver::previewLookup,
        hoverPreviewEnabled = combine(prefs.hoverPreview, prefs.dataSaver) { hover, saver ->
            SportsLiveDataSaver.hoverPreviewAllowed(hover, saver)
        },
        miniPlayerActive = SportsLiveMiniPlayers.controller.state.map { it is SportsLiveMiniState.Playing },
        miniPlayingId = SportsLiveMiniPlayers.controller.state.map { (it as? SportsLiveMiniState.Playing)?.item?.id },
        dataSaver = prefs.dataSaver,
        onResolveFailed = resolver::invalidate,
        endedInputs = combine(liveScores.scores, liveScores.mode, liveScores.unmatched) { scores, mode, unmatched ->
            EndedInputs(scores, SportsLiveEndedGames.estimateGate(mode == ScoresMode.OFF, unmatched))
        }.firstThenCoalesce(STATE_SCORES_SETTLE_MS),
        endedGames = SportsLiveEndedGames.shared,
        clock = flow {
            while (true) {
                emit(Unit)
                delay(ENDED_TICK_MS)
            }
        },
        // V2 (#132): one view per tab, in start order; the View menu and Sort are gone with their stored choices.
        dropRetiredPrefs = prefs::dropRetiredKeys,
        scoresMode = liveScores.mode,
        linkChecker = SportsLiveLinkChecker(fetch = manifestFetcher::fetch, verdicts = SportsLiveLinkVerdicts.shared),
        // Only a fresh resolve (the resolve cache's, at most five minutes old): never an older link that
        // once played, and never a resolve just to test.
        linkCheckOf = { item ->
            resolveCache.getFresh(item.streamKey)?.let { (stream, resolvedAtMs) ->
                SportsLiveManifestVerdict.requestFor(item, stream, resolvedAtMs)
            }
        },
        // A game's teams identified in the background (#168) rebuild the lists as a learned logo does. A score feed or a
        // team list landing learns many logos one by one: each bump re-inferred every card and rebuilt the board
        // (live.20 B1, GC churn on the TV), so a burst counts once. #143: LOGO_SETTLE_MS after its first bump (was a
        // debounce after its last, which a steady trickle of feeds could hold back for seconds).
        logoRevision = combine(TeamLogoBook.shared.revision, TeamIdentityBook.shared.revision) { logos, identities -> logos * 31 + identities }
            .firstThenCoalesce(LOGO_SETTLE_MS),
        inferSport = { item -> SportsLiveTeamIdentity.identified(SportsLiveSportInference.infer(item, TeamDirectory.shared)) },
        identify = identifier::offer,
        directoryChanges = SportsLiveSharedBuckets.stampChanges().map { },
        mySports = prefs.mySports,
        hiddenTitles = prefs.hiddenTitles,
        hideTitle = prefs::hideTitle,
        standings = standingsRepository.books,
        ensureStandings = standingsRepository::ensure,
        listCache = listStore,
        directoryStamp = { SportsLiveSharedBuckets.currentStamp() },
        sharedBuckets = SportsLiveSharedBuckets.shared,
        // RC regression (#143): the section's rebuilds on their own two threads at a lower priority than the UI.
        computeDispatcher = SportsLiveCompute.dispatcher,
        prefetchHooks = SportsLivePrefetchHooks.of(resolver),
        report = reporter::report,
        channelPicks = prefs.channelPicks,
        updateChannelPicks = prefs::updateChannelPicks,
        channelCountries = prefs.channelCountries,
        saveChannelCountries = prefs::setChannelCountries,
        tvRegion = SportsLiveChannelCountries.tvRegion(),
        addSportMemory = prefs.addSportMemory,
        saveAddSportMemory = prefs::updateAddSportMemory,
        addMySport = { sport -> prefs.updateMySports { if (it.isDefault || sport in it.effectiveSports) it else it.toggleSport(sport) } },
        scoresSettleMs = BOARD_SCORES_SETTLE_MS,
        prefetchUpcomingScores = liveScores::prefetchUpcoming,
        unmatchedScores = liveScores.unmatched,
        protectionScope = liveScores.protection.map { SportsLiveBoardCache.ProtectionScope.of(it) }.distinctUntilChanged(),
        activeProfile = profileManager.activeProfileId,
        rebucketGapMs = REBUCKET_GAP_MS,
        pinSettleMs = PIN_SETTLE_MS,
        isMyGame = { item -> SportsLiveGameReadiness.shared.mine.value.let { mine -> (listOf(item) + item.alternates).any { it.id in mine } } },
        upcomingWindowDays = SportsLiveUpcomingWindow.NEAR_DAYS
    )

    private data class Selection(
        val tab: SportsLiveTab = SportsLiveTab.LIVE_NOW,
        val filter: String? = null,
        /** The last chip picked on each tab (#79), for this session: a tab opens on it while it has games. */
        val filterByTab: Map<SportsLiveTab, String> = emptyMap(),
        val itemId: String? = null,
        /** Live now is browsed (F6, TV QA BUG7): its rows, order and count hold until it is left or Refresh shows the new. */
        val browsing: Boolean = false,
        /** Bumped by "N new" on Refresh ([showNewRows]): the next build shows Live now as it is now, once. */
        val thaw: Int = 0,
        /** Live now's "More live" view is open (user 2026-11-21): the games My sports leaves out, by sport. */
        val moreLiveOpen: Boolean = false,
        /** J1: [SportsLiveCalmOpen.HOLD_MS] has passed since opening: Live now may show the saved list of an earlier visit. */
        val holdOver: Boolean = false,
        /** The sport of a More live game just played, to offer adding to My sports ([SportsLiveAddSportPrompt]). */
        val addSport: MySport? = null,
        /** Its card is on screen now (Live now shown since the game was played): leaving it unanswered counts. */
        val addSportShown: Boolean = false,
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
        val tabs: Map<SportsLiveTab, List<SportsLiveItem>>,
        /** When the catalogs shown were loaded (a list restored from the device: when it was saved); null: none. */
        val listAtMs: Long? = null,
        /** Built from this session's own load, not the saved list or nothing (#214). */
        val fromLoad: Boolean = false,
        /** The followed teams the tabs were pinned with (null: not pinned yet). */
        val followed: List<FollowedTeam>? = null,
        /** live.24: Upcoming's later dates not sorted yet ([SportsLiveUpcomingWindow]). */
        val upcomingPending: List<LocalDate> = emptyList(),
        /** live.24: games on Upcoming's sorted days waiting for All sports (a cheap count). */
        val upcomingWaiting: Int = 0,
        /** live.24: tabs still being sorted on the cold open (Live now went first): an empty one shows as loading. */
        val sortingTabs: Set<SportsLiveTab> = emptySet()
    )

    /** What Upcoming's working set was asked to hold (live.24): how many days, and every sport (All sports picked). */
    private data class UpcomingAsk(val days: Int, val allSports: Boolean = false)

    /** Upcoming's working set as asked, with My sports and the followed teams it reads; null without a window (tests). */
    private data class UpcomingInputs(val ask: UpcomingAsk, val mySports: MySports, val followed: List<FollowedTeam>)

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

    // The last "N new" press applied ([Selection.thaw]).
    private var thawApplied = 0

    // The profile's answers to "Add ‹sport› to My sports?", as last read.
    @Volatile
    private var addSportMemoryNow = SportsLiveAddSportPrompt.Memory()

    // The follows and My sports Live now was last ranked with: a change is the user's (Follow, Unfollow, Edit my sports),
    // so Live now is ranked again once (J2) instead of keeping its calm order.
    private var liveNowRankedFor: Pair<List<FollowedTeam>?, MySports>? = null

    // J1: Live now has been shown this visit of the screen (reset when the screen is left): it stays calm, also across tabs.
    @Volatile
    private var liveNowShown = false

    // The mini player's game while it plays (TV QA BUG7): it stays listed.
    @Volatile
    private var miniPlayingNow: String? = null

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
    // RC regression (#143): bucketing the whole load (4-6 s on the onn) ran every few seconds while team lists and
    // identities kept landing; now at most once per rebucketGapMs after the first.
    private val rebucket: Flow<Unit> = directoryChanges.debounce(REBUCKET_DEBOUNCE_MS).firstThenCoalesce(rebucketGapMs).onStart { emit(Unit) }

    // live.24: Upcoming's days and sports asked for so far this visit ([showUpcomingDay], All sports).
    private val upcomingAsk = MutableStateFlow(UpcomingAsk(days = upcomingWindowDays ?: SportsLiveUpcomingWindow.NEAR_DAYS))

    private val upcomingInputs: Flow<UpcomingInputs?> =
        if (upcomingWindowDays == null || sharedBuckets == null) {
            flowOf(null)
        } else {
            combine(upcomingAsk, mySports, repo.followedTeams) { ask, sports, followed -> UpcomingInputs(ask, sports, followed) }.distinctUntilChanged()
        }

    // live.20 C1: the next tip-off (or live window's end) of the shown list; when it passes, the list is bucketed again,
    // so a game moves from Upcoming to Live now on time without a catalog refresh.
    private val kickoffs = MutableStateFlow(0L)
    private var kickoffJob: Job? = null
    private var kickoffTarget = Long.MIN_VALUE
    private var kickoffFired = Long.MIN_VALUE

    // The saved list (live.20 B1), read once: shown while there is no load yet.
    private var savedRead = false
    private var saved: SportsLiveListSnapshot? = null

    // When the section was opened (debug timing lines).
    private val openedAtNs = System.nanoTime()
    private val firstStateLogged = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun sinceOpenMs(): Long = (System.nanoTime() - openedAtNs) / 1_000_000

    // live.24: the cold open's Live now went out before the other tabs were sorted (once per visit), and the last sorting out.
    private var liveFirstDone = false
    private var lastSorted: Bucketed? = null

    /** Runs between the cold open's Live now and the other tabs' sorting; tests hold it to see Live now first. */
    @VisibleForTesting
    internal var afterLiveFirst: suspend () -> Unit = {}

    private val bucketed = combineTransform(repo.claimedCatalogs, repo.latest, rebucket, kickoffs, upcomingInputs) { claimed, latest, _, _, upcoming ->
        // Only items of catalogs claimed right now: the cache may still hold another profile's load.
        val claimedKeys = claimed.mapTo(HashSet()) { it.key }
        // Claimed catalogs come in the user's addon order, which picks a merged game's primary.
        val addonOrder = claimed.map { it.addon.id }.distinct()
        val now = nowMs()
        if (latest == null) {
            // Stale-while-revalidate (live.20 B1): the profile's last list, moved by the clock, while the first load runs.
            savedTabs(claimed, now)?.let { (tabs, savedAtMs) ->
                scheduleKickoff(SportsLiveItemBucketer.nextClockChange(tabs.values.flatten(), now))
                emit(Bucketed(hasClaimed = true, hasLoad = true, tabs = tabs, listAtMs = savedAtMs).also { lastSorted = it })
                return@combineTransform
            }
            emit(Bucketed(hasClaimed = claimed.isNotEmpty(), hasLoad = false, tabs = bucket(emptyList(), now, addonOrder)))
            return@combineTransform
        }
        val items = latest.items.filter { it.catalogKey in claimedKeys }
        val stamp = directoryStamp()
        // Back on the section while the load it was built from is still the latest (live.20 B1): no bucketing again,
        // unless the team directory moved or a game started since.
        // live.24: a windowed Upcoming is kept by the shared sorting itself (per load, per days asked), never by the list cache.
        val reused = listCache.peek()?.takeIf {
            upcoming == null && it.load === latest && it.claimedKeys == claimedKeys && it.addonOrder == addonOrder && it.directoryStamp == stamp &&
                !SportsLiveItemBucketer.clockChangedBetween(items, it.builtAtMs, now)
        }
        var upcomingBucket: SportsLiveUpcomingBucket? = null
        val tabs = if (reused != null) {
            SportsLiveDebugTiming.log { "sportsOpen stage=bucketReused t=${sinceOpenMs()}" }
            reused.tabs
        } else {
            val startedAt = System.nanoTime()
            // live.24: the cold open's first sorting sends Live now (~80 games) out at once, before Upcoming, Channels and
            // Replays (~3,800) are sorted; those tabs keep what they showed (the saved list) or wait as loading meanwhile.
            if (sharedBuckets != null && !liveFirstDone) {
                liveFirstDone = true
                val live = sharedBuckets.tab(latest, claimedKeys, addonOrder, SportsLiveTab.LIVE_NOW, now, stamp)
                val before = lastSorted?.tabs.orEmpty()
                emit(
                    Bucketed(
                        hasClaimed = claimed.isNotEmpty(), hasLoad = true, listAtMs = latest.loadedAtMs, fromLoad = true,
                        tabs = SportsLiveTab.entries.associateWith { tab -> if (tab == SportsLiveTab.LIVE_NOW) live else before[tab].orEmpty() },
                        sortingTabs = SportsLiveTab.entries.filter { it != SportsLiveTab.LIVE_NOW && before[it].isNullOrEmpty() }.toSet()
                    )
                )
                SportsLiveDebugTiming.log {
                    "sportsOpen stage=liveFirst ms=${(System.nanoTime() - startedAt) / 1_000_000} t=${sinceOpenMs()} rows=${live.size}"
                }
                afterLiveFirst()
            }
            val sorted = if (sharedBuckets != null && upcoming != null) {
                val window = SportsLiveUpcomingWindow(
                    zone = zone,
                    days = upcoming.ask.days,
                    mySports = upcoming.mySports.takeUnless { upcoming.ask.allSports },
                    followed = upcoming.followed
                )
                val windowed = sharedBuckets.upcoming(latest, claimedKeys, addonOrder, now, window, stamp)
                upcomingBucket = windowed
                SportsLiveTab.entries.associateWith { tab ->
                    if (tab == SportsLiveTab.UPCOMING) windowed.rows else sharedBuckets.tab(latest, claimedKeys, addonOrder, tab, now, stamp)
                }
            } else {
                sharedBuckets?.all(latest, claimedKeys, addonOrder, now, stamp) ?: bucket(items, now, addonOrder)
            }
            sorted.also { tabs ->
                SportsLiveDebugTiming.log {
                    "sportsOpen stage=bucket ms=${(System.nanoTime() - startedAt) / 1_000_000} t=${sinceOpenMs()} items=${items.size} " +
                        tabs.entries.joinToString(" ") { (tab, list) -> "${tab.name}=${list.size}" }
                }
                // The early list of a first load is never the device's list (live.23 review L3): closed before the full
                // load lands, the next cold start would lack the slow addons' games.
                if (!latest.partial) listCache.save(
                    SportsLiveListSnapshot(
                        savedAtMs = latest.loadedAtMs, tabs = tabs, load = latest, claimedKeys = claimedKeys,
                        addonOrder = addonOrder, directoryStamp = stamp, builtAtMs = now
                    )
                )
            }
        }
        scheduleKickoff(SportsLiveItemBucketer.nextClockChange(items, now))
        val done = Bucketed(
            hasClaimed = claimed.isNotEmpty(), hasLoad = true, tabs = tabs, listAtMs = latest.loadedAtMs, fromLoad = true,
            upcomingPending = upcomingBucket?.pendingDates.orEmpty(), upcomingWaiting = upcomingBucket?.waitingBySport ?: 0
        )
        lastSorted = done
        emit(done)
    }.flowOn(computeDispatcher)

    /** The saved list's tabs for [claimed] at [now], with when it was loaded; null when there is none to show. */
    private suspend fun savedTabs(claimed: List<ClaimedCatalog>, now: Long): Pair<Map<SportsLiveTab, List<SportsLiveItem>>, Long>? {
        if (claimed.isEmpty()) return null
        if (!savedRead) {
            savedRead = true
            saved = try {
                listCache.read()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
        val snapshot = saved ?: return null
        val startedAt = System.nanoTime()
        val tabs = SportsLiveListSnapshots.restore(snapshot, claimed, now) ?: return null
        SportsLiveDebugTiming.log {
            "sportsOpen stage=restored ms=${(System.nanoTime() - startedAt) / 1_000_000} t=${sinceOpenMs()} " +
                "ageMin=${(now - snapshot.savedAtMs) / MINUTE_MS} live=${tabs[SportsLiveTab.LIVE_NOW].orEmpty().size}"
        }
        return tabs to snapshot.savedAtMs
    }

    /**
     * Buckets the list again just after [next] (live.20 C1): a start or a live window's end. Each moment once: a
     * clock that has not moved past it (tests) never schedules it again.
     */
    @Synchronized
    private fun scheduleKickoff(next: Long?) {
        if (next == null || next <= kickoffFired) return
        if (next == kickoffTarget && kickoffJob?.isActive == true) return
        kickoffJob?.cancel()
        kickoffTarget = next
        kickoffJob = viewModelScope.launch {
            delay((next - nowMs()).coerceAtLeast(0L) + KICKOFF_SLACK_MS)
            synchronized(this@SportsLiveViewModel) { kickoffFired = next }
            SportsLiveDebugTiming.log { "sportsOpen stage=kickoff t=${sinceOpenMs()}" }
            kickoffs.value = next
        }
    }

    // Followed teams' games first in every tab but Channels, where they are only marked (for the
    // card star); a follow change does not re-bucket.
    // Live now's, Upcoming's and Replays' unrouted two-team cards take the league their teams name (#132 P4),
    // again whenever the team directory changes (it bumps the logo revision). Channels are never inferred.
    // Titles hidden from Live Now (#134) are left off its list, and off Upcoming's (live.20: "Hide this" there).
    // RC regression (#143): re-inferring every card (3-4 s on the onn) at most once per pinSettleMs for learned logos.
    // live.24: each card's inferred copy, kept while the learned logos, identities and team directory are as they were, so
    // a re-pin (a refreshed catalog, a follow, a hidden title, the open's second step) infers only the cards it has not
    // seen. By object: rows a re-bucketing did not change keep theirs ([SportsLiveItemBucketer.bucketClockedIncremental]).
    private var pinMemo = IdentityHashMap<SportsLiveItem, SportsLiveItem>()
    private var pinMemoKey: Pair<Int, Long>? = null

    private val pinned = combine(bucketed, repo.followedTeams, logoRevision.firstThenCoalesce(pinSettleMs), hiddenTitles) { bucketed, followed, revision, hidden ->
        hiddenNow = hidden
        val startedAt = System.nanoTime()
        val memoKey = revision to directoryStamp()
        val before = if (memoKey == pinMemoKey) pinMemo else IdentityHashMap()
        val memo = IdentityHashMap<SportsLiveItem, SportsLiveItem>()
        var inferredCount = 0
        fun inferred(item: SportsLiveItem): SportsLiveItem = memo.getOrPut(item) { before[item] ?: inferSport(item).also { inferredCount++ } }
        bucketed.copy(
            followed = followed,
            tabs = bucketed.tabs.mapValues { (tab, all) ->
                val listed = if (tab == SportsLiveTab.LIVE_NOW || tab == SportsLiveTab.UPCOMING) SportsLiveHiddenTitles.filter(all, hidden) else all
                val items = if (tab in INFERRED_TABS) listed.map { inferred(it) } else listed
                if (tab == SportsLiveTab.CHANNELS) {
                    SportsLiveItemBucketer.markFollowed(items, followed)
                } else {
                    SportsLiveItemBucketer.pinFollowed(items, followed).also { rows ->
                        // Debug builds: rows that look like one game but did not merge (live.18).
                        if (BuildConfig.IS_DEBUG_BUILD) SportsLiveDupes.log(tab.name, rows, nowMs())
                    }
                }
            }
        ).also {
            pinMemo = memo
            pinMemoKey = memoKey
            SportsLiveDebugTiming.log {
                "sportsOpen stage=pinned ms=${(System.nanoTime() - startedAt) / 1_000_000} t=${sinceOpenMs()} " +
                    "inferred=$inferredCount reused=${memo.size - inferredCount}"
            }
        }
    }.flowOn(computeDispatcher)

    /** What one full state build read, and what it built (live.20): a focus move alone reuses it ([stateForFocus]). */
    private class StateBuild(
        val pinned: Bucketed,
        val endedKeys: Set<String>,
        val selection: Selection,
        val inputs: EndedInputs,
        val mode: ScoresMode?,
        val linkDead: Map<String, SportsLiveLinkVerdicts.Dead>,
        val mySports: MySports,
        val tick: Long,
        /** Live now as built, before the keep step (null: no Live now tab). */
        val builtLiveNow: List<SportsLiveItem>?,
        /** How many of [builtLiveNow]'s first rows are live (the rest: the Just ended strip). */
        val builtLiveCount: Int,
        val state: SportsLiveUiState
    )

    // Each clock tick counts, so a tick (with nothing else new) still builds again.
    private var clockTicks = 0L
    private var lastBuild: StateBuild? = null
    // Live now as the last full build made it, before the keep step.
    private var lastBuiltLiveNow: List<SportsLiveItem>? = null
    private var lastBuiltLiveCount = 0

    /** Full state builds; tests read it. */
    @VisibleForTesting
    internal var stateBuilds = 0

    val uiState: StateFlow<SportsLiveUiState> =
        combine(
            pinned,
            repo.endedKeys,
            combine(selectionState, scoresMode, mySports) { selection, mode, sports -> Triple(selection, mode, sports) },
            combine(endedInputs, linkChecker?.dead ?: flowOf(emptyMap())) { inputs, linkDead -> inputs to linkDead },
            clock.map { ++clockTicks }
        ) { pinned, endedKeys, (selection, mode, sports), (inputs, linkDead), tick ->
            // A focus move alone (live.20): the last build with the new selection, unless it changes which rows Live now keeps.
            stateForFocus(pinned, endedKeys, selection, inputs, mode, linkDead, sports, tick)
                ?: run {
                    val startedAt = System.nanoTime()
                    buildState(pinned, endedKeys, selection, inputs, mode, linkDead, sports).also {
                        // #143: what a tab switch, a scores update or a learned logo costs before any board is built.
                        SportsLiveDebugTiming.log { "sportsOpen stage=state ms=${(System.nanoTime() - startedAt) / 1_000_000} tab=${selection.tab.name}" }
                    }
                }.also { state ->
                    stateBuilds++
                    lastBuild = StateBuild(pinned, endedKeys, selection, inputs, mode, linkDead, sports, tick, lastBuiltLiveNow, lastBuiltLiveCount, state)
                }
        }
            // A rebuild never runs on the main thread (#143).
            .flowOn(computeDispatcher)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SportsLiveUiState())

    /**
     * [uiState] as the screen draws it (#143): a focus move alone (only the selected id changed, which the screen never
     * reads) emits nothing, so a D-pad move no longer recomposes the whole section a second time, every row included.
     */
    val screenState: StateFlow<SportsLiveUiState> =
        uiState.distinctUntilChanged(::sportsLiveSameScreen)
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

    // Channels phase 1: the picks' order is taken when Channels opens, the section comes back or a refresh is asked, so
    // a favorite added or removed never moves a row under focus; the ★ follows the picks at once.
    private val channelOrderTakes = MutableStateFlow(0)
    private val storedChannelPicks: Flow<SportsLiveChannelPicks> = channelPicks.catch { emit(SportsLiveChannelPicks.NONE) }
    private val channelBrowseState = MutableStateFlow<SportsLiveChannelBrowse?>(null)

    /** Channels' browse view (phase 2): null while the tab shows its own sections. */
    val channelBrowse: StateFlow<SportsLiveChannelBrowse?> = channelBrowseState

    private val channelLists: Flow<SportsLiveChannelLists> =
        combine(
            sportsLiveFrozenOn(channelOrderTakes, storedChannelPicks),
            storedChannelPicks,
            channelBrowseState,
            channelCountries.catch { emit(null) }
        ) { order, now, browse, countries ->
            SportsLiveChannelLists(order, now.favorites, browse, countries, tvRegion)
        }.distinctUntilChanged()

    /** "Sports ›", "All channels ›" or "See all popular ›" on Channels (phase 2): the browse view of that kind. */
    fun openChannelBrowse(kind: SportsLiveChannelBrowseKind) {
        channelBrowseState.value = SportsLiveChannelBrowse(kind)
    }

    /** Back from the browse view, or its All / Sports only switch (null kind: back to the tab's sections). */
    fun closeChannelBrowse() {
        channelBrowseState.value = null
    }

    /** The browse view's All / Sports only switch. */
    fun setChannelBrowseKind(kind: SportsLiveChannelBrowseKind) {
        channelBrowseState.update { it?.copy(kind = kind) ?: SportsLiveChannelBrowse(kind) }
    }

    /** Find a channel (phase 2): the browse view lists the channels matching [query]. */
    fun setChannelQuery(query: String) {
        channelBrowseState.update { it?.copy(query = query) ?: SportsLiveChannelBrowse(SportsLiveChannelBrowseKind.ALL, query) }
    }

    /** The countries panel's Back (phase 2): saves the countries ticked ([SportsLiveChannelCountries.ALL]; null: yours). */
    fun setChannelCountries(countries: Set<String>?) {
        viewModelScope.launch { runCatching { saveChannelCountries(countries) } }
    }

    private fun takeChannelOrder() = channelOrderTakes.update { it + 1 }

    /** Hold OK → Add to / Remove from favorites on a channel (Channels phase 1); [favorite]: it shows as one now. */
    fun toggleChannelFavorite(item: SportsLiveItem, favorite: Boolean) {
        viewModelScope.launch { runCatching { updateChannelPicks { SportsLiveChannelFavorites.toggle(it, item, favorite) } } }
    }

    /** A channel started from the section (Channels phase 1): first in Recent. */
    fun onChannelPlayed(item: SportsLiveItem) {
        if (!item.isChannel) return
        val at = nowMs()
        viewModelScope.launch { runCatching { updateChannelPicks { SportsLiveChannelFavorites.played(it, item, at) } } }
    }

    /**
     * Each view's last board (#143): a tab opened again shows it at once while the fresh one is built, builds nothing
     * when nothing it reads changed, and keeps the rows that did not change ([SportsLiveBoard.reusingRows]).
     */
    private val boardCache = SportsLiveBoardCache()

    /** Upcoming's last pre-game matches (live.23 RC upshift): a board built before its feeds answer again keeps its sections. */
    private val knownMatches = SportsLiveKnownMatches()

    /** The standings the last board read, for Upcoming built ahead (same inputs as its normal build). */
    @Volatile
    private var lastStandings: Map<EspnLeague, Map<String, TeamStanding>> = emptyMap()

    /** Debug timing (#143): the tab just selected and when, until its first board is shown. */
    @Volatile
    private var tabOpening: Pair<SportsLiveTab, Long>? = null

    /** Upcoming's board built ahead while Live now is browsed (#143), so its first open shows at once. */
    private var prewarmJob: Job? = null

    /** Builds a board; tests swap it to see where it runs. */
    @VisibleForTesting
    internal var buildBoard: suspend (SportsLiveBoardInputs) -> SportsLiveBoard = { SportsLiveBoard.build(it) }

    /** The tab of the board last published ([keptBoardSharing]: a held Live now board expires while nothing watches). */
    @Volatile
    private var shownTab: SportsLiveTab? = null

    /**
     * What the list draws (#71): the selected tab's items in the order shown, with the Scoreboard's rows
     * and the cards' drawn art, built on the compute dispatcher and published ready.
     * Only a change of what it shows builds again: a focus move does not. Null until the first one is built.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val board: StateFlow<SportsLiveBoard?> =
        combine(
            uiState,
            boardScores.firstThenCoalesce(scoresSettleMs),
            repo.followedTeams,
            logoRevision,
            combine(
                standings,
                combine(channelLists, unmatchedScores, ::Pair),
                combine(combine(repo.claimedCatalogs, mySports, hiddenTitles, ::Triple), activeProfile, protectionScope, ::Triple),
                ::Triple
            )
        ) { state, scores, followed, revision, (books, listsAndUnmatched, profileIdAndProtection) ->
            val (lists, unmatched) = listsAndUnmatched
            // #143: another profile or setting drops every tab's kept board before any is shown again; so does a
            // spoiler protection change (a Watch later save, Hide my teams' scores; review S2).
            val (profile, profileId, protection) = profileIdAndProtection
            val (claimed, sports, hidden) = profile
            boardCache.scopeTo(SportsLiveBoardScope(profileId, claimed.mapTo(HashSet()) { it.key }, sports, hidden, followed, scores.mode, protection))
            lastStandings = books
            sportsLiveBoardInputsOf(
                state = state,
                tab = state.selectedTab,
                filter = state.selectedFilter,
                items = state.visibleItems,
                scores = scores,
                followed = followed,
                logoRevision = revision,
                standings = books,
                channelLists = lists,
                unmatched = unmatched
            )
        }
            .distinctUntilChanged()
            // F21: an update of the same tab and filter lets the board being built finish (the newest follows); another
            // tab or filter cancels it.
            // #143: a tab or filter opened again shows its last board at once ([SportsLiveBoardCache.shownFor]).
            .mapFinishing(
                supersedes = { running, next -> running.tab != next.tab || running.filter != next.filter },
                instant = { inputs -> boardCache.instantFor(inputs) }
            ) { inputs ->
                val generation = boardCache.generation
                withContext(computeDispatcher) {
                    // Live.23 RC upshift: Upcoming's matches as last seen, so a board built before its feeds answer again
                    // keeps its Games to watch and times ([SportsLiveKnownMatches]).
                    knownMatches.learn(inputs.scores.scores, generation)
                    // Nothing it reads changed since it was built (back on a tab within the minute): no build at all. Not
                    // Channels, whose Popular now also reads the links found dead this session (outside the inputs).
                    if (inputs.tab != SportsLiveTab.CHANNELS) boardCache.reusable(inputs)?.let { return@withContext it }
                    val startedAt = System.nanoTime()
                    // #143: unchanged rows keep their instances, so the list redraws only the rows that changed.
                    buildBoard(knownMatches.fill(inputs)).reusingRows(boardCache.shownFor(inputs)).also {
                        boardCache.putIfCurrent(generation, inputs, it)
                        SportsLiveDebugTiming.log {
                            "sportsOpen stage=board ms=${(System.nanoTime() - startedAt) / 1_000_000} t=${sinceOpenMs()} tab=${inputs.tab.name} items=${inputs.items.size}"
                        }
                    }
                }
            }
            .onEach(::onBoardShown)
            // live.24 RC review H2: back from the player, never the Live now board from before the game.
            .stateIn(
                viewModelScope,
                keptBoardSharing(5_000, SportsLiveBoardCache.LIVE_MAX_AGE_MS) { shownTab == SportsLiveTab.LIVE_NOW },
                null
            )

    /** Each board as it is published: the tab-open timing line, and Upcoming built ahead from Live now. */
    private fun onBoardShown(shown: SportsLiveBoard) {
        shownTab = shown.tab
        val opening = tabOpening
        if (opening != null && opening.first == shown.tab) {
            tabOpening = null
            SportsLiveDebugTiming.log {
                "tab ${shown.tab.name} ready in ${(System.nanoTime() - opening.second) / 1_000_000} ms rows=${shown.items.size}"
            }
        }
        if (shown.tab == SportsLiveTab.LIVE_NOW) prewarmUpcoming()
    }

    /**
     * Upcoming's board, built ahead (#143): a while after Live now's list shows, while it is still the tab and the section
     * is on screen, Upcoming (All, no browse) is built once off the main thread from what is known now, so opening it
     * shows its games at once; the fresh board (with Upcoming's own scores and standings) follows as before. Its inputs
     * never equal a real build's (the logo revision is left unknown), so it is never taken as up to date.
     */
    /** The Upcoming list last built ahead ([prewarmUpcoming]). */
    private var prewarmedItems: List<SportsLiveItem>? = null

    private fun prewarmUpcoming() {
        if (prewarmJob?.isActive == true || boardCache.has(SportsLiveTab.UPCOMING, null)) return
        prewarmJob = viewModelScope.launch {
            delay(PREWARM_DELAY_MS)
            val selection = selectionState.value
            if (selection.tab != SportsLiveTab.LIVE_NOW || !screenVisible || ambientShowing) return@launch
            if (selection.filterByTab[SportsLiveTab.UPCOMING] != null || boardCache.has(SportsLiveTab.UPCOMING, null)) return@launch
            // Upcoming's scoreboards for today and tomorrow too, so its games match at once on open (capped in the repository).
            // Not under Data saver (live.23 review F4), as the link prefetch.
            if (SportsLiveDataSaver.prefetchAllowed(dataSaverActive)) launch {
                val asked = prefetchUpcomingScores()
                SportsLiveDebugTiming.log { "sportsOpen stage=prefetchScores requests=$asked" }
            }
            val generation = boardCache.generation
            val state = uiState.value
            val items = state.tabs[SportsLiveTab.UPCOMING].orEmpty()
            // Once per load (RC regression): a kept board dropped by a scope change is not built ahead again for it.
            if (items.isEmpty() || items === prewarmedItems) return@launch
            prewarmedItems = items
            // The same inputs as Upcoming's normal build (live.23 RC upshift): its scores as known, followed teams and
            // standings, so the board shown on open has the sections (Games to watch) the fresh one will have.
            val inputs = sportsLiveBoardInputsOf(
                state = state,
                tab = SportsLiveTab.UPCOMING,
                filter = null,
                items = items,
                scores = boardScores.value,
                followed = followedTeams.value,
                logoRevision = PREWARM_LOGO_REVISION,
                standings = lastStandings,
                channelLists = SportsLiveChannelLists.NONE,
                unmatched = emptySet()
            )
            val startedAt = System.nanoTime()
            val built = withContext(computeDispatcher) { buildBoard(knownMatches.fill(inputs)) }
            if (!boardCache.has(SportsLiveTab.UPCOMING, null)) boardCache.putIfCurrent(generation, inputs, built)
            SportsLiveDebugTiming.log { "sportsOpen stage=prewarm ms=${(System.nanoTime() - startedAt) / 1_000_000} tab=UPCOMING items=${items.size}" }
        }
    }

    /** The active profile's followed teams, for the card menu's Follow / Unfollow labels. */
    val followedTeams: StateFlow<List<FollowedTeam>> =
        repo.followedTeams.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val previewController = SportsLivePreviewController(viewModelScope, previewResolve).apply {
        onTab(selectionState.value.tab)
    }

    /** The details pane's hover preview. */
    val preview: StateFlow<SportsLivePreviewState> = previewController.state

    init {
        fullLoadJob = launchFullLoad(force = false)
        // J1: Live now waits behind its loading rows for this visit's load, at most HOLD_MS.
        viewModelScope.launch {
            delay(SportsLiveCalmOpen.HOLD_MS)
            selectionState.update { it.copy(holdOver = true) }
        }
        // An active mini player counts as the setting off; once it stops, a fresh settle starts.
        viewModelScope.launch {
            combine(hoverPreviewEnabled, miniPlayerActive) { enabled, mini -> enabled && !mini }
                .collect(previewController::onEnabled)
        }
        viewModelScope.launch { miniPlayingId.collect { miniPlayingNow = it } }
        viewModelScope.launch { dataSaver.collect { dataSaverActive = it } }
        viewModelScope.launch { addSportMemory.catch { }.collect { addSportMemoryNow = it } }
        viewModelScope.launch { runCatching { dropRetiredPrefs() } }
    }

    /**
     * The card the preview may play: the focused card while the list or the details pane holds
     * focus and no dialog, menu or pick mode is open; null otherwise.
     */
    fun setPreviewFocus(item: SportsLiveItem?) {
        previewController.onFocus(item)
        // B2: a live row rested on is looked up, so OK on it starts on a found link; never under Data saver.
        prefetcher?.onFocus(
            item?.takeIf {
                selectionState.value.tab == SportsLiveTab.LIVE_NOW && sportsLiveAutoPlayEligible(it) &&
                    SportsLiveDataSaver.prefetchAllowed(dataSaverActive) &&
                    // live.20 1b: only my games are checked ahead; a random row rested on sends nothing (RC regression).
                    isMyGame(it)
            }
        )
    }

    private val prefetcher = prefetchHooks?.let { SportsLiveStreamPrefetcher(viewModelScope, it) }

    fun onPreviewFirstFrame(item: SportsLiveItem) = previewController.onFirstFrame(item)

    /** A key pressed on the section: a playing preview's idle stop waits again (live.20). */
    fun onPreviewKey() = previewController.onKey()

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

    /** Hold OK → More… → Report a problem on a row (TV QA: remotes with no Play/Pause or Info to hold). */
    fun reportProblem() {
        report(REPORT_TRIGGER)
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
        // J1 + #143: back on Live now from another tab, its kept board shows at once and stays calm (rows in place, new
        // ones behind "N new"), so the fresh build never reshuffles the list just shown; leaving the screen builds afresh.
        // The add-sport card shows on Live now only: leaving it unanswered counts; coming back to Live now shows it.
        if (tab != SportsLiveTab.LIVE_NOW) leaveAddSportCard()
        tabOpening = tab to System.nanoTime()
        noteUpcomingFilter(tab, remembered)
        if (tab == SportsLiveTab.CHANNELS) takeChannelOrder() else channelBrowseState.value = null
        selectionState.update {
            it.copy(
                tab = tab, filter = remembered, itemId = null, browsing = false, moreLiveOpen = false,
                addSportShown = it.addSport != null && tab == SportsLiveTab.LIVE_NOW
            )
        }
        previewController.onTab(tab)
        // The new tab's catalogs on its own cadence (#71).
        if (changed) restartAutoRefresh()
    }

    /** [value] is a sport, a group on the Channels tab, or [SportsLiveUiState.MY_TEAMS_FILTER]; null means All. */
    fun selectFilter(value: String?) {
        // Another filter lists other cards: the preview stops until a card is focused again.
        if (value != selectionState.value.filter) previewController.onFocus(null)
        noteUpcomingFilter(selectionState.value.tab, value)
        selectionState.update {
            val remembered = if (value == null) it.filterByTab - it.tab else it.filterByTab + (it.tab to value)
            it.copy(filter = value, filterByTab = remembered)
        }
    }

    /** live.24: All sports picked on Upcoming sorts the games My sports left waiting, from then on this visit. */
    private fun noteUpcomingFilter(tab: SportsLiveTab, filter: String?) {
        if (tab == SportsLiveTab.UPCOMING && filter == SportsLiveUiState.ALL_SPORTS_FILTER) upcomingAsk.update { it.copy(allSports = true) }
    }

    /**
     * live.24: the viewer went to Upcoming's [date] (its day tab, Later › past the last sorted day, the list's end): that
     * day and every one before it are sorted, once per load. A day already sorted asks nothing.
     */
    fun showUpcomingDay(date: LocalDate) {
        val today = Instant.ofEpochMilli(nowMs()).atZone(zone).toLocalDate()
        val days = (ChronoUnit.DAYS.between(today, date) + 1).coerceIn(0L, MAX_UPCOMING_DAYS.toLong()).toInt()
        if (days > upcomingAsk.value.days) upcomingAsk.update { it.copy(days = maxOf(it.days, days)) }
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

    /**
     * Live now's content (list, strip, stage) holds focus ([focused]) or lets it go (F6, TV QA BUG7): while it does,
     * the list keeps its rows, order and count; new rows wait behind "N new" on Refresh ([showNewRows]).
     */
    fun setBrowsing(focused: Boolean) {
        if (selectionState.value.browsing != focused) selectionState.update { it.copy(browsing = focused) }
    }

    /** Add on the "Add ‹sport› to My sports?" card: the sport goes into My sports; the card goes. */
    fun acceptAddSport() {
        val sport = selectionState.value.addSport ?: return
        selectionState.update { it.copy(addSport = null, addSportShown = false) }
        viewModelScope.launch { runCatching { addMySport(sport) } }
    }

    /** Not now on the card: that sport is not asked about again. */
    fun declineAddSport() {
        val sport = selectionState.value.addSport ?: return
        selectionState.update { it.copy(addSport = null, addSportShown = false) }
        viewModelScope.launch { runCatching { saveAddSportMemory { it.decline(sport) } } }
    }

    /**
     * The card left unanswered (another tab, the player, another screen): it shows again next time, until it was left
     * [SportsLiveAddSportPrompt.MAX_IGNORES] times; then that sport is not asked about again.
     */
    private fun leaveAddSportCard() {
        val selection = selectionState.value
        val sport = selection.addSport ?: return
        if (!selection.addSportShown) return
        val next = addSportMemoryNow.ignore(sport)
        addSportMemoryNow = next
        selectionState.update { it.copy(addSport = sport.takeIf { next.asks(it) }, addSportShown = false) }
        viewModelScope.launch { runCatching { saveAddSportMemory { it.ignore(sport) } } }
    }

    /** OK on Live now's "More live" row: its view, the games My sports leaves out by sport. */
    fun openMoreLive() {
        selectionState.update { it.copy(moreLiveOpen = true, itemId = null) }
    }

    /** Back from the "More live" view: Live now's own list again. */
    fun closeMoreLive() {
        selectionState.update { it.copy(moreLiveOpen = false, itemId = null) }
    }

    /** "N new" on Refresh (TV QA Q5): Live now as it is now, the waiting rows in their places; then frozen again. */
    fun showNewRows() {
        selectionState.update { it.copy(thaw = it.thaw + 1) }
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
     * [target]'s player is opening: its zap session, as [startZapSession]; none for a replay (G4), which plays alone
     * under its clean title (no ▲▼ over the Replays list's raw titles, no live guide, no mini player on Back).
     */
    fun startZapSession(target: SportsLiveZapTarget) {
        if (target.replay) SportsLiveZapSessions.clear() else startZapSession(target.item)
    }

    /**
     * Before [item] is played: the player may zap through this tab, the filter's games first. On Live
     * now the session has every game of the tab, whatever the filter, so the player's panel opens on
     * All (#68); other tabs keep the list shown. With no session to start, any earlier one ends. A replay starts
     * none, by whatever path it is played (the Stream screen too: G4, never a frozen lineup over replays' raw titles).
     */
    fun startZapSession(item: SportsLiveItem) {
        if (item.kind == SportsLiveKind.REPLAY) {
            SportsLiveZapSessions.clear()
            return
        }
        val state = uiState.value
        // More live's games are zapped only among themselves, when one of them is played (user 2026-11-21).
        val moreLive = state.moreLive.flatMap { it.items }
        // A More live game played: the next Live now offers adding its sport to My sports, once per sport.
        if (moreLive.any { it.streamKey == item.streamKey }) {
            SportsLiveMoreLive.promptFor(item, lastMySports, addSportMemoryNow.notAsked)?.let { sport ->
                selectionState.update { it.copy(addSport = sport, addSportShown = false) }
            }
        }
        val (items, tab) = if (moreLive.any { it.streamKey == item.streamKey }) {
            moreLive to state.selectedTab
        } else {
            sportsLiveZapSource(state.selectedTab, item, state.zapItems, state.tabs[SportsLiveTab.LIVE_NOW].orEmpty())
        }
        val session = SportsLiveZapSession.of(
            items = items,
            itemKey = item.streamKey,
            tab = tab,
            filter = state.selectedFilter.takeIf { tab == state.selectedTab },
            returnToHomeOnBack = false
        )
        if (session != null) SportsLiveZapSessions.start(session) else SportsLiveZapSessions.clear()
    }

    fun refresh(force: Boolean = true) {
        // A manual refresh also forgets ended verdicts: those games show and are re-checked on focus.
        if (force) repo.clearEnded()
        takeChannelOrder()
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
        if (!visible) prefetcher?.stop()
        // Leaving the screen ends browsing (TV QA BUG7): Live now is as built when it comes back.
        if (!visible) {
            liveNowShown = false
            setBrowsing(false)
            leaveAddSportCard()
        } else {
            // Back on the section after a More live game: its card shows on Live now.
            selectionState.update { it.copy(addSportShown = it.addSport != null && it.tab == SportsLiveTab.LIVE_NOW) }
        }
        if (visible == screenVisible) return
        screenVisible = visible
        if (visible) takeChannelOrder()
        restartAutoRefresh(returning = visible)
    }

    /** Ambient mode, the screensaver or a dream shows over the section (#71): no preview, no refresh. */
    fun onAmbient(showing: Boolean) {
        previewController.onAmbient(showing)
        if (showing == ambientShowing) return
        ambientShowing = showing
        restartAutoRefresh()
    }

    /** [returning]: the section came back on screen (from the player, another screen, or the app in the background). */
    private fun restartAutoRefresh(returning: Boolean = false) {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
        if (!screenVisible || ambientShowing) return
        val plan = SportsLiveRefreshPlan.of(selectionState.value.tab)
        if (plan == null && !returning) return
        autoRefreshJob = viewModelScope.launch {
            // The section's opening load (or a manual refresh) first: it fetched this tab's catalogs too.
            fullLoadJob?.join()
            // live.20 C1: back on the section with a list over RETURN_STALE_MS old, the live catalogs (and the tab's own)
            // refresh at once, whatever the tab's cadence: the list must not wait minutes for its next round.
            if (returning) {
                val kinds = setOf(SportsLiveKind.EVENTS) + plan?.kinds.orEmpty()
                if (ageOf(kinds) >= RETURN_STALE_MS) refreshKinds(kinds)
            }
            if (plan == null) return@launch
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

    /**
     * The last build's state for a selection that differs from its own only in the focused card (live.20: a D-pad move
     * rebuilt every tab's scoping, chips and counts): the same lists, with [selection]'s card selected. Null when anything
     * else changed, or when the new focus changes the rows Live now keeps ([sportsLiveKeepFocused]: a card a load dropped
     * leaves once focus moves off it), which takes a full build.
     */
    private fun stateForFocus(
        pinned: Bucketed,
        endedKeys: Set<String>,
        selection: Selection,
        inputs: EndedInputs,
        mode: ScoresMode?,
        linkDead: Map<String, SportsLiveLinkVerdicts.Dead>,
        mySports: MySports,
        tick: Long
    ): SportsLiveUiState? {
        val last = lastBuild ?: return null
        if (last.pinned !== pinned || last.endedKeys !== endedKeys || last.inputs !== inputs || last.linkDead !== linkDead ||
            last.tick != tick || last.mode != mode || last.mySports != mySports
        ) {
            return null
        }
        if (last.selection.copy(itemId = null) != selection.copy(itemId = null)) return null
        val built = last.builtLiveNow
        if (built != null) {
            val focusedId = selection.itemId.takeIf { selection.tab == SportsLiveTab.LIVE_NOW }
            // Same rule as the full build below (BUG7 freeze); any difference falls back to the full build.
            val kept = keptLiveNow(selection, built, last.builtLiveCount, focusedId, thawed = false).items
                .let { listed -> sportsLiveKeepFocused(shownLiveNow, listed, miniPlayingNow) }
            val shown = SportsLiveHiddenTitles.filter(kept, hiddenNow)
            val shownBefore = shownLiveNow
            if (shown.size != shownBefore.size || shown.indices.any { shown[it] !== shownBefore[it] }) return null
        }
        val visible = last.state.visibleItems
        val selectedId = selection.itemId?.takeIf { id -> visible.any { it.id == id } } ?: visible.firstOrNull()?.id
        return last.state.copy(selectedItemId = selectedId)
    }

    /**
     * Live now as shown after a build of [built] (F6, TV QA BUG7; J1): while it holds focus nothing moves at all
     * ([sportsLiveFreeze]); once it has been shown this visit, it stays calm ([sportsLiveCalm]: rows keep their order, a
     * row a load dropped goes, new rows wait for "N new"); otherwise as built. The focused card is never taken away.
     * [liveCount]: [built]'s first rows that are live; calm keeps their order, but a game that ended (or came back) still
     * moves between the live rows and the Just ended strip, which comes last as built.
     */
    private fun keptLiveNow(selection: Selection, built: List<SportsLiveItem>, liveCount: Int, focusedId: String?, thawed: Boolean): SportsLiveFrozenList {
        if (!thawed) {
            if (selection.tab == SportsLiveTab.LIVE_NOW && selection.browsing) return sportsLiveFreeze(shownLiveNow, built)
            // Calm on another tab too (#143): Live now's kept board is what shows on return, so its rows keep their order.
            if (liveNowShown) {
                val calm = sportsLiveCalm(shownLiveNow, built)
                val liveIds = built.subList(0, liveCount.coerceIn(0, built.size)).mapTo(HashSet()) { it.id }
                val calmIds = calm.items.mapTo(HashSet()) { it.id }
                val items = calm.items.filter { it.id in liveIds } +
                    built.drop(liveCount).filter { it.id in calmIds && it.id !in liveIds }
                return calm.copy(items = sportsLiveKeepFocused(shownLiveNow, items, focusedId))
            }
        }
        return SportsLiveFrozenList(sportsLiveKeepFocused(shownLiveNow, built, focusedId), emptyList())
    }

    /** Each tab's My sports scope as last worked out (RC regression: Upcoming's 2,000+ games scoped on every build). */
    private val scopeMemo = HashMap<SportsLiveTab, ScopeMemo>()

    private class ScopeMemo(val items: List<SportsLiveItem>, val mySports: MySports, val stamp: Long, val scoped: List<SportsLiveItem>)

    /**
     * [items] scoped to [mySports]: the same list (by identity) with the same My sports and team directory gives the last
     * answer. Only from the state build, which runs one at a time.
     */
    private fun scopedOnce(tab: SportsLiveTab, items: List<SportsLiveItem>, mySports: MySports, stamp: Long): List<SportsLiveItem> {
        scopeMemo[tab]?.let { memo -> if (memo.items === items && memo.mySports == mySports && memo.stamp == stamp) return memo.scoped }
        return SportsLiveMySportsScope.scope(items, mySports).also { scopeMemo[tab] = ScopeMemo(items, mySports, stamp, it) }
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
        lastBuiltLiveNow = null
        lastBuiltLiveCount = 0
        val hasClaimed = bucketed.hasClaimed
        // A live game whose lookup found no streams is not over (#145): it stays where it is, dimmed as
        // "no streams yet", and is looked up again later. Games found over (#60: ESPN, or their addon) move
        // to the Just ended strip at the end: ESPN finals leave half an hour after they ended, estimated
        // ones stay (playable) until their addon drops them or six hours pass.
        val now = nowMs()
        var justEnded = emptyMap<String, EndedGame>()
        var heldBack = emptyList<SportsLiveItem>()
        var noStreams = emptySet<String>()
        var finals = emptySet<String>()
        excitementHistory.observe(inputs.scores.values, now)
        var whyTags = emptyMap<String, WhyTag>()
        var otherEvents = emptyList<SportsLiveItem>()
        // J1 (live.23): Live now shows its loading rows, not the saved list of an earlier visit, until this visit's load
        // (most addons answered) lands or HOLD_MS passed; then the whole list at once.
        val holdLiveNow = !bucketed.fromLoad && !selection.holdOver
        val tabs = bucketed.tabs.mapValues { (tab, allItems) ->
            if (tab == SportsLiveTab.LIVE_NOW && holdLiveNow) {
                emptyList<SportsLiveItem>()
            } else if (tab == SportsLiveTab.LIVE_NOW) {
                // H (live.23): listings that are no game and no event of their own leave the list for More live's Other.
                // With nothing picked in My sports there is no More live: they stay in the list, as before.
                val (tabItems, others) = if (mySports.isDefault) allItems to emptyList<SportsLiveItem>() else SportsLiveOtherEvents.split(allItems)
                otherEvents = SportsLiveHiddenTitles.filter(others, hiddenNow)
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
                // The one shared order (live.25, #225): the panel, its table guide, CH± and the dual picker take the same
                // (SportsLiveLineup). Followed teams first, league priority, matched to a feed inside the tier, then start;
                // no key reads a score, so the excitement badge above never moves a row and Show and Hide list alike.
                // Here, before the calm step (J1), not on the board: a match landing later never moves a shown row; the
                // order is applied on the first build, a thaw ("N new") and a re-rank.
                val ranked = SportsLiveLineup.order(live, mySports, scores, mode)
                val built = ranked + split.justEnded + overdueEnded.map { it.first } + linkEnded
                lastBuiltLiveNow = built
                lastBuiltLiveCount = ranked.size
                // The focused card is never taken from under the user: kept in place until focus moves on. While Live now
                // is browsed nothing moves at all (F6, TV QA BUG7): no row leaves, none re-orders, new ones wait for
                // "N new" on Refresh; once the user leaves it (or asks), the list is as built.
                val focusedId = selection.itemId.takeIf { selection.tab == SportsLiveTab.LIVE_NOW }
                // A follow or My sports change is the user's own: the list is ranked afresh, as "N new" does.
                val rankedFor = bucketed.followed to mySports
                val reranked = liveNowRankedFor.let { it != null && it != rankedFor }
                liveNowRankedFor = rankedFor
                val thawed = selection.thaw != thawApplied || reranked
                thawApplied = selection.thaw
                val kept = keptLiveNow(selection, built, ranked.size, focusedId, thawed).also { heldBack = it.heldBack }.items.let { listed ->
                    // The game playing in the mini player stays listed while it plays (BUG7: its row vanished).
                    sportsLiveKeepFocused(shownLiveNow, listed, miniPlayingNow)
                }
                // A title just hidden (#134) leaves at once, even with focus on it: the keep step must not bring it back.
                SportsLiveHiddenTitles.filter(kept, hiddenNow).also {
                    shownLiveNow = it
                    // J1: shown once this visit, Live now keeps its rows in place from now on; new ones wait for "N new".
                    if (selection.tab == SportsLiveTab.LIVE_NOW && it.isNotEmpty()) liveNowShown = true
                }
            } else {
                allItems
            }
        }
        // #146: My sports scopes Live now, Upcoming and Replays (never Channels): followed teams' games stay, games of
        // no identifiable sport go (#167). All sports lifts it on the tab it is picked on; the chips and counts follow My sports.
        // More live (user 2026-11-21) replaces All sports on Live now: a stored All sports there reads as My sports.
        val onLiveNow = selection.tab == SportsLiveTab.LIVE_NOW
        val allSports = selection.filter == SportsLiveUiState.ALL_SPORTS_FILTER && !onLiveNow
        val stamp = directoryStamp()
        val scopedTabs = tabs.mapValues { (tab, items) ->
            if (tab in SCOPED_TABS) scopedOnce(tab, items, mySports, stamp) else items
        }
        val fullItems = tabs[selection.tab].orEmpty()
        // #168: the listed games of unknown sport (shown under All sports, counted as hidden under My sports) are
        // looked up in the background, only while the section is on screen and only for Live now and Upcoming.
        if (screenVisible && (selection.tab == SportsLiveTab.LIVE_NOW || selection.tab == SportsLiveTab.UPCOMING)) identify(fullItems)
        val scopedItems = scopedTabs[selection.tab].orEmpty()
        // live.24: Upcoming games My sports leaves waiting are not sorted yet (All sports sorts them): counted, not listed.
        val waiting = if (selection.tab == SportsLiveTab.UPCOMING) bucketed.upcomingWaiting else 0
        val mySportsScoped = scopedItems.size < fullItems.size || waiting > 0
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
            .let { if (mySportsScoped && !onLiveNow) it + (SportsLiveUiState.ALL_SPORTS_FILTER to counted(fullItems).size + waiting) else it }
        // "★ My teams" leads whenever a followed team plays in the tab (never on Channels); chips with no
        // games hide, except the selected one, kept with 0 until the user moves (onUserMoved). All sports
        // follows it while My sports hides any of the tab's games (#146).
        val filter = selection.filter.takeUnless { onLiveNow && it == SportsLiveUiState.ALL_SPORTS_FILTER }
        val filters = sportsLiveShownFilters(values, counts, filter.takeIf { !allSports })
            .let { if (channels) SportsLiveChannelRegions.ordered(it) else it }
            .let { shown -> if ((mySportsScoped || allSports) && !onLiveNow) withAllSports(shown) else shown }
        // More live (user 2026-11-21): under My sports, Live now ends with one row holding the live games it leaves out
        // (other sports, J3's unplaced games) and the listings that are no game (H), by sport; OK opens them.
        val moreLiveGroups = if (onLiveNow && filter == null && !mySports.isDefault) {
            val scopedKeys = scopedItems.mapTo(HashSet()) { it.streamKey }
            SportsLiveMoreLive.groups(counted(fullItems).filter { it.streamKey !in scopedKeys }, otherEvents)
        } else {
            emptyList()
        }
        val moreLiveOpen = selection.moreLiveOpen && moreLiveGroups.isNotEmpty()
        val visible = when (filter) {
            null, SportsLiveUiState.ALL_SPORTS_FILTER -> if (moreLiveOpen) moreLiveGroups.flatMap { it.items } else tabItems
            SportsLiveUiState.MY_TEAMS_FILTER -> tabItems.filter { it.followed }
            else -> tabItems.filter { filterOf(it) == filter }
        }
        val selectedId = selection.itemId?.takeIf { id -> visible.any { it.id == id } }
            ?: visible.firstOrNull()?.id
        // "N new" (TV QA Q5): the waiting rows this tab and filter would list.
        val newList = if (heldBack.isEmpty() || selection.tab != SportsLiveTab.LIVE_NOW) {
            emptyList<SportsLiveItem>()
        } else {
            val scoped = if (allSports) heldBack else SportsLiveMySportsScope.scope(heldBack, mySports)
            when (filter) {
                null, SportsLiveUiState.ALL_SPORTS_FILTER -> scoped
                SportsLiveUiState.MY_TEAMS_FILTER -> scoped.filter { it.followed }
                else -> scoped.filter { filterOf(it) == filter }
            }
        }
        val newRows = newList.size
        // Sorting model (live.24): "2 new · Lakers game" when a followed team's game is among them.
        val newFollowedTeam = newList.firstOrNull { it.followed }?.let { game ->
            bucketed.followed.orEmpty().firstOrNull { team -> SportsLiveTeams.followsAnySource(team, game) }?.name
        }
        return SportsLiveUiState(
            // Only a load with no data yet shows as loading, so a refresh does not blank the list.
            isLoading = hasClaimed && !bucketed.hasLoad && selection.isRefreshing,
            // #214: until the first load of this visit lands (or fails), an empty tab is loading, never "nothing here".
            firstLoadPending = (hasClaimed && !bucketed.fromLoad && selection.failures.isEmpty() &&
                (selection.isRefreshing || selection.lastRefreshedMs == null)) || selection.tab in bucketed.sortingTabs,
            hasClaimedCatalogs = hasClaimed,
            tabs = if (allSports) scopedTabs + (selection.tab to fullItems) else scopedTabs,
            selectedTab = selection.tab,
            filters = filters,
            selectedFilter = filter,
            mySportsScoped = selection.tab in SCOPED_TABS && !mySports.isDefault,
            // #167's "hidden by My sports" line is More live's row now (user 2026-11-21).
            hiddenByMySports = 0,
            filterCounts = counts,
            visibleItems = visible,
            selectedItemId = selectedId,
            failures = selection.failures,
            lastRefreshedMs = selection.lastRefreshedMs,
            sources = selection.sources,
            justEnded = justEnded,
            noStreams = noStreams,
            nowMs = now,
            whyTags = whyTags,
            // Stale-while-revalidate (live.20 B1): a list over 5 minutes old shown while the refresh runs says when it is from.
            staleListAtMs = bucketed.listAtMs?.takeIf { selection.isRefreshing && now - it >= STALE_MARK_MS },
            newLiveRows = newRows,
            newLiveFollowedTeam = newFollowedTeam,
            moreLive = moreLiveGroups,
            moreLiveOpen = moreLiveOpen,
            moreLiveOther = otherEvents.mapTo(HashSet()) { it.streamKey },
            // The add-sport card: on Live now's own list (not a chip, not More live's view) while shown.
            addSportPrompt = selection.addSport?.takeIf {
                selection.addSportShown && onLiveNow && filter == null && !moreLiveOpen && !mySports.isDefault
            },
            upcomingPendingDates = bucketed.upcomingPending
        ).also { state ->
            if (!state.isLoading && hasClaimed && firstStateLogged.compareAndSet(false, true)) {
                SportsLiveDebugTiming.log {
                    "sportsOpen stage=firstList t=${sinceOpenMs()} restored=${bucketed.listAtMs != null && selection.lastRefreshedMs == null} " +
                        "visible=${state.visibleItems.size}"
                }
            }
        }
    }

    private companion object {
        /** How long Live now's list shows before Upcoming is built ahead (#143): never during the opening rush. */
        const val PREWARM_DELAY_MS = 4_000L

        /** A built-ahead board's logo revision: no real build has it, so the board is only shown, never reused. */
        const val PREWARM_LOGO_REVISION = Int.MIN_VALUE
        /** How long the team directory must be still before the load is merged again (live.18). */
        const val REBUCKET_DEBOUNCE_MS = 2_000L
        const val MINUTE_MS = 60_000L
        const val FOCUS_PROBE_DELAY_MS = 700L
        const val ENDED_TICK_MS = 30_000L

        /** The trigger a row's Report a problem writes in the report. */
        const val REPORT_TRIGGER = "Hold OK › More… › Report a problem (row)"

        /** After a tip-off, how long before the list is bucketed again (live.20 C1): clear of the start itself. */
        const val KICKOFF_SLACK_MS = 1_000L

        /** Coming back to the section, a list this old refreshes at once (live.20). */
        const val RETURN_STALE_MS = 3 * 60_000L

        /**
         * #143, tunable on the TV (NuvioTiming stage=pinned / stage=state / stage=board counts): a burst of learned logos
         * or of score updates rebuilds the lists once, this long after its first update.
         */
        const val LOGO_SETTLE_MS = 750L

        /**
         * RC regression (#143), TV-tunable: what score updates, the team directory and learned logos may cost. Each state
         * build is 1-2 s, a bucketing 4-6 s and a re-pin 3-4 s on the onn, so each window is longer than the work it gates.
         */
        const val STATE_SCORES_SETTLE_MS = 3_000L
        const val BOARD_SCORES_SETTLE_MS = 1_000L
        const val REBUCKET_GAP_MS = 20_000L
        const val PIN_SETTLE_MS = 8_000L

        /** A shown list this old is marked "Updated ‹time›" while a refresh runs (live.20 B1); the 3-minute Live now refresh never is. */
        const val STALE_MARK_MS = 5 * 60_000L

        /** live.24: Upcoming never sorts further ahead than this many days, however far the viewer goes. */
        const val MAX_UPCOMING_DAYS = 60

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

        /**
         * Back on a tab after this long away, it is stale: Replays refreshes on opening, and Results opens
         * afresh on Today (live.20: a quick hop keeps the chosen day, watching a game does not).
         */
        const val OPEN_STALE_MS = 10 * 60_000L

        fun of(tab: SportsLiveTab): SportsLiveRefreshPlan? = when (tab) {
            SportsLiveTab.LIVE_NOW -> SportsLiveRefreshPlan(setOf(SportsLiveKind.EVENTS), LIVE_NOW_MS)
            SportsLiveTab.UPCOMING -> SportsLiveRefreshPlan(setOf(SportsLiveKind.EVENTS), UPCOMING_MS)
            SportsLiveTab.REPLAYS -> SportsLiveRefreshPlan(setOf(SportsLiveKind.REPLAY), everyMs = null, openStaleMs = OPEN_STALE_MS)
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

/**
 * What every kept board was built for (#143, [SportsLiveBoardCache.scopeTo]): the profile (F3: two profiles with the same
 * catalogs and settings never share a board) and its catalogs and settings.
 */
internal data class SportsLiveBoardScope(
    val profileId: Int?,
    val claimed: Set<String>,
    val mySports: MySports,
    val hidden: Set<String>,
    val followed: List<FollowedTeam>,
    val mode: ScoresMode?,
    val protection: SportsLiveBoardCache.ProtectionScope = SportsLiveBoardCache.ProtectionScope.NONE
)
