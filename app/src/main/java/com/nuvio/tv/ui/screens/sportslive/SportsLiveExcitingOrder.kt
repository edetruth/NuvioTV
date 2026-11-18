package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.scores.ExcitementHistory
import com.nuvio.tv.core.sportslive.scores.ExcitementRating
import com.nuvio.tv.core.sportslive.scores.LiveExcitement
import com.nuvio.tv.core.sportslive.scores.ScoreChips
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.domain.model.SportsLiveItem

/**
 * [item]'s excitement (#43) from its delayed [snapshot]: only for a strict match with the card's sides on
 * it one way round (what a score chip needs), so a tag or rank never comes from an unmatched or wrong game.
 */
internal fun excitementOf(item: SportsLiveItem, snapshot: ScoreSnapshot?, history: ExcitementHistory, nowMs: Long): ExcitementRating? {
    val event = ScoreChips.matchedEvent(item, snapshot) ?: return null
    return LiveExcitement.rate(event, history.recent(event.eventId, nowMs))
}

/**
 * Live now's live games in Most exciting order (#43): followed teams stay pinned first, then rated
 * games, most exciting first, then the unrated ones (unmatched, no score, another sport). Ties and the
 * unrated keep their order in [items] (start time).
 */
internal fun excitingOrder(items: List<SportsLiveItem>, ratingOf: (SportsLiveItem) -> ExcitementRating?): List<SportsLiveItem> {
    val ratings = items.map(ratingOf)
    return items.indices.sortedWith(
        compareByDescending<Int> { items[it].followed }
            .thenBy { if (ratings[it] == null) 1 else 0 }
            .thenByDescending { ratings[it]?.score ?: 0.0 }
            .thenBy { it }
    ).map { items[it] }
}

/**
 * [fresh] in the order of [previous] (stream keys as last shown), so a card never moves under the D-pad while
 * the list is focused (the in-player panel's I3 rule); cards new to the list follow, in their fresh order.
 */
internal fun stabilizeOrder(fresh: List<SportsLiveItem>, previous: List<String>?): List<SportsLiveItem> {
    if (previous == null) return fresh
    val rank = HashMap<String, Int>(previous.size * 2)
    previous.forEachIndexed { index, key -> rank.putIfAbsent(key, index) }
    return fresh.sortedBy { rank[it.streamKey] ?: Int.MAX_VALUE }
}
