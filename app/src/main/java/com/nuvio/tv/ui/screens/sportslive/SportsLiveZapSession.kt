package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.SportsLiveSportKind
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveTab

/**
 * The list a game was started from in Sports & Live, so the player can flip to the next or previous
 * game. The list is a snapshot taken at launch: on Live now every game of the tab, whatever the filter
 * (#68), so the player's live games panel and its chips can reach them all.
 *
 * Zap order ([zapOrder]): once a chip is picked in the panel ([panelChip]), only that chip's games,
 * whichever tab the session started from (all of them when the session has none of its games);
 * before that, the games of the [filter] the game was started under first, then the rest.
 */
data class SportsLiveZapSession(
    val items: List<SportsLiveItem>,
    val index: Int,
    val tab: SportsLiveTab,
    val filter: String?,
    val returnToHomeOnBack: Boolean,
    /** The live games panel's chip picked during this playback (#68); null until one is picked. */
    val panelChip: ZapPanelChip? = null
) {
    init {
        require(index in items.indices) { "index $index outside ${items.size} items" }
    }

    val current: SportsLiveItem get() = items[index]

    /** Indices of [items] in the order Up/Down zap through them. */
    internal val zapOrder: List<Int> by lazy {
        val chip = panelChip
        if (chip != null) {
            // The panel's chips are Live now's (a sport or My teams), whatever tab the session is on.
            items.indices.filter { items[it].matchesZapFilter(chip.filter, SportsLiveTab.LIVE_NOW) }
                .ifEmpty { items.indices.toList() }
        } else {
            val (first, rest) = items.indices.partition { items[it].matchesZapFilter(filter, tab) }
            first + rest
        }
    }

    /** (1-based position, list size) in the zap order, for the "%1$d of %2$d" label. */
    val positionLabelArgs: Pair<Int, Int>
        get() {
            val position = zapOrder.indexOf(index)
            return if (position >= 0) (position + 1) to zapOrder.size else (index + 1) to items.size
        }

    fun next(): SportsLiveZapSession = copy(index = (index + 1) % items.size)

    fun previous(): SportsLiveZapSession = copy(index = (index - 1 + items.size) % items.size)

    /**
     * The next (or previous) game of the zap order not known to have ended ([SportsLiveItem.hasEnded]);
     * null when there is none. A game outside the order (a chip that leaves it out) zaps to the order's
     * first (or last) game.
     */
    fun step(forward: Boolean, endedKeys: Set<String>): SportsLiveZapSession? {
        val order = zapOrder
        if (order.isEmpty()) return null
        val start = order.indexOf(index)
        var position = if (start >= 0) start else if (forward) -1 else order.size
        repeat(if (start >= 0) order.size - 1 else order.size) {
            position = Math.floorMod(position + if (forward) 1 else -1, order.size)
            val moved = copy(index = order[position])
            if (!moved.current.hasEnded(endedKeys)) return moved
        }
        return null
    }

    /** The session moved to the item whose [SportsLiveItem.streamKey] is [itemKey], or null if absent. */
    fun moveTo(itemKey: String): SportsLiveZapSession? =
        items.indexOfFirst { it.streamKey == itemKey }
            .takeIf { it >= 0 }
            ?.let { copy(index = it) }

    companion object {
        /**
         * A session over a copy of [items], on the item whose [SportsLiveItem.streamKey] is [itemKey];
         * null (no session, so upstream player behavior) when the list is empty or lacks that item.
         */
        fun of(
            items: List<SportsLiveItem>,
            itemKey: String,
            tab: SportsLiveTab,
            filter: String?,
            returnToHomeOnBack: Boolean
        ): SportsLiveZapSession? {
            val snapshot = items.toList()
            if (snapshot.isEmpty()) return null
            return SportsLiveZapSession(snapshot, 0, tab, filter, returnToHomeOnBack).moveTo(itemKey)
        }
    }
}

/** A chip of the player's live games panel (#68): [filter] is a sport or "★ My teams"; null is All. */
data class ZapPanelChip(val filter: String?)

/**
 * True when this item is listed under [filter] on [tab]: null is All, [SportsLiveUiState.MY_TEAMS_FILTER]
 * a followed team's game, otherwise a channel group on Channels and a sport on every other tab.
 */
internal fun SportsLiveItem.matchesZapFilter(filter: String?, tab: SportsLiveTab): Boolean = when (filter) {
    null -> true
    SportsLiveUiState.MY_TEAMS_FILTER -> followed
    else -> if (tab == SportsLiveTab.CHANNELS) group == filter else SportsLiveSportKind.filterKeyOf(this) == filter
}

/**
 * The zap session for the game playing now, process-wide like [com.nuvio.tv.ui.screens.player.SourceFallbackHandoff].
 * A player only zaps when its content id is the session's current item id, so a player opened for
 * anything else keeps upstream behavior. Lost on process death, which leaves upstream behavior too.
 */
internal object SportsLiveZapSessions {
    @Volatile
    private var session: SportsLiveZapSession? = null

    /** Set when a zap navigates, so the player it opens shows the zap banner. */
    @Volatile
    private var zapArrivalPending = false

    fun start(session: SportsLiveZapSession) {
        this.session = session
        zapArrivalPending = false
    }

    fun current(): SportsLiveZapSession? = session

    fun activeFor(contentId: String?): SportsLiveZapSession? =
        session?.takeIf { contentId != null && it.current.id == contentId }

    /** A player opening for [contentId] ends a session that is not on that content. */
    fun keepOnlyFor(contentId: String?) {
        if (activeFor(contentId) == null) clear()
    }

    fun update(session: SportsLiveZapSession) {
        this.session = session
    }

    fun markZapArrival() {
        zapArrivalPending = true
    }

    /** True once after [markZapArrival]: the player opening now was reached by a zap. */
    fun consumeZapArrival(): Boolean = zapArrivalPending.also { zapArrivalPending = false }

    fun clear() {
        session = null
        zapArrivalPending = false
    }
}
