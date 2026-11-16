package com.nuvio.tv.ui.screens.sportslive.dual

import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.Stream

enum class Side {
    LEFT, RIGHT;

    val other: Side get() = if (this == LEFT) RIGHT else LEFT
}

sealed interface PaneStatus {
    /** The stream is being looked up ([DualPane.stream] null) or is starting (stream set, no frame yet). */
    data object Loading : PaneStatus
    data object Playing : PaneStatus

    /** No stream, a playback error, or no first frame in time: "Couldn't play this stream". */
    data object Error : PaneStatus

    /** The device had no decoder left for this pane while the other one played. */
    data object DeviceLimit : PaneStatus
}

data class DualPane(
    val item: SportsLiveItem,
    val stream: Stream? = null,
    val status: PaneStatus = PaneStatus.Loading
)

/** Two games side by side; only the [audibleSide] pane has sound. */
data class DualState(
    val left: DualPane,
    val right: DualPane,
    val highlighted: Side = Side.LEFT
) {
    fun pane(side: Side): DualPane = if (side == Side.LEFT) left else right

    /** The side whose pane shows [item] ([SportsLiveItem.streamKey]); null when neither does. */
    fun sideOf(item: SportsLiveItem): Side? = when (item.streamKey) {
        left.item.streamKey -> Side.LEFT
        right.item.streamKey -> Side.RIGHT
        else -> null
    }

    /**
     * The pane with sound: the highlighted one, unless it failed while the other plays, so a second
     * stream failing keeps the first one audible.
     */
    val audibleSide: Side
        get() {
            val failed = pane(highlighted).status.let { it == PaneStatus.Error || it == PaneStatus.DeviceLimit }
            return if (failed && pane(highlighted.other).status == PaneStatus.Playing) highlighted.other else highlighted
        }

    private fun with(side: Side, pane: DualPane): DualState =
        if (side == Side.LEFT) copy(left = pane) else copy(right = pane)

    private fun updatePaneOf(item: SportsLiveItem, update: (DualPane) -> DualPane?): DualState {
        val side = sideOf(item) ?: return this
        val updated = update(pane(side)) ?: return this
        return with(side, updated)
    }

    fun reduce(action: DualAction): DualState = when (action) {
        is DualAction.Highlight -> copy(highlighted = action.side)
        // The highlight stays on the same game.
        DualAction.Swap -> copy(left = right, right = left, highlighted = highlighted.other)
        is DualAction.ZapRequested -> with(action.side, DualPane(action.item))
        is DualAction.Resolved -> updatePaneOf(action.item) { pane ->
            if (pane.status != PaneStatus.Loading || pane.stream != null) null
            else if (action.stream == null) pane.copy(status = PaneStatus.Error)
            else pane.copy(stream = action.stream)
        }
        is DualAction.FirstFrame -> updatePaneOf(action.item) { pane ->
            if (pane.status == PaneStatus.Loading && pane.stream != null) pane.copy(status = PaneStatus.Playing) else null
        }
        is DualAction.Restarted -> updatePaneOf(action.item) { pane ->
            if (pane.status == PaneStatus.Playing && pane.stream != null) pane.copy(status = PaneStatus.Loading) else null
        }
        is DualAction.TimedOut -> updatePaneOf(action.item) { pane ->
            if (pane.status == PaneStatus.Loading) pane.copy(status = PaneStatus.Error) else null
        }
        is DualAction.Failed -> updatePaneOf(action.item) { pane ->
            if (pane.stream == null || pane.status == PaneStatus.Error || pane.status == PaneStatus.DeviceLimit) null
            else pane.copy(status = PaneStatus.Error)
        }
        is DualAction.DeviceLimit -> updatePaneOf(action.item) { pane ->
            if (pane.stream == null || pane.status == PaneStatus.Error || pane.status == PaneStatus.DeviceLimit) null
            else pane.copy(status = PaneStatus.DeviceLimit)
        }
    }
}

/** What a pane's label shows about its sound. */
enum class PaneSound {
    /** Muted pane: a faint muted speaker. */
    MUTED,

    /** The pane with sound, not playing right now (starting, buffering, failed): a still speaker. */
    AUDIBLE,

    /** The pane with sound, playing: the speaker and moving bars. */
    AUDIBLE_PLAYING
}

/**
 * The sound shown on [side]'s label: it follows [DualState.audibleSide], not the highlight, and the
 * bars move only while the pane is [PaneStatus.Playing] and its player [isPlaying].
 */
fun DualState.paneSound(side: Side, isPlaying: Boolean): PaneSound = when {
    side != audibleSide -> PaneSound.MUTED
    pane(side).status == PaneStatus.Playing && isPlaying -> PaneSound.AUDIBLE_PLAYING
    else -> PaneSound.AUDIBLE
}

/**
 * What changes the dual screen. Results name the item they are for, not a side: a pane that was
 * zapped to another game or swapped meanwhile ignores a result for a game it no longer shows.
 */
sealed interface DualAction {
    data class Highlight(val side: Side) : DualAction

    /** MENU: the panes trade places. */
    data object Swap : DualAction

    /** The [side] pane now looks up [item]'s stream. */
    data class ZapRequested(val side: Side, val item: SportsLiveItem) : DualAction

    /** The stream found for [item]; null when there is none. */
    data class Resolved(val item: SportsLiveItem, val stream: Stream?) : DualAction
    data class FirstFrame(val item: SportsLiveItem) : DualAction

    /** [item]'s player was created again: a playing pane waits for its first frame again. */
    data class Restarted(val item: SportsLiveItem) : DualAction

    /** No first frame within the pane timeout. */
    data class TimedOut(val item: SportsLiveItem) : DualAction
    data class Failed(val item: SportsLiveItem) : DualAction
    data class DeviceLimit(val item: SportsLiveItem) : DualAction
}
