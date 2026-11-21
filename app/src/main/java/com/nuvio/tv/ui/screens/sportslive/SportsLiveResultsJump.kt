package com.nuvio.tv.ui.screens.sportslive

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Colors

/**
 * Results' **Jump to ▾** (results redesign phase 1, replacing the sport dropdown): one header pill whose menu lists
 * All leagues, ★ Your teams, the day's My sports leagues with their games, **Other sports ›** (the day's other sports
 * and leagues, fetched when opened) and Edit my sports last. Picking one narrows the list to it and focus goes to its
 * first game; Back in the list returns to all leagues. The pill reads "Jump to", or the choice while one is picked.
 */
@Composable
internal fun resultsJumpDropdown(
    state: SportsLiveResultsUiState,
    actions: ResultsActions,
    onEditMySports: () -> Unit
): SportsLiveHeaderDropdown {
    val title = stringResource(R.string.sports_live_results_jump_to)
    val face = when (val jump = state.jump) {
        null -> title
        ResultsJump.YourTeams -> stringResource(R.string.sports_live_results_jump_your_teams)
        ResultsJump.OtherSports -> stringResource(R.string.sports_live_results_other_title)
        is ResultsJump.League -> (state.jumpEntries + state.otherEntries.orEmpty())
            .firstOrNull { it.jump == jump }?.let { it.name ?: sportLabel(it.sport.orEmpty()) } ?: title
    }
    return SportsLiveHeaderDropdown(
        title = title,
        // A long competition name ("Nitto ATP Finals · Men's Singles") would push the header: the pill keeps it short.
        faceLabel = resultsJumpFace(face),
        menu = { close -> ResultsJumpMenu(state, actions, onEditMySports, close) }
    )
}

/** The pill's text: at most [RESULTS_JUMP_FACE_MAX] characters, an ellipsis ending a longer name. */
internal fun resultsJumpFace(label: String): String =
    if (label.length <= RESULTS_JUMP_FACE_MAX) label else label.take(RESULTS_JUMP_FACE_MAX - 1).trimEnd() + "…"

internal const val RESULTS_JUMP_FACE_MAX = 22

/** Jump to's menu: the first page, or Other sports (Back returns to the first page). */
@Composable
private fun ResultsJumpMenu(
    state: SportsLiveResultsUiState,
    actions: ResultsActions,
    onEditMySports: () -> Unit,
    close: () -> Unit
) {
    // Opens on Other sports when the list shows one of them, so the choice is in view.
    var otherPage by remember { mutableStateOf(state.jump == ResultsJump.OtherSports || state.jump.inOther(state)) }
    val firstFocus = remember(otherPage) { FocusRequester() }
    BackHandler(enabled = otherPage) { otherPage = false }
    if (otherPage) LaunchedEffect(Unit) { actions.loadOtherSports() }
    val pick: (ResultsJump?) -> Unit = { jump ->
        close()
        actions.jumpTo(jump)
    }
    Column(
        modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // The selected entry takes focus first, else the first one.
        val entries = if (otherPage) state.otherEntries.orEmpty() else state.jumpEntries
        val focusJump = entries.firstOrNull { it.jump == state.jump }?.jump ?: entries.firstOrNull()?.jump
        @Composable
        fun entry(entry: ResultsJumpEntry) {
            val label = entryLabel(entry)
            val selected = entry.jump == state.jump
            SportsLiveMenuButton(
                text = if (selected) stringResource(R.string.sports_live_dropdown_selected, label) else label,
                onClick = { pick(entry.jump) },
                followed = entry.jump == ResultsJump.YourTeams,
                modifier = if (entry.jump == focusJump && entries.isNotEmpty()) Modifier.focusRequester(firstFocus) else Modifier
            )
        }
        if (otherPage) {
            when {
                // M3: an ESPN failure is said as such, with a focusable Retry; never "No games".
                state.otherFailed -> {
                    QuietLine(stringResource(R.string.sports_live_results_other_failed))
                    SportsLiveMenuButton(
                        text = stringResource(R.string.action_retry),
                        onClick = actions::retryOtherSports,
                        modifier = Modifier.focusRequester(firstFocus)
                    )
                }
                state.otherEntries == null || entries.isEmpty() -> {
                    QuietLine(
                        stringResource(
                            if (state.otherEntries == null) R.string.sports_live_results_other_loading else R.string.sports_live_results_other_none
                        )
                    )
                    // Something to hold focus while it loads or when there is nothing (M3): Back to the first page.
                    SportsLiveMenuButton(
                        text = stringResource(R.string.action_back),
                        onClick = { otherPage = false },
                        modifier = Modifier.focusRequester(firstFocus)
                    )
                }
                else -> entries.forEach { entry(it) }
            }
        } else {
            entries.forEach { entry(it) }
            SportsLiveMenuButton(
                text = stringResource(R.string.sports_live_results_jump_other),
                onClick = { otherPage = true },
                modifier = if (entries.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier
            )
            SportsLiveMenuButton(
                text = stringResource(R.string.sports_live_results_edit_my_sports),
                onClick = {
                    close()
                    onEditMySports()
                }
            )
        }
        // Focus once the page is drawn, and again each time Other sports changes what it shows (loading, failed,
        // none, its entries): the button that held focus is gone then (M3).
        val shows = when {
            !otherPage -> OtherSportsShows.FIRST_PAGE
            state.otherFailed -> OtherSportsShows.FAILED
            state.otherEntries == null -> OtherSportsShows.LOADING
            entries.isEmpty() -> OtherSportsShows.NONE
            else -> OtherSportsShows.ENTRIES
        }
        LaunchedEffect(shows) {
            withFrameNanos { }
            runCatching { firstFocus.requestFocus() }
        }
    }
}

/** What Jump to's menu shows, for its focus: the first page, or Other sports loading, failed, empty or listed. */
private enum class OtherSportsShows { FIRST_PAGE, LOADING, FAILED, NONE, ENTRIES }

/** A choice's text: "All leagues · 23", "★ Your teams · 2", "NBA · 9", "All other sports · 41". */
@Composable
private fun entryLabel(entry: ResultsJumpEntry): String {
    val name = when (val jump = entry.jump) {
        null -> stringResource(R.string.sports_live_results_jump_all)
        ResultsJump.YourTeams -> stringResource(R.string.sports_live_results_jump_your_teams)
        ResultsJump.OtherSports -> stringResource(R.string.sports_live_results_other_all)
        is ResultsJump.League -> entry.name ?: sportLabel(entry.sport.orEmpty())
    }
    return stringResource(R.string.sports_live_results_jump_entry, name, entry.count)
}

@Composable
private fun QuietLine(text: String) {
    Text(
        text = text,
        style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta),
        color = Colors.TextSecondary,
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
    )
}

/** True when this jump is a league of Other sports (not on Jump to's first page). */
private fun ResultsJump?.inOther(state: SportsLiveResultsUiState): Boolean =
    this is ResultsJump.League && state.jumpEntries.none { it.jump == this }
