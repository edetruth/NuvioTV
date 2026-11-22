package com.nuvio.tv.ui.screens.sportslive

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.tv.core.sportslive.alerts.AlertKeyAction
import com.nuvio.tv.core.sportslive.alerts.AlertKeyContext
import com.nuvio.tv.core.sportslive.alerts.AlertOkPress
import com.nuvio.tv.core.sportslive.alerts.DualPanes
import com.nuvio.tv.core.sportslive.alerts.SportsLiveAlertKeys
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.ui.screens.sportslive.dual.SportsLiveDualSessions
import com.nuvio.tv.ui.screens.sportslive.dual.sportsLiveDualAvailable
import com.nuvio.tv.ui.screens.sportslive.preview.SportsLiveDecoderLimit
import com.nuvio.tv.ui.screens.sportslive.preview.SportsLiveSecondDecoders
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the screen under an alert card offers it (#136). The full player of a Sports & Live game and dual
 * screen register one while they show ([RegisterSportsLiveAlertSurface]).
 */
internal interface SportsLiveAlertSurface {
    /** Watch on a card: [item] plays here. False when it cannot here (the host then plays it as Watch on a kickoff). */
    fun watch(item: SportsLiveItem): Boolean

    /**
     * The screen's own overlays are up (controls, the live games panel, a dialog, a starting card): a card waits under
     * them (live.24), so whenever a card shows, OK is free to be its.
     */
    fun busy(): Boolean

    /** The full player, which could peek (#136; no alert offers it since live.24); null elsewhere. */
    val peek: SportsLivePeekHost?

    /** Watch both: dual screen can open here with this screen's game and another (the full player only). */
    fun offersBoth(): Boolean = false

    /** Watch both on a card for [item]: dual screen with the game playing (its link and sound) and [item], muted. */
    fun watchBoth(item: SportsLiveItem): Boolean = false
}

/** The full player during a peek (#136): its game goes quiet and comes back. */
internal interface SportsLivePeekHost {
    /**
     * False when this player cannot go quiet (another engine than ExoPlayer, a watch party), or this TV has no
     * spare decoder for a peek beside the game (#106).
     */
    fun canPeek(): Boolean

    /** The game playing loses its sound while the peek plays; it keeps playing, so it stays ready. */
    fun quiet()

    /** Its sound back, at the live edge. */
    fun restore()
}

/**
 * The screens alerts can act on (#136), and the key dispatcher: [MainActivity][com.nuvio.tv.MainActivity] hands every
 * key here first, so a card that shows "OK" takes a plain OK and Back, and the countdown card every key but the volume
 * (live.24). A held OK is never a card's: it is handed back to the screens from its start ([takeReplay]). Main thread
 * only.
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

    private val _promptUp = MutableStateFlow(false)

    /**
     * A kickoff prompt is on screen (#173): it holds the keys, so the full player's own Sports & Live cards
     * wait under it ([com.nuvio.tv.ui.screens.player.SportsLivePlayerLayers], live.19 QA F11).
     */
    val promptUp: StateFlow<Boolean> = _promptUp.asStateFlow()

    fun setPromptUp(up: Boolean) {
        _promptUp.value = up
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

    /** A card's OK, held back to tell a short press (the card's) from a hold (the screen's). */
    private val okPress = AlertOkPress()

    /** A held OK handed back: its first press, for the screens before the repeat that showed it was held. */
    private var replay: KeyEvent? = null

    /** True when an alert took [event] (see [SportsLiveAlertKeys.route]). */
    fun dispatch(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.downTime <= backTakenAtMs) return true
        val fresh = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        if (SportsLiveAlertKeys.isOk(event.keyCode) && !fresh) {
            when (okPress.onOk(event.action, event.repeatCount, event.downTime, cardTakes = false)) {
                AlertOkPress.Step.TAKE -> return true
                AlertOkPress.Step.ACT -> {
                    // A short press: the card's OK, on its release.
                    taken -= event.keyCode
                    keys?.onAction(AlertKeyAction.OK)
                    return true
                }
                AlertOkPress.Step.HAND_BACK -> {
                    // Held (#218): Hold OK keeps its meaning everywhere; the screens get the press from its start.
                    taken -= event.keyCode
                    replay = KeyEvent(
                        event.downTime, event.downTime, KeyEvent.ACTION_DOWN, event.keyCode, 0,
                        event.metaState, event.deviceId, event.scanCode, event.flags, event.source
                    )
                    return false
                }
                AlertOkPress.Step.NONE -> Unit
            }
        }
        if (!fresh) {
            if (event.keyCode !in taken) return false
            if (event.action == KeyEvent.ACTION_UP) taken -= event.keyCode
            return true
        }
        // A fresh press: a release lost earlier must not swallow this one's.
        taken -= event.keyCode
        val handler = keys
        val action = handler?.let { SportsLiveAlertKeys.route(event.keyCode, it.context()) } ?: AlertKeyAction.PASS
        if (SportsLiveAlertKeys.isOk(event.keyCode)) {
            // A plain OK on a card acts on its release, so a hold can still go to the screens.
            okPress.onOk(event.action, event.repeatCount, event.downTime, cardTakes = action == AlertKeyAction.OK)
        }
        if (handler == null || action == AlertKeyAction.PASS) return false
        taken += event.keyCode
        if (action != AlertKeyAction.OK) handler.onAction(action)
        return true
    }

    /** The first press of a held OK handed back ([dispatch]), once: the screens get it before the repeat. */
    fun takeReplay(): KeyEvent? = replay.also { replay = null }
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
    partyActive: () -> Boolean,
    /** Opens dual screen over this player; null where it cannot open. */
    openDual: (() -> Unit)? = null
): SportsLiveAlertSurface = object : SportsLiveAlertSurface {
    override fun watch(item: SportsLiveItem): Boolean = !partyActive() && zap.watchFromAlert(item)

    override fun offersBoth(): Boolean = openDual != null && !partyActive() && zap.hasSession && sportsLiveDualAvailable()

    override fun watchBoth(item: SportsLiveItem): Boolean {
        val open = openDual ?: return false
        if (partyActive()) return false
        val session = zap.bothFromAlert(item) ?: return false
        SportsLiveDualSessions.start(session)
        open()
        return true
    }

    override fun busy(): Boolean = isBusy()

    override val peek: SportsLivePeekHost = object : SportsLivePeekHost {
        /** The track selection from before the peek, given back as it was. */
        private var saved: TrackSelectionParameters? = null

        override fun canPeek(): Boolean {
            val player = exoPlayer() ?: return false
            if (partyActive()) return false
            // The peek is a second full-size decoder beside the game's: only where the TV has one to spare.
            val video = player.videoFormat
            return SportsLiveSecondDecoders.allowedBesideMain(
                mainPlaying = true,
                mainAbove1080p = video != null && (video.width > 1920 || video.height > 1088),
                hardwareInstances = SportsLiveSecondDecoders.hardwareInstances(video?.sampleMimeType ?: MimeTypes.VIDEO_H264),
                limitSeen = SportsLiveDecoderLimit.seen
            )
        }

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
