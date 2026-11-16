package com.nuvio.tv.ui.screens.sportslive.mini

import android.os.Build
import com.nuvio.tv.ui.screens.sportslive.SportsLiveZapSessions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether leaving the app (Home) puts it in picture-in-picture: only on API 26+ with the system's
 * picture-in-picture feature, and only while Sports & Live plays (a full player with a zap session,
 * or the mini player). Anything else keeps upstream behavior.
 */
fun sportsLivePipEligible(
    sdkInt: Int,
    hasPipFeature: Boolean,
    sportsFullPlayerShowing: Boolean,
    miniPlayerActive: Boolean,
    /** The app itself is starting another activity (external player, browser): not the user leaving. */
    ownLaunchInFlight: Boolean = false
): Boolean = sdkInt >= Build.VERSION_CODES.O && hasPipFeature && !ownLaunchInFlight &&
    (sportsFullPlayerShowing || miniPlayerActive)

/**
 * The app's picture-in-picture state for Sports & Live. [inPip] makes the full player hide its
 * controls and the mini player fill the window. The full player showing registers itself
 * ([attachFullPlayer]) so Home can tell whether it plays a zap session, and closing the PiP
 * window ([onClosed]) can end it. Main thread only.
 */
object SportsLivePip {
    private val _inPip = MutableStateFlow(false)
    val inPip: StateFlow<Boolean> = _inPip.asStateFlow()

    /**
     * Set when the app starts another activity itself, cleared on resume: the leave hint that
     * launch gives is not the user pressing Home, so neither PiP nor the mini player starts.
     */
    @Volatile
    var ownLaunchInFlight = false

    private class FullPlayer(val contentId: String?, val close: () -> Unit)

    private var fullPlayer: FullPlayer? = null

    /** The Player route showing [contentId]; [close] stops and leaves it. Returns the token for [detachFullPlayer]. */
    fun attachFullPlayer(contentId: String?, close: () -> Unit): Any =
        FullPlayer(contentId, close).also { fullPlayer = it }

    fun detachFullPlayer(token: Any) {
        if (fullPlayer === token) fullPlayer = null
    }

    /** A full player is showing a game of the zap session. */
    fun sportsFullPlayerShowing(): Boolean =
        fullPlayer?.let { SportsLiveZapSessions.activeFor(it.contentId) != null } == true

    fun onModeChanged(inPip: Boolean) {
        _inPip.value = inPip
    }

    /** The PiP window was closed: its playback stops and the players are released. */
    fun onClosed() {
        _inPip.value = false
        // Taken first, so a second close (onStop and the mode change both report it) does nothing.
        val closing = fullPlayer
        fullPlayer = null
        closing?.close?.invoke()
        SportsLiveMiniPlayers.controller.stop(SportsLiveMiniStop.PIP_CLOSED)
    }
}
