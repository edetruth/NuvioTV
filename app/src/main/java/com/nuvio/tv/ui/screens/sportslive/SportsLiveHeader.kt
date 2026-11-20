package com.nuvio.tv.ui.screens.sportslive

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveButton
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveButtonRole
import java.util.Date
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveHeaderControl
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTextTab
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Colors
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Size

/**
 * Whether [tab] shows the header's addon filter (V2): Upcoming (inline), Channels (regions) and Replays
 * (sports) as a dropdown (P4), with two or more values. Live now has no filter; Results has its own sports
 * dropdown ([SportsLiveHeaderDropdown]).
 *
 * While My sports hides some of the tab's games ([allSports], #146), the filter shows whatever its count, so
 * All sports is one press away: on Live now too, as a dropdown of just My sports and All sports.
 */
internal fun sportsLiveHeaderFilterShown(tab: SportsLiveTab, filterCount: Int, allSports: Boolean = false): Boolean =
    if (tab == SportsLiveTab.LIVE_NOW) allSports else tab in FILTER_TABS && (filterCount >= 2 || allSports)

/** The header's filter drawn as one dropdown (V2 P4: Channels, Replays, Results): the menu's [title] and [actions]. */
internal data class SportsLiveHeaderDropdown(val title: String, val actions: List<Pair<String, () -> Unit>> = emptyList())

/**
 * The day in Results' title (V2 P4): "· Yesterday ‹ ›". OK on ‹ steps to an older day and on › to a newer one
 * ([onStep], true for newer); each arrow is its own focus stop, so ← and → always move along the header (#153).
 */
internal data class SportsLiveHeaderDate(val label: String, val canOlder: Boolean, val canNewer: Boolean, val onStep: (Boolean) -> Unit)

private val FILTER_TABS = setOf(SportsLiveTab.UPCOMING, SportsLiveTab.CHANNELS, SportsLiveTab.REPLAYS)

/** The header's focus stops, left to right: the active tab, Results' day arrows, the filter, Refresh. */
internal enum class SportsLiveHeaderStop { TAB, DAY_OLDER, DAY_NEWER, FILTER, REFRESH }

/**
 * The stop ← ([right] false) or → ([right] true) moves to from [stop] (#153): the next stop shown that way, or
 * null at the ends. ← from the first control past the tabs is the active tab, never the nearest tab (tabs select
 * on focus); the day arrows never hold the key, so the D-pad always gets back to the tabs.
 */
internal fun sportsLiveHeaderNeighbour(
    stop: SportsLiveHeaderStop,
    right: Boolean,
    hasDate: Boolean,
    hasFilter: Boolean
): SportsLiveHeaderStop? {
    val shown = SportsLiveHeaderStop.entries.filter {
        when (it) {
            SportsLiveHeaderStop.DAY_OLDER, SportsLiveHeaderStop.DAY_NEWER -> hasDate
            SportsLiveHeaderStop.FILTER -> hasFilter
            else -> true
        }
    }
    val index = shown.indexOf(stop).takeIf { it >= 0 } ?: return null
    return shown.getOrNull(if (right) index + 1 else index - 1)
}

/**
 * The section's one-line header (V2, #99): text tabs (selected = white with an underline, the rest 70 %
 * white; no counts) on the left; on the right the tab's filter (one focus stop) and Refresh, with a dot on it
 * while a source keeps failing ([sourceBadge], #170: OK still refreshes, Hold OK opens the sources sheet). Tabs select on focus;
 * entering the header lands on the active tab. In columns (#170): the tabs and day arrows go Down to
 * [downFocusRequester] (the list), the controls to [controlsDownFocusRequester] (the stage's primary). Left
 * from the filter goes back to the active tab, never to the nearest one (which would switch tabs).
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
    /** The dot on Refresh (#170). */
    sourceBadge: SportsLiveSourceBadge,
    /** The first control over the stage (the filter, else Refresh): the stage's Up lands on it (#170). */
    controlsFocusRequester: FocusRequester,
    /** Where the controls' Down lands: the stage's primary; null goes to [downFocusRequester]. */
    controlsDownFocusRequester: FocusRequester?,
    /** Draws the filter as one dropdown (P4); null keeps the inline pill. */
    filterDropdown: SportsLiveHeaderDropdown? = null,
    /** Results' day in the title (P4), right after the tabs; null for none. */
    date: SportsLiveHeaderDate? = null,
    dateFocusRequester: FocusRequester = remember { FocusRequester() },
    onShowFailures: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    /** Stale-while-revalidate (live.20 B1): when the list shown was loaded, while a refresh runs; null shows nothing. */
    updatedAtMs: Long? = null,
    /** Live now's rows waiting while it is browsed (TV QA Q5): Refresh reads "N new" and OK shows them. */
    newRows: Int = 0
) {
    val activeTabFocusRequester = tabFocusRequesters.getValue(selectedTab)
    val downModifier = Modifier.focusProperties { downFocusRequester?.let { down = it } }
    val controlsDown = controlsDownFocusRequester ?: downFocusRequester
    val controlsStop = sportsLiveHeaderControlsStop(hasFilter = filterOptions != null)
    fun controlsModifier(stop: SportsLiveHeaderStop) =
        if (stop == controlsStop) Modifier.focusRequester(controlsFocusRequester) else Modifier
    // F6 (#133): Right past the filter's last option goes to the next header control, never down into the stage.
    val refreshFocusRequester = remember { FocusRequester() }
    val dayNewerFocusRequester = remember { FocusRequester() }
    fun requesterOf(stop: SportsLiveHeaderStop?): FocusRequester = when (stop) {
        SportsLiveHeaderStop.DAY_OLDER -> dateFocusRequester
        SportsLiveHeaderStop.DAY_NEWER -> dayNewerFocusRequester
        SportsLiveHeaderStop.FILTER -> filterFocusRequester
        SportsLiveHeaderStop.REFRESH -> refreshFocusRequester
        SportsLiveHeaderStop.TAB, null -> activeTabFocusRequester
    }
    fun neighbour(stop: SportsLiveHeaderStop, right: Boolean): FocusRequester = requesterOf(
        sportsLiveHeaderNeighbour(stop, right, hasDate = date != null, hasFilter = filterOptions != null)
    )
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
            tabs.forEachIndexed { index, tab ->
                SportsLiveTextTab(
                    label = stringResource(tab.labelRes()),
                    selected = tab == selectedTab,
                    onFocused = { onSelectTab(tab) },
                    onClick = onOpenTab,
                    modifier = Modifier
                        .focusRequester(tabFocusRequesters.getValue(tab))
                        .then(downModifier)
                        // #170: → past the last tab walks on to the day arrows, then the controls.
                        .then(if (index == tabs.lastIndex) Modifier.focusProperties { right = neighbour(SportsLiveHeaderStop.TAB, right = true) } else Modifier)
                )
            }
        }
        if (date != null) {
            fun arrow(stop: SportsLiveHeaderStop, requester: FocusRequester) = Modifier
                .focusRequester(requester)
                .focusProperties {
                    left = neighbour(stop, right = false)
                    right = neighbour(stop, right = true)
                    up = activeTabFocusRequester
                    downFocusRequester?.let { down = it }
                }
            SportsLiveHeaderDateControl(
                date = date,
                olderModifier = arrow(SportsLiveHeaderStop.DAY_OLDER, dateFocusRequester),
                newerModifier = arrow(SportsLiveHeaderStop.DAY_NEWER, dayNewerFocusRequester)
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(start = Size.TabGap, end = Size.ControlGap),
            contentAlignment = Alignment.CenterEnd
        ) {
            if (filterOptions != null) {
                val filterModifier = Modifier
                    .focusRequester(filterFocusRequester)
                    .then(controlsModifier(SportsLiveHeaderStop.FILTER))
                    .focusProperties {
                        left = neighbour(SportsLiveHeaderStop.FILTER, right = false)
                        right = neighbour(SportsLiveHeaderStop.FILTER, right = true)
                        controlsDown?.let { down = it }
                    }
                if (filterDropdown != null) {
                    SportsLiveFilterDropdown(
                        options = filterOptions,
                        selected = selectedFilter,
                        onSelect = onSelectFilter,
                        title = filterDropdown.title,
                        actions = filterDropdown.actions,
                        modifier = filterModifier
                    )
                } else {
                    SportsLiveFilterControl(
                        options = filterOptions,
                        selected = selectedFilter,
                        onSelect = onSelectFilter,
                        onOpenList = { runCatching { downFocusRequester?.requestFocus() } },
                        modifier = filterModifier
                    )
                }
            }
        }
        // live.20 B1: a quiet "Updated 7:42 PM" beside Refresh while a refresh runs over an older list (the TV pattern of
        // a timestamp on cached guide data); it goes when the fresh list lands.
        if (updatedAtMs != null) {
            val context = LocalContext.current
            val timeFormat = remember(context) { DateFormat.getTimeFormat(context) }
            Text(
                text = stringResource(R.string.sports_live_updated_at, timeFormat.format(Date(updatedAtMs))),
                style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta),
                color = Colors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = Size.ControlGap)
            )
        }
        // #170: while a source keeps failing, a dot on Refresh (grey, or amber when it matters). OK always refreshes;
        // Hold OK opens the sources sheet, as Hold OK means more options elsewhere.
        val badged = sourceBadge != SportsLiveSourceBadge.NONE
        Box {
            SportsLiveHeaderControl(
                onClick = onRefresh,
                onLongClick = if (badged) onShowFailures else null,
                round = newRows == 0,
                modifier = Modifier
                    .focusRequester(refreshFocusRequester)
                    .then(controlsModifier(SportsLiveHeaderStop.REFRESH))
                    .focusProperties {
                        left = neighbour(SportsLiveHeaderStop.REFRESH, right = false)
                        controlsDown?.let { down = it }
                    }
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = stringResource(if (badged) R.string.sports_live_sources_status else R.string.sports_live_refresh),
                    tint = Colors.TextSecondary,
                    modifier = Modifier.size(Size.ControlIcon)
                )
                if (newRows > 0) {
                    Text(
                        text = pluralStringResource(R.plurals.sports_live_new_rows, newRows, newRows),
                        style = SportsLiveTokens.style(SportsLiveTokens.Type.ControlSelected),
                        color = Colors.TextPrimary,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
            if (badged) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(SOURCE_DOT)
                        .clip(CircleShape)
                        .background(if (sourceBadge == SportsLiveSourceBadge.AMBER) Colors.WarningAmber else Colors.TextMuted)
                )
            }
        }
    }
}

/** The dot on Refresh (#170). */
private val SOURCE_DOT = 9.dp

/**
 * Results' day in the title (V2 P4): "· Yesterday ‹ ›". The day is a label; ‹ and › are two focus stops, OK
 * steps the day (#153: ← and → only move focus, so the stepper never traps the D-pad). An arrow with no day
 * left that way is dimmed and does nothing.
 */
@Composable
private fun SportsLiveHeaderDateControl(date: SportsLiveHeaderDate, olderModifier: Modifier, newerModifier: Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.sports_live_title_separator),
            style = SportsLiveTokens.style(SportsLiveTokens.Type.Tab),
            color = Colors.TextInactive,
            maxLines = 1
        )
        Text(
            text = date.label,
            style = SportsLiveTokens.style(SportsLiveTokens.Type.Tab),
            color = Colors.TextPrimary,
            maxLines = 1,
            modifier = Modifier.padding(start = 9.dp, end = 4.dp)
        )
        SportsLiveHeaderDayArrow(R.string.sports_live_day_older, date.canOlder, { date.onStep(false) }, olderModifier)
        SportsLiveHeaderDayArrow(R.string.sports_live_day_newer, date.canNewer, { date.onStep(true) }, newerModifier)
    }
}

@Composable
private fun SportsLiveHeaderDayArrow(glyph: Int, enabled: Boolean, onStep: () -> Unit, modifier: Modifier) {
    SportsLiveHeaderControl(
        onClick = { if (enabled) onStep() },
        outline = Color.Transparent,
        modifier = modifier
    ) {
        Text(
            text = stringResource(glyph),
            style = SportsLiveTokens.style(SportsLiveTokens.Type.Tab),
            color = if (enabled) Colors.TextSecondary else Colors.TextInactive.copy(alpha = 0.35f),
            maxLines = 1
        )
    }
}

/**
 * The sources sheet (#170), from Hold OK on Refresh while it has a dot: "‹Source› hasn't responded since 7:42 PM" for each
 * [flagged] source (its display name only, never a host or URL), then **Retry** and **Sources** (the addons screen).
 */
@Composable
internal fun SportsLiveSourcesSheet(
    flagged: List<SportsLiveSourceHealth>,
    onRetry: () -> Unit,
    onOpenSources: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val timeFormat = remember(context) { DateFormat.getTimeFormat(context) }
    val retryFocusRequester = remember { FocusRequester() }
    NuvioDialog(
        onDismiss = onDismiss,
        title = pluralStringResource(R.plurals.sports_live_partial_failure, flagged.size, flagged.size)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Size.RowGap)) {
            flagged.forEach { source ->
                Text(
                    text = source.silentSinceMs
                        ?.let { stringResource(R.string.sports_live_source_silent_since, source.name, timeFormat.format(Date(it))) }
                        ?: source.name,
                    style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta),
                    color = Colors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(SportsLiveTokens.Size.ButtonGap)) {
            SportsLiveButton(
                onClick = {
                    onDismiss()
                    onRetry()
                },
                role = SportsLiveButtonRole.PRIMARY,
                modifier = Modifier.focusRequester(retryFocusRequester)
            ) {
                Text(text = stringResource(R.string.sports_live_source_retry), style = SportsLiveTokens.style(SportsLiveTokens.Type.Button), maxLines = 1)
            }
            SportsLiveButton(
                onClick = {
                    onDismiss()
                    onOpenSources()
                }
            ) {
                Text(text = stringResource(R.string.sports_live_state_sources), style = SportsLiveTokens.style(SportsLiveTokens.Type.Button), maxLines = 1)
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { retryFocusRequester.requestFocus() } }
}

internal fun SportsLiveTab.labelRes(): Int = when (this) {
    SportsLiveTab.LIVE_NOW -> R.string.sports_live_tab_live_now
    SportsLiveTab.UPCOMING -> R.string.sports_live_tab_upcoming
    SportsLiveTab.CHANNELS -> R.string.sports_live_tab_channels
    SportsLiveTab.REPLAYS -> R.string.sports_live_tab_replays
    SportsLiveTab.RESULTS -> R.string.sports_live_tab_results
}
