package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.SportsLiveToastKind
import com.nuvio.tv.core.sportslive.SportsLiveToasts
import com.nuvio.tv.core.sportslive.scores.FollowTarget
import com.nuvio.tv.core.sportslive.scores.FollowTargets
import com.nuvio.tv.core.sportslive.scores.GameSection
import com.nuvio.tv.core.sportslive.SportsLiveSportInference
import com.nuvio.tv.core.sportslive.SportsLiveMySportsScope
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
import com.nuvio.tv.core.sportslive.FullTimeToast
import com.nuvio.tv.core.sportslive.scores.ScoreChips
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.SportsLiveEndedGames
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
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
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
@OptIn(ExperimentalCoroutinesApi::class)
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
    private val mySports: Flow<MySports> = flowOf(MySports.DEFAULT)
) : ViewModel() {

    @Inject constructor(
        savedStateHandle: SavedStateHandle,
        resolver: SportsLiveZapResolver,
        directDebridResolver: DirectDebridResolver,
        profileManager: ProfileManager,
        repo: SportsLiveRepository,
        liveScores: LiveScoresRepository,
        // My sports (#146); null in tests that do not scope.
        prefs: SportsLivePreferencesDataStore? = null
    ) : this(
        savedStateHandle, resolver, directDebridResolver, profileManager, repo, liveScores, Dispatchers.Default,
        mySports = prefs?.mySports ?: flowOf(MySports.DEFAULT)
    )

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
        onNoStream = { item -> _events.trySend(SportsLiveZapEvent.NoStream(item.displayName)) },
        onSkipped = { item -> _events.trySend(SportsLiveZapEvent.Skipped(item.displayName)) },
        endedKeys = { repo.endedKeys.value },
        // #142: CH+/CH- walk the panel's own list.
        lineup = { lineup.value?.rows }
    )

    /** True when this player was opened with a zap session for its content; only then is the overlay shown. */
    val hasSession: Boolean = SportsLiveZapSessions.activeFor(contentId) != null

    // A player reached by a zap shows the banner for BANNER_MS.
    private val arrival = MutableStateFlow(
        SportsLiveZapSessions.activeFor(contentId)?.takeIf { SportsLiveZapSessions.consumeZapArrival() }
    )

    /**
     * The zap banner (#76): "Switching to …" while a stream is looked up, then the arrival banner, with
     * logos, the score chip under the Scores setting (no score until it is read) and the next game.
     */
    val banner: StateFlow<SportsLiveZapBanner?> = combine(
        controller.resolving,
        arrival,
        liveScores.scores,
        liveScores.mode.map<ScoresMode, ScoresMode?> { it }.onStart { emit(null) },
        repo.endedKeys
    ) { resolving, arrived, scores, mode, ended ->
        resolving?.banner(switching = true, scores = scores, mode = mode, endedKeys = ended, known = logos)
            ?: arrived?.banner(switching = false, scores = scores, mode = mode, endedKeys = ended, known = logos)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // The session when the panel opened; null while it is closed.
    private val panelSession = MutableStateFlow<SportsLiveZapSession?>(null)

    // The panel's chip (null is All): the session's picked chip, or All until one is picked (#68). Kept while the
    // panel is closed, since CH+/CH- walk the panel's list under it (#142).
    private val panelChip = MutableStateFlow(SportsLiveZapSessions.activeFor(contentId)?.let(::openingChip))

    private val _panelOpen = MutableStateFlow(false)
    val panelOpen: StateFlow<Boolean> = _panelOpen.asStateFlow()

    // The panel's games are tracked for live scores only while it is open: the section stops its own
    // tracking when the player covers it (ON_STOP), so the player has its own scope.
    private val scoreTracker = LiveScoresTracker(LiveScoresRepository.SCREEN_PLAYER_PANEL, liveScores)

    // Hide until focused: a row's score shows after it has been focused for a second.
    private val reveal = ResultSpoilerReveal(viewModelScope)

    // Read where the rows are grouped, off the main thread.
    @Volatile
    private var focusedRowKey: String? = null

    // The row order last shown (stream keys) and the minute it was taken in (I3).
    @Volatile
    private var shownOrder: List<String>? = null
    @Volatile
    private var shownMinute: Long? = null

    /**
     * The live games panel's one ordered list (#142): the list the panel shows and CH+/CH- walk, kept current
     * for a player with a session, open or not. Grouped by V2 group with followed teams first ([panelLineup]),
     * under the panel's chip, its order frozen while a row has focus (I3). Ticks only while the panel is open
     * (while closed, an order change comes with the next list or score); scores are polled only while open.
     */
    private val lineup: StateFlow<PanelLineup?> = if (!hasSession) {
        MutableStateFlow(null)
    } else {
        val all = combine(repo.latest, repo.claimedCatalogs, repo.endedKeys, repo.followedTeams) { load, claimed, ended, followed ->
            liveGamesPanelItems(
                items = load?.items.orEmpty(),
                claimedKeys = claimed.mapTo(HashSet()) { it.key },
                endedKeys = ended,
                followed = followed,
                nowMs = System.currentTimeMillis(),
                addonOrder = claimed.map { it.addon.id }.distinct()
                // Unrouted two-team games take their teams' league here too (P4), as in the section's lists.
            ).map { SportsLiveSportInference.infer(it, TeamDirectory.shared) } to followed
        }.flowOn(computeDispatcher)
        // The games under the chip are the ones listed and tracked: My sports' (#146), or every game under All sports.
        val items = combine(all, panelChip, mySports) { (list, followed), chip, sports ->
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
            val boardScores = split.scoresWithFinals(scores)
            val grouped = groupPanelRows(split.live + split.justEnded, boardScores, scoresMode, revealed, nowMs, split.ended, logos)
            // The order stays put under a focused row, taking the fresh one each new minute (I3).
            val rows = if (panelMayReorder(focusedRowKey, nowMs, shownMinute)) {
                shownMinute = nowMs / PANEL_REORDER_MS
                panelLineup(grouped)
            } else {
                panelLineup(grouped, shownOrder)
            }
            shownOrder = rows.map { it.item.streamKey }
            // The rows CH± passes over count as finished too, so the chip's count is the banner's "of N".
            val finished = splitFinished + rows.filterNot { it.zappable }.map { it.item.streamKey }
            PanelLineup(panelList, rows, scoresMode, boardScores, split.ended, revealed, nowMs, loaded, finished)
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
                nowPlayingRow = nowRow
            )
            ShownPanel(current, state, session)
        }
    }.flowOn(computeDispatcher).map { shown ->
        shown ?: return@map null
        // A list built before closePanel can arrive after it: it must not become the tracker's cards, or
        // the next open would start tracking this session's list.
        if (panelSession.value !== shown.session) return@map null
        scoreTracker.update(shown.lineup.list.shown, enabled = shown.lineup.mode != ScoresMode.OFF)
        shown.state
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Hide until focused: rows reveal only once the user has pressed a key in the panel, so opening it
    // (focus lands on the game playing) never reveals a score by itself.
    private var panelKeyPressed = false
    /** The focused panel row's stream key, or null when no row is focused (Hide until focused reveal). */
    fun onPanelRowFocus(key: String?) {
        focusedRowKey = key
        if (panelKeyPressed) reveal.onFocus(key)
    }

    /** Panel rows revealed under Hide until focused (for tests). */
    internal val revealedRows: Set<String> get() = reveal.revealed.value

    /** A key was pressed inside the panel: from now on a row focused for a second reveals its score. */
    fun onPanelKey() {
        if (panelKeyPressed) return
        panelKeyPressed = true
        reveal.onFocus(focusedRowKey)
    }

    private val shortPressKeys = SportsLiveShortPressKeys()

    // The game this player plays, for "Ended while you're watching" (#60).
    private val nowPlaying: SportsLiveItem? = SportsLiveZapSessions.activeFor(contentId)?.current

    // Only the game playing is scored for the toast, while the player is started and Scores is not Off.
    private val nowTracker = LiveScoresTracker(LiveScoresRepository.SCREEN_PLAYER_NOW, liveScores)

    // ESPN's delayed view showed the game playing in play: only then may it say it ended here.
    private var nowSeenLive = false

    private val _fullTime = MutableStateFlow<FullTimeToast?>(null)

    /**
     * "Full time · ‹score› · Stay for postgame / Next live game →" (#60) when ESPN's delayed view says the
     * game playing is over; the score under Show only, nothing under Off. The overlay shows it when
     * nothing else is up and says so ([onFullTimeVisible]): only then is the game's full time claimed
     * ([SportsLiveFullTimeAnnouncements], shared with the followed-team alerts, #42) and the toast stays
     * [FULL_TIME_TOAST_MS]. One never shown within [FULL_TIME_PENDING_MS] is dropped unclaimed.
     */
    val fullTime: StateFlow<FullTimeToast?> = _fullTime.asStateFlow()
    private var fullTimeJob: Job? = null

    // The player is started: only then is the game playing scored and its toast looked for.
    private val playerStarted = MutableStateFlow(false)

    // The toast's game's full time was claimed (it showed), and the toast is on screen now.
    private var fullTimeClaimed = false
    private var fullTimeVisible = false

    // The event a toast was raised for: one toast per game per player.
    private var fullTimeEventId: String? = null

    init {
        if (arrival.value != null) {
            viewModelScope.launch {
                delay(BANNER_MS)
                arrival.value = null
            }
        }
        nowPlaying?.let { item ->
            viewModelScope.launch {
                // Paused while the player is stopped: nothing is scored or raised behind Home.
                playerStarted.flatMapLatest { started ->
                    if (started) combine(liveScores.scores, liveScores.mode) { scores, mode -> scores[item.streamKey] to mode } else emptyFlow()
                }.collect { (snapshot, mode) ->
                    nowTracker.update(listOf(item), enabled = mode != ScoresMode.OFF)
                    if (SportsLiveFullTimeToasts.isLive(item, snapshot)) nowSeenLive = true
                    val toast = SportsLiveFullTimeToasts.of(item, snapshot, mode, nowSeenLive) ?: return@collect
                    raiseFullTime(toast)
                }
            }
        }
    }

    /** The player started (true) or stopped: the game playing is scored only meanwhile. */
    fun onPlayerStarted(started: Boolean) {
        if (started) nowTracker.onStart() else nowTracker.onStop()
        playerStarted.value = started
    }

    /** [toast] is due: it waits for the overlay to show it, unclaimed; once per game, never one already announced. */
    private fun raiseFullTime(toast: FullTimeToast) {
        if (fullTimeEventId == toast.eventId || SportsLiveFullTimeAnnouncements.isAnnounced(toast.eventId)) return
        fullTimeEventId = toast.eventId
        fullTimeClaimed = false
        _fullTime.value = toast
        fullTimeJob?.cancel()
        fullTimeJob = viewModelScope.launch {
            delay(FULL_TIME_PENDING_MS)
            if (!fullTimeClaimed) _fullTime.value = null
        }
        if (fullTimeVisible) onFullTimeVisible(true)
    }

    /**
     * The overlay shows the toast ([visible]) or not (controls, the panel or the zap banner are up, or the
     * player left). The first time it shows, its game's full time is claimed; already announced by the
     * alerts, it goes at once. Shown, it stays [FULL_TIME_TOAST_MS].
     */
    fun onFullTimeVisible(visible: Boolean) {
        fullTimeVisible = visible
        val toast = _fullTime.value ?: return
        if (!visible || fullTimeClaimed) return
        if (!SportsLiveFullTimeAnnouncements.claim(toast.eventId)) {
            dismissFullTime()
            return
        }
        fullTimeClaimed = true
        fullTimeJob?.cancel()
        fullTimeJob = viewModelScope.launch {
            delay(FULL_TIME_TOAST_MS)
            _fullTime.value = null
        }
    }

    /** Stay for postgame: the toast goes. */
    fun dismissFullTime() {
        fullTimeJob?.cancel()
        _fullTime.value = null
    }

    /**
     * Next live game → on the full-time toast: zaps to the game "Watch with another game" would pair
     * with the one that ended (#57's pick, never a finished game). False when there is none.
     */
    internal fun nextLiveGame(): Boolean {
        // Only while the toast is on screen: Right does nothing for a toast that is not showing.
        if (_fullTime.value == null || !fullTimeClaimed || !fullTimeVisible) return false
        val session = controller.session(contentId) ?: return false
        val next = nextLiveGameFor(session) ?: return false
        dismissFullTime()
        return controller.pick(contentId, next, session.items)
    }

    /**
     * "Next live game" on the player's recovery panel (#138), once the stream could not be recovered:
     * zaps to #57's pick, as the full-time toast does. False when there is none (or no zap session).
     */
    internal fun nextLiveGameAfterFailure(): Boolean {
        val session = controller.session(contentId) ?: return false
        val next = nextLiveGameFor(session) ?: return false
        return controller.pick(contentId, next, session.items)
    }

    /** #57's pick after the game playing in [session], never a finished game. */
    private fun nextLiveGameFor(session: SportsLiveZapSession): SportsLiveItem? {
        val scores = liveScores.scores.value
        // Never a finished game: the probe's, the panel's Just ended, ESPN finals, and dead link checks.
        val finished = repo.endedKeys.value + panelEndedKeys() + linkVerdicts.deadKeys +
            SportsLiveEndedGames.shared.confirmedEndedSourceKeys(session.items, scores, System.currentTimeMillis())
        return SportsLiveSecondGame.pick(session.current, session.items, scores, finished)
    }

    /**
     * True when the key was used: a zap, or ▼ while the arrival banner shows, which opens the live games
     * panel (#76; CH− still zaps). No zaps while a watch party is active.
     */
    fun onKey(keyCode: Int, partyActive: Boolean): Boolean {
        if (zapBannerOpensPanel(keyCode, partyActive, banner.value)) {
            openPanel()
            return true
        }
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
        // The panel takes over from the arrival banner (#76).
        arrival.value = null
        // Opens on All, or on the chip picked earlier in this playback (#68).
        panelChip.value = openingChip(session)
        panelSession.value = session
        _panelOpen.value = true
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
        // Untracked now, and the next open starts from no cards rather than this list.
        scoreTracker.update(emptyList(), enabled = false)
        scoreTracker.onStop()
        panelKeyPressed = false
        focusedRowKey = null
        reveal.onFocus(null)
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
        return ScoreChips.matchedEvent(item, snapshot)
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
        return SportsLiveGameRequest(
            event,
            revealed = current?.mode == ScoresMode.SHOW || item.streamKey in current?.revealed.orEmpty(),
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

/** How long the full-time toast stays (#60); doing nothing meanwhile stays for the postgame. */
internal const val FULL_TIME_TOAST_MS = 10_000L

/** A full-time toast the overlay could not show this long (controls or the panel up) is dropped. */
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

/**
 * Whether [keyCode] opens the live games panel from the zap banner (#76): ▼ (the D-pad, not CH−) while
 * the arrival banner shows. While a stream is still being looked up ("Switching to …") ▼ zaps on.
 */
internal fun zapBannerOpensPanel(keyCode: Int, partyActive: Boolean, banner: SportsLiveZapBanner?): Boolean =
    !partyActive && keyCode == KeyEvent.KEYCODE_DPAD_DOWN && banner != null && !banner.switching

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
