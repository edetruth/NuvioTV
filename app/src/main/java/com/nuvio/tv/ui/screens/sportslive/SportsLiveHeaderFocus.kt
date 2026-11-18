package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.SportsLiveLayout
import com.nuvio.tv.domain.model.SportsLiveTab

/**
 * The section header's second row and its focus order (C2, #79): on Live now and Upcoming the View
 * button (layout and sort in one menu) leads the filter-chip row, so the order is tabs → View → chips
 * → content. Down from the tabs reaches View without crossing another tab (tabs select on focus); View:
 * Up the tabs, Right the selected chip, Down the content. Results has its own header; Channels and
 * Replays show no View button.
 */
internal data class SportsLiveHeaderFocus(val viewShown: Boolean, val chipsShown: Boolean) {

    enum class Stop { TABS, VIEW, CHIPS, CONTENT }

    /** The second row shows: the View button, the chips, or both. */
    val rowShown: Boolean get() = viewShown || chipsShown

    /** Down from a tab (or Refresh). */
    val tabsDown: Stop
        get() = when {
            viewShown -> Stop.VIEW
            chipsShown -> Stop.CHIPS
            else -> Stop.CONTENT
        }

    val viewUp: Stop get() = Stop.TABS
    val viewDown: Stop get() = Stop.CONTENT

    /** Right from View: the selected chip; null when no chips follow it. */
    val viewRight: Stop? get() = Stop.CHIPS.takeIf { chipsShown }

    /** Up from the content's first row. */
    val contentUp: Stop
        get() = when {
            chipsShown -> Stop.CHIPS
            viewShown -> Stop.VIEW
            else -> Stop.TABS
        }

    companion object {
        /** [tab]'s header with [filterCount] filter values (the chips show with two or more). */
        fun of(tab: SportsLiveTab, filterCount: Int): SportsLiveHeaderFocus = SportsLiveHeaderFocus(
            viewShown = tab != SportsLiveTab.RESULTS && SportsLiveLayout.offeredFor(tab).size >= 2,
            chipsShown = tab != SportsLiveTab.RESULTS && filterCount >= 2
        )
    }
}
