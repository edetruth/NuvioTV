package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.nuvio.tv.ui.screens.stream.StreamPlaybackInfo
import androidx.compose.ui.unit.dp
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens

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
 * The full-screen Stream screen's list for [videoId] (N2): a Sports & Live game's notices last; a spoiler-safe replay's
 * links score-masked, an episode of a hidden replay folder opened from its Detail page too (#240); any other title's
 * list as is.
 */
internal fun sportsLiveStreamScreenOrder(videoId: String?, streams: List<Stream>, contentId: String? = null): List<Stream> {
    // By the video or its content (H-F1: an episode of a hidden folder not registered yet).
    val id = listOf(videoId, contentId).firstOrNull(SportsLiveExtraSources::isSpoilerSafe) ?: videoId
    val shown = SportsLiveExtraSources.spoilerSafeStreams(id, streams)
    val sports = SportsLiveExtraSources.isSportsPlayback(videoId) || SportsLiveExtraSources.isSportsPlayback(contentId)
    return if (!sports) shown else sportsLiveNoticesLast(shown, SportsLiveZapResolver::isNoticeEntry)
}

/**
 * [info], a link the Stream screen hands the player (picked there, auto-picked or the last link reused), as the player
 * names it: for a spoiler-safe replay (#223) its link name and description with any score masked; any other as is.
 */
internal fun sportsLiveSpoilerSafePlayback(info: StreamPlaybackInfo): StreamPlaybackInfo =
    // By its video, or its content (S4: an episode of a hidden folder not registered yet).
    if (!SportsLiveExtraSources.isSpoilerSafe(info.videoId) && !SportsLiveExtraSources.isSpoilerSafe(info.contentId)) info
    else info.copy(
        streamName = spoilerSafeLinkText(info.streamName) ?: info.streamName,
        streamDescription = spoilerSafeLinkText(info.streamDescription)
    )

/**
 * A source's message in a stream list (N2): one small dimmed line, "Note from the source: ‹its text in words›" (V2,
 * live.20: no emoji; TV QA live.20: the two-to-four-line card was as big as a real stream), never focusable and never
 * playable. [header]: the Sources panel's group header over the first one.
 */
@Composable
internal fun SportsLiveSourceNoticeRow(stream: Stream, modifier: Modifier = Modifier, header: SportsLiveSourceGroup? = null) {
    if (header != null) {
        Column(modifier = modifier.fillMaxWidth()) {
            SportsLiveSourceGroupHeader(header)
            SportsLiveSourceNoticeRow(stream)
        }
        return
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(SportsLiveTokens.Radius.RowShape)
            .background(SportsLiveTokens.Colors.Row.copy(alpha = NOTICE_ROW_ALPHA))
            .padding(horizontal = 14.dp, vertical = 4.dp)
    ) {
        Text(
            text = stringResource(
                R.string.sports_live_source_notice_line,
                sportsLivePlainSourceName(SportsLiveNoticeEntries.text(stream).ifEmpty { stream.addonName })
            ),
            style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta),
            color = SportsLiveTokens.Colors.TextMuted,
            maxLines = 1,
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
