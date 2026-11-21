package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.SportsLiveItem
import java.util.IdentityHashMap

/**
 * One merge of a tab's copies ([SportsLiveItemBucketer.merge]) kept for the next load or clock (live.24): the items it
 * read by stream key, each candidate's team-pair keys and id view, and each group of possible copies with its rows, so
 * the next merge ([SportsLiveItemBucketer.mergeIncremental]) merges again only the groups whose items changed.
 */
class SportsLiveMergeState internal constructor(
    internal val addonOrder: List<String>,
    /** The team directory stamp ([SportsLiveSharedBuckets.currentStamp]) the keys and views were made under. */
    internal val stamp: Long,
    /** The clock of the oldest group result kept in it. */
    internal val sinceMs: Long,
    internal val byKey: Map<String, SportsLiveItem>,
    internal val keys: Map<String, List<Pair<String, String>>>,
    internal val views: IdentityHashMap<SportsLiveItem, SportsLiveItem>,
    /** Each grouped item's group, by stream key; an item in no group merges with nothing. */
    internal val groupOf: Map<String, Group>,
    /** The rows, in input order, as [SportsLiveItemBucketer.merge] gives them. */
    val output: List<SportsLiveItem>,
    /** Debug counters (NuvioTiming): the items read, those new, changed or gone since the last merge, and those merged again. */
    val inputCount: Int,
    val changedCount: Int,
    val remergedCount: Int,
    /** True when nothing of an earlier merge was used. */
    val full: Boolean
) {
    /** A group of possible copies: its members' stream keys, and each member's row (null: folded into another). */
    internal class Group(val members: Set<String>, val rows: Map<String, SportsLiveItem?>)
}

/** One tab's rows ([SportsLiveItemBucketer.bucketClocked]) and the merge they came from (null: Replays, never merged). */
class SportsLiveTabBucket internal constructor(val rows: List<SportsLiveItem>, internal val merge: SportsLiveMergeState?)
