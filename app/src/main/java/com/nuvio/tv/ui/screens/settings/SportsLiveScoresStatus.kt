package com.nuvio.tv.ui.screens.settings

import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.scores.EspnFeedKey
import com.nuvio.tv.core.sportslive.scores.FeedFailureKind
import com.nuvio.tv.core.sportslive.scores.FeedHealth
import com.nuvio.tv.core.sportslive.scores.MyLeague
import com.nuvio.tv.core.sportslive.scores.MySport
import com.nuvio.tv.core.sportslive.scores.ScoresStatus
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.sportslive.myLeagueLabel
import com.nuvio.tv.ui.screens.sportslive.mySportLabel
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveButton
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveButtonRole
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.ui.theme.NuvioTheme
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/** The Scores status row's value (#70): its string and, for a failure, when it began (shown as a time). */
internal data class ScoresStatusLabel(@StringRes val res: Int, val sinceMs: Long? = null) {
    companion object {
        fun of(status: ScoresStatus): ScoresStatusLabel = when (status) {
            ScoresStatus.Off -> ScoresStatusLabel(R.string.sports_live_scores_status_off)
            ScoresStatus.NotChecked -> ScoresStatusLabel(R.string.sports_live_scores_status_not_checked)
            ScoresStatus.Working -> ScoresStatusLabel(R.string.sports_live_scores_status_working)
            is ScoresStatus.SomeFailing -> ScoresStatusLabel(R.string.sports_live_scores_status_some_failing, status.sinceMs)
            is ScoresStatus.NotResponding -> ScoresStatusLabel(R.string.sports_live_scores_status_not_responding, status.sinceMs)
        }

        /** A failing feed's plain-language reason. */
        @StringRes
        fun reasonOf(kind: FeedFailureKind): Int = when (kind) {
            FeedFailureKind.FORMAT -> R.string.sports_live_scores_reason_format
            FeedFailureKind.NETWORK -> R.string.sports_live_scores_reason_network
            FeedFailureKind.TIMEOUT -> R.string.sports_live_scores_reason_timeout
            FeedFailureKind.HTTP -> R.string.sports_live_scores_reason_http
        }

        /** True when [sinceMs] fell on another day than [nowMs] in [zone]: the time then shows its weekday. */
        fun showsDay(sinceMs: Long, nowMs: Long, zone: ZoneId): Boolean =
            Instant.ofEpochMilli(sinceMs).atZone(zone).toLocalDate() != Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    }
}

@Composable
internal fun scoresStatusText(status: ScoresStatus): String {
    val label = ScoresStatusLabel.of(status)
    return label.sinceMs?.let { stringResource(label.res, clockTime(it)) } ?: stringResource(label.res)
}

/** [ms] as the device writes a time of day (12 or 24 hour), after its weekday when not today ("Tue 11:50 PM"). */
@Composable
private fun clockTime(ms: Long): String {
    val time = DateFormat.getTimeFormat(LocalContext.current).format(Date(ms))
    if (!ScoresStatusLabel.showsDay(ms, System.currentTimeMillis(), ZoneId.systemDefault())) return time
    return SimpleDateFormat("EEE", Locale.getDefault()).format(Date(ms)) + " " + time
}

/** The failing feeds with their reasons, and Retry now (not while Scores is Off). */
@Composable
internal fun ScoresStatusDialog(status: ScoresStatus, onRetry: () -> Unit, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.sports_live_setting_scores_status),
        subtitle = scoresStatusText(status)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
            val lines = when (status) {
                ScoresStatus.Off -> listOf(stringResource(R.string.sports_live_scores_status_off_detail))
                ScoresStatus.NotChecked -> listOf(stringResource(R.string.sports_live_scores_status_not_checked_detail))
                ScoresStatus.Working -> listOf(stringResource(R.string.sports_live_scores_status_all_ok))
                // One line per league: today's and yesterday's scoreboards fail together.
                is ScoresStatus.SomeFailing -> status.failing.distinctBy { it.key.family to it.key.league }.map { failingLine(it) }
                is ScoresStatus.NotResponding -> status.failing.distinctBy { it.key.family to it.key.league }.map { failingLine(it) }
            }
            lines.forEach { line ->
                Text(text = line, style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary)
            }
        }
        // V2 buttons (#134): Retry now is the one PRIMARY; focus is the ring, never a white slab.
        Row(horizontalArrangement = Arrangement.spacedBy(SportsLiveTokens.Size.ButtonGap)) {
            if (status != ScoresStatus.Off) {
                SportsLiveButton(onClick = onRetry, modifier = Modifier.focusRequester(focus), role = SportsLiveButtonRole.PRIMARY) {
                    Text(stringResource(R.string.sports_live_scores_retry_now), style = SportsLiveTokens.style(SportsLiveTokens.Type.Button))
                }
            }
            SportsLiveButton(
                onClick = onDismiss,
                modifier = if (status == ScoresStatus.Off) Modifier.focusRequester(focus) else Modifier
            ) {
                Text(stringResource(R.string.action_close), style = SportsLiveTokens.style(SportsLiveTokens.Type.Button))
            }
        }
    }
}

@Composable
private fun failingLine(feed: FeedHealth): String {
    val kind = feed.lastFailure ?: FeedFailureKind.NETWORK
    val reasonRes = ScoresStatusLabel.reasonOf(kind)
    val reason = if (kind == FeedFailureKind.HTTP) stringResource(reasonRes, feed.lastHttpCode ?: 0) else stringResource(reasonRes)
    return stringResource(R.string.sports_live_scores_feed_failing, feedName(feed.key), reason, clockTime(feed.failingSince ?: feed.lastAttemptAt))
}

@Composable
private fun feedName(key: EspnFeedKey): String {
    val league = key.league ?: return stringResource(R.string.sports_live_scores_feed_game_details)
    MyLeague.ofFeed(league)?.let { return myLeagueLabel(it) }
    MySport.ofEspn(league.sport)?.let { return mySportLabel(it) }
    return league.path
}
