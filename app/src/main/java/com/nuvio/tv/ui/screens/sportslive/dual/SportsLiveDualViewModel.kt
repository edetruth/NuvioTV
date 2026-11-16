package com.nuvio.tv.ui.screens.sportslive.dual

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.debrid.DirectDebridResolver
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.repository.SportsLiveRepository
import com.nuvio.tv.ui.screens.sportslive.SportsLiveZapResolver
import com.nuvio.tv.ui.screens.sportslive.SportsLiveZapSession
import com.nuvio.tv.ui.screens.sportslive.SportsLiveZapSessions
import com.nuvio.tv.ui.screens.sportslive.sportsLiveNeedsPreparation
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
    val tab: SportsLiveTab
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
    val endedKeys: Set<String> = emptySet()
) {
    /** The other games of the list that are not known to have ended; never [chosen] or one of its sources. */
    val candidates: List<SportsLiveItem> by lazy { items.filter(::isCandidate) }

    // Built once per pick, not once per item checked.
    private val chosenKeys: Set<String> by lazy { chosen.sourceKeys() }

    fun session(second: SportsLiveItem): SportsLiveDualSession =
        SportsLiveDualSession(left = chosen, right = second, items = items.toList(), filter = filter, tab = tab)

    /** The session pairing [chosen] with [second]; null when [second] is [chosen], shares a source with it, or has ended. */
    fun pairWith(second: SportsLiveItem): SportsLiveDualSession? = if (isCandidate(second)) session(second) else null

    private fun isCandidate(item: SportsLiveItem): Boolean =
        !item.sharesSourceWith(chosenKeys) && !item.hasEnded(endedKeys)
}

/** Dual screen is offered on Live now and Channels only. */
internal fun SportsLiveTab.offersDual(): Boolean = this == SportsLiveTab.LIVE_NOW || this == SportsLiveTab.CHANNELS

/**
 * The section's pick-second mode: OK on [item], shown on [tab] in [items] under [filter], after
 * [chosen] was picked first. The dual session, or null when OK does nothing: a tab without dual
 * screen, or an [item] that [SportsLiveDualPick.pairWith] refuses.
 */
internal fun sportsLivePickModeSession(
    chosen: SportsLiveItem,
    item: SportsLiveItem,
    tab: SportsLiveTab,
    items: List<SportsLiveItem>,
    filter: String?
): SportsLiveDualSession? =
    if (!tab.offersDual()) null else SportsLiveDualPick(chosen = chosen, items = items, filter = filter, tab = tab).pairWith(item)

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
    session: SportsLiveDualSession?
) : ViewModel() {

    @Inject
    constructor(
        resolver: SportsLiveZapResolver,
        repo: SportsLiveRepository,
        directDebridResolver: DirectDebridResolver
    ) : this(
        // A pane plays a plain url only: no Stream screen preparation (server, YouTube, debrid) or torrent.
        resolve = { item ->
            resolver.resolve(item)?.takeIf { stream ->
                !stream.isExternal() && !stream.isTorrent() && stream.getStreamUrl() != null &&
                    !sportsLiveNeedsPreparation(stream, directDebridResolver)
            }
        },
        endedKeys = { repo.endedKeys.value },
        session = SportsLiveDualSessions.take()
    )

    private val items: List<SportsLiveItem> = session?.items.orEmpty()
    private val filter: String? = session?.filter
    private val tab: SportsLiveTab = session?.tab ?: SportsLiveTab.LIVE_NOW

    private val _state = MutableStateFlow(session?.let { DualState(DualPane(it.left), DualPane(it.right)) })

    /** Null when there is no session (process death): the screen leaves. */
    val state: StateFlow<DualState?> = _state.asStateFlow()

    private val jobs = mutableMapOf<Side, Job>()

    /** Stream keys of the games whose decoder failure has had its one retry. */
    private val deviceLimitRetried = mutableSetOf<String>()

    init {
        session?.let {
            load(Side.LEFT, it.left)
            load(Side.RIGHT, it.right)
        }
    }

    fun highlight(side: Side) = dispatch(DualAction.Highlight(side))

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
        load(side, target)
        return true
    }

    /**
     * Before OK plays [item] full screen: the player zaps through the dual screen's list, and Back
     * from it returns here. With no session to start, any earlier one ends.
     */
    fun startZapSession(item: SportsLiveItem) {
        val session = SportsLiveZapSession.of(
            items = items,
            itemKey = item.streamKey,
            tab = tab,
            filter = filter,
            returnToHomeOnBack = false
        )
        if (session != null) SportsLiveZapSessions.start(session) else SportsLiveZapSessions.clear()
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

    /** A pane's player failed; [decoderInit] when it could not get a decoder. */
    fun onPlayerError(item: SportsLiveItem, decoderInit: Boolean) {
        val state = _state.value ?: return
        val side = state.sideOf(item) ?: return
        // No decoder while the other pane holds one (starting or playing): the device cannot play two at once.
        val other = state.pane(side.other)
        val otherActive = other.stream != null &&
            (other.status == PaneStatus.Loading || other.status == PaneStatus.Playing)
        if (!decoderInit || !otherActive) {
            dispatch(DualAction.Failed(item))
            return
        }
        // A decoder can still be held by a player that is just closing (the full player opening this
        // screen); the pane starts again once before the device limit shows.
        if (deviceLimitRetried.add(item.streamKey)) {
            load(side, item, delayMs = DEVICE_LIMIT_RETRY_MS)
        } else {
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
                dispatch(DualAction.TimedOut(pane.item))
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
            val stream = resolve(item)
            dispatch(DualAction.Resolved(item, stream))
            if (stream != null) {
                delay(FIRST_FRAME_TIMEOUT_MS)
                dispatch(DualAction.TimedOut(item))
            }
        }
    }

    private fun dispatch(action: DualAction) {
        _state.update { it?.reduce(action) }
    }

    companion object {
        /** A pane with no first frame this long after its stream was found shows an error. */
        const val FIRST_FRAME_TIMEOUT_MS = 15_000L

        /** A pane that found no decoder starts again once, this long later, before showing the device limit. */
        const val DEVICE_LIMIT_RETRY_MS = 1_000L
    }
}
