package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.alerts.GameReminders
import com.nuvio.tv.core.sportslive.alerts.RemindersState
import com.nuvio.tv.core.sportslive.displayName
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveTab

/** Why a tab's list is empty (#134): each one names its own next step. */
internal enum class SportsLiveEmptyKind {
    /** The tab has games, but not under the chip picked: Show all. */
    FILTER_EMPTY,
    /** The first load has not landed yet (#214): loading rows, never an empty card. */
    LOADING,
    /** Every source failed and nothing is listed anywhere: Try again, or the addons. */
    UNREACHABLE,
    /** Live Now: the next game with See Upcoming (and Remind me). */
    NOTHING_LIVE,
    NOTHING_UPCOMING,
    NO_CHANNELS,
    NO_REPLAYS
}

/** An empty tab's card: its [kind], and on Live Now the [next] game listed on Upcoming (null when none is). */
internal data class SportsLiveEmptyState(val kind: SportsLiveEmptyKind, val next: SportsLiveItem? = null)

/**
 * What an empty tab says (#134), never just "Nothing here": a chip that hides the tab's games offers Show all;
 * sources that all failed with nothing loaded say so; otherwise each tab points at the next useful one. Live Now
 * names the next game Upcoming lists ([nowMs]: only one not started yet).
 */
internal fun sportsLiveEmptyState(state: SportsLiveUiState, nowMs: Long): SportsLiveEmptyState {
    val tab = state.selectedTab
    if (state.tabs[tab].orEmpty().isNotEmpty() && state.visibleItems.isEmpty()) return SportsLiveEmptyState(SportsLiveEmptyKind.FILTER_EMPTY)
    if (state.firstLoadPending) return SportsLiveEmptyState(SportsLiveEmptyKind.LOADING)
    if (state.failures.isNotEmpty() && state.tabs.values.all { it.isEmpty() }) return SportsLiveEmptyState(SportsLiveEmptyKind.UNREACHABLE)
    return when (tab) {
        SportsLiveTab.LIVE_NOW -> SportsLiveEmptyState(
            SportsLiveEmptyKind.NOTHING_LIVE,
            next = state.tabs[SportsLiveTab.UPCOMING].orEmpty()
                .filter { !it.isChannel && (it.startMs ?: 0L) > nowMs }
                .minByOrNull { it.startMs ?: Long.MAX_VALUE }
        )
        SportsLiveTab.UPCOMING -> SportsLiveEmptyState(SportsLiveEmptyKind.NOTHING_UPCOMING)
        SportsLiveTab.CHANNELS -> SportsLiveEmptyState(SportsLiveEmptyKind.NO_CHANNELS)
        // Results draws its own card; Replays and anything else.
        SportsLiveTab.REPLAYS, SportsLiveTab.RESULTS -> SportsLiveEmptyState(SportsLiveEmptyKind.NO_REPLAYS)
    }
}

/**
 * The empty tab's card ([sportsLiveEmptyState]). [onSelectTab] / [onShowAll] / [onRetry] / [onOpenAddons] are its
 * next steps.
 */
@Composable
internal fun SportsLiveEmptyCard(
    empty: SportsLiveEmptyState,
    nowMs: Long,
    reminders: RemindersState,
    onSelectTab: (SportsLiveTab) -> Unit,
    onShowAll: () -> Unit,
    onRetry: () -> Unit,
    onOpenAddons: () -> Unit,
    onToggleReminder: (SportsLiveItem) -> Unit,
    modifier: Modifier = Modifier,
    primaryModifier: Modifier = Modifier
) {
    when (empty.kind) {
        SportsLiveEmptyKind.LOADING -> SportsLiveSkeletonRows(modifier = modifier)
        SportsLiveEmptyKind.FILTER_EMPTY -> SportsLiveStateCard(
            title = stringResource(R.string.sports_live_state_filter_empty),
            line = stringResource(R.string.sports_live_state_filter_empty_line),
            primary = SportsLiveStateAction(stringResource(R.string.sports_live_state_show_all), onShowAll),
            modifier = modifier,
            primaryModifier = primaryModifier
        )
        SportsLiveEmptyKind.UNREACHABLE -> SportsLiveStateCard(
            title = stringResource(R.string.sports_live_state_unreachable),
            line = stringResource(R.string.sports_live_state_unreachable_line),
            primary = SportsLiveStateAction(stringResource(R.string.sports_live_state_try_again), onRetry),
            secondary = SportsLiveStateAction(stringResource(R.string.sports_live_state_sources), onOpenAddons),
            problem = true,
            modifier = modifier,
            primaryModifier = primaryModifier
        )
        SportsLiveEmptyKind.NOTHING_LIVE -> {
            val next = empty.next
            val remindable = next != null && GameReminders.canRemind(next, nowMs)
            SportsLiveStateCard(
                title = stringResource(R.string.sports_live_state_nothing_live),
                line = next?.let { stringResource(R.string.sports_live_state_next, it.displayName, agendaTimeText(it.startMs ?: nowMs)) }
                    ?: stringResource(R.string.sports_live_state_nothing_live_line),
                primary = SportsLiveStateAction(stringResource(R.string.sports_live_state_see_upcoming), { onSelectTab(SportsLiveTab.UPCOMING) }),
                secondary = if (next != null && remindable) {
                    val reminded = reminders.isReminded(next)
                    SportsLiveStateAction(
                        stringResource(if (reminded) R.string.sports_live_cancel_reminder else R.string.sports_live_remind_me),
                        { onToggleReminder(next) },
                        bell = true
                    )
                } else {
                    null
                },
                modifier = modifier,
                primaryModifier = primaryModifier
            )
        }
        SportsLiveEmptyKind.NOTHING_UPCOMING -> SportsLiveStateCard(
            title = stringResource(R.string.sports_live_state_nothing_upcoming),
            line = stringResource(R.string.sports_live_state_nothing_upcoming_line),
            primary = SportsLiveStateAction(stringResource(R.string.sports_live_state_see_live_now), { onSelectTab(SportsLiveTab.LIVE_NOW) }),
            modifier = modifier,
            primaryModifier = primaryModifier
        )
        SportsLiveEmptyKind.NO_CHANNELS -> SportsLiveStateCard(
            title = stringResource(R.string.sports_live_state_no_channels),
            line = stringResource(R.string.sports_live_state_no_channels_line),
            primary = SportsLiveStateAction(stringResource(R.string.sports_live_state_see_live_now), { onSelectTab(SportsLiveTab.LIVE_NOW) }),
            modifier = modifier,
            primaryModifier = primaryModifier
        )
        SportsLiveEmptyKind.NO_REPLAYS -> SportsLiveStateCard(
            title = stringResource(R.string.sports_live_state_no_replays),
            line = stringResource(R.string.sports_live_state_no_replays_line),
            primary = SportsLiveStateAction(stringResource(R.string.sports_live_state_see_results), { onSelectTab(SportsLiveTab.RESULTS) }),
            modifier = modifier,
            primaryModifier = primaryModifier
        )
    }
}
