package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WarningAmber
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveButton
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveButtonRole
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Colors

/** The buttons of the full player's Sports & Live recovery card (live.19 QA F9), left to right; the first is PRIMARY. */
enum class SportsLivePlayerErrorAction { TRY_NEXT_LINK, RETRY, CHOOSE_STREAM, NEXT_LIVE_GAME }

/**
 * What the recovery card offers (F9), as the starting card does: **Try next link** (PRIMARY) when the game has
 * another link ([hasOtherLinks]; it plays even after the automatic tries gave up), else **Retry** this one;
 * **Choose stream** always; **Next live game** with a zap session ([canNextLiveGame]). Back leaves (no button).
 */
internal fun sportsLivePlayerErrorActions(hasOtherLinks: Boolean, canNextLiveGame: Boolean): List<SportsLivePlayerErrorAction> =
    listOfNotNull(
        if (hasOtherLinks) SportsLivePlayerErrorAction.TRY_NEXT_LINK else SportsLivePlayerErrorAction.RETRY,
        SportsLivePlayerErrorAction.CHOOSE_STREAM,
        SportsLivePlayerErrorAction.NEXT_LIVE_GAME.takeIf { canNextLiveGame }
    )

/**
 * The full player's Sports & Live recovery card (V2, live.19 QA F9), in place of the legacy pill error page: an
 * amber ⚠ "Couldn't play this stream", what happened ([message]: "No working stream found after 9 links"), then
 * [actions] as V2 buttons, the first focused. It sits in the player's bottom left corner, where the starting card
 * was, over the dimmed last frame. Never a url or a host name ([message] is already plain).
 */
@Composable
fun SportsLivePlayerErrorCard(
    message: String,
    actions: List<SportsLivePlayerErrorAction>,
    onAction: (SportsLivePlayerErrorAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val labels = mapOf(
        SportsLivePlayerErrorAction.TRY_NEXT_LINK to stringResource(R.string.sports_live_try_next_link),
        SportsLivePlayerErrorAction.RETRY to stringResource(R.string.player_retry_stream),
        SportsLivePlayerErrorAction.CHOOSE_STREAM to stringResource(R.string.sports_live_choose_stream),
        SportsLivePlayerErrorAction.NEXT_LIVE_GAME to stringResource(R.string.sports_live_next_live_game)
    )
    SportsLivePlayerCornerCard(
        title = stringResource(R.string.sports_live_dual_stream_error),
        line = message,
        buttons = actions.map { action -> labels.getValue(action) to { onAction(action) } },
        modifier = modifier
    )
}

/**
 * "No other live game is playing right now" (#138 follow-up): Next live game found every other game tried and
 * failed this session. **Try again** (focused first) forgets the tried games and looks again; **Back to Live Now**
 * leaves the player. Never a loop back to a dead game. The recovery card's V2 frame (live.19 QA F9).
 */
@Composable
fun SportsLiveNoLiveGameCard(
    onTryAgain: () -> Unit,
    onBackToLiveNow: () -> Unit,
    modifier: Modifier = Modifier
) {
    SportsLivePlayerCornerCard(
        title = stringResource(R.string.sports_live_no_other_live_game),
        line = null,
        buttons = listOf(
            stringResource(R.string.sports_live_state_try_again) to onTryAgain,
            stringResource(R.string.sports_live_back_to_live_now) to onBackToLiveNow
        ),
        modifier = modifier
    )
}

/** The player corner's problem card: amber ⚠ and [title], an optional [line], then [buttons], the first PRIMARY and focused. */
@Composable
private fun SportsLivePlayerCornerCard(
    title: String,
    line: String?,
    buttons: List<Pair<String, () -> Unit>>,
    modifier: Modifier
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstFocus.requestFocus() }
    }
    val shape = RoundedCornerShape(SportsLiveTokens.Alert.Radius)
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .claimsBottomLeft()
            .widthIn(min = SportsLiveTokens.Alert.Width, max = ERROR_CARD_MAX_WIDTH)
            .clip(shape)
            .background(Colors.SurfaceRaised.copy(alpha = SportsLiveTokens.Alert.FILL_ALPHA))
            .border(1.dp, SportsLiveTokens.Alert.Border, shape)
            .padding(SportsLiveTokens.Alert.Padding)
            .semantics { liveRegion = LiveRegionMode.Polite }
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = Icons.Rounded.WarningAmber, contentDescription = null, tint = Colors.WarningAmber, modifier = Modifier.size(18.dp))
            Text(
                text = title,
                style = SportsLiveTokens.style(SportsLiveTokens.Toast.Title),
                color = Colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        line?.takeIf(String::isNotBlank)?.let {
            Text(
                text = it,
                style = SportsLiveTokens.style(SportsLiveTokens.Toast.Line),
                color = Colors.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(SportsLiveTokens.Size.ButtonGap)) {
            buttons.forEachIndexed { index, (label, onClick) ->
                SportsLiveButton(
                    onClick = onClick,
                    role = if (index == 0) SportsLiveButtonRole.PRIMARY else SportsLiveButtonRole.SECONDARY,
                    modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier
                ) {
                    Text(text = label, style = SportsLiveTokens.style(SportsLiveTokens.Type.Button), maxLines = 1)
                }
            }
        }
    }
}

private val ERROR_CARD_MAX_WIDTH = 480.dp
