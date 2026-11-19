package com.nuvio.tv.ui.screens.sportslive

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.tv.core.sportslive.alerts.AlertKeyAction
import com.nuvio.tv.core.sportslive.alerts.AlertKeyContext
import com.nuvio.tv.core.sportslive.alerts.DualPanes
import com.nuvio.tv.core.sportslive.alerts.SportsLiveAlertKeys
import com.nuvio.tv.domain.model.SportsLiveItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the screen under an alert card offers it (#136). The full player of a Sports & Live game and dual
 * screen register one while they show ([RegisterSportsLiveAlertSurface]).
 */
internal interface SportsLiveAlertSurface {
    /** OK on a card: [item] plays here. False when it cannot here (the host then plays it as Watch on a prompt). */
    fun watch(item: SportsLiveItem): Boolean

    /** The screen's own overlays are up (controls, the live games panel, a dialog, a starting card): OK is theirs. */
    fun busy(): Boolean

    /** The full player, which can peek (#136); null elsewhere. */
    val peek: SportsLivePeekHost?
}

/** The full player during a peek (#136): its game goes quiet and comes back. */
internal interface SportsLivePeekHost {
    /** False when this player cannot go quiet (another engine than ExoPlayer, a watch party). */
    fun canPeek(): Boolean

    /** The game playing loses its sound while the peek plays; it keeps playing, so it stays ready. */
    fun quiet()

    /** Its sound back, at the live edge. */
    fun restore()
}

/**
 * The screens alerts can act on (#136), and the key dispatcher: [MainActivity][com.nuvio.tv.MainActivity]
 * hands every key here first, so a card takes OK, ▼ and Back only while it is up and actionable, and a
 * peek takes every key but the volume. Main thread only.
 */
internal object SportsLiveAlertSurfaces {
    private val _surface = MutableStateFlow<SportsLiveAlertSurface?>(null)
    val surface: StateFlow<SportsLiveAlertSurface?> = _surface.asStateFlow()

    private val _dualPanes = MutableStateFlow<DualPanes?>(null)

    /** Dual screen's games while it shows; null otherwise. */
    val dualPanes: StateFlow<DualPanes?> = _dualPanes.asStateFlow()

    fun register(surface: SportsLiveAlertSurface) {
        _surface.value = surface
    }

    fun unregister(surface: SportsLiveAlertSurface) {
        _surface.compareAndSet(surface, null)
    }

    fun setDualPanes(panes: DualPanes?) {
        _dualPanes.value = panes
    }

    /** The host's key context and actions while a card or a peek is up; null otherwise. */
    var keys: AlertKeyHandler? = null

    /** Key codes whose first press an alert took: their repeats and release are its too. */
    private val taken = HashSet<Int>()

    /** Uptime at which the back callback gave Back to an alert: that press's key events are its too. */
    private var backTakenAtMs = Long.MIN_VALUE

    /** The back callback gave Back to a card or a peek (it runs before the key events of the same press). */
    fun backTaken() {
        backTakenAtMs = SystemClock.uptimeMillis()
    }

    /** True when an alert took [event] (see [SportsLiveAlertKeys.route]). */
    fun dispatch(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.downTime <= backTakenAtMs) return true
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) {
            if (event.keyCode !in taken) return false
            if (event.action == KeyEvent.ACTION_UP) taken -= event.keyCode
            return true
        }
        // A fresh press: a release lost earlier must not swallow this one's.
        taken -= event.keyCode
        val handler = keys ?: return false
        val action = SportsLiveAlertKeys.route(event.keyCode, handler.context())
        if (action == AlertKeyAction.PASS) return false
        taken += event.keyCode
        handler.onAction(action)
        return true
    }
}

internal interface AlertKeyHandler {
    fun context(): AlertKeyContext
    fun onAction(action: AlertKeyAction)
}

/** Registers [surface] while this composable is shown. */
@Composable
internal fun RegisterSportsLiveAlertSurface(surface: SportsLiveAlertSurface) {
    DisposableEffect(surface) {
        SportsLiveAlertSurfaces.register(surface)
        onDispose { SportsLiveAlertSurfaces.unregister(surface) }
    }
}

/**
 * The full player as an alert surface (#136): OK zaps to the card's game as a panel pick does ([zap]); a
 * peek mutes this game by turning its audio track off (volume 0 alone would not mute passthrough audio,
 * and the peek's own sound needs the output) and gives it back at the live edge. Only ExoPlayer peeks
 * ([exoPlayer] null otherwise), never during a watch party ([partyActive]).
 */
internal fun sportsLivePlayerAlertSurface(
    zap: SportsLiveZapViewModel,
    isBusy: () -> Boolean,
    exoPlayer: () -> ExoPlayer?,
    partyActive: () -> Boolean
): SportsLiveAlertSurface = object : SportsLiveAlertSurface {
    override fun watch(item: SportsLiveItem): Boolean = !partyActive() && zap.watchFromAlert(item)

    override fun busy(): Boolean = isBusy()

    override val peek: SportsLivePeekHost = object : SportsLivePeekHost {
        /** The track selection from before the peek, given back as it was. */
        private var saved: TrackSelectionParameters? = null

        override fun canPeek(): Boolean = !partyActive() && exoPlayer() != null

        override fun quiet() {
            val player = exoPlayer() ?: return
            if (saved == null) saved = player.trackSelectionParameters
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
        }

        override fun restore() {
            val player = exoPlayer() ?: return
            saved?.let { player.trackSelectionParameters = it }
            saved = null
            if (player.isCurrentMediaItemLive) player.seekToDefaultPosition()
        }
    }
}
