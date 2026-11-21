package com.nuvio.tv.ui.screens.sportslive.dual

import com.nuvio.tv.core.sportslive.SportsLiveLinkMarks
import com.nuvio.tv.core.sportslive.GameEndReason
import com.nuvio.tv.core.sportslive.SportsLiveEndCountdown
import com.nuvio.tv.core.sportslive.SportsLiveEndMove
import com.nuvio.tv.core.sportslive.SportsLiveMyGames
import com.nuvio.tv.core.sportslive.SportsLiveStillWatching
import com.nuvio.tv.core.sportslive.alerts.RemindersState
import com.nuvio.tv.core.sportslive.SportsLiveGameEndAction
import com.nuvio.tv.core.sportslive.SportsLiveGameWatch
import com.nuvio.tv.core.sportslive.SportsLiveTriedGames
import com.nuvio.tv.core.sportslive.scores.ScoreChips
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.core.sportslive.scores.WatchingNow
import com.nuvio.tv.core.sportslive.scores.LiveScoresRepository
import com.nuvio.tv.core.sportslive.SportsLiveEndedGames
import com.nuvio.tv.core.sportslive.SportsLiveLinkVerdicts
import com.nuvio.tv.ui.screens.sportslive.SportsLiveEndCard
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import com.nuvio.tv.core.sportslive.SportsLiveReadyMark
import com.nuvio.tv.core.sportslive.SportsLiveGameReadiness
import com.nuvio.tv.core.sportslive.displayName
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.debrid.DirectDebridResolver
import com.nuvio.tv.core.sportslive.SportsLiveRecoveryBudget
import com.nuvio.tv.core.sportslive.SportsLiveRecoveryLadder
import com.nuvio.tv.core.sportslive.SportsLiveStartupTimeout
import com.nuvio.tv.core.sportslive.SportsLiveStreamThresholds
import com.nuvio.tv.core.sportslive.SportsLiveStreamHealth
import com.nuvio.tv.core.sportslive.SportsLiveWorkingLinks
import com.nuvio.tv.data.local.SportsLivePreferencesDataStore
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.repository.SportsLiveRepository
import com.nuvio.tv.ui.screens.sportslive.SportsLiveZapResolver
import com.nuvio.tv.ui.screens.sportslive.SportsLiveZapSession
import com.nuvio.tv.ui.screens.sportslive.SportsLiveZapSessions
import com.nuvio.tv.ui.screens.sportslive.ZapPanelRow
import com.nuvio.tv.ui.screens.sportslive.sportsLiveLightPlayable
import com.nuvio.tv.ui.screens.sportslive.preview.SportsLiveDecoderLimit
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The games dual screen opens with, and the list Up/Down zap through (a snapshot taken at launch). */
data class SportsLiveDualSession(
    val left: SportsLiveItem,
    val right: SportsLiveItem,
    val items: List<SportsLiveItem>,
    /** The filter (sport, or channel group) [items] were listed under, kept by the zap session OK starts. */
    val filter: String?,
    /** The tab [items] come from (Live now or Channels), kept by the zap session OK starts. */
    val tab: SportsLiveTab,
    /** The stream the full player was playing for [left] (#65): the left pane plays it as is, with no lookup. */
    val leftStream: Stream? = null,
    /** Every game of [tab] (on Live now, whatever [filter]), for the zap session OK starts (#68). */
    val zapItems: List<SportsLiveItem> = items,
    /** The game whose full player opened dual screen; null from the section. Back keeps the game with sound ([SportsLiveDualViewModel.keepOnBack]). */
    val backTo: SportsLiveItem? = null,
    /** "Watch with another game" (#57) paired [right] by itself: "Paired with ‹game› · Hold OK to change" shows. */
    val pairedHint: Boolean = false,
    /** Card keys of games known to be over when dual opened (Just ended, ESPN finals): never offered. */
    val finishedKeys: Set<String> = emptySet()
)

/**
 * "Watch with another game" for [chosen], from a Live now or Channels list ([items], shown on [tab]
 * under [filter]): the picker lists [candidates], and the pick starts a [SportsLiveDualSession] over [items].
 */
data class SportsLiveDualPick(
    val chosen: SportsLiveItem,
    val items: List<SportsLiveItem>,
    val filter: String?,
    val tab: SportsLiveTab,
    val endedKeys: Set<String> = emptySet(),
    /** The stream the full player plays for [chosen], handed to its pane (#65); null from the section. */
    val chosenStream: Stream? = null,
    /** Every game of [tab], kept by the session for the zap session OK starts (#68). */
    val zapItems: List<SportsLiveItem> = items,
    /** The player panel's rows by stream key: the picker draws logos and score chips from them (M11). */
    val rows: Map<String, ZapPanelRow> = emptyMap(),
    /** Picked in [chosen]'s full player: Back from dual screen returns to it (M11). */
    val fromPlayer: Boolean = false
) {
    /** The other games of the list that are not known to have ended; never [chosen] or one of its sources. */
    val candidates: List<SportsLiveItem> by lazy { items.filter(::isCandidate) }

    // Built once per pick, not once per item checked.
    private val chosenKeys: Set<String> by lazy { chosen.sourceKeys() }

    fun session(second: SportsLiveItem): SportsLiveDualSession =
        SportsLiveDualSession(
            left = chosen,
            right = second,
            items = items.toList(),
            filter = filter,
            tab = tab,
            leftStream = chosenStream,
            zapItems = zapItems.toList(),
            backTo = chosen.takeIf { fromPlayer }
        )

    /** The session pairing [chosen] with [second]; null when [second] is [chosen], shares a source with it, or has ended. */
    fun pairWith(second: SportsLiveItem): SportsLiveDualSession? = if (isCandidate(second)) session(second) else null

    private fun isCandidate(item: SportsLiveItem): Boolean =
        !item.sharesSourceWith(chosenKeys) && !item.hasEnded(endedKeys)
}

/**
 * "Pick another to watch" (live.24): [pool]'s games in its order (the ranked live games first), each once, none sharing
 * a source with the games on screen ([onScreen]), known to be over ([over]: any source) or with no stream left
 * ([ended], the probe's verdicts: every source, [SportsLiveItem.hasEnded]).
 */
internal fun dualPickerCandidates(
    pool: List<SportsLiveItem>,
    onScreen: Set<String>,
    over: Set<String>,
    ended: Set<String> = emptySet()
): List<SportsLiveItem> {
    val taken = HashSet(onScreen)
    return pool.filter { item ->
        val keys = item.sourceKeys()
        val keep = keys.none { it in taken || it in over } && !item.hasEnded(ended)
        if (keep) taken += keys
        keep
    }
}

/**
 * The games dual screen's ▲ ▼ step through (live.24): [frozen], the order taken at the first zap, kept as it is (an
 * ended or unavailable game is skipped in place by the zap, never removed, so its neighbours never shift); then the
 * games of [now] it does not hold yet, at the end: games that started since, and every game played in this session
 * (so a game zapped away from is always reachable again). Each game once. With nothing frozen yet: [now] in its order.
 */
internal fun dualZapLineup(frozen: List<SportsLiveItem>, now: List<SportsLiveItem>): List<SportsLiveItem> {
    val taken = HashSet<String>()
    return (frozen + now).filter { item ->
        val keys = item.sourceKeys()
        val keep = keys.none { it in taken }
        if (keep) taken += keys
        keep
    }
}

/** Dual screen is offered on Live now and Channels only. */
internal fun SportsLiveTab.offersDual(): Boolean = this == SportsLiveTab.LIVE_NOW || this == SportsLiveTab.CHANNELS

/**
 * True when this card and [other] play the same game from a common source: one of the two, or one of
 * their merged [SportsLiveItem.alternates], has the same [SportsLiveItem.streamKey]. Such a card is
 * never paired with [other], in the picker or by a zap.
 */
internal fun SportsLiveItem.sharesSourceWith(other: SportsLiveItem): Boolean = sharesSourceWith(other.sourceKeys())

/** The stream keys of this card and its merged [SportsLiveItem.alternates]. */
/** A dual pane's key in [WatchingNow] (Q18). */
internal fun dualWatchOwner(side: Side): String = "dual:" + side.name

internal fun SportsLiveItem.sourceKeys(): Set<String> = (listOf(this) + alternates).mapTo(HashSet()) { it.streamKey }

/** True when this card or one of its alternates has one of [otherKeys] (see [sourceKeys]). */
internal fun SportsLiveItem.sharesSourceWith(otherKeys: Set<String>): Boolean {
    return streamKey in otherKeys || alternates.any { it.streamKey in otherKeys }
}

/** The dual screen's starting games, process-wide like the zap session; not route arguments. */
internal object SportsLiveDualSessions {
    @Volatile
    private var session: SportsLiveDualSession? = null

    fun start(session: SportsLiveDualSession) {
        this.session = session
    }

    /** The session, handed over once: the dual ViewModel keeps it, so the list is not held process-wide. */
    fun take(): SportsLiveDualSession? = session.also { session = null }

    fun clear() {
        session = null
    }
}

/**
 * Two Sports & Live games side by side. Each pane looks its stream up with its own job, so zapping
 * one pane cancels only that pane's lookup. The screen plays [state] with [DualPlayers] and reports
 * first frames and errors back.
 */
@HiltViewModel
class SportsLiveDualViewModel internal constructor(
    private val resolve: suspend (SportsLiveItem) -> Stream?,
    private val endedKeys: () -> Set<String>,
    session: SportsLiveDualSession?,
    /** Whether a pane can play the full player's stream as is (a plain url); else the pane looks one up. */
    private val handOffPlayable: suspend (Stream) -> Boolean = { true },
    /** Which links work for each game (#65): the panes' outcomes are remembered here. */
    private val workingLinks: SportsLiveWorkingLinks = SportsLiveWorkingLinks.shared,
    /**
     * The next link a recovering pane may switch to for a game, given the links already tried there
     * (#138); null when none is left.
     */
    private val recoveryLookup: suspend (item: SportsLiveItem, tried: Set<String>) -> Stream? = { _, _ -> null },
    /** Automatic recoveries each game may have (#138). */
    private val recoveryBudget: SportsLiveRecoveryBudget = SportsLiveRecoveryBudget(),
    /** How many links a game has, as far as known without a request (#138); 0 when unknown. */
    private val linkCount: suspend (SportsLiveItem) -> Int = { 0 },
    /** Tells a pane's stream stopping at its game's end from a failure (#161); null: never an end (tests). */
    private val gameWatch: SportsLiveGameWatch? = null,
    /** "When a game ends" (#161). */
    endActions: Flow<SportsLiveGameEndAction> = flowOf(SportsLiveGameEndAction.DEFAULT),
    /** The Scores setting: an ended pane's card says the final score under Show only. */
    scoresModes: Flow<ScoresMode?> = flowOf(null),
    /**
     * A pane game's strictly matched score now (delayed and stamped with spoiler protection), or null: the ended
     * pane's card reads its final from it (live.23 TV test: a pane opened as the second game had none).
     */
    private val scoreOf: (SportsLiveItem) -> ScoreSnapshot? = { null },
    /** The profile's saved layout (#123); null: never chosen (split). */
    private val loadLayout: suspend () -> DualLayout? = { null },
    private val saveLayout: suspend (DualLayout) -> Unit = {},
    /**
     * Card keys of the games of a list Live now shows over now (F2: ESPN finals, "Probably ended" estimates, dead
     * link checks), with every source of a merged card; [scoresOff]: the Scores setting is Off.
     */
    private val overKeys: (items: List<SportsLiveItem>, scoresOff: Boolean) -> Set<String> = { _, _ -> emptySet() },
    /** True for a game saved with Watch later (#151): no next-game pick plays it live (#184). */
    private val savedForLater: () -> (SportsLiveItem) -> Boolean = { { false } },
    /** ✓ ready and ⚠ no working stream (#147), read by the next-game picks. */
    private val readiness: SportsLiveGameReadiness = SportsLiveGameReadiness.shared,
    /** Games tried and failed this session (#138 follow-up). */
    private val triedGames: SportsLiveTriedGames = SportsLiveTriedGames.shared,
    /** Which games are mine (#161, research 6): an ended pane moves by itself only into one of them showing "▶ Ready". */
    reminders: Flow<RemindersState> = flowOf(RemindersState()),
    /** "Are you still watching?" after a few automatic moves or hours with no key press (#161). */
    private val stillWatching: SportsLiveStillWatching = SportsLiveStillWatching.shared,
    /**
     * Every live game of My sports and followed teams, in importance order (J2, [com.nuvio.tv.core.sportslive.SportsLiveImportance]):
     * what "Pick another to watch" offers (live.24), not only the list dual screen was opened from.
     */
    liveGames: Flow<List<SportsLiveItem>> = flowOf(emptyList()),
    /**
     * Called once per entry: true when the keys hint shows this time (the profile's first [DUAL_KEYS_HINT_OPENS]
     * entries, [shouldShowDualKeysHint]), which is then counted.
     */
    private val claimKeysHint: suspend () -> Boolean = { true }
) : ViewModel() {

    @Inject
    constructor(
        resolver: SportsLiveZapResolver,
        repo: SportsLiveRepository,
        directDebridResolver: DirectDebridResolver,
        preferences: SportsLivePreferencesDataStore,
        liveScores: LiveScoresRepository
    ) : this(
        // A pane plays a plain url only: no Stream screen preparation (server, YouTube, debrid) or torrent.
        resolve = { item ->
            resolver.resolve(item)?.takeIf { stream -> sportsLiveLightPlayable(stream, directDebridResolver) }
        },
        endedKeys = { repo.endedKeys.value },
        session = SportsLiveDualSessions.take(),
        handOffPlayable = { stream -> sportsLiveLightPlayable(stream, directDebridResolver) },
        recoveryLookup = resolver::recoveryStream,
        recoveryBudget = SportsLiveRecoveryBudget.shared,
        linkCount = resolver::cachedLinkCount,
        gameWatch = SportsLiveGameWatch.shared,
        endActions = preferences.gameEndAction,
        scoresModes = preferences.scoreSettings.map { it.mode },
        scoreOf = { item -> ScoreChips.matchedEvent(item, liveScores.scores.value[item.streamKey]) },
        loadLayout = { preferences.dualLayout()?.let { name -> DualLayout.entries.firstOrNull { it.name == name } } },
        saveLayout = { layout -> preferences.setDualLayout(layout.name) },
        overKeys = { list, scoresOff ->
            SportsLiveEndedGames.shared.overSourceKeys(
                list, liveScores.scores.value, System.currentTimeMillis(),
                SportsLiveEndedGames.estimateGate(scoresOff, liveScores.unmatched.value)
            ) + SportsLiveLinkVerdicts.shared.deadKeys
        },
        savedForLater = { sportsLiveSavedForLater(liveScores.protection.value, liveScores.scores.value) },
        reminders = preferences.reminders,
        liveGames = repo.liveNow(limit = PICKER_LIMIT, forHomeRow = false),
        claimKeysHint = { preferences.claimDualKeysHint(::shouldShowDualKeysHint) }
    )

    private val items: List<SportsLiveItem> = session?.items.orEmpty()
    private val zapItems: List<SportsLiveItem> = session?.zapItems.orEmpty()
    private val filter: String? = session?.filter
    private val tab: SportsLiveTab = session?.tab ?: SportsLiveTab.LIVE_NOW
    private val finishedKeys: Set<String> = session?.finishedKeys.orEmpty()

    private val _state = MutableStateFlow(session?.let { DualState(DualPane(it.left), DualPane(it.right)) })

    /** Null when there is no session (process death): the screen leaves. */
    val state: StateFlow<DualState?> = _state.asStateFlow()

    private val _keysHint = MutableStateFlow(false)

    /**
     * The keys hint shows for [KEYS_HINT_MS] after the screen opens, on the profile's first [DUAL_KEYS_HINT_OPENS]
     * entries only (user, live.24), until ◀ ▶ or OK ([hideKeysHint]).
     */
    val keysHint: StateFlow<Boolean> = _keysHint.asStateFlow()

    private val _pairHint = MutableStateFlow<String?>(null)

    /** "Paired with ‹game›" (#57): the auto-picked game's name, for [PAIR_HINT_MS] after the screen opens. */
    val pairHint: StateFlow<String?> = _pairHint.asStateFlow()

    private val _pairPicker = MutableStateFlow<List<SportsLiveItem>?>(null)

    /** The games the right pane may change to (#57), while its picker is open; null when closed. */
    val pairPicker: StateFlow<List<SportsLiveItem>?> = _pairPicker.asStateFlow()

    // MENU was held this session: the saved layout read late does not undo it.
    private var layoutChosen = false

    private val _layoutReady = MutableStateFlow(false)

    /**
     * Whether the panes' layout is known: the profile's saved layout has been read (or could not be), or MENU
     * was held. The screen places the pane views only then, so each pane's video surface is first laid out at
     * its real tile: opened straight into big + small, the big pane's SurfaceView was created at the split
     * tile and resized before a player drew into it, and kept drawing the video at the split tile's size.
     */
    val layoutReady: StateFlow<Boolean> = _layoutReady.asStateFlow()

    private val jobs = mutableMapOf<Side, Job>()

    /** Every game a pane has played this session, by stream key, in order: ▲ ▼ can always get back to one (live.24). */
    private val played = LinkedHashMap<String, SportsLiveItem>()

    /** Stream keys of the games whose decoder failure has had its one retry. */
    private val deviceLimitRetried = mutableSetOf<String>()

    /**
     * Streams handed over by the full player (#65), by game stream key: such a game's pane plays it
     * with no lookup, on entry and whenever it loads again, until it fails there.
     */
    private val handOffs = mutableMapOf<String, Stream>()

    /** How long a pane's link has actually played (advancing, not paused or buffering). */
    private class PlayClock(val stream: Stream) {
        var playedMs = 0L
        var sinceMs: Long? = null

        fun stop(nowMs: Long) {
            sinceMs?.let { playedMs += nowMs - it }
            sinceMs = null
        }
    }

    /** Each pane game's play clock, by stream key; kept across a swap, a restart and a decoder retry. */
    private val clocks = mutableMapOf<String, PlayClock>()

    /** Each pane game's recovery steps (#138), by stream key: a pane's recovery never touches the other. */
    private val ladders = mutableMapOf<String, SportsLiveRecoveryLadder>()

    /** The links each pane game's recoveries have given up on (#138), by stream key. */
    private val tried = mutableMapOf<String, MutableSet<String>>()

    /** Pane games (by stream key) whose starting link had a request fail: a shorter start-up time (#138). */
    private val startRequestFailed = mutableSetOf<String>()

    /** What each pane game's link has loaded so far (#158), by stream key, for its start-up timeout. */
    private val loadProgress = mutableMapOf<String, SportsLiveStartupTimeout.Progress>()

    @Volatile
    private var endAction = SportsLiveGameEndAction.DEFAULT

    @Volatile
    private var scoresMode: ScoresMode? = null

    @Volatile
    private var remindersNow = RemindersState()

    @Volatile
    private var liveGamesNow: List<SportsLiveItem> = emptyList()

    init {
        // Q18 (#85): both panes' games are being watched: no score or clock from us, the others' scores wait.
        viewModelScope.launch {
            _state.collect { state ->
                Side.entries.forEach { side ->
                    val item = state?.pane(side)?.item
                    if (item == null) WatchingNow.shared.stop(dualWatchOwner(side))
                    else WatchingNow.shared.watch(dualWatchOwner(side), item.sourceKeys(), item.addonId)
                }
            }
        }
        viewModelScope.launch { endActions.collect { endAction = it } }
        viewModelScope.launch { reminders.collect { remindersNow = it } }
        viewModelScope.launch { scoresModes.collect { scoresMode = it } }
        viewModelScope.launch { liveGames.collect { liveGamesNow = it } }
        session?.let { s -> s.leftStream?.let { handOffs[s.left.streamKey] = it } }
        session?.let {
            viewModelScope.launch {
                // A layout already changed with MENU held before this read wins.
                val saved = runCatching { loadLayout() }.getOrNull()
                if (saved != null && !layoutChosen) dispatch(DualAction.SetLayout(saved))
                _layoutReady.value = true
            }
            load(Side.LEFT, it.left)
            load(Side.RIGHT, it.right)
            if (it.pairedHint) {
                _pairHint.value = it.right.displayName
                viewModelScope.launch {
                    delay(PAIR_HINT_MS)
                    _pairHint.value = null
                }
            }
            // The profile's first few entries (user, live.24): the model is shown where it is used, briefly.
            viewModelScope.launch {
                if (!runCatching { claimKeysHint() }.getOrDefault(false)) return@launch
                _keysHint.value = true
                delay(KEYS_HINT_MS)
                _keysHint.value = false
            }
        }
    }

    /** ◀ ▶ or OK was pressed: the keys hint has done its job. */
    fun hideKeysHint() {
        _keysHint.value = false
    }

    fun highlight(side: Side) = dispatch(DualAction.Highlight(side))

    // The pane the open picker changes (#57): the one without the ring (Hold OK › Change the other game).
    private var pickerSide = Side.RIGHT

    /**
     * Opens [side]'s picker ("Pick another to watch", live.24): every live game of My sports and followed teams in
     * importance order, not known to be over, not already on screen and sharing no source with either pane
     * ([dualPickerCandidates]). A Channels session lists its channels after them (no channel is a ranked game), and
     * before the ranked list has loaded the session's list stands in. Nothing is looked up until one is picked.
     */
    fun openPairPicker(side: Side = Side.RIGHT) {
        val state = _state.value ?: return
        pickerSide = side
        val pool = rankedPool()
        val over = finishedKeys + overKeys(pool, scoresMode == ScoresMode.OFF)
        _pairHint.value = null
        _pairPicker.value = dualPickerCandidates(pool, state.left.item.sourceKeys() + state.right.item.sourceKeys(), over, endedKeys())
    }

    /**
     * Every live game of My sports and followed teams in importance order (live.24); a Channels session's channels after
     * them, and the session's list while the ranked one has not loaded.
     */
    private fun rankedPool(): List<SportsLiveItem> {
        val ranked = liveGamesNow
        val listed = if (tab == SportsLiveTab.CHANNELS || ranked.isEmpty()) items else emptyList()
        return ranked + listed
    }

    fun closePairPicker() {
        _pairPicker.value = null
    }

    /** [item] picked in a pane's picker: that pane plays it (one lookup, the cache's when fresh). */
    fun pickPair(item: SportsLiveItem) {
        _pairPicker.value = null
        val state = _state.value ?: return
        if (item.sharesSourceWith(state.left.item) || item.sharesSourceWith(state.right.item)) return
        settle(state.pane(pickerSide).item)
        forgetRecovery(item)
        load(pickerSide, item)
    }

    /**
     * Watch on an alert card (#136; user, live.24): the alert's game goes into the pane WITHOUT sound (in big + small,
     * the small one), as that pane's zap does; the game with sound plays on untouched. A game a pane already shows
     * changes nothing. False with no session.
     */
    fun watchFromAlert(item: SportsLiveItem): Boolean {
        val state = _state.value ?: return false
        if (item.sharesSourceWith(state.left.item) || item.sharesSourceWith(state.right.item)) return true
        // The ring stays on the game heard (a failed highlighted pane hands it the ring first), so the sound never moves.
        val audible = state.audibleSide
        if (state.highlighted != audible) highlight(audible)
        val quiet = audible.other
        settle(state.pane(quiet).item)
        forgetRecovery(item)
        load(quiet, item)
        return true
    }

    /**
     * MENU held (#123): split ↔ big + small, remembered for the profile. Both players keep playing. Into big + small
     * the game with the sound goes large (F13): heard in the right pane, it trades places with the left one.
     */
    fun toggleLayout() {
        val state = _state.value ?: return
        val layout = state.layout.other
        layoutChosen = true
        dispatch(DualAction.SetLayout(layout))
        if (layout == DualLayout.BIG_SMALL && state.audibleSide == Side.RIGHT) swap()
        _layoutReady.value = true
        viewModelScope.launch { runCatching { saveLayout(layout) } }
    }

    /** MENU: the panes trade places, each keeping its lookup. */
    fun swap() {
        if (_state.value == null) return
        val left = jobs.remove(Side.LEFT)
        val right = jobs.remove(Side.RIGHT)
        left?.let { jobs[Side.RIGHT] = it }
        right?.let { jobs[Side.LEFT] = it }
        dispatch(DualAction.Swap)
    }

    /**
     * Up/Down, CH+/CH-, next/previous: the highlighted pane moves to the next (or previous) game of the list, wrapping,
     * that is not known to have ended, not shown in the other pane and not known dead this session (tried and failed,
     * ⚠ no working stream, or every known link failed). A pane whose recovery gave up (its error card) zaps the same
     * way, its game counted as tried and failed, so flipping on through dead games never sticks (live.19 TV report)
     * and never comes back to one. False when no such game is left.
     */
    fun zap(forward: Boolean): Boolean {
        val state = _state.value ?: return false
        val side = state.highlighted
        if (state.pane(side).status == PaneStatus.Error) triedGames.noteFailed(state.pane(side).item)
        val target = zapTarget(state.pane(side).item, state.pane(side.other).item, forward) ?: return false
        settle(state.pane(side).item)
        forgetRecovery(target)
        load(side, target)
        return true
    }

    /** ✓ ready (#147: a check, a race, a first frame) or a link known to play. */
    private fun knownGood(item: SportsLiveItem): Boolean =
        readiness.of(item) == SportsLiveReadyMark.READY || workingLinks.goodStream(item.id) != null

    /** Card keys of the games over now (F2): the probe's ended games, those known over when dual opened, and Live now's. */
    private fun overNow(): Set<String> = endedKeys() + finishedKeys + overKeys(items, scoresMode == ScoresMode.OFF)

    /**
     * "Close this side" on the highlighted pane once its recovery gave up (#138): the other pane's game
     * and playing stream, for the full player; null when the highlighted pane has not failed or the
     * other pane does not play (Back leaves then, as before).
     */
    fun closeFailedSide(): Pair<SportsLiveItem, Stream>? {
        val state = _state.value ?: return null
        if (state.pane(state.highlighted).status != PaneStatus.Error) return null
        val other = state.pane(state.highlighted.other)
        return other.okStream?.let { other.item to it }
    }

    /**
     * [item]'s pane stream went bad (#138): after its first frame (a freeze, a long buffering, an
     * unexpected end, an error), or a link a recovery tried did not start. The link counts as failed
     * (#65), then the pane's ladder says what to do: load it again as is, switch to the next link for
     * the game, or offer the choices. The other pane is never touched.
     */
    fun onUnhealthy(item: SportsLiveItem, problem: SportsLiveStreamHealth.Problem) {
        val state = _state.value ?: return
        val side = state.sideOf(item) ?: return
        val pane = state.pane(side)
        val stream = pane.stream ?: return
        // The pane's game is over (#161): its end card, never the recovery ladder; the other pane plays on.
        if (pane.status != PaneStatus.Ended) {
            // The game's score as the full player notes it, so the end is told from a failure as it is there.
            scoreOf(item)?.let { gameWatch?.note(item.id, it) }
            gameWatch?.failureIsEnd(item.id, item)?.let { reason ->
                endPane(side, item, reason)
                return
            }
        }
        // A link that never started (or whose host is unknown) recovers from Loading too.
        val startFailure = pane.status == PaneStatus.Loading &&
            (problem == SportsLiveStreamHealth.Problem.NO_START || problem == SportsLiveStreamHealth.Problem.DEAD_LINK)
        if (pane.status != PaneStatus.Playing && !pane.reconnecting && !startFailure) return
        linkFailed(item, stream, problem)
        SportsLiveWorkingLinks.linkKey(stream.getStreamUrl())?.let { tried.getOrPut(item.streamKey) { mutableSetOf() } += it }
        val ladder = ladders.getOrPut(item.streamKey) { SportsLiveRecoveryLadder(recoveryBudget, workingLinks.nowMs) }
        when (ladder.decide(item.id, stream.getStreamUrl(), problem)) {
            SportsLiveRecoveryLadder.Step.RETRY_SAME -> {
                dispatch(DualAction.RetrySame(item))
                // A link that has not started yet gets its first-frame timeout again.
                if (pane.status == PaneStatus.Loading) startTimeout(side, item)
            }
            SportsLiveRecoveryLadder.Step.NEXT_LINK -> switchLink(side, item)
            SportsLiveRecoveryLadder.Step.CHOICES -> {
                jobs.remove(side)?.cancel()
                dispatch(DualAction.Exhausted(item))
            }
        }
    }

    /**
     * [item]'s pane game ended ([reason], #161): its player stops and its pane shows the end card, Up next picked as
     * the full player picks it ([SportsLiveNextGame], never the other pane's game). The 10 s countdown runs only
     * when "When a game ends" is Move, this pane had the sound and Up next is one of my games showing "▶ Ready"
     * ([SportsLiveEndCountdown.move]); otherwise the card waits for OK, or asks "Are you still watching?" when the
     * guard holds the move back. The other pane is never touched.
     */
    private fun endPane(side: Side, item: SportsLiveItem, reason: GameEndReason) {
        val state = _state.value ?: return
        jobs.remove(side)?.cancel()
        settle(item)
        val hasSound = state.audibleSide == side
        val other = state.pane(side.other).item
        val lineup = items.filterNot { it.sharesSourceWith(other) }
        val saved = savedForLater()
        val reminded = remindersNow
        val isMine = { game: SportsLiveItem -> SportsLiveMyGames.isMine(game, reminded, saved) }
        val isReady = { game: SportsLiveItem -> readiness.of(game) == SportsLiveReadyMark.READY }
        val next = SportsLiveNextGame.pick(
            ended = item,
            lineup = lineup,
            scores = emptyMap(),
            finishedKeys = overNow(),
            tried = triedGames.ids(),
            // #183: a ✓ ready game first within each step; never one with no working stream (⚠, or every known link
            // failed), nor one saved with Watch later (#184).
            knownGood = ::knownGood,
            noStream = sportsLiveNoStreamIds(lineup, readiness, workingLinks),
            savedForLater = saved,
            mineReady = { game -> isReady(game) && isMine(game) }
        )
        val move = SportsLiveEndCountdown.move(
            endAction, next, hasSound = hasSound, isMine = isMine, isReady = isReady, mayAutoMove = stillWatching.mayAutoMove()
        )
        val countdown = move == SportsLiveEndMove.COUNTDOWN
        val card = SportsLiveEndCard(
            item = item,
            reason = reason,
            eventId = null,
            snapshot = scoreOf(item) ?: gameWatch?.snapshot(item.id),
            mode = scoresMode,
            next = next,
            upcoming = null,
            boxScore = false,
            streamStopped = true,
            countdown = countdown,
            countdownEndsAtMs = if (countdown) workingLinks.nowMs() + SportsLiveEndCountdown.DURATION_MS else null,
            stillWatching = move == SportsLiveEndMove.STILL_WATCHING
        )
        dispatch(DualAction.Ended(item, card))
        if (countdown) {
            jobs[side] = viewModelScope.launch {
                delay(SportsLiveEndCountdown.DURATION_MS)
                // The pane the ended game is in now: a swap (#123) may have moved it.
                if (_state.value?.sideOf(item)?.let { endWatchNext(it) } == true) stillWatching.onAutoMove()
            }
        }
    }

    /**
     * Watch next on an ended pane's card (#161), or its countdown running out: that pane ([side], else the highlighted
     * one) moves to Up next. False when the pane has no card or no next game.
     */
    fun endWatchNext(side: Side? = null): Boolean {
        val state = _state.value ?: return false
        val at = side ?: state.highlighted
        val pane = state.pane(at)
        if (pane.status != PaneStatus.Ended) return false
        val next = pane.end?.next ?: return false
        if (next.sharesSourceWith(state.pane(at.other).item)) return false
        jobs.remove(at)?.cancel()
        forgetRecovery(next)
        load(at, next)
        return true
    }

    /**
     * Back with an end card up (coordinator decision 4: a pane's card has no Stay): the highlighted pane's card closes,
     * else the other pane's; "Game over" stays. False when neither pane shows one.
     */
    fun closeEndCard(): Boolean {
        val state = _state.value ?: return false
        val side = listOf(state.highlighted, state.highlighted.other).firstOrNull { side ->
            state.pane(side).let { it.status == PaneStatus.Ended && it.end != null }
        } ?: return false
        jobs.remove(side)?.cancel()
        dispatch(DualAction.EndChanged(state.pane(side).item, null))
        return true
    }

    /** Stay on the highlighted ended pane's card: the card goes, "Game over" stays. False when none shows. */
    fun endStay(): Boolean {
        val state = _state.value ?: return false
        val pane = state.pane(state.highlighted)
        if (pane.status != PaneStatus.Ended || pane.end == null) return false
        jobs.remove(state.highlighted)?.cancel()
        dispatch(DualAction.EndChanged(pane.item, null))
        return true
    }

    /** Any key (#161): every running end countdown stops; the cards wait for OK. True when one ran. */
    fun cancelEndCountdowns(): Boolean {
        val state = _state.value ?: return false
        var any = false
        for (side in Side.entries) {
            val pane = state.pane(side)
            val card = pane.end ?: continue
            if (pane.status != PaneStatus.Ended || card.countdownEndsAtMs == null) continue
            jobs.remove(side)?.cancel()
            dispatch(DualAction.EndChanged(pane.item, card.copy(countdown = false, countdownEndsAtMs = null)))
            any = true
        }
        return any
    }

    /** [item]'s pane stream moves again: a recovery is over (#138). */
    fun onMoving(item: SportsLiveItem) {
        val pane = _state.value?.let { state -> state.sideOf(item)?.let(state::pane) } ?: return
        if (pane.reconnecting) dispatch(DualAction.Recovered(item))
    }

    /** The next link for [item]'s pane (#138), already-found links first; the choices when none is left. */
    private fun switchLink(side: Side, item: SportsLiveItem) {
        jobs.remove(side)?.cancel()
        dispatch(DualAction.Reconnecting(item))
        jobs[side] = viewModelScope.launch {
            val next = recoveryLookup(item, tried[item.streamKey].orEmpty().toSet())
            if (next == null) {
                dispatch(DualAction.Exhausted(item))
                return@launch
            }
            dispatch(DualAction.Switched(item, next))
            countLinks(item)
            awaitStart(item)
            timeOut(item)
        }
    }

    private fun startTimeout(side: Side, item: SportsLiveItem) {
        jobs.remove(side)?.cancel()
        jobs[side] = viewModelScope.launch {
            awaitStart(item)
            timeOut(item)
        }
    }

    /**
     * Waits out [item]'s pane link start-up time (#138, #158, [SportsLiveStartupTimeout]): at least 15 s
     * (8 s once one of its requests failed, [onRequestFailed]), longer while it keeps loading ([onLoadProgress]).
     */
    private suspend fun awaitStart(item: SportsLiveItem) {
        startRequestFailed -= item.streamKey
        loadProgress -= item.streamKey
        SportsLiveStartupTimeout.await(
            requestFailed = { item.streamKey in startRequestFailed },
            progress = { loadProgress[item.streamKey] }
        )
    }

    /** What [item]'s pane link has loaded so far (#158), sampled every second. */
    fun onLoadProgress(item: SportsLiveItem, progress: SportsLiveStartupTimeout.Progress) {
        loadProgress[item.streamKey] = progress
    }

    /** A manifest or segment request of [item]'s pane link failed before its first frame (#138). */
    fun onRequestFailed(item: SportsLiveItem) {
        val pane = _state.value?.let { state -> state.sideOf(item)?.let(state::pane) } ?: return
        if (pane.status == PaneStatus.Loading) startRequestFailed += item.streamKey
    }

    /** Looks up how many links [item]'s game has, for its starting card (#138); no request is made. */
    private fun countLinks(item: SportsLiveItem) {
        viewModelScope.launch {
            val count = linkCount(item)
            if (count > 0) dispatch(DualAction.LinkCount(item, count))
        }
    }

    /**
     * "Try next link" on the highlighted pane's starting card (#138): its link still starting is left
     * behind (not held against it, no recovery spent) and the next link for the game is tried; a late
     * first frame of the old link never counts ([onFirstFrame]). False when the pane is not starting.
     */
    fun tryNextLink(): Boolean {
        val state = _state.value ?: return false
        val side = state.highlighted
        val pane = state.pane(side)
        val stream = pane.stream
        if (!pane.isStarting || stream == null) return false
        SportsLiveWorkingLinks.linkKey(stream.getStreamUrl())?.let { tried.getOrPut(pane.item.streamKey) { mutableSetOf() } += it }
        switchLink(side, pane.item)
        return true
    }

    /** A game a pane moves to starts with a fresh recovery ladder (its budget stays, per game). */
    private fun forgetRecovery(item: SportsLiveItem) {
        ladders.remove(item.streamKey)
        tried.remove(item.streamKey)
    }

    /**
     * Before OK plays [item] full screen: the player zaps through the dual screen's list, and Back
     * from it returns here. With no session to start, any earlier one ends.
     */
    fun startZapSession(item: SportsLiveItem, returnToDual: Boolean = true) {
        // Back from that full screen returns here with both games (coordinator decision 1): the other pane's game.
        // Not when Back from dual is what plays it ([keepOnBack], Close this side): Back then leaves.
        val other = if (!returnToDual) null else _state.value?.let { state -> state.sideOf(item)?.let { state.pane(it.other).item } }
        // Every game of the tab, so the player's panel and zaps are not held to the filter (#68).
        val session = SportsLiveZapSession.of(
            items = zapItems,
            itemKey = item.streamKey,
            tab = tab,
            filter = filter,
            returnToHomeOnBack = false
        )?.copy(dualReturn = other?.let { SportsLiveDualReturn(it, items, zapItems, filter, tab, finishedKeys) })
        if (session != null) SportsLiveZapSessions.start(session) else SportsLiveZapSessions.clear()
    }

    /**
     * Back from dual screen (user, 2026-11-21): the game with sound ([DualState.audibleSide], the highlighted pane
     * unless it failed while the other plays) goes on full screen, the other one stops; never back to the list.
     * Returns that game and the stream it plays on, handed over as OK does (the pane's playing stream, else the one
     * the full player handed over unless it failed; null takes the section's play path). Null, so Back leaves, only
     * with no session or when the kept game is over.
     */
    fun keepOnBack(): Pair<SportsLiveItem, Stream?>? {
        val state = _state.value ?: return null
        val pane = state.pane(state.audibleSide)
        if (pane.status == PaneStatus.Ended) return null
        return pane.item to (pane.okStream ?: handOffs[pane.item.streamKey])
    }

    /**
     * [item]'s pane player showed its first frame of [stream] (null: whatever the pane plays). A late
     * first frame of a link the pane already left (Try next link, a recovery) is ignored (#138).
     */
    fun onFirstFrame(item: SportsLiveItem, stream: Stream? = null) {
        val side = _state.value?.sideOf(item) ?: return
        if (stream != null && !sameLink(_state.value?.pane(side)?.stream, stream)) return
        startRequestFailed -= item.streamKey
        // The link plays: its "didn't work" mark clears in the stream lists (#156).
        SportsLiveLinkMarks.shared.played(item.id, (stream ?: _state.value?.pane(side)?.stream)?.getStreamUrl())
        dispatch(DualAction.FirstFrame(item))
        // A game tried and failed plays after all: zaps may land on it again, as in the full player.
        triedGames.onPlaying(item.id)
        // Playing now: the pane's first-frame timeout is no longer needed, and a later decoder
        // failure gets its retry again.
        if (_state.value?.pane(side)?.status == PaneStatus.Playing) {
            jobs.remove(side)?.cancel()
            deviceLimitRetried.remove(item.streamKey)
        }
    }

    /**
     * [item]'s pane player started ([playing]) or stopped advancing (paused, buffering, stopped or
     * released): only the time in between counts as played for its link.
     */
    fun onIsPlayingChanged(item: SportsLiveItem, playing: Boolean) {
        val stream = _state.value?.let { state -> state.sideOf(item)?.let(state::pane) }?.stream ?: return
        val clock = clocks[item.streamKey]?.takeIf { it.stream.getStreamUrl() == stream.getStreamUrl() }
            ?: PlayClock(stream).also { clocks[item.streamKey] = it }
        val now = workingLinks.nowMs()
        if (playing) {
            if (clock.sinceMs == null) clock.sinceMs = now
        } else {
            clock.stop(now)
        }
    }

    /**
     * A pane's player failed; [decoderInit] when it could not get a decoder. After the first frame (or
     * on a link a recovery tries), the pane recovers ([onUnhealthy]) with [problem]: a burst of errors
     * skips the retry of the same link.
     */
    fun onPlayerError(
        item: SportsLiveItem,
        decoderInit: Boolean,
        problem: SportsLiveStreamHealth.Problem = SportsLiveStreamHealth.Problem.ERROR,
        /** The link that failed; a link the pane already left is ignored (#138). Null: the pane's own. */
        stream: Stream? = null
    ) {
        val state = _state.value ?: return
        val side = state.sideOf(item) ?: return
        val pane = state.pane(side)
        if (stream != null && !sameLink(pane.stream, stream)) return
        if (!decoderInit && (pane.status == PaneStatus.Playing || pane.reconnecting)) {
            onUnhealthy(item, problem)
            return
        }
        // A link that fails to start (#138) moves on to the next link, with no retry, as the full player does.
        if (!decoderInit && pane.status == PaneStatus.Loading && pane.stream != null) {
            val startProblem = if (problem == SportsLiveStreamHealth.Problem.DEAD_LINK) problem else SportsLiveStreamHealth.Problem.NO_START
            onUnhealthy(item, startProblem)
            return
        }
        // No decoder while the other pane holds one (starting or playing): the device cannot play two at once.
        val other = state.pane(side.other)
        val otherActive = other.stream != null &&
            (other.status == PaneStatus.Loading || other.status == PaneStatus.Playing)
        // A missing decoder is the device's fault, not the link's.
        if (!decoderInit) linkFailed(item, state.pane(side).stream)
        if (!decoderInit || !otherActive) {
            dispatch(DualAction.Failed(item))
            return
        }
        // A decoder can still be held by a player that is just closing (the full player opening this
        // screen); the pane starts again once before the device limit shows.
        if (deviceLimitRetried.add(item.streamKey)) {
            load(side, item, delayMs = DEVICE_LIMIT_RETRY_MS)
        } else {
            // The in-player panel no longer previews on this device (#83).
            SportsLiveDecoderLimit.note()
            dispatch(DualAction.DeviceLimit(item))
        }
    }

    /**
     * The screen created its players again (back from Home or another screen): panes that were
     * playing start again, so their first-frame timeout applies again.
     */
    fun onPlayersCreated() {
        val state = _state.value ?: return
        for (side in Side.entries) {
            val pane = state.pane(side)
            if (pane.status != PaneStatus.Playing) continue
            jobs.remove(side)?.cancel()
            dispatch(DualAction.Restarted(pane.item))
            jobs[side] = viewModelScope.launch {
                awaitStart(pane.item)
                timeOut(pane.item)
            }
        }
    }

    /**
     * The games ▲ ▼ step through (live.24, user TV report: zapping only cycled the 3 games of the filter dual opened
     * under, and a game zapped away from could not be reached again): the picker's ranked games, then every game played
     * in this session ([dualZapLineup]).
     */
    private fun zapLineup(): List<SportsLiveItem> =
        dualZapLineup(zapOrder, rankedPool() + played.values).also { zapOrder = it }

    /**
     * ▲ ▼'s order, frozen at the first zap (user, live.24: ▲ then ▼ always returns to the game you were on, whatever the
     * ranking does meanwhile); games that start later join at the end.
     */
    private var zapOrder: List<SportsLiveItem> = emptyList()

    private fun zapTarget(current: SportsLiveItem, other: SportsLiveItem, forward: Boolean): SportsLiveItem? {
        val items = zapLineup()
        if (items.isEmpty()) return null
        val over = finishedKeys + overKeys(items, scoresMode == ScoresMode.OFF)
        val ended = endedKeys()
        val failed = triedGames.ids()
        val noStream = sportsLiveNoStreamIds(items, readiness, workingLinks)
        val start = items.indexOfFirst { it.streamKey == current.streamKey }
        val step = if (forward) 1 else -1
        fun next(skipDead: Boolean): SportsLiveItem? {
            // Not in the list: the first step lands on the list's first (or last) game.
            var index = if (start >= 0) start else if (forward) -1 else items.size
            repeat(items.size) {
                index = Math.floorMod(index + step, items.size)
                val candidate = items[index]
                val dead = candidate.id in failed || candidate.alternates.any { it.id in failed } || candidate.id in noStream
                val skip = candidate.sharesSourceWith(current) ||
                    candidate.sharesSourceWith(other) ||
                    candidate.hasEnded(ended) ||
                    candidate.sourceKeys().any { it in over } ||
                    (skipDead && dead)
                if (!skip) return candidate
            }
            return null
        }
        // Games known dead this session are passed over while another remains; when only they are left the zap
        // still moves through them, each looked up afresh (the user keeps flipping, never stuck on one error).
        return next(skipDead = true) ?: next(skipDead = false)
    }

    /** [delayMs]: wait this long before the lookup (a retry). */
    private fun load(side: Side, item: SportsLiveItem, delayMs: Long = 0L) {
        jobs.remove(side)?.cancel()
        played.putIfAbsent(item.streamKey, item)
        gameWatch?.watch(item)
        dispatch(DualAction.ZapRequested(side, item))
        jobs[side] = viewModelScope.launch {
            if (delayMs > 0) delay(delayMs)
            // The full player's stream for this game plays as is: no lookup (#65).
            val stream = handOffs[item.streamKey]?.takeIf { handOffPlayable(it) } ?: resolve(item)
            dispatch(DualAction.Resolved(item, stream))
            if (stream != null) {
                countLinks(item)
                awaitStart(item)
                timeOut(item)
            }
        }
    }

    /**
     * No first frame in time (#138): a step of the pane's recovery ladder, so the next link for the
     * game is tried (its link is quarantined for the game); with none left, or the game's recoveries
     * spent, the pane offers the choices.
     */
    private fun timeOut(item: SportsLiveItem) {
        val pane = _state.value?.let { state -> state.sideOf(item)?.let(state::pane) }
        if (pane?.status == PaneStatus.Loading && pane.stream != null) {
            onUnhealthy(item, SportsLiveStreamHealth.Problem.NO_START)
            return
        }
        dispatch(DualAction.TimedOut(item))
    }

    /**
     * [stream] failed for [item]. Its played time counts first, so a link that played long enough
     * stays good after one failure; a quarantined link is not handed over again.
     */
    private fun linkFailed(item: SportsLiveItem, stream: Stream?, problem: SportsLiveStreamHealth.Problem? = null) {
        val url = stream?.getStreamUrl() ?: return
        settle(item)
        val quarantined = workingLinks.failed(item.id, url, problem)
        if (quarantined && handOffs[item.streamKey]?.getStreamUrl() == url) handOffs.remove(item.streamKey)
    }

    /** [item]'s pane stops playing it: a link that played long enough is remembered as good. */
    private fun settle(item: SportsLiveItem) {
        val clock = clocks.remove(item.streamKey) ?: return
        clock.stop(workingLinks.nowMs())
        workingLinks.played(item.id, clock.stream.getStreamUrl(), clock.playedMs)
    }

    // Time in the background is not counted: the players report a stop when released (ON_STOP).
    override fun onCleared() {
        _state.value?.let { state -> Side.entries.forEach { settle(state.pane(it).item) } }
        Side.entries.forEach { WatchingNow.shared.stop(dualWatchOwner(it)) }
        super.onCleared()
    }

    private fun dispatch(action: DualAction) {
        _state.update { it?.reduce(action) }
    }

    private fun sameLink(a: Stream?, b: Stream?): Boolean =
        a != null && b != null && a.getStreamUrl() == b.getStreamUrl()

    companion object {
        /**
         * A pane with no first frame this long after its stream was found moves on to the next link
         * (#138); 8 s once one of its requests failed ([SportsLiveStartupTimeout]).
         */
        const val FIRST_FRAME_TIMEOUT_MS = SportsLiveStreamThresholds.NO_FIRST_FRAME_MS

        /** A pane that found no decoder starts again once, this long later, before showing the device limit. */
        const val DEVICE_LIMIT_RETRY_MS = 1_000L

        /** How long the keys hint shows after the screen opens. */
        const val KEYS_HINT_MS = 4_000L

        /** How long "Paired with ‹game› · Hold OK to change" shows after "Watch with another game" (#57). */
        const val PAIR_HINT_MS = 3_000L

        /** The most live games "Pick another to watch" lists (live.24). */
        const val PICKER_LIMIT = 60
    }
}
