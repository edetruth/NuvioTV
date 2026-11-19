package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.SportsLiveNoticeEntries
import com.nuvio.tv.core.sportslive.SportsLiveSourceCooldown
import com.nuvio.tv.core.sportslive.SportsLiveToastKind
import com.nuvio.tv.core.sportslive.SportsLiveToasts
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Whether a stream list lists [stream] as a notice row (QA C1, N2): an addon's informational entry
 * (a tip, a limit, an upsell) or junk link, for Sports & Live playback only. Movies and shows list every
 * stream as before.
 */
internal fun sportsLiveSourceNotice(contentId: String?, stream: Stream): Boolean =
    SportsLiveExtraSources.isSportsPlayback(contentId) && SportsLiveZapResolver.isNoticeEntry(stream)

/**
 * [streams] with the entries [isNotice] flags moved after the others, each part in its own order (N2):
 * a list never opens on, or leads with, a source's message.
 */
internal fun <T> sportsLiveNoticesLast(streams: List<T>, isNotice: (T) -> Boolean): List<T> {
    val (notices, others) = streams.partition(isNotice)
    return if (notices.isEmpty()) streams else others + notices
}

/**
 * The full-screen Stream screen's list for [videoId] (N2): a Sports & Live game's notices last; any
 * other title's list as is.
 */
internal fun sportsLiveStreamScreenOrder(videoId: String?, streams: List<Stream>): List<Stream> =
    if (!SportsLiveExtraSources.isSportsPlayback(videoId)) streams
    else sportsLiveNoticesLast(streams, SportsLiveZapResolver::isNoticeEntry)

/**
 * A source's message in a stream list (N2): "Message from the source · not a stream" over its text,
 * dimmed, never focusable and never playable.
 */
@Composable
internal fun SportsLiveSourceNoticeRow(stream: Stream, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NuvioTheme.radii.md))
            .background(NuvioTheme.colors.BackgroundCard.copy(alpha = NOTICE_ROW_ALPHA))
            .padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
    ) {
        Text(
            text = stringResource(R.string.sports_live_source_notice),
            style = MaterialTheme.typography.labelMedium,
            color = NuvioTheme.colors.TextTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = SportsLiveNoticeEntries.text(stream).ifEmpty { stream.addonName },
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private const val NOTICE_ROW_ALPHA = 0.5f

/**
 * Shows each rate-limit cool-down once, while the screen is started: "‹source› is rate-limited, try
 * again in N min" ([SportsLiveSourceCooldown.announcements]); several sources share one toast.
 */
@Composable
internal fun SportsLiveCooldownToasts(cooldown: SportsLiveSourceCooldown = SportsLiveSourceCooldown.shared) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(cooldown, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            cooldown.announcements.collect { notice ->
                val title = if (notice.sources.size == 1) {
                    context.getString(R.string.sports_live_source_busy, notice.sources.single())
                } else {
                    context.getString(R.string.sports_live_sources_busy, notice.sources.joinToString(", "))
                }
                SportsLiveToasts.show(
                    SportsLiveToastKind.PROBLEM,
                    title,
                    context.getString(R.string.sports_live_sources_busy_line, notice.minutes)
                )
            }
        }
    }
}
