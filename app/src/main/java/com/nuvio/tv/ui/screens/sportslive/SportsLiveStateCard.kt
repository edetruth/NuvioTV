package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveButton
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveButtonRole
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Colors

/** A button of the state card: its label and what OK does; [bell] puts the reminder bell before the label. */
internal data class SportsLiveStateAction(val label: String, val onClick: () -> Unit, val bell: Boolean = false)

/**
 * The one empty / error card (#134), in the list column: a title, one line, and always a next step: [primary]
 * (the PRIMARY button) and an optional [secondary]. [problem] puts the amber ⚠ before the title ("Couldn't reach
 * your sources"). [primaryModifier] carries the screen's focus wiring for the first button.
 */
@Composable
internal fun SportsLiveStateCard(
    title: String,
    line: String?,
    primary: SportsLiveStateAction?,
    modifier: Modifier = Modifier,
    secondary: SportsLiveStateAction? = null,
    problem: Boolean = false,
    primaryModifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(SportsLiveTokens.Size.ListWidth)
            .background(Colors.Surface, SportsLiveTokens.Radius.RowShape)
            .padding(SportsLiveTokens.State.Padding),
        verticalArrangement = Arrangement.spacedBy(SportsLiveTokens.State.Gap)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (problem) {
                Icon(imageVector = Icons.Rounded.WarningAmber, contentDescription = null, tint = Colors.WarningAmber, modifier = Modifier.size(18.dp))
            }
            Text(
                text = title,
                style = SportsLiveTokens.style(SportsLiveTokens.State.Title),
                color = Colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        line?.let {
            Text(
                text = it,
                style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta),
                color = Colors.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (primary != null || secondary != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(SportsLiveTokens.Size.ButtonGap), verticalAlignment = Alignment.CenterVertically) {
                primary?.let { StateButton(it, SportsLiveButtonRole.PRIMARY, primaryModifier) }
                secondary?.let { StateButton(it, SportsLiveButtonRole.SECONDARY, if (primary == null) primaryModifier else Modifier) }
            }
        }
    }
}

@Composable
private fun StateButton(action: SportsLiveStateAction, role: SportsLiveButtonRole, modifier: Modifier) {
    SportsLiveButton(onClick = action.onClick, role = role, modifier = modifier.height(SportsLiveTokens.Size.ButtonHeight)) {
        if (action.bell) {
            Icon(imageVector = Icons.Rounded.Notifications, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(text = action.label, style = SportsLiveTokens.style(SportsLiveTokens.Type.Button), maxLines = 1, softWrap = false)
    }
}

/**
 * What a list looks like while it loads (#134): [count] placeholder rows at the live row's height, never a lone
 * spinner mid-screen. [stage] adds the stage's placeholder beside them, where the stage will sit.
 */
@Composable
internal fun SportsLiveSkeletonRows(
    modifier: Modifier = Modifier,
    count: Int = SportsLiveTokens.State.SkeletonRows,
    stage: Boolean = false
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(SportsLiveTokens.Size.StageGap)) {
        Column(modifier = Modifier.width(SportsLiveTokens.Size.ListWidth), verticalArrangement = Arrangement.spacedBy(SportsLiveTokens.Size.RowGap)) {
            repeat(count) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(SportsLiveTokens.Size.RowLive)
                        .background(SportsLiveTokens.State.Skeleton, SportsLiveTokens.Radius.RowShape)
                )
            }
        }
        if (stage) {
            Column(modifier = Modifier.width(SportsLiveTokens.Size.StageWidth), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(SportsLiveStageLayout.MEDIA_HEIGHT_DP.dp)
                        .background(SportsLiveTokens.State.Skeleton, SportsLiveTokens.Radius.RowShape)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .height(20.dp)
                        .background(SportsLiveTokens.State.Skeleton, SportsLiveTokens.Radius.RowShape)
                )
            }
        }
    }
}

/** A full-height skeleton column (the section's first load): the rows where the list starts, and the stage. */
@Composable
internal fun SportsLiveLoadingSkeleton() {
    SportsLiveSkeletonRows(
        modifier = Modifier
            .fillMaxHeight()
            .padding(
                start = SportsLiveTokens.Size.SafeStart,
                top = SportsLiveTokens.Size.SafeTop + SportsLiveTokens.Size.HeaderHeight + SportsLiveTokens.Size.HeaderBottomGap
            ),
        stage = true
    )
}
