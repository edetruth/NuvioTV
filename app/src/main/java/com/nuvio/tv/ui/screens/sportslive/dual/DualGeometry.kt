package com.nuvio.tv.ui.screens.sportslive.dual

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Size

/** A pane's 16:9 tile on the dual screen, from the screen's top left corner. */
data class DualTile(val start: Dp, val top: Dp, val width: Dp) {
    val height: Dp get() = width * 9f / 16f
    val end: Dp get() = start + width
    val bottom: Dp get() = top + height
}

/**
 * Where the two tiles sit (#123), for the screen and for the alert cards placed over them, so both agree:
 * - split: two equal tiles, a little above the middle to leave [Size.DualInfoRoom] under the strips;
 * - big + small: [Side.LEFT] large (3/4 of the row) and [Side.RIGHT] small (1/4) in the top right corner,
 *   tops aligned, the pair centred with their strips. The tiles never overlap: each pane's video is its own
 *   SurfaceView, and the column under the small tile holds the game info overlay.
 */
object DualGeometry {

    fun tile(side: Side, layout: DualLayout, width: Dp, height: Dp): DualTile = when (layout) {
        DualLayout.SPLIT -> {
            val tileWidth = (width - Size.DualSideMargin * 2 - Size.DualGap) / 2
            val top = ((height - tileWidth * 9f / 16f - Size.DualCaption - Size.DualInfoRoom) / 2).coerceAtLeast(0.dp)
            val start = if (side == Side.LEFT) Size.DualSideMargin else Size.DualSideMargin + tileWidth + Size.DualGap
            DualTile(start, top, tileWidth)
        }
        DualLayout.BIG_SMALL -> {
            val row = width - Size.DualBigMargin * 2 - Size.DualGap
            val big = row * 3f / 4f
            val top = ((height - big * 9f / 16f - Size.DualCaption) / 2).coerceAtLeast(0.dp)
            if (side == Side.LEFT) DualTile(Size.DualBigMargin, top, big)
            else DualTile(Size.DualBigMargin + big + Size.DualGap, top, row - big)
        }
    }
}
