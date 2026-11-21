package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.identify.TeamIdentityBook
import com.nuvio.tv.core.sportslive.scores.TeamDirectory
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.domain.repository.SportsLiveLoad
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * One sorting of a load into tabs, shared by everything that lists it (live.20): the section's tabs, Home's live row
 * and the player's live games panel used to bucket the same load each on its own, three merges of the same games on
 * a slow box. Each tab is bucketed once, when first asked, for the newest load; a later ask for the same load, catalogs,
 * addon order and team directory reuses it, until a game changes tab by the clock
 * ([SportsLiveItemBucketer.clockChangedBetween]) or [maxAgeMs] pass. The result is what
 * [SportsLiveItemBucketer.bucketTab] gives at the time it was built.
 */
class SportsLiveSharedBuckets(
    private val maxAgeMs: Long = MAX_AGE_MS,
    // Where unplaced rows are recorded (live.20); null: nowhere (tests).
    private val unidentified: SportsLiveUnidentified? = null,
    // The score matches and women's-game marks to forget once their sources leave the lists (live.23 review B3/B4).
    private val matchedEvents: SportsLiveMatchedEvents? = null,
    private val womenGames: SportsLiveWomenGames? = null
) {

    private class Entry(
        val load: SportsLiveLoad,
        val claimedKeys: Set<String>,
        val addonOrder: List<String>,
        val stamp: Long,
        val builtAtMs: Long,
        /** The load's items of claimed catalogs, as loaded (the clock check reads their starts). */
        val raw: List<SportsLiveItem>,
        /** [raw] reclocked at [builtAtMs]. */
        val clocked: List<SportsLiveItem>,
        val tabs: HashMap<SportsLiveTab, List<SportsLiveItem>> = HashMap()
    )

    private var entry: Entry? = null

    /** [load]'s [tab] for the catalogs [claimedKeys] in [addonOrder], at [nowMs]. */
    @Synchronized
    fun tab(
        load: SportsLiveLoad,
        claimedKeys: Set<String>,
        addonOrder: List<String>,
        tab: SportsLiveTab,
        nowMs: Long,
        stamp: Long = currentStamp()
    ): List<SportsLiveItem> {
        val entry = entryFor(load, claimedKeys, addonOrder, stamp, nowMs)
        return entry.tabs.getOrPut(tab) {
            SportsLiveItemBucketer.bucketClocked(entry.clocked, tab, entry.builtAtMs, entry.addonOrder).also { games ->
                // live.20: the rows no rule could place, kept for the continuous cleanup ([SportsLiveUnidentified]).
                if (tab == SportsLiveTab.LIVE_NOW || tab == SportsLiveTab.UPCOMING) unidentified?.record(games, System.identityHashCode(load), nowMs)
            }
        }
    }

    /** Every tab of [load] ([SportsLiveItemBucketer.bucket]), each built once. */
    @Synchronized
    fun all(
        load: SportsLiveLoad,
        claimedKeys: Set<String>,
        addonOrder: List<String>,
        nowMs: Long,
        stamp: Long = currentStamp()
    ): Map<SportsLiveTab, List<SportsLiveItem>> =
        SportsLiveTab.entries.associateWith { tab(load, claimedKeys, addonOrder, it, nowMs, stamp) }

    private fun entryFor(load: SportsLiveLoad, claimedKeys: Set<String>, addonOrder: List<String>, stamp: Long, nowMs: Long): Entry {
        entry?.let { old ->
            val age = nowMs - old.builtAtMs
            if (old.load === load && old.stamp == stamp && old.claimedKeys == claimedKeys && old.addonOrder == addonOrder &&
                age in 0 until maxAgeMs && !SportsLiveItemBucketer.clockChangedBetween(old.raw, old.builtAtMs, nowMs)
            ) {
                return old
            }
        }
        val raw = load.items.filter { it.catalogKey in claimedKeys }
        // A new load: the matches and marks of sources it no longer lists go (live.23 review B3/B4). Not on the early list
        // of a first load (L3: the slow addons' games are not in it yet), and an addon with a catalog that failed this time
        // keeps its sources' records (its games are not gone, only unread).
        if (entry?.load !== load && !load.partial && (matchedEvents != null || womenGames != null)) {
            val listed = load.items.mapTo(HashSet()) { it.streamKey }
            // A source's key is "addon id|item id": the addons with a catalog that failed keep their sources' records.
            val failedAddons = load.failures.mapTo(HashSet()) { it.catalog.addon.id + "|" }
            val unread: (String) -> Boolean = { key -> failedAddons.any(key::startsWith) }
            matchedEvents?.retain(listed, unread)
            womenGames?.retain(listed, unread)
        }
        return Entry(load, claimedKeys, addonOrder, stamp, nowMs, raw, SportsLiveItemBucketer.reclock(raw, nowMs)).also { entry = it }
    }

    companion object {
        /** A sorting is used at most this long (starts that are not by the clock alone, merge windows). */
        const val MAX_AGE_MS = 5 * 60_000L

        /** The one the app shares. */
        val shared = SportsLiveSharedBuckets(
            unidentified = SportsLiveUnidentified.shared,
            matchedEvents = SportsLiveMatchedEvents.shared,
            womenGames = SportsLiveWomenGames.shared
        )

        /**
         * The team directory's, identities' and ESPN starts' ([SportsLiveEspnStarts]) revisions as one number: copies
         * merge differently once they move, and a game ESPN schedules later leaves Live now.
         */
        fun currentStamp(): Long =
            ((TeamDirectory.shared.revision.value * 31 + TeamIdentityBook.shared.revision.value) * 31 +
                SportsLiveEspnStarts.shared.revision.value) * 31 +
                SportsLiveMatchedEvents.shared.revision.value * 17 + SportsLiveWomenGames.shared.revision.value

        /** Every change of [currentStamp] after the current one: what lists are built again on. */
        fun stampChanges(): Flow<Long> = merge(
            TeamDirectory.shared.revision.drop(1).map { it.toLong() },
            TeamIdentityBook.shared.revision.drop(1).map { it.toLong() },
            SportsLiveEspnStarts.shared.revision.drop(1).map { it.toLong() },
            matchChanges()
        )

        /** How long score matches are gathered before one rebuild (a poll matches many cards at once). */
        const val MATCH_DEBOUNCE_MS = 1_000L

        /**
         * One signal per burst of score matches that merge copies or mark women's games (live.23, TV report #10), after
         * [MATCH_DEBOUNCE_MS] of quiet: the lists are built again once, not once per card.
         */
        @OptIn(FlowPreview::class)
        fun matchChanges(
            events: SportsLiveMatchedEvents = SportsLiveMatchedEvents.shared,
            women: SportsLiveWomenGames = SportsLiveWomenGames.shared
        ): Flow<Long> = merge(events.revision.drop(1), women.revision.drop(1)).debounce(MATCH_DEBOUNCE_MS)
    }
}
