package com.nuvio.tv.ui.screens.sportslive.dual

import android.view.KeyEvent
import com.nuvio.tv.ui.screens.sportslive.SportsLiveStartingAction
import com.nuvio.tv.ui.screens.sportslive.sportsLiveEndActions

/*
 * Dual screen's key model (research 8). It works with the D-pad and OK alone: Google TV's quality rule TV-DM says an
 * app must not depend on a Menu button, and Google TV remotes have neither Menu nor channel keys. Modelled on
 * YouTube TV multiview and Apple TV multiview (the D-pad moves the active view and the sound follows it; Select on
 * the active view goes full screen; Apple's one larger view).
 *
 *   ◀ ▶       side by side: move the white ring (and the sound) to the other pane; past the edge nothing more.
 *             Big + small (user, live.24): swap which game is big; the sound follows the big one. Neither ever opens
 *             the picker (live.23 TV report: ◀ pressed to swap sizes opened "Pick another to watch"); that is Hold OK
 *             › Change the other game.
 *   ▲ ▼       change the ringed pane's game (previous / next live game). On a pane card with buttons (a link
 *             starting, the game over) they move through its buttons first; past the top or bottom button they
 *             change the game, so a card never traps the pane.
 *   OK        split, or the big pane: full screen (Back there returns to dual with both games). The small pane of
 *             big + small: it becomes the big one (the sound stays with it). On a pane card: presses the selected
 *             button ("Couldn't play": choose a stream; an end card with nothing next: closes it).
 *   Hold OK   the app-wide "more" menu (#88: a key keeps its meaning): Report a problem · Layout · Swap panes ·
 *             Change the other game · Full screen ([dualMenuEntries]).
 *   Back      closes a pane's end card first; then as before (back to the full player or out).
 *
 * Optional shortcuts: MENU swaps the panes, held it changes the layout (#123); CH+/CH- and next/previous change the
 * game, on a card too; INFO shows the game info (#139). The keys hint names ◀ ▶, OK and Hold OK on the profile's
 * first few entries ([DUAL_KEYS_HINT_OPENS]), for a few seconds or until ◀ ▶ or OK is pressed.
 */

/** What a key does in dual screen, from [dualKeyRoute]. */
internal sealed interface DualKeyRoute {
    /** The ring (and the sound) moves to [side]. */
    data class Highlight(val side: Side) : DualKeyRoute

    /** Big + small: the panes trade places, so the other game is big (and has the sound). */
    data object SwapBig : DualKeyRoute

    /** The ringed pane's card selects its button [index]. */
    data class CardButton(val index: Int) : DualKeyRoute

    /** The ringed pane changes game: [forward] the next one, else the previous one. */
    data class Zap(val forward: Boolean) : DualKeyRoute
}

/**
 * How many buttons [pane]'s card has that ▲ ▼ move through and OK presses: a link starting (#138), or the game over
 * with a game to watch next (#161; Back closes that card). 0: none ("Couldn't play" takes OK and ▲ ▼ change the game).
 */
internal fun dualCardButtons(pane: DualPane): Int = when {
    pane.isStarting -> SportsLiveStartingAction.entries.size
    pane.status == PaneStatus.Ended -> pane.end?.let { sportsLiveEndActions(it, pane = true).size } ?: 0
    else -> 0
}

/**
 * What [keyCode] (a press, not its release) does in [state]: ◀ ▶ ▲ ▼ and the zap keys; null for any other key.
 * [cardSelected]: the index of the button selected on the ringed pane's card, when it has one.
 */
internal fun dualKeyRoute(state: DualState, keyCode: Int, cardSelected: Int = 0): DualKeyRoute? {
    when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> return when {
            state.layout == DualLayout.BIG_SMALL -> DualKeyRoute.SwapBig
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> DualKeyRoute.Highlight(Side.LEFT)
            else -> DualKeyRoute.Highlight(Side.RIGHT)
        }
    }
    val pane = state.pane(state.highlighted)
    val buttons = dualCardButtons(pane)
    if (buttons > 0 && (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
        val step = if (keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
        val to = cardSelected.coerceIn(0, buttons - 1) + step
        // Past the card's first or last button: the pane changes game, as everywhere else.
        return if (to in 0 until buttons) DualKeyRoute.CardButton(to) else DualKeyRoute.Zap(forward = step > 0)
    }
    return dualZapKey(pane, keyCode)?.let { DualKeyRoute.Zap(it) }
}

/** What a short OK does in dual screen, from [dualOkRoute]. */
internal enum class DualOk {
    /** The ringed pane's card presses its selected button (an end card with none: it closes). */
    CARD_BUTTON,

    /** "Couldn't play this stream": the game's streams to choose from (#138). */
    CHOOSE_STREAM,

    /** The small pane of big + small becomes the big one. */
    MAKE_BIG,

    /** The ringed pane's game plays in the full player. */
    FULL_SCREEN
}

/**
 * The pane a short OK and Back act on (live.24, audit G1: a card owns its keys wherever it sits): the ringed pane; or,
 * when it has no card of its own, the other pane while that one shows the error card ("OK Try another link · BACK
 * Close this side") or an end card. In big + small the ring never sits on the small pane, which is where an alert's
 * game lands, so its cards must be reachable without it.
 */
internal fun dualKeySide(state: DualState): Side {
    val ringed = state.highlighted
    if (dualPaneOwnsKeys(state.pane(ringed)) || dualCardButtons(state.pane(ringed)) > 0) return ringed
    return if (dualPaneOwnsKeys(state.pane(ringed.other))) ringed.other else ringed
}

/** A pane card that takes OK and Back: the error card after every link failed, or an end card still up. */
internal fun dualPaneOwnsKeys(pane: DualPane): Boolean =
    pane.status == PaneStatus.Error || (pane.status == PaneStatus.Ended && pane.end != null)

/** What a short OK does in [state] (see the key model above), on the pane [dualKeySide] picks. */
internal fun dualOkRoute(state: DualState): DualOk = dualRingedOkRoute(state.copy(highlighted = dualKeySide(state)))

private fun dualRingedOkRoute(state: DualState): DualOk {
    val pane = state.pane(state.highlighted)
    return when {
        dualCardButtons(pane) > 0 || (pane.status == PaneStatus.Ended && pane.end != null) -> DualOk.CARD_BUTTON
        pane.status == PaneStatus.Error -> DualOk.CHOOSE_STREAM
        state.layout == DualLayout.BIG_SMALL && state.highlighted == Side.RIGHT -> DualOk.MAKE_BIG
        else -> DualOk.FULL_SCREEN
    }
}

/** One line of the keys hint: a key and what it does. */
internal enum class DualHint { MOVE, SWAP_BIG, OK_FULL_SCREEN, OK_MAKE_BIG, HOLD_OK_MORE }

/**
 * The keys hint for [state] (research 8, live.24): three keys, for the panes playing (a pane card names its own keys).
 * ◀ ▶ say what they do in this layout ("Swap big" or "Sound"); OK says what it does on the ringed pane; Hold OK opens
 * the menu. ▲ ▼ are left out: they are found by use, and the hint stays short enough to read at a glance.
 */
internal fun dualKeyHints(state: DualState): List<DualHint> = listOf(
    if (state.layout == DualLayout.BIG_SMALL) DualHint.SWAP_BIG else DualHint.MOVE,
    if (state.layout == DualLayout.BIG_SMALL && state.highlighted == Side.RIGHT) DualHint.OK_MAKE_BIG else DualHint.OK_FULL_SCREEN,
    DualHint.HOLD_OK_MORE
)

/** The dual keys hint shows on the profile's first this many dual screen entries (user, live.24), then never. */
internal const val DUAL_KEYS_HINT_OPENS = 3

/** Whether the dual keys hint shows on this entry, after it has shown [shownCount] times. */
internal fun shouldShowDualKeysHint(shownCount: Int): Boolean = shownCount < DUAL_KEYS_HINT_OPENS

/** An entry of the Hold OK menu in dual screen. */
internal enum class DualMenuEntry {
    /** Report a problem (#172), with the snapshot taken as OK was held: first, as in every Hold OK sheet (#218). */
    REPORT_PROBLEM,

    /** Split now: big + small. */
    LAYOUT_BIG_SMALL,

    /** Big + small now: side by side. */
    LAYOUT_SIDE_BY_SIDE,
    SWAP,

    /** The picker for the game of the pane without the ring (#57). */
    CHANGE_OTHER_GAME,

    /** The ringed pane's game in the full player, as OK does. */
    FULL_SCREEN
}

/**
 * The Hold OK menu for [state], in its order: Report a problem · Layout (the other one) · Swap panes · Change the other
 * game · Full screen. Five entries: it never grows past six (user, live.24).
 */
internal fun dualMenuEntries(state: DualState): List<DualMenuEntry> = listOf(
    DualMenuEntry.REPORT_PROBLEM,
    if (state.layout == DualLayout.SPLIT) DualMenuEntry.LAYOUT_BIG_SMALL else DualMenuEntry.LAYOUT_SIDE_BY_SIDE,
    DualMenuEntry.SWAP,
    DualMenuEntry.CHANGE_OTHER_GAME,
    DualMenuEntry.FULL_SCREEN
)

/** A key pressed briefly or held, from [DualHoldKey]. */
internal enum class DualPress { SHORT, HELD }

/**
 * A key that does one thing pressed briefly (on its release) and another held: held until the key repeats (the
 * system's long-press time), or released after [holdMs], it is [DualPress.HELD] once and its release does nothing
 * more. [heldOnRelease]: a hold is reported on the release instead (a menu that opens then never takes the release
 * as a click). A release or repeat without its press (the press went to the screen before) does nothing.
 */
internal class DualHoldKey(private val holdMs: Long = DEFAULT_HOLD_MS, private val heldOnRelease: Boolean = false) {
    private var down = false
    private var held = false

    /** A down event, [repeatCount] as the system counts it; the press to act on now, if any. */
    fun onDown(repeatCount: Int): DualPress? {
        if (repeatCount == 0) {
            down = true
            held = false
            return null
        }
        if (!down || held) return null
        held = true
        return if (heldOnRelease) null else DualPress.HELD
    }

    /** The release, [heldMs] after the press; the press to act on now, if any. */
    fun onUp(heldMs: Long = 0L): DualPress? {
        val press = when {
            !down -> null
            held -> if (heldOnRelease) DualPress.HELD else null
            heldMs >= holdMs -> DualPress.HELD
            else -> DualPress.SHORT
        }
        down = false
        held = false
        return press
    }

    companion object {
        /** Android's default long-press time; the screen passes the system's. */
        const val DEFAULT_HOLD_MS = 500L
    }
}
