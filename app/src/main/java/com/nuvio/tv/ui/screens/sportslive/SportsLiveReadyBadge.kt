package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.SportsLiveGameReadiness
import com.nuvio.tv.core.sportslive.SportsLiveMyGamesToday
import com.nuvio.tv.core.sportslive.SportsLiveReadyMark
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Colors

/*
 * Readiness in words (live.20 1b), for every Sports & Live surface. ✓ means Watched / Selected in Nuvio, so a game
 * whose verified link is held reads "▶ Ready" (a green outlined pill, never the filled Replay pill) and one with no
 * working stream "No stream yet" (muted text: the panel, the kickoff prompt, and a dimmed Live Now row whose own
 * lookup found nothing playable (BUG2); never as a guess on rows nobody looked up).
 *
 * Reading an item's mark: [rememberSportsLiveReadyMark] in a composable, or SportsLiveGameReadiness.shared.of(item)
 * (marks by game id: SportsLiveGameReadiness.shared.marks). READY stands only while the verified link is held; OK on
 * the game plays that link (the resolver's lookup serves it first).
 */

/**
 * "▶ Ready": the game holds a link verified live (1b). Tag size, green outline on a green tint, one line. On a
 * [stage] it is spelled out once, "▶ Stream ready" (research 6); rows keep the short pill.
 */
@Composable
fun SportsLiveReadyPill(modifier: Modifier = Modifier, stage: Boolean = false) {
    val shape = SportsLiveTokens.Radius.PillShape
    Text(
        text = stringResource(if (stage) R.string.sports_live_stage_stream_ready else R.string.sports_live_ready_pill),
        style = SportsLiveTokens.style(SportsLiveTokens.Type.Tag).sportsLiveBold(),
        color = Colors.ReplayGreen,
        maxLines = 1,
        softWrap = false,
        modifier = modifier
            .clip(shape)
            .background(Colors.ReplayGreen.copy(alpha = READY_TINT_ALPHA))
            .border(1.dp, Colors.ReplayGreen.copy(alpha = READY_OUTLINE_ALPHA), shape)
            .padding(horizontal = 6.dp, vertical = 1.dp)
    )
}

/** "No stream yet": no working stream was found (1b). Muted, calm: never the alarming ⚠. Panel and prompt only. */
@Composable
fun SportsLiveNoStreamYet(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.sports_live_no_stream_yet_mark),
        style = SportsLiveTokens.style(SportsLiveTokens.Type.Tag),
        color = Colors.TextMuted,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

/**
 * A link check of the row's game is running (live.20): a faint "…" at tag size, quieter than "No stream yet", so a game
 * not checked yet reads apart from one checked with no stream. Still (no animation): a check takes seconds, and a
 * pulsing mark on many rows would pull the eye on a 10-ft screen. Never larger than the marks it stands in for.
 */
@Composable
fun SportsLiveCheckingMark(modifier: Modifier = Modifier) {
    val description = stringResource(R.string.sports_live_checking_description)
    Text(
        text = stringResource(R.string.sports_live_checking_mark),
        style = SportsLiveTokens.style(SportsLiveTokens.Type.Tag),
        color = Colors.TextMuted.copy(alpha = CHECKING_ALPHA),
        maxLines = 1,
        softWrap = false,
        modifier = modifier.semantics { contentDescription = description }
    )
}

/** True while a link check of [item] runs ([SportsLiveGameReadiness.checking]), kept fresh as checks start and end. */
@Composable
fun rememberSportsLiveChecking(item: SportsLiveItem, readiness: SportsLiveGameReadiness = SportsLiveGameReadiness.shared): Boolean {
    val checking by readiness.checking.collectAsState()
    return remember(checking, item) { readiness.isChecking(item) }
}

/**
 * [mark] drawn: "▶ Ready" for READY; "No stream yet" for NO_STREAM only where [showNoStream] (the in-player panel,
 * the kickoff prompt); nothing otherwise.
 */
@Composable
fun SportsLiveReadyMarkView(mark: SportsLiveReadyMark?, modifier: Modifier = Modifier, showNoStream: Boolean = false) {
    when (mark) {
        SportsLiveReadyMark.READY -> SportsLiveReadyPill(modifier)
        SportsLiveReadyMark.NO_STREAM -> if (showNoStream) SportsLiveNoStreamYet(modifier)
        null -> Unit
    }
}

/** [item]'s readiness mark, kept fresh as marks change (1b); null: none. */
@Composable
fun rememberSportsLiveReadyMark(item: SportsLiveItem, readiness: SportsLiveGameReadiness = SportsLiveGameReadiness.shared): SportsLiveReadyMark? {
    val marks by readiness.marks.collectAsState()
    return remember(marks, item) { readiness.of(item) }
}

/** Home's quiet line (1b extra a): "2 games tonight · 1 ready" ("· N ready" only when one is). */
@Composable
fun sportsLiveMyGamesTodayText(today: SportsLiveMyGamesToday): String {
    val context = LocalContext.current
    // "today" before 5 pm, "tonight" from 5 pm (live.20, as Your teams' heading).
    val plural = if (YourTeams.isEvening(System.currentTimeMillis())) R.plurals.sports_live_my_games_tonight else R.plurals.sports_live_my_games_today
    val games = context.resources.getQuantityString(plural, today.games, today.games)
    if (today.ready <= 0) return games
    return listOf(games, context.getString(R.string.sports_live_my_games_ready, today.ready))
        .joinToString(stringResource(R.string.sports_live_game_info_separator))
}

private const val READY_TINT_ALPHA = 0.16f
private const val READY_OUTLINE_ALPHA = 0.6f
private const val CHECKING_ALPHA = 0.7f
