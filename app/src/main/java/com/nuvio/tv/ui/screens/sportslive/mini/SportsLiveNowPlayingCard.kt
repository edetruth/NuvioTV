package com.nuvio.tv.ui.screens.sportslive.mini

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.sportslive.SportsLivePaneButton
import com.nuvio.tv.ui.theme.NuvioTheme

/** "Now playing <game>" with Expand and Stop, shown above the section while the mini player plays. */
@Composable
internal fun SportsLiveNowPlayingCard(
    name: String,
    onExpand: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(NuvioTheme.radii.md))
            .background(NuvioTheme.colors.BackgroundCard)
            .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
    ) {
        Text(
            text = stringResource(R.string.sports_live_now_playing),
            style = MaterialTheme.typography.labelMedium,
            color = NuvioTheme.colors.Primary
        )
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmall,
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        SportsLivePaneButton(onClick = onExpand) { Text(stringResource(R.string.sports_live_expand)) }
        SportsLivePaneButton(onClick = onStop) { Text(stringResource(R.string.sports_live_stop)) }
    }
}
