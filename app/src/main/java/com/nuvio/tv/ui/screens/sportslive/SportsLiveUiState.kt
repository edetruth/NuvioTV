package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.domain.repository.SportsLiveFailure

data class SportsLiveUiState(
    val isLoading: Boolean = true,
    val hasClaimedCatalogs: Boolean = false,
    val tabs: Map<SportsLiveTab, List<SportsLiveItem>> = emptyMap(),
    val selectedTab: SportsLiveTab = SportsLiveTab.LIVE_NOW,
    /**
     * Filter values present in [selectedTab], sorted alphabetically: groups on Channels, sports elsewhere,
     * led by [MY_TEAMS_FILTER] when a followed team plays in the tab.
     */
    val filters: List<String> = emptyList(),
    /** null means All. */
    val selectedFilter: String? = null,
    /** [selectedTab]'s items filtered by [selectedFilter]. */
    val visibleItems: List<SportsLiveItem> = emptyList(),
    val selectedItemId: String? = null,
    val failures: List<SportsLiveFailure> = emptyList(),
    val lastRefreshedMs: Long? = null
) {
    companion object {
        /** The "★ My teams" filter: followed teams' games only. Not a sport or group name. */
        const val MY_TEAMS_FILTER = "\u0000my_teams"
    }
}
