package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveButton
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveButtonRole
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Colors

/**
 * "You watched Cricket · Add to My sports?  [Add]  [Not now]" (coordinator decision 2026-11-21): one quiet card at the top
 * of Live now's list, the next time Live now shows after a More live game was played ([SportsLiveAddSportPrompt]). It
 * never takes first focus: Up from the list's first row reaches it, and Up from it goes on to [up].
 */
@Composable
internal fun SportsLiveAddSportCard(
    sportLabel: String,
    onAdd: () -> Unit,
    onNotNow: () -> Unit,
    addRequester: FocusRequester,
    up: FocusRequester,
    right: FocusRequester,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Colors.Row, SportsLiveTokens.Radius.RowShape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.sports_live_add_sport_question, sportLabel),
            style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta),
            color = Colors.TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        SportsLiveButton(
            onClick = onAdd,
            role = SportsLiveButtonRole.PRIMARY,
            modifier = Modifier.focusRequester(addRequester).focusProperties { this.up = up }
        ) {
            Text(text = stringResource(R.string.sports_live_add_sport_add), style = SportsLiveTokens.style(SportsLiveTokens.Type.Button), maxLines = 1)
        }
        SportsLiveButton(
            onClick = onNotNow,
            modifier = Modifier.focusProperties {
                this.up = up
                this.right = right
            }
        ) {
            Text(text = stringResource(R.string.sports_live_add_sport_not_now), style = SportsLiveTokens.style(SportsLiveTokens.Type.Button), maxLines = 1)
        }
    }
}
