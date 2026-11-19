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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

/**
 * Live now's Resume chip in the header (#148): [label], OK [onOpen]; Back on it or its ✕ [onDismiss].
 * [live]: the game is still on (the play glyph); else it ended and OK opens its Game page.
 */
internal data class SportsLiveHeaderResume(val label: String, val live: Boolean, val onOpen: () -> Unit, val onDismiss: () -> Unit)

private val FILTER_TABS = setOf(SportsLiveTab.UPCOMING, SportsLiveTab.CHANNELS, SportsLiveTab.REPLAYS)

/**
 * The header's focus stops, left to right: the active tab, Results' day arrows, Live now's Resume chip and its
 * ✕ (#148), the filter, "⚠ N", Refresh.
 */
internal enum class SportsLiveHeaderStop { TAB, DAY_OLDER, DAY_NEWER, RESUME, RESUME_CLOSE, FILTER, FAILURES, REFRESH }

/**
 * The stop ← ([right] false) or → ([right] true) moves to from [stop] (#153): the next stop shown that way, or
 * null at the ends. ← from the first control past the tabs is the active tab, never the nearest tab (tabs select
 * on focus); the day arrows never hold the key, so the D-pad always gets back to the tabs.
 */
internal fun sportsLiveHeaderNeighbour(
    stop: SportsLiveHeaderStop,
    right: Boolean,
    hasDate: Boolean,
    hasFilter: Boolean,
    hasFailures: Boolean,
    hasResume: Boolean = false
): SportsLiveHeaderStop? {
    val shown = SportsLiveHeaderStop.entries.filter {
        when (it) {
            SportsLiveHeaderStop.DAY_OLDER, SportsLiveHeaderStop.DAY_NEWER -> hasDate
            SportsLiveHeaderStop.RESUME, SportsLiveHeaderStop.RESUME_CLOSE -> hasResume
            SportsLiveHeaderStop.FILTER -> hasFilter
            SportsLiveHeaderStop.FAILURES -> hasFailures
            else -> true
        }
    }
    val index = shown.indexOf(stop).takeIf { it >= 0 } ?: return null
    return shown.getOrNull(if (right) index + 1 else index - 1)
}

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
    /** Draws the filter as one dropdown (P4); null keeps the inline pill. */
    filterDropdown: SportsLiveHeaderDropdown? = null,
    /** Results' day in the title (P4), right after the tabs; null for none. */
    date: SportsLiveHeaderDate? = null,
    dateFocusRequester: FocusRequester = remember { FocusRequester() },
    /** Live now's Resume chip (#148); null for none. */
    resume: SportsLiveHeaderResume? = null,
    onShowFailures: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activeTabFocusRequester = tabFocusRequesters.getValue(selectedTab)
    val downModifier = Modifier.focusProperties { downFocusRequester?.let { down = it } }
    // F6 (#133): Right past the filter's last option goes to the next header control, never down into the stage.
    val failuresFocusRequester = remember { FocusRequester() }
    val refreshFocusRequester = remember { FocusRequester() }
    val dayNewerFocusRequester = remember { FocusRequester() }
    val resumeFocusRequester = remember { FocusRequester() }
    val resumeCloseFocusRequester = remember { FocusRequester() }
    // The Resume chip expiring (or its game leaving Live now) while focused hands focus to the active tab.
    var resumeFocused by remember { mutableStateOf(false) }
    LaunchedEffect(resume == null) {
        if (resume != null || !resumeFocused) return@LaunchedEffect
        resumeFocused = false
        withFrameNanos { }
        runCatching { activeTabFocusRequester.requestFocus() }
    }
    fun requesterOf(stop: SportsLiveHeaderStop?): FocusRequester = when (stop) {
        SportsLiveHeaderStop.DAY_OLDER -> dateFocusRequester
        SportsLiveHeaderStop.DAY_NEWER -> dayNewerFocusRequester
        SportsLiveHeaderStop.RESUME -> resumeFocusRequester
        SportsLiveHeaderStop.RESUME_CLOSE -> resumeCloseFocusRequester
        SportsLiveHeaderStop.FILTER -> filterFocusRequester
        SportsLiveHeaderStop.FAILURES -> failuresFocusRequester
        SportsLiveHeaderStop.REFRESH -> refreshFocusRequester
        SportsLiveHeaderStop.TAB, null -> activeTabFocusRequester
    }
    fun neighbour(stop: SportsLiveHeaderStop, right: Boolean): FocusRequester = requesterOf(
        sportsLiveHeaderNeighbour(
            stop,
            right,
            hasDate = date != null,
            hasFilter = filterOptions != null,
            hasFailures = failures > 0,
            hasResume = resume != null
        )
    )
    Row(
        modifier = modifier
            // Focus gone from the whole header: the chip no longer holds it.
            .onFocusChanged { if (!it.hasFocus) resumeFocused = false }
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
            Row(horizontalArrangement = Arrangement.spacedBy(Size.ControlGap), verticalAlignment = Alignment.CenterVertically) {
                if (resume != null) {
                    fun stopModifier(stop: SportsLiveHeaderStop, requester: FocusRequester) = Modifier
                        .onFocusChanged { if (it.isFocused) resumeFocused = true }
                        .focusRequester(requester)
                        .focusProperties {
                            left = neighbour(stop, right = false)
                            right = neighbour(stop, right = true)
                            downFocusRequester?.let { down = it }
                        }
                        // Back on the chip or its ✕ dismisses it; focus goes back to the active tab first.
                        .onPreviewKeyEvent { event ->
                            val back = event.key == Key.Back || event.key == Key.Escape
                            if (back && event.type == KeyEventType.KeyUp) {
                                resumeFocused = false
                                runCatching { activeTabFocusRequester.requestFocus() }
                                resume.onDismiss()
                            }
                            back
                        }
                    SportsLiveResumeControl(
                        resume = resume,
                        // A long game name ellipsizes: the chip never pushes the tabs.
                        chipModifier = stopModifier(SportsLiveHeaderStop.RESUME, resumeFocusRequester).widthIn(max = RESUME_CHIP_MAX_WIDTH),
                        closeModifier = stopModifier(SportsLiveHeaderStop.RESUME_CLOSE, resumeCloseFocusRequester),
                        onClose = {
                            resumeFocused = false
                            runCatching { activeTabFocusRequester.requestFocus() }
                            resume.onDismiss()
                        }
                    )
                }
                if (filterOptions != null) {
                    val filterModifier = Modifier
                        .focusRequester(filterFocusRequester)
                        .focusProperties {
                            left = neighbour(SportsLiveHeaderStop.FILTER, right = false)
                            right = neighbour(SportsLiveHeaderStop.FILTER, right = true)
                            downFocusRequester?.let { down = it }
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

/** The Resume chip's widest (#148): room for "Resume Devils vs Canadiens · Live"; longer names ellipsize. */
private val RESUME_CHIP_MAX_WIDTH = 340.dp

/** Live now's Resume chip (#148): "▶ Resume <game> · Live" (or "<game> · Final 2–3") and a round ✕. */
@Composable
private fun SportsLiveResumeControl(resume: SportsLiveHeaderResume, chipModifier: Modifier, closeModifier: Modifier, onClose: () -> Unit) {
    SportsLiveHeaderControl(onClick = resume.onOpen, round = false, modifier = chipModifier) {
        if (resume.live) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = Colors.TextPrimary,
                modifier = Modifier.size(Size.ControlIcon)
            )
        }
        Text(
            text = resume.label,
            style = SportsLiveTokens.style(SportsLiveTokens.Type.ControlSelected),
            color = Colors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
    SportsLiveHeaderControl(onClick = onClose, modifier = closeModifier) {
        Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = stringResource(R.string.sports_live_resume_dismiss),
            tint = Colors.TextSecondary,
            modifier = Modifier.size(Size.ControlIcon)
        )
    }
}

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
