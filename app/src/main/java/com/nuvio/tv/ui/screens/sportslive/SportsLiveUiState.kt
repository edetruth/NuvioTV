package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.SportsLiveMoreLive
import com.nuvio.tv.core.sportslive.scores.MySport
import com.nuvio.tv.core.sportslive.EndedGame
import com.nuvio.tv.core.sportslive.EndedSignal
import com.nuvio.tv.core.sportslive.SportsLiveEndedGames
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.core.sportslive.scores.WhyTag
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.domain.repository.SportsLiveFailure
import com.nuvio.tv.ui.screens.sportslive.dual.SportsLiveDualPick
import com.nuvio.tv.ui.screens.sportslive.dual.SportsLiveDualSession
import com.nuvio.tv.ui.screens.sportslive.dual.offersDual
import com.nuvio.tv.ui.screens.sportslive.dual.sportsLiveWatchWithSession
import java.time.LocalDate

data class SportsLiveUiState(
    val isLoading: Boolean = true,
    /**
     * The first load of this visit has not landed yet (#214): what shows is the saved list or nothing, so an empty tab
     * shows its loading rows, never "Nothing live right now".
     */
    val firstLoadPending: Boolean = false,
    val hasClaimedCatalogs: Boolean = false,
    val tabs: Map<SportsLiveTab, List<SportsLiveItem>> = emptyMap(),
    val selectedTab: SportsLiveTab = SportsLiveTab.LIVE_NOW,
    /**
     * Filter values present in [selectedTab], sorted alphabetically: groups on Channels, sports elsewhere,
     * led by [MY_TEAMS_FILTER] when a followed team plays in the tab.
     */
    val filters: List<String> = emptyList(),
    /** null means All (My sports' games, #146, while it scopes the tab). */
    val selectedFilter: String? = null,
    /** My sports is picked and scopes [selectedTab] (#146): All reads "My sports"; [ALL_SPORTS_FILTER] is offered while it hides games. */
    val mySportsScoped: Boolean = false,
    /** Live now games My sports hides under My sports (#167), for the list's last "N more hidden by My sports · Show all" line; 0: none. */
    val hiddenByMySports: Int = 0,
    /**
     * Each chip's game count (#79): All (null), "★ My teams" and each value of [filters], over the tab's
     * list (Live now leaves its Just ended strip out, as its tab count does); a chip missing here has none.
     */
    val filterCounts: Map<String?, Int> = emptyMap(),
    /** [selectedTab]'s items filtered by [selectedFilter]. */
    val visibleItems: List<SportsLiveItem> = emptyList(),
    val selectedItemId: String? = null,
    val failures: List<SportsLiveFailure> = emptyList(),
    val lastRefreshedMs: Long? = null,
    /** Each source's run of refreshes (#170): the dot on Refresh and the sources sheet. */
    val sources: SportsLiveSources = SportsLiveSources(),
    /**
     * Live now games that are over (#60), by card key: they sit at the end of the Live now list, after
     * the games still live, as the dimmed Just ended strip.
     */
    val justEnded: Map<String, EndedGame> = emptyMap(),
    /**
     * Live now games whose last stream lookup found nothing (#145), by card key: still listed in place,
     * dimmed as "no streams yet", and looked up again later. Never a sign the game is over.
     */
    val noStreams: Set<String> = emptySet(),
    /** The view model's clock when this state was built (it ticks while the list is watched). */
    val nowMs: Long = 0L,
    /** Live now's excitement badges (#43, V2) by card key, with scores shown only; empty otherwise. */
    val whyTags: Map<String, WhyTag> = emptyMap(),
    /**
     * Stale-while-revalidate (live.20 B1): when the list shown was loaded, while a refresh runs over a list over 5 minutes
     * old (the one saved on the device, or the last load); the header reads "Updated ‹time›". Null otherwise.
     */
    val staleListAtMs: Long? = null,
    /** Live now's new rows waiting while it is browsed (TV QA BUG7 / Q5): "N new" on Refresh shows them. */
    val newLiveRows: Int = 0,
    /** A followed team among [newLiveRows]' games, its short name (live.25 M): "Mavericks game started · 13 new — OK to show". */
    val newLiveFollowedTeam: String? = null,
    /**
     * More live (user 2026-11-21): under My sports, the live games Live now leaves out and the listings that are no game
     * (H), by sport, largest first, Other last ([SportsLiveMoreLive]); empty with nothing picked, or on a chip.
     */
    val moreLive: List<SportsLiveMoreLive.Group> = emptyList(),
    /** Its view is open: [visibleItems] are [moreLive]'s games, group by group. */
    val moreLiveOpen: Boolean = false,
    /** The card keys of [moreLive]'s listings that are no game: LIVE only when ESPN confirms. */
    val moreLiveOther: Set<String> = emptySet(),
    /** "You watched ‹sport› · Add to My sports?": the sport its card at the top of Live now offers; null for none. */
    val addSportPrompt: MySport? = null,
    /**
     * live.24: Upcoming's later dates whose games are not sorted yet ([SportsLiveUpcomingWindow]): their day tabs show,
     * and going to one sorts it ([SportsLiveViewModel.showUpcomingDay]).
     */
    val upcomingPendingDates: List<LocalDate> = emptyList(),
    /** live.26 settled screens: Live now was kept as shown this build, past its landing ([SportsLiveSettledRows.settles]). */
    val liveNowSettled: Boolean = false
) {
    /** Each More live game's group key, by card key. */
    val moreLiveGroupOf: Map<String, String> by lazy {
        moreLive.flatMap { group -> group.items.map { it.streamKey to group.key } }.toMap()
    }

    /** The selected chip has no games left (#79): it stays shown with 0 until the user moves, then All. */
    val selectedFilterEmpty: Boolean get() = selectedFilter != null && (filterCounts[selectedFilter] ?: 0) == 0

    /** [item]'s ended verdict when it sits in the Just ended strip (Live now only), else null. */
    fun endedOf(item: SportsLiveItem): EndedGame? =
        if (selectedTab == SportsLiveTab.LIVE_NOW) justEnded[item.streamKey] else null

    /**
     * The list a zap session started here carries: on Live now every game of the tab, whatever the
     * filter (#68), elsewhere [visibleItems].
     */
    val zapItems: List<SportsLiveItem>
        get() = if (selectedTab == SportsLiveTab.LIVE_NOW) tabs[SportsLiveTab.LIVE_NOW].orEmpty() else visibleItems

    /**
     * The games (not channels) live on Live now, its Just ended strip left out: what Results' "N games live
     * now" line counts, so both use one live definition (QA pass 3).
     */
    val liveGameCount: Int
        get() = tabs[SportsLiveTab.LIVE_NOW].orEmpty().count { !it.isChannel && it.streamKey !in justEnded }

    /** How many cards [tab] counts: Live now leaves its Just ended strip out. */
    fun countOf(tab: SportsLiveTab): Int {
        val items = tabs[tab].orEmpty()
        return if (tab == SportsLiveTab.LIVE_NOW) items.count { it.streamKey !in justEnded } else items.size
    }

    companion object {
        /** The "★ My teams" filter: followed teams' games only. Not a sport or group name. */
        const val MY_TEAMS_FILTER = "\u0000my_teams"

        /**
         * "All sports" (#146): every game, My sports' scoping off. Offered only while My sports hides some of the
         * tab's games; kept for this visit only, so the next one opens on My sports (All, null) again.
         */
        const val ALL_SPORTS_FILTER = "\u0000all_sports"
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
        selectedTab == SportsLiveTab.LIVE_NOW && selectedTab.offersDual() && item.kind == SportsLiveKind.EVENTS &&
            endedOf(item) == null && !SportsLiveEndedGames.isEspnFinal(item, snapshot)
    }

/**
 * "Watch with another game" on [item] from the More… menu ([scores]: the delayed snapshots by card key):
 * always the picker (F13), for a game as for a channel, so the viewer sees what goes beside it; null when
 * nothing may.
 */
internal fun SportsLiveUiState.watchWithFor(item: SportsLiveItem, scores: Map<String, ScoreSnapshot>): SportsLiveDualPick? {
    val pick = SportsLiveDualPick(
        chosen = item,
        items = visibleItems,
        filter = selectedFilter,
        tab = selectedTab,
        endedKeys = finishedKeys(scores),
        zapItems = zapItems
    )
    return pick.takeIf { it.candidates.isNotEmpty() }
}

/**
 * The dual session "Watch with another game" starts on the live game [item]: the pick made now, over
 * Live now's games (its list as shown while Live now is the tab), never pairing a game saved with Watch later
 * ([savedForLater], #151).
 */
internal fun SportsLiveUiState.watchWithSession(
    item: SportsLiveItem,
    scores: Map<String, ScoreSnapshot>,
    savedForLater: (SportsLiveItem) -> Boolean = { false },
    mode: ScoresMode? = ScoresMode.SHOW
): SportsLiveDualSession? {
    val liveNow = tabs[SportsLiveTab.LIVE_NOW].orEmpty()
    return sportsLiveWatchWithSession(
        first = item,
        items = if (selectedTab == SportsLiveTab.LIVE_NOW) visibleItems else liveNow,
        zapItems = liveNow,
        filter = selectedFilter.takeIf { selectedTab == SportsLiveTab.LIVE_NOW },
        scores = scores,
        finishedKeys = finishedKeys(scores),
        savedForLater = savedForLater,
        mode = mode
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

/**
 * [new] draws exactly as [old] (#143): at most the focused card's id ([SportsLiveUiState.selectedItemId]) differs, which
 * the screen never reads (its stage follows the focused row). A focus move rebuilds no state ([SportsLiveViewModel]'s
 * fast path keeps the build's clock), so its copy is told apart from a real build by the clock first, then by value.
 */
internal fun sportsLiveSameScreen(old: SportsLiveUiState, new: SportsLiveUiState): Boolean =
    old.nowMs == new.nowMs && old == new.copy(selectedItemId = old.selectedItemId)
