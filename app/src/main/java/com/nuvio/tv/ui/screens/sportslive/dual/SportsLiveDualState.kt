package com.nuvio.tv.ui.screens.sportslive.dual

import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.ui.screens.sportslive.SportsLiveEndCard
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

    /** The pane's game ended (#161): its end card ([DualPane.end]), or "Game over" once it was closed. */
    data object Ended : PaneStatus
}

data class DualPane(
    val item: SportsLiveItem,
    val stream: Stream? = null,
    val status: PaneStatus = PaneStatus.Loading,
    /** A recovery runs (#138): "Reconnecting…" shows over the last frame. */
    val reconnecting: Boolean = false,
    /** Counts retries of [stream] as is (#138): a change makes the pane's player load it again. */
    val attempt: Int = 0,
    /** Which of the game's links [stream] is, for the starting card (#138): 1 for the first one tried. */
    val linkNumber: Int = 1,
    /** How many links the game has, as far as known (#138); 0 when unknown. */
    val linkCount: Int = 0,
    /** The end card in the pane (#161) while [status] is [PaneStatus.Ended]; null once Stay closed it. */
    val end: SportsLiveEndCard? = null
)

/** The pane's link is starting (#138): its starting card shows (Try next link / Sources / Back). */
val DualPane.isStarting: Boolean
    get() = status == PaneStatus.Loading && stream != null

/** How the two panes share the screen (#123); remembered per profile. */
enum class DualLayout {
    /** Two equal tiles side by side. */
    SPLIT,

    /** The [Side.LEFT] pane large, the [Side.RIGHT] pane small in the top right corner; MENU swaps them. */
    BIG_SMALL;

    val other: DualLayout get() = if (this == SPLIT) BIG_SMALL else SPLIT
}

/** Two games side by side; only the [audibleSide] pane has sound. */
data class DualState(
    val left: DualPane,
    val right: DualPane,
    val highlighted: Side = Side.LEFT,
    val layout: DualLayout = DualLayout.SPLIT
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
            val failed = pane(highlighted).status.let { it == PaneStatus.Error || it == PaneStatus.DeviceLimit || it == PaneStatus.Ended }
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
        // Split: the highlight stays on the same game. Big + small (#123): the sound follows the large
        // pane, so the game swapped in large has it; Left/Right still move it after.
        DualAction.Swap -> copy(
            left = right,
            right = left,
            highlighted = if (layout == DualLayout.BIG_SMALL) Side.LEFT else highlighted.other
        )
        is DualAction.SetLayout -> copy(layout = action.layout)
        is DualAction.ZapRequested -> with(action.side, DualPane(action.item))
        is DualAction.Resolved -> updatePaneOf(action.item) { pane ->
            if (pane.status != PaneStatus.Loading || pane.stream != null) null
            else if (action.stream == null) pane.copy(status = PaneStatus.Error)
            else pane.copy(stream = action.stream)
        }
        is DualAction.FirstFrame -> updatePaneOf(action.item) { pane ->
            if (pane.status == PaneStatus.Loading && pane.stream != null) {
                pane.copy(status = PaneStatus.Playing, reconnecting = false)
            } else {
                null
            }
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
            else pane.copy(status = PaneStatus.DeviceLimit, reconnecting = false)
        }
        is DualAction.RetrySame -> updatePaneOf(action.item) { pane ->
            if (pane.stream == null || !pane.isRecoverable) null
            else pane.copy(reconnecting = true, attempt = pane.attempt + 1)
        }
        is DualAction.Reconnecting -> updatePaneOf(action.item) { pane ->
            if (!pane.isRecoverable) null else pane.copy(reconnecting = true)
        }
        is DualAction.Switched -> updatePaneOf(action.item) { pane ->
            if (!pane.isRecoverable) null
            else pane.copy(
                stream = action.stream,
                status = PaneStatus.Loading,
                reconnecting = true,
                linkNumber = pane.linkNumber + 1
            )
        }
        is DualAction.Ended -> updatePaneOf(action.item) { pane ->
            pane.copy(status = PaneStatus.Ended, end = action.card, reconnecting = false)
        }
        is DualAction.EndChanged -> updatePaneOf(action.item) { pane ->
            if (pane.status != PaneStatus.Ended) null else pane.copy(end = action.card)
        }
        is DualAction.LinkCount -> updatePaneOf(action.item) { pane ->
            if (pane.linkCount == action.count) null else pane.copy(linkCount = action.count)
        }
        is DualAction.Recovered -> updatePaneOf(action.item) { pane ->
            if (pane.reconnecting && pane.status == PaneStatus.Playing) pane.copy(reconnecting = false) else null
        }
        is DualAction.Exhausted -> updatePaneOf(action.item) { pane ->
            if (pane.status == PaneStatus.DeviceLimit) null
            else pane.copy(status = PaneStatus.Error, reconnecting = false)
        }
    }
}

/** A pane a recovery may act on (#138): starting or playing, not already failed. */
private val DualPane.isRecoverable: Boolean
    get() = status == PaneStatus.Loading || status == PaneStatus.Playing

/**
 * The stream OK hands to the full player as is: the one this pane is playing. Null while it is
 * loading or failed (Error, DeviceLimit), when OK takes the section's normal play path.
 */
val DualPane.okStream: Stream?
    get() = stream.takeIf { status == PaneStatus.Playing }

/**
 * The pane the game info overlay is about in dual (#139): the one with sound, never both games. It
 * follows [DualState.audibleSide], so a failed highlighted pane leaves it on the game still heard.
 */
val DualState.infoSide: Side get() = audibleSide

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

    /** MENU held, or the profile's saved layout (#123): the panes take [layout]. */
    data class SetLayout(val layout: DualLayout) : DualAction

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

    /** [item]'s stream is loaded again as is, from the live edge (#138). */
    data class RetrySame(val item: SportsLiveItem) : DualAction

    /** A recovery looks for another link for [item] (#138). */
    data class Reconnecting(val item: SportsLiveItem) : DualAction

    /** [item]'s pane switches to [stream], another link for the same game (#138). */
    data class Switched(val item: SportsLiveItem, val stream: Stream) : DualAction

    /** [item]'s stream moves again after a recovery (#138). */
    data class Recovered(val item: SportsLiveItem) : DualAction

    /** Every recovery for [item] failed or its budget is used up: the pane offers the choices (#138). */
    data class Exhausted(val item: SportsLiveItem) : DualAction

    /** [item]'s game has [count] links, as far as known (#138). */
    data class LinkCount(val item: SportsLiveItem, val count: Int) : DualAction

    /** [item]'s game ended (#161): its pane shows [card]; the other pane plays on. */
    data class Ended(val item: SportsLiveItem, val card: SportsLiveEndCard) : DualAction

    /** [item]'s ended pane's card changed (its countdown stopped) or closed ([card] null: Stay). */
    data class EndChanged(val item: SportsLiveItem, val card: SportsLiveEndCard?) : DualAction
}
