package com.nuvio.tv.ui.screens.sportslive.dual

import android.view.KeyEvent
import com.nuvio.tv.ui.screens.sportslive.zapDirection

/**
 * Whether [keyCode] zaps the highlighted [pane] (true: forward, the next game; false: back), or null when it is no
 * zap key there. ▲/▼, CH+/CH- and next/previous zap on every pane, its error card included (live.19 TV report: a
 * pane on "Couldn't play this stream" kept the viewer stuck), except ▲/▼ on a card whose buttons they move through:
 * a starting card (#138) or an end card (#161). CH+/CH- and next/previous zap there too.
 */
internal fun dualZapKey(pane: DualPane, keyCode: Int): Boolean? {
    val forward = zapDirection(keyCode, partyActive = false) ?: return null
    val dpad = keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
    val cardTakesDpad = pane.isStarting || (pane.status == PaneStatus.Ended && pane.end != null)
    return if (dpad && cardTakesDpad) null else forward
}
