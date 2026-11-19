package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.domain.repository.SportsLiveFailure
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveHeaderControl
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTextTab
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Colors
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Size

/**
 * Whether [tab] shows the header's filter control (V2): Upcoming (and, until P4 gives them their own
 * dropdowns, Channels and Replays) with two or more values. Live now has no filter; Results has its own
 * rows below the header for now.
 */
internal fun sportsLiveHeaderFilterShown(tab: SportsLiveTab, filterCount: Int): Boolean =
    tab in FILTER_TABS && filterCount >= 2

private val FILTER_TABS = setOf(SportsLiveTab.UPCOMING, SportsLiveTab.CHANNELS, SportsLiveTab.REPLAYS)

/**
 * The section's one-line header (V2, #99): text tabs (selected = white with an underline, the rest 70 %
 * white; no counts) on the left; on the right the tab's filter (one focus stop), the amber "⚠ N" when
 * sources failed (OK lists them) and Refresh. Tabs select on focus; entering the header lands on the
 * active tab. Down goes to [downFocusRequester] (the list); Up from the list's first row goes to the filter
 * when shown. Left from the filter goes back to the active tab, never to the nearest one (which would
 * switch tabs).
 */
@Composable
internal fun SportsLiveHeader(
    tabs: List<SportsLiveTab>,
    selectedTab: SportsLiveTab,
    tabFocusRequesters: Map<SportsLiveTab, FocusRequester>,
    downFocusRequester: FocusRequester?,
    onSelectTab: (SportsLiveTab) -> Unit,
    onOpenTab: () -> Unit,
    /** The tab's filter options; null shows no filter. */
    filterOptions: List<SportsLiveFilterOption>?,
    /** The filter's requester: Up from the list's first row lands on it. */
    filterFocusRequester: FocusRequester,
    selectedFilter: String?,
    onSelectFilter: (String?) -> Unit,
    failures: Int,
    onShowFailures: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activeTabFocusRequester = tabFocusRequesters.getValue(selectedTab)
    val downModifier = Modifier.focusProperties { downFocusRequester?.let { down = it } }
    // F6 (#133): Right past the filter's last option goes to the next header control, never down into the stage.
    val failuresFocusRequester = remember { FocusRequester() }
    val refreshFocusRequester = remember { FocusRequester() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(Size.HeaderHeight),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                // The tab's own focus padding sits outside the safe line, so the first label starts on it.
                .offset(x = -Size.TabPaddingHorizontal)
                // Entering the tabs from elsewhere (the list, the filter) lands on the active tab, never on
                // whichever sits nearest, since tabs select on focus.
                .focusProperties { onEnter = { activeTabFocusRequester.requestFocus() } }
                .focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(Size.TabGap - Size.TabPaddingHorizontal * 2),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEach { tab ->
                SportsLiveTextTab(
                    label = stringResource(tab.labelRes()),
                    selected = tab == selectedTab,
                    onFocused = { onSelectTab(tab) },
                    onClick = onOpenTab,
                    modifier = Modifier
                        .focusRequester(tabFocusRequesters.getValue(tab))
                        .then(downModifier)
                )
            }
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(start = Size.TabGap, end = Size.ControlGap),
            contentAlignment = Alignment.CenterEnd
        ) {
            if (filterOptions != null) {
                SportsLiveFilterControl(
                    options = filterOptions,
                    selected = selectedFilter,
                    onSelect = onSelectFilter,
                    onOpenList = { runCatching { downFocusRequester?.requestFocus() } },
                    modifier = Modifier
                        .focusRequester(filterFocusRequester)
                        .focusProperties {
                        left = activeTabFocusRequester
                        right = if (failures > 0) failuresFocusRequester else refreshFocusRequester
                        downFocusRequester?.let { down = it }
                    }
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Size.ControlGap), verticalAlignment = Alignment.CenterVertically) {
            if (failures > 0) {
                val description = pluralStringResource(R.plurals.sports_live_partial_failure, failures, failures)
                SportsLiveHeaderControl(
                    onClick = onShowFailures,
                    round = false,
                    outline = Colors.WarningAmberOutline,
                    modifier = downModifier.focusRequester(failuresFocusRequester)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.WarningAmber,
                        contentDescription = description,
                        tint = Colors.WarningAmber,
                        modifier = Modifier.size(Size.ControlIcon)
                    )
                    Text(
                        text = failures.toString(),
                        style = SportsLiveTokens.style(SportsLiveTokens.Type.ControlSelected),
                        color = Colors.WarningAmber,
                        maxLines = 1
                    )
                }
            }
            SportsLiveHeaderControl(onClick = onRefresh, modifier = downModifier.focusRequester(refreshFocusRequester)) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = stringResource(R.string.sports_live_refresh),
                    tint = Colors.TextSecondary,
                    modifier = Modifier.size(Size.ControlIcon)
                )
            }
        }
    }
}

/** The sources that did not respond on the last load (the header's "⚠ N"): catalog, addon and why. */
@Composable
internal fun SportsLiveSourceFailuresDialog(failures: List<SportsLiveFailure>, onDismiss: () -> Unit) {
    NuvioDialog(
        onDismiss = onDismiss,
        title = pluralStringResource(R.plurals.sports_live_partial_failure, failures.size, failures.size)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Size.RowGap)) {
            failures.forEach { failure ->
                Text(
                    text = stringResource(
                        R.string.sports_live_source_failure_line,
                        failure.catalog.catalog.name,
                        failure.catalog.addon.displayName,
                        failure.message
                    ),
                    style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta),
                    color = Colors.TextSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

internal fun SportsLiveTab.labelRes(): Int = when (this) {
    SportsLiveTab.LIVE_NOW -> R.string.sports_live_tab_live_now
    SportsLiveTab.UPCOMING -> R.string.sports_live_tab_upcoming
    SportsLiveTab.CHANNELS -> R.string.sports_live_tab_channels
    SportsLiveTab.REPLAYS -> R.string.sports_live_tab_replays
    SportsLiveTab.RESULTS -> R.string.sports_live_tab_results
}
