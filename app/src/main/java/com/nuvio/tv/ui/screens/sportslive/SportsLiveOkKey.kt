package com.nuvio.tv.ui.screens.sportslive

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.nuvio.tv.core.sportslive.alerts.SportsLiveAlertKeys
import com.nuvio.tv.domain.model.SportsLiveKind

/**
 * The full player's OK over a Sports & Live game (TV QA C5 / Q19), for the player's key handler: true when the
 * key was taken ([SportsLiveGameInfoKeys.fullScreenOk] decides; a bare OK never pauses a live game or channel).
 * Called with the key event, whether a watch party runs, and whether the controls (or the pause overlay) show.
 */
@Composable
fun rememberSportsLiveOkHandler(
    zap: SportsLiveZapViewModel,
    info: SportsLiveGameInfoViewModel,
    showControls: () -> Unit,
    isPlaying: () -> Boolean
): (KeyEvent, Boolean, Boolean) -> Boolean {
    val openGame = LocalOpenSportsLiveGame.current
    val currentShowControls by rememberUpdatedState(showControls)
    val currentIsPlaying by rememberUpdatedState(isPlaying)
    val currentOpenGame by rememberUpdatedState(openGame)
    return remember(zap, info) {
        { event: KeyEvent, partyActive: Boolean, controlsShown: Boolean ->
            if (event.action != KeyEvent.ACTION_DOWN || partyActive || !SportsLiveAlertKeys.isOk(event.keyCode)) {
                false
            } else {
                val playing = zap.nowPlaying
                val live = zap.hasSession && playing != null && playing.kind != SportsLiveKind.REPLAY
                val open = currentOpenGame
                val request = if (info.shown && open != null) info.gameRequest() else null
                val action = SportsLiveGameInfoKeys.fullScreenOk(
                    live = live,
                    controlsShown = controlsShown,
                    infoShown = info.shown,
                    infoAvailable = info.available(playing),
                    canOpenGame = request != null
                )
                // A held OK repeats: only its first press acts, the rest are taken with it.
                val first = event.repeatCount == 0
                when (action) {
                    SportsLiveOkAction.PASS -> false
                    SportsLiveOkAction.SHOW_INFO -> {
                        if (first && playing != null) info.show(playing)
                        true
                    }
                    SportsLiveOkAction.HIDE_INFO -> {
                        info.hide()
                        true
                    }
                    SportsLiveOkAction.OPEN_GAME -> {
                        if (first && request != null && open != null) {
                            info.hide()
                            SportsLiveGameRequests.open(request)
                            zap.onGameOpened(wasPlaying = currentIsPlaying())
                            open()
                        }
                        true
                    }
                    SportsLiveOkAction.SHOW_CONTROLS -> {
                        if (first) currentShowControls()
                        true
                    }
                }
            }
        }
    }
}
