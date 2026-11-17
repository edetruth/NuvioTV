package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.EndedGame
import com.nuvio.tv.core.sportslive.EndedSignal
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
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
    val lastRefreshedMs: Long? = null,
    /**
     * Live now games that are over (#60), by card key: they sit at the end of the Live now list, after
     * the games still live, as the dimmed Just ended strip.
     */
    val justEnded: Map<String, EndedGame> = emptyMap(),
    /** The view model's clock when this state was built (it ticks while the list is watched). */
    val nowMs: Long = 0L
) {
    /** [item]'s ended verdict when it sits in the Just ended strip (Live now only), else null. */
    fun endedOf(item: SportsLiveItem): EndedGame? =
        if (selectedTab == SportsLiveTab.LIVE_NOW) justEnded[item.streamKey] else null

    /** How many cards [tab] counts: Live now leaves its Just ended strip out. */
    fun countOf(tab: SportsLiveTab): Int {
        val items = tabs[tab].orEmpty()
        return if (tab == SportsLiveTab.LIVE_NOW) items.count { it.streamKey !in justEnded } else items.size
    }

    companion object {
        /** The "★ My teams" filter: followed teams' games only. Not a sport or group name. */
        const val MY_TEAMS_FILTER = "\u0000my_teams"
    }
}

/** What OK does on a Live now card (#60). */
internal sealed interface SportsLiveCardOk {
    /** The card's usual play (an estimate can be wrong, and the stream may still work). */
    data object Play : SportsLiveCardOk

    /** An ESPN-ended game in the Just ended strip: its Game page. */
    data class OpenGame(val event: ScoreSnapshot) : SportsLiveCardOk
}

/** OK on a card with [ended] (its Just ended verdict, null while live): an ESPN final opens its Game page. */
internal fun sportsLiveCardOk(ended: EndedGame?): SportsLiveCardOk {
    val event = ended?.takeIf { it.signal == EndedSignal.FINAL }?.event ?: return SportsLiveCardOk.Play
    return SportsLiveCardOk.OpenGame(event)
}
