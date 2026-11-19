package com.nuvio.tv.ui.screens.sportslive.dual

import com.nuvio.tv.core.sportslive.displayName
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.debrid.DirectDebridResolver
import com.nuvio.tv.core.sportslive.SportsLiveRecoveryBudget
import com.nuvio.tv.core.sportslive.SportsLiveRecoveryLadder
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
    /** The game whose full player opened dual screen: Back plays it full screen again (M11); null from the section. */
    val backTo: SportsLiveItem? = null,
    /** "Watch with another game" (#57) paired [right] by itself: "Paired with ‹game› · ← to change" shows. */
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

/** Dual screen is offered on Live now and Channels only. */
internal fun SportsLiveTab.offersDual(): Boolean = this == SportsLiveTab.LIVE_NOW || this == SportsLiveTab.CHANNELS

/**
 * True when this card and [other] play the same game from a common source: one of the two, or one of
 * their merged [SportsLiveItem.alternates], has the same [SportsLiveItem.streamKey]. Such a card is
 * never paired with [other], in the picker or by a zap.
 */
internal fun SportsLiveItem.sharesSourceWith(other: SportsLiveItem): Boolean = sharesSourceWith(other.sourceKeys())

/** The stream keys of this card and its merged [SportsLiveItem.alternates]. */
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
    /** Counts this entry for the keys hint; true when the hint shows (see [keysHint]). */
    claimKeysHint: suspend () -> Boolean = { false },
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
    private val recoveryBudget: SportsLiveRecoveryBudget = SportsLiveRecoveryBudget()
) : ViewModel() {

    @Inject
    constructor(
        resolver: SportsLiveZapResolver,
        repo: SportsLiveRepository,
        directDebridResolver: DirectDebridResolver,
        preferences: SportsLivePreferencesDataStore
    ) : this(
        // A pane plays a plain url only: no Stream screen preparation (server, YouTube, debrid) or torrent.
        resolve = { item ->
            resolver.resolve(item)?.takeIf { stream -> sportsLiveLightPlayable(stream, directDebridResolver) }
        },
        endedKeys = { repo.endedKeys.value },
        session = SportsLiveDualSessions.take(),
        claimKeysHint = preferences::claimDualKeysHint,
        handOffPlayable = { stream -> sportsLiveLightPlayable(stream, directDebridResolver) },
        recoveryLookup = resolver::recoveryStream,
        recoveryBudget = SportsLiveRecoveryBudget.shared
    )

    private val items: List<SportsLiveItem> = session?.items.orEmpty()
    private val zapItems: List<SportsLiveItem> = session?.zapItems.orEmpty()
    private val filter: String? = session?.filter
    private val tab: SportsLiveTab = session?.tab ?: SportsLiveTab.LIVE_NOW
    private val backTo: SportsLiveItem? = session?.backTo
    private val finishedKeys: Set<String> = session?.finishedKeys.orEmpty()

    private val _state = MutableStateFlow(session?.let { DualState(DualPane(it.left), DualPane(it.right)) })

    /** Null when there is no session (process death): the screen leaves. */
    val state: StateFlow<DualState?> = _state.asStateFlow()

    private val _keysHint = MutableStateFlow(false)

    /** The keys hint shows for [KEYS_HINT_MS] after the screen opens, on the profile's first entries. */
    val keysHint: StateFlow<Boolean> = _keysHint.asStateFlow()

    private val _pairHint = MutableStateFlow<String?>(null)

    /** "Paired with ‹game›" (#57): the auto-picked game's name, for [PAIR_HINT_MS] after the screen opens. */
    val pairHint: StateFlow<String?> = _pairHint.asStateFlow()

    private val _pairPicker = MutableStateFlow<List<SportsLiveItem>?>(null)

    /** The games the right pane may change to (#57), while its picker is open; null when closed. */
    val pairPicker: StateFlow<List<SportsLiveItem>?> = _pairPicker.asStateFlow()

    private val jobs = mutableMapOf<Side, Job>()

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

    init {
        session?.let { s -> s.leftStream?.let { handOffs[s.left.streamKey] = it } }
        session?.let {
            load(Side.LEFT, it.left)
            load(Side.RIGHT, it.right)
            if (it.pairedHint) {
                _pairHint.value = it.right.displayName
                viewModelScope.launch {
                    delay(PAIR_HINT_MS)
                    _pairHint.value = null
                }
            }
            viewModelScope.launch {
                if (runCatching { claimKeysHint() }.getOrDefault(false)) {
                    _keysHint.value = true
                    delay(KEYS_HINT_MS)
                    _keysHint.value = false
                }
            }
        }
    }

    fun highlight(side: Side) = dispatch(DualAction.Highlight(side))

    /**
     * Left (#57): on the left pane, already highlighted, it opens the right pane's picker ("← to change"
     * in the pair hint); otherwise it highlights the left pane as before.
     */
    fun onLeft() {
        val state = _state.value ?: return
        if (state.highlighted == Side.LEFT) openPairPicker() else highlight(Side.LEFT)
    }

    /**
     * Opens the right pane's picker: the list's games not known to be over, not already on screen and
     * sharing no source with either pane. Nothing is looked up until one is picked.
     */
    fun openPairPicker() {
        val state = _state.value ?: return
        val ended = endedKeys()
        val onScreen = state.left.item.sourceKeys() + state.right.item.sourceKeys()
        _pairHint.value = null
        _pairPicker.value = items.filter { item ->
            !item.sharesSourceWith(onScreen) && !item.hasEnded(ended) && item.sourceKeys().none { it in finishedKeys }
        }
    }

    fun closePairPicker() {
        _pairPicker.value = null
    }

    /** [item] picked in the right pane's picker: that pane plays it (one lookup, the cache's when fresh). */
    fun pickPair(item: SportsLiveItem) {
        _pairPicker.value = null
        val state = _state.value ?: return
        if (item.sharesSourceWith(state.left.item) || item.sharesSourceWith(state.right.item)) return
        settle(state.right.item)
        forgetRecovery(item)
        load(Side.RIGHT, item)
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
     * Up/Down: the highlighted pane moves to the next (or previous) game of the list that is not known
     * to have ended and not shown in the other pane. False when there is none.
     */
    fun zap(forward: Boolean): Boolean {
        val state = _state.value ?: return false
        val side = state.highlighted
        val target = zapTarget(state.pane(side).item, state.pane(side.other).item, forward) ?: return false
        settle(state.pane(side).item)
        forgetRecovery(target)
        load(side, target)
        return true
    }

    /**
     * "Next live game" on a pane whose recovery gave up (#138): the highlighted pane moves to the game
     * "Watch with another game" would pick (#57: a followed team's, else by start time), never the other
     * pane's or one known to be over; a plain zap when there is none. False when nothing is left.
     */
    fun nextLiveGame(): Boolean {
        val state = _state.value ?: return false
        val side = state.highlighted
        val current = state.pane(side).item
        val other = state.pane(side.other).item
        val pick = SportsLiveSecondGame.pick(
            first = current,
            items = items.filterNot { it.sharesSourceWith(other) },
            scores = emptyMap(),
            finishedKeys = endedKeys() + finishedKeys
        ) ?: return zap(forward = true)
        settle(current)
        forgetRecovery(pick)
        load(side, pick)
        return true
    }

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
        if (pane.status != PaneStatus.Playing && !pane.reconnecting) return
        linkFailed(item, stream)
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
            delay(FIRST_FRAME_TIMEOUT_MS)
            timeOut(item)
        }
    }

    private fun startTimeout(side: Side, item: SportsLiveItem) {
        jobs.remove(side)?.cancel()
        jobs[side] = viewModelScope.launch {
            delay(FIRST_FRAME_TIMEOUT_MS)
            timeOut(item)
        }
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
    fun startZapSession(item: SportsLiveItem) {
        // Every game of the tab, so the player's panel and zaps are not held to the filter (#68).
        val session = SportsLiveZapSession.of(
            items = zapItems,
            itemKey = item.streamKey,
            tab = tab,
            filter = filter,
            returnToHomeOnBack = false
        )
        if (session != null) SportsLiveZapSessions.start(session) else SportsLiveZapSessions.clear()
    }

    /**
     * Back (M11): when a full player opened dual screen, its game and the stream to play it on (the
     * pane's playing stream, else the one the player handed over unless it failed; null takes the section's play path),
     * so Back returns to that single player, which replaces dual screen as OK does (#27). Null when dual
     * screen was opened elsewhere: Back leaves.
     */
    fun backToPlayer(): Pair<SportsLiveItem, Stream?>? {
        val item = backTo ?: return null
        val pane = _state.value?.let { state -> state.sideOf(item)?.let(state::pane) }
        return item to (pane?.okStream ?: handOffs[item.streamKey])
    }

    fun onFirstFrame(item: SportsLiveItem) {
        val side = _state.value?.sideOf(item) ?: return
        dispatch(DualAction.FirstFrame(item))
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
        problem: SportsLiveStreamHealth.Problem = SportsLiveStreamHealth.Problem.ERROR
    ) {
        val state = _state.value ?: return
        val side = state.sideOf(item) ?: return
        val pane = state.pane(side)
        if (!decoderInit && (pane.status == PaneStatus.Playing || pane.reconnecting)) {
            onUnhealthy(item, problem)
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
                delay(FIRST_FRAME_TIMEOUT_MS)
                timeOut(pane.item)
            }
        }
    }

    private fun zapTarget(current: SportsLiveItem, other: SportsLiveItem, forward: Boolean): SportsLiveItem? {
        if (items.isEmpty()) return null
        val ended = endedKeys()
        val start = items.indexOfFirst { it.streamKey == current.streamKey }
        val step = if (forward) 1 else -1
        // Not in the list: the first step lands on the list's first (or last) game.
        var index = if (start >= 0) start else if (forward) -1 else items.size
        repeat(items.size) {
            index = Math.floorMod(index + step, items.size)
            val candidate = items[index]
            val skip = candidate.sharesSourceWith(current) ||
                candidate.sharesSourceWith(other) ||
                candidate.hasEnded(ended)
            if (!skip) return candidate
        }
        return null
    }

    /** [delayMs]: wait this long before the lookup (a retry). */
    private fun load(side: Side, item: SportsLiveItem, delayMs: Long = 0L) {
        jobs.remove(side)?.cancel()
        dispatch(DualAction.ZapRequested(side, item))
        jobs[side] = viewModelScope.launch {
            if (delayMs > 0) delay(delayMs)
            // The full player's stream for this game plays as is: no lookup (#65).
            val stream = handOffs[item.streamKey]?.takeIf { handOffPlayable(it) } ?: resolve(item)
            dispatch(DualAction.Resolved(item, stream))
            if (stream != null) {
                delay(FIRST_FRAME_TIMEOUT_MS)
                timeOut(item)
            }
        }
    }

    /** No first frame in time: the pane shows an error, and its link is quarantined for the game. */
    private fun timeOut(item: SportsLiveItem) {
        val pane = _state.value?.let { state -> state.sideOf(item)?.let(state::pane) }
        // A link a recovery tried that did not start: the recovery goes on (#138).
        if (pane?.status == PaneStatus.Loading && pane.reconnecting) {
            onUnhealthy(item, SportsLiveStreamHealth.Problem.NO_START)
            return
        }
        if (pane?.status == PaneStatus.Loading) linkFailed(item, pane.stream)
        dispatch(DualAction.TimedOut(item))
    }

    /**
     * [stream] failed for [item]. Its played time counts first, so a link that played long enough
     * stays good after one failure; a quarantined link is not handed over again.
     */
    private fun linkFailed(item: SportsLiveItem, stream: Stream?) {
        val url = stream?.getStreamUrl() ?: return
        settle(item)
        val quarantined = workingLinks.failed(item.id, url)
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
        super.onCleared()
    }

    private fun dispatch(action: DualAction) {
        _state.update { it?.reduce(action) }
    }

    companion object {
        /** A pane with no first frame this long after its stream was found shows an error. */
        const val FIRST_FRAME_TIMEOUT_MS = 15_000L

        /** A pane that found no decoder starts again once, this long later, before showing the device limit. */
        const val DEVICE_LIMIT_RETRY_MS = 1_000L

        /** How long the keys hint shows after the screen opens. */
        const val KEYS_HINT_MS = 4_000L

        /** How long "Paired with ‹game› · ← to change" shows after "Watch with another game" (#57). */
        const val PAIR_HINT_MS = 3_000L
    }
}
