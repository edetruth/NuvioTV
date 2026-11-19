@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.ui.screens.sportslive.panelPreviewCornerShown
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nuvio.tv.R
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.ui.screens.sportslive.LiveWindow
import com.nuvio.tv.ui.screens.sportslive.SportsLiveRewind
import com.nuvio.tv.ui.screens.sportslive.SportsLiveZapBanner
import com.nuvio.tv.ui.screens.sportslive.SportsLiveZapPanelState
import com.nuvio.tv.ui.screens.sportslive.SportsLiveScoreChip
import com.nuvio.tv.ui.screens.sportslive.SportsLiveUiState
import com.nuvio.tv.ui.screens.sportslive.sportsLiveChipText
import com.nuvio.tv.ui.screens.sportslive.SportsLiveGameArt
import com.nuvio.tv.ui.screens.sportslive.panelBoards
import com.nuvio.tv.ui.screens.sportslive.LocalOpenSportsLiveGame
import com.nuvio.tv.ui.screens.sportslive.SportsLiveCardMenu
import com.nuvio.tv.ui.screens.sportslive.SportsLiveFilterControl
import com.nuvio.tv.ui.screens.sportslive.SportsLiveFilterOption
import com.nuvio.tv.ui.screens.sportslive.SportsLiveGameRequests
import com.nuvio.tv.ui.screens.sportslive.SportsLivePanelHeader
import com.nuvio.tv.ui.screens.sportslive.SportsLivePanelLine
import com.nuvio.tv.ui.screens.sportslive.SportsLivePanelMenu
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.core.sportslive.displayName
import com.nuvio.tv.ui.screens.sportslive.preview.SportsLivePanelPreviewViewModel
import com.nuvio.tv.ui.screens.sportslive.preview.SportsLivePreviewLabel
import com.nuvio.tv.ui.screens.sportslive.preview.SportsLivePreviewPlayer
import com.nuvio.tv.ui.screens.sportslive.preview.SportsLivePreviewState
import com.nuvio.tv.ui.screens.sportslive.preview.SportsLivePreviewVideo
import com.nuvio.tv.ui.screens.sportslive.preview.previewKey
import coil3.compose.AsyncImage
import com.nuvio.tv.ui.screens.sportslive.SportsLiveZapViewModel
import com.nuvio.tv.ui.screens.sportslive.PanelGuideEntry
import com.nuvio.tv.core.sportslive.FullTimeToast
import com.nuvio.tv.core.sportslive.FullTimeToastStrings
import com.nuvio.tv.core.sportslive.SportsLiveFullTimeToasts
import com.nuvio.tv.ui.screens.sportslive.TeamLogoSlot
import com.nuvio.tv.ui.screens.sportslive.rememberTeamLogoPair
import com.nuvio.tv.ui.screens.sportslive.dual.LocalOpenSportsLiveDual
import com.nuvio.tv.ui.screens.sportslive.dual.SportsLiveDualSessions
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLivePip
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

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
    isPlaying: () -> Boolean = { false }
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
    BehindLiveIndicator(liveWindow = liveWindow, engine = engine, covered = playerOverlayVisible || panelOpen)
    val panel by viewModel.panel.collectAsState()
    val openDual = LocalOpenSportsLiveDual.current

    // The last banner, kept on screen while it fades out.
    var lastBanner by remember { mutableStateOf<SportsLiveZapBanner?>(null) }
    LaunchedEffect(banner) { banner?.let { lastBanner = it } }
    AnimatedVisibility(
        visible = banner != null,
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(250)),
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 32.dp)
            .zIndex(3f)
    ) {
        (banner ?: lastBanner)?.let { ZapBanner(it) }
    }

    // "Ended while you're watching" (#60): over the video while nothing else is up; Right moves on.
    val fullTime by viewModel.fullTime.collectAsState()
    var lastFullTime by remember { mutableStateOf<FullTimeToast?>(null) }
    LaunchedEffect(fullTime) { fullTime?.let { lastFullTime = it } }
    val fullTimeShown = fullTime != null && banner == null && !panelOpen && !playerOverlayVisible
    // Claimed and timed only once on screen; Right moves on only while it is.
    DisposableEffect(fullTimeShown, viewModel) {
        viewModel.onFullTimeVisible(fullTimeShown)
        onDispose { viewModel.onFullTimeVisible(false) }
    }
    AnimatedVisibility(
        visible = fullTimeShown,
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(250)),
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 32.dp)
            .zIndex(3f)
    ) {
        (fullTime ?: lastFullTime)?.let { FullTimeToastCard(it) }
    }

    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(panelOpen) {
        if (wasOpen && !panelOpen) runCatching { playerFocusRequester.requestFocus() }
        wasOpen = panelOpen
    }

    // #83: the focused row's muted preview, in its own light player, only while the panel is open.
    val previewViewModel: SportsLivePanelPreviewViewModel = hiltViewModel()
    val context = LocalContext.current
    val previewPlayer = remember(previewViewModel) {
        SportsLivePreviewPlayer(
            context,
            previewViewModel::onFirstFrame,
            previewViewModel::onError,
            previewViewModel::onDecoderLimit,
            lowBitrate = true
        )
    }
    // Behind Home, another app or the screensaver (paused): the preview stops and nothing plays meanwhile.
    DisposableEffect(lifecycleOwner, previewPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    previewPlayer.suspended = false
                    previewViewModel.onForeground(true)
                }
                Lifecycle.Event.ON_PAUSE -> {
                    previewPlayer.suspended = true
                    previewViewModel.onForeground(false)
                    previewPlayer.release()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(panelOpen) { previewViewModel.onPanelOpen(panelOpen) }
    val previewState by previewViewModel.state.collectAsState()
    val previewPlaying = previewState as? SportsLivePreviewState.Playing
    LaunchedEffect(previewPlaying?.item?.previewKey, previewPlaying?.stream) {
        if (previewPlaying == null) previewPlayer.release() else previewPlayer.play(previewPlaying.item, previewPlaying.stream)
    }
    DisposableEffect(previewPlayer) {
        onDispose {
            previewViewModel.onPanelOpen(false)
            previewPlayer.release()
        }
    }

    AnimatedVisibility(
        visible = panelOpen,
        enter = fadeIn(tween(120)),
        exit = fadeOut(tween(120)),
        modifier = Modifier
            .align(Alignment.CenterStart)
            .zIndex(3.1f)
    ) {
        // #140/#145: a strong dark fade behind the panel, so its rows read over bright video; clear soon past its edge.
        Box(modifier = Modifier.fillMaxHeight().width(PANEL_WIDTH + PANEL_FADE).background(PANEL_SCRIM))
    }
    // Hold OK on a row (#140): the V2 action menu, Watch both first.
    var menu by remember { mutableStateOf<SportsLivePanelMenu?>(null) }
    val openGame = LocalOpenSportsLiveGame.current
    LaunchedEffect(panelOpen) { if (!panelOpen) menu = null }
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
                    previewViewModel.onPick()
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
                onPreviewFocus = { item -> previewViewModel.onFocus(item, state.currentKey) },
                preview = previewPlaying,
                previewPlayer = previewPlayer,
                onKey = viewModel::onPanelKey
            )
        }
    }

    menu?.let { shown ->
        val item = shown.item
        SportsLiveCardMenu(
            title = item.displayName,
            sides = shown.sides,
            onToggleFollow = { side ->
                menu = null
                viewModel.onPanelToggleFollow(item, side)
            },
            onDetails = null,
            onDismiss = { menu = null },
            // Watch both: dual screen right away, the game playing beside the one held.
            onWatchWith = if (shown.watchBoth && openDual != null) {
                {
                    menu = null
                    viewModel.dualSessionWith(item)?.let { session ->
                        previewViewModel.onPick()
                        viewModel.closePanel()
                        SportsLiveDualSessions.start(session)
                        openDual.invoke()
                    }
                }
            } else {
                null
            },
            watchWithText = R.string.sports_live_watch_both,
            // OK opens this Just ended game's box score: its postgame stream stays here (#140).
            onWatchPostgame = if (shown.postgame) {
                {
                    menu = null
                    previewViewModel.onPick()
                    viewModel.onPanelPick(item)
                }
            } else {
                null
            },
            onChooseStream = if (shown.chooseStream) {
                {
                    menu = null
                    previewViewModel.onPick()
                    viewModel.onPanelChooseStream(item)
                }
            } else {
                null
            },
            onGameStats = if (shown.game != null && openGame != null) {
                {
                    menu = null
                    viewModel.panelGameRequest(shown)?.let { request ->
                        previewViewModel.onPick()
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

/** "Full time · 2–1" (no score under Hide until focused), then "Stay for postgame · Next live game →". */
@Composable
private fun FullTimeToastCard(toast: FullTimeToast) {
    val strings = FullTimeToastStrings(
        title = stringResource(R.string.sports_live_full_time),
        withScore = stringResource(R.string.sports_live_full_time_score),
        score = stringResource(R.string.sports_live_chip_score)
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .widthIn(max = 720.dp)
            .clip(RoundedCornerShape(NuvioTheme.radii.xl))
            .background(NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.92f))
            .padding(horizontal = 28.dp, vertical = 14.dp)
    ) {
        Text(
            text = SportsLiveFullTimeToasts.text(toast, strings),
            style = MaterialTheme.typography.titleLarge,
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 1
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.sports_live_full_time_actions),
            style = MaterialTheme.typography.labelLarge,
            color = NuvioTheme.extendedColors.textSecondary,
            maxLines = 1
        )
    }
}

@Composable
private fun BoxScope.BehindLiveIndicator(
    liveWindow: () -> LiveWindow?,
    engine: () -> InternalPlayerEngine,
    covered: Boolean
) {
    var window by remember { mutableStateOf<LiveWindow?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            window = liveWindow()
            delay(500)
        }
    }
    val shown = SportsLiveRewind.indicatorBehindMs(
        hasSession = true, engine = engine(), window = window, covered = covered
    ) ?: return
    Text(
        text = stringResource(R.string.sports_live_rewind_behind, SportsLiveRewind.formatBehind(shown)),
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
 * The zap banner (#76): both logos around the matchup (or the channel's name), the score chip under
 * the Scores setting, "league · source", "4 of 13", the next game and, once arrived, "▼ All games".
 */
@Composable
private fun ZapBanner(banner: SportsLiveZapBanner) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .widthIn(max = 720.dp)
            .clip(RoundedCornerShape(NuvioTheme.radii.xl))
            .background(NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.92f))
            .padding(horizontal = 28.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Logos only when both teams have one and both loaded (#130); never letters.
            val logos = rememberTeamLogoPair(banner.art)
            if (logos != null) TeamLogoSlot(logos.first, logos.shown, 36.dp)
            Text(
                text = if (banner.switching) {
                    stringResource(R.string.sports_live_zap_switching, banner.name)
                } else {
                    banner.name
                },
                style = MaterialTheme.typography.titleLarge,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (logos != null) TeamLogoSlot(logos.second, logos.shown, 36.dp)
        }
        banner.chip?.let { chip ->
            Spacer(modifier = Modifier.height(4.dp))
            SportsLiveScoreChip(chip)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = banner.detail,
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.extendedColors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                text = stringResource(R.string.sports_live_zap_position, banner.position, banner.total),
                style = MaterialTheme.typography.labelLarge,
                color = NuvioTheme.colors.Primary
            )
            banner.next?.let { next ->
                Text(
                    text = stringResource(R.string.sports_live_zap_next, next),
                    style = MaterialTheme.typography.labelLarge,
                    color = NuvioTheme.extendedColors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
            if (!banner.switching) {
                Text(
                    text = stringResource(R.string.sports_live_zap_down_hint),
                    style = MaterialTheme.typography.labelLarge,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * The games live now, from the left edge (#140): a slim column of one-line rows over a soft fade, so the game
 * stays visible beside it. Under one compact filter (#68: on it Left and Right move along the options), the
 * game playing is pinned as **Now playing**, where focus opens; below it the lineup like a cable guide, from
 * the game after it (down / CH-) round to the one before it (CH+), with "From the top" where it wraps
 * ([SportsLiveZapPanelState.guide]). Left or Right closes the panel (Back is handled by the player), and
 * Up/Down stay inside it. OK on a game zaps to it; Hold OK opens its menu ([onLongPick]).
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
    /** The focused row's game for the corner preview (#83); null when no row has focus. */
    onPreviewFocus: (SportsLiveItem?) -> Unit,
    /** The corner preview loaded in [previewPlayer], null when none plays. */
    preview: SportsLivePreviewState.Playing?,
    previewPlayer: SportsLivePreviewPlayer,
    /** Any key pressed inside the panel (Hide until focused reveals only after one). */
    onKey: () -> Unit
) {
    // #140: the list starts at its top (the game after the one playing), never scrolled to centre one.
    val listState = rememberLazyListState()
    val nowFocusRequester = remember { FocusRequester() }
    val firstRowFocusRequester = remember { FocusRequester() }
    val emptyFocusRequester = remember { FocusRequester() }
    val filterFocusRequester = remember { FocusRequester() }
    val guide = state.guide
    val nowRow = guide.nowPlaying ?: state.nowPlayingRow
    val firstRowKey = guide.rows.firstOrNull()?.item?.streamKey
    // While the filter has focus, an option that empties (or fills) the list leaves focus on it.
    var filterFocused by remember { mutableStateOf(false) }
    // The game row with focus, by stream key (null on the filter).
    var focusedKey by remember { mutableStateOf<String?>(null) }
    val currentOnPreviewFocus by rememberUpdatedState(onPreviewFocus)
    // The preview follows the focused row; none while the filter has focus (#83).
    val focusedItem = (state.items + listOfNotNull(nowRow?.item)).firstOrNull { it.streamKey == focusedKey }
    LaunchedEffect(focusedItem?.streamKey) { currentOnPreviewFocus(focusedItem) }
    // One option (All) filters nothing: no filter then.
    val filterShown = state.chips.size > 1

    // #140: focus opens on Now playing; on the first game when the game playing is unknown, else the empty line.
    LaunchedEffect(nowRow == null, guide.rows.isEmpty()) {
        if (filterFocused) return@LaunchedEffect
        runCatching {
            when {
                nowRow != null -> nowFocusRequester.requestFocus()
                guide.rows.isNotEmpty() -> firstRowFocusRequester.requestFocus()
                else -> emptyFocusRequester.requestFocus()
            }
        }
    }
    // I3: rows regrouped by a score or a tick keep focus on the same game (by key) and keep it in view;
    // the list scrolls only when that row left the view, and only to it.
    LaunchedEffect(guide) {
        val index = guide.indexOf(focusedKey ?: return@LaunchedEffect)
        if (index < 0) return@LaunchedEffect
        withFrameNanos { }
        if (!listState.isFullyVisible(index)) runCatching { listState.centerOn(index) }
    }
    val boxScoreHint = stringResource(R.string.sports_live_panel_box_score_hint)
    val rowFocus: (SportsLiveItem) -> Unit = { item ->
        focusedKey = item.streamKey
        onRowFocus(item.streamKey)
    }

    // Left or Right closes the panel on release, only when its press reached the panel: the filter uses its own.
    var closeArmed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(PANEL_WIDTH)
            .focusProperties { onExit = { cancelFocusChange() } }
            .onPreviewKeyEvent {
                if (it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) onKey()
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
        Column(modifier = Modifier.padding(horizontal = PANEL_PADDING, vertical = NuvioTheme.spacing.lg)) {
            Row(
                modifier = Modifier.padding(horizontal = PANEL_ROW_ROOM),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
            ) {
                // One line (#140): no info card under it.
                Text(
                    text = stringResource(R.string.sports_live_zap_panel_title),
                    style = SportsLiveTokens.style(SportsLiveTokens.Type.PanelTitle),
                    color = SportsLiveTokens.Colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).align(Alignment.CenterVertically)
                )
                // #83: the corner holds only the focused game's preview video, inside the panel; nothing while none plays.
                val cornerPreview = preview?.takeIf { it.item.streamKey == focusedKey }
                if (panelPreviewCornerShown(cornerPreview != null)) {
                    PanelPreviewCorner(preview = cornerPreview, player = previewPlayer)
                }
            }
            if (filterShown) {
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
                // #146: All is My sports' games while it scopes the panel; All sports lifts it.
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
                    SportsLiveFilterOption(chip, sportsLiveChipText(name, state.counts[chip] ?: 0), gold = chip == SportsLiveUiState.MY_TEAMS_FILTER)
                }
                // #140: one compact control in the V2 focus language (the selection tinted, the cursor outlined).
                SportsLiveFilterControl(
                    options = options,
                    selected = state.chip,
                    onSelect = onChip,
                    onOpenList = {
                        runCatching {
                            if (nowRow != null) nowFocusRequester.requestFocus() else if (guide.rows.isNotEmpty()) firstRowFocusRequester.requestFocus()
                        }
                    },
                    modifier = Modifier
                        .padding(horizontal = PANEL_ROW_ROOM)
                        .onFocusChanged {
                            filterFocused = it.isFocused
                            // No game row is focused while the filter is: none is revealed meanwhile.
                            if (it.isFocused) {
                                focusedKey = null
                                onRowFocus(null)
                            }
                        }
                        .focusRequester(filterFocusRequester)
                )
            }
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
            // #140: the game playing, pinned under the filter. Up goes to the filter, Down to the list's first
            // game (what CH- plays).
            nowRow?.let { row ->
                val item = row.item
                SportsLivePanelLine(
                    row = state.boards[item.streamKey]?.board
                        ?: panelBoards(listOf(row), emptyMap(), null, emptySet(), 0L).single().board,
                    nowPlaying = true,
                    onClick = { onPick(item) },
                    onLongClick = { onLongPick(item) },
                    onFocus = { rowFocus(item) },
                    modifier = Modifier
                        .padding(horizontal = PANEL_ROW_ROOM)
                        .focusRequester(nowFocusRequester)
                        .then(if (filterShown) Modifier.focusProperties { up = filterFocusRequester } else Modifier),
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
            if (guide.rows.isEmpty()) {
                // Nothing else on: Now playing alone, or the empty line when even that is unknown.
                if (nowRow == null) {
                    Text(
                        text = stringResource(R.string.sports_live_zap_panel_empty),
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
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(SportsLiveTokens.Size.PanelLineGap),
                    // Room for the rows' focus ring and lift, so neither is clipped.
                    contentPadding = PaddingValues(horizontal = PANEL_ROW_ROOM, vertical = NuvioTheme.spacing.xs),
                    modifier = Modifier.weight(1f)
                ) {
                    guide.entries.forEach { entry ->
                        when (entry) {
                            is PanelGuideEntry.Header -> item(key = entry.key) { SportsLivePanelHeader(entry.section, entry.count) }
                            PanelGuideEntry.FromTheTop -> item(key = entry.key) { FromTheTopDivider() }
                            is PanelGuideEntry.Game -> {
                                val row = entry.row
                                val item = row.item
                                val first = item.streamKey == firstRowKey
                                item(key = entry.key) {
                                    SportsLivePanelLine(
                                        row = state.boards[item.streamKey]?.board
                                            ?: panelBoards(listOf(row), emptyMap(), null, emptySet(), 0L).single().board,
                                        nowPlaying = false,
                                        onClick = { onPick(item) },
                                        onLongClick = { onLongPick(item) },
                                        onFocus = { rowFocus(item) },
                                        okHint = boxScoreHint.takeIf { item.streamKey in state.boxScores },
                                        // I3: Up from the first game reaches Now playing (else the filter), never past it.
                                        modifier = if (first) {
                                            Modifier.focusRequester(firstRowFocusRequester).focusProperties {
                                                if (nowRow != null) {
                                                    up = nowFocusRequester
                                                } else if (filterShown) {
                                                    up = filterFocusRequester
                                                }
                                            }
                                        } else {
                                            Modifier
                                        },
                                        menuOpen = menuOpen
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // #140: the hint in place of the old "Watch with another game" button.
            Text(
                text = stringResource(R.string.sports_live_panel_footer_hint),
                style = SportsLiveTokens.style(SportsLiveTokens.Type.Tag),
                color = SportsLiveTokens.Colors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = PANEL_ROW_ROOM, top = NuvioTheme.spacing.xs)
            )
        }
    }
}

/**
 * The panel's top-right corner (#83, V2 P3): the focused game's muted 16:9 preview, shown only while one
 * loads or plays ([panelPreviewCornerShown]); no logos or time card in its place. Never focusable.
 */
@Composable
private fun PanelPreviewCorner(preview: SportsLivePreviewState.Playing?, player: SportsLivePreviewPlayer) {
    Box(
        modifier = Modifier
            .width(PANEL_PREVIEW_WIDTH)
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(NuvioTheme.radii.md))
            .background(if (preview?.rendering == true) NuvioTheme.colors.PosterFallback else Color.Transparent)
    ) {
        // The video is laid out while the stream loads, so its first frame can arrive.
        if (preview != null) SportsLivePreviewVideo(player, overVideo = true)
        if (preview?.rendering == true) {
            SportsLivePreviewLabel(modifier = Modifier.align(Alignment.TopStart).padding(NuvioTheme.spacing.xs))
        }
    }
}

/** "From the top" (#140): a thin rule and its words where the cable-guide lineup wraps to its first game. */
@Composable
private fun FromTheTopDivider() {
    Row(
        modifier = Modifier.padding(start = 8.dp, end = 6.dp, top = 4.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.sports_live_panel_from_the_top),
            style = SportsLiveTokens.style(SportsLiveTokens.Type.Tag),
            color = SportsLiveTokens.Colors.TextMuted,
            maxLines = 1
        )
        Box(modifier = Modifier.weight(1f).height(1.dp).background(SportsLiveTokens.Colors.TextMuted.copy(alpha = 0.4f)))
    }
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

/** The corner preview: 16:9 at this width (#83), beside the title, inside the panel's width (F12). */
private val PANEL_PREVIEW_WIDTH = 150.dp

/** Above and below the rule under the pinned Now playing row (#145). */
private val NOW_PLAYING_GAP = 6.dp

/** How far past the panel its fade runs out (#140, #145). */
private val PANEL_FADE = 80.dp

/**
 * Behind the panel (#145): about 85 % black at the left edge, still about 75 % at the panel's edge (rows stay
 * readable over a yellow or white scene), then clear over the [PANEL_FADE] past it.
 */
private val PANEL_SCRIM = Brush.horizontalGradient(
    0f to Color.Black.copy(alpha = 0.85f),
    0.75f to Color.Black.copy(alpha = 0.75f),
    1f to Color.Transparent
)
