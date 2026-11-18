package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.LiveNowSort
import com.nuvio.tv.core.sportslive.SportsLiveLayout
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme

/** An entry of the View menu (#79). */
internal sealed interface SportsLiveViewOption {
    data class Layout(val layout: SportsLiveLayout) : SportsLiveViewOption
    data class Sort(val sort: LiveNowSort) : SportsLiveViewOption
}

/**
 * The View button's menu on Live now and Upcoming (#79), in place of the layout switch and the sort
 * switch: **Layout** (Cards / Scoreboard / Timeline) and, where it applies, **Sort** (Start time /
 * Most exciting: Live now only, not on the Timeline, not under Scores Off; [LiveNowSort.offered]).
 * Both stay stored per tab and per profile as before.
 */
internal data class SportsLiveViewMenu(
    val layouts: List<SportsLiveLayout>,
    val layout: SportsLiveLayout,
    val sortShown: Boolean,
    val sort: LiveNowSort
) {
    val layoutOptions: List<SportsLiveViewOption.Layout> get() = layouts.map(SportsLiveViewOption::Layout)

    /** Empty while the sort does not apply. */
    val sortOptions: List<SportsLiveViewOption.Sort>
        get() = if (sortShown) LiveNowSort.entries.map(SportsLiveViewOption::Sort) else emptyList()

    fun isChecked(option: SportsLiveViewOption): Boolean = when (option) {
        is SportsLiveViewOption.Layout -> option.layout == layout
        is SportsLiveViewOption.Sort -> option.sort == sort
    }

    /** OK on [option]: the layout or the sort is applied (and stored, by the callbacks). */
    fun apply(option: SportsLiveViewOption, onLayout: (SportsLiveLayout) -> Unit, onSort: (LiveNowSort) -> Unit) {
        when (option) {
            is SportsLiveViewOption.Layout -> onLayout(option.layout)
            is SportsLiveViewOption.Sort -> onSort(option.sort)
        }
    }

    companion object {
        /** [tab]'s menu ([layout]: its layout, [mode]: the Scores setting, [sort]: Live now's stored sort); null when [tab] has none. */
        fun of(tab: SportsLiveTab, layout: SportsLiveLayout, mode: ScoresMode?, sort: LiveNowSort): SportsLiveViewMenu? {
            val layouts = SportsLiveLayout.offeredFor(tab)
            if (layouts.size < 2) return null
            val sortShown = tab == SportsLiveTab.LIVE_NOW && LiveNowSort.offered(mode, layout)
            return SportsLiveViewMenu(layouts, layout, sortShown, LiveNowSort.effective(sort, mode, layout))
        }
    }
}

@Composable
private fun layoutLabel(layout: SportsLiveLayout): String = stringResource(
    when (layout) {
        SportsLiveLayout.CARDS -> R.string.sports_live_layout_cards
        SportsLiveLayout.SCOREBOARD -> R.string.sports_live_layout_scoreboard
        SportsLiveLayout.TIMELINE -> R.string.sports_live_layout_timeline
    }
)

@Composable
private fun sortLabel(sort: LiveNowSort, short: Boolean): String = stringResource(
    when (sort) {
        LiveNowSort.START_TIME -> R.string.sports_live_sort_start_time
        LiveNowSort.MOST_EXCITING -> if (short) R.string.sports_live_view_sort_exciting else R.string.sports_live_sort_most_exciting
    }
)

/** "View · Scoreboard · Exciting": the button names the layout, and the sort while it applies. */
@Composable
private fun viewButtonLabel(menu: SportsLiveViewMenu): String = if (menu.sortShown) {
    stringResource(R.string.sports_live_view_label_sort, layoutLabel(menu.layout), sortLabel(menu.sort, short = true))
} else {
    stringResource(R.string.sports_live_view_label, layoutLabel(menu.layout))
}

/** The header's View button (#79): OK opens [SportsLiveViewMenuDialog]. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SportsLiveViewButton(menu: SportsLiveViewMenu, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(20.dp)
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            contentColor = NuvioTheme.colors.TextPrimary,
            focusedContainerColor = NuvioTheme.colors.BackgroundCard,
            focusedContentColor = NuvioTheme.colors.TextPrimary
        ),
        border = ButtonDefaults.border(
            focusedBorder = Border(border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape)
        ),
        shape = ButtonDefaults.shape(shape),
        contentPadding = PaddingValues(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.xs)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = Icons.Rounded.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(viewButtonLabel(menu), style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/**
 * The View menu (#79): Layout, then Sort where it applies. OK applies an entry and closes the menu (QA
 * pass 3: one pick is the common case); Back closes it too. Focus opens on the layout in force.
 */
@Composable
internal fun SportsLiveViewMenuDialog(
    menu: SportsLiveViewMenu,
    onSelectLayout: (SportsLiveLayout) -> Unit,
    onSelectSort: (LiveNowSort) -> Unit,
    onDismiss: () -> Unit
) {
    val checkedFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { checkedFocusRequester.requestFocus() } }
    NuvioDialog(onDismiss = onDismiss, title = stringResource(R.string.sports_live_view), width = 360.dp) {
        @Composable
        fun Group(title: String, options: List<SportsLiveViewOption>, label: @Composable (SportsLiveViewOption) -> String, focusChecked: Boolean) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = NuvioTheme.colors.TextSecondary,
                modifier = Modifier.padding(top = NuvioTheme.spacing.xs)
            )
            options.forEach { option ->
                val checked = menu.isChecked(option)
                SportsLiveViewMenuEntry(
                    text = label(option),
                    checked = checked,
                    onClick = {
                        menu.apply(option, onSelectLayout, onSelectSort)
                        onDismiss()
                    },
                    modifier = if (checked && focusChecked) Modifier.focusRequester(checkedFocusRequester) else Modifier
                )
            }
        }
        Group(
            title = stringResource(R.string.sports_live_view_layout),
            options = menu.layoutOptions,
            label = { layoutLabel((it as SportsLiveViewOption.Layout).layout) },
            focusChecked = true
        )
        if (menu.sortOptions.isNotEmpty()) {
            Group(
                title = stringResource(R.string.sports_live_view_sort),
                options = menu.sortOptions,
                label = { sortLabel((it as SportsLiveViewOption.Sort).sort, short = false) },
                focusChecked = false
            )
        }
    }
}

/** A View menu entry: its label, and a check on the one in force. */
@Composable
private fun SportsLiveViewMenuEntry(text: String, checked: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = ButtonDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            contentColor = NuvioTheme.colors.TextPrimary
        )
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (checked) Icon(imageVector = Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}
