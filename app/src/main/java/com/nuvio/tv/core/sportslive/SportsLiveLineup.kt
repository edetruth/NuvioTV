package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.scores.MySports
import com.nuvio.tv.core.sportslive.scores.ScoreChips
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.core.sportslive.scores.TeamDirectory
import com.nuvio.tv.domain.model.SportsLiveItem

/**
 * The one order of live games (live.25, #225, Fable sorting audit): Live Now, the player's live games panel and its table
 * guide, CH± / ▲▼ zaps and the dual screen picker all take it from [order], with the same inputs, so the order you see is
 * the order you flip through. Each surface then only splits it by status (Live, Starting soon, Just ended) and keeps rows
 * in place while you look (Live Now's calm rule, the open panel's freeze, the frozen zap order), never re-ranks it.
 *
 * The keys are [SportsLiveImportance]'s: followed teams first, then league priority, then matched to a score feed
 * ([matchedKeys]) inside the tier, then the earliest start, the name and the card's key. None reads a score, a clock or a
 * game state, so the order is the same under Scores Show and Hide; under Off nothing is matched.
 */
object SportsLiveLineup {

    /** [live] in the shared order for [mySports]; [scores] by card key, [mode] the Scores setting (null: not read yet). */
    fun order(
        live: List<SportsLiveItem>,
        mySports: MySports,
        scores: Map<String, ScoreSnapshot>,
        mode: ScoresMode?,
        directory: TeamDirectory = TeamDirectory.shared
    ): List<SportsLiveItem> =
        SportsLiveImportance.sort(live, mySports, directory = directory, scored = matchedKeys(live, scores, mode))

    /**
     * Card keys of [items] strictly matched to a score feed's game ([ScoreChips.matchedEvent]): whether a feed has the game,
     * never what it says (no score, clock or state), so Show and Hide give the same set. None while Scores is Off or unread.
     */
    fun matchedKeys(items: List<SportsLiveItem>, scores: Map<String, ScoreSnapshot>, mode: ScoresMode?): Set<String> {
        if (mode == null || mode == ScoresMode.OFF || scores.isEmpty()) return emptySet()
        return items.filter { ScoreChips.matchedEvent(it, scores[it.streamKey]) != null }.mapTo(HashSet()) { it.streamKey }
    }
}
