package com.nuvio.tv.ui.screens.sportslive.preview

import android.view.SurfaceView
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * The preview's video in the details pane: a [PlayerView] with its default SurfaceView (composited
 * by the display hardware, as in the dual screen) showing [player]'s video. It never takes focus.
 * [overVideo]: drawn over the full player's own video (the in-player panel, #83), so its surface is
 * put above that one.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun SportsLivePreviewVideo(player: SportsLivePreviewPlayer, modifier: Modifier = Modifier, overVideo: Boolean = false) {
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
                isFocusable = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                if (overVideo) (videoSurfaceView as? SurfaceView)?.setZOrderMediaOverlay(true)
            }.also(player::attach)
        },
        onRelease = { player.attach(null) },
        modifier = modifier.fillMaxSize()
    )
}

/** The small "Preview" tag on a playing preview. */
@Composable
internal fun SportsLivePreviewLabel(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.sports_live_preview_label),
        style = MaterialTheme.typography.labelMedium,
        color = NuvioTheme.colors.TextPrimary,
        modifier = modifier
            .clip(RoundedCornerShape(NuvioTheme.radii.sm))
            .background(NuvioTheme.colors.FocusBackground)
            .padding(horizontal = NuvioTheme.spacing.sm, vertical = 2.dp)
    )
}
