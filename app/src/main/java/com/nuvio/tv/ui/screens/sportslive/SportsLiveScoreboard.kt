package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.EndedGame
import com.nuvio.tv.core.sportslive.EndedSignal
import com.nuvio.tv.core.sportslive.scores.GameArt
import com.nuvio.tv.core.sportslive.scores.ScoreChip
import com.nuvio.tv.core.sportslive.scores.ScoreChips
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.ui.screens.sportslive.dual.offersDual

/** The Scoreboard's groups, in the order they are listed. */
enum class ScoreboardGroup { LIVE, STARTING_SOON, LATER, FINAL }

/**
 * A Scoreboard row: its [group], the score [chip] (spoiler setting applied; null when unmatched,
 * upcoming, Scores Off or an estimated end), the drawn [art] (null for a channel) and which sides a
 * followed team plays, in the card's team order. [probablyEnded]: an estimated end (#60), shown as
 * "Probably ended" with no score.
 */
data class ScoreboardRow(
    val item: SportsLiveItem,
    val group: ScoreboardGroup,
    val chip: ScoreChip?,
    val art: GameArt?,
    val firstFollowed: Boolean,
    val secondFollowed: Boolean,
    val probablyEnded: Boolean
)

/**
 * The Scoreboard's rows for [items] (one tab, as filtered): the in-player panel's grouping
 * ([groupPanelRows]: live furthest-in first, starting soon within 3 h soonest first, followed teams
 * first in each group, finished games kept in place by start under Hide until focused), with its
 * Just ended group as **FINAL** ([ended], #60). On Upcoming a game is LIVE only when ESPN says it is in
 * play; otherwise it belongs with the games starting soon. [followsSide] tells whether a followed team
 * is a card's side (by the side's card name).
 */
internal fun scoreboardRows(
    items: List<SportsLiveItem>,
    tab: SportsLiveTab,
    scores: Map<String, ScoreSnapshot>,
    mode: ScoresMode?,
    revealed: Set<String>,
    nowMs: Long,
    ended: Map<String, EndedGame> = emptyMap(),
    followsSide: (SportsLiveItem, String) -> Boolean = { _, _ -> false }
): List<ScoreboardRow> {
    val scoresOn = mode != null && mode != ScoresMode.OFF
    return groupPanelRows(items, scores, mode, revealed, nowMs, ended).map { row ->
        val item = row.item
        val endedGame = ended[item.streamKey]
        val snapshot = (scores[item.streamKey] ?: endedGame?.event).takeIf { scoresOn }
        val group = when (row.group) {
            ZapPanelGroup.LIVE_NOW -> if (tab == SportsLiveTab.UPCOMING &&
                ScoreChips.matchedEvent(item, snapshot)?.state != ScoreState.IN
            ) {
                ScoreboardGroup.STARTING_SOON
            } else {
                ScoreboardGroup.LIVE
            }
            ZapPanelGroup.STARTING_SOON -> ScoreboardGroup.STARTING_SOON
            ZapPanelGroup.OTHER -> ScoreboardGroup.LATER
            ZapPanelGroup.JUST_ENDED -> ScoreboardGroup.FINAL
        }
        val estimate = endedGame?.signal == EndedSignal.ESTIMATE
        val sides = ScoreChips.orderedSides(item)
        ScoreboardRow(
            item = item,
            group = group,
            chip = if (estimate) null else row.chip,
            art = GameArt.of(item, snapshot),
            firstFollowed = sides != null && followsSide(item, sides.first),
            secondFollowed = sides != null && followsSide(item, sides.second),
            probablyEnded = estimate
        )
    }.sortedBy { it.group.ordinal }
}

/** Which buttons the Scoreboard's focused game offers beside Watch (always shown). */
data class ScoreboardButtons(val watchWith: Boolean, val gameStats: Boolean)

/**
 * "Watch with another game" (the dual screen's pick mode): on a tab that offers dual screen, for a game
 * not over. "Game stats": only for a strict ESPN match ([matchedEvent] non-null).
 */
internal fun scoreboardButtons(tab: SportsLiveTab, row: ScoreboardRow, matchedEvent: ScoreSnapshot?): ScoreboardButtons =
    ScoreboardButtons(
        watchWith = tab.offersDual() && row.group != ScoreboardGroup.FINAL,
        gameStats = matchedEvent != null
    )

/**
 * Time to kickoff for a Starting soon row as (hours, minutes), rounded up to the minute; null once
 * [startMs] has passed or when it is unknown.
 */
internal fun scoreboardStartsIn(startMs: Long?, nowMs: Long): Pair<Int, Int>? {
    val left = (startMs ?: return null) - nowMs
    if (left <= 0) return null
    val minutes = ((left + 59_999) / 60_000).toInt()
    return minutes / 60 to minutes % 60
}
