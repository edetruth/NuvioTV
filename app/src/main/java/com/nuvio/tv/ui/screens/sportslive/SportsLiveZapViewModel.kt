package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.SportsLiveToastKind
import com.nuvio.tv.core.sportslive.SportsLiveToasts
import com.nuvio.tv.core.sportslive.scores.FollowTarget
import com.nuvio.tv.core.sportslive.scores.WatchingNow
import com.nuvio.tv.core.sportslive.scores.FollowTargets
import com.nuvio.tv.core.sportslive.scores.GameSection
import com.nuvio.tv.core.sportslive.SportsLiveSportInference
import com.nuvio.tv.core.sportslive.SportsLiveDupes
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.sportslive.identify.SportsLiveTeamIdentity
import com.nuvio.tv.core.sportslive.identify.TeamIdentifier
import com.nuvio.tv.core.sportslive.identify.TeamIdentityBook
import com.nuvio.tv.core.sportslive.SportsLiveMySportsScope
import com.nuvio.tv.core.sportslive.SportsLiveTriedGames
import com.nuvio.tv.core.sportslive.SportsLiveDataSaver
import com.nuvio.tv.core.sportslive.SportsLiveReadyMark
import com.nuvio.tv.core.sportslive.SportsLiveGameReadiness
import com.nuvio.tv.core.sportslive.scores.MySports
import com.nuvio.tv.data.local.SportsLivePreferencesDataStore
import com.nuvio.tv.core.sportslive.scores.TeamDirectory
import com.nuvio.tv.core.sportslive.displayName
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.debrid.DirectDebridResolver
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sportslive.EndedGame
import com.nuvio.tv.core.sportslive.GameEndReason
import com.nuvio.tv.core.sportslive.SportsLiveEndCountdown
import com.nuvio.tv.core.sportslive.SportsLiveEndMove
import com.nuvio.tv.core.sportslive.SportsLiveMyGames
import com.nuvio.tv.core.sportslive.SportsLiveStillWatching
import com.nuvio.tv.core.sportslive.alerts.RemindersState
import com.nuvio.tv.core.sportslive.SportsLiveGameEndAction
import com.nuvio.tv.core.sportslive.SportsLiveOkOnLive
import com.nuvio.tv.core.sportslive.SportsLiveGameWatch
import com.nuvio.tv.core.sportslive.alerts.GameReminders
import com.nuvio.tv.core.sportslive.alerts.SportsLiveAlerts
import com.nuvio.tv.ui.screens.sportslive.dual.SportsLiveNextGame
import com.nuvio.tv.ui.screens.sportslive.dual.sportsLiveNoStreamIds
import com.nuvio.tv.ui.screens.sportslive.dual.sportsLiveSavedForLater
import com.nuvio.tv.core.sportslive.scores.ScoreChips
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.SportsLiveEndedGames
import com.nuvio.tv.core.sportslive.SportsLiveSharedBuckets
import com.nuvio.tv.core.sportslive.SportsLiveFullTimeAnnouncements
import com.nuvio.tv.core.sportslive.SportsLiveFullTimeToasts
import com.nuvio.tv.core.sportslive.SportsLiveLinkVerdicts
import com.nuvio.tv.core.sportslive.SportsLiveWorkingLinks
import com.nuvio.tv.core.sportslive.scores.LiveScoresRepository
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.core.sportslive.scores.TeamLogoBook
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.repository.SportsLiveRepository
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.ui.screens.sportslive.dual.SportsLiveDualPick
import com.nuvio.tv.ui.screens.sportslive.dual.SportsLiveDualSession
import com.nuvio.tv.ui.screens.sportslive.dual.SportsLiveSecondGame
import com.nuvio.tv.ui.screens.sportslive.dual.offersDual
import com.nuvio.tv.ui.screens.sportslive.dual.sourceKeys
import com.nuvio.tv.ui.screens.stream.StreamPlaybackInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Where a zap goes. [playback] is the Player route's stream; null when the stream needs the
 * Stream screen's preparation (server, YouTube or debrid resolve), so the item's Stream screen opens.
 * [autoPicked]: the stream was picked by autoplay settings for a merged game, so the player's
 * source fallback (which includes the other addons' streams) applies to it.
 */
data class SportsLiveZapTarget(
    val item: SportsLiveItem,
    val playback: StreamPlaybackInfo?,
    val returnToHomeOnBack: Boolean,
    val autoPicked: Boolean = false,
    /** "Choose stream" (#140): the item's Stream screen opens to pick from, with no autoplay. */
    val manualSelection: Boolean = false,
    /** Live now's Resume (#148): the player starts at the live edge, never at a saved position. */
    val liveEdge: Boolean = false
)

sealed interface SportsLiveZapEvent {
    data class Navigate(val target: SportsLiveZapTarget) : SportsLiveZapEvent
    data class NoStream(val name: String) : SportsLiveZapEvent

    /** A zap skipped game [name], which had no stream, and went on in the same direction (#138). */
    data class Skipped(val name: String) : SportsLiveZapEvent
}

/** Zap state of one Player back stack entry, whose route arguments it reads. */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class SportsLiveZapViewModel internal constructor(
    savedStateHandle: SavedStateHandle,
    resolver: SportsLiveZapResolver,
    private val directDebridResolver: DirectDebridResolver,
    profileManager: ProfileManager,
    private val repo: SportsLiveRepository,
    private val liveScores: LiveScoresRepository,
    // Where the panel's list is built and grouped; tests pass their dispatcher, so it runs in virtual time.
    computeDispatcher: CoroutineDispatcher,
    // The stream this player plays, handed to dual screen (#65); cleared when the player leaves.
    private val workingLinks: SportsLiveWorkingLinks = SportsLiveWorkingLinks.shared,
    // The persistent logo map the rows draw from; observed, so logos learned while the panel is open appear.
    private val logos: TeamLogoBook = TeamLogoBook.shared,
    // Games whose link check found them over (#60): "Next live game" never goes to one while that stands.
    private val linkVerdicts: SportsLiveLinkVerdicts = SportsLiveLinkVerdicts.shared,
    // My sports (#146): the panel and CH+/CH- list only its games, unless the panel's All sports is picked.
    private val mySports: Flow<MySports> = flowOf(MySports.DEFAULT),
    // Games tried and failed this session (#138 follow-up): Next live game and the CH± auto-skip never go back to one.
    private val tried: SportsLiveTriedGames = SportsLiveTriedGames.shared,
    // "When a game ends" (#161): Move to the next game, Ask, or Stay.
    private val gameEndAction: Flow<SportsLiveGameEndAction> = flowOf(SportsLiveGameEndAction.DEFAULT),
    // The nothing-live state's Remind me on the upcoming game (#161): reminded now?
    private val toggleReminder: suspend (SportsLiveItem) -> Boolean = { false },
    // What every sports player knows about its game (#161): its snapshot, and a stream stopping at the game's end.
    private val gameWatch: SportsLiveGameWatch = SportsLiveGameWatch.shared,
    // #147, 1b: "▶ Ready" / "No stream yet", shown on the panel's rows and read by Next live game. The panel checks no
    // game itself (1b: only my games are checked ahead, by [SportsLiveReadyKeeper]).
    private val readiness: SportsLiveGameReadiness = SportsLiveGameReadiness.shared,
    // live.20: the panel's keys hint, claimed per opening (the profile's first few show it).
    private val claimPanelKeysHint: suspend () -> Boolean = { false },
    // live.20 B2: looks up a focused panel row and the zap neighbours ahead of OK and CH±; null (tests): none.
    prefetchHooks: SportsLivePrefetchHooks? = null,
    // Data saver: nothing is looked up ahead while it is on (B2).
    dataSaver: Flow<Boolean> = flowOf(false),
    // #161, research 6: the end card moves by itself only into my games (followed teams, reminders) showing "▶ Ready".
    private val reminders: Flow<RemindersState> = flowOf(RemindersState()),
    // #161: "Are you still watching?" after a few automatic moves or hours with no key press.
    private val stillWatching: SportsLiveStillWatching = SportsLiveStillWatching.shared,
    // "OK on live" (research Q19): Show controls (default) or Pause.
    okOnLive: Flow<SportsLiveOkOnLive> = flowOf(SportsLiveOkOnLive.DEFAULT),
    // Q18 (#85): "Scores ahead? +15 s" for a source (addon id); its new seconds.
    private val nudgeScores: suspend (String) -> Int = { 0 },
    // live.20 (#168): the panel's games whose sport is unknown are looked up too, also when the section was never opened.
    private val identify: (List<SportsLiveItem>) -> Unit = {}
) : ViewModel() {

    @Inject constructor(
        savedStateHandle: SavedStateHandle,
        resolver: SportsLiveZapResolver,
        directDebridResolver: DirectDebridResolver,
        profileManager: ProfileManager,
        repo: SportsLiveRepository,
        liveScores: LiveScoresRepository,
        // My sports (#146); null in tests that do not scope.
        prefs: SportsLivePreferencesDataStore? = null,
        // The team lookup (#168); null in tests.
        identifier: TeamIdentifier? = null
    ) : this(
        savedStateHandle, resolver, directDebridResolver, profileManager, repo, liveScores, Dispatchers.Default,
        mySports = prefs?.mySports ?: flowOf(MySports.DEFAULT),
        gameEndAction = prefs?.gameEndAction ?: flowOf(SportsLiveGameEndAction.DEFAULT),
        toggleReminder = { item ->
            prefs?.updateReminders { GameReminders.toggle(it, item, System.currentTimeMillis()) }?.isReminded(item) == true
        },
        claimPanelKeysHint = { prefs?.claimPanelKeysHint() == true },
        prefetchHooks = SportsLivePrefetchHooks.of(resolver),
        dataSaver = prefs?.dataSaver ?: flowOf(false),
        reminders = prefs?.reminders ?: flowOf(RemindersState()),
        okOnLive = prefs?.okOnLive ?: flowOf(SportsLiveOkOnLive.DEFAULT),
        nudgeScores = { source -> prefs?.nudgeScores(source) ?: 0 },
        identify = { items -> identifier?.offer(items) }
    )

    /** "OK on live" is Pause (research Q19): a bare OK pauses a live game at once, as Google's TV-PC says. */
    val okPausesLive: StateFlow<Boolean> = okOnLive.map { it == SportsLiveOkOnLive.PAUSE }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val contentId: String? = savedStateHandle.get<String>("contentId").also { id ->
        // A player for other content ends the session; zap targets match because the
        // controller moves the session before navigating.
        SportsLiveZapSessions.keepOnlyFor(id)
    }

    // The player's profile, resolved the way PlayerViewModel resolves it.
    private val profileId: Int = savedStateHandle.get<String>("profileId")?.toIntOrNull()
        ?: profileManager.activeProfileId.value

    private val _events = Channel<SportsLiveZapEvent>(Channel.BUFFERED)
    val events: Flow<SportsLiveZapEvent> = _events.receiveAsFlow()

    private val controller = PlayerZapController(
        scope = viewModelScope,
        resolve = resolver::resolve,
        onNavigate = { item, stream ->
            val target = targetFor(item, stream)
            // The player zapped to gets the target's other addons' streams; this player's go.
            if (contentId != item.id) SportsLiveExtraSources.clear(contentId)
            SportsLiveExtraSources.register(item.id, item)
            SportsLiveZapSessions.markZapArrival()
            _events.send(SportsLiveZapEvent.Navigate(target))
        },
        onNoStream = { item ->
            tried.noteFailed(item)
            _events.trySend(SportsLiveZapEvent.NoStream(item.displayName))
        },
        onSkipped = { item ->
            tried.noteFailed(item)
            _events.trySend(SportsLiveZapEvent.Skipped(item.displayName))
        },
        endedKeys = { repo.endedKeys.value },
        triedIds = { tried.ids() },
        // #142: CH+/CH- walk the panel's own list.
        lineup = { lineup.value?.rows },
        // B2: a zap on a game being prefetched waits for that prefetch instead of skipping it.
        prefetching = { item -> prefetchHooks?.isPrefetching?.invoke(item) == true }
    )

    /** True when this player was opened with a zap session for its content; only then is the overlay shown. */
    val hasSession: Boolean = SportsLiveZapSessions.activeFor(contentId) != null

    // live.20 B2: streams resolved before OK or CH± asks (a panel row rested on, the games ▲ and ▼ go to).
    private val prefetcher = prefetchHooks?.takeIf { hasSession }?.let { SportsLiveStreamPrefetcher(viewModelScope, it) }
    private var neighbourJob: Job? = null

    @Volatile
    private var dataSaverOn = false

    init {
        if (prefetcher != null) viewModelScope.launch { dataSaver.collect { dataSaverOn = it } }
    }

    /** The prefetcher, unless Data saver is on (B2). */
    private val activePrefetcher: SportsLiveStreamPrefetcher?
        get() = prefetcher?.takeIf { SportsLiveDataSaver.prefetchAllowed(dataSaverOn) }

    init {
        // Watch from outside Sports & Live (#173 follow-up): the shared list may not be loaded; the panel fills once it is.
        if (hasSession && repo.latest.value == null) {
            viewModelScope.launch {
                try {
                    repo.load(force = false, includeChannels = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The panel shows Now playing alone; opening it tries again.
                }
            }
        }
    }

    // A player reached by a zap shows the banner for BANNER_MS.
    private val arrival = MutableStateFlow(
        SportsLiveZapSessions.activeFor(contentId)?.takeIf { SportsLiveZapSessions.consumeZapArrival() }
    )

    /**
     * The zap banner (#76): "Switching to …" while a stream is looked up, then the arrival banner, with
     * logos, the score chip under the Scores setting (no score until it is read) and the next game.
     */
    val banner: StateFlow<SportsLiveZapBanner?> = combine(
        combine(controller.resolving, controller.looking) { resolving, looking -> resolving to looking },
        arrival,
        liveScores.scores,
        liveScores.mode.map<ScoresMode, ScoresMode?> { it }.onStart { emit(null) },
        repo.endedKeys
    ) { (resolving, looking), arrived, scores, mode, ended ->
        resolving?.banner(switching = true, scores = scores, mode = mode, endedKeys = ended, known = logos)
            // #158: a CH± zap's slow lookup reads "Looking for streams…" with Skip.
            ?.let { banner -> if (looking != null && looking.streamKey == resolving.current.streamKey) banner.copy(looking = true) else banner }
            ?: arrived?.banner(switching = false, scores = scores, mode = mode, endedKeys = ended, known = logos)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** OK (or Skip) on the Looking for streams card (#158): the zap moves on now. False when no card shows. */
    fun skipLookup(): Boolean = controller.skip()

    // The session when the panel opened; null while it is closed.
    private val panelSession = MutableStateFlow<SportsLiveZapSession?>(null)

    // The panel's chip (null is All): the session's picked chip, or All until one is picked (#68). Kept while the
    // panel is closed, since CH+/CH- walk the panel's list under it (#142).
    private val panelChip = MutableStateFlow(SportsLiveZapSessions.activeFor(contentId)?.let(::openingChip))

    private val _panelOpen = MutableStateFlow(false)
    val panelOpen: StateFlow<Boolean> = _panelOpen.asStateFlow()

    // live.20: "OK switch · Hold OK more · Back close" under the panel, for the profile's first few openings only.
    private val _panelKeysHint = MutableStateFlow(false)
    val panelKeysHint: StateFlow<Boolean> = _panelKeysHint.asStateFlow()

    // The panel's games are tracked for live scores only while it is open: the section stops its own
    // tracking when the player covers it (ON_STOP), so the player has its own scope.
    private val scoreTracker = LiveScoresTracker(LiveScoresRepository.SCREEN_PLAYER_PANEL, liveScores)

    // Hide until focused: a row's score shows after it has been focused for a second.
    private val reveal = ResultSpoilerReveal(viewModelScope)

    // The focused panel row (Hide until focused reveal).
    @Volatile
    private var focusedRowKey: String? = null

    // #164: the open panel's frozen order and the chip it was taken under; null while closed.
    @Volatile
    private var frozenOrder: FrozenPanelOrder? = null
    @Volatile
    private var frozenChip: String? = null

    // When a key was last pressed in the open panel: after PANEL_IDLE_REORDER_MS without one, the order is taken afresh.
    @Volatile
    private var lastPanelKeyAtMs = 0L

    /**
     * The live games panel's one ordered list (#142): the list the panel shows and CH+/CH- walk, kept current
     * for a player with a session, open or not. Grouped by V2 group with followed teams first ([panelLineup]),
     * under the panel's chip, its order frozen while the panel is open (#164). Ticks only while the panel is open
     * (while closed, an order change comes with the next list or score); scores are polled only while open.
     */
    private val lineup: StateFlow<PanelLineup?> = if (!hasSession) {
        MutableStateFlow(null)
    } else {
        // A game's teams identified in the background (#168, by the section) list it under its sport here too, and a
        // team list landing merges copies of one game by their teams (live.18): both build the list again, 2 s after
        // the last change and only once the panel is closed (an open panel keeps its snapshot).
        val rebucket = SportsLiveSharedBuckets.stampChanges()
            .debounce(REBUCKET_DEBOUNCE_MS)
            .transform<Any, Unit> {
                _panelOpen.first { open -> !open }
                emit(Unit)
            }
            .onStart { emit(Unit) }
        val all = combine(
            repo.latest, repo.claimedCatalogs, repo.endedKeys, repo.followedTeams, rebucket
        ) { load, claimed, ended, followed, _ ->
            liveGamesPanelItems(
                items = load?.items.orEmpty(),
                claimedKeys = claimed.mapTo(HashSet()) { it.key },
                endedKeys = ended,
                followed = followed,
                nowMs = System.currentTimeMillis(),
                addonOrder = claimed.map { it.addon.id }.distinct(),
                // The section's and Home's sorting of this load (live.20): no second merge of the same games.
                load = load
                // Unrouted two-team games take their teams' league here too (P4), as in the section's lists.
            ).map { SportsLiveTeamIdentity.identified(SportsLiveSportInference.infer(it, TeamDirectory.shared)) }
                // Debug builds: rows that look like one game but did not merge (live.18).
                .also { if (BuildConfig.IS_DEBUG_BUILD) SportsLiveDupes.log("PANEL", it, System.currentTimeMillis()) }
                .also(identify) to followed
        }.flowOn(computeDispatcher)
        // The games under the chip are the ones listed and tracked: My sports' (#146), or every game under All sports.
        // Scoped again when the team directory changes (#159: a league's team list landing tells games apart).
        val items = combine(all, panelChip, mySports, logos.revision) { (list, followed), chip, sports, _ ->
            val scoped = SportsLiveMySportsScope.scope(list, sports)
            val base = if (chip == SportsLiveUiState.ALL_SPORTS_FILTER) list else scoped
            PanelList(list, chip, filterPanelItems(base, chip), followed, scoped)
        }
        // Ticks while the panel is open, so a grace or an estimate running out regroups its games.
        // A logo learned while open (a score feed answering, the map loading) redraws the rows.
        val ticks = _panelOpen.flatMapLatest { open -> if (open) panelTicks() else flowOf(Unit) }
        val ticking = combine(items, ticks, logos.revision, liveScores.logosLoaded) { panelList, _, _, loaded -> panelList to loaded }
        combine(ticking, liveScores.scores, liveScores.mode, reveal.revealed, liveScores.unmatched) { (panelList, loaded), scores, scoresMode, revealed, unmatched ->
            val list = panelList.shown
            val nowMs = System.currentTimeMillis()
            // Ended games (#60) go to the last group; ESPN finals leave the panel half an hour on.
            val canEstimate = SportsLiveEndedGames.estimateGate(scoresMode == ScoresMode.OFF, unmatched)
            val split = SportsLiveEndedGames.shared.split(list, scores, nowMs, canEstimate)
            // The chips count live, zappable games (#145): finals and just ended games of every chip are left out.
            val allSplit = if (list === panelList.all) split else SportsLiveEndedGames.shared.split(panelList.all, scores, nowMs, canEstimate)
            val splitFinished = allSplit.ended.keys + allSplit.finals.keys
            // A final in its grace is grouped (and chipped) as the final it is, never as live (C3).
            // Spoiler protection (#151): a kept Just ended final is stamped too, as on Live Now's board.
            val protection = liveScores.protection.value
            val boardScores = protection.stampAll(split.scoresWithFinals(scores))
            val ended = split.ended.mapValues { (_, game) -> game.event?.let { game.copy(event = protection.stamp(it)) } ?: game }
            val grouped = groupPanelRows(split.live + split.justEnded, boardScores, scoresMode, revealed, nowMs, ended, logos)
            // #164: while the panel is open its order stays as it opened (rows update in place, new ones go to the end of
            // their group, gone ones stay dimmed); a new chip or two idle minutes take the fresh order. Closed: fresh.
            val open = _panelOpen.value
            val keep = frozenOrder?.takeIf {
                open && frozenChip == panelList.chip && nowMs - lastPanelKeyAtMs < PANEL_IDLE_REORDER_MS
            }
            val (rows, frozen) = freezePanelLineup(panelLineup(grouped), keep)
            if (open) {
                frozenOrder = frozen
                frozenChip = panelList.chip
                // Idle: the fresh order was just taken, and is kept from here.
                if (keep == null) lastPanelKeyAtMs = nowMs
            } else {
                frozenOrder = null
            }
            // The rows CH± passes over count as finished too, so the chip's count is the banner's "of N".
            val finished = splitFinished + rows.filterNot { it.zappable }.map { it.item.streamKey }
            PanelLineup(panelList, rows, scoresMode, boardScores, ended, revealed, nowMs, loaded, finished)
        }.flowOn(computeDispatcher)
            // A player whose sources cannot be read keeps the session's own order.
            .catch { }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    }

    /**
     * The live games panel while it is open: [lineup]'s rows, each drawn as the Scoreboard draws it (#83).
     * Nothing is shown before the kept logo map is read (I3): known teams never show as letters first; nor
     * before the Scores setting is read. Its games are tracked for scores while it shows.
     */
    val panel: StateFlow<SportsLiveZapPanelState?> = combine(lineup, panelSession) { current, session ->
        if (session == null || current == null || !current.logosLoaded) {
            null
        } else {
            val playingKey = session.current.streamKey
            // #140: Now playing is pinned above the list; a game the chip or My sports leaves out gets its own row.
            val nowRow = current.rows.firstOrNull { it.item.streamKey == playingKey }
                ?: groupPanelRows(
                    listOf(current.list.all.firstOrNull { it.streamKey == playingKey } ?: session.current),
                    current.boardScores, current.mode, current.revealed, current.nowMs, current.ended, logos
                ).firstOrNull()
            val drawn = if (nowRow == null || nowRow in current.rows) current.rows else current.rows + nowRow
            val state = SportsLiveZapPanelState(
                rows = current.rows,
                currentKey = playingKey,
                chips = panelChips(current.list.all, current.list.chip, current.list.scoped),
                chip = current.list.chip,
                allItems = current.list.all,
                counts = panelChipCounts(
                    current.list.all.filterNot { it.streamKey in current.finished },
                    current.list.scoped.filterNot { it.streamKey in current.finished }
                ),
                // #83: each row drawn as the Scoreboard draws it.
                boards = panelBoards(drawn, current.boardScores, current.mode, current.revealed, current.nowMs, current.ended, current.list.followed)
                    .associateBy { it.board.item.streamKey },
                boxScores = current.rows
                    .filter { panelRowOk(it, strictGame(current, it.item)) == PanelRowOk.BOX_SCORE }
                    .mapTo(HashSet()) { it.item.streamKey },
                nowPlayingRow = nowRow,
                // #167: under My sports (All picked), the list ends with the live games it hides, one press from All sports.
                hiddenByMySports = if (current.list.chip == null) {
                    (current.list.all.count { it.streamKey !in current.finished } -
                        current.list.scoped.count { it.streamKey !in current.finished }).coerceAtLeast(0)
                } else {
                    0
                },
                loading = repo.latest.value == null
            )
            // Laid out here, off the main thread.
            state.guide
            ShownPanel(current, state, session)
        }
    }.flowOn(computeDispatcher).map { shown ->
        shown ?: return@map null
        // A list built before closePanel can arrive after it: it must not become the tracker's cards, or
        // the next open would start tracking this session's list.
        if (panelSession.value !== shown.session) return@map null
        // Under Off too: only the start of games listed live is read then, never a score (live.20, a wrong LIVE).
        // The game playing too, when the chip or My sports leaves it out: its pinned row gets its score (live.20).
        scoreTracker.update(panelTrackedCards(shown.lineup.list.shown, shown.state.nowPlayingRow?.item), enabled = true)
        shown.state
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * The open panel's "▶ Ready" / "No stream yet" marks (#147, 1b), by stream key; none for a game with no mark.
     * "▶ Ready" only on my games' rows (BUG-F): a zap neighbour's prefetch verifies links without marking its row.
     */
    val panelReadiness: StateFlow<Map<String, SportsLiveReadyMark>> = combine(panel, readiness.marks, readiness.mine) { state, _, _ ->
        state?.rows.orEmpty().mapNotNull { row -> readiness.rowOf(row.item)?.let { row.item.streamKey to it } }.toMap()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    // Hide until focused: rows reveal only once the user has pressed a key in the panel, so opening it
    // (focus lands on the game playing) never reveals a score by itself.
    private var panelKeyPressed = false
    /** The focused panel row's stream key, or null when no row is focused (Hide until focused reveal). */
    fun onPanelRowFocus(key: String?) {
        focusedRowKey = key
        if (panelKeyPressed) reveal.onFocus(key)
        // B2: a row rested on is looked up, so OK on it starts at once; never the game playing.
        activePrefetcher?.onFocus(
            key?.takeIf { it != nowPlaying?.streamKey }
                ?.let { focused -> lineup.value?.rows?.firstOrNull { it.item.streamKey == focused }?.item }
                ?.takeIf(::sportsLiveAutoPlayEligible)
        )
    }

    /** Panel rows revealed under Hide until focused (for tests). */
    internal val revealedRows: Set<String> get() = reveal.revealed.value

    /** A key was pressed inside the panel: from now on a row focused for a second reveals its score. */
    fun onPanelKey(keyCode: Int = 0) {
        lastPanelKeyAtMs = System.currentTimeMillis()
        // Research 8: Info brings the keys hint back, whenever the viewer wants it.
        if (keyCode == android.view.KeyEvent.KEYCODE_INFO) _panelKeysHint.value = true
        if (panelKeyPressed) return
        panelKeyPressed = true
        reveal.onFocus(focusedRowKey)
    }

    private val shortPressKeys = SportsLiveShortPressKeys()

    // The game this player plays, for "Ended while you're watching" (#60).
    internal val nowPlaying: SportsLiveItem? = SportsLiveZapSessions.activeFor(contentId)?.current

    // Only the game playing is scored for the toast, while the player is started and Scores is not Off.
    private val nowTracker = LiveScoresTracker(LiveScoresRepository.SCREEN_PLAYER_NOW, liveScores)

    // ESPN's delayed view showed the game playing in play: only then may it say it ended here.
    private var nowSeenLive = false

    // The Scores setting as the game playing was last scored: the end card's title follows it.
    @Volatile
    private var nowMode: ScoresMode? = null

    // "When a game ends" as last read (#161).
    @Volatile
    private var endAction: SportsLiveGameEndAction = SportsLiveGameEndAction.DEFAULT

    // The reminders as last read: which games are mine for the end card's automatic move (#161).
    @Volatile
    private var remindersNow: RemindersState = RemindersState()

    private val _endCard = MutableStateFlow<SportsLiveEndCard?>(null)

    /**
     * The end-of-game card (#161), one end-of-game experience in place of the full-time toast (#60): raised
     * when ESPN's delayed view says the game playing is over, or when its stream stopped as the game ended
     * ([SportsLiveGameWatch.ends]: the recovery ladder never runs then). The overlay shows it when nothing
     * else is up and says so ([onEndCardVisible]): only then is an ESPN final claimed
     * ([SportsLiveFullTimeAnnouncements], shared with the followed-team alerts, #42), and only then does its
     * 10 s Up next countdown start. Under Stay, while the stream still plays, it is a toast only.
     */
    val endCard: StateFlow<SportsLiveEndCard?> = _endCard.asStateFlow()
    private var endJob: Job? = null
    private val countdown = SportsLiveEndCountdown()

    // The player is started: only then is the game playing scored and its end looked for.
    private val playerStarted = MutableStateFlow(false)

    // The card's ESPN final was claimed (it showed), and the card is on screen now.
    private var endClaimed = false
    private var endVisible = false

    // The ESPN event a card was raised for: one card per game per player.
    private var endEventId: String? = null

    init {
        if (arrival.value != null) {
            viewModelScope.launch {
                delay(BANNER_MS)
                arrival.value = null
            }
        }
        viewModelScope.launch { gameEndAction.collect { endAction = it } }
        viewModelScope.launch { reminders.collect { remindersNow = it } }
        nowPlaying?.let { item ->
            gameWatch.watch(item)
            viewModelScope.launch {
                // Paused while the player is stopped: nothing is scored or raised behind Home.
                // The directory moving re-places the game (live.20): an untagged game is tracked once its league is known.
                playerStarted.flatMapLatest { started ->
                    if (started) {
                        combine(liveScores.scores, liveScores.mode, TeamDirectory.shared.revision) { scores, mode, _ ->
                            scores[item.streamKey] to mode
                        }
                    } else {
                        emptyFlow()
                    }
                }.collect { (snapshot, mode) ->
                    nowMode = mode
                    nowTracker.update(listOf(playerTrackedCard(item)), enabled = mode != ScoresMode.OFF)
                    gameWatch.note(item.id, ScoreChips.matchedEvent(item, snapshot))
                    if (SportsLiveFullTimeToasts.isLive(item, snapshot)) nowSeenLive = true
                    val toast = SportsLiveFullTimeToasts.of(item, snapshot, mode, nowSeenLive) ?: return@collect
                    raiseEnd(GameEndReason.FINAL, streamStopped = false, eventId = toast.eventId)
                }
            }
            // The player stopped the stream because the game ended (#161): the card, never an error.
            viewModelScope.launch {
                gameWatch.ends.collect { (gameId, reason) ->
                    if (gameId == item.id) raiseEnd(reason, streamStopped = true, eventId = null)
                }
            }
        }
    }

    /** The player started (true) or stopped: the game playing is scored only meanwhile. */
    fun onPlayerStarted(started: Boolean) {
        if (started) nowTracker.onStart() else nowTracker.onStop()
        playerStarted.value = started
        // Q18 (#85): while it plays, its game shows no score or clock from us and the others' scores wait.
        val item = nowPlaying
        if (started && item != null) {
            WatchingNow.shared.watch(watchOwner, (listOf(item) + item.alternates).mapTo(HashSet()) { it.streamKey }, item.addonId)
        } else {
            WatchingNow.shared.stop(watchOwner)
        }
        neighbourJob?.cancel()
        neighbourJob = null
        val prefetch = activePrefetcher ?: return
        if (!started) return
        // B2: the games ▲ and ▼ zap to are resolved (and their links probed once) after this one had time to start,
        // so its own start never shares the box's bandwidth with them.
        neighbourJob = viewModelScope.launch {
            delay(NEIGHBOUR_PREFETCH_DELAY_MS)
            prefetch.prefetchNow(
                listOfNotNull(controller.nextTarget(contentId, forward = true), controller.nextTarget(contentId, forward = false))
                    .filter(::sportsLiveAutoPlayEligible)
            )
        }
    }

    // This player's key in [WatchingNow].
    private val watchOwner: String get() = "player:" + (contentId ?: "")

    /** How far the player is behind its live edge (its live window, polled by the overlay; null: unknown). */
    fun onBehindLive(ms: Long?) {
        WatchingNow.shared.behindLive(watchOwner, ms)
    }

    /** "Scores ahead? +15 s" (Q18, #85): one more press for the source playing; its new seconds, or null. */
    fun onScoresAhead(onNudged: (Int) -> Unit) {
        val source = nowPlaying?.addonId ?: return
        viewModelScope.launch { onNudged(nudgeScores(source)) }
    }

    /**
     * The game playing ended ([reason]); [streamStopped]: its stream is gone too. Once per ESPN game per player
     * (a stream stopping afterwards still turns the card into its stopped form); never for one the alerts
     * already announced while the stream plays.
     */
    private suspend fun raiseEnd(reason: GameEndReason, streamStopped: Boolean, eventId: String?) {
        val item = nowPlaying ?: return
        val shown = _endCard.value
        if (!streamStopped) {
            if (eventId != null && (endEventId == eventId || SportsLiveFullTimeAnnouncements.isAnnounced(eventId))) return
            endEventId = eventId
        } else if (shown?.streamStopped == true) {
            return
        }
        val session = controller.session(contentId)
        val lineupItems = lineup.value?.rows?.map { it.item } ?: session?.items.orEmpty()
        val scores = liveScores.scores.value
        val nowMs = System.currentTimeMillis()
        val finished = overKeys(lineupItems, scores, nowMs)
        val savedForLater = sportsLiveSavedForLater(liveScores.protection.value, scores)
        val reminded = remindersNow
        val isMine = { game: SportsLiveItem ->
            SportsLiveMyGames.isMine(game, reminded, savedForLater, ScoreChips.matchedEvent(game, scores[game.streamKey]))
        }
        val isReady = { game: SportsLiveItem -> readiness.of(game) == SportsLiveReadyMark.READY }
        // #183: a ✓ ready game first within each step; never one with no working stream, nor one saved for later (#184).
        // One of my games showing "▶ Ready" before all: the only kind the card moves to by itself (research 6).
        val next = SportsLiveNextGame.pick(
            item, lineupItems, scores, finished, tried.ids(),
            knownGood = { game -> readiness.of(game) == SportsLiveReadyMark.READY || workingLinks.goodStream(game.id) != null },
            noStream = noStreamIds(lineupItems),
            savedForLater = savedForLater,
            mineReady = { game -> isReady(game) && isMine(game) }
        ).takeUnless { gameWatch.debugNoNext }
        val upcoming = if (next != null) null else upcomingGame(nowMs)
        val snapshot = ScoreChips.matchedEvent(item, scores[item.streamKey]) ?: gameWatch.snapshot(item.id)
        val action = endAction
        val move = SportsLiveEndCountdown.move(
            action, next, hasSound = true, isMine = isMine, isReady = isReady, mayAutoMove = stillWatching.mayAutoMove()
        )
        val card = SportsLiveEndCard(
            item = item,
            reason = reason,
            eventId = eventId ?: shown?.eventId,
            snapshot = snapshot,
            mode = nowMode,
            next = next,
            upcoming = upcoming,
            boxScore = nowMode != ScoresMode.OFF && snapshot != null,
            streamStopped = streamStopped,
            toastOnly = action == SportsLiveGameEndAction.STAY && !streamStopped,
            countdown = move == SportsLiveEndMove.COUNTDOWN,
            stillWatching = move == SportsLiveEndMove.STILL_WATCHING
        )
        // A new card (or its stopped form) is claimed and timed afresh once on screen.
        endClaimed = false
        _endCard.value = card
        endJob?.cancel()
        countdown.cancel()
        // Not on screen within a minute (the controls or the panel stayed up): dropped, unless nothing plays.
        if (!streamStopped) {
            endJob = viewModelScope.launch {
                delay(FULL_TIME_PENDING_MS)
                if (!endClaimed) _endCard.value = null
            }
        }
        if (endVisible) onEndCardVisible(true)
    }

    /** The game the nothing-live state names: the soonest still to start among the loaded games. */
    private suspend fun upcomingGame(nowMs: Long): SportsLiveItem? = try {
        val candidates = SportsLiveAlerts.candidates(repo.latest.value, repo.claimedCatalogs.first(), repo.followedTeams.first())
        SportsLiveNextGame.upcoming(candidates, nowMs)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /**
     * The overlay shows the card ([visible]) or not (controls, the panel or the zap banner are up, or the player
     * left). The first time it shows, its ESPN final is claimed; one the alerts announced meanwhile goes at once
     * while the stream plays. Shown: the countdown starts when it runs; a card over a stream still playing with
     * no countdown leaves after [FULL_TIME_TOAST_MS].
     */
    fun onEndCardVisible(visible: Boolean) {
        endVisible = visible
        val card = _endCard.value ?: return
        if (!visible || endClaimed) return
        val eventId = card.eventId
        if (eventId != null && !SportsLiveFullTimeAnnouncements.claim(eventId) && !card.streamStopped) {
            dismissEndCard()
            return
        }
        endClaimed = true
        endJob?.cancel()
        endJob = null
        when {
            card.countdown -> {
                countdown.start(System.currentTimeMillis())
                _endCard.value = card.copy(countdownEndsAtMs = countdown.endsAtMs)
                endJob = viewModelScope.launch {
                    delay(SportsLiveEndCountdown.DURATION_MS)
                    // The delay is the clock (a cancel clears the countdown and this job), not the wall time.
                    if (countdown.running && endWatchNext()) stillWatching.onAutoMove()
                }
            }
            !card.streamStopped -> endJob = viewModelScope.launch {
                delay(FULL_TIME_TOAST_MS)
                _endCard.value = null
            }
        }
    }

    /** Back or any key on the card (#161): the countdown stops and the card waits for OK. True when one ran. */
    fun cancelEndCountdown(): Boolean {
        if (!countdown.cancel()) return false
        endJob?.cancel()
        endJob = null
        _endCard.update { it?.copy(countdown = false, countdownEndsAtMs = null) }
        return true
    }

    /** Back with the card on screen (#161): its countdown stops, else it closes. False when no card shows. */
    fun endCardOnBack(): Boolean {
        val card = _endCard.value ?: return false
        if (!endVisible || card.toastOnly) return false
        if (!cancelEndCountdown()) dismissEndCard()
        return true
    }

    /** Stay (or Back with no countdown): the card goes; the postgame stream plays on if it still does. */
    fun dismissEndCard() {
        endJob?.cancel()
        endJob = null
        countdown.cancel()
        _endCard.value = null
    }

    /** Watch next on the card, or its countdown running out: the zap to Up next. False when there is none. */
    internal fun endWatchNext(): Boolean {
        val card = _endCard.value ?: return false
        val next = card.next ?: return false
        val session = controller.session(contentId) ?: return false
        dismissEndCard()
        val lineupItems = lineup.value?.rows?.map { it.item } ?: session.items
        return controller.pick(contentId, next, if (lineupItems.any { it.streamKey == next.streamKey }) lineupItems else session.items + next)
    }

    /** Box score on the card: the Game page on its Box Score tab (#145). Null without a strict ESPN match. */
    internal fun endBoxScore(): SportsLiveGameRequest? {
        val card = _endCard.value ?: return null
        val event = card.snapshot?.takeIf { card.boxScore } ?: return null
        dismissEndCard()
        return SportsLiveGameRequest(
            event,
            revealed = card.mode == ScoresMode.SHOW,
            homeFirst = sportsLiveGameHomeFirst(card.item, event),
            card = card.item,
            section = GameSection.BOX_SCORE
        )
    }

    /** Remind me on the nothing-live state's upcoming game. */
    fun endRemindUpcoming() {
        val upcoming = _endCard.value?.upcoming ?: return
        viewModelScope.launch {
            val on = toggleReminder(upcoming)
            _endCard.update { card -> if (card?.upcoming?.streamKey == upcoming.streamKey) card.copy(upcomingReminded = on) else card }
        }
    }

    /**
     * A short Right with the card on screen (#60's "Next live game →"): Watch next. False when the card is not
     * on screen or has no next game.
     */
    internal fun nextLiveGame(): Boolean {
        if (_endCard.value == null || !endClaimed || !endVisible) return false
        return endWatchNext()
    }

    private val _nextExhausted = MutableStateFlow(false)

    /**
     * Next live game found every other game used up (#138 follow-up): "No other live game is playing right now",
     * with Try again ([retryNextLiveGame]) and Back to Live Now.
     */
    val nextExhausted: StateFlow<Boolean> = _nextExhausted.asStateFlow()

    /**
     * "Next live game" on the player's recovery panel (#138), once the stream could not be recovered: the game
     * playing counts as tried and failed, and the zap goes forward in the lineup to the next live game never tried
     * this session, a known-good one first ([SportsLiveSecondGame.nextAfterFailure]). False, with [nextExhausted]
     * set, when none is left (or false with no zap session).
     */
    internal fun nextLiveGameAfterFailure(): Boolean {
        val session = controller.session(contentId) ?: return false
        tried.noteFailed(session.current)
        val lineupItems = lineup.value?.rows?.map { it.item } ?: session.items
        val scores = liveScores.scores.value
        val finished = overKeys(lineupItems, scores, System.currentTimeMillis())
        val next = SportsLiveSecondGame.nextAfterFailure(
            current = session.current,
            lineup = lineupItems,
            scores = scores,
            finishedKeys = finished,
            tried = tried.ids(),
            // #147: ✓ ready (a check, a race, a first frame) or a link that played; ⚠ only when no other is left.
            knownGood = { item -> readiness.of(item) == SportsLiveReadyMark.READY || workingLinks.goodStream(item.id) != null },
            noStream = noStreamIds(lineupItems),
            // #184: never a game saved with Watch later, which would play live.
            savedForLater = sportsLiveSavedForLater(liveScores.protection.value, scores)
        )
        if (next == null) {
            _nextExhausted.value = true
            return false
        }
        _nextExhausted.value = false
        return controller.pick(contentId, next, lineupItems)
    }

    /**
     * Card keys of the games over at [nowMs] (live.19 QA F2): the probe's ended games, the open panel's Just ended,
     * dead link checks, and every game of [lineupItems] Live now shows final, ended or "Probably ended" (an estimate,
     * gated as the panel gates it), with every source of a merged card. Up next and "Next live game" never pick one.
     */
    private fun overKeys(lineupItems: List<SportsLiveItem>, scores: Map<String, ScoreSnapshot>, nowMs: Long): Set<String> {
        val canEstimate = SportsLiveEndedGames.estimateGate(lineup.value?.mode == ScoresMode.OFF, liveScores.unmatched.value)
        return repo.endedKeys.value + panelEndedKeys() + linkVerdicts.deadKeys +
            SportsLiveEndedGames.shared.overSourceKeys(lineupItems, scores, nowMs, canEstimate)
    }

    /** Game ids marked ⚠ no working stream (#147), and those of [lineupItems] whose every known link failed this session. */
    private fun noStreamIds(lineupItems: List<SportsLiveItem>): Set<String> = sportsLiveNoStreamIds(lineupItems, readiness, workingLinks)

    /** Try again on "No other live game is playing right now": the tried games may be tried again. */
    internal fun retryNextLiveGame(): Boolean {
        tried.clear()
        _nextExhausted.value = false
        return nextLiveGameAfterFailure()
    }

    /** The exhausted card was left (Back to Live Now). */
    internal fun dismissNextExhausted() {
        _nextExhausted.value = false
    }

    /**
     * True when the key was used: a zap. ▼ zaps on even while the arrival banner shows (BUG6/Q10: the banner names the
     * game ▼ goes to; ◀ opens the live games panel). No zaps while a watch party is active.
     */
    fun onKey(keyCode: Int, partyActive: Boolean): Boolean {
        val forward = zapDirection(keyCode, partyActive) ?: return false
        return controller.zap(contentId, forward)
    }

    /**
     * Left or Right, pressed with the controls hidden: a short Left opens the live games panel, a
     * short Right is returned as [SportsLiveShortPress.SHOW_CONTROLS] (both on release). A long press
     * is left to the player, so it gets the repeats and the release. Inactive during a watch party.
     */
    fun onShortPressKey(event: KeyEvent, partyActive: Boolean): SportsLiveShortPress {
        val active = !partyActive && controller.session(contentId) != null
        val result = shortPressKeys.onKey(event, active)
        if (result == SportsLiveShortPress.OPEN_PANEL) openPanel()
        // The full-time toast's "Next live game →" (#60): a short Right moves on instead of showing the controls.
        if (result == SportsLiveShortPress.SHOW_CONTROLS && nextLiveGame()) return SportsLiveShortPress.CONSUMED
        return result
    }

    internal fun openPanel() {
        val session = controller.session(contentId) ?: return
        scoreTracker.onStart()
        panelKeyPressed = false
        focusedRowKey = null
        // #164: the order is taken afresh as the panel opens, then kept while it is open.
        frozenOrder = null
        // BUG6/Q10: CH± follow the order the panel shows from now on.
        controller.thaw(contentId)
        lastPanelKeyAtMs = System.currentTimeMillis()
        // The panel takes over from the arrival banner (#76).
        arrival.value = null
        // Opens on All, or on the chip picked earlier in this playback (#68).
        panelChip.value = openingChip(session)
        panelSession.value = session
        _panelOpen.value = true
        _panelKeysHint.value = false
        viewModelScope.launch {
            _panelKeysHint.value = try {
                claimPanelKeysHint()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
        }
        viewModelScope.launch {
            try {
                repo.load(force = false, includeChannels = false)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The panel keeps showing the last load.
            }
        }
    }

    /** Closes the live games panel; false when it was not open. */
    fun closePanel(): Boolean {
        if (!_panelOpen.value) return false
        _panelOpen.value = false
        panelSession.value = null
        // #164: the next open (and CH± meanwhile) takes the fresh order.
        frozenOrder = null
        // Untracked now, and the next open starts from no cards rather than this list.
        scoreTracker.update(emptyList(), enabled = false)
        scoreTracker.onStop()
        panelKeyPressed = false
        focusedRowKey = null
        reveal.onFocus(null)
        prefetcher?.stop()
        return true
    }

    // Uptime at which Back closed the panel; see isBackKeyOfPanelClose.
    private var panelClosedOnBackAtMs = Long.MIN_VALUE

    /**
     * Back while the panel is open closes it; false when it was not open. One Back press reaches the
     * player twice on recent Android (the back callback, then the key event), so the press is remembered.
     */
    fun closePanelOnBack(): Boolean {
        if (!closePanel()) return false
        panelClosedOnBackAtMs = SystemClock.uptimeMillis()
        return true
    }

    /** True for the key events of the Back press that has already closed the panel. */
    fun isBackKeyOfPanelClose(event: KeyEvent): Boolean =
        event.keyCode == KeyEvent.KEYCODE_BACK && event.downTime <= panelClosedOnBackAtMs

    /**
     * "Watch with another game" in the live games panel, for the game playing: the second-game picker
     * over the panel's list (the session's before it is grouped), its finals left out (M11). Null (no
     * action) when the session is neither Live now nor Channels.
     */
    fun dualPick(): SportsLiveDualPick? = dualPick({ session -> panel.value?.items ?: session.items }, { session -> panel.value?.allItems ?: session.items })

    // The panel's Just ended games, with every source of each (M11): never offered for dual screen.
    private fun panelEndedKeys(): Set<String> =
        panel.value?.rows.orEmpty().filter { it.group == ZapPanelGroup.JUST_ENDED }.flatMapTo(HashSet()) { it.item.sourceKeys() }

    private inline fun dualPick(
        items: (SportsLiveZapSession) -> List<SportsLiveItem>,
        zapItems: (SportsLiveZapSession) -> List<SportsLiveItem>
    ): SportsLiveDualPick? {
        val session = panelSession.value ?: controller.session(contentId) ?: return null
        if (!session.tab.offersDual()) return null
        return SportsLiveDualPick(
            chosen = session.current,
            items = items(session),
            zapItems = zapItems(session),
            filter = session.filter,
            tab = session.tab,
            // ESPN finals past their grace are left out too (C3), as remembered across restarts.
            // The panel's Just ended games (M11) are left out as well.
            endedKeys = repo.endedKeys.value + panelEndedKeys() +
                SportsLiveEndedGames.shared.confirmedEndedSourceKeys(items(session), liveScores.scores.value, System.currentTimeMillis()),
            // The game playing keeps its working stream in dual screen, with no new lookup (#65).
            chosenStream = workingLinks.playing(session.current.id),
            // The picker draws each game's logos and score chip as the panel row did (M11).
            rows = panel.value?.rows?.associateBy { it.item.streamKey }.orEmpty(),
            // Back from dual screen plays this game full screen again (M11).
            fromPlayer = true
        )
    }

    /**
     * Long-press on [item] in the live games panel: dual screen with the game playing on the left,
     * [item] on the right, over the panel's list. Null when the panel offers no dual screen, or when
     * [item] is the game playing, shares a source with it, or has ended.
     */
    fun dualSessionWith(item: SportsLiveItem): SportsLiveDualSession? =
        dualPick({ session -> panel.value?.items ?: session.items }, { session -> panel.value?.allItems ?: session.items })
            ?.pairWith(item)

    /**
     * A chip was picked in the live games panel (null is All): the rows are narrowed to it, and Up/Down
     * follow it for the rest of this playback (#68).
     */
    fun onPanelChip(chip: String?) {
        if (!_panelOpen.value || panelChip.value == chip) return
        panelChip.value = chip
        controller.pickChip(contentId, chip)
    }

    /** [item] was picked in the live games panel: the panel closes and the player zaps to it. */
    fun onPanelPick(item: SportsLiveItem) {
        val items = panel.value?.allItems.orEmpty()
        closePanel()
        controller.pick(contentId, item, items)
    }

    /**
     * ▲ Both on an alert or kickoff card for [item]: dual screen with the game playing on the left (its working link
     * and the sound, as "Watch both" in the panel) and [item] on the right, muted, over the panel's list ([item]
     * added when the list leaves it out). Null when dual screen cannot pair them (the same game, a shared source,
     * a game over, or no zap session).
     */
    fun bothFromAlert(item: SportsLiveItem): SportsLiveDualSession? {
        fun withItem(list: List<SportsLiveItem>) = if (list.any { it.streamKey == item.streamKey }) list else list + item
        return dualPick(
            { session -> withItem(lineup.value?.list?.all ?: session.items) },
            { session -> withItem(panel.value?.allItems ?: lineup.value?.list?.all ?: session.items) }
        )?.pairWith(item)
    }

    /**
     * OK on an alert card (or during its peek) for [item] (#136): the player zaps to it as a panel pick does,
     * over the panel's list (with [item] added when the list leaves it out). False with no zap session.
     */
    fun watchFromAlert(item: SportsLiveItem): Boolean {
        val session = controller.session(contentId) ?: return false
        val listed = panel.value?.allItems ?: lineup.value?.list?.all ?: session.items
        val items = if (listed.any { it.streamKey == item.streamKey }) listed else listed + item
        closePanel()
        return controller.pick(contentId, item, items)
    }

    /**
     * Hold OK on [item] in the live games panel (#140): **Watch both** first where dual screen pairs it with
     * the game playing, then Choose stream, Game stats (a strict ESPN match) and Follow for each side.
     */
    internal fun panelMenu(item: SportsLiveItem): SportsLivePanelMenu {
        val current = lineup.value
        val game = current?.let { strictGame(it, item) }
        val row = current?.rows?.firstOrNull { it.item.streamKey == item.streamKey }
        val followed = lineup.value?.list?.followed.orEmpty()
        // Follow only real teams (#134): the strict game's, else the team directory's; never a raw title's side.
        val targets = FollowTargets.of(item, game, homeFirst = game?.let { sportsLiveGameHomeFirst(item, it) } ?: false)
        val sides = targets.map { it.label to it.isFollowed(followed) }
        val entries = panelMenuEntries(
            watchBoth = dualSessionWith(item) != null,
            chooseStream = sportsLiveAutoPlayEligible(item),
            strictMatch = game != null,
            sides = sides.size
        )
        val postgame = row != null && panelRowOk(row, game) == PanelRowOk.BOX_SCORE
        return SportsLivePanelMenu(item, entries, sides, game, postgame, targets)
    }

    /**
     * OK on [item] in the panel (#140): a Just ended game with a strict ESPN match opens its box score (the Game
     * page's request is returned, the panel closed) where the player can open it ([canOpenGame]); any other row
     * zaps to its game as before ([onPanelPick]) and null is returned.
     */
    internal fun onPanelOk(item: SportsLiveItem, canOpenGame: Boolean): SportsLiveGameRequest? {
        val current = lineup.value
        val row = current?.rows?.firstOrNull { it.item.streamKey == item.streamKey }
        val game = current?.let { strictGame(it, item) }
        if (canOpenGame && row != null && panelRowOk(row, game) == PanelRowOk.BOX_SCORE) {
            // "Box score ›" (#145): the page opens on its Box Score tab.
            return panelGameRequest(SportsLivePanelMenu(item, emptyList(), emptyList(), game), GameSection.BOX_SCORE)
        }
        onPanelPick(item)
        return null
    }

    /**
     * [item]'s strict ESPN match in [current] (a final's own event included), the one Game stats and the box
     * score open; null without one or under Scores Off.
     */
    private fun strictGame(current: PanelLineup, item: SportsLiveItem): ScoreSnapshot? {
        if (current.mode == ScoresMode.OFF) return null
        val snapshot = current.boardScores[item.streamKey] ?: liveScores.scores.value[item.streamKey]
        // A tennis match (#160) has a score but no Game page.
        return ScoreChips.matchedEvent(item, snapshot)?.takeIf { it.individual == null }
    }

    /** Choose stream on [item] in the panel (#140): the panel closes and [item]'s Stream screen opens, to pick from. */
    fun onPanelChooseStream(item: SportsLiveItem) {
        val items = panel.value?.allItems.orEmpty()
        closePanel()
        val moved = controller.chooseStream(contentId, item, items) ?: return
        if (contentId != item.id) SportsLiveExtraSources.clear(contentId)
        SportsLiveExtraSources.register(item.id, item)
        _events.trySend(
            SportsLiveZapEvent.Navigate(
                SportsLiveZapTarget(item, playback = null, returnToHomeOnBack = moved.returnToHomeOnBack, manualSelection = true)
            )
        )
    }

    /** Game stats on [menu]'s game (#140): the panel closes; the Game page's request, null with no strict ESPN match. */
    internal fun panelGameRequest(menu: SportsLivePanelMenu, section: GameSection? = null): SportsLiveGameRequest? {
        val event = menu.game ?: return null
        val item = menu.item
        val current = lineup.value
        closePanel()
        // A replay's page is spoiler-free (#151): its result shows only on Reveal.
        val replay = item.kind == com.nuvio.tv.domain.model.SportsLiveKind.REPLAY
        return SportsLiveGameRequest(
            event,
            revealed = !replay && (current?.mode == ScoresMode.SHOW || item.streamKey in current?.revealed.orEmpty()),
            spoilerSafe = replay,
            homeFirst = sportsLiveGameHomeFirst(item, event),
            card = item,
            section = section
        )
    }

    // The Game page was opened over the game while it played (#145): coming back to the player resumes it.
    private var resumeOnReturn = false

    /** The Game page opens over the player; [wasPlaying]: the game was playing, so the return resumes it. */
    fun onGameOpened(wasPlaying: Boolean) {
        resumeOnReturn = wasPlaying
    }

    /** True once after [onGameOpened] over a playing game: the player should play again now. */
    fun consumeResumeOnReturn(): Boolean = resumeOnReturn.also { resumeOnReturn = false }

    /** Follow (or unfollow) [target]'s team from the panel's Hold OK menu (#140, #134). */
    fun onPanelToggleFollow(target: FollowTarget) {
        viewModelScope.launch { repo.toggleFollowTarget(target) }
    }

    override fun onCleared() {
        scoreTracker.onStop()
        nowTracker.onStop()
        WatchingNow.shared.stop(watchOwner)
        // This player left: its stream no longer plays, so dual screen cannot take it over (#65).
        contentId?.let(workingLinks::stoppedPlaying)
        super.onCleared()
    }

    private suspend fun targetFor(item: SportsLiveItem, stream: Stream): SportsLiveZapTarget =
        sportsLiveZapTarget(
            item = item,
            stream = stream,
            profileId = profileId,
            returnToHomeOnBack = SportsLiveZapSessions.current()?.returnToHomeOnBack == true,
            directDebridResolver = directDebridResolver,
            autoPicked = item.alternates.isNotEmpty()
        )
}

private const val BANNER_MS = 3_000L

/**
 * The zap neighbours are looked up this long after the player started (B2): the live.19 TV start took 3.8 s from the
 * stream found to playing, so 5 s leaves the start its bandwidth and still has them ready long before a viewer
 * usually zaps on (tens of seconds).
 */
internal const val NEIGHBOUR_PREFETCH_DELAY_MS = 5_000L

/**
 * The game playing as the score tracker gets it (live.20): placed in its inferred league with its teams identified, as
 * the panel and My sports place it, so an untagged game still gets its score while the panel is closed. Same stream key.
 */
internal fun playerTrackedCard(item: SportsLiveItem, directory: TeamDirectory = TeamDirectory.shared): SportsLiveItem =
    SportsLiveTeamIdentity.identified(SportsLiveSportInference.infer(item, directory))

/** How long the end card stays over a stream still playing with no countdown (#60, #161); then the postgame plays on. */
internal const val FULL_TIME_TOAST_MS = 10_000L

/** An end card the overlay could not show this long (controls or the panel up) is dropped, unless nothing plays. */
internal const val FULL_TIME_PENDING_MS = 60_000L

/**
 * The panel's one ordered list (#142) and what its rows are drawn from: [list] (the games under the chip),
 * [rows] in the order shown and zapped, the Scores [mode], the [boardScores] and [ended] verdicts, the
 * [revealed] rows at [nowMs]; [logosLoaded] once the kept logo map is read (the panel waits for it).
 */
private data class PanelLineup(
    val list: PanelList,
    val rows: List<ZapPanelRow>,
    val mode: ScoresMode,
    val boardScores: Map<String, ScoreSnapshot>,
    val ended: Map<String, EndedGame>,
    val revealed: Set<String>,
    val nowMs: Long,
    val logosLoaded: Boolean,
    /** Finals and just ended games among every game listed (stream keys): not counted on the chips (#145). */
    val finished: Set<String> = emptySet()
)

/** The open panel's [state], built from [lineup] for [session]. */
private class ShownPanel(val lineup: PanelLineup, val state: SportsLiveZapPanelState, val session: SportsLiveZapSession)

/** The panel's games: [all] of them, and those [shown] under [chip]; [followed] stars their sides (#83). */
private data class PanelList(
    val all: List<SportsLiveItem>,
    val chip: String?,
    val shown: List<SportsLiveItem>,
    val followed: List<FollowedTeam> = emptyList(),
    /** [all] as My sports lists them (#146). */
    val scoped: List<SportsLiveItem> = all
)

/**
 * The chip the panel opens on (#68): the one picked earlier in this playback, else All sports when the game was
 * started under All sports in the section (#146), else All (My sports).
 */
private fun openingChip(session: SportsLiveZapSession): String? =
    session.panelChip?.filter ?: session.filter.takeIf { it == SportsLiveUiState.ALL_SPORTS_FILTER && session.tab == SportsLiveTab.LIVE_NOW }

/** true = next game, false = previous game, null = not a zap key or a watch party is active. */
internal fun zapDirection(keyCode: Int, partyActive: Boolean): Boolean? = if (partyActive) null else when (keyCode) {
    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT -> true
    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> false
    else -> null
}

/**
 * The player's zap key handler: returns true when a key (pressed with the controls hidden) zapped
 * to another Sports & Live game. Players without a session for their content get false for every key.
 */
@Composable
fun rememberSportsLiveZapKeyHandler(
    onZap: (SportsLiveZapTarget) -> Unit
): (keyCode: Int, partyActive: Boolean) -> Boolean {
    val viewModel: SportsLiveZapViewModel = hiltViewModel()
    val context = LocalContext.current
    val currentOnZap by rememberUpdatedState(onZap)
    // A source that answered with a rate-limit notice while zapping is announced once (C1).
    SportsLiveCooldownToasts()
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is SportsLiveZapEvent.Navigate -> currentOnZap(event.target)
                is SportsLiveZapEvent.NoStream -> SportsLiveToasts.show(
                    SportsLiveToastKind.PROBLEM,
                    context.getString(R.string.sports_live_zap_no_stream, event.name)
                )
                is SportsLiveZapEvent.Skipped -> SportsLiveToasts.show(
                    SportsLiveToastKind.PROBLEM,
                    context.getString(R.string.sports_live_zap_no_stream, event.name),
                    context.getString(R.string.sports_live_zap_skipped_line)
                )
            }
        }
    }
    return remember(viewModel) { viewModel::onKey }
}

/** A tick every [PANEL_TICK_MS] while collected (the open live games panel). */
private fun panelTicks(): Flow<Unit> = flow {
    while (true) {
        emit(Unit)
        delay(PANEL_TICK_MS)
    }
}

private const val PANEL_TICK_MS = 30_000L

/** How long the team directory must be still before the panel list is merged again (live.18). */
private const val REBUCKET_DEBOUNCE_MS = 2_000L
