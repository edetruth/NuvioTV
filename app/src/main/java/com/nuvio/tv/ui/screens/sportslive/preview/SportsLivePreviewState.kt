package com.nuvio.tv.ui.screens.sportslive.preview

import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the details pane previews. */
sealed interface SportsLivePreviewState {
    data object Idle : SportsLivePreviewState

    /** The card has settled; its stream is being looked up. */
    data class Resolving(val item: SportsLiveItem) : SportsLivePreviewState

    /**
     * [stream] is loaded in the preview player. Until [rendering] (its first frame), the pane keeps
     * the art; from then on it shows the video and the "Preview" label.
     */
    data class Playing(
        val item: SportsLiveItem,
        val stream: Stream,
        val rendering: Boolean = false
    ) : SportsLivePreviewState

    /** The lookup found nothing to play: the pane says so at once, instead of a lingering wait. */
    data class NoStream(val item: SportsLiveItem) : SportsLivePreviewState
}

/** What the preview's lookup found for a card. */
sealed interface SportsLivePreviewLookup {
    /** A stream the light preview player can play. */
    data class Found(val stream: Stream) : SportsLivePreviewLookup

    /** A stream exists, but only the full player can play it (it needs preparation): no preview. */
    data object NotPreviewable : SportsLivePreviewLookup

    /** Nothing playable came back. */
    data object NoStream : SportsLivePreviewLookup
}

/** Upcoming has nothing to play and Replays would spoil results, so only these tabs preview. */
fun SportsLiveTab.offersPreview(): Boolean = this == SportsLiveTab.LIVE_NOW || this == SportsLiveTab.CHANNELS

/**
 * The hover preview: once the focused card has rested for [settleMs] on a tab that
 * [offersPreview], with the setting on and the screen visible, its stream is looked up with
 * [resolve]; a stream plays (muted) until its first frame, which must come within
 * [firstFrameTimeoutMs], and for at most [maxPlayMs] in all (#71: a preview left on a card must not
 * stream for hours). Ambient mode, the screensaver or a dream ([onAmbient]) and the screen leaving
 * ([onVisible]) stop it like any other input. Any change of input (focus, tab, setting, visibility) drops the preview
 * at once and starts a fresh settle when the new inputs allow one, so scrolling past cards never
 * looks anything up. A stream only the full player can play, an error or a missed first frame
 * leave the art in place, silently, until the focus moves; no stream at all shows as [NoStream]
 * until then. A preview that ran its [maxPlayMs] also leaves the art until the focus moves.
 */
class SportsLivePreviewController(
    private val scope: CoroutineScope,
    private val resolve: suspend (SportsLiveItem) -> SportsLivePreviewLookup,
    private val settleMs: Long = SETTLE_MS,
    private val firstFrameTimeoutMs: Long = FIRST_FRAME_TIMEOUT_MS,
    private val maxPlayMs: Long = MAX_PLAY_MS
) {
    companion object {
        const val SETTLE_MS = 2_000L
        const val FIRST_FRAME_TIMEOUT_MS = 10_000L

        /** A preview plays this long on one card at most (from its stream loading), then the art comes back. */
        const val MAX_PLAY_MS = 20_000L
    }

    private val _state = MutableStateFlow<SportsLivePreviewState>(SportsLivePreviewState.Idle)
    val state: StateFlow<SportsLivePreviewState> = _state.asStateFlow()

    private var focused: SportsLiveItem? = null
    private var tab: SportsLiveTab? = null
    private var enabled = false
    private var visible = false
    private var ambient = false
    private var job: Job? = null

    /** Counts restarts; a lookup whose generation is no longer current is ignored. */
    private var generation = 0

    /** The focused card (null: none, or the focus is elsewhere). A refreshed copy of the same card changes nothing. */
    fun onFocus(item: SportsLiveItem?) {
        val same = item?.previewKey == focused?.previewKey
        focused = item
        if (!same) restart()
    }

    fun onTab(tab: SportsLiveTab) {
        if (tab == this.tab) return
        this.tab = tab
        restart()
    }

    fun onEnabled(enabled: Boolean) {
        if (enabled == this.enabled) return
        this.enabled = enabled
        restart()
    }

    fun onVisible(visible: Boolean) {
        if (visible == this.visible) return
        this.visible = visible
        restart()
    }

    /** Ambient mode, the screensaver or a dream is showing over the screen: no preview meanwhile. */
    fun onAmbient(showing: Boolean) {
        if (showing == ambient) return
        ambient = showing
        restart()
    }

    fun onFirstFrame(item: SportsLiveItem) {
        val playing = _state.value as? SportsLivePreviewState.Playing ?: return
        if (playing.item.previewKey == item.previewKey && !playing.rendering) {
            _state.value = playing.copy(rendering = true)
        }
    }

    /** The player failed: back to the art until the focus moves. */
    fun onError(item: SportsLiveItem) {
        val playing = _state.value as? SportsLivePreviewState.Playing ?: return
        if (playing.item.previewKey == item.previewKey) stop()
    }

    /**
     * Hands [item]'s preview over when it is on screen (rendering): returns it and stops, without a
     * new settle until an input changes (the screen leaving and coming back included).
     */
    fun takeOver(item: SportsLiveItem): SportsLivePreviewState.Playing? {
        val playing = _state.value as? SportsLivePreviewState.Playing ?: return null
        if (!playing.rendering || playing.item.previewKey != item.previewKey) return null
        stop()
        return playing
    }

    private fun stop() {
        generation++
        job?.cancel()
        job = null
        _state.value = SportsLivePreviewState.Idle
    }

    private fun restart() {
        stop()
        val item = focused ?: return
        if (!enabled || !visible || ambient || tab?.offersPreview() != true) return
        val current = generation
        job = scope.launch {
            delay(settleMs)
            _state.value = SportsLivePreviewState.Resolving(item)
            val lookup = resolve(item)
            if (current != generation) return@launch
            val stream = when (lookup) {
                is SportsLivePreviewLookup.Found -> lookup.stream
                SportsLivePreviewLookup.NotPreviewable -> {
                    _state.value = SportsLivePreviewState.Idle
                    return@launch
                }
                SportsLivePreviewLookup.NoStream -> {
                    _state.value = SportsLivePreviewState.NoStream(item)
                    return@launch
                }
            }
            _state.value = SportsLivePreviewState.Playing(item, stream)
            delay(minOf(firstFrameTimeoutMs, maxPlayMs))
            if (current != generation) return@launch
            if ((_state.value as? SportsLivePreviewState.Playing)?.rendering != true) {
                _state.value = SportsLivePreviewState.Idle
                return@launch
            }
            // Its time is up: back to the art, with no new settle until an input changes.
            delay((maxPlayMs - firstFrameTimeoutMs).coerceAtLeast(0L))
            if (current == generation) _state.value = SportsLivePreviewState.Idle
        }
    }
}

/** A card's identity across refreshes: two addons can list the same id. */
internal val SportsLiveItem.previewKey: String get() = "$addonId|$id"
