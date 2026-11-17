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
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
import com.nuvio.tv.ui.screens.sportslive.SportsLiveZapViewModel
import com.nuvio.tv.ui.screens.sportslive.ZapPanelGroup
import com.nuvio.tv.ui.screens.sportslive.ZapPanelRow
import com.nuvio.tv.ui.screens.sportslive.kickoffText
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
            if (event == Lifecycle.Event.ON_STOP) viewModel.closePanel()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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

    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(panelOpen) {
        if (wasOpen && !panelOpen) runCatching { playerFocusRequester.requestFocus() }
        wasOpen = panelOpen
    }

    AnimatedVisibility(
        visible = panelOpen,
        enter = fadeIn(tween(120)),
        exit = fadeOut(tween(120)),
        modifier = Modifier.zIndex(3.1f)
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
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
                onPick = viewModel::onPanelPick,
                // Long-press OK: dual screen right away, the game playing beside the one held.
                onLongPick = if (canWatchWith) {
                    { item ->
                        viewModel.dualSessionWith(item)?.let { session ->
                            viewModel.closePanel()
                            SportsLiveDualSessions.start(session)
                            openDual?.invoke()
                        }
                    }
                } else {
                    null
                },
                onClose = { viewModel.closePanel() },
                onRowFocus = viewModel::onPanelRowFocus,
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
            onPick = { second ->
                dualPick = null
                SportsLiveDualSessions.start(pick.session(second))
                openDual?.invoke()
            },
            onDismiss = { dualPick = null }
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

@Composable
private fun ZapBanner(banner: SportsLiveZapBanner) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(NuvioTheme.radii.xl))
            .background(NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.92f))
            .padding(horizontal = 28.dp, vertical = 14.dp)
    ) {
        Text(
            text = if (banner.switching) {
                stringResource(R.string.sports_live_zap_switching, banner.name)
            } else {
                banner.name
            },
            style = MaterialTheme.typography.titleLarge,
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = banner.detail,
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.extendedColors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.sports_live_zap_position, banner.position, banner.total),
            style = MaterialTheme.typography.labelLarge,
            color = NuvioTheme.colors.Primary
        )
    }
}

/**
 * The games live now, from the left edge. Focus opens on the game playing; Left or Right closes the
 * panel (Back is handled by the player), and Up/Down stay inside it. OK on a game zaps to it; a
 * long press calls [onLongPick] instead, when given.
 */
@Composable
private fun LiveGamesPanel(
    state: SportsLiveZapPanelState,
    onPick: (SportsLiveItem) -> Unit,
    /** Long-press OK on a game (dual screen with the game playing); null leaves long presses alone. */
    onLongPick: ((SportsLiveItem) -> Unit)?,
    onClose: () -> Unit,
    /** "Watch with another game" for the game playing, above the list; null hides it. */
    onWatchWith: (() -> Unit)?,
    /** A game row took focus (Hide until focused reveals its score after a second). */
    onRowFocus: (String?) -> Unit,
    /** Any key pressed inside the panel (Hide until focused reveals only after one). */
    onKey: () -> Unit
) {
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val emptyFocusRequester = remember { FocusRequester() }
    // Focus starts on the game playing, found by key so a regroup (scores arriving) never moves it to
    // another row; the first row when the game playing is not listed.
    val startKey = state.items.firstOrNull { it.streamKey == state.currentKey }?.streamKey ?: state.items.firstOrNull()?.streamKey

    LaunchedEffect(state.items.isEmpty()) {
        runCatching {
            if (state.items.isEmpty()) {
                emptyFocusRequester.requestFocus()
            } else {
                // The list also holds a header above each group.
                val startIndex = state.items.indexOfFirst { it.streamKey == startKey }.coerceAtLeast(0)
                val headersAbove = state.rows.take(startIndex + 1).map { it.group }.distinct().size
                listState.scrollToItem((startIndex + headersAbove - 2).coerceAtLeast(0))
                delay(32)
                focusRequester.requestFocus()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(340.dp)
            .clip(RoundedCornerShape(topEnd = NuvioTheme.spacing.lg, bottomEnd = NuvioTheme.spacing.lg))
            .background(NuvioTheme.colors.BackgroundElevated)
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
        Column(modifier = Modifier.padding(NuvioTheme.spacing.xl)) {
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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = NuvioTheme.spacing.xs)
                )
            }
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
            if (onWatchWith != null) {
                PanelCard(onClick = onWatchWith, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.sports_live_dual_watch_with),
                        style = MaterialTheme.typography.titleMedium,
                        color = NuvioTheme.colors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                    )
                }
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.sm))
            }
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
                    contentPadding = PaddingValues(vertical = NuvioTheme.spacing.xs),
                    modifier = Modifier.fillMaxHeight()
                ) {
                    state.rows.forEachIndexed { index, row ->
                        if (index == 0 || state.rows[index - 1].group != row.group) {
                            item(key = "group_${row.group}") { GroupHeader(row.group) }
                        }
                        val item = row.item
                        item(key = item.streamKey) {
                            LiveGameItem(
                                row = row,
                                isCurrent = item.streamKey == state.currentKey,
                                modifier = if (item.streamKey == startKey) {
                                    Modifier.focusRequester(focusRequester)
                                } else {
                                    Modifier
                                },
                                onClick = { onPick(item) },
                                onLongClick = onLongPick?.let { { it(item) } },
                                onFocus = { onRowFocus(item.streamKey) }
                            )
                        }
                    }
                }
            }
        }
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

@Composable
private fun LiveGameItem(
    row: ZapPanelRow,
    isCurrent: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onFocus: () -> Unit
) {
    val item = row.item
    PanelCard(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocus() }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Box(modifier = Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                if (isCurrent) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = stringResource(R.string.cd_current),
                        tint = NuvioTheme.colors.TextPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.meta.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // A game starting soon leads with its local kickoff.
                val detail = "${item.sport ?: item.catalogName} · ${item.addonName}"
                val kickoff = row.art?.let { kickoffText(it) }
                Text(
                    text = if (kickoff != null) stringResource(R.string.sports_live_art_footer, kickoff, detail) else detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.extendedColors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                row.chip?.let { SportsLiveScoreChip(chip = it, modifier = Modifier.padding(top = NuvioTheme.spacing.xxs)) }
            }
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
