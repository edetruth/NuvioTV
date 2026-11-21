package com.nuvio.tv.ui.screens.sportslive.dual

import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.displayName
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.sportslive.SportsLiveScoreChip
import com.nuvio.tv.ui.screens.sportslive.TeamLogoPairSlot
import com.nuvio.tv.ui.screens.sportslive.rememberTeamLogoPair
import com.nuvio.tv.ui.screens.sportslive.GameIdentities
import com.nuvio.tv.ui.screens.sportslive.panelPlaysHalves
import com.nuvio.tv.core.sportslive.GameStatus
import com.nuvio.tv.ui.screens.sportslive.rememberSportWord
import com.nuvio.tv.ui.screens.sportslive.ZapPanelRow
import com.nuvio.tv.ui.screens.sportslive.groupPanelRows
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

private const val FOCUS_ATTEMPTS = 5
private const val FOCUS_RETRY_MS = 50L

/**
 * "Pick a second game": the [candidates] of a [SportsLiveDualPick]. Picking one starts dual screen; Back
 * dismisses. Each game shows its logos and score chip from its panel row in [rows] ([dualPickerRows], M11), so
 * the panel's spoiler rules hold here too: a hidden score stays hidden. [onFocus] gets the focused game's card key
 * (Hide until focused reveals its score), and null when the picker closes.
 */
@Composable
internal fun SportsLiveDualPickerDialog(
    candidates: List<SportsLiveItem>,
    rows: Map<String, ZapPanelRow>,
    onPick: (SportsLiveItem) -> Unit,
    onDismiss: () -> Unit,
    onFocus: (String?) -> Unit = {}
) {
    val firstFocusRequester = remember { FocusRequester() }
    val firstFocused = remember { mutableStateOf(false) }
    // As in SportsLiveStreamPickerDialog: the first row may not be attached yet when the dialog opens.
    LaunchedEffect(Unit) {
        repeat(2) { withFrameNanos { } }
        repeat(FOCUS_ATTEMPTS) {
            runCatching { firstFocusRequester.requestFocus() }
            withFrameNanos { }
            if (firstFocused.value) return@LaunchedEffect
            delay(FOCUS_RETRY_MS)
        }
    }
    val firstModifier = Modifier
        .focusRequester(firstFocusRequester)
        .onFocusChanged { firstFocused.value = it.isFocused }
    val sportWord = rememberSportWord()

    NuvioDialog(onDismiss = onDismiss, title = stringResource(R.string.sports_live_dual_pick), width = 640.dp) {
        if (candidates.isEmpty()) {
            Text(
                text = stringResource(R.string.sports_live_zap_panel_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = NuvioTheme.colors.TextSecondary,
                modifier = firstModifier.focusable()
            )
            return@NuvioDialog
        }
        // Live games first, channels last under their own heading (the games are what is picked most).
        val (games, channels) = remember(candidates) { dualPickerSections(candidates) }
        val ordered = games + channels
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            itemsIndexed(ordered, key = { _, item -> item.streamKey }) { index, item ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (index == games.size && games.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.sports_live_tab_channels),
                            style = MaterialTheme.typography.labelLarge,
                            color = NuvioTheme.colors.TextSecondary,
                            modifier = Modifier.padding(top = NuvioTheme.spacing.sm, bottom = NuvioTheme.spacing.xxs)
                        )
                    }
                    Button(
                        onClick = { onPick(item) },
                        modifier = (if (index == 0) firstModifier else Modifier)
                            .fillMaxWidth()
                            .onFocusChanged { if (it.isFocused) onFocus(item.streamKey) },
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioTheme.colors.BackgroundCard,
                            contentColor = NuvioTheme.colors.TextPrimary
                        )
                    ) {
                        val row = rows[item.streamKey]
                        // A game's tag is the one game identity's (live.24): Live Now's "NCAAM", not the addon's sport word.
                        val identity = remember(item, row, sportWord) {
                            if (item.isChannel) null else GameIdentities.of(item, null, row?.chip, sportWord, art = row?.teams)
                        }
                        val tag = identity?.tag?.takeIf { it.isNotEmpty() } ?: SportsLiveTeams.sportLabelOf(item)
                        // The game grammar's matchup (live.24): full names, "at" only when the first side is away.
                        val first = identity?.first
                        val second = identity?.second
                        val title = if (identity != null && first != null && second != null) {
                            stringResource(
                                if (identity.saysAt) R.string.sports_live_matchup_at else R.string.sports_live_matchup,
                                first.full,
                                second.full
                            )
                        } else {
                            item.displayName
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
                        ) {
                            // Logos only when both teams have one and both loaded (#130); never letters.
                            rememberTeamLogoPair(row?.teams)?.let { logos -> TeamLogoPairSlot(logos, 28.dp, NuvioTheme.spacing.xs) }
                            Column(modifier = Modifier.weight(1f)) {
                                // The whole name, on two lines when long (live.23 TV test): never cut.
                                Text(title)
                                Text(
                                    text = listOfNotNull(tag, item.addonName).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                row?.chip?.let { SportsLiveScoreChip(chip = it, halves = panelPlaysHalves((row?.status as? GameStatus.Live)?.event?.league), modifier = Modifier.padding(top = NuvioTheme.spacing.xxs)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The picker's candidates in two sections: the games first, then the channels, each in the order given (the
 * games are what a second pick is for; channels go last, under their own heading).
 */
internal fun dualPickerSections(candidates: List<SportsLiveItem>): Pair<List<SportsLiveItem>, List<SportsLiveItem>> =
    candidates.partition { !it.isChannel }

/**
 * The picker's rows by card key (live.23 TV test: they showed no logos and no score): each candidate's logos and
 * score chip as the live games panel builds them ([groupPanelRows]), so the Scores setting holds: Show the score,
 * Hide until focused a hidden chip until the row has been focused ([revealed]), Off no chip at all. A spoiler-
 * protected game (followed team's results hidden, Watch later) stays hidden whatever the setting.
 */
internal fun dualPickerRows(
    candidates: List<SportsLiveItem>,
    scores: Map<String, ScoreSnapshot>,
    mode: ScoresMode?,
    revealed: Set<String>,
    nowMs: Long
): Map<String, ZapPanelRow> =
    groupPanelRows(candidates, scores, mode, revealed, nowMs).associateBy { it.item.streamKey }
