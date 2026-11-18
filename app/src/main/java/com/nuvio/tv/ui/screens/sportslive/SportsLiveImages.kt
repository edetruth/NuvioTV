package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest

/**
 * An image request decoded at [width] × [height] (#71), the size it is drawn at, never the source's full
 * size: decoding every card's art at full size made the native heap spike to about 200 MB when Upcoming's
 * 60 games opened. The image keeps its aspect within that box (Crop or Fit as drawn).
 */
@Composable
internal fun rememberSizedImage(data: Any?, width: Dp, height: Dp): ImageRequest {
    val context = LocalPlatformContext.current
    val density = LocalDensity.current
    val widthPx = with(density) { width.roundToPx() }.coerceAtLeast(1)
    val heightPx = with(density) { height.roundToPx() }.coerceAtLeast(1)
    return remember(data, widthPx, heightPx, context) {
        ImageRequest.Builder(context).data(data).size(widthPx, heightPx).build()
    }
}
