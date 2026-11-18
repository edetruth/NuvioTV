@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player

import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import com.nuvio.tv.R
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.ui.screens.sportslive.LiveWindow
import com.nuvio.tv.ui.screens.sportslive.SportsLiveRewind
import com.nuvio.tv.ui.screens.sportslive.SportsLiveZapBanner
import com.nuvio.tv.ui.screens.sportslive.SportsLiveZapPanelState
import com.nuvio.tv.ui.screens.sportslive.SportsLiveScoreChip
import com.nuvio.tv.ui.screens.sportslive.SportsLiveUiState
import com.nuvio.tv.ui.screens.sportslive.SportsLiveChipCursor
import com.nuvio.tv.ui.screens.sportslive.SportsLiveChipIcon
import com.nuvio.tv.ui.screens.sportslive.SportsLiveChipIconView
import com.nuvio.tv.ui.screens.sportslive.sportsLiveChipIconOf
import com.nuvio.tv.ui.screens.sportslive.sportsLiveChipText
import com.nuvio.tv.ui.screens.sportslive.FOLLOWED_STAR_COLOR
import com.nuvio.tv.ui.screens.sportslive.SportsLiveGameArt
import com.nuvio.tv.ui.screens.sportslive.SportsLiveScoreboardRow
import com.nuvio.tv.ui.screens.sportslive.ZapPanelBoard
import com.nuvio.tv.ui.screens.sportslive.panelBoards
import com.nuvio.tv.ui.screens.sportslive.preview.SportsLivePanelPreviewViewModel
import com.nuvio.tv.ui.screens.sportslive.preview.SportsLivePreviewLabel
import com.nuvio.tv.ui.screens.sportslive.preview.SportsLivePreviewPlayer
import com.nuvio.tv.ui.screens.sportslive.preview.SportsLivePreviewState
import com.nuvio.tv.ui.screens.sportslive.preview.SportsLivePreviewVideo
import com.nuvio.tv.ui.screens.sportslive.preview.previewKey
import coil3.compose.AsyncImage
import com.nuvio.tv.ui.screens.sportslive.SportsLiveZapViewModel
import com.nuvio.tv.ui.screens.sportslive.ZapPanelGroup
import com.nuvio.tv.ui.screens.sportslive.ZapPanelRow
import com.nuvio.tv.ui.screens.sportslive.ZapPanelStop
import com.nuvio.tv.ui.screens.sportslive.panelListIndex
import com.nuvio.tv.ui.screens.sportslive.zapPanelUpFrom
import com.nuvio.tv.core.sportslive.FullTimeToast
import com.nuvio.tv.core.sportslive.FullTimeToastStrings
import com.nuvio.tv.core.sportslive.SportsLiveFullTimeToasts
import com.nuvio.tv.ui.screens.sportslive.TeamLogoSlot
import com.nuvio.tv.ui.screens.sportslive.rememberTeamLogoPair
import com.nuvio.tv.ui.screens.sportslive.dual.LocalOpenSportsLiveDual
import com.nuvio.tv.ui.screens.sportslive.dual.SportsLiveDualPick
import com.nuvio.tv.ui.screens.sportslive.dual.SportsLiveDualPickerDialog
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
    playerOverlayVisible: Boolean
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
    var dualPick by remember { mutableStateOf<SportsLiveDualPick?>(null) }

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
        modifier = Modifier.zIndex(3.1f)
    ) {
        // #83: the video keeps playing visibly on the right, behind a left-to-right scrim.
        Box(modifier = Modifier.fillMaxSize().background(PANEL_SCRIM))
    }
    AnimatedVisibility(
        visible = panelOpen,
        enter = slideInHorizontally(tween(220)) { -it },
        exit = slideOutHorizontally(tween(220)) { -it },
        modifier = Modifier
            .align(Alignment.CenterStart)
            .zIndex(3.2f)
    ) {
        panel?.let { state ->
            // Depends on the session only, not on score updates.
            val canWatchWith = remember(state.currentKey, openDual) { openDual != null && viewModel.dualPick() != null }
            LiveGamesPanel(
                state = state,
                onPick = { item ->
                    previewViewModel.onPick()
                    viewModel.onPanelPick(item)
                },
                // Long-press OK: dual screen right away, the game playing beside the one held.
                onLongPick = if (canWatchWith) {
                    { item ->
                        viewModel.dualSessionWith(item)?.let { session ->
                            previewViewModel.onPick()
                            viewModel.closePanel()
                            SportsLiveDualSessions.start(session)
                            openDual?.invoke()
                        }
                    }
                } else {
                    null
                },
                onClose = { viewModel.closePanel() },
                onChip = viewModel::onPanelChip,
                onRowFocus = viewModel::onPanelRowFocus,
                onPreviewFocus = { item -> previewViewModel.onFocus(item, state.currentKey) },
                preview = previewPlaying,
                previewPlayer = previewPlayer,
                onKey = viewModel::onPanelKey,
                onWatchWith = if (canWatchWith) {
                    {
                        dualPick = viewModel.dualPick()
                        viewModel.closePanel()
                    }
                } else {
                    null
                }
            )
        }
    }

    // The second-game picker; the pick leaves this player for dual screen.
    dualPick?.let { pick ->
        SportsLiveDualPickerDialog(
            candidates = pick.candidates,
            rows = pick.rows,
            onPick = { second ->
                dualPick = null
                SportsLiveDualSessions.start(pick.session(second))
                openDual?.invoke()
            },
            onDismiss = { dualPick = null }
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
 * The games live now, from the left edge. Focus opens on the game playing; Left or Right closes the
 * panel (Back is handled by the player), and Up/Down stay inside it. OK on a game zaps to it; a
 * long press calls [onLongPick] instead, when given. Above the games, the filter chips (#68): on
 * them Left and Right pick a chip instead of closing the panel.
 */
@Composable
private fun LiveGamesPanel(
    state: SportsLiveZapPanelState,
    onPick: (SportsLiveItem) -> Unit,
    /** Long-press OK on a game (dual screen with the game playing); null leaves long presses alone. */
    onLongPick: ((SportsLiveItem) -> Unit)?,
    onClose: () -> Unit,
    /** A filter chip was picked (null is All). */
    onChip: (String?) -> Unit,
    /** "Watch with another game" for the game playing, above the list; null hides it. */
    onWatchWith: (() -> Unit)?,
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
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val emptyFocusRequester = remember { FocusRequester() }
    val chipsFocusRequester = remember { FocusRequester() }
    // Focus starts on the game playing, found by key so a regroup (scores arriving) never moves it to
    // another row; the first row when the game playing is not listed.
    val startKey = state.items.firstOrNull { it.streamKey == state.currentKey }?.streamKey ?: state.items.firstOrNull()?.streamKey
    // While the chips have focus, a chip that empties (or fills) the list leaves focus on them.
    var chipsFocused by remember { mutableStateOf(false) }
    // The game row with focus, by stream key (null on the chips or the button above them).
    var focusedKey by remember { mutableStateOf<String?>(null) }
    val currentOnPreviewFocus by rememberUpdatedState(onPreviewFocus)
    // The preview follows the focused row; none while the chips or the button have focus (#83).
    val focusedItem = state.items.firstOrNull { it.streamKey == focusedKey }
    LaunchedEffect(focusedItem?.streamKey) { currentOnPreviewFocus(focusedItem) }
    val currentRows by rememberUpdatedState(state.rows)

    LaunchedEffect(state.items.isEmpty()) {
        if (chipsFocused) return@LaunchedEffect
        runCatching {
            if (state.items.isEmpty()) {
                emptyFocusRequester.requestFocus()
            } else {
                // I3: the game playing is scrolled to the middle of the list before focus lands on it.
                listState.centerOn(panelListIndex(currentRows, startKey).coerceAtLeast(0))
                focusRequester.requestFocus()
            }
        }
    }
    // I3: rows regrouped by a score or a tick keep focus on the same game (by key) and keep it in view;
    // the list scrolls only when that row left the view, and only to it.
    LaunchedEffect(state.rows) {
        val index = panelListIndex(state.rows, focusedKey ?: return@LaunchedEffect)
        if (index < 0) return@LaunchedEffect
        withFrameNanos { }
        if (!listState.isFullyVisible(index)) runCatching { listState.centerOn(index) }
    }

    Box(
        modifier = Modifier
            .fillMaxHeight()
            // #83: Scoreboard-wide rows; no opaque backing, the scrim behind keeps the video visible.
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
                        if (event.nativeKeyEvent.action == KeyEvent.ACTION_UP) onClose()
                        true
                    }
                    else -> false
                }
            }
    ) {
        // F12: rows 336 dp wide, the preview corner inside that width.
        Column(modifier = Modifier.padding(horizontal = PANEL_PADDING, vertical = NuvioTheme.spacing.xl)) {
            Row(
                modifier = Modifier.padding(horizontal = PANEL_ROW_ROOM),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.sports_live_zap_panel_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = NuvioTheme.colors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (onLongPick != null) {
                        Text(
                            text = stringResource(R.string.sports_live_dual_hold_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.extendedColors.textSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = NuvioTheme.spacing.xs)
                        )
                    }
                }
                // #83: the focused game (the one playing when none is) in the top-right corner.
                val cornerKey = focusedKey ?: state.currentKey
                PanelPreviewCorner(
                    row = state.rows.firstOrNull { it.item.streamKey == cornerKey },
                    preview = preview?.takeIf { it.item.streamKey == focusedKey },
                    player = previewPlayer
                )
            }
            // F12: full width under the title and preview, so its label never truncates.
            if (onWatchWith != null) {
                PanelCard(
                    onClick = onWatchWith,
                    modifier = Modifier.padding(top = NuvioTheme.spacing.md, start = PANEL_ROW_ROOM, end = PANEL_ROW_ROOM).fillMaxWidth().onFocusChanged {
                        if (it.isFocused) {
                            focusedKey = null
                            onRowFocus(null)
                        }
                    }
                ) {
                    Text(
                        text = stringResource(R.string.sports_live_dual_watch_with),
                        style = MaterialTheme.typography.titleMedium,
                        color = NuvioTheme.colors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
            PanelChipRow(
                chips = state.chips,
                counts = state.counts,
                selected = state.chip,
                onChip = onChip,
                onFocusChanged = { focused ->
                    chipsFocused = focused
                    // No game row is focused while the chips are: none is revealed meanwhile.
                    if (focused) {
                        focusedKey = null
                        onRowFocus(null)
                    }
                },
                focusRequester = chipsFocusRequester
            )
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
            if (state.items.isEmpty()) {
                Text(
                    text = stringResource(R.string.sports_live_zap_panel_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuvioTheme.extendedColors.textSecondary,
                    modifier = Modifier
                        .focusRequester(emptyFocusRequester)
                        .focusable()
                )
            } else {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                    // Room for the rows' focus ring and lift, so neither is clipped.
                    contentPadding = PaddingValues(horizontal = PANEL_ROW_ROOM, vertical = NuvioTheme.spacing.xs),
                    modifier = Modifier.fillMaxHeight()
                ) {
                    state.rows.forEachIndexed { index, row ->
                        if (index == 0 || state.rows[index - 1].group != row.group) {
                            item(key = "group_${row.group}") { GroupHeader(row.group) }
                        }
                        val item = row.item
                        // I3: Up from the first game reaches the chips, never past them.
                        val upToChips = zapPanelUpFrom(index) == ZapPanelStop.CHIPS
                        item(key = item.streamKey) {
                            LiveGameItem(
                                row = row,
                                board = state.boards[item.streamKey],
                                isCurrent = item.streamKey == state.currentKey,
                                modifier = (if (item.streamKey == startKey) Modifier.focusRequester(focusRequester) else Modifier)
                                    .then(if (upToChips) Modifier.focusProperties { up = chipsFocusRequester } else Modifier),
                                onClick = { onPick(item) },
                                onLongClick = onLongPick?.let { { it(item) } },
                                onFocus = {
                                    focusedKey = item.streamKey
                                    onRowFocus(item.streamKey)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The panel's filter chips (#68): ★ My teams, All, then each sport, each with its game count (#79).
 * One focus stop: Left and Right move the highlight (the focus ring) only, OK picks the highlighted
 * chip (#79: focus never changes the filter), and Down moves into the games.
 */
@Composable
private fun PanelChipRow(
    chips: List<String?>,
    counts: Map<String?, Int>,
    selected: String?,
    onChip: (String?) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    focusRequester: FocusRequester
) {
    var focused by remember { mutableStateOf(false) }
    var highlighted by remember { mutableStateOf(selected) }
    val cursor = SportsLiveChipCursor(chips, selected, if (highlighted in chips) highlighted else selected)
    Row(
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged {
                // The highlight starts on the selected chip each time the row takes focus.
                if (it.isFocused && !focused) highlighted = selected
                focused = it.isFocused
                onFocusChanged(it.isFocused)
            }
            .onKeyEvent { event ->
                val native = event.nativeKeyEvent
                when (native.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        // Both the press and the release are used, so neither reaches the panel's close.
                        if (native.action == KeyEvent.ACTION_DOWN) {
                            highlighted = cursor.move(if (native.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1).highlighted
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (native.action == KeyEvent.ACTION_DOWN && native.repeatCount == 0) {
                            val picked = cursor.ok().selected
                            if (picked != selected) onChip(picked)
                        }
                        true
                    }
                    else -> false
                }
            }
            .focusRequester(focusRequester)
            .focusable()
            .horizontalScroll(rememberScrollState())
            .padding(NuvioTheme.spacing.xs)
    ) {
        chips.forEach { chip ->
            PanelChip(
                name = when (chip) {
                    null -> stringResource(R.string.sports_live_filter_all)
                    SportsLiveUiState.MY_TEAMS_FILTER -> stringResource(R.string.sports_live_filter_my_teams)
                    else -> com.nuvio.tv.core.sportslive.SportsLiveDisplayText.label(chip) ?: chip
                },
                count = counts[chip] ?: 0,
                icon = sportsLiveChipIconOf(chip),
                selected = chip == selected,
                highlighted = focused && chip == cursor.highlighted
            )
        }
    }
}

@Composable
private fun PanelChip(name: String, count: Int, icon: SportsLiveChipIcon, selected: Boolean, highlighted: Boolean) {
    // M6: the highlighted chip (else the selected one) scrolls into view as Left and Right move along a
    // long row, and again when the row takes focus, once laid out.
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(selected, highlighted) {
        if (highlighted || selected) {
            withFrameNanos { }
            runCatching { bringIntoView.bringIntoView() }
        }
    }
    val shape = RoundedCornerShape(NuvioTheme.radii.xl)
    val color = if (selected) NuvioTheme.colors.OnPrimary else NuvioTheme.extendedColors.textSecondary
    // #79: the selected chip is filled; the highlighted one (where OK lands) carries the focus ring.
    Row(
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .bringIntoViewRequester(bringIntoView)
            .clip(shape)
            .background(if (selected) NuvioTheme.colors.Primary else NuvioTheme.colors.BackgroundCard)
            .then(if (highlighted) Modifier.border(NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape) else Modifier)
            // M6: compact, so All and three sports fit the panel; a longer row scrolls.
            .padding(horizontal = NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.xs)
    ) {
        SportsLiveChipIconView(icon, tint = if (icon == SportsLiveChipIcon.MY_TEAMS && !selected) FOLLOWED_STAR_COLOR else color, size = 14)
        Text(
            text = sportsLiveChipText(name, count),
            style = MaterialTheme.typography.labelLarge,
            color = color,
            maxLines = 1
        )
    }
}

/** A small group heading in the live games panel: Live now, Starting soon, More games. */
@Composable
private fun GroupHeader(group: ZapPanelGroup) {
    Text(
        text = stringResource(
            when (group) {
                ZapPanelGroup.LIVE_NOW -> R.string.sports_live_zap_group_live
                ZapPanelGroup.STARTING_SOON -> R.string.sports_live_zap_group_soon
                ZapPanelGroup.OTHER -> R.string.sports_live_zap_group_other
                ZapPanelGroup.JUST_ENDED -> R.string.sports_live_just_ended
            }
        ),
        style = MaterialTheme.typography.labelMedium,
        color = NuvioTheme.extendedColors.textSecondary,
        maxLines = 1,
        modifier = Modifier.padding(top = NuvioTheme.spacing.xs, start = NuvioTheme.spacing.xs)
    )
}

/**
 * A game of the live games panel, drawn as a Scoreboard row (#83): logos, short names, stacked scores,
 * the clock or kickoff on the right and, with a shown live score, the situation line. The game playing
 * is labelled NOW PLAYING and highlighted. A row with no [board] uses one built from [row] alone.
 */
@Composable
private fun LiveGameItem(
    row: ZapPanelRow,
    board: ZapPanelBoard?,
    isCurrent: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onFocus: () -> Unit
) {
    SportsLiveScoreboardRow(
        row = board?.board ?: panelBoards(listOf(row), emptyMap(), null, emptySet(), 0L).single().board,
        selected = isCurrent,
        onClick = onClick,
        onLongClick = onLongClick,
        onFocus = onFocus,
        modifier = modifier,
        nowPlaying = isCurrent,
        shortNames = true
    )
}

/**
 * The panel's top-right corner (#83): the focused game's muted 16:9 preview once its first frame is
 * drawn, otherwise its logos art (or its poster when it names no two teams). Never focusable.
 */
@Composable
private fun PanelPreviewCorner(row: ZapPanelRow?, preview: SportsLivePreviewState.Playing?, player: SportsLivePreviewPlayer) {
    Box(
        modifier = Modifier
            .width(PANEL_PREVIEW_WIDTH)
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(NuvioTheme.radii.md))
            .background(NuvioTheme.colors.PosterFallback)
    ) {
        // The video is laid out while the stream loads, so its first frame can arrive; the art covers it until then.
        if (preview != null) SportsLivePreviewVideo(player, overVideo = true)
        if (preview?.rendering != true && row != null) {
            val teams = row.teams
            if (teams != null) {
                SportsLiveGameArt(art = teams, compact = true, chip = row.chip, modifier = Modifier.fillMaxSize())
            } else {
                AsyncImage(
                    model = row.item.meta.backdropUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        if (preview?.rendering == true) {
            SportsLivePreviewLabel(modifier = Modifier.align(Alignment.TopStart).padding(NuvioTheme.spacing.xs))
        }
    }
}

/**
 * A focusable row of the live games panel. With [onLongClick], the Card's own long press (OK held)
 * calls it and skips the click that the key's release would otherwise fire.
 */
@Composable
private fun PanelCard(
    onClick: () -> Unit,
    modifier: Modifier,
    onLongClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = RoundedCornerShape(NuvioTheme.radii.xl)
            )
        ),
        scale = CardDefaults.scale(focusedScale = 1.01f),
        shape = CardDefaults.shape(shape = RoundedCornerShape(NuvioTheme.radii.xl)),
        content = content
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

/** The live games panel's width at 1080p (F12): Live Now's own rows, 336 dp, inside its padding. */
private val PANEL_WIDTH = 360.dp
private val PANEL_PADDING = 4.dp
private val PANEL_ROW_ROOM = 8.dp

/** The corner preview: 16:9 at this width (#83), beside the title, inside the panel's width (F12). */
private val PANEL_PREVIEW_WIDTH = 150.dp

/** Behind the panel (#83): about 85% black at the left edge, clear by the right, so the video stays visible. */
private val PANEL_SCRIM = Brush.horizontalGradient(
    0f to Color.Black.copy(alpha = 0.85f),
    0.4f to Color.Black.copy(alpha = 0.7f),
    1f to Color.Transparent
)
