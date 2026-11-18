package com.nuvio.tv.ui.screens.sportslive

import android.os.SystemClock
import android.view.KeyEvent
import android.widget.Toast
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
import com.nuvio.tv.core.sportslive.SportsLiveEndedGames
import com.nuvio.tv.core.sportslive.SportsLiveWorkingLinks
import com.nuvio.tv.core.sportslive.scores.LiveScoresRepository
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.core.sportslive.scores.TeamLogoBook
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.repository.SportsLiveRepository
import com.nuvio.tv.ui.screens.sportslive.dual.SportsLiveDualPick
import com.nuvio.tv.ui.screens.sportslive.dual.SportsLiveDualSession
import com.nuvio.tv.ui.screens.sportslive.dual.offersDual
import com.nuvio.tv.ui.screens.stream.StreamPlaybackInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    val autoPicked: Boolean = false
)

sealed interface SportsLiveZapEvent {
    data class Navigate(val target: SportsLiveZapTarget) : SportsLiveZapEvent
    data class NoStream(val name: String) : SportsLiveZapEvent
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
    private val logos: TeamLogoBook = TeamLogoBook.shared
) : ViewModel() {

    @Inject constructor(
        savedStateHandle: SavedStateHandle,
        resolver: SportsLiveZapResolver,
        directDebridResolver: DirectDebridResolver,
        profileManager: ProfileManager,
        repo: SportsLiveRepository,
        liveScores: LiveScoresRepository
    ) : this(savedStateHandle, resolver, directDebridResolver, profileManager, repo, liveScores, Dispatchers.Default)

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
        onNoStream = { item -> _events.trySend(SportsLiveZapEvent.NoStream(item.meta.name)) },
        endedKeys = { repo.endedKeys.value }
    )

    /** True when this player was opened with a zap session for its content; only then is the overlay shown. */
    val hasSession: Boolean = SportsLiveZapSessions.activeFor(contentId) != null

    // A player reached by a zap shows the banner for BANNER_MS.
    private val arrival = MutableStateFlow(
        SportsLiveZapSessions.activeFor(contentId)?.takeIf { SportsLiveZapSessions.consumeZapArrival() }
    )

    /** The zap banner: "Switching to …" while a stream is looked up, then the arrival banner. */
    val banner: StateFlow<SportsLiveZapBanner?> = combine(controller.resolving, arrival) { resolving, arrived ->
        resolving?.banner(switching = true) ?: arrived?.banner(switching = false)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // The session when the panel opened; null while it is closed.
    private val panelSession = MutableStateFlow<SportsLiveZapSession?>(null)

    // The panel's chip (null is All): the session's picked chip, or All until one is picked (#68).
    private val panelChip = MutableStateFlow<String?>(null)

    private val _panelOpen = MutableStateFlow(false)
    val panelOpen: StateFlow<Boolean> = _panelOpen.asStateFlow()

    // The panel's games are tracked for live scores only while it is open: the section stops its own
    // tracking when the player covers it (ON_STOP), so the player has its own scope.
    private val scoreTracker = LiveScoresTracker(LiveScoresRepository.SCREEN_PLAYER_PANEL, liveScores)

    // Hide until focused: a row's score shows after it has been focused for a second.
    private val reveal = ResultSpoilerReveal(viewModelScope)

    /**
     * The live games panel's list, kept current while the panel is open, grouped Live now → Starting
     * soon → the rest, with score chips. Scores are collected (and so polled) only while it is open.
     */
    val panel: StateFlow<SportsLiveZapPanelState?> = panelSession.flatMapLatest { session ->
        if (session == null) {
            flowOf(null)
        } else {
            val all = combine(repo.latest, repo.claimedCatalogs, repo.endedKeys, repo.followedTeams) { load, claimed, ended, followed ->
                liveGamesPanelItems(
                    items = load?.items.orEmpty(),
                    claimedKeys = claimed.mapTo(HashSet()) { it.key },
                    endedKeys = ended,
                    followed = followed,
                    nowMs = System.currentTimeMillis(),
                    addonOrder = claimed.map { it.addon.id }.distinct()
                )
            }.flowOn(computeDispatcher)
            // The games under the chip are the ones listed and tracked.
            val items = combine(all, panelChip) { list, chip -> PanelList(list, chip, filterPanelItems(list, chip)) }
            // The mode is null until read: no chips and nothing tracked before it is known.
            val mode = liveScores.mode.map<ScoresMode, ScoresMode?> { it }.onStart { emit(null) }
            // Grouped off the main thread; the tracker is updated back on it.
            // Ticks while the panel is open, so a grace or an estimate running out regroups its games.
            // A logo learned while open (a score feed answering, the map loading) redraws the rows.
            val ticking = combine(items, panelTicks(), logos.revision) { panelList, _, _ -> panelList }
            combine(ticking, liveScores.scores, mode, reveal.revealed, liveScores.unmatched) { panelList, scores, scoresMode, revealed, unmatched ->
                val list = panelList.shown
                val nowMs = System.currentTimeMillis()
                // Ended games (#60) go to the last group; ESPN finals leave the panel half an hour on.
                val canEstimate = SportsLiveEndedGames.estimateGate(scoresMode == ScoresMode.OFF, unmatched)
                val split = SportsLiveEndedGames.shared.split(list, scores, nowMs, canEstimate)
                val state = SportsLiveZapPanelState(
                    rows = groupPanelRows(split.live + split.justEnded, scores, scoresMode, revealed, nowMs, split.ended, logos),
                    currentKey = session.current.streamKey,
                    chips = panelChips(panelList.all, panelList.chip),
                    chip = panelList.chip,
                    allItems = panelList.all
                )
                Triple(list, scoresMode, state)
            }.flowOn(computeDispatcher).map { (list, scoresMode, state) ->
                // A list grouped before closePanel can arrive after it: it must not become the tracker's
                // cards, or the next open would start tracking this session's list.
                if (panelSession.value === session) {
                    scoreTracker.update(list, enabled = scoresMode != null && scoresMode != ScoresMode.OFF)
                }
                state
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Hide until focused: rows reveal only once the user has pressed a key in the panel, so opening it
    // (focus lands on the game playing) never reveals a score by itself.
    private var panelKeyPressed = false
    private var focusedRowKey: String? = null

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

    init {
        if (arrival.value != null) {
            viewModelScope.launch {
                delay(BANNER_MS)
                arrival.value = null
            }
        }
    }

    /** True when the key was used for a zap. No zaps while a watch party is active. */
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
        return result
    }

    internal fun openPanel() {
        val session = controller.session(contentId) ?: return
        scoreTracker.onStart()
        panelKeyPressed = false
        focusedRowKey = null
        // Opens on All, or on the chip picked earlier in this playback (#68).
        panelChip.value = session.panelChip?.filter
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
     * over the session list. Null (no action) when the session is neither Live now nor Channels.
     */
    fun dualPick(): SportsLiveDualPick? = dualPick({ it.items }, { it.items })

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
            endedKeys = repo.endedKeys.value,
            // The game playing keeps its working stream in dual screen, with no new lookup (#65).
            chosenStream = workingLinks.playing(session.current.id)
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

    override fun onCleared() {
        scoreTracker.onStop()
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

/** The panel's games: [all] of them, and those [shown] under [chip]. */
private data class PanelList(val all: List<SportsLiveItem>, val chip: String?, val shown: List<SportsLiveItem>)

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
                is SportsLiveZapEvent.NoStream -> Toast.makeText(
                    context,
                    context.getString(R.string.sports_live_zap_no_stream, event.name),
                    Toast.LENGTH_SHORT
                ).show()
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
