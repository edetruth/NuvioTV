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
 *   ◀ ▶       move the white ring to the other pane; the sound follows it. ◀ on the left pane already: pick the
 *             other game (#57).
 *   ▲ ▼       change the ringed pane's game (previous / next live game). On a pane card with buttons (a link
 *             starting, the game over) they move through its buttons first; past the top or bottom button they
 *             change the game, so a card never traps the pane.
 *   OK        split, or the big pane: full screen. The small pane of big + small: it becomes the big one (the sound
 *             stays with it). On a pane card: presses the selected button ("Couldn't play": choose a stream).
 *   Hold OK   side by side ↔ big + small.
 *   Back      as before (a countdown stops first, then back to the full player or out).
 *
 * Optional shortcuts: MENU swaps the panes, held it changes the layout (#123); CH+/CH- and next/previous change the
 * game, on a card too; INFO shows the game info (#139). The keys hint names the D-pad ones on entry.
 */

/** What a key does in dual screen, from [dualKeyRoute]. */
internal sealed interface DualKeyRoute {
    /** The ring (and the sound) moves to [side]. */
    data class Highlight(val side: Side) : DualKeyRoute

    /** ◀ on the left pane already: the picker for the other game (#57). */
    data object PickOtherGame : DualKeyRoute

    /** The ringed pane's card selects its button [index]. */
    data class CardButton(val index: Int) : DualKeyRoute

    /** The ringed pane changes game: [forward] the next one, else the previous one. */
    data class Zap(val forward: Boolean) : DualKeyRoute
}

/**
 * How many buttons [pane]'s card has that ▲ ▼ move through and OK presses: a link starting (#138) or the game over
 * (#161, until Stay closes it). 0: no such card ("Couldn't play" takes OK and ▲ ▼ change the game).
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
        KeyEvent.KEYCODE_DPAD_LEFT ->
            return if (state.highlighted == Side.LEFT) DualKeyRoute.PickOtherGame else DualKeyRoute.Highlight(Side.LEFT)
        KeyEvent.KEYCODE_DPAD_RIGHT -> return DualKeyRoute.Highlight(Side.RIGHT)
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
    /** The ringed pane's card presses its selected button. */
    CARD_BUTTON,

    /** "Couldn't play this stream": the game's streams to choose from (#138). */
    CHOOSE_STREAM,

    /** The small pane of big + small becomes the big one. */
    MAKE_BIG,

    /** The ringed pane's game plays in the full player. */
    FULL_SCREEN
}

/** What a short OK does in [state] (see the key model above). */
internal fun dualOkRoute(state: DualState): DualOk {
    val pane = state.pane(state.highlighted)
    return when {
        dualCardButtons(pane) > 0 -> DualOk.CARD_BUTTON
        pane.status == PaneStatus.Error -> DualOk.CHOOSE_STREAM
        state.layout == DualLayout.BIG_SMALL && state.highlighted == Side.RIGHT -> DualOk.MAKE_BIG
        else -> DualOk.FULL_SCREEN
    }
}

/** One line of the keys hint: a key and what it does. */
internal enum class DualHint { MOVE, ZAP, OK_FULL_SCREEN, OK_MAKE_BIG, HOLD_OK_BIG_SMALL, HOLD_OK_SIDE_BY_SIDE }

/**
 * The keys hint for [state] (research 8): the D-pad model only, for the panes playing (a pane card names its own
 * keys). OK says what it does on the ringed pane; Hold OK names the other layout.
 */
internal fun dualKeyHints(state: DualState): List<DualHint> = listOf(
    DualHint.MOVE,
    DualHint.ZAP,
    if (state.layout == DualLayout.BIG_SMALL && state.highlighted == Side.RIGHT) DualHint.OK_MAKE_BIG else DualHint.OK_FULL_SCREEN,
    if (state.layout == DualLayout.SPLIT) DualHint.HOLD_OK_BIG_SMALL else DualHint.HOLD_OK_SIDE_BY_SIDE
)

/** A key pressed briefly or held, from [DualHoldKey]. */
internal enum class DualPress { SHORT, HELD }

/**
 * A key that does one thing pressed briefly (on its release) and another held: held until the key repeats (the
 * system's long-press time), or released after [holdMs], it is [DualPress.HELD] once and its release does nothing
 * more. A release or repeat without its press (the press went to the screen before) does nothing.
 */
internal class DualHoldKey(private val holdMs: Long = DEFAULT_HOLD_MS) {
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
        return DualPress.HELD
    }

    /** The release, [heldMs] after the press; the press to act on now, if any. */
    fun onUp(heldMs: Long = 0L): DualPress? {
        val press = when {
            !down || held -> null
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
