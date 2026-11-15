package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.domain.repository.SportsLiveFailure

data class SportsLiveUiState(
    val isLoading: Boolean = true,
    val hasClaimedCatalogs: Boolean = false,
    val tabs: Map<SportsLiveTab, List<SportsLiveItem>> = emptyMap(),
    val selectedTab: SportsLiveTab = SportsLiveTab.LIVE_NOW,
    /** Sports present in [selectedTab], sorted alphabetically. */
    val sports: List<String> = emptyList(),
    /** null means All. */
    val selectedSport: String? = null,
    /** [selectedTab]'s items filtered by [selectedSport]. */
    val visibleItems: List<SportsLiveItem> = emptyList(),
    val selectedItemId: String? = null,
    val failures: List<SportsLiveFailure> = emptyList(),
    val lastRefreshedMs: Long? = null
)
