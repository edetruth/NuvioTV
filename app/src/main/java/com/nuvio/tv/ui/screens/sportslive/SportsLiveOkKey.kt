package com.nuvio.tv.ui.screens.sportslive

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.alerts.SportsLiveAlertKeys
import com.nuvio.tv.domain.model.SportsLiveKind

/**
 * The full player's OK over a Sports & Live game (TV QA C5, research Q19), for the player's key handler: true when
 * the key was taken ([SportsLiveGameInfoKeys.fullScreenOk] decides: on live the first OK shows the controls).
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
                val live = sportsLiveOkLive(zap)
                val open = currentOpenGame
                val request = if (info.shown && open != null) info.gameRequest() else null
                val action = SportsLiveGameInfoKeys.fullScreenOk(
                    live = live,
                    controlsShown = controlsShown,
                    infoShown = info.shown,
                    canOpenGame = request != null,
                    okPausesLive = zap.okPausesLive.value
                )
                // A held OK repeats: only its first press acts, the rest are taken with it.
                val first = event.repeatCount == 0
                when (action) {
                    SportsLiveOkAction.PASS -> false
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

/** The player plays a live Sports & Live game or channel (a zap session, not a replay): OK never pauses it. */
private fun sportsLiveOkLive(zap: SportsLiveZapViewModel): Boolean {
    val playing = zap.nowPlaying
    return zap.hasSession && playing != null && playing.kind != SportsLiveKind.REPLAY
}

/**
 * The full player's info card hint (coordinator, live.20): "OK Game page · Back close" while OK on it opens the Game
 * page ([SportsLiveGameInfoKeys.infoCardOpensGame]), else "Back close". Read while the card shows.
 */
@Composable
fun sportsLiveInfoCardHint(zap: SportsLiveZapViewModel, info: SportsLiveGameInfoViewModel): String {
    val canOpenGame = LocalOpenSportsLiveGame.current != null && info.gameRequest() != null
    return stringResource(
        if (SportsLiveGameInfoKeys.infoCardOpensGame(sportsLiveOkLive(zap), canOpenGame)) R.string.sports_live_info_hint_game
        else R.string.sports_live_info_hint_close
    )
}
