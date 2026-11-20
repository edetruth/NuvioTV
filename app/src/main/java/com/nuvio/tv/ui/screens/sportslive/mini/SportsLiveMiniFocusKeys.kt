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

    /** Where focus goes when Back stops the docked mini player (F23: the stage's Watch); null: back to the content. */
    var back: FocusRequester? = null

    private var contentFocused = false
    private var contentLeftAtMs = Long.MIN_VALUE / 2

    /** The app content (everything under the mini player) gained or lost focus. */
    fun onContentFocus(hasFocus: Boolean, nowMs: Long = android.os.SystemClock.uptimeMillis()) {
        if (contentFocused && !hasFocus) contentLeftAtMs = nowMs
        contentFocused = hasFocus
    }

    /** Whether focus reaching the mini player now came from the content (a D-pad move or the stage's Up). */
    fun arrivalInvited(nowMs: Long = android.os.SystemClock.uptimeMillis()): Boolean =
        sportsLiveMiniFocusInvited(contentFocused, contentLeftAtMs, nowMs)
}

/** How long after the content lost focus the mini player may still take it as the same move. */
internal const val MINI_FOCUS_INVITE_MS = 400L

/**
 * BUG13: the mini player takes focus only from a move out of the content: the content still has focus (its change
 * arrives after the mini player's) or lost it within [MINI_FOCUS_INVITE_MS]. Focus that lands on it from nowhere (a
 * screen that opened with nothing focused yet, its first key press searching the whole window) is not the user's
 * choice, and goes back to the screen.
 */
fun sportsLiveMiniFocusInvited(contentFocused: Boolean, contentLeftAtMs: Long, nowMs: Long): Boolean =
    contentFocused || nowMs - contentLeftAtMs in 0..MINI_FOCUS_INVITE_MS

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
