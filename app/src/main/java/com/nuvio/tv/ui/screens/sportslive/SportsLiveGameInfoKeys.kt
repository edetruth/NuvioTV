package com.nuvio.tv.ui.screens.sportslive

import android.view.KeyEvent

/** What a key does to the game info overlay (#139). */
enum class GameInfoKeyAction {
    /** Not the overlay's: the screen handles the key as before. */
    PASS,

    /** The overlay opens; the key ends here. */
    SHOW,

    /** The overlay closes; the key ends here. */
    HIDE,

    /** The overlay closes and the screen still handles the key (a zap, the panel, the controls…). */
    HIDE_AND_PASS
}

/**
 * The game info overlay's keys (#139), chosen not to fight the keys already taken:
 * - Full screen: ▲/▼ and CH± zap, ◀ opens the live games panel, ▶ the controls, OK pauses, so none of
 *   them can open it (and a held ▲ would zap first). INFO opens it, and so does MENU, which the sports
 *   player leaves free (remotes without an Info key have one). With the overlay up, INFO again goes on
 *   to the stream details as INFO did before; any other key closes it and does its own job. Only with the
 *   controls hidden, and only for a game with a strict ESPN match while Scores is not Off: otherwise INFO
 *   keeps opening the stream details.
 * - Dual: MENU swaps (held: the layout), so INFO alone opens and closes it; every other key keeps its
 *   job and leaves it up (the overlay follows the side with sound).
 * Back closes it in both, first, through the screens' back handlers.
 */
object SportsLiveGameInfoKeys {

    fun fullScreen(keyCode: Int, shown: Boolean, available: Boolean, controlsShown: Boolean): GameInfoKeyAction = when {
        shown -> when (keyCode) {
            KeyEvent.KEYCODE_MENU -> GameInfoKeyAction.HIDE
            else -> GameInfoKeyAction.HIDE_AND_PASS
        }
        !controlsShown && available && (keyCode == KeyEvent.KEYCODE_INFO || keyCode == KeyEvent.KEYCODE_MENU) -> GameInfoKeyAction.SHOW
        else -> GameInfoKeyAction.PASS
    }

    fun dual(keyCode: Int, shown: Boolean, available: Boolean): GameInfoKeyAction = when {
        keyCode != KeyEvent.KEYCODE_INFO -> GameInfoKeyAction.PASS
        shown -> GameInfoKeyAction.HIDE
        available -> GameInfoKeyAction.SHOW
        else -> GameInfoKeyAction.PASS
    }
}
