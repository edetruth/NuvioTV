package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The debug-only safe-area frame (#259): a 1 px red rectangle at the 48 dp left and right and 27 dp top and bottom
 * insets (Android TV layout guide), drawn over every screen so a screenshot shows at once any text or focus target
 * outside it. Toggled in Settings > Developer; never shown in a release build.
 */
object SportsLiveSafeAreaFrame {
    val shown = MutableStateFlow(false)

    /** The frame's insets in dp: sides, then top and bottom. */
    val sideDp: Float get() = SportsLiveTokens.Size.SafeEnd.value
    val topBottomDp: Float get() = SportsLiveTokens.Size.SafeTop.value

    /** Whether the frame draws: only when a debug build has it switched on. */
    fun draws(debugBuild: Boolean, on: Boolean): Boolean = debugBuild && on
}

@Composable
fun SportsLiveSafeAreaFrameLayer() {
    if (!BuildConfig.DEBUG) return
    val on by SportsLiveSafeAreaFrame.shown.collectAsStateWithLifecycle()
    if (!SportsLiveSafeAreaFrame.draws(BuildConfig.DEBUG, on)) return
    Canvas(modifier = Modifier.fillMaxSize()) {
        val side = SportsLiveSafeAreaFrame.sideDp.dp.toPx()
        val vertical = SportsLiveSafeAreaFrame.topBottomDp.dp.toPx()
        drawRect(
            color = Color.Red,
            topLeft = Offset(side, vertical),
            size = Size(size.width - 2 * side, size.height - 2 * vertical),
            style = Stroke(width = 1f)
        )
    }
}
