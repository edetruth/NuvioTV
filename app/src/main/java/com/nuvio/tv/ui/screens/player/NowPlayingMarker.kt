package com.nuvio.tv.ui.screens.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlin.math.PI
import kotlin.math.sin

/*
 * The now-playing marker for a list of sources (#131): the playing source is pinned first under a
 * "Now playing" header, with an accent bar on its leading edge and a three-bar equalizer before its
 * name. The focus ring stays the focus cue, so a focused playing row shows both.
 */

/** [items] with the one at [playingIndex] moved to the front; the rest keep their order, with no duplicate. */
internal fun <T> pinPlayingFirst(items: List<T>, playingIndex: Int): List<T> {
    if (playingIndex <= 0 || playingIndex > items.lastIndex) return items
    return buildList(items.size) {
        add(items[playingIndex])
        items.forEachIndexed { index, item -> if (index != playingIndex) add(item) }
    }
}

/** The equalizer moves only while the video actually plays; paused or buffering, its bars hold still. */
internal fun nowPlayingBarsAnimate(isPlaying: Boolean, isBuffering: Boolean): Boolean =
    isPlaying && !isBuffering

private val AccentBarWidth = 4.dp
private val EqualizerSize = 14.dp

/** A 4 dp [color] bar on the row's leading edge. Drawn behind the content, so it costs no layout. */
internal fun Modifier.nowPlayingAccentBar(color: Color): Modifier = drawBehind {
    val width = AccentBarWidth.toPx()
    val x = if (layoutDirection == LayoutDirection.Rtl) size.width - width else 0f
    drawRect(color = color, topLeft = Offset(x, 0f), size = Size(width, size.height))
}

/** The "Now playing" header over the pinned row. */
@Composable
internal fun NowPlayingHeader(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.sports_live_now_playing),
        modifier = modifier.padding(
            start = NuvioTheme.spacing.xs,
            bottom = NuvioTheme.spacing.xs
        ),
        style = MaterialTheme.typography.labelMedium,
        color = NuvioTheme.colors.Primary
    )
}

/**
 * The three-bar equalizer before the playing source's name. One infinite transition while [animate];
 * none otherwise, and none once the row leaves the list. The bars are redrawn, never recomposed.
 */
@Composable
internal fun NowPlayingMarker(animate: Boolean, modifier: Modifier = Modifier) {
    val color = NuvioTheme.colors.Primary
    val label = stringResource(R.string.sports_live_now_playing)
    val markerModifier = modifier
        .size(EqualizerSize)
        .semantics { contentDescription = label }
    if (animate) {
        val phase = rememberInfiniteTransition(label = "nowPlaying").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing), RepeatMode.Restart),
            label = "nowPlayingPhase"
        )
        Canvas(markerModifier) {
            drawEqualizer(color) { bar -> 0.35f + 0.65f * (0.5f + 0.5f * sin(2 * PI * (phase.value + bar / 3.0)).toFloat()) }
        }
    } else {
        Canvas(markerModifier) { drawEqualizer(color) { bar -> StaticBarHeights[bar] } }
    }
}

private val StaticBarHeights = floatArrayOf(0.55f, 1f, 0.75f)

private fun DrawScope.drawEqualizer(color: Color, heightFraction: (Int) -> Float) {
    val gap = size.width / 7f
    val barWidth = (size.width - 2 * gap) / 3f
    for (bar in 0 until 3) {
        val h = size.height * heightFraction(bar)
        val x = bar * (barWidth + gap)
        drawRoundRect(
            color = color,
            topLeft = Offset(x, size.height - h),
            size = Size(barWidth, h),
            cornerRadius = CornerRadius(barWidth / 3f)
        )
    }
}
