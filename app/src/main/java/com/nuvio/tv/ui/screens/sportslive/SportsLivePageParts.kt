package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveFocus
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Colors
import java.util.Locale

/**
 * The pushed pages' (Game, Team, Player, #134) focusable surface: a row or card on [container] that takes the
 * V2 focus (white ring, 8 % lighter fill, and the 1.03 lift when [lift]). Every list row can take focus, so ↓
 * always scrolls (F8, F10); OK does nothing unless [onClick] is given.
 */
@Composable
internal fun SportsLivePageSurface(
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    container: Color = Colors.Row,
    lift: Boolean = true,
    content: @Composable () -> Unit
) {
    val shape = SportsLiveTokens.Radius.RowShape
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = if (lift) SportsLiveTokens.Focus.LIFT else 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = container,
            contentColor = Colors.TextPrimary,
            focusedContainerColor = SportsLiveFocus.focusedFill(container),
            focusedContentColor = Colors.TextPrimary,
            pressedContainerColor = SportsLiveFocus.focusedFill(container),
            pressedContentColor = Colors.TextPrimary
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = SportsLiveFocus.ring(shape), pressedBorder = SportsLiveFocus.ring(shape))
    ) { content() }
}

/** A one-line focusable row [height] tall (Box score, Standings: 56 px; Last 5: 64–72 px), its parts in a Row. */
@Composable
internal fun SportsLivePageRow(
    height: Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    container: Color = Colors.Row,
    /** A white bar at the left edge: "selected", not focus (this team in a standings snippet). */
    selectedBar: Boolean = false,
    /** The 1.03 lift; off for full-width table rows, whose numbers would shift sideways under focus. */
    lift: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    SportsLivePageSurface(modifier = modifier.fillMaxWidth().height(height), onClick = onClick, container = container, lift = lift) {
        Box(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = PAGE_ROW_PADDING),
                horizontalArrangement = Arrangement.spacedBy(PAGE_ROW_GAP),
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
            if (selectedBar) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(vertical = 6.dp)
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(Colors.FocusWhite)
                )
            }
        }
    }
}

/** A section's heading: 24 px caps, 0.08 em apart, muted ("TOP PERFORMERS", "LAST 5"). */
@Composable
internal fun SportsLivePageHeading(text: String, modifier: Modifier = Modifier, color: Color = Colors.TextMuted) {
    Text(
        text = text.uppercase(Locale.getDefault()),
        style = SportsLiveTokens.style(SportsLiveTokens.Type.SectionHeader),
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

/** A quiet line of text on a pushed page (loading failed, nothing yet). */
@Composable
internal fun SportsLivePageMessage(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = SportsLiveTokens.style(SportsLiveTokens.Type.RowName),
        color = Colors.TextSecondary,
        modifier = modifier.padding(vertical = 8.dp)
    )
}

/** The green "Replay" pill, in the tokens' colours. */
@Composable
internal fun SportsLiveReplayPill() {
    Text(
        text = stringResource(R.string.sports_live_results_replay),
        style = SportsLiveTokens.style(SportsLiveTokens.Type.Tag).sportsLiveBold(),
        color = Colors.ReplayGreenText,
        maxLines = 1,
        modifier = Modifier
            .clip(SportsLiveTokens.Radius.PillShape)
            .background(Colors.ReplayGreen)
            .padding(horizontal = 6.dp, vertical = 1.dp)
    )
}

/** The red live dot, at tag size. */
@Composable
internal fun SportsLiveLiveDot(size: Dp = 6.dp) {
    Box(modifier = Modifier.size(size).clip(CircleShape).background(Colors.LiveRed))
}

/** The pushed pages' frame: the safe area's sides and top (board: 96 px from each side, 68 px from the top). */
internal val PAGE_SIDE = 48.dp
internal val PAGE_TOP = 34.dp
private val PAGE_ROW_PADDING = 12.dp
private val PAGE_ROW_GAP = 10.dp

/** A Box score / Standings row: 56 px (#134, F8). */
internal val PAGE_TABLE_ROW = 28.dp
