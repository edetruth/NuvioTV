package com.nuvio.tv.ui.screens.sportslive.dual

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
import com.nuvio.tv.ui.screens.sportslive.ZapPanelRow
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

private const val FOCUS_ATTEMPTS = 5
private const val FOCUS_RETRY_MS = 50L

/**
 * "Pick a second game": the [candidates] of a [SportsLiveDualPick]. Picking one starts dual screen; Back
 * dismisses. Each game shows its logos and score chip from its player panel row in [rows] (M11), so the
 * panel's spoiler rules hold here too: a hidden score stays hidden.
 */
@Composable
internal fun SportsLiveDualPickerDialog(
    candidates: List<SportsLiveItem>,
    rows: Map<String, ZapPanelRow>,
    onPick: (SportsLiveItem) -> Unit,
    onDismiss: () -> Unit
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
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            itemsIndexed(candidates, key = { _, item -> item.streamKey }) { index, item ->
                Button(
                    onClick = { onPick(item) },
                    modifier = (if (index == 0) firstModifier else Modifier).fillMaxWidth(),
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundCard,
                        contentColor = NuvioTheme.colors.TextPrimary
                    )
                ) {
                    val row = rows[item.streamKey]
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
                    ) {
                        // Logos only when both teams have one and both loaded (#130); never letters.
                        rememberTeamLogoPair(row?.teams)?.let { logos -> TeamLogoPairSlot(logos, 28.dp, NuvioTheme.spacing.xs) }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.meta.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                text = "${item.sport ?: item.catalogName} · ${item.addonName}",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            row?.chip?.let { SportsLiveScoreChip(chip = it, modifier = Modifier.padding(top = NuvioTheme.spacing.xxs)) }
                        }
                    }
                }
            }
        }
    }
}
