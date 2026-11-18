package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

/**
 * Scrolling in [content] keeps a focused row about [margin] from the list's top and bottom edges (V2,
 * #132: two rows), instead of letting it touch the edge where the fade sits ([sportsLiveKeepFromEdge]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SportsLiveKeepFromEdge(margin: Dp, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val default = LocalBringIntoViewSpec.current
    val spec = remember(density, default, margin) {
        val marginPx = with(density) { margin.toPx() }
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        object : BringIntoViewSpec {
            override val scrollAnimationSpec: AnimationSpec<Float> = default.scrollAnimationSpec

            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                sportsLiveKeepFromEdge(offset, size, containerSize, marginPx.coerceAtMost(containerSize / 3f))
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/** The list's top and bottom edges fade over [fade] while there is more to scroll that way (V2), never a hard cut. */
internal fun Modifier.sportsLiveFadingEdges(state: LazyListState, fade: Dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val top = state.canScrollBackward
        val bottom = state.canScrollForward
        if (!top && !bottom) return@drawWithContent
        val share = (fade.toPx() / size.height).coerceIn(0f, 0.5f)
        drawRect(
            brush = Brush.verticalGradient(
                0f to (if (top) Color.Transparent else Color.Black),
                share to Color.Black,
                1f - share to Color.Black,
                1f to (if (bottom) Color.Transparent else Color.Black)
            ),
            blendMode = BlendMode.DstIn
        )
    }
