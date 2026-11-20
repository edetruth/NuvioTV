package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.SportsLiveToast
import com.nuvio.tv.core.sportslive.SportsLiveToastKind
import com.nuvio.tv.core.sportslive.SportsLiveToastQueue
import com.nuvio.tv.core.sportslive.SportsLiveToasts
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Colors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one toast card (#134): 320 dp on the raised surface, the icon column, a title and an optional line, each
 * one line with an ellipsis. The icon's colour carries the meaning ([toastIcon]). Never focusable. The alert
 * cards (#136) can take the same look by drawing their body in [SportsLiveToastFrame].
 */
@Composable
fun SportsLiveToastCard(toast: SportsLiveToast, modifier: Modifier = Modifier) {
    SportsLiveToastFrame(kind = toast.kind, modifier = modifier) {
        Text(
            text = toast.title,
            style = SportsLiveTokens.style(SportsLiveTokens.Toast.Title),
            color = Colors.TextPrimary,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
        toast.line?.let { line ->
            Text(
                text = line,
                style = SportsLiveTokens.style(SportsLiveTokens.Toast.Line),
                color = Colors.TextSecondary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** The toast card's frame: the surface, the icon column for [kind], then [content] (the text column). */
@Composable
fun SportsLiveToastFrame(kind: SportsLiveToastKind, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    // The alert cards' surface (#136): the panel colour, slightly see-through, with their hairline border.
    val shape = RoundedCornerShape(SportsLiveTokens.Alert.Radius)
    Row(
        modifier = modifier
            .width(SportsLiveTokens.Toast.Width)
            .background(Colors.Surface.copy(alpha = SportsLiveTokens.Alert.FILL_ALPHA), shape)
            .border(1.dp, SportsLiveTokens.Alert.Border, shape)
            .padding(horizontal = SportsLiveTokens.Toast.PaddingHorizontal, vertical = SportsLiveTokens.Toast.PaddingVertical),
        horizontalArrangement = Arrangement.spacedBy(SportsLiveTokens.Toast.IconGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.width(SportsLiveTokens.Toast.IconColumn), contentAlignment = Alignment.Center) {
            ToastIcon(kind)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) { content() }
    }
}

/** The icon's colour carries the meaning: blue bell reminder, gold ★ your team, amber ⚠ problem, FT final. */
internal fun toastIconColor(kind: SportsLiveToastKind): Color = when (kind) {
    SportsLiveToastKind.REMINDER -> Colors.WhyTagBlue
    SportsLiveToastKind.FOLLOWED -> Colors.FollowedGold
    SportsLiveToastKind.PROBLEM -> Colors.WarningAmber
    SportsLiveToastKind.FINAL -> Colors.TextPrimary
    SportsLiveToastKind.INFO -> Colors.TextMuted
}

private fun toastIcon(kind: SportsLiveToastKind): ImageVector? = when (kind) {
    SportsLiveToastKind.REMINDER -> Icons.Rounded.Notifications
    SportsLiveToastKind.FOLLOWED -> Icons.Rounded.Star
    SportsLiveToastKind.PROBLEM -> Icons.Rounded.WarningAmber
    SportsLiveToastKind.INFO -> Icons.Rounded.PlayArrow
    SportsLiveToastKind.FINAL -> null
}

@Composable
private fun ToastIcon(kind: SportsLiveToastKind) {
    val vector = toastIcon(kind)
    if (vector != null) {
        Icon(imageVector = vector, contentDescription = null, tint = toastIconColor(kind), modifier = Modifier.size(18.dp))
    } else {
        Text(
            text = stringResource(R.string.sports_live_toast_final_mark),
            style = SportsLiveTokens.style(SportsLiveTokens.Toast.FinalMark),
            color = toastIconColor(kind),
            maxLines = 1,
            softWrap = false
        )
    }
}

/**
 * Where every Sports & Live toast shows (#134, live.19 QA F8): one corner, the bottom left of the safe area, on
 * every screen (clear of the side rail) and over video ([SportsLiveToastPlace.overVideo]: the player's corner,
 * where its starting card sits). It slides in from the left and sits above a card that holds the corner
 * ([claimsBottomLeft]: the starting, waiting and error cards, the kickoff prompt), never over it. One at a time,
 * the newest replacing the one up ([SportsLiveToastQueue]), each for [SportsLiveToastQueue.SHOW_MS]. It never
 * takes focus: the screen under it keeps the D-pad.
 */
@Composable
fun SportsLiveToastHost(queue: SportsLiveToastQueue = SportsLiveToasts.shared) {
    val toast by queue.current.collectAsStateWithLifecycle()
    val claimedTopPx by SportsLiveBottomLeft.topPx.collectAsStateWithLifecycle()
    val overVideo by SportsLiveToastPlace.overVideo.collectAsStateWithLifecycle()
    LaunchedEffect(toast?.id) {
        val id = toast?.id ?: return@LaunchedEffect
        delay(SportsLiveToastQueue.SHOW_MS)
        queue.dismiss(id)
    }
    // The last toast stays composed while it slides out.
    val shown = remember { ToastHolder() }
    toast?.let { shown.last = it }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val claimedTop = claimedTopPx?.let { with(density) { it.toDp() } }
        val bottom = sportsLiveToastBottom(
            base = if (overVideo) SportsLiveTokens.Toast.BottomOverVideo else SportsLiveTokens.Toast.Bottom,
            height = maxHeight,
            claimedTop = claimedTop,
            gap = SportsLiveTokens.Size.RowGap
        )
        AnimatedVisibility(
            visible = toast != null,
            enter = slideInHorizontally(tween(SportsLiveTokens.Alert.SLIDE_MS)) { width -> -width } +
                fadeIn(tween(SportsLiveTokens.Alert.SLIDE_MS)),
            exit = slideOutHorizontally(tween(SportsLiveTokens.Toast.ENTER_MS)) { width -> -width } +
                fadeOut(tween(SportsLiveTokens.Toast.ENTER_MS)),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(
                    start = if (overVideo) SportsLiveTokens.Toast.StartOverVideo else SportsLiveTokens.Toast.Start,
                    bottom = bottom
                )
        ) {
            shown.last?.let { SportsLiveToastCard(it) }
        }
    }
}

/**
 * The toast's distance from the bottom of a screen [height] tall: [base], or [gap] above a card whose top edge
 * is at [claimedTop] (null: nothing claims the corner), whichever is higher up.
 */
internal fun sportsLiveToastBottom(base: Dp, height: Dp, claimedTop: Dp?, gap: Dp): Dp =
    if (claimedTop == null) base else maxOf(base, height - claimedTop + gap)

private class ToastHolder {
    var last: SportsLiveToast? = null
}

/**
 * Who holds the bottom left corner (#134, live.19 QA F8): the player's starting, waiting and error cards and the
 * kickoff prompt claim it with [claimsBottomLeft], and the toast sits above the highest of them, so the two
 * never overlap.
 */
object SportsLiveBottomLeft {
    private val claims = HashMap<Any, Int>()
    private val _topPx = MutableStateFlow<Int?>(null)

    /** The highest top edge (window px) of what claims the corner; null when nothing does. */
    val topPx: StateFlow<Int?> = _topPx.asStateFlow()

    @Synchronized
    fun claim(owner: Any, topPx: Int) {
        claims[owner] = topPx
        _topPx.value = claims.values.minOrNull()
    }

    @Synchronized
    fun release(owner: Any) {
        claims.remove(owner)
        _topPx.value = claims.values.minOrNull()
    }
}

/** Claims the bottom left corner for as long as this is composed: the toast then shows above it. */
@Composable
fun Modifier.claimsBottomLeft(): Modifier {
    val owner = remember { Any() }
    DisposableEffect(owner) { onDispose { SportsLiveBottomLeft.release(owner) } }
    return onGloballyPositioned { coordinates -> SportsLiveBottomLeft.claim(owner, coordinates.boundsInWindow().top.toInt()) }
}

/**
 * Whether the toast shows over full-screen video (the player): then it takes the player's corner, lined up with
 * its starting card, instead of the corner beside the side rail. A screen says so with [SportsLiveToastsOverVideo].
 */
object SportsLiveToastPlace {
    private val owners = HashSet<Any>()
    private val _overVideo = MutableStateFlow(false)
    val overVideo: StateFlow<Boolean> = _overVideo.asStateFlow()

    @Synchronized
    fun add(owner: Any) {
        owners += owner
        _overVideo.value = true
    }

    @Synchronized
    fun remove(owner: Any) {
        owners -= owner
        _overVideo.value = owners.isNotEmpty()
    }
}

/** Composed by a full-screen video screen: toasts take the player's corner while it shows. */
@Composable
fun SportsLiveToastsOverVideo() {
    val owner = remember { Any() }
    DisposableEffect(owner) {
        SportsLiveToastPlace.add(owner)
        onDispose { SportsLiveToastPlace.remove(owner) }
    }
}

/** The host laid over the whole window (MainActivity): a full-size box that draws only the card. */
@Composable
fun SportsLiveToastLayer() {
    SportsLiveToastHost()
}
