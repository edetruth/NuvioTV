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
        /**
         * Each tab's bucket of an earlier sorting of the same catalogs in the same order (live.24): a new load or clock
         * merges again only what changed ([SportsLiveItemBucketer.bucketClockedIncremental]).
         */
        val previous: Map<SportsLiveTab, SportsLiveTabBucket> = emptyMap(),
        /** Upcoming's chunk merges of that earlier sorting, by chunk slot ([upcoming], live.24). */
        val previousUpcoming: Map<String, SportsLiveMergeState> = emptyMap(),
        val tabs: HashMap<SportsLiveTab, List<SportsLiveItem>> = HashMap(),
        val buckets: HashMap<SportsLiveTab, SportsLiveTabBucket> = HashMap()
    ) {
        /** Upcoming's listed games, each stream key once, when first asked ([upcoming]). */
        var upcomingListed: List<SportsLiveItem>? = null
        val upcomingScopes = HashMap<SportsLiveUpcomingWindow, SportsLiveUpcomingChunks.Scope>()
        val upcomingChunks = HashMap<Pair<SportsLiveUpcomingWindow, SportsLiveUpcomingChunks.Key>, List<SportsLiveItem>>()
        val upcomingMerges = HashMap<String, SportsLiveMergeState>()
        val upcomingResults = HashMap<SportsLiveUpcomingWindow, SportsLiveUpcomingBucket>()
    }

    /** Upcoming games merged by [upcoming] so far (live.24: tests read it; the debug line counts it per call). */
    @Volatile
    internal var upcomingSorted = 0
        private set

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
            val startedAt = System.nanoTime()
            val previous = entry.previous[tab]
            val bucket = SportsLiveItemBucketer.bucketClockedIncremental(previous, entry.clocked, tab, entry.builtAtMs, entry.addonOrder, entry.stamp)
            entry.buckets[tab] = bucket
            SportsLiveDebugTiming.log { "sportsOpen stage=bucketTab tab=${tab.name} ms=${(System.nanoTime() - startedAt) / 1_000_000} ${countsOf(bucket, previous)}" }
            bucket.rows.also { games ->
                // live.20: the rows no rule could place, kept for the continuous cleanup ([SportsLiveUnidentified]).
                if (tab == SportsLiveTab.LIVE_NOW || tab == SportsLiveTab.UPCOMING) unidentified?.record(games, System.identityHashCode(load), nowMs)
            }
        }
    }

    // live.24 debug counters: the items a tab read, how many were new or changed since the last sorting, and how many
    // were merged again (the rest kept their groups' rows).
    private fun countsOf(bucket: SportsLiveTabBucket, previous: SportsLiveTabBucket?): String {
        val merge = bucket.merge ?: return "rows=${bucket.rows.size} merged=0"
        if (bucket === previous) return "rows=${bucket.rows.size} items=${merge.inputCount} reused=all"
        return "rows=${bucket.rows.size} items=${merge.inputCount} changed=${merge.changedCount} merged=${merge.remergedCount} full=${merge.full}"
    }

    /**
     * [load]'s Upcoming for [window] (live.24): only the near days', followed teams' and untimed games are merged, each
     * later day when a window asks for it, each chunk of days once per sorting (the next load merges again only what
     * changed in it). With every day asked and no My sports wait, the rows are [tab]'s for Upcoming. A copy is listed once:
     * across chunks, a source already in a row (a timed one first) is never listed again.
     */
    @Synchronized
    fun upcoming(
        load: SportsLiveLoad,
        claimedKeys: Set<String>,
        addonOrder: List<String>,
        nowMs: Long,
        window: SportsLiveUpcomingWindow,
        stamp: Long = currentStamp()
    ): SportsLiveUpcomingBucket {
        val entry = entryFor(load, claimedKeys, addonOrder, stamp, nowMs)
        entry.upcomingResults[window]?.let { return it }
        val startedAt = System.nanoTime()
        val zone = window.zone
        val listed = entry.upcomingListed
            ?: SportsLiveItemBucketer.listedIn(entry.clocked, SportsLiveTab.UPCOMING, entry.builtAtMs).also { entry.upcomingListed = it }
        val scopeKey = window.copy(days = SportsLiveUpcomingWindow.NEAR_DAYS)
        val scope = entry.upcomingScopes.getOrPut(scopeKey) { SportsLiveUpcomingChunks.scope(listed, window) }
        val today = SportsLiveUpcomingChunks.dateOf(entry.builtAtMs, zone)
        val nearEnd = today.plusDays(SportsLiveUpcomingWindow.NEAR_DAYS.toLong())
        val end = today.plusDays(maxOf(window.days, SportsLiveUpcomingWindow.NEAR_DAYS).toLong())
        val near = SportsLiveUpcomingChunks.Key(today, nearEnd)
        val keys = ArrayList<SportsLiveUpcomingChunks.Key>()
        keys += near
        var day = nearEnd
        while (day < end) {
            keys += SportsLiveUpcomingChunks.Key(day, day.plusDays(1))
            day = day.plusDays(1)
        }
        keys += SportsLiveUpcomingChunks.Key(end, null, followedOnly = true)
        var sorted = 0
        val chunks = keys.map { key ->
            entry.upcomingChunks.getOrPut(scopeKey to key) {
                val population = SportsLiveUpcomingChunks.population(scope, key, near, zone)
                sorted += population.size
                val previous = entry.upcomingMerges[key.slot] ?: entry.previousUpcoming[key.slot]
                val merged = SportsLiveItemBucketer.mergeIncremental(previous, population, entry.addonOrder, entry.builtAtMs, entry.stamp)
                entry.upcomingMerges[key.slot] = merged
                merged.output.filter { SportsLiveUpcomingChunks.keeps(key, near, it, zone) }
            }
        }
        upcomingSorted += sorted
        // Each source once: timed rows first (an untimed copy that folded into one goes with it), then Any time rows.
        val used = HashSet<String>()
        val rows = ArrayList<SportsLiveItem>()
        fun take(row: SportsLiveItem) {
            if (row.streamKey in used || row.alternates.any { it.streamKey in used }) return
            rows += row
            used += row.streamKey
            row.alternates.forEach { used += it.streamKey }
        }
        chunks.forEach { chunk -> chunk.forEach { if (SportsLiveUpcomingChunks.timed(it)) take(it) } }
        chunks.forEach { chunk -> chunk.forEach { if (!SportsLiveUpcomingChunks.timed(it)) take(it) } }
        // In the listed order of their primaries, as one merge of every game gives them, then the tab's order.
        val position = HashMap<String, Int>(listed.size * 2)
        listed.forEachIndexed { index, item -> position[item.streamKey] = index }
        val ordered = SportsLiveItemBucketer.orderedForTab(rows.sortedBy { position[it.streamKey] ?: Int.MAX_VALUE }, entry.addonOrder)
        val endMs = SportsLiveUpcomingChunks.startOf(end, zone)
        val pending = scope.inScope.asSequence()
            .filter { SportsLiveUpcomingChunks.timed(it) && it.startMs!! >= endMs }
            .map { SportsLiveUpcomingChunks.dateOf(it.startMs!!, zone) }
            .distinct().sorted().toList()
        val waiting = scope.waiting.count { !SportsLiveUpcomingChunks.timed(it) || it.startMs!! < endMs }
        unidentified?.record(ordered, System.identityHashCode(load), nowMs)
        SportsLiveDebugTiming.log {
            "sportsOpen stage=upcoming ms=${(System.nanoTime() - startedAt) / 1_000_000} days=${maxOf(window.days, SportsLiveUpcomingWindow.NEAR_DAYS)} " +
                "listed=${listed.size} inScope=${scope.inScope.size} sorted=$sorted rows=${ordered.size} pendingDays=${pending.size} waiting=$waiting"
        }
        return SportsLiveUpcomingBucket(ordered, pending, waiting).also { entry.upcomingResults[window] = it }
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
            // live.25 (#221): an ask whose clock is a moment behind this sorting's uses it too while no game changes tab in
            // between. Home's row reads its clock before waiting for this lock (seconds, while the section sorts Upcoming):
            // its ask replaced the sorting, Upcoming's included, and the section's next ask sorted Upcoming again.
            val clockOk = if (age >= 0) {
                age < maxAgeMs && !SportsLiveItemBucketer.clockChangedBetween(old.raw, old.builtAtMs, nowMs)
            } else {
                -age < maxAgeMs && !SportsLiveItemBucketer.clockChangedBetween(old.raw, nowMs, old.builtAtMs)
            }
            if (old.load === load && old.stamp == stamp && old.claimedKeys == claimedKeys && old.addonOrder == addonOrder && clockOk) {
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
        // The same catalogs in the same order (a refresh, a tip-off, the directory moving): the last buckets are the base.
        val base = entry?.takeIf { it.claimedKeys == claimedKeys && it.addonOrder == addonOrder }
        val previous = base?.let { old -> old.previous + old.buckets }.orEmpty()
        val previousUpcoming = base?.let { old -> old.previousUpcoming + old.upcomingMerges }.orEmpty()
        return Entry(load, claimedKeys, addonOrder, stamp, nowMs, raw, SportsLiveItemBucketer.reclock(raw, nowMs), previous, previousUpcoming)
            .also { entry = it }
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
