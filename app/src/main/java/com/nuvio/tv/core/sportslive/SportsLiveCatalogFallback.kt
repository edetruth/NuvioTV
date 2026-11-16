package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind

/**
 * Classifies the items of a catalog whose items carry no signals of their own (an addon the
 * item rules do not know) from the catalog name alone. Replay words are checked first, then
 * schedule words, then live words ("Live Schedule" is a schedule); any other name changes nothing.
 * Live items stay subject to the ended-games probe like any other live item.
 */
object SportsLiveCatalogFallback {
    fun apply(items: List<SportsLiveItem>, catalogName: String): List<SportsLiveItem> = when {
        SportsLiveKeywords.containsKeyword(catalogName, SportsLiveKeywords.FALLBACK_REPLAY) ->
            items.map { it.copy(kind = SportsLiveKind.REPLAY, isLive = false) }
        // Not live and without a start: an events item shows on Upcoming as "Time TBA".
        SportsLiveKeywords.containsKeyword(catalogName, SportsLiveKeywords.FALLBACK_SCHEDULE) ->
            items.map { it.copy(isLive = false) }
        SportsLiveKeywords.containsKeyword(catalogName, SportsLiveKeywords.FALLBACK_LIVE) ->
            items.map { it.copy(isLive = true) }
        else -> items
    }
}
