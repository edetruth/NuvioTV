package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import com.nuvio.tv.core.sportslive.alerts.AlertCardClock
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import kotlinx.coroutines.delay

/**
 * Overlay card motion (#101): a card enters from its own edge by [SportsLiveTokens.Motion.TRAVEL] with a fade
 * (200 ms, decelerating) and leaves with a 150 ms fade and the same slide back (accelerating). Top right cards
 * ([fromEnd]) come from the right, bottom left cards from the left. Hide [visible] to start the exit.
 */
@Composable
internal fun SportsLiveCardEntrance(
    visible: MutableTransitionState<Boolean>,
    fromEnd: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable AnimatedVisibilityScope.() -> Unit
) {
    val travel = with(LocalDensity.current) { SportsLiveTokens.Motion.TRAVEL.roundToPx() }
    val offset = SportsLiveTokens.Motion.slideOffset(fromEnd, travel)
    AnimatedVisibility(
        visibleState = visible,
        enter = slideInHorizontally(tween(SportsLiveTokens.Motion.ENTER_MS, easing = FastOutSlowInEasing)) { offset } +
            fadeIn(tween(SportsLiveTokens.Motion.ENTER_MS, easing = FastOutSlowInEasing)),
        exit = slideOutHorizontally(tween(SportsLiveTokens.Motion.EXIT_MS, easing = FastOutLinearInEasing)) { offset } +
            fadeOut(tween(SportsLiveTokens.Motion.EXIT_MS, easing = FastOutLinearInEasing)),
        modifier = modifier,
        content = content
    )
}

/** The share of a card's time still left ([0, 1]) for its [duration], on [clock] at [nowMs]; it stops while the clock does. */
internal fun AlertCardClock.timeLeftFraction(nowMs: Long, duration: Long): Float =
    if (duration <= 0L) 0f else (1f - totalShownMs(nowMs).toFloat() / duration).coerceIn(0f, 1f)

/** The time-left share of a card, updated every [tickMs] and eased linearly between ticks so the line shrinks smoothly. */
@Composable
internal fun rememberTimeLeft(clock: AlertCardClock, duration: Long, tickMs: Long = 250L): State<Float> {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(tickMs) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(tickMs)
        }
    }
    return animateFloatAsState(clock.timeLeftFraction(nowMs, duration), tween(tickMs.toInt(), easing = LinearEasing), label = "timeLeft")
}

/** The 2 dp time-left line along a card's bottom inside edge ([share] is read when drawing, so it never recomposes). */
internal fun Modifier.timeLeftLine(share: (() -> Float)?): Modifier {
    if (share == null) return this
    return drawBehind {
        val height = SportsLiveTokens.Motion.TIME_LINE.toPx()
        drawRect(
            color = SportsLiveTokens.Colors.TextMuted.copy(alpha = SportsLiveTokens.Motion.TIME_LINE_ALPHA),
            topLeft = Offset(0f, size.height - height),
            size = Size(size.width * share().coerceIn(0f, 1f), height)
        )
    }
}
