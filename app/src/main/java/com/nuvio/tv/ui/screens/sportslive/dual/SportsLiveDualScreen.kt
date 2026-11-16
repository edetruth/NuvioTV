package com.nuvio.tv.ui.screens.sportslive.dual

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
import com.nuvio.tv.ui.screens.sportslive.SportsLivePlayHost
import com.nuvio.tv.ui.screens.sportslive.SportsLivePlayState
import com.nuvio.tv.ui.screens.sportslive.SportsLivePlayViewModel
import com.nuvio.tv.ui.screens.sportslive.SportsLiveZapTarget
import com.nuvio.tv.ui.screens.sportslive.zapDirection
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
 * game's best stream) and Back leaves.
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
    onOpenStreamScreen: (SportsLiveItem) -> Unit,
    onPlayTarget: (SportsLiveZapTarget) -> Unit,
    viewModel: SportsLiveDualViewModel = hiltViewModel(),
    playViewModel: SportsLivePlayViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val keysHint by viewModel.keysHint.collectAsStateWithLifecycle()
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
                        onError = viewModel::onPlayerError
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
        onOpenStreamScreen = { item ->
            releasePlayers()
            viewModel.startZapSession(item)
            onOpenStreamScreen(item)
        },
        onPlayTarget = { target ->
            releasePlayers()
            viewModel.startZapSession(target.item)
            onPlayTarget(target)
        }
    )

    BackHandler { currentOnBack() }

    // Focus starts on the screen and comes back to it when OK's lookup or stream picker closes.
    val focusRequester = remember { FocusRequester() }
    val playState by playViewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(playState == SportsLivePlayState.Idle) {
        if (playState == SportsLivePlayState.Idle) runCatching { focusRequester.requestFocus() }
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
                when (native.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> viewModel.highlight(Side.LEFT)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> viewModel.highlight(Side.RIGHT)
                    KeyEvent.KEYCODE_MENU -> if (native.repeatCount == 0) viewModel.swap()
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                        if (native.repeatCount == 0) {
                            val pane = dual.pane(dual.highlighted)
                            // A playing pane's stream goes straight to the full player.
                            val stream = pane.okStream
                            if (stream != null) playViewModel.playResolved(pane.item, stream)
                            else playViewModel.play(pane.item)
                        }
                    else -> {
                        val forward = zapDirection(native.keyCode, partyActive = false) ?: return@onKeyEvent false
                        if (native.repeatCount == 0) viewModel.zap(forward)
                    }
                }
                true
            },
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Side.entries.forEach { side ->
                DualPaneView(
                    pane = dual.pane(side),
                    highlighted = side == dual.highlighted,
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
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun DualPaneView(
    pane: DualPane,
    highlighted: Boolean,
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
                    }.also(onViewCreated)
                },
                modifier = Modifier.fillMaxSize()
            )
            PaneStatusOverlay(pane.status)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, start = 4.dp, end = 4.dp)
        ) {
            PaneSoundIndicator(sound)
            Text(
                text = "${pane.item.meta.name} · ${pane.item.sport ?: pane.item.catalogName}",
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
private fun PaneStatusOverlay(status: PaneStatus) {
    when (status) {
        PaneStatus.Loading -> LoadingIndicator()
        PaneStatus.Playing -> Unit
        PaneStatus.Error -> PaneMessage(
            text = stringResource(R.string.sports_live_dual_stream_error),
            hint = stringResource(R.string.sports_live_dual_stream_error_hint)
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
