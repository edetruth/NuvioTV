package com.nuvio.tv.ui.screens.sportslive.dual

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.debrid.DirectDebridResolver
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.repository.SportsLiveRepository
import com.nuvio.tv.ui.screens.sportslive.SportsLiveZapResolver
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
    val items: List<SportsLiveItem>
)

/** The dual screen's starting games, process-wide like the zap session; not route arguments. */
internal object SportsLiveDualSessions {
    @Volatile
    private var session: SportsLiveDualSession? = null

    fun start(session: SportsLiveDualSession) {
        this.session = session
    }

    fun current(): SportsLiveDualSession? = session

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
        session = SportsLiveDualSessions.current()
    )

    private val items: List<SportsLiveItem> = session?.items.orEmpty()

    private val _state = MutableStateFlow(session?.let { DualState(DualPane(it.left), DualPane(it.right)) })

    /** Null when there is no session (process death): the screen leaves. */
    val state: StateFlow<DualState?> = _state.asStateFlow()

    private val jobs = mutableMapOf<Side, Job>()

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

    fun onFirstFrame(item: SportsLiveItem) {
        val side = _state.value?.sideOf(item) ?: return
        dispatch(DualAction.FirstFrame(item))
        // Playing now: the pane's first-frame timeout is no longer needed.
        if (_state.value?.pane(side)?.status == PaneStatus.Playing) jobs.remove(side)?.cancel()
    }

    /** A pane's player failed; [decoderInit] when it could not get a decoder. */
    fun onPlayerError(item: SportsLiveItem, decoderInit: Boolean) {
        val state = _state.value ?: return
        val side = state.sideOf(item) ?: return
        // No decoder while the other pane holds one (starting or playing): the device cannot play two at once.
        val other = state.pane(side.other)
        val otherActive = other.stream != null &&
            (other.status == PaneStatus.Loading || other.status == PaneStatus.Playing)
        dispatch(if (decoderInit && otherActive) DualAction.DeviceLimit(item) else DualAction.Failed(item))
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
            val skip = candidate.streamKey == current.streamKey ||
                candidate.streamKey == other.streamKey ||
                candidate.hasEnded(ended)
            if (!skip) return candidate
        }
        return null
    }

    private fun load(side: Side, item: SportsLiveItem) {
        jobs.remove(side)?.cancel()
        dispatch(DualAction.ZapRequested(side, item))
        jobs[side] = viewModelScope.launch {
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
    }
}
