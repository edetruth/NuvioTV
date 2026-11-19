package com.nuvio.tv.ui.screens.sportslive

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which Sports & Live video players (each dual screen pane, the mini player) are playing, reported by
 * the players themselves from [androidx.media3.common.Player.isPlaying], so no screen or layout change
 * can drop it (#38). [anyPlaying] is true while at least one plays: the screen stays awake and ambient
 * mode stays away meanwhile. The full player holds the screen itself.
 */
class SportsLiveAwakeHolds {
    private val holders = mutableSetOf<Any>()
    private val _anyPlaying = MutableStateFlow(false)
    val anyPlaying: StateFlow<Boolean> = _anyPlaying.asStateFlow()

    /** Records whether [holder]'s video is playing; returns whether any is now. */
    @Synchronized
    fun set(holder: Any, playing: Boolean): Boolean {
        if (playing) holders += holder else holders -= holder
        return holders.isNotEmpty().also { _anyPlaying.value = it }
    }

    companion object {
        /** The app's one record, fed by the sports players. */
        val shared = SportsLiveAwakeHolds()
    }
}

/**
 * Keeps the TV from dimming or starting its screensaver while [awake], by the window's
 * FLAG_KEEP_SCREEN_ON; released when [awake] turns false or this leaves the composition.
 */
@Composable
fun SportsLiveKeepScreenOn(awake: Boolean) {
    val window = LocalContext.current.findActivity()?.window ?: return
    DisposableEffect(window, awake) {
        if (awake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { if (awake) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
