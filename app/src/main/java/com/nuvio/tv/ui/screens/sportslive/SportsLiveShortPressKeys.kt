package com.nuvio.tv.ui.screens.sportslive

import android.view.KeyEvent
import android.view.ViewConfiguration
import com.nuvio.tv.ui.util.LongPressKeyTracker

/** What a Left or Right key event does in a player with a zap session (controls hidden). */
enum class SportsLiveShortPress { NONE, CONSUMED, OPEN_PANEL, SHOW_CONTROLS }

/**
 * Short Left and Right presses of a player with a zap session: a short Left opens the live games
 * panel and a short Right shows the controls, both on release. Their down and up events are consumed.
 * A long press is left to the player, which gets its repeats and its release (live window seeking).
 */
internal class SportsLiveShortPressKeys(
    timeoutMillis: Long = ViewConfiguration.getLongPressTimeout().toLong()
) {
    private class KeyState(timeoutMillis: Long) {
        val tracker = LongPressKeyTracker(timeoutMillis)
        var downSeen = false
        var longPress = false
    }

    private val keys = mapOf(
        KeyEvent.KEYCODE_DPAD_LEFT to KeyState(timeoutMillis),
        KeyEvent.KEYCODE_DPAD_RIGHT to KeyState(timeoutMillis)
    )

    /** [active]: the player has a zap session and zapping is allowed; read on each press's first down. */
    fun onKey(event: KeyEvent, active: Boolean): SportsLiveShortPress {
        val keyCode = event.keyCode
        val state = keys[keyCode] ?: return SportsLiveShortPress.NONE
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            state.downSeen = active
            state.longPress = false
        }
        if (!state.downSeen) return SportsLiveShortPress.NONE
        state.tracker.handle(event, { it == keyCode }) { state.longPress = true }
        return when (event.action) {
            KeyEvent.ACTION_DOWN ->
                if (state.longPress) SportsLiveShortPress.NONE else SportsLiveShortPress.CONSUMED
            KeyEvent.ACTION_UP -> {
                state.downSeen = false
                when {
                    state.longPress -> SportsLiveShortPress.NONE
                    keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> SportsLiveShortPress.OPEN_PANEL
                    else -> SportsLiveShortPress.SHOW_CONTROLS
                }
            }
            else -> SportsLiveShortPress.NONE
        }
    }
}
