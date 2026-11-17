package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * The Sources panel's "‹provider› · loading…" rows for a merged Sports & Live game's [providers]
 * still being fetched; each goes once its streams arrive (or it times out). They are not focusable.
 * Shown under "All" only ([selectedAddonFilter] null); no rows at all when [providers] is empty.
 */
internal fun LazyListScope.sportsLiveSourceLoadingRows(providers: List<String>, selectedAddonFilter: String?) {
    if (selectedAddonFilter != null || providers.isEmpty()) return
    items(providers, key = { "sports-live-loading-$it" }) { provider ->
        Text(
            text = stringResource(R.string.sports_live_source_loading, provider),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.6f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm)
        )
    }
}
