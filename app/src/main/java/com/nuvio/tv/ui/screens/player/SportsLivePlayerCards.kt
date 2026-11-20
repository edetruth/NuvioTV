package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.zIndex
import com.nuvio.tv.core.sportslive.SportsLiveDisplayText
import com.nuvio.tv.ui.screens.sportslive.SportsLiveAlertSurfaces
import com.nuvio.tv.ui.screens.sportslive.SportsLiveExtraSources
import com.nuvio.tv.ui.screens.sportslive.SportsLiveNoLiveGameCard
import com.nuvio.tv.ui.screens.sportslive.SportsLivePlayerErrorAction
import com.nuvio.tv.ui.screens.sportslive.SportsLivePlayerErrorCard
import com.nuvio.tv.ui.screens.sportslive.SportsLiveZapResolver
import com.nuvio.tv.ui.screens.sportslive.SportsLiveZapViewModel
import com.nuvio.tv.ui.screens.sportslive.sportsLivePlayerErrorActions
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens

/**
 * The Sports & Live cards up in the full player now (live.19 QA F10, F11), for [SportsLivePlayerLayers]: the
 * kickoff prompt, the recovery card, the end card (not its toast form), the waiting and starting cards (while
 * [startingVisible], the player's own rule for them) and the pause screen.
 */
@Composable
internal fun rememberSportsLivePlayerLayers(
    uiState: PlayerUiState,
    contentId: String?,
    zap: SportsLiveZapViewModel,
    startingVisible: Boolean
): Set<SportsLivePlayerLayer> {
    val promptUp by SportsLiveAlertSurfaces.promptUp.collectAsState()
    val endCard by zap.endCard.collectAsState()
    val sports = SportsLiveExtraSources.isSportsPlayback(contentId)
    return SportsLivePlayerLayers.up(
        kickoffPrompt = promptUp,
        error = sports && uiState.error != null,
        endCard = endCard?.toastOnly == false,
        waiting = startingVisible && uiState.sportsWaiting != null,
        starting = startingVisible && uiState.sportsStarting != null,
        pauseInfo = uiState.showPauseOverlay
    )
}

/**
 * The full player's Sports & Live recovery (live.19 QA F9), in place of the legacy error page for a game or
 * channel ([isSportsPlayback]): the last frame dimmed and, in the player's bottom left corner, the V2 recovery
 * card, or "No other live game is playing right now" once Next live game found none. The card waits under a
 * higher card ([layers]: the kickoff prompt). Back leaves the player (the player's Back handler).
 */
@Composable
internal fun BoxScope.SportsLivePlayerRecovery(
    uiState: PlayerUiState,
    zap: SportsLiveZapViewModel,
    layers: Set<SportsLivePlayerLayer>,
    onEvent: (PlayerEvent) -> Unit,
    onExit: () -> Unit
) {
    val message = uiState.error ?: return
    val exhausted by zap.nextExhausted.collectAsState()
    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = BACKDROP_ALPHA)).zIndex(3f))
    if (!SportsLivePlayerLayers.shows(SportsLivePlayerLayer.ERROR, layers)) return
    val corner = Modifier
        .align(Alignment.BottomStart)
        .padding(start = SportsLiveTokens.Player.CornerStart, bottom = SportsLiveTokens.Player.CornerBottom)
        .zIndex(5f)
    if (exhausted) {
        SportsLiveNoLiveGameCard(
            onTryAgain = { zap.retryNextLiveGame() },
            onBackToLiveNow = {
                zap.dismissNextExhausted()
                onExit()
            },
            modifier = corner
        )
        return
    }
    val linkCount = remember(uiState.sourceAllStreams) {
        uiState.sourceAllStreams
            .filterNot(SportsLiveZapResolver::isNonStream)
            .mapNotNull { it.getStreamUrl() }
            .distinct()
            .size
    }
    SportsLivePlayerErrorCard(
        message = message,
        // Not loaded yet (0) or several: the next link is worth a try; one link only: retry it.
        actions = sportsLivePlayerErrorActions(hasOtherLinks = linkCount != 1, canNextLiveGame = zap.hasSession),
        onAction = { action ->
            when (action) {
                SportsLivePlayerErrorAction.TRY_NEXT_LINK -> onEvent(PlayerEvent.OnSportsTryNextLink)
                SportsLivePlayerErrorAction.RETRY -> onEvent(PlayerEvent.OnRetryPlayback)
                SportsLivePlayerErrorAction.CHOOSE_STREAM -> onEvent(PlayerEvent.OnRecoverToSources)
                SportsLivePlayerErrorAction.NEXT_LIVE_GAME -> zap.nextLiveGameAfterFailure()
            }
        },
        modifier = corner
    )
}

/** The last frame stays visible behind the recovery card, dimmed so the card reads. */
private const val BACKDROP_ALPHA = 0.6f

/**
 * The pause screen's description (live.19 QA F10): a game's or channel's without the addon's taglines and ids
 * ("Live IPTV Recording", "Live on ‹ID›", "LIVE NOW"), null when nothing is left; a movie's or show's as is.
 */
internal fun sportsLivePauseDescription(contentId: String?, description: String?): String? =
    if (SportsLiveExtraSources.isSportsPlayback(contentId)) SportsLiveDisplayText.description(description) else description
