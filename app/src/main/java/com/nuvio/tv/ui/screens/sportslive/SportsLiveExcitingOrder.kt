package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.scores.ExcitementHistory
import com.nuvio.tv.core.sportslive.scores.ExcitementRating
import com.nuvio.tv.core.sportslive.scores.LiveExcitement
import com.nuvio.tv.core.sportslive.scores.RecentChange
import com.nuvio.tv.core.sportslive.scores.ScoreChips
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.domain.model.SportsLiveItem

/**
 * [item]'s excitement (#43: the badge under a live row's period) from its delayed [snapshot]: only for a strict match with the card's sides on
 * it one way round (what a score chip needs), so a tag or rank never comes from an unmatched or wrong game.
 * Without [recentChanges] (Hide until focused) a goal or lead change adds nothing, so a card never jumps
 * just after a score.
 */
internal fun excitementOf(
    item: SportsLiveItem,
    snapshot: ScoreSnapshot?,
    history: ExcitementHistory,
    nowMs: Long,
    recentChanges: Boolean = true
): ExcitementRating? {
    val event = ScoreChips.matchedEvent(item, snapshot) ?: return null
    val recent = if (recentChanges) history.recent(event.eventId, nowMs) else RecentChange.NONE
    return LiveExcitement.rate(event, recent)
}
