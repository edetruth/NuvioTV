package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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

/** The waiting card's buttons (#162), in order; the first takes focus. Back is the Back key's (V2: no Back button). */
enum class SportsLiveWaitingAction { SOURCES, NEXT_LIVE_GAME }

/**
 * "Waiting for the stream to start…" (#162): a calm V2 card, never an error, while a game opened around its
 * kickoff has no live link yet. Shown only while nothing plays (live.19 QA F12). Like the starting card it sits in
 * the player's bottom left corner, not as a modal (its owner places it): a spinner and "Waiting for the stream to
 * start…", then the game, its scheduled time ([startMs]) and "Checking every 30 s"; **Sources** (PRIMARY) and,
 * with a zap session ([canNextLiveGame]), **Next live game**. It goes by itself as soon as a link is live and plays.
 */
@Composable
fun SportsLiveWaitingCard(
    game: String?,
    startMs: Long?,
    onAction: (SportsLiveWaitingAction) -> Unit,
    modifier: Modifier = Modifier,
    canNextLiveGame: Boolean = false
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstFocus.requestFocus() }
    }
    val shape = RoundedCornerShape(SportsLiveTokens.Alert.Radius)
    val time = remember(startMs) { startMs?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) } }
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .claimsBottomLeft()
            .widthIn(max = SportsLiveTokens.Alert.Width + 60.dp)
            .clip(shape)
            .background(SportsLiveTokens.Colors.SurfaceRaised)
            .border(1.dp, SportsLiveTokens.Alert.Border, shape)
            .padding(SportsLiveTokens.Alert.Padding)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SportsLiveCardSpinner()
            Text(
                text = stringResource(R.string.sports_live_waiting_title),
                style = SportsLiveTokens.style(SportsLiveTokens.Toast.Title),
                color = SportsLiveTokens.Colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = listOfNotNull(
                game?.takeIf(String::isNotBlank)?.let(::sportsLivePlainTitle),
                time?.let { stringResource(R.string.sports_live_waiting_starts, it) },
                stringResource(R.string.sports_live_waiting_checking, (SportsLiveKickoffWait.RECHECK_MS / 1_000).toInt())
            ).joinToString(stringResource(R.string.sports_live_game_info_separator)),
            style = SportsLiveTokens.style(SportsLiveTokens.Toast.Line),
            color = SportsLiveTokens.Colors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(SportsLiveTokens.Size.ButtonGap)) {
            SportsLiveButton(
                onClick = { onAction(SportsLiveWaitingAction.SOURCES) },
                role = SportsLiveButtonRole.PRIMARY,
                modifier = Modifier.focusRequester(firstFocus)
            ) {
                Text(stringResource(R.string.sources_title), style = SportsLiveTokens.style(SportsLiveTokens.Type.Button), maxLines = 1)
            }
            if (canNextLiveGame) {
                SportsLiveButton(onClick = { onAction(SportsLiveWaitingAction.NEXT_LIVE_GAME) }) {
                    Text(stringResource(R.string.sports_live_next_live_game), style = SportsLiveTokens.style(SportsLiveTokens.Type.Button), maxLines = 1)
                }
            }
        }
    }
}
