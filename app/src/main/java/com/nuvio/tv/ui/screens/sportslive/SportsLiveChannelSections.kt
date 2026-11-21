package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.SportsLiveChannelFavorites
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Colors
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.take

/**
 * [source]'s value as read at each emission of [takes] (Channels phase 1): Channels lists its favorites and recent
 * plays in the order taken when the tab opened, the section came back or a refresh was asked, never moving a row
 * under focus while the picks change.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T> sportsLiveFrozenOn(takes: Flow<*>, source: Flow<T>): Flow<T> = takes.flatMapLatest { source.take(1) }

/** A Channels section's heading text: ★ Favorites, Recent, or the region in words ([channelRegionLabel]). */
@Composable
internal fun channelSectionLabel(section: String, other: String): String = when (section) {
    SportsLiveChannelFavorites.FAVORITES -> stringResource(R.string.sports_live_channels_favorites)
    SportsLiveChannelFavorites.RECENT -> stringResource(R.string.sports_live_channels_recent)
    else -> channelRegionLabel(section, other)
}

/**
 * Channels with no favorite listed (phase 1): the Favorites heading, with no count, over one muted line that says how
 * to add one. Neither is focusable; it rides on the list's first row, so list indices stay the rows' own.
 */
@Composable
internal fun SportsLiveFavoritesHint(modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(top = 8.dp, bottom = 4.dp)) {
        Text(
            text = stringResource(R.string.sports_live_channels_favorites).uppercase(Locale.getDefault()),
            style = SportsLiveTokens.style(SportsLiveTokens.Type.SectionHeader),
            color = Colors.TextPrimary.copy(alpha = 0.6f),
            maxLines = 1
        )
        Text(
            text = stringResource(R.string.sports_live_channels_favorites_hint),
            style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta),
            color = Colors.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
