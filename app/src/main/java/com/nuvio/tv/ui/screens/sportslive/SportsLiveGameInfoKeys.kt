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

/** What OK does in the full player over a Sports & Live game ([SportsLiveGameInfoKeys.fullScreenOk]). */
enum class SportsLiveOkAction {
    /** The player's own OK (a replay's pause, "OK on live: Pause", the focused control). */
    PASS,

    /** The game info card closes (no Game page to open). */
    HIDE_INFO,

    /** The game info card's Game page opens. */
    OPEN_GAME,

    /** The controls show, Play focused: a second, deliberate OK pauses. */
    SHOW_CONTROLS
}

/**
 * The game info overlay's keys (#139), chosen not to fight the keys already taken:
 * - Full screen: ▲/▼ and CH± zap, ◀ opens the live games panel, ▶ the controls, OK shows the controls on a live
 *   game ([fullScreenOk]: never a pause by itself, research Q19) and pauses a replay, so none of them
 *   can open it (and a held ▲ would zap first). INFO opens it, and so does MENU, which the sports
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

    /**
     * OK in the full player with its controls hidden (TV QA C5, research Q19), the one place this is decided: on a
     * [live] game or channel the first OK shows the controls with ⏸ focused, so pausing takes a second, deliberate OK
     * (live-TV practice: Google's Live Channels, TiviMate); the Play/Pause key still pauses at once. With the game
     * info up ([infoShown], opened by INFO) OK opens its Game page ([canOpenGame]), else just closes it. "OK on live:
     * Pause" ([okPausesLive]), a replay (not [live]) and the controls ([controlsShown]) keep the player's own OK.
     */
    fun fullScreenOk(live: Boolean, controlsShown: Boolean, infoShown: Boolean, canOpenGame: Boolean, okPausesLive: Boolean = false): SportsLiveOkAction = when {
        !live || controlsShown -> SportsLiveOkAction.PASS
        infoShown -> if (canOpenGame) SportsLiveOkAction.OPEN_GAME else SportsLiveOkAction.HIDE_INFO
        okPausesLive -> SportsLiveOkAction.PASS
        else -> SportsLiveOkAction.SHOW_CONTROLS
    }

    /**
     * The info card's hint (full player): whether OK on it opens the Game page, read from [fullScreenOk] itself, so
     * the words always follow the routing (switching OK on a live game to "show controls" changes neither).
     */
    fun infoCardOpensGame(live: Boolean, canOpenGame: Boolean): Boolean =
        fullScreenOk(live = live, controlsShown = false, infoShown = true, canOpenGame = canOpenGame) ==
            SportsLiveOkAction.OPEN_GAME

    fun dual(keyCode: Int, shown: Boolean, available: Boolean): GameInfoKeyAction = when {
        keyCode != KeyEvent.KEYCODE_INFO -> GameInfoKeyAction.PASS
        shown -> GameInfoKeyAction.HIDE
        available -> GameInfoKeyAction.SHOW
        else -> GameInfoKeyAction.PASS
    }
}
