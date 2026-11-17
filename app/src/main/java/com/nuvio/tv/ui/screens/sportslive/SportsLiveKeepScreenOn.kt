package com.nuvio.tv.ui.screens.sportslive

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Who asks for the screen to stay on (dual screen, the mini player); it stays on while anyone
 * does, so one letting go never turns it off under the other.
 */
class SportsLiveAwakeHolds {
    private val holders = mutableSetOf<Any>()

    /** Records whether [holder] wants the screen on; returns whether anyone does now. */
    fun set(holder: Any, awake: Boolean): Boolean {
        if (awake) holders += holder else holders -= holder
        return holders.isNotEmpty()
    }
}

/** The app's one set of holds (main thread only), behind the window's keep-screen-on flag. */
private val awakeHolds = SportsLiveAwakeHolds()

/**
 * Keeps the TV from dimming or starting its screensaver while [awake] (video playing outside the
 * full player, which holds the screen itself), by the window's FLAG_KEEP_SCREEN_ON; released when
 * [awake] turns false or this leaves the composition.
 */
@Composable
fun SportsLiveKeepScreenOn(awake: Boolean) {
    val window = LocalContext.current.findActivity()?.window ?: return
    val holder = remember { Any() }
    DisposableEffect(window, awake) {
        fun apply(on: Boolean) {
            if (on) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        apply(awakeHolds.set(holder, awake))
        onDispose { apply(awakeHolds.set(holder, false)) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
