package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.SportsLiveSportKind
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveTab

/**
 * The list a game was started from in Sports & Live, so the player can flip to the next or previous
 * game. The list is a snapshot taken at launch: on Live now every game of the tab, whatever the filter
 * (#68), so the player's live games panel and its chips can reach them all.
 *
 * Zap order ([zapOrder]): on Live now, the player panel's one ordered list ([lineup], #142: same chip,
 * groups and order as the panel shows), once the player knows it ([withLineup]). Otherwise (another tab, or
 * before the panel's list is known): once a chip is picked in the panel ([panelChip]), only that chip's
 * games, whichever tab the session started from (all of them when the session has none of its games);
 * before that, the games of the [filter] the game was started under first, then the rest.
 */
data class SportsLiveZapSession(
    val items: List<SportsLiveItem>,
    val index: Int,
    val tab: SportsLiveTab,
    val filter: String?,
    val returnToHomeOnBack: Boolean,
    /** The live games panel's chip picked during this playback (#68); null until one is picked. */
    val panelChip: ZapPanelChip? = null,
    /** The panel's rows (stream keys) in the order it lists them (#142); null until [withLineup]. */
    val lineup: List<String>? = null,
    /** Rows of [lineup] CH+/CH− pass over (F11): finals and just ended games, which the panel still lists. */
    val skipped: Set<String> = emptySet(),
    /** Opened full screen from dual screen: Back returns to dual with both games (coordinator decision 1). */
    val dualReturn: com.nuvio.tv.ui.screens.sportslive.dual.SportsLiveDualReturn? = null,
    /**
     * Live Now's order as shown when the game was started there (live.25, #225: stream keys, calm order included); null
     * from anywhere else (Home, an alert, Upcoming, a Game page: the shared order fresh). The panel lists its games in this
     * order ([panelLineup]), so CH± and the guide follow the list you launched from.
     */
    val seed: List<String>? = null
) {
    init {
        require(index in items.indices) { "index $index outside ${items.size} items" }
    }

    val current: SportsLiveItem get() = items[index]

    /** Indices of [items] in the order Up/Down zap through them. */
    internal val zapOrder: List<Int> by lazy {
        val chip = panelChip
        val listed = lineup
        if (listed != null) {
            // The panel's list (#142): items were set to it, in its order ([withLineup]).
            val indexOf = items.withIndex().associate { (index, item) -> item.streamKey to index }
            listed.mapNotNull { indexOf[it] }
        } else if (chip != null) {
            // The panel's chips are Live now's (a sport or My teams), whatever tab the session is on.
            items.indices.filter { items[it].matchesZapFilter(chip.filter, SportsLiveTab.LIVE_NOW) }
                .ifEmpty { items.indices.toList() }
        } else {
            val (first, rest) = items.indices.partition { items[it].matchesZapFilter(filter, tab) }
            first + rest
        }
    }

    /**
     * (1-based position, count) for the "%1$d of %2$d" label: among the games CH± stops on (#145), so finished
     * ones ([skipped]) are not counted and the count is the panel's and the section's. A finished game playing
     * takes the place after the zappable games before it.
     */
    val positionLabelArgs: Pair<Int, Int>
        get() {
            val order = zapOrder
            val at = order.indexOf(index)
            if (at < 0) return (index + 1) to items.size
            val counted = order.filter { items[it].streamKey !in skipped }
            val before = order.subList(0, at).count { items[it].streamKey !in skipped }
            return (before + 1) to counted.size
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
            if (!moved.current.hasEnded(endedKeys) && moved.current.streamKey !in skipped) return moved
        }
        return null
    }

    /**
     * The session on the player panel's one ordered list (#142): [rows] as the panel lists them (its chip,
     * V2 groups, followed first, the order frozen under focus), so the next zap (CH− / ▼) lands on the row
     * below the game playing and the previous one (CH+ / ▲) on the row above; finals stay listed but are passed over. The game playing stays
     * current: when the list leaves it out (a chip without it), it is kept after the list, outside the
     * order, so CH+/CH− go to the list's first or last game. Only a Live now session takes it (the panel
     * lists Live now's games); with no rows, unchanged.
     */
    internal fun withLineup(rows: List<ZapPanelRow>): SportsLiveZapSession {
        if (tab != SportsLiveTab.LIVE_NOW || rows.isEmpty()) return this
        val listed = rows.map { it.item }
        val here = listed.indexOfFirst { it.streamKey == current.streamKey }
        return copy(
            items = if (here >= 0) listed else listed + current,
            index = if (here >= 0) here else listed.size,
            lineup = listed.map { it.streamKey },
            skipped = rows.filterNot { it.zappable }.mapTo(HashSet()) { it.item.streamKey }
        )
    }

    /**
     * [withLineup], frozen (BUG6/Q10): with no [lineup] yet, the panel's [rows] are taken as they are. Once taken, the
     * order is kept: a game new to [rows] is added at the end, a game gone from them is passed over (as finals are),
     * games keep their places and the game playing stays current. So the banner's ▲/▼ names and the next CH± agree,
     * however the list moves meanwhile. [PlayerZapController.thaw] and a chip pick take the order afresh.
     */
    internal fun withFrozenLineup(rows: List<ZapPanelRow>): SportsLiveZapSession {
        val frozen = lineup
        if (frozen == null || tab != SportsLiveTab.LIVE_NOW || rows.isEmpty()) return withLineup(rows)
        val freshByKey = rows.associateBy { it.item.streamKey }
        val listed = frozen.toHashSet()
        val added = rows.filter { it.item.streamKey !in listed }
        val itemKeys = items.mapTo(HashSet()) { it.streamKey }
        return copy(
            // Rows update in place (a game's newer copy); new games go after the rest.
            items = items.map { freshByKey[it.streamKey]?.item ?: it } +
                added.filter { it.item.streamKey !in itemKeys }.map { it.item },
            lineup = frozen + added.map { it.item.streamKey },
            skipped = rows.filterNot { it.zappable }.mapTo(HashSet()) { it.item.streamKey } +
                frozen.filter { it !in freshByKey }
        )
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

/**
 * The list a zap session started on [tab] walks (live.24, audit G14/G15): Channels and Replays their own list
 * ([listed]), Live now its games; every other tab (Upcoming, a game just started there) the Live now games ([liveNow]),
 * with [item] first when they lack it, never the games that have not started. The session freezes its order at the
 * first zap ([SportsLiveZapSession.withFrozenLineup]).
 */
internal fun sportsLiveZapSource(
    tab: SportsLiveTab,
    item: SportsLiveItem,
    listed: List<SportsLiveItem>,
    liveNow: List<SportsLiveItem>
): Pair<List<SportsLiveItem>, SportsLiveTab> = when (tab) {
    SportsLiveTab.LIVE_NOW, SportsLiveTab.CHANNELS, SportsLiveTab.REPLAYS -> listed to tab
    else -> (if (liveNow.any { it.streamKey == item.streamKey }) liveNow else listOf(item) + liveNow) to SportsLiveTab.LIVE_NOW
}

/** A chip of the player's live games panel (#68): [filter] is a sport or "★ My teams"; null is All. */
data class ZapPanelChip(val filter: String?)

/**
 * True when this item is listed under [filter] on [tab]: null (and All sports, #146) is All,
 * [SportsLiveUiState.MY_TEAMS_FILTER] a followed team's game, otherwise a channel group on Channels and a
 * sport on every other tab.
 */
internal fun SportsLiveItem.matchesZapFilter(filter: String?, tab: SportsLiveTab): Boolean = when (filter) {
    null, SportsLiveUiState.ALL_SPORTS_FILTER -> true
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
        // J7: the panel's "Just watched" line reads the games played this app session.
        com.nuvio.tv.core.sportslive.SportsLiveJustWatched.shared.played(session.current)
        zapArrivalPending = false
        // A new session tries every game afresh (#138 follow-up).
        com.nuvio.tv.core.sportslive.SportsLiveTriedGames.shared.clear()
    }

    fun current(): SportsLiveZapSession? = session

    fun activeFor(contentId: String?): SportsLiveZapSession? =
        session?.takeIf { contentId != null && it.current.id == contentId }

    /** A player opening for [contentId] ends a session that is not on that content. */
    fun keepOnlyFor(contentId: String?) {
        if (activeFor(contentId) != null) return
        // live.25 I: a player a zap opened must find its session; never lose CH±, the guide and the sports menu silently.
        if (zapArrivalPending) {
            com.nuvio.tv.core.sportslive.SportsLiveSpeedClock.stage("noSession", "zapArrival=true session=${if (session == null) "none" else "otherGame"}")
        }
        clear()
    }

    fun update(session: SportsLiveZapSession) {
        if (this.session?.current?.streamKey != session.current.streamKey) {
            com.nuvio.tv.core.sportslive.SportsLiveJustWatched.shared.played(session.current)
        }
        this.session = session
    }

    /**
     * The one way a Sports & Live Watch outside Live now's own list opens the full player (#173 follow-up):
     * an alert or kickoff prompt from any screen, the Game page, a Team page, the Resume chip, the Upcoming
     * stage. The session already on [item] (Live now's, Home's row, a zap's) is kept; otherwise one over
     * [lineup] (the shared Live now list, as the panel builds it) starts on [item], with [item] put first
     * when the list lacks it (not loaded yet, a game about to start). So the panel and CH± always work.
     * #211: the kept session takes this Watch's origin ([returnToHomeOnBack]): a session left over from Home's
     * row must not make a game started in the section behave as Home's (no mini player on Back, BUG13).
     */
    fun ensureFor(item: SportsLiveItem, lineup: List<SportsLiveItem>, returnToHomeOnBack: Boolean = false): SportsLiveZapSession {
        activeFor(item.id)?.let { kept ->
            if (kept.returnToHomeOnBack == returnToHomeOnBack) return kept
            return kept.copy(returnToHomeOnBack = returnToHomeOnBack).also(::update)
        }
        val items = if (lineup.any { it.streamKey == item.streamKey }) lineup else listOf(item) + lineup
        val session = checkNotNull(
            SportsLiveZapSession.of(items, item.streamKey, SportsLiveTab.LIVE_NOW, filter = null, returnToHomeOnBack = returnToHomeOnBack)
        )
        start(session)
        return session
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
