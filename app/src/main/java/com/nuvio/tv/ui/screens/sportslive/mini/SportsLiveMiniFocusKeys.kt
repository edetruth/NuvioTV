package com.nuvio.tv.ui.screens.sportslive.mini

import android.view.KeyEvent

/** What a key does while the mini player has focus. */
enum class SportsLiveMiniKey {
    /** OK: expand, as Play/Pause does. */
    EXPAND,

    /** Back released: stop and close the mini player; focus goes back to the content. */
    CLOSE,

    /** The other half of an OK or Back press (repeats, Back down, OK up): eaten, nothing else. */
    CONSUME
}

/**
 * The mini player's own keys; null for every other key, which keeps its normal handling (Up/Left
 * move focus back to the content). Back acts on release and its press is eaten, so no screen
 * underneath also handles that Back.
 */
fun sportsLiveMiniKey(keyCode: Int, down: Boolean, repeatCount: Int): SportsLiveMiniKey? = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
        if (down && repeatCount == 0) SportsLiveMiniKey.EXPAND else SportsLiveMiniKey.CONSUME
    KeyEvent.KEYCODE_BACK -> if (down) SportsLiveMiniKey.CONSUME else SportsLiveMiniKey.CLOSE
    else -> null
}
