package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.SportsLiveKickoffWait
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveButton
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveButtonRole
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import java.text.DateFormat
import java.util.Date

/** The waiting card's buttons (#162), in order; the first takes focus. */
enum class SportsLiveWaitingAction { BACK, SOURCES }

/**
 * "Waiting for the stream to start…" (#162): a calm V2 card, never an error, while a game opened around its
 * kickoff has no live link yet. The game, its scheduled time ([startMs]) and "Checking every 30 s"; Back /
 * Sources. It goes by itself as soon as a link is live and plays.
 */
@Composable
fun SportsLiveWaitingCard(
    game: String?,
    startMs: Long?,
    onAction: (SportsLiveWaitingAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstFocus.requestFocus() }
    }
    val shape = RoundedCornerShape(SportsLiveTokens.Alert.Radius)
    val time = remember(startMs) { startMs?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) } }
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .widthIn(max = 460.dp)
            .clip(shape)
            .background(SportsLiveTokens.Colors.Surface.copy(alpha = 0.94f))
            .border(1.dp, SportsLiveTokens.Colors.Outline, shape)
            .padding(horizontal = 24.dp, vertical = 20.dp)
    ) {
        game?.takeIf(String::isNotBlank)?.let {
            Text(
                text = it,
                style = SportsLiveTokens.style(SportsLiveTokens.Type.PanelTitle),
                color = SportsLiveTokens.Colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = stringResource(R.string.sports_live_waiting_title),
            style = SportsLiveTokens.style(SportsLiveTokens.Type.RowName),
            color = SportsLiveTokens.Colors.TextPrimary
        )
        Text(
            text = listOfNotNull(
                time?.let { stringResource(R.string.sports_live_waiting_starts, it) },
                stringResource(R.string.sports_live_waiting_checking, (SportsLiveKickoffWait.RECHECK_MS / 1_000).toInt())
            ).joinToString(" · "),
            style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta),
            color = SportsLiveTokens.Colors.TextSecondary,
            maxLines = 1
        )
        Row(horizontalArrangement = Arrangement.spacedBy(SportsLiveTokens.Size.ButtonGap)) {
            SportsLiveButton(
                onClick = { onAction(SportsLiveWaitingAction.BACK) },
                role = SportsLiveButtonRole.PRIMARY,
                modifier = Modifier.focusRequester(firstFocus)
            ) {
                Text(stringResource(R.string.action_back), style = SportsLiveTokens.style(SportsLiveTokens.Type.Button), maxLines = 1)
            }
            SportsLiveButton(onClick = { onAction(SportsLiveWaitingAction.SOURCES) }) {
                Text(stringResource(R.string.sources_title), style = SportsLiveTokens.style(SportsLiveTokens.Type.Button), maxLines = 1)
            }
        }
    }
}
