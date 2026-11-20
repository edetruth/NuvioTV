package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.identify.TeamIdentityBook
import com.nuvio.tv.core.sportslive.scores.TeamDirectory
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.domain.repository.SportsLiveLoad
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
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
    private val unidentified: SportsLiveUnidentified? = null
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
        return Entry(load, claimedKeys, addonOrder, stamp, nowMs, raw, SportsLiveItemBucketer.reclock(raw, nowMs)).also { entry = it }
    }

    companion object {
        /** A sorting is used at most this long (starts that are not by the clock alone, merge windows). */
        const val MAX_AGE_MS = 5 * 60_000L

        /** The one the app shares. */
        val shared = SportsLiveSharedBuckets(unidentified = SportsLiveUnidentified.shared)

        /**
         * The team directory's, identities' and ESPN starts' ([SportsLiveEspnStarts]) revisions as one number: copies
         * merge differently once they move, and a game ESPN schedules later leaves Live now.
         */
        fun currentStamp(): Long =
            (TeamDirectory.shared.revision.value * 31 + TeamIdentityBook.shared.revision.value) * 31 +
                SportsLiveEspnStarts.shared.revision.value

        /** Every change of [currentStamp] after the current one: what lists are built again on. */
        fun stampChanges(): Flow<Long> = merge(
            TeamDirectory.shared.revision.drop(1),
            TeamIdentityBook.shared.revision.drop(1),
            SportsLiveEspnStarts.shared.revision.drop(1)
        )
    }
}
