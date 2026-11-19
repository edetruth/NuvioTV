package com.nuvio.tv.ui.screens.sportslive.dual

import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.displayName
import android.view.KeyEvent
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
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
import com.nuvio.tv.ui.screens.sportslive.SportsLiveReconnectingChip
import com.nuvio.tv.ui.screens.sportslive.SportsLiveStartingAction
import com.nuvio.tv.ui.screens.sportslive.SportsLiveStartingCard
import com.nuvio.tv.ui.screens.sportslive.SportsLiveZapTarget
import com.nuvio.tv.ui.screens.sportslive.zapDirection
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
 * pane, Up/Down zap it, MENU swaps the panes, OK plays the highlighted game in the full player (its
 * playing stream as is; a pane not playing takes the section's path: Stream screen, or a merged
 * game's best stream) and Back leaves, or returns to the full player dual screen was opened in.
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
    playViewModel: SportsLivePlayViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
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

    // Focus starts on the screen and comes back to it when OK's lookup or stream picker closes.
    val focusRequester = remember { FocusRequester() }
    val playState by playViewModel.state.collectAsStateWithLifecycle()

    // Alerts (#136): a card sits over the pane it is not about, and OK on it plays its game in the
    // highlighted pane, unless the picker, OK's lookup or a starting card is up.
    LaunchedEffect(dual.left.item, dual.right.item, dual.highlighted) {
        SportsLiveAlertSurfaces.setDualPanes(DualPanes(dual.left.item, dual.right.item, dual.highlighted == Side.LEFT))
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
                if (native.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
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
                    KeyEvent.KEYCODE_MENU -> if (native.repeatCount == 0) viewModel.swap()
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
                        val forward = zapDirection(native.keyCode, partyActive = false) ?: return@onKeyEvent false
                        // On a pane whose recovery gave up, Down is "Next live game" (#138).
                        if (native.repeatCount == 0) {
                            if (forward && dual.pane(dual.highlighted).status == PaneStatus.Error) viewModel.nextLiveGame()
                            else viewModel.zap(forward)
                        }
                    }
                }
                true
            },
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SportsLiveTokens.Size.DualSideMargin),
            horizontalArrangement = Arrangement.spacedBy(SportsLiveTokens.Size.DualGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Side.entries.forEach { side ->
                DualPaneView(
                    pane = dual.pane(side),
                    highlighted = side == dual.highlighted,
                    startingSelection = startingSelection,
                    sound = dual.paneSound(side, players?.isPlaying(side) == true),
                    onViewCreated = { view ->
                        views[side] = view
                        view.player = players?.player(side)
                    },
                    modifier = Modifier.weight(1f)
                )
            }
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
    startingSelection: SportsLiveStartingAction,
    sound: PaneSound,
    onViewCreated: (PlayerView) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .border(
                    width = 3.dp,
                    color = if (highlighted) NuvioTheme.colors.Primary else Color.Transparent,
                    shape = shape
                )
                .padding(3.dp),
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
            PaneStatusOverlay(pane, highlighted, startingSelection)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, start = 4.dp, end = 4.dp)
        ) {
            PaneSoundIndicator(sound)
            Text(
                text = listOfNotNull(pane.item.displayName, SportsLiveTeams.sportLabelOf(pane.item)).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = if (highlighted) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp)
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
private fun BoxScope.PaneStatusOverlay(pane: DualPane, highlighted: Boolean, startingSelection: SportsLiveStartingAction) {
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
            hint = stringResource(R.string.sports_live_dual_recovery_choices)
        )
        PaneStatus.DeviceLimit -> PaneMessage(text = stringResource(R.string.sports_live_dual_device_limit), hint = null)
    }
}

@Composable
private fun PaneMessage(text: String, hint: String?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(16.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = NuvioTheme.colors.TextPrimary,
            textAlign = TextAlign.Center
        )
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}
