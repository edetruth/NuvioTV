package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
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
    val shape = RoundedCornerShape(SportsLiveTokens.Toast.Radius)
    Row(
        modifier = modifier
            .width(SportsLiveTokens.Toast.Width)
            .background(Colors.SurfaceRaised, shape)
            .border(1.dp, Colors.OutlineStrong.copy(alpha = 0.6f), shape)
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
 * Where every Sports & Live toast shows (#134): top right, in the alert cards' spot (#136), over every screen
 * and the player. One at a time, the newest replacing the one up ([SportsLiveToastQueue]), each for
 * [SportsLiveToastQueue.SHOW_MS]. It never takes focus: the screen under it keeps the D-pad.
 */
@Composable
fun BoxScope.SportsLiveToastHost(queue: SportsLiveToastQueue = SportsLiveToasts.shared) {
    val toast by queue.current.collectAsStateWithLifecycle()
    LaunchedEffect(toast?.id) {
        val id = toast?.id ?: return@LaunchedEffect
        delay(SportsLiveToastQueue.SHOW_MS)
        queue.dismiss(id)
    }
    // The last toast stays composed while it fades out.
    val shown = remember { ToastHolder() }
    toast?.let { shown.last = it }
    AnimatedVisibility(
        visible = toast != null,
        enter = fadeIn(tween(SportsLiveTokens.Toast.ENTER_MS)) +
            slideInHorizontally(tween(SportsLiveTokens.Toast.ENTER_MS)) { width -> width / 4 },
        exit = fadeOut(tween(SportsLiveTokens.Toast.ENTER_MS)),
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(top = SportsLiveTokens.Toast.Top, end = SportsLiveTokens.Toast.End)
    ) {
        shown.last?.let { SportsLiveToastCard(it) }
    }
}

private class ToastHolder {
    var last: SportsLiveToast? = null
}

/** The host laid over the whole window (MainActivity): a full-size box that draws only the card. */
@Composable
fun SportsLiveToastLayer() {
    Box(modifier = Modifier.fillMaxSize()) { SportsLiveToastHost() }
}
