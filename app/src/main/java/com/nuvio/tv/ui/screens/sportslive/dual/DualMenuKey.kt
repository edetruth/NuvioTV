package com.nuvio.tv.ui.screens.sportslive.dual

/** What MENU does in dual screen. */
enum class DualMenuAction {
    /** A short press: the panes trade places (in big + small, which game is large). */
    SWAP,

    /** Held (#123): split ↔ big + small. */
    TOGGLE_LAYOUT
}

/**
 * MENU in dual screen (#123): a short press swaps on release; held until the key repeats (the system's
 * long-press time), it changes the layout once and its release does nothing. A release without its
 * press (the press went to the screen before) does nothing either.
 */
internal class DualMenuKey {
    private var down = false
    private var held = false

    /** A MENU down event, [repeatCount] as the system counts it; the action to take now, if any. */
    fun onDown(repeatCount: Int): DualMenuAction? {
        if (repeatCount == 0) {
            down = true
            held = false
            return null
        }
        if (!down || held) return null
        held = true
        return DualMenuAction.TOGGLE_LAYOUT
    }

    fun onUp(): DualMenuAction? {
        val action = if (down && !held) DualMenuAction.SWAP else null
        down = false
        held = false
        return action
    }
}
