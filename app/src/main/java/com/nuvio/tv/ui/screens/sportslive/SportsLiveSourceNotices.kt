package com.nuvio.tv.ui.screens.sportslive

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.SportsLiveSourceCooldown
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Whether the Sources panel lists [stream] as a notice row (QA C1): an addon's informational entry or
 * junk link, for Sports & Live playback only. Movies and shows list every stream as before.
 */
internal fun sportsLiveSourceNotice(contentId: String?, stream: Stream): Boolean =
    SportsLiveExtraSources.isSportsPlayback(contentId) && SportsLiveZapResolver.isNonStream(stream)

/** The caption above a notice row: the entry is the source's message, not a stream to play. */
@Composable
internal fun SportsLiveSourceNoticeLabel() {
    Text(
        text = stringResource(R.string.sports_live_source_notice),
        style = MaterialTheme.typography.labelMedium,
        color = Color.White.copy(alpha = 0.6f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.xs)
    )
}

/**
 * Shows each rate-limit cool-down once, while the screen is started: "‹source› is rate-limited, try
 * again in N min" ([SportsLiveSourceCooldown.announcements]).
 */
@Composable
internal fun SportsLiveCooldownToasts(cooldown: SportsLiveSourceCooldown = SportsLiveSourceCooldown.shared) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(cooldown, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            cooldown.announcements.collect { notice ->
                Toast.makeText(
                    context,
                    context.getString(R.string.sports_live_source_rate_limited, notice.source, notice.minutes),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
