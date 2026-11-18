package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.SportsLiveLayout
import com.nuvio.tv.domain.model.SportsLiveTab

/**
 * The section header's second row and its focus order (C2): on Live now and Upcoming the layout switch
 * (Cards | Scoreboard | Timeline) leads the filter-chip row, so Down from the tabs reaches it without
 * crossing another tab (tabs select on focus). The switch: Up the tabs, Right the filter chips, Down
 * the content. Results has its own header; Channels and Replays show no switch. Live now's sort control
 * (#43) sits between the switch and the chips: switch → Right → sort → Right → chips.
 */
internal data class SportsLiveHeaderFocus(val switchShown: Boolean, val chipsShown: Boolean, val sortShown: Boolean = false) {

    enum class Stop { TABS, SWITCH, CHIPS, SORT, CONTENT }

    /** The second row shows: the switch, the chips, the sort, or several. */
    val rowShown: Boolean get() = switchShown || chipsShown || sortShown

    /** Down from a tab (or Refresh). */
    val tabsDown: Stop
        get() = when {
            switchShown -> Stop.SWITCH
            chipsShown -> Stop.CHIPS
            else -> Stop.CONTENT
        }

    val switchUp: Stop get() = Stop.TABS
    val switchDown: Stop get() = Stop.CONTENT

    /** Right from the switch's last segment; null when nothing follows it. */
    val switchRight: Stop?
        get() = when {
            sortShown -> Stop.SORT
            chipsShown -> Stop.CHIPS
            else -> null
        }

    /** Right from the sort's last segment; null when no chips follow it. */
    val sortRight: Stop? get() = Stop.CHIPS.takeIf { chipsShown }

    /** Up from the content's first row. */
    val contentUp: Stop
        get() = when {
            chipsShown -> Stop.CHIPS
            switchShown -> Stop.SWITCH
            sortShown -> Stop.SORT
            else -> Stop.TABS
        }

    companion object {
        /** [tab]'s header with [filterCount] filter values (the chips show with two or more); [sortOffered]: Live now's sort (#43). */
        fun of(tab: SportsLiveTab, filterCount: Int, sortOffered: Boolean = false): SportsLiveHeaderFocus = SportsLiveHeaderFocus(
            switchShown = tab != SportsLiveTab.RESULTS && SportsLiveLayout.offeredFor(tab).size >= 2,
            chipsShown = tab != SportsLiveTab.RESULTS && filterCount >= 2,
            sortShown = tab == SportsLiveTab.LIVE_NOW && sortOffered
        )
    }
}
