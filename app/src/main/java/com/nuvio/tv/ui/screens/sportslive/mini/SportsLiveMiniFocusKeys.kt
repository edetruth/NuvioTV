package com.nuvio.tv.ui.screens.sportslive.mini

import android.view.KeyEvent
import androidx.compose.ui.focus.FocusRequester

/**
 * The docked mini player's focus (#157): a stage's primary button sends Up to it while it is docked, so
 * OK (expand) and Back (stop) on it are one press away. Attached only while the mini player is drawn.
 */
object SportsLiveMiniFocus {
    val requester = FocusRequester()

    /** Where Up from the docked mini player goes (#170: the header controls over the stage); null: back to the content. */
    var up: FocusRequester? = null
}

/** What a key does while the mini player has focus. */
enum class SportsLiveMiniKey {
    /** OK: expand, as Play/Pause does. */
    EXPAND,

    /** Back released: stop and close the mini player; focus goes back to the content. */
    CLOSE,

    /**
     * An arrow pressed: focus goes back to the content underneath (where it last was), and the mini
     * player keeps playing where it is.
     */
    RELEASE,

    /** The other half of an OK or Back press (repeats, Back down, OK up): eaten, nothing else. */
    CONSUME
}

/**
 * The mini player's own keys; null for every other key, which keeps its normal handling. Back acts
 * on release and its press is eaten, so no screen underneath also handles that Back. Every arrow
 * hands focus back to the content: the mini player sits above the screens, where a D-pad move
 * cannot find its way back on its own.
 */
fun sportsLiveMiniKey(keyCode: Int, down: Boolean, repeatCount: Int): SportsLiveMiniKey? = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
        if (down && repeatCount == 0) SportsLiveMiniKey.EXPAND else SportsLiveMiniKey.CONSUME
    KeyEvent.KEYCODE_BACK -> if (down) SportsLiveMiniKey.CONSUME else SportsLiveMiniKey.CLOSE
    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT ->
        // Its release is eaten too, so it never reaches the element that has just taken focus.
        if (down) SportsLiveMiniKey.RELEASE else SportsLiveMiniKey.CONSUME
    else -> null
}
