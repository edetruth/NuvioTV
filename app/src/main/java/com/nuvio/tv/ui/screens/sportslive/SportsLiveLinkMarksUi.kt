package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.SportsLiveLinkFailure
import com.nuvio.tv.core.sportslive.SportsLiveLinkGroups
import com.nuvio.tv.core.sportslive.SportsLiveLinkMark
import com.nuvio.tv.core.sportslive.SportsLiveLinkMarks
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

/** How often the marks' ages ("3 min ago") are brought up to date while a list shows. */
private const val AGE_TICK_MS = 30_000L

/** A failed link's row is drawn at this alpha (#156); focus still shows at full strength. */
internal const val SPORTS_LIVE_FAILED_LINK_ALPHA = 0.5f

/**
 * One stream list's view of a game's link marks (#156, #166), taken at [nowMs]: what each link did,
 * and whether protected links are hidden.
 */
@Stable
internal class SportsLiveLinkMarksView(
    private val gameKey: String,
    private val marks: SportsLiveLinkMarks,
    val hideProtected: Boolean,
    val nowMs: Long
) {
    fun markOf(stream: Stream): SportsLiveLinkMark? = marks.markOf(gameKey, stream.getStreamUrl())

    /** [streams] in the marks' order: working and untried, then "Didn't work", then [isNotice] rows. */
    fun group(
        streams: List<Stream>,
        isNotice: (Stream) -> Boolean = { false },
        keep: (Stream) -> Boolean = { false }
    ): SportsLiveLinkGroups<Stream> = SportsLiveLinkGroups.of(streams, ::markOf, hideProtected, isNotice, keep)
}

/**
 * The marks of [gameKey]'s links for a stream list, kept current (a failure, a play, the hide setting,
 * the age ticking on); null when [gameKey] is not a Sports & Live game, so every other list is as before.
 * [knownSports]: the caller lists a Sports & Live game for sure (its picker), whether or not it played yet.
 */
@Composable
internal fun rememberSportsLiveLinkMarks(
    gameKey: String?,
    knownSports: Boolean = false,
    marks: SportsLiveLinkMarks = SportsLiveLinkMarks.shared
): SportsLiveLinkMarksView? {
    val revision by marks.revision.collectAsState()
    val hide by marks.hideUnplayable.collectAsState()
    var now by remember { mutableLongStateOf(marks.nowMs()) }
    LaunchedEffect(marks) {
        while (true) {
            delay(AGE_TICK_MS)
            now = marks.nowMs()
        }
    }
    val sports = gameKey != null && (knownSports || SportsLiveExtraSources.isSportsPlayback(gameKey))
    return remember(gameKey, sports, revision, hide, now) {
        if (sports && gameKey != null) SportsLiveLinkMarksView(gameKey, marks, hide, now) else null
    }
}

/** The text of [mark] at [nowMs]: "Didn't start · 3 min ago", "Link dead", "Played before"… */
@Composable
internal fun sportsLiveLinkMarkText(mark: SportsLiveLinkMark, nowMs: Long): String = when (mark) {
    SportsLiveLinkMark.Played -> stringResource(R.string.sports_live_link_played)
    is SportsLiveLinkMark.Failed -> {
        val reason = stringResource(
            when (mark.reason) {
                SportsLiveLinkFailure.DIDNT_START -> R.string.sports_live_link_didnt_start
                SportsLiveLinkFailure.STOPPED -> R.string.sports_live_link_stopped
                SportsLiveLinkFailure.DEAD -> R.string.sports_live_link_dead
                SportsLiveLinkFailure.PROTECTED -> R.string.sports_live_link_protected
                SportsLiveLinkFailure.UNSUPPORTED -> R.string.sports_live_link_unsupported
                SportsLiveLinkFailure.REFUSED -> R.string.sports_live_link_refused
                SportsLiveLinkFailure.EXPIRED -> R.string.sports_live_link_expired
            }
        )
        if (!mark.reason.showsAge) {
            stringResource(R.string.sports_live_link_mark_warning, reason)
        } else {
            val minutes = mark.minutesAgo(nowMs)
            val age = if (minutes < 1) {
                stringResource(R.string.sports_live_link_age_now)
            } else {
                stringResource(R.string.sports_live_link_age_minutes, minutes)
            }
            stringResource(R.string.sports_live_link_mark_warning_with_age, reason, age)
        }
    }
}

/** A link's mark under its name: an amber ⚠ and reason for a failure, a subtle "Played before" otherwise. */
@Composable
internal fun SportsLiveLinkMarkLine(mark: SportsLiveLinkMark, nowMs: Long, modifier: Modifier = Modifier) {
    Text(
        text = sportsLiveLinkMarkText(mark, nowMs),
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = if (mark is SportsLiveLinkMark.Failed) NuvioTheme.colors.Warning else NuvioTheme.colors.TextTertiary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/** The "Didn't work" header over a list's trailing group of failed links. */
@Composable
internal fun SportsLiveDidntWorkHeader(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.sports_live_links_didnt_work),
        modifier = modifier.padding(start = NuvioTheme.spacing.xs, top = NuvioTheme.spacing.sm, bottom = NuvioTheme.spacing.xs),
        style = MaterialTheme.typography.labelMedium,
        color = NuvioTheme.colors.TextSecondary
    )
}
