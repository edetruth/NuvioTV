package com.nuvio.tv.ui.screens.sportslive.dual

import com.nuvio.tv.core.sportslive.displayName
import android.view.KeyEvent
import com.nuvio.tv.core.sportslive.SportsLiveToastKind
import com.nuvio.tv.core.sportslive.SportsLiveToasts
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import com.nuvio.tv.core.sportslive.scores.ScoreChipStrings
import com.nuvio.tv.core.sportslive.scores.ScoreDetailsStrings
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.ui.screens.sportslive.GameInfoKeyAction
import com.nuvio.tv.ui.screens.sportslive.SportsLiveGameInfoKeys
import com.nuvio.tv.ui.screens.sportslive.SportsLiveGameInfoOverlay
import com.nuvio.tv.ui.screens.sportslive.SportsLiveGameInfoViewModel
import com.nuvio.tv.ui.screens.sportslive.SportsLiveScoresViewModel
import com.nuvio.tv.ui.screens.sportslive.TeamLogoSlot
import com.nuvio.tv.ui.screens.sportslive.TrackLiveScores
import com.nuvio.tv.ui.screens.sportslive.rememberScoreChipStrings
import com.nuvio.tv.ui.screens.sportslive.rememberScoreDetailsStrings
import com.nuvio.tv.ui.screens.sportslive.rememberTeamLogoPair
import com.nuvio.tv.ui.screens.sportslive.sportsLiveBold
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.core.sportslive.alerts.DualPanes
import com.nuvio.tv.ui.screens.sportslive.RegisterSportsLiveAlertSurface
import com.nuvio.tv.ui.screens.sportslive.SportsLiveAlertSurface
import com.nuvio.tv.ui.screens.sportslive.SportsLiveAlertSurfaces
import com.nuvio.tv.ui.screens.sportslive.SportsLivePeekHost
import com.nuvio.tv.ui.screens.sportslive.SportsLivePlayHost
import com.nuvio.tv.ui.screens.sportslive.SportsLivePlayState
import com.nuvio.tv.ui.screens.sportslive.SportsLivePlayViewModel
import com.nuvio.tv.ui.screens.sportslive.SportsLiveEndAction
import com.nuvio.tv.ui.screens.sportslive.SportsLiveEndCardView
import com.nuvio.tv.ui.screens.sportslive.SportsLiveReconnectingChip
import com.nuvio.tv.ui.screens.sportslive.sportsLiveEndActions
import com.nuvio.tv.ui.screens.sportslive.SportsLiveStartingAction
import com.nuvio.tv.ui.screens.sportslive.SportsLiveStartingCard
import com.nuvio.tv.ui.screens.sportslive.SportsLiveZapTarget
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Opens dual screen on the session just put in [SportsLiveDualSessions], leaving the player that
 * asks; provided around the player by the nav host, null elsewhere (no action is offered).
 */
val LocalOpenSportsLiveDual = staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * Two Sports & Live games side by side; only the highlighted pane has sound. Left/Right highlight a
 * pane, Up/Down zap it, MENU swaps the panes (held: split or big + small, #123), INFO shows the game info
 * for the game with sound (#139), OK plays the highlighted game in the full player (its
 * playing stream as is; a pane not playing takes the section's path: Stream screen, or a merged
 * game's best stream) and Back leaves, or returns to the full player dual screen was opened in.
 * Each pane has a score strip under it (#124). Both layouts use the same two players: no third decoder.
 *
 * Each pane is a [PlayerView] with its default SurfaceView: two SurfaceViews are composited by the
 * display hardware directly, cheaper than TextureViews (no GPU copy per frame) and the right surface
 * for video on TV; the Compose overlays draw over them in the window.
 *
 * The [DualPlayers] live only while the screen is on: ON_STOP (Home, Back, another screen), leaving
 * the composition and OK release them, and ON_RESUME creates them again and replays the state.
 */
@OptIn(UnstableApi::class)
@Composable
fun SportsLiveDualScreen(
    onBack: () -> Unit,
    onOpenStreamScreen: (item: SportsLiveItem, manualSelection: Boolean) -> Unit,
    onPlayTarget: (SportsLiveZapTarget) -> Unit,
    viewModel: SportsLiveDualViewModel = hiltViewModel(),
    playViewModel: SportsLivePlayViewModel = hiltViewModel(),
    scoresViewModel: SportsLiveScoresViewModel = hiltViewModel(),
    gameInfo: SportsLiveGameInfoViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val layoutReady by viewModel.layoutReady.collectAsStateWithLifecycle()
    val keysHint by viewModel.keysHint.collectAsStateWithLifecycle()
    val pairHint by viewModel.pairHint.collectAsStateWithLifecycle()
    val pairPicker by viewModel.pairPicker.collectAsStateWithLifecycle()
    val currentOnBack by rememberUpdatedState(onBack)
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // The pane views by side; a swap moves the players between them.
    val views = remember { mutableMapOf<Side, PlayerView>() }
    var players by remember { mutableStateOf<DualPlayers?>(null) }

    fun releasePlayers() {
        val current = players ?: return
        views.values.forEach { it.player = null }
        current.release()
        players = null
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // Resumed, not started: a screen being navigated to resumes once its transition has
                // ended, by when the full player it may come from has let its decoder go.
                Lifecycle.Event.ON_RESUME -> if (players == null) {
                    players = DualPlayers(
                        context = context,
                        onFirstFrame = viewModel::onFirstFrame,
                        onError = viewModel::onPlayerError,
                        onIsPlaying = viewModel::onIsPlayingChanged,
                        onUnhealthy = viewModel::onUnhealthy,
                        onMoving = viewModel::onMoving,
                        onRequestFailed = viewModel::onRequestFailed,
                        onLoadProgress = viewModel::onLoadProgress
                    )
                    viewModel.onPlayersCreated()
                }
                Lifecycle.Event.ON_STOP -> releasePlayers()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            releasePlayers()
        }
    }

    // No session (process death): nothing to show.
    LaunchedEffect(state == null) {
        if (state == null) currentOnBack()
    }
    val dual = state ?: return

    // The panes' games are scored while the screen is started and Scores is not Off (#124); the highlighted
    // pane counts as focused for Hide until focused.
    val scores by scoresViewModel.scores.collectAsStateWithLifecycle()
    val scoresMode by scoresViewModel.mode.collectAsStateWithLifecycle()
    val revealed by scoresViewModel.revealed.collectAsStateWithLifecycle()
    val paneItems = remember(dual.left.item, dual.right.item) { listOf(dual.left.item, dual.right.item) }
    TrackLiveScores(SCREEN_DUAL, paneItems, enabled = scoresMode != null && scoresMode != ScoresMode.OFF, tracking = scoresViewModel.tracking)
    val highlightedKey = dual.pane(dual.highlighted).item.streamKey
    LaunchedEffect(highlightedKey) { scoresViewModel.onCardFocus(highlightedKey) }

    // The game info overlay (#139) is about the game with sound, and follows it while open.
    val info by gameInfo.info.collectAsStateWithLifecycle()
    val infoItem = dual.pane(dual.infoSide).item
    LaunchedEffect(infoItem) { gameInfo.follow(infoItem) }
    val menuKey = remember { DualMenuKey() }

    LaunchedEffect(players, dual) {
        val current = players ?: return@LaunchedEffect
        current.sync(dual)
        Side.entries.forEach { side -> views[side]?.player = current.player(side) }
    }

    // OK plays the pane's stream (or takes the section's path); the players stop before the full
    // player (or Stream screen) opens, and the zap session runs over the dual list.
    SportsLivePlayHost(
        playViewModel = playViewModel,
        onOpenStreamScreen = { item, manual ->
            releasePlayers()
            viewModel.startZapSession(item)
            onOpenStreamScreen(item, manual)
        },
        onPlayTarget = { target ->
            releasePlayers()
            viewModel.startZapSession(target.item)
            onPlayTarget(target)
        }
    )

    // Back from dual screen opened in a full player plays that game full screen again (M11), in place
    // of dual screen as OK does (#27); a second Back while it starts, or dual opened elsewhere, leaves.
    var backPlayed by remember { mutableStateOf(false) }
    val handleBack: () -> Unit = handleBack@{
        // The game info overlay closes first.
        if (gameInfo.hide()) return@handleBack
        // An ended pane's countdown (#161): Back stops it first.
        if (viewModel.cancelEndCountdowns()) return@handleBack
        // "Close this side" (#138): the highlighted pane gave up; the other game plays full screen.
        viewModel.closeFailedSide()?.takeUnless { backPlayed }?.let { (item, stream) ->
            backPlayed = true
            playViewModel.playResolved(item, stream)
            return@handleBack
        }
        val back = viewModel.backToPlayer()?.takeUnless { backPlayed }
        if (back == null) {
            currentOnBack()
        } else {
            backPlayed = true
            val (item, stream) = back
            if (stream != null) playViewModel.playResolved(item, stream) else playViewModel.play(item)
        }
    }
    BackHandler { handleBack() }

    // The highlighted pane's starting card (#138): Up/Down move through its buttons, OK presses one.
    val highlightedPane = dual.pane(dual.highlighted)
    var startingSelection by remember { mutableStateOf(SportsLiveStartingAction.TRY_NEXT_LINK) }
    LaunchedEffect(dual.highlighted, highlightedPane.stream) { startingSelection = SportsLiveStartingAction.TRY_NEXT_LINK }
    // The highlighted ended pane's card (#161): Up/Down move through its buttons, OK presses one.
    var endSelection by remember { mutableStateOf(SportsLiveEndAction.WATCH_NEXT) }
    val endActions = highlightedPane.end?.takeIf { highlightedPane.status == PaneStatus.Ended }?.let { sportsLiveEndActions(it, pane = true) }
    LaunchedEffect(dual.highlighted, highlightedPane.end?.item?.streamKey, endActions) {
        endSelection = endActions?.first() ?: SportsLiveEndAction.WATCH_NEXT
    }

    // Focus starts on the screen and comes back to it when OK's lookup or stream picker closes.
    val focusRequester = remember { FocusRequester() }
    val playState by playViewModel.state.collectAsStateWithLifecycle()

    // Alerts (#136): a card sits over the pane it is not about, and OK on it plays its game in the
    // highlighted pane, unless the picker, OK's lookup or a starting card is up.
    LaunchedEffect(dual.left.item, dual.right.item, dual.highlighted, dual.layout) {
        SportsLiveAlertSurfaces.setDualPanes(
            DualPanes(dual.left.item, dual.right.item, dual.highlighted == Side.LEFT, bigSmall = dual.layout == DualLayout.BIG_SMALL)
        )
    }
    DisposableEffect(Unit) { onDispose { SportsLiveAlertSurfaces.setDualPanes(null) } }
    val alertBusy by rememberUpdatedState(
        pairPicker != null || playState != SportsLivePlayState.Idle || dual.pane(dual.highlighted).isStarting
    )
    val alertSurface = remember(viewModel) {
        object : SportsLiveAlertSurface {
            override fun watch(item: SportsLiveItem): Boolean = viewModel.watchFromAlert(item)
            override fun busy(): Boolean = alertBusy
            override val peek: SportsLivePeekHost? = null
        }
    }
    RegisterSportsLiveAlertSurface(alertSurface)
    LaunchedEffect(playState == SportsLivePlayState.Idle, pairPicker == null) {
        if (playState == SportsLivePlayState.Idle && pairPicker == null) runCatching { focusRequester.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                val native = event.nativeKeyEvent
                // MENU (#123): a short press swaps the panes on release; held, it changes the layout.
                if (native.keyCode == KeyEvent.KEYCODE_MENU) {
                    val action = when (native.action) {
                        KeyEvent.ACTION_DOWN -> menuKey.onDown(native.repeatCount)
                        KeyEvent.ACTION_UP -> menuKey.onUp()
                        else -> null
                    }
                    when (action) {
                        DualMenuAction.SWAP -> viewModel.swap()
                        DualMenuAction.TOGGLE_LAYOUT -> viewModel.toggleLayout()
                        null -> Unit
                    }
                    return@onKeyEvent true
                }
                if (native.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
                // Any key stops an ended pane's countdown (#161), and still does what it does.
                if (native.keyCode != KeyEvent.KEYCODE_BACK) viewModel.cancelEndCountdowns()
                // INFO (#139): the game info overlay for the game with sound.
                when (SportsLiveGameInfoKeys.dual(native.keyCode, gameInfo.shown, gameInfo.available(infoItem))) {
                    GameInfoKeyAction.SHOW -> {
                        if (native.repeatCount == 0) gameInfo.show(infoItem)
                        return@onKeyEvent true
                    }
                    GameInfoKeyAction.HIDE -> {
                        if (native.repeatCount == 0) gameInfo.hide()
                        return@onKeyEvent true
                    }
                    GameInfoKeyAction.PASS, GameInfoKeyAction.HIDE_AND_PASS -> if (native.keyCode == KeyEvent.KEYCODE_INFO) {
                        // F13: a game with no ESPN match has no info to show; say so rather than do nothing.
                        if (native.repeatCount == 0) {
                            SportsLiveToasts.show(SportsLiveToastKind.INFO, context.getString(R.string.sports_live_dual_no_info))
                        }
                        return@onKeyEvent true
                    }
                }
                // The highlighted ended pane's card: Up/Down move through its buttons, OK presses one.
                if (endActions != null) {
                    when (native.keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                            val at = endActions.indexOf(endSelection).coerceAtLeast(0)
                            val step = if (native.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
                            endSelection = endActions[(at + step).coerceIn(0, endActions.lastIndex)]
                            return@onKeyEvent true
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            if (native.repeatCount == 0) {
                                when (endSelection) {
                                    SportsLiveEndAction.WATCH_NEXT -> viewModel.endWatchNext()
                                    else -> viewModel.endStay()
                                }
                            }
                            return@onKeyEvent true
                        }
                    }
                }
                // A starting pane's card (#138): CH+/CH- still zap, as everywhere.
                if (dual.pane(dual.highlighted).isStarting) {
                    val actions = SportsLiveStartingAction.entries
                    when (native.keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            startingSelection = actions[(startingSelection.ordinal - 1).coerceAtLeast(0)]
                            return@onKeyEvent true
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            startingSelection = actions[(startingSelection.ordinal + 1).coerceAtMost(actions.lastIndex)]
                            return@onKeyEvent true
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            if (native.repeatCount == 0) {
                                when (startingSelection) {
                                    SportsLiveStartingAction.TRY_NEXT_LINK -> viewModel.tryNextLink()
                                    SportsLiveStartingAction.SOURCES -> playViewModel.chooseStream(dual.pane(dual.highlighted).item)
                                }
                            }
                            return@onKeyEvent true
                        }
                    }
                }
                when (native.keyCode) {
                    // On the left pane already, Left opens the right pane's picker (#57).
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (native.repeatCount == 0) viewModel.onLeft()
                    KeyEvent.KEYCODE_DPAD_RIGHT -> viewModel.highlight(Side.RIGHT)
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                        if (native.repeatCount == 0) {
                            val pane = dual.pane(dual.highlighted)
                            // A playing pane's stream goes straight to the full player; a pane whose
                            // recovery gave up offers its streams to choose from (#138).
                            val stream = pane.okStream
                            if (stream != null) playViewModel.playResolved(pane.item, stream)
                            else if (pane.status == PaneStatus.Error) playViewModel.chooseStream(pane.item)
                            else playViewModel.play(pane.item)
                        }
                    else -> {
                        // Every pane zaps, its error card included: flipping on through dead games never sticks.
                        val forward = dualZapKey(dual.pane(dual.highlighted), native.keyCode) ?: return@onKeyEvent false
                        if (native.repeatCount == 0 && !viewModel.zap(forward)) {
                            SportsLiveToasts.show(SportsLiveToastKind.INFO, context.getString(R.string.sports_live_no_other_live_game))
                        }
                    }
                }
                true
            },
        contentAlignment = Alignment.Center
    ) {
        // Both layouts place the same two pane views (#123): only their size and spot change, so the
        // players keep playing through a layout change.
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val chipStrings = rememberScoreChipStrings()
            val detailsStrings = rememberScoreDetailsStrings()
            // Not before the layout is known: a pane view is first laid out at its real tile (see layoutReady).
            if (layoutReady) Side.entries.forEach { side ->
                val tile = DualGeometry.tile(side, dual.layout, maxWidth, maxHeight)
                val pane = dual.pane(side)
                DualPaneView(
                    pane = pane,
                    highlighted = side == dual.highlighted,
                    small = dual.layout == DualLayout.BIG_SMALL && side == Side.RIGHT,
                    startingSelection = startingSelection,
                    endSelection = endSelection,
                    sound = dual.paneSound(side, players?.isPlaying(side) == true),
                    strip = DualScoreStrip.of(pane.item, scores[pane.item.streamKey], scoresMode, pane.item.streamKey in revealed),
                    chipStrings = chipStrings,
                    detailsStrings = detailsStrings,
                    onViewCreated = { view ->
                        views[side] = view
                        view.player = players?.player(side)
                    },
                    modifier = Modifier
                        .offset(x = tile.start, y = tile.top)
                        .width(tile.width)
                )
            }
            // Split: under the sound pane's strip; big + small: the column under the small tile.
            val infoTile = DualGeometry.tile(
                if (dual.layout == DualLayout.BIG_SMALL) Side.RIGHT else dual.infoSide, dual.layout, maxWidth, maxHeight
            )
            SportsLiveGameInfoOverlay(
                info = info,
                compact = true,
                modifier = Modifier
                    .offset(x = infoTile.start, y = infoTile.bottom + SportsLiveTokens.Size.DualCaption + 4.dp)
                    .width(infoTile.width)
            )
        }
        // Not focusable: keys stay with the screen.
        AnimatedVisibility(
            visible = keysHint,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = stringResource(R.string.sports_live_dual_keys_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextPrimary,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(50))
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            )
        }
        // "Watch with another game" paired the right pane by itself (#57); not focusable either. The
        // last name stays while the hint fades out.
        var lastPairHint by remember { mutableStateOf("") }
        LaunchedEffect(pairHint) { pairHint?.let { lastPairHint = it } }
        AnimatedVisibility(
            visible = pairHint != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 32.dp)
        ) {
            Text(
                text = stringResource(R.string.sports_live_dual_paired_hint, pairHint ?: lastPairHint),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(50))
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            )
        }
    }

    pairPicker?.let { candidates ->
        SportsLiveDualPickerDialog(
            candidates = candidates,
            rows = emptyMap(),
            onPick = viewModel::pickPair,
            onDismiss = viewModel::closePairPicker
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun DualPaneView(
    pane: DualPane,
    highlighted: Boolean,
    /** The corner tile of big + small (#123): a smaller strip. */
    small: Boolean,
    startingSelection: SportsLiveStartingAction,
    endSelection: SportsLiveEndAction,
    sound: PaneSound,
    /** The score strip (#124); null: the pane's clean name. */
    strip: DualScoreStrip?,
    chipStrings: ScoreChipStrings,
    detailsStrings: ScoreDetailsStrings,
    onViewCreated: (PlayerView) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    val ring = SportsLiveTokens.Focus.RingWidth
    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                // The V2 ring (white, 4 px) marks the pane the keys act on, which has the sound.
                .border(
                    width = ring,
                    color = if (highlighted) SportsLiveTokens.Colors.FocusWhite else Color.Transparent,
                    shape = shape
                )
                .padding(ring),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        // Keys go to the Compose screen, never to the view.
                        isFocusable = false
                        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                        // The TV stays awake while the panes are on screen.
                        keepScreenOn = true
                        // A recovery switching links keeps the last frame, not a black pane (#138).
                        setKeepContentOnPlayerReset(true)
                    }.also(onViewCreated)
                },
                modifier = Modifier.fillMaxSize()
            )
            PaneStatusOverlay(pane, highlighted, small, startingSelection, endSelection)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, start = 4.dp, end = 4.dp)
        ) {
            PaneSoundIndicator(sound)
            val textColor = if (highlighted) SportsLiveTokens.Colors.TextPrimary else SportsLiveTokens.Colors.TextSecondary
            if (strip == null) {
                Text(
                    text = dualPaneCaption(pane.item),
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp)
                )
            } else {
                PaneScoreStrip(strip, small, textColor, chipStrings, detailsStrings, Modifier.weight(1f).padding(start = 8.dp))
            }
        }
    }
}

/** "[logo] BOS 91–88 NYK [logo]  ● 4:12 Q4": the logos only once both have loaded, the dot only while live. */
@Composable
private fun PaneScoreStrip(
    strip: DualScoreStrip,
    small: Boolean,
    textColor: Color,
    chipStrings: ScoreChipStrings,
    detailsStrings: ScoreDetailsStrings,
    modifier: Modifier = Modifier
) {
    val logos = rememberTeamLogoPair(strip.first.logo, strip.second.logo)?.takeUnless { it.failed }
    val logoSize = if (small) 12.dp else 16.dp
    val style = SportsLiveTokens.style(if (small) SportsLiveTokens.Type.PanelScore else SportsLiveTokens.Type.DualStrip)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = modifier) {
        logos?.let { TeamLogoSlot(it.first, it.shown, logoSize) }
        Text(
            text = strip.scoreline(detailsStrings),
            style = style.sportsLiveBold(),
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        logos?.let { TeamLogoSlot(it.second, it.shown, logoSize) }
        strip.status(chipStrings)?.let { status ->
            if (strip.live) Box(Modifier.size(6.dp).background(SportsLiveTokens.Colors.LiveRed, CircleShape))
            Text(
                text = status,
                style = style,
                color = if (strip.live) SportsLiveTokens.Colors.LiveText else SportsLiveTokens.Colors.TextMuted,
                maxLines = 1
            )
        }
    }
}

/** A speaker on the pane with sound (with moving bars while it plays), a faint muted one on the other. */
@Composable
private fun PaneSoundIndicator(sound: PaneSound) {
    val audible = sound != PaneSound.MUTED
    val color = if (audible) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextPrimary.copy(alpha = 0.4f)
    Icon(
        imageVector = if (audible) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
        contentDescription = stringResource(
            if (audible) R.string.sports_live_dual_sound_on else R.string.sports_live_dual_sound_off
        ),
        tint = color,
        modifier = Modifier.size(20.dp)
    )
    if (sound == PaneSound.AUDIBLE_PLAYING) SoundBars(color = color, modifier = Modifier.padding(start = 4.dp))
}

/**
 * Three bars moving like an equaliser. The phase is read only while drawing, so the animation
 * redraws this small canvas and recomposes nothing.
 */
@Composable
private fun SoundBars(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "soundBars")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing)),
        label = "soundBarsPhase"
    )
    Canvas(modifier = modifier.size(width = 14.dp, height = 14.dp)) {
        val barWidth = size.width / 5f
        repeat(3) { i ->
            val fraction = 0.25f + 0.75f * abs(sin((phase + i / 3f) * PI.toFloat()))
            val barHeight = size.height * fraction
            drawRect(
                color = color,
                topLeft = Offset(x = i * 2 * barWidth, y = size.height - barHeight),
                size = Size(barWidth, barHeight)
            )
        }
    }
}

@Composable
private fun BoxScope.PaneStatusOverlay(
    pane: DualPane,
    highlighted: Boolean,
    small: Boolean,
    startingSelection: SportsLiveStartingAction,
    endSelection: SportsLiveEndAction
) {
    // A link starting (#138): its card; the highlighted pane's has the buttons the keys move through.
    if (pane.isStarting) {
        val startedAtMs = remember(pane.stream, pane.attempt) { System.currentTimeMillis() }
        SportsLiveStartingCard(
            game = pane.item.displayName,
            linkNumber = pane.linkNumber,
            linkCount = pane.linkCount,
            sourceName = pane.stream?.addonName,
            startedAtMs = startedAtMs,
            onAction = {},
            selected = startingSelection,
            compact = true,
            showActions = highlighted,
            modifier = Modifier.align(Alignment.Center)
        )
        return
    }
    // A recovery (#138): the last frame stays, with a small chip at its top-left, clear of the scorebug.
    if (pane.reconnecting && pane.status == PaneStatus.Playing) {
        SportsLiveReconnectingChip(
            small = true,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
        )
        return
    }
    when (pane.status) {
        PaneStatus.Loading -> LoadingIndicator()
        PaneStatus.Playing -> Unit
        PaneStatus.Error -> PaneMessage(
            text = stringResource(R.string.sports_live_dual_stream_error),
            hint = stringResource(R.string.sports_live_dual_recovery_choices),
            small = small
        )
        PaneStatus.DeviceLimit -> PaneMessage(text = stringResource(R.string.sports_live_dual_device_limit), hint = null, small = small)
        // The game ended (#161): the end card in the pane; once closed (Stay), "Game over".
        PaneStatus.Ended -> pane.end?.let { card ->
            SportsLiveEndCardView(
                card = card,
                onAction = {},
                onAnyKey = {},
                pane = true,
                selected = endSelection,
                showActions = highlighted,
                modifier = Modifier.align(Alignment.Center)
            )
        } ?: PaneMessage(text = stringResource(R.string.sports_live_end_game_over), hint = null, small = small)
    }
}

/**
 * A pane's message (V2): a scrim over the pane's last frame and a card like the pane's end card, never bare text on
 * video. The title takes up to two lines; each key hint ("OK  Choose stream") has its own line. The corner tile of
 * big + small ([small]) has smaller type and padding, so all of it fits.
 */
@Composable
private fun BoxScope.PaneMessage(text: String, hint: String?, small: Boolean) {
    Box(modifier = Modifier.matchParentSize().background(Color.Black.copy(alpha = PANE_SCRIM_ALPHA)))
    val shape = RoundedCornerShape(SportsLiveTokens.Alert.Radius)
    Column(
        verticalArrangement = Arrangement.spacedBy(if (small) 2.dp else 4.dp),
        modifier = Modifier
            .align(Alignment.Center)
            .padding(if (small) 6.dp else 16.dp)
            .widthIn(max = 320.dp)
            .clip(shape)
            .background(SportsLiveTokens.Colors.Surface.copy(alpha = 0.94f))
            .border(1.dp, SportsLiveTokens.Colors.Outline, shape)
            .padding(horizontal = if (small) 8.dp else 14.dp, vertical = if (small) 6.dp else 12.dp)
    ) {
        Text(
            text = text,
            style = SportsLiveTokens.style(if (small) SportsLiveTokens.Toast.Title else SportsLiveTokens.Type.PanelTitle),
            color = SportsLiveTokens.Colors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        // One line per key: the hints are joined with a middle dot.
        hint?.split('\u00B7')?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach { line ->
            Text(
                text = line,
                style = SportsLiveTokens.style(if (small) SportsLiveTokens.Type.Tag else SportsLiveTokens.Type.PanelLine),
                color = SportsLiveTokens.Colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** How dark a pane's message makes its last frame. */
private const val PANE_SCRIM_ALPHA = 0.6f
