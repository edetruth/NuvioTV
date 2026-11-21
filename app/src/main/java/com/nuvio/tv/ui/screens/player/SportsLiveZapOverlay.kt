@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.ui.screens.sportslive.sportFilterLabel
import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nuvio.tv.R
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.ui.screens.sportslive.LiveWindow
import com.nuvio.tv.ui.screens.sportslive.SportsLiveRewind
import com.nuvio.tv.ui.screens.sportslive.SportsLivePausedLive
import com.nuvio.tv.ui.screens.sportslive.SportsLiveZapBanner
import com.nuvio.tv.ui.screens.sportslive.SportsLiveZapPanelState
import com.nuvio.tv.core.sportslive.scores.ScoreChip
import com.nuvio.tv.ui.screens.sportslive.SportsLiveDrawnButton
import com.nuvio.tv.ui.screens.sportslive.rememberScoreChipStrings
import com.nuvio.tv.ui.screens.sportslive.scoreChipParts
import com.nuvio.tv.ui.screens.sportslive.SportsLiveUiState
import com.nuvio.tv.ui.screens.sportslive.SportsLiveGameArt
import com.nuvio.tv.ui.screens.sportslive.LocalPanelTagColumn
import com.nuvio.tv.ui.screens.sportslive.panelBoards
import com.nuvio.tv.ui.screens.sportslive.rememberPanelTagColumn
import com.nuvio.tv.ui.screens.sportslive.LocalOpenSportsLiveGame
import com.nuvio.tv.ui.screens.sportslive.SportsLiveCardMenu
import com.nuvio.tv.ui.screens.sportslive.SportsLiveFilterDropdown
import com.nuvio.tv.ui.screens.sportslive.SportsLiveFilterOption
import com.nuvio.tv.ui.screens.sportslive.SportsLiveGameRequests
import com.nuvio.tv.ui.screens.sportslive.SportsLivePanelHeader
import com.nuvio.tv.ui.screens.sportslive.SportsLivePanelLine
import com.nuvio.tv.ui.screens.sportslive.panelFooterText
import com.nuvio.tv.ui.screens.sportslive.panelFullName
import com.nuvio.tv.ui.screens.sportslive.panelTitleSuffixes
import com.nuvio.tv.ui.screens.sportslive.SportsLiveHiddenByMySportsLine
import com.nuvio.tv.ui.util.dpadRepeatThrottle
import com.nuvio.tv.ui.screens.sportslive.SportsLivePanelMenu
import com.nuvio.tv.ui.screens.sportslive.SportsLiveMoreEntry
import com.nuvio.tv.core.sportslive.scores.ScoreWatch
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.core.sportslive.displayName
import com.nuvio.tv.ui.screens.sportslive.SportsLiveZapViewModel
import com.nuvio.tv.ui.screens.sportslive.PanelGuideEntry
import com.nuvio.tv.ui.screens.sportslive.SportsLiveEndAction
import com.nuvio.tv.ui.screens.sportslive.SportsLiveEndCardView
import com.nuvio.tv.core.sportslive.SportsLiveReadyMark
import com.nuvio.tv.ui.screens.sportslive.TeamLogoSlot
import com.nuvio.tv.ui.screens.sportslive.rememberTeamLogoPair
import com.nuvio.tv.ui.screens.sportslive.dual.LocalOpenSportsLiveDual
import com.nuvio.tv.ui.screens.sportslive.dual.SportsLiveDualSessions
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLivePip
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Sports & Live zap overlay of a player with a zap session: the banner naming the game after a
 * zap (or the one being switched to), and the live games panel that Left opens. Draws nothing for
 * other players. Closing the panel gives focus back to [playerFocusRequester]. While a rewind keeps
 * playback behind the edge of [liveWindow], a small "−1:20 · LIVE ▸" indicator shows it,
 * hidden while [playerOverlayVisible] (controls, a player panel or dialog) or the live games panel is up.
 */
@Composable
internal fun BoxScope.SportsLiveZapOverlay(
    viewModel: SportsLiveZapViewModel,
    playerFocusRequester: FocusRequester,
    liveWindow: () -> LiveWindow?,
    engine: () -> InternalPlayerEngine,
    playerOverlayVisible: Boolean,
    /** Whether the game is playing now: a Game page opened over it resumes it on return (#145). */
    isPlaying: () -> Boolean = { false },
    /** Back to Live Now on the end card's nothing-live state (#161): the player leaves. */
    onBackToLiveNow: () -> Unit = {},
    /** A card above the end card is up ([SportsLivePlayerLayers], live.19 QA F10): the end card waits for it. */
    endCardCovered: Boolean = false,
    /** The player is paused (not buffering): a live game shows "Paused · behind live · Go live" (research Q19). */
    paused: () -> Boolean = { false }
) {
    if (!viewModel.hasSession) return
    // In picture-in-picture only the video shows: no banner, indicator or panel.
    val inPip by SportsLivePip.inPip.collectAsState()
    LaunchedEffect(inPip) { if (inPip) viewModel.closePanel() }
    if (inPip) return
    // The panel's live scores poll while it is open; a stopped player (Home, screen off) closes it.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // The game playing is scored for its full-time toast only while the player is started (#60).
                Lifecycle.Event.ON_START -> viewModel.onPlayerStarted(true)
                Lifecycle.Event.ON_STOP -> {
                    viewModel.closePanel()
                    viewModel.onPlayerStarted(false)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onPlayerStarted(false)
        }
    }
    val banner by viewModel.banner.collectAsState()
    val panelOpen by viewModel.panelOpen.collectAsState()
    // Research Q19: after any pause on a live game, how far behind live and how to get back; it replaces the indicator.
    val pausedLive = PausedLiveChip(
        live = viewModel.nowPlaying?.kind != com.nuvio.tv.domain.model.SportsLiveKind.REPLAY,
        paused = paused,
        liveWindow = liveWindow,
        engine = engine,
        covered = panelOpen
    )
    BehindLiveIndicator(liveWindow = liveWindow, engine = engine, covered = playerOverlayVisible || panelOpen || pausedLive, onBehind = viewModel::onBehindLive)
    val panel by viewModel.panel.collectAsState()
    val panelReadiness by viewModel.panelReadiness.collectAsState()
    val panelKeysHint by viewModel.panelKeysHint.collectAsState()
    val openDual = LocalOpenSportsLiveDual.current

    // The last banner, kept on screen while it fades out.
    var lastBanner by remember { mutableStateOf<SportsLiveZapBanner?>(null) }
    LaunchedEffect(banner) { banner?.let { lastBanner = it } }
    AnimatedVisibility(
        visible = banner != null,
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(250)),
        // V2: the top-left corner of the safe area (`Player.dc.html` 5).
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(top = SportsLiveTokens.Size.SafeTop, start = SportsLiveTokens.Size.SafeEnd)
            .zIndex(3f)
    ) {
        (banner ?: lastBanner)?.let { ZapBanner(it) }
    }

    // The end-of-game card (#161, in place of #60's full-time toast): over the last frame while nothing else is up.
    val endCard by viewModel.endCard.collectAsState()
    val openGameFromEnd = LocalOpenSportsLiveGame.current
    val endShown = endCard != null && banner == null && !panelOpen && !playerOverlayVisible &&
        (endCard?.toastOnly == true || !endCardCovered)
    // Claimed, and its countdown started, only once on screen.
    DisposableEffect(endShown, viewModel) {
        viewModel.onEndCardVisible(endShown)
        onDispose { viewModel.onEndCardVisible(false) }
    }
    // A card closed: the keys go back to the player.
    var endWasShown by remember { mutableStateOf(false) }
    LaunchedEffect(endShown) {
        if (endWasShown && !endShown && endCard?.toastOnly != true) runCatching { playerFocusRequester.requestFocus() }
        endWasShown = endShown && endCard?.toastOnly != true
    }
    if (endShown) {
        endCard?.let { card ->
            // The dimmed backdrop: the last frame stays, the card reads over it.
            if (!card.toastOnly && card.streamStopped) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).zIndex(2.9f))
            }
            SportsLiveEndCardView(
                card = card,
                onAction = { action ->
                    when (action) {
                        SportsLiveEndAction.WATCH_NEXT -> viewModel.endWatchNext()
                        SportsLiveEndAction.REMIND -> viewModel.endRemindUpcoming()
                        SportsLiveEndAction.BACK_TO_LIVE_NOW -> {
                            viewModel.dismissEndCard()
                            onBackToLiveNow()
                        }
                        SportsLiveEndAction.BOX_SCORE -> viewModel.endBoxScore()?.let { request ->
                            SportsLiveGameRequests.open(request)
                            viewModel.onGameOpened(wasPlaying = isPlaying())
                            openGameFromEnd?.invoke()
                        }
                        SportsLiveEndAction.STAY -> viewModel.dismissEndCard()
                    }
                },
                onAnyKey = { viewModel.cancelEndCountdown() },
                modifier = Modifier
                    .align(if (card.toastOnly) Alignment.TopCenter else Alignment.Center)
                    .padding(top = if (card.toastOnly) 32.dp else 0.dp)
                    .zIndex(3f)
            )
        }
    }

    // live.21 B2: the guide as the new table (Settings → Sports & Live → More), over the same panel state.
    val guideTable by viewModel.guideTable.collectAsState()
    // Toasts and the kickoff prompt never sit under the panel (research 4): beside it while it is open.
    com.nuvio.tv.ui.screens.sportslive.SportsLiveToastsBesidePanel(open = panelOpen && !guideTable)
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(panelOpen) {
        if (wasOpen && !panelOpen) runCatching { playerFocusRequester.requestFocus() }
        wasOpen = panelOpen
    }

    // TV QA BUG-H: the panel's backing is drawn by the panel itself ([panelScrim]), under its own rows, not by a layer of
    // its own beside it (that layer did not show on the TV while the rows did, leaving text bare over the video).
    // Hold OK on a row (#140): the V2 action menu, Watch both first.
    var menu by remember { mutableStateOf<SportsLivePanelMenu?>(null) }
    val scoresAheadToast = stringResource(R.string.sports_live_scores_ahead_toast)
    val openGame = LocalOpenSportsLiveGame.current
    LaunchedEffect(panelOpen) { if (!panelOpen) menu = null }
    // B2: the guide stays open while its Hold OK menu is up, and its idle wait starts again once the menu goes.
    LaunchedEffect(menu != null) { viewModel.onGuideMenu(held = menu != null) }
    // live.21 TV test: each guide row keeps one reading (ESPN's once matched) across renders and openings.
    val guideMemory = remember { com.nuvio.tv.ui.screens.sportslive.GuideRowMemory() }
    if (guideTable) {
        val guideStyle by viewModel.guideStyle.collectAsState()
        val fadeMs = com.nuvio.tv.ui.screens.sportslive.guideFadeMs(com.nuvio.tv.ui.screens.sportslive.guideSqueezeModeOf(guideStyle))
        AnimatedVisibility(
            visible = panelOpen,
            enter = fadeIn(tween(fadeMs)),
            exit = fadeOut(tween(fadeMs)),
            modifier = Modifier.fillMaxSize().zIndex(3.2f)
        ) {
            com.nuvio.tv.ui.screens.sportslive.SportsLiveGuideTable(
                state = panel,
                readiness = panelReadiness,
                open = panelOpen,
                menuOpen = menu != null,
                keysHint = panelKeysHint,
                // OK: a matched Just ended game opens its box score (#140); any other row zaps to its game.
                onPick = { item ->
                    viewModel.onPanelOk(item, canOpenGame = openGame != null)?.let { request ->
                        SportsLiveGameRequests.open(request)
                        viewModel.onGameOpened(wasPlaying = isPlaying())
                        openGame?.invoke()
                    }
                },
                onLongPick = { item -> menu = viewModel.panelMenu(item) },
                onChip = viewModel::onPanelChip,
                onRowFocus = viewModel::onPanelRowFocus,
                onKey = viewModel::onPanelKey,
                onClose = { viewModel.closePanel() },
                registerKeys = { handler -> viewModel.guideKeyHandler = handler },
                memory = guideMemory
            )
        }
    } else {
        AnimatedVisibility(
            visible = panelOpen,
            enter = slideInHorizontally(tween(220)) { -it },
            exit = slideOutHorizontally(tween(220)) { -it },
            modifier = Modifier
                .align(Alignment.CenterStart)
                .zIndex(3.2f)
        ) {
            panel?.let { state ->
                LiveGamesPanel(
                    state = state,
                    // OK: a matched Just ended game opens its box score (#140); any other row zaps to its game.
                    onPick = { item ->
                        viewModel.onPanelOk(item, canOpenGame = openGame != null)?.let { request ->
                            SportsLiveGameRequests.open(request)
                            viewModel.onGameOpened(wasPlaying = isPlaying())
                            openGame?.invoke()
                        }
                    },
                    onLongPick = { item -> menu = viewModel.panelMenu(item) },
                    menuOpen = menu != null,
                    onClose = { viewModel.closePanel() },
                    onChip = viewModel::onPanelChip,
                    onRowFocus = viewModel::onPanelRowFocus,
                    onKey = viewModel::onPanelKey,
                    open = panelOpen,
                    readiness = panelReadiness,
                    keysHint = panelKeysHint
                )
            }
        }
    }

    // #218: Hold OK while watching (nothing else open): the player menu, Report a problem first (its snapshot was taken
    // as the hold started), then Choose stream and Scores ahead (a game only: a channel has no score to hold back).
    val playerMenu by viewModel.playerMenu.collectAsState()
    val playingNow = viewModel.nowPlaying
    if (playerMenu && playingNow != null) {
        SportsLiveCardMenu(
            title = playingNow.displayName,
            sides = emptyList(),
            onToggleFollow = {},
            onDetails = null,
            onDismiss = viewModel::dismissPlayerMenu,
            onReport = viewModel::onPlayerMenuReport,
            reportFirst = true,
            onChooseStream = viewModel::onPlayerMenuChooseStream,
            onScoresAhead = if (playingNow.isChannel) {
                null
            } else {
                {
                    viewModel.dismissPlayerMenu()
                    viewModel.onScoresAhead { seconds ->
                        com.nuvio.tv.core.sportslive.SportsLiveToasts.show(
                            com.nuvio.tv.core.sportslive.SportsLiveToastKind.INFO,
                            scoresAheadToast.format(SportsLiveRewind.formatBehind(seconds * 1_000L))
                        )
                    }
                }
            }
        )
    }

    val favoriteAddedToast = stringResource(R.string.sports_live_channel_favorite_added_toast)
    val favoriteRemovedToast = stringResource(R.string.sports_live_channel_favorite_removed_toast)
    menu?.let { shown ->
        val item = shown.item
        SportsLiveCardMenu(
            title = item.displayName,
            sides = shown.sides,
            onToggleFollow = { index ->
                menu = null
                shown.targets.getOrNull(index)?.let(viewModel::onPanelToggleFollow)
            },
            onDetails = null,
            onDismiss = { menu = null },
            // Q18 (#85): other games' scores ahead of this stream? Each press makes them wait 15 s more for its source.
            onScoresAhead = if (viewModel.nowPlaying?.isChannel == false) {
                {
                    menu = null
                    viewModel.onScoresAhead { seconds ->
                        com.nuvio.tv.core.sportslive.SportsLiveToasts.show(
                            com.nuvio.tv.core.sportslive.SportsLiveToastKind.INFO,
                            scoresAheadToast.format(SportsLiveRewind.formatBehind(seconds * 1_000L))
                        )
                    }
                }
            } else {
                null
            },
            // Watch both: dual screen right away, the game playing beside the one held.
            onWatchWith = if (shown.watchBoth && openDual != null) {
                {
                    menu = null
                    viewModel.dualSessionWith(item)?.let { session ->
                        viewModel.closePanel()
                        SportsLiveDualSessions.start(session)
                        openDual.invoke()
                    }
                }
            } else {
                null
            },
            watchWithText = R.string.sports_live_watch_both,
            watchWithFirst = true,
            // OK opens this Just ended game's box score: its postgame stream stays here (#140).
            onWatchPostgame = if (shown.postgame) {
                {
                    menu = null
                    viewModel.onPanelPick(item)
                }
            } else {
                null
            },
            onChooseStream = if (shown.chooseStream) {
                {
                    menu = null
                    viewModel.onPanelChooseStream(item)
                }
            } else {
                null
            },
            // TV QA: the one way to report on a remote with no Play/Pause or Info to hold.
            onReport = if (SportsLiveMoreEntry.REPORT in shown.entries) {
                {
                    menu = null
                    viewModel.onPanelReport()
                }
            } else {
                null
            },
            // Channels phase 1: Hold OK on a channel row, Add to / Remove from favorites first.
            favorite = shown.favorite == true,
            onToggleFavorite = shown.favorite?.let { wasFavorite ->
                {
                    menu = null
                    viewModel.onPanelToggleFavorite(item, wasFavorite)
                    val name = com.nuvio.tv.core.sportslive.SportsLiveDisplayText.of(item.meta.name).name
                    com.nuvio.tv.core.sportslive.SportsLiveToasts.show(
                        if (wasFavorite) com.nuvio.tv.core.sportslive.SportsLiveToastKind.INFO else com.nuvio.tv.core.sportslive.SportsLiveToastKind.FOLLOWED,
                        (if (wasFavorite) favoriteRemovedToast else favoriteAddedToast).format(name)
                    )
                }
            },
            onGameStats = if (shown.game != null && openGame != null) {
                {
                    menu = null
                    viewModel.panelGameRequest(shown)?.let { request ->
                        SportsLiveGameRequests.open(request)
                        viewModel.onGameOpened(wasPlaying = isPlaying())
                        openGame.invoke()
                    }
                }
            } else {
                null
            }
        )
    }
}

/**
 * "Paused · −0:23 behind live · OK resume · ▶▶ Go live" (research Q19), top right, while a live game is paused, the
 * controls up or not ([covered]: the live games panel). True while it shows.
 */
@Composable
private fun BoxScope.PausedLiveChip(
    live: Boolean,
    paused: () -> Boolean,
    liveWindow: () -> LiveWindow?,
    engine: () -> InternalPlayerEngine,
    covered: Boolean
): Boolean {
    var state by remember { mutableStateOf<SportsLivePausedLive?>(null) }
    LaunchedEffect(live) {
        while (live) {
            state = if (paused()) SportsLivePausedLive.of(hasSession = true, engine = engine(), window = liveWindow()) else null
            delay(500)
        }
        state = null
    }
    val shown = state?.takeIf { !covered } ?: return false
    val separator = stringResource(R.string.sports_live_game_info_separator)
    val text = listOfNotNull(
        stringResource(R.string.sports_live_paused),
        shown.behindMs?.let { stringResource(R.string.sports_live_paused_behind, SportsLiveRewind.formatBehind(it)) },
        stringResource(R.string.sports_live_paused_resume),
        stringResource(R.string.sports_live_paused_go_live).takeIf { shown.canGoLive }
    ).joinToString(separator)
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = NuvioTheme.colors.TextPrimary,
        maxLines = 1,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(top = 32.dp, end = 32.dp)
            .zIndex(3f)
            .clip(RoundedCornerShape(NuvioTheme.radii.xl))
            .background(NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.92f))
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
    return true
}

@Composable
private fun BoxScope.BehindLiveIndicator(
    liveWindow: () -> LiveWindow?,
    engine: () -> InternalPlayerEngine,
    covered: Boolean,
    /** The player's distance behind its live edge, for the scores (live.20 Q18, #85: other games wait that long more). */
    onBehind: (Long?) -> Unit = {}
) {
    var window by remember { mutableStateOf<LiveWindow?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            window = liveWindow()
            onBehind(window?.takeIf { it.isDynamic }?.behindEdgeMs)
            delay(500)
        }
    }
    val shown = SportsLiveRewind.indicatorBehindMs(
        hasSession = true, engine = engine(), window = window, covered = covered
    ) ?: return
    // Q18: "LIVE · −0:45"; from 10 s behind, "▶▶ Go live" (fast-forward reaches the edge).
    val label = ScoreWatch.liveLabel(shown)
    Text(
        text = stringResource(
            if (label.offersGoLive) R.string.sports_live_rewind_behind_go_live else R.string.sports_live_rewind_behind,
            SportsLiveRewind.formatBehind(shown)
        ),
        style = MaterialTheme.typography.labelLarge,
        color = NuvioTheme.colors.TextPrimary,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(top = 32.dp, end = 32.dp)
            .zIndex(3f)
            .clip(RoundedCornerShape(NuvioTheme.radii.xl))
            .background(NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.92f))
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

/**
 * The zap banner (#76) in the V2 language (`Player.dc.html` 5): a slim top-left card. The league tag, both logos
 * around the clean matchup (or the channel's name); a status line, the red dot with the clock and score under the
 * Scores setting; then "▲ previous game" and "▼ next game" (BUG6/Q10) and, once arrived, "◀ All games". Never the
 * source's name.
 */
@Composable
private fun ZapBanner(banner: SportsLiveZapBanner) {
    val shape = RoundedCornerShape(SportsLiveTokens.Alert.Radius)
    Column(
        verticalArrangement = Arrangement.spacedBy(SportsLiveTokens.Alert.Gap),
        modifier = Modifier
            .widthIn(max = BANNER_MAX_WIDTH)
            .clip(shape)
            // TV QA P6: near-opaque over video (the shared alert fill let bright scenes wash the hint lines out).
            .background(SportsLiveTokens.Colors.SurfaceRaised.copy(alpha = BANNER_FILL_ALPHA))
            .border(1.dp, SportsLiveTokens.Alert.Border, shape)
            .padding(SportsLiveTokens.Alert.Padding)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            banner.tag?.let { tag ->
                Text(
                    text = tag,
                    style = SportsLiveTokens.style(SportsLiveTokens.Type.Tag),
                    color = SportsLiveTokens.Colors.TextSecondary,
                    maxLines = 1,
                    softWrap = false
                )
            }
            // Logos only when both teams have one and both loaded (#130); never letters.
            val logos = rememberTeamLogoPair(banner.art)
            if (logos != null) TeamLogoSlot(logos.first, logos.shown, SportsLiveTokens.Alert.Logo)
            Text(
                text = if (banner.switching) {
                    stringResource(R.string.sports_live_zap_switching, banner.name)
                } else {
                    banner.name
                },
                style = SportsLiveTokens.style(SportsLiveTokens.Toast.Title),
                color = SportsLiveTokens.Colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (logos != null) TeamLogoSlot(logos.second, logos.shown, SportsLiveTokens.Alert.Logo)
        }
        banner.chip?.let { chip ->
            val (lead, score) = scoreChipParts(chip, rememberScoreChipStrings())
            val live = chip is ScoreChip.Live
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (live) Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(SportsLiveTokens.Colors.LiveRed))
                Text(
                    text = listOfNotNull(lead.takeIf(String::isNotEmpty), score).joinToString(" "),
                    style = SportsLiveTokens.style(SportsLiveTokens.Toast.Line).copy(fontFeatureSettings = "tnum"),
                    color = if (live) SportsLiveTokens.Colors.TextPrimary else SportsLiveTokens.Colors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        // BUG6/Q10: the games ▲ and ▼ zap to, named from the frozen zap order (no "N of M", which moved with the list).
        // A leading emoji or symbol on a title ("📺 …") goes. One line each, so a long name never hides the other.
        val previous = banner.previous?.replaceFirst(LEADING_SYMBOLS, "")?.let { stringResource(R.string.sports_live_zap_previous_name, it) }
        val next = banner.next?.replaceFirst(LEADING_SYMBOLS, "")?.let { stringResource(R.string.sports_live_zap_next_name, it) }
        // ◀ opens the panel (a short Left); ▼ always zaps on, as the line above says.
        val panelHint = stringResource(R.string.sports_live_zap_left_hint).takeIf { !banner.switching }
        listOfNotNull(previous, next, panelHint).forEach { line ->
            Text(
                text = line,
                style = SportsLiveTokens.style(SportsLiveTokens.Type.Tag),
                // TV QA P6: secondary, not muted: these lines name where ▲ ▼ go and read at a glance from the sofa.
                color = SportsLiveTokens.Colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (banner.looking) {
            // #158: a CH± zap's slow lookup; Skip is the card's one action (OK), drawn focused (never a white slab).
            Row(
                modifier = Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.sports_live_zap_looking),
                    style = SportsLiveTokens.style(SportsLiveTokens.Toast.Line),
                    color = SportsLiveTokens.Colors.TextSecondary,
                    maxLines = 1
                )
                SportsLiveDrawnButton(
                    text = stringResource(R.string.sports_live_zap_skip),
                    primary = true,
                    focused = true,
                    height = 28.dp
                )
            }
        }
    }
}

private val LEADING_SYMBOLS = Regex("""^[^\p{L}\p{N}\[(]+""")

/** The zap banner's fill (TV QA P6): near-opaque, as the banner sits over live video. */
private const val BANNER_FILL_ALPHA = 0.95f

/** The banner's widest: 720 px, clear of the game's own scorebug. */
private val BANNER_MAX_WIDTH = 360.dp

/**
 * The games live now, from the left edge (#140): a slim column of one-line rows over a soft fade, so the game
 * stays visible beside it. Under one filter dropdown (V2: "My sports ▾", OK opens its options), the
 * game playing is pinned as **Now playing**, where focus opens; below it the lineup in its own order, the game
 * playing drawn under Now playing only, keeping its place in the CH± walk ([SportsLiveZapPanelState.guide]): CH−
 * plays the game after that place, CH+ the one before it. Under My sports the list may end with "N more hidden by My
 * sports · Show all" (#167). Left or Right closes the panel (Back is handled by the player), and Up/Down stay
 * inside it. OK on a game zaps to it; Hold OK opens its menu ([onLongPick]). Focus is kept by row key: a row that
 * leaves the list hands focus to the nearest one, and focus lost while the panel is [open] is given back (#167).
 */
@Composable
private fun LiveGamesPanel(
    state: SportsLiveZapPanelState,
    onPick: (SportsLiveItem) -> Unit,
    /** Hold OK on a game: its action menu, Watch both first. */
    onLongPick: (SportsLiveItem) -> Unit,
    /** The menu is open (the hold is then reset). */
    menuOpen: Boolean,
    onClose: () -> Unit,
    /** A filter option was picked (null is All). */
    onChip: (String?) -> Unit,
    /** A game row took focus (Hide until focused reveals its score after a second). */
    onRowFocus: (String?) -> Unit,
    /** Any key pressed inside the panel, by key code (Hide until focused reveals only after one; Info shows the keys hint). */
    onKey: (Int) -> Unit,
    /** The panel is open (false while it fades out): only then is lost focus given back. */
    open: Boolean,
    /** Ready / no working stream by stream key (#147, live.20 1b); a game with none has no mark. */
    readiness: Map<String, SportsLiveReadyMark> = emptyMap(),
    /** The keys hint shows under the list (live.20: the profile's first few openings). */
    keysHint: Boolean = false
) {
    // #140: the list starts at its top, never scrolled to centre one.
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val nowFocusRequester = remember { FocusRequester() }
    val emptyFocusRequester = remember { FocusRequester() }
    val filterFocusRequester = remember { FocusRequester() }
    // #167: one requester per focusable line, by key: focus is kept and given back by key, never by index.
    val lineRequesters = remember { HashMap<String, FocusRequester>() }
    fun requesterOf(key: String): FocusRequester = lineRequesters.getOrPut(key) { FocusRequester() }
    val guide = state.guide
    val nowRow = guide.nowPlaying ?: state.nowPlayingRow
    val nowKey = nowRow?.item?.streamKey
    val firstKey = guide.firstFocusableKey
    // While the filter has focus, an option that empties (or fills) the list leaves focus on it.
    var filterFocused by remember { mutableStateOf(false) }
    // The filter's options are open (a dialog has focus): focus is not lost meanwhile.
    var filterMenuOpen by remember { mutableStateOf(false) }
    // The line with focus, by key (a game's stream key, the pinned row's too; null on the filter), and the list
    // index it was last at, where focus falls back to should its line go.
    var focusedKey by remember { mutableStateOf<String?>(null) }
    var focusedIndex by remember { mutableIntStateOf(-1) }
    var panelHasFocus by remember { mutableStateOf(false) }
    // A key was pressed in the panel since it opened: from then on focus is where the user put it.
    var userKeyed by remember { mutableStateOf(false) }
    // live.20: the footer names the focused row in full; nothing while the filter has focus.
    val focusedBoard = remember(state, focusedKey, nowRow) {
        (state.rows.firstOrNull { it.item.streamKey == focusedKey } ?: nowRow?.takeIf { it.item.streamKey == focusedKey })
            ?.let { row -> state.boards[row.item.streamKey]?.board ?: panelBoards(listOf(row), emptyMap(), null, emptySet(), 0L).single().board }
    }
    // BUG-C: the rows whose "▶ Ready" had no room; the footer says it for the focused one.
    var readyHiddenKeys by remember { mutableStateOf(emptySet<String>()) }
    // live.20: rows of one title ("MotoGP" four times) each say what tells them apart ("· Onboard").
    val feedFormat = stringResource(R.string.sports_live_panel_feed)
    val suffixes = remember(state.rows, nowRow, feedFormat) {
        panelTitleSuffixes((listOfNotNull(nowRow) + state.rows).map { it.item }, { n -> String.format(feedFormat, n) })
    }
    // One option (All) filters nothing: no filter then.
    val filterShown = state.chips.size > 1

    /** Brings line [key] into view (scrolled to only when it is not laid out wholly) and focuses it. */
    suspend fun focusLine(key: String) {
        val index = guide.indexOf(key)
        if (index < 0) return
        if (!listState.isFullyVisible(index)) listState.scrollToItem(index)
        withFrameNanos { }
        runCatching { requesterOf(key).requestFocus() }
    }

    // #140: focus opens on Now playing; on the first game when the game playing is unknown, else the empty line.
    // TV QA (54-panel): the panel opened with no focus at all (no ring, blank footer). Focus is now asked for until it
    // lands (a request in the panel's first frames can come to nothing), and once the game playing is known focus
    // moves to it from wherever the panel put it meanwhile (the filter); only a line the user moved to is kept.
    LaunchedEffect(nowRow == null, firstKey == null) {
        repeat(PANEL_OPEN_FOCUS_FRAMES) {
            val target = panelOpenFocus(nowRow != null, firstKey != null, userKeyed, panelHasFocus || filterFocused || filterMenuOpen)
            val landed = when (target) {
                PanelOpenFocus.KEEP -> true
                PanelOpenFocus.NOW_PLAYING -> focusedKey == nowKey && panelHasFocus
                PanelOpenFocus.FIRST_LINE, PanelOpenFocus.EMPTY_LINE -> panelHasFocus && !filterFocused
            }
            if (landed) return@LaunchedEffect
            when (target) {
                PanelOpenFocus.NOW_PLAYING -> runCatching { nowFocusRequester.requestFocus() }
                PanelOpenFocus.FIRST_LINE -> firstKey?.let { focusLine(it) }
                PanelOpenFocus.EMPTY_LINE -> runCatching { emptyFocusRequester.requestFocus() }
                PanelOpenFocus.KEEP -> Unit
            }
            withFrameNanos { }
        }
    }
    // #164/#167: an update keeps focus on the same line, by key, and keeps it in view (scrolled only when it left
    // the view). A line no longer listed (a chip or Show all changed the list) hands focus to the nearest one.
    LaunchedEffect(guide) {
        val key = focusedKey ?: return@LaunchedEffect
        if (key == nowKey) return@LaunchedEffect
        val index = guide.indexOf(key)
        if (index >= 0) {
            focusedIndex = index
            withFrameNanos { }
            if (!listState.isFullyVisible(index)) runCatching { listState.centerOn(index) }
            return@LaunchedEffect
        }
        val fallback = guide.nearestFocusable(focusedIndex)
        when {
            fallback != null -> focusLine(fallback)
            nowRow != null -> runCatching { nowFocusRequester.requestFocus() }
            filterShown -> runCatching { filterFocusRequester.requestFocus() }
        }
    }
    // #167: focus never stays lost while the panel is open (a removed node, a dialog gone): it comes back to the
    // line it was on, else the nearest one, else Now playing. A move between lines never leaves the panel focusless
    // for a frame, so two frames without focus is a loss.
    LaunchedEffect(panelHasFocus, open, menuOpen, filterMenuOpen) {
        if (panelHasFocus || !open || menuOpen || filterMenuOpen) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        val key = focusedKey
        when {
            key != null && key != nowKey && guide.indexOf(key) >= 0 -> focusLine(key)
            key != null && key != nowKey -> guide.nearestFocusable(focusedIndex)?.let { focusLine(it) }
                ?: runCatching { nowFocusRequester.requestFocus() }
            filterFocused || (key == null && filterShown && nowRow == null && firstKey == null) -> runCatching { filterFocusRequester.requestFocus() }
            nowRow != null -> runCatching { nowFocusRequester.requestFocus() }
            firstKey != null -> focusLine(firstKey)
        }
    }
    val boxScoreHint = stringResource(R.string.sports_live_panel_box_score_hint)
    val lineFocus: (String, String?) -> Unit = { key, rowKey ->
        focusedKey = key
        guide.indexOf(key).takeIf { it >= 0 && key != nowKey }?.let { focusedIndex = it }
        onRowFocus(rowKey)
    }

    // The rows' tag column as wide as the widest tag shown (coordinator decision): "NCAAM" rows keep no "FOOTBALL" room.
    val shownBoards = remember(state, nowRow, guide) {
        (listOfNotNull(nowRow) + guide.entries.filterIsInstance<PanelGuideEntry.Game>().map { it.row }).map { row ->
            state.boards[row.item.streamKey]?.board ?: panelBoards(listOf(row), emptyMap(), null, emptySet(), 0L).single().board
        }
    }
    val tagColumn = rememberPanelTagColumn(shownBoards)

    // Left or Right closes the panel on release, only when its press reached the panel: the filter uses its own.
    var closeArmed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(PANEL_WIDTH)
            .panelScrim()
            .onFocusChanged { panelHasFocus = it.hasFocus }
            .focusProperties { onExit = { cancelFocusChange() } }
            .onPreviewKeyEvent {
                val native = it.nativeKeyEvent
                if (native.action == KeyEvent.ACTION_DOWN) {
                    userKeyed = true
                    onKey(native.keyCode)
                }
                false
            }
            .focusGroup()
            .onKeyEvent { event ->
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        when (event.nativeKeyEvent.action) {
                            KeyEvent.ACTION_DOWN -> closeArmed = true
                            KeyEvent.ACTION_UP -> {
                                if (closeArmed) onClose()
                                closeArmed = false
                            }
                        }
                        true
                    }
                    else -> false
                }
            }
    ) {
        CompositionLocalProvider(LocalPanelTagColumn provides tagColumn) {
            Column(modifier = Modifier.padding(horizontal = PANEL_PADDING, vertical = NuvioTheme.spacing.lg)) {
                // V2 (`Player.dc.html` 1): "Live now" and the filter on one line; no preview (live.20, #106), the list has the room.
                Row(
                    modifier = Modifier.padding(horizontal = PANEL_ROW_ROOM).height(PANEL_HEADER_HEIGHT),
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.sports_live_zap_panel_title),
                        style = SportsLiveTokens.style(SportsLiveTokens.Type.PanelTitle),
                        color = SportsLiveTokens.Colors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (filterShown) PanelFilter(state, onChip, onRowFocus, filterFocusRequester, { filterMenuOpen = it }) {
                        filterFocused = it
                        // No game row is focused while the filter is: none is revealed meanwhile.
                        if (it) focusedKey = null
                    }
                }
                // #216: All sports says so, as it lists sports the user never picked (it resets when the panel opens again).
                if (state.chip == com.nuvio.tv.ui.screens.sportslive.SportsLiveUiState.ALL_SPORTS_FILTER) {
                    Text(
                        text = stringResource(R.string.sports_live_panel_all_sports_scope),
                        style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta),
                        color = SportsLiveTokens.Colors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = PANEL_ROW_ROOM)
                    )
                }
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
                // #140: the game playing, pinned under the filter. Up goes to the filter, Down to the list's first line.
                nowRow?.let { row ->
                    val item = row.item
                    SportsLivePanelLine(
                        row = state.boards[item.streamKey]?.board
                            ?: panelBoards(listOf(row), emptyMap(), null, emptySet(), 0L).single().board,
                        nowPlaying = true,
                        titleSuffix = suffixes[item.streamKey],
                        onClick = { onPick(item) },
                        onLongClick = { onLongPick(item) },
                        onFocus = { lineFocus(item.streamKey, item.streamKey) },
                        modifier = Modifier
                            .padding(horizontal = PANEL_ROW_ROOM)
                            .focusRequester(nowFocusRequester)
                            .then(if (filterShown) Modifier.focusProperties { up = filterFocusRequester } else Modifier)
                            .onPreviewKeyEvent { event ->
                                // ▼: the list's first line, scrolled back into view first (#140).
                                val native = event.nativeKeyEvent
                                if (native.keyCode != KeyEvent.KEYCODE_DPAD_DOWN || native.action != KeyEvent.ACTION_DOWN) {
                                    return@onPreviewKeyEvent false
                                }
                                val first = firstKey ?: return@onPreviewKeyEvent false
                                scope.launch { focusLine(first) }
                                true
                            },
                        menuOpen = menuOpen
                    )
                    // #145: a 6 dp gap and a thin rule, so the scrolled rows never butt into the pinned row.
                    Spacer(modifier = Modifier.height(NOW_PLAYING_GAP))
                    Box(
                        modifier = Modifier
                            .padding(horizontal = PANEL_ROW_ROOM)
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(SportsLiveTokens.Colors.TextSecondary.copy(alpha = 0.35f))
                    )
                    Spacer(modifier = Modifier.height(NOW_PLAYING_GAP))
                }
                if (firstKey == null) {
                    // Nothing else on: Now playing alone, or the empty line when even that is unknown.
                    if (nowRow == null || state.loading) {
                        Text(
                            text = stringResource(if (state.loading) R.string.sports_live_zap_panel_loading else R.string.sports_live_zap_panel_empty),
                            style = SportsLiveTokens.style(SportsLiveTokens.Type.PanelLine),
                            color = SportsLiveTokens.Colors.TextSecondary,
                            modifier = Modifier
                                .padding(horizontal = PANEL_ROW_ROOM)
                                .focusRequester(emptyFocusRequester)
                                .focusable()
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                } else {
                    // Up from the list's first line reaches Now playing (else the filter), never past it.
                    val upFromFirst: FocusRequester? = if (nowRow != null) nowFocusRequester else filterFocusRequester.takeIf { filterShown }
                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(SportsLiveTokens.Size.PanelLineGap),
                        // Room for the rows' focus ring and lift, so neither is clipped.
                        contentPadding = PaddingValues(horizontal = PANEL_ROW_ROOM, vertical = NuvioTheme.spacing.xs),
                        // #167: held keys move focus at a steady pace instead of flooding the list.
                        modifier = Modifier.weight(1f).dpadRepeatThrottle()
                    ) {
                        guide.entries.forEach { entry ->
                            when (entry) {
                                is PanelGuideEntry.Header -> item(key = entry.key) { SportsLivePanelHeader(entry.section, entry.count) }
                                is PanelGuideEntry.HiddenByMySports -> item(key = entry.key) {
                                    SportsLiveHiddenByMySportsLine(
                                        count = entry.count,
                                        onClick = { onChip(SportsLiveUiState.ALL_SPORTS_FILTER) },
                                        onFocus = { lineFocus(entry.key, null) },
                                        modifier = Modifier
                                            .focusRequester(requesterOf(entry.key))
                                            .then(
                                                if (entry.key == firstKey && upFromFirst != null) {
                                                    Modifier.focusProperties { up = upFromFirst }
                                                } else {
                                                    Modifier
                                                }
                                            )
                                    )
                                }
                                is PanelGuideEntry.Game -> {
                                    val row = entry.row
                                    val item = row.item
                                    item(key = entry.key) {
                                        SportsLivePanelLine(
                                            row = state.boards[item.streamKey]?.board
                                                ?: panelBoards(listOf(row), emptyMap(), null, emptySet(), 0L).single().board,
                                            nowPlaying = false,
                                            titleSuffix = suffixes[item.streamKey],
                                            isNew = row.isNew,
                                            gone = row.gone,
                                            onClick = { onPick(item) },
                                            onLongClick = { onLongPick(item) },
                                            onFocus = { lineFocus(item.streamKey, item.streamKey) },
                                            okHint = boxScoreHint.takeIf { item.streamKey in state.boxScores },
                                            readyMark = readiness[item.streamKey],
                                            onReadyHidden = { hidden ->
                                                readyHiddenKeys = if (hidden) readyHiddenKeys + item.streamKey else readyHiddenKeys - item.streamKey
                                            },
                                            modifier = when {
                                                entry.key == firstKey && upFromFirst != null ->
                                                    Modifier.focusRequester(requesterOf(entry.key)).focusProperties { up = upFromFirst }
                                                else -> Modifier.focusRequester(requesterOf(entry.key))
                                            },
                                            menuOpen = menuOpen
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                // live.20: the focused row's full name, which its one line may cut (both sides, the whole title). The line
                // stays, blank, while nothing is focused, so the list never jumps.
                val vs = stringResource(R.string.sports_live_panel_vs)
                val readyWord = stringResource(R.string.sports_live_ready_pill)
                val separator = stringResource(R.string.sports_live_game_info_separator)
                Text(
                    text = focusedBoard?.let {
                        val key = it.item.streamKey
                        panelFooterText(panelFullName(it, vs, suffixes[key]), key in readyHiddenKeys, readyWord, separator)
                    }.orEmpty(),
                    style = SportsLiveTokens.style(SportsLiveTokens.Type.PanelLine),
                    color = SportsLiveTokens.Colors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(start = PANEL_FOOTER_START, end = PANEL_ROW_ROOM, top = NuvioTheme.spacing.xs)
                )
                // #140: the keys hint, for the profile's first few openings, after a long gap, or on Info (live.20, research 8).
                if (keysHint) {
                    Text(
                        text = stringResource(R.string.sports_live_panel_footer_hint),
                        style = SportsLiveTokens.style(SportsLiveTokens.Type.Tag),
                        color = SportsLiveTokens.Colors.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = PANEL_FOOTER_START, top = 2.dp)
                    )
                }
            }
        }
    }
}

/**
 * The panel's filter (V2 `Player.dc.html` 1): one dropdown, "My sports ▾", no counts; OK opens its options (#146: All
 * is My sports' games while it scopes the panel; All sports lifts it). [onFocused] follows its focus.
 */
@Composable
private fun PanelFilter(
    state: SportsLiveZapPanelState,
    onChip: (String?) -> Unit,
    onRowFocus: (String?) -> Unit,
    focusRequester: FocusRequester,
    onOpenChange: (Boolean) -> Unit,
    onFocused: (Boolean) -> Unit
) {
    val scoped = SportsLiveUiState.ALL_SPORTS_FILTER in state.chips
    val allLabel = stringResource(if (scoped) R.string.sports_live_filter_my_sports else R.string.sports_live_filter_all)
    val allSportsLabel = stringResource(R.string.sports_live_filter_all_sports)
    val myTeamsLabel = stringResource(R.string.sports_live_filter_my_teams)
    val options = state.chips.map { chip ->
        val name = when (chip) {
            null -> allLabel
            SportsLiveUiState.MY_TEAMS_FILTER -> myTeamsLabel
            SportsLiveUiState.ALL_SPORTS_FILTER -> allSportsLabel
            else -> sportFilterLabel(chip)
        }
        SportsLiveFilterOption(chip, name, gold = chip == SportsLiveUiState.MY_TEAMS_FILTER)
    }
    SportsLiveFilterDropdown(
        options = options,
        selected = state.chip,
        onSelect = onChip,
        title = stringResource(R.string.sports_live_zap_panel_title),
        onOpenChange = onOpenChange,
        modifier = Modifier
            .onFocusChanged {
                onFocused(it.isFocused)
                if (it.isFocused) onRowFocus(null)
            }
            .focusRequester(focusRequester)
    )
}

/** Scrolls item [index] to the middle of the list (as near as the list's ends allow), once laid out. */
private suspend fun LazyListState.centerOn(index: Int) {
    scrollToItem(index)
    withFrameNanos { }
    val info = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val viewport = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
    scrollBy((info.offset + info.size / 2 - viewport / 2).toFloat())
}

/** True when item [index] is laid out wholly inside the list's viewport. */
private fun LazyListState.isFullyVisible(index: Int): Boolean {
    val info = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return false
    return info.offset >= layoutInfo.viewportStartOffset && info.offset + info.size <= layoutInfo.viewportEndOffset
}

/** The live games panel (#140): 280 dp wide at 1080p (29 % of the screen), its rows filling it. */
private val PANEL_WIDTH = SportsLiveTokens.Size.PanelWidth
private val PANEL_PADDING = 4.dp
private val PANEL_ROW_ROOM = 6.dp

/** The header line: "Live now" and the filter (V2), the filter's height. */
private val PANEL_HEADER_HEIGHT = SportsLiveTokens.Size.ControlHeight + 4.dp

/** The footer's start: where the rows' tags start. */
private val PANEL_FOOTER_START = PANEL_ROW_ROOM + 8.dp

/** Above and below the rule under the pinned Now playing row (#145). */
private val NOW_PLAYING_GAP = 6.dp

/** Where focus goes as the panel opens (#140, TV QA 54-panel): [panelOpenFocus]. */
internal enum class PanelOpenFocus { NOW_PLAYING, FIRST_LINE, EMPTY_LINE, KEEP }

/**
 * Where focus goes as the panel opens (#140): the game playing's row once it is known ([nowPlaying]), else the list's
 * first line ([firstLine]), else the empty line. Focus stays where it is only once the user moved it ([userKeyed]) and
 * the panel holds it ([hasFocus]): focus the panel itself put on the filter while the game playing was unknown moves on
 * to Now playing (TV QA: blank footer, no ring).
 */
internal fun panelOpenFocus(nowPlaying: Boolean, firstLine: Boolean, userKeyed: Boolean, hasFocus: Boolean): PanelOpenFocus =
    when {
        userKeyed && hasFocus -> PanelOpenFocus.KEEP
        nowPlaying -> PanelOpenFocus.NOW_PLAYING
        firstLine -> PanelOpenFocus.FIRST_LINE
        else -> PanelOpenFocus.EMPTY_LINE
    }

/** How many frames the panel keeps asking for its opening focus before it leaves it to the loss watch below. */
private const val PANEL_OPEN_FOCUS_FRAMES = 30

/** How far past the panel its fade runs out (#140, #145). */
private val PANEL_FADE = 80.dp

/** One point of the panel's backing ([panelScrimStops]): [at] from the panel's left edge, black at [alpha]. */
internal data class PanelScrimStop(val at: Dp, val alpha: Float)

/**
 * Behind the panel (#145, TV QA P6): near-opaque black behind the rows (95 % at the left edge, still 90 % at the
 * panel's edge, [panelWidth]), so muted text keeps its contrast over a white or busy scene (10-ft text over video wants
 * a solid backing, not a tint: Android TV overlay guidance, WCAG 4.5:1 for the muted lines); then a soft fall to clear
 * over the [fade] past it, so the game stays visible beside the panel.
 */
internal fun panelScrimStops(panelWidth: Dp, fade: Dp): List<PanelScrimStop> = listOf(
    PanelScrimStop(0.dp, 0.95f),
    PanelScrimStop(panelWidth, 0.9f),
    PanelScrimStop(panelWidth + fade / 3, 0.55f),
    PanelScrimStop(panelWidth + fade, 0f)
)

/**
 * The panel's backing ([panelScrimStops]), drawn by the panel's own node under its rows (TV QA BUG-H), past its right
 * edge over the fade: wherever the rows draw, so does their backing.
 */
private fun Modifier.panelScrim(): Modifier = drawWithCache {
    val stops = panelScrimStops(PANEL_WIDTH, PANEL_FADE)
    val end = stops.last().at
    val brush = Brush.horizontalGradient(
        *stops.map { (it.at / end) to Color.Black.copy(alpha = it.alpha) }.toTypedArray(),
        startX = 0f,
        endX = end.toPx()
    )
    val scrimSize = Size(end.toPx(), size.height)
    onDrawBehind { drawRect(brush = brush, size = scrimSize) }
}
