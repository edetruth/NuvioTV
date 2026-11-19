package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.SportsLiveStreamThresholds
import com.nuvio.tv.ui.screens.player.PlayerOverlayButton
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

/** The starting card's buttons (#138), top to bottom; the first one is focused (or selected) first. */
enum class SportsLiveStartingAction { TRY_NEXT_LINK, SOURCES, BACK }

/** "0:07": the time a link has been starting, once it is [SportsLiveStreamThresholds.STARTING_ELAPSED_AFTER_MS] or more. */
internal fun sportsLiveStartingElapsed(elapsedMs: Long): String? {
    if (elapsedMs < SportsLiveStreamThresholds.STARTING_ELAPSED_AFTER_MS) return null
    val seconds = elapsedMs / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/**
 * The compact card shown while a sports link starts (#138): the game, "Starting link N of M · source",
 * the elapsed time after a few seconds, and Try next link / Sources / Back.
 *
 * With [selected] null the buttons are real, focusable buttons and Try next link takes focus (the
 * full player). With [selected] set the card draws that button as selected and takes no focus: its
 * owner moves the selection with the keys (a dual screen pane). [linkCount] 0 leaves "of M" out.
 * [onAction] is only called by the focusable buttons; a key-driven owner acts on [selected] itself.
 */
@Composable
fun SportsLiveStartingCard(
    game: String?,
    linkNumber: Int,
    linkCount: Int,
    sourceName: String?,
    startedAtMs: Long,
    onAction: (SportsLiveStartingAction) -> Unit,
    modifier: Modifier = Modifier,
    selected: SportsLiveStartingAction? = null,
    compact: Boolean = false,
    /** False: the game, link and time only (a dual pane that is not highlighted). */
    showActions: Boolean = true
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAtMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val link = if (linkCount >= linkNumber) {
        stringResource(R.string.sports_live_starting_link_of, linkNumber, linkCount)
    } else {
        stringResource(R.string.sports_live_starting_link, linkNumber)
    }
    val line = sourceName?.takeIf(String::isNotBlank)?.let { "$link · $it" } ?: link
    val elapsed = sportsLiveStartingElapsed(now - startedAtMs)
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    Column(
        verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .widthIn(max = if (compact) 320.dp else 420.dp)
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(horizontal = if (compact) 14.dp else 24.dp, vertical = if (compact) 10.dp else 20.dp)
    ) {
        game?.takeIf(String::isNotBlank)?.let {
            Text(
                text = it,
                style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = line,
            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (elapsed != null) {
            Text(
                text = elapsed,
                style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelLarge,
                color = NuvioTheme.colors.TextSecondary
            )
        }
        val labels = mapOf(
            SportsLiveStartingAction.TRY_NEXT_LINK to stringResource(R.string.sports_live_try_next_link),
            SportsLiveStartingAction.SOURCES to stringResource(R.string.sources_title),
            SportsLiveStartingAction.BACK to stringResource(R.string.action_back)
        )
        if (!showActions) {
            Unit
        } else if (selected == null) {
            val firstFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
            SportsLiveStartingAction.entries.forEach { action ->
                PlayerOverlayButton(
                    text = labels.getValue(action),
                    onClick = { onAction(action) },
                    primary = action == SportsLiveStartingAction.TRY_NEXT_LINK,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (action == SportsLiveStartingAction.TRY_NEXT_LINK) Modifier.focusRequester(firstFocus) else Modifier
                        )
                )
            }
        } else {
            SportsLiveStartingAction.entries.forEach { action ->
                val isSelected = action == selected
                val chipShape = RoundedCornerShape(NuvioTheme.radii.sm)
                Text(
                    text = labels.getValue(action),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) Color.Black else NuvioTheme.colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(chipShape)
                        .background(if (isSelected) Color.White else NuvioTheme.colors.BackgroundCard)
                        .then(
                            if (isSelected) Modifier.border(2.dp, NuvioTheme.colors.TextPrimary, chipShape) else Modifier
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
    }
}

/**
 * "No other live game is playing right now" (#138 follow-up): Next live game found every other game tried and
 * failed this session. **Try again** (focused first) forgets the tried games and looks again; **Back to Live Now**
 * leaves the player. Never a loop back to a dead game.
 */
@Composable
fun SportsLiveNoLiveGameCard(
    onTryAgain: () -> Unit,
    onBackToLiveNow: () -> Unit,
    modifier: Modifier = Modifier
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .widthIn(max = 420.dp)
            .clip(RoundedCornerShape(NuvioTheme.radii.md))
            .background(Color.Black.copy(alpha = 0.85f))
            .padding(horizontal = 24.dp, vertical = 20.dp)
    ) {
        Text(
            text = stringResource(R.string.sports_live_no_other_live_game),
            style = MaterialTheme.typography.titleMedium,
            color = NuvioTheme.colors.TextPrimary
        )
        PlayerOverlayButton(
            text = stringResource(R.string.sports_live_state_try_again),
            onClick = onTryAgain,
            primary = true,
            modifier = Modifier.fillMaxWidth().focusRequester(firstFocus)
        )
        PlayerOverlayButton(
            text = stringResource(R.string.sports_live_back_to_live_now),
            onClick = onBackToLiveNow,
            primary = false,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
