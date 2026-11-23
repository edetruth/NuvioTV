package com.nuvio.tv.ui.screens.sportslive.dual

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.GameStatus
import com.nuvio.tv.core.sportslive.SportsLiveChannelPicks
import com.nuvio.tv.core.sportslive.debug.ShownGamesLog
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.sportslive.ScoreboardGroup
import com.nuvio.tv.ui.screens.sportslive.ScoreboardRow
import com.nuvio.tv.ui.screens.sportslive.SportsLiveScoreboardRow
import com.nuvio.tv.ui.screens.sportslive.SportsLiveSectionHeader
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Colors
import kotlinx.coroutines.delay

private const val FOCUS_ATTEMPTS = 5
private const val FOCUS_RETRY_MS = 50L

/**
 * "Pick another to watch" (live.26, #222): the [candidates] of a dual pick, each drawn as its Live Now row ([boards]:
 * tag, logos, names, rank, score chip and status, [dualPickerBoards]) with the one focus outline, in the sections of
 * [dualPickerList] (Your teams · Same league · More live · Channels; headed from 6 rows, never focusable). No addon or
 * catalog name anywhere: "2 sources" when a game has two. [anchor]: the game the picker starts from (its league is
 * "Same league"); [channelPicks]: the profile's favourite and recent channels (no picks: no channels). The sections
 * are laid out once per open and hold still; the rows' scores and clocks update in place. A game that ended while the
 * picker was open stays in place and OK on it says so. [onFocus] gets the focused row's card key (Hide until focused
 * reveals its score), and null when the picker closes.
 */
@Composable
internal fun SportsLiveDualPickerDialog(
    candidates: List<SportsLiveItem>,
    boards: Map<String, ScoreboardRow>,
    onPick: (SportsLiveItem) -> Unit,
    onDismiss: () -> Unit,
    onFocus: (String?) -> Unit = {},
    anchor: SportsLiveItem? = null,
    scores: Map<String, ScoreSnapshot> = emptyMap(),
    channelPicks: SportsLiveChannelPicks = SportsLiveChannelPicks.NONE
) {
    // Settled (principle 3): the sections are taken when the picker opens, not on every score update.
    val list = remember(candidates, anchor, channelPicks) { dualPickerList(candidates, anchor, scores, channelPicks).also { ShownGamesLog.noteItems(ShownGamesLog.DUAL_PICKER, it.rows.map { row -> row.item }, scores) } }
    val firstFocusRequester = remember { FocusRequester() }
    var firstFocused by remember { mutableStateOf(false) }
    var endedNotice by remember { mutableStateOf(false) }
    // No row is focused once the picker closes: a dwell started on one never reveals it later.
    DisposableEffect(Unit) { onDispose { onFocus(null) } }
    // As in SportsLiveStreamPickerDialog: the first row may not be attached yet when the dialog opens.
    LaunchedEffect(Unit) {
        repeat(2) { withFrameNanos { } }
        repeat(FOCUS_ATTEMPTS) {
            runCatching { firstFocusRequester.requestFocus() }
            withFrameNanos { }
            if (firstFocused) return@LaunchedEffect
            delay(FOCUS_RETRY_MS)
        }
    }

    NuvioDialog(onDismiss = onDismiss, title = stringResource(R.string.sports_live_dual_pick), width = 640.dp) {
        // The entry is not offered with no other live game (principle 8); this only covers a list emptied meanwhile.
        if (list.rows.isEmpty()) {
            Text(
                text = stringResource(R.string.sports_live_dual_no_pair),
                style = SportsLiveTokens.style(SportsLiveTokens.Type.RowName),
                color = Colors.TextSecondary
            )
            return@NuvioDialog
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp),
            // Room for the focused row's lift and outline, never clipped by the list's edge.
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            itemsIndexed(list.rows, key = { _, row -> row.item.streamKey }) { index, row ->
                val item = row.item
                val board = boards[item.streamKey] ?: return@itemsIndexed
                Column(modifier = Modifier.fillMaxWidth()) {
                    // The section's heading rides on its first row: not a focus stop, read by TalkBack as a heading.
                    list.headerAt(index)?.let { section ->
                        SportsLiveSectionHeader(
                            label = stringResource(dualPickerSectionLabel(section)),
                            count = list.countOf(section),
                            modifier = Modifier.semantics { heading() }
                        )
                    }
                    SportsLiveScoreboardRow(
                        row = board,
                        selected = false,
                        onClick = {
                            if (dualPickerRowEnded(board)) {
                                endedNotice = true
                            } else {
                                onPick(item)
                            }
                        },
                        onLongClick = null,
                        onFocus = { onFocus(item.streamKey) },
                        modifier = if (index == 0) {
                            Modifier
                                .focusRequester(firstFocusRequester)
                                .onFocusChanged { firstFocused = it.isFocused }
                        } else {
                            Modifier
                        }
                    )
                    if (row.showsSources) {
                        Text(
                            text = pluralStringResource(R.plurals.sports_live_sources_count, row.sources, row.sources),
                            style = SportsLiveTokens.style(SportsLiveTokens.Type.Tag),
                            color = Colors.TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            // Under the names column (the league tag column and its gap sit before it).
                            modifier = Modifier.padding(start = SportsLiveTokens.Size.ColumnLeague + 10.dp, top = 2.dp)
                        )
                    }
                }
            }
        }
        if (endedNotice) {
            Text(
                text = stringResource(R.string.sports_live_dual_picker_ended),
                style = SportsLiveTokens.style(SportsLiveTokens.Type.RowName),
                color = Colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

/** A row whose game is over (it ended while the picker was open): kept in place, and OK on it starts nothing. */
internal fun dualPickerRowEnded(board: ScoreboardRow): Boolean =
    board.group == ScoreboardGroup.JUST_ENDED || board.status is GameStatus.Final || board.status is GameStatus.JustEnded

/** A section heading's words. */
internal fun dualPickerSectionLabel(section: DualPickerSection): Int = when (section) {
    DualPickerSection.YOUR_TEAMS -> R.string.sports_live_dual_picker_your_teams
    DualPickerSection.SAME_LEAGUE -> R.string.sports_live_dual_picker_same_league
    DualPickerSection.MORE_LIVE -> R.string.sports_live_dual_picker_more_live
    DualPickerSection.CHANNELS -> R.string.sports_live_tab_channels
}
