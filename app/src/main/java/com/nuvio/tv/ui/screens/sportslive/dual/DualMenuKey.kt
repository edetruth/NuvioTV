package com.nuvio.tv.ui.screens.sportslive.dual

/** What MENU does in dual screen. */
enum class DualMenuAction {
    /** A short press: the panes trade places (in big + small, which game is large). */
    SWAP,

    /** Held (#123): split ↔ big + small. */
    TOGGLE_LAYOUT
}

/**
 * MENU in dual screen (#123), an optional shortcut (research 8: Hold OK does the same, for remotes with no Menu): a
 * short press swaps on release; held until the key repeats (the system's long-press time), it changes the layout
 * once and its release does nothing. A release without its press (the press went to the screen before) does
 * nothing either.
 */
internal class DualMenuKey {
    private val key = DualHoldKey()

    /** A MENU down event, [repeatCount] as the system counts it; the action to take now, if any. */
    fun onDown(repeatCount: Int): DualMenuAction? = key.onDown(repeatCount)?.toMenu()

    fun onUp(): DualMenuAction? = key.onUp()?.toMenu()

    private fun DualPress.toMenu(): DualMenuAction = if (this == DualPress.SHORT) DualMenuAction.SWAP else DualMenuAction.TOGGLE_LAYOUT
}
