package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.SportsLiveLayout
import com.nuvio.tv.domain.model.SportsLiveTab

/**
 * The section header's second row and its focus order (C2): on Live now and Upcoming the layout switch
 * (Cards | Scoreboard | Timeline) leads the filter-chip row, so Down from the tabs reaches it without
 * crossing another tab (tabs select on focus). The switch: Up the tabs, Right the filter chips, Down
 * the content. Results has its own header; Channels and Replays show no switch.
 */
internal data class SportsLiveHeaderFocus(val switchShown: Boolean, val chipsShown: Boolean) {

    enum class Stop { TABS, SWITCH, CHIPS, CONTENT }

    /** The second row shows: the switch, the chips, or both. */
    val rowShown: Boolean get() = switchShown || chipsShown

    /** Down from a tab (or Refresh). */
    val tabsDown: Stop
        get() = when {
            switchShown -> Stop.SWITCH
            chipsShown -> Stop.CHIPS
            else -> Stop.CONTENT
        }

    val switchUp: Stop get() = Stop.TABS
    val switchDown: Stop get() = Stop.CONTENT

    /** Right from the switch's last segment; null when no chips follow it. */
    val switchRight: Stop? get() = Stop.CHIPS.takeIf { chipsShown }

    /** Up from the content's first row. */
    val contentUp: Stop
        get() = when {
            chipsShown -> Stop.CHIPS
            switchShown -> Stop.SWITCH
            else -> Stop.TABS
        }

    companion object {
        /** [tab]'s header with [filterCount] filter values (the chips show with two or more). */
        fun of(tab: SportsLiveTab, filterCount: Int): SportsLiveHeaderFocus = SportsLiveHeaderFocus(
            switchShown = tab != SportsLiveTab.RESULTS && SportsLiveLayout.offeredFor(tab).size >= 2,
            chipsShown = tab != SportsLiveTab.RESULTS && filterCount >= 2
        )
    }
}
