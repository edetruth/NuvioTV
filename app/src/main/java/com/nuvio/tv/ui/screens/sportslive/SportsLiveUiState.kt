package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.EndedGame
import com.nuvio.tv.core.sportslive.EndedSignal
import com.nuvio.tv.core.sportslive.LiveNowSort
import com.nuvio.tv.core.sportslive.SportsLiveEndedGames
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.WhyTag
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.domain.repository.SportsLiveFailure
import com.nuvio.tv.ui.screens.sportslive.dual.SportsLiveDualPick
import com.nuvio.tv.ui.screens.sportslive.dual.SportsLiveDualSession
import com.nuvio.tv.ui.screens.sportslive.dual.offersDual
import com.nuvio.tv.ui.screens.sportslive.dual.sportsLiveWatchWithSession

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
    val nowMs: Long = 0L,
    /** Live now shows its sort control (#43): scores on, not the Timeline. */
    val liveSortOffered: Boolean = false,
    /** Live now's sort in force: Start time while the control is hidden. */
    val liveSort: LiveNowSort = LiveNowSort.START_TIME,
    /**
     * Most exciting: each live game's place (by card key) in the order shown, for the Scoreboard's LIVE
     * group; empty under Start time.
     */
    val liveRank: Map<String, Int> = emptyMap(),
    /** Most exciting with scores shown: a ranked card's why tag, by card key; empty otherwise. */
    val whyTags: Map<String, WhyTag> = emptyMap()
) {
    /** [item]'s ended verdict when it sits in the Just ended strip (Live now only), else null. */
    fun endedOf(item: SportsLiveItem): EndedGame? =
        if (selectedTab == SportsLiveTab.LIVE_NOW) justEnded[item.streamKey] else null

    /**
     * The list a zap session started here carries: on Live now every game of the tab, whatever the
     * filter (#68), elsewhere [visibleItems].
     */
    val zapItems: List<SportsLiveItem>
        get() = if (selectedTab == SportsLiveTab.LIVE_NOW) tabs[SportsLiveTab.LIVE_NOW].orEmpty() else visibleItems

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

/**
 * "Watch with another game" (#57) is offered on [item] ([snapshot]: its delayed score): a live game on
 * Live now, not in Just ended and not an ESPN final (in the postgame grace either); or a channel on a tab
 * with dual screen (Live now, Channels), which opens the picker ([watchWithFor]).
 */
internal fun SportsLiveUiState.offersWatchWith(item: SportsLiveItem, snapshot: ScoreSnapshot?): Boolean =
    if (item.isChannel) {
        selectedTab.offersDual()
    } else {
        selectedTab == SportsLiveTab.LIVE_NOW && item.kind == SportsLiveKind.EVENTS &&
            endedOf(item) == null && !SportsLiveEndedGames.isEspnFinal(item, snapshot)
    }

/** What "Watch with another game" does on a card (#57). */
internal sealed interface SportsLiveWatchWith {
    /** A live game: dual screen at once, beside the game picked for it. */
    data class Paired(val session: SportsLiveDualSession) : SportsLiveWatchWith

    /** A channel: no game to pair it with by itself, so the picker lists what may go beside it. */
    data class Pick(val pick: SportsLiveDualPick) : SportsLiveWatchWith
}

/** "Watch with another game" on [item] ([scores]: the delayed snapshots by card key); null: nothing to pair with. */
internal fun SportsLiveUiState.watchWithFor(item: SportsLiveItem, scores: Map<String, ScoreSnapshot>): SportsLiveWatchWith? =
    if (item.isChannel) {
        SportsLiveWatchWith.Pick(
            SportsLiveDualPick(
                chosen = item,
                items = visibleItems,
                filter = selectedFilter,
                tab = selectedTab,
                endedKeys = finishedKeys(scores),
                zapItems = zapItems
            )
        )
    } else {
        watchWithSession(item, scores)?.let(SportsLiveWatchWith::Paired)
    }

/**
 * The dual session "Watch with another game" starts on the live game [item]: the pick made now, over
 * Live now's games (its list as shown while Live now is the tab).
 */
internal fun SportsLiveUiState.watchWithSession(item: SportsLiveItem, scores: Map<String, ScoreSnapshot>): SportsLiveDualSession? {
    val liveNow = tabs[SportsLiveTab.LIVE_NOW].orEmpty()
    return sportsLiveWatchWithSession(
        first = item,
        items = if (selectedTab == SportsLiveTab.LIVE_NOW) visibleItems else liveNow,
        zapItems = liveNow,
        filter = selectedFilter.takeIf { selectedTab == SportsLiveTab.LIVE_NOW },
        scores = scores,
        finishedKeys = finishedKeys(scores)
    )
}

/**
 * Every source key of the Live now games known to be over: the Just ended strip, and ESPN finals still
 * in their postgame grace ([scores]: the delayed snapshots by card key). Dual screen never offers them.
 */
internal fun SportsLiveUiState.finishedKeys(scores: Map<String, ScoreSnapshot>): Set<String> =
    tabs[SportsLiveTab.LIVE_NOW].orEmpty()
        .filter { it.streamKey in justEnded || SportsLiveEndedGames.isEspnFinal(it, scores[it.streamKey]) }
        .flatMapTo(HashSet()) { item -> (listOf(item) + item.alternates).map { it.streamKey } }
