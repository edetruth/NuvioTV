package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.runtime.Immutable
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
enum class ScoreboardGroup { LIVE, STARTING_SOON, MORE_GAMES, FINAL }

/**
 * A Scoreboard row: its [group], the score [chip] (spoiler setting applied; null when unmatched,
 * upcoming, Scores Off or an estimated end), the drawn [art] (null for a channel) and which sides a
 * followed team plays, in the card's team order. [probablyEnded]: an estimated end (#60), shown as
 * "Probably ended" with no score. [startsIn]: (hours, minutes) to a timed kickoff of a Starting soon
 * row, at the clock the rows were built with; null otherwise (a date with no time never counts down).
 */
@Immutable
data class ScoreboardRow(
    val item: SportsLiveItem,
    val group: ScoreboardGroup,
    val chip: ScoreChip?,
    val art: GameArt?,
    val firstFollowed: Boolean,
    val secondFollowed: Boolean,
    val probablyEnded: Boolean,
    val startsIn: Pair<Int, Int>? = null
)

/**
 * The Scoreboard's rows for [items] (one tab, as filtered): the in-player panel's grouping
 * ([groupPanelRows]: live furthest-in first, starting soon within 3 h soonest first, followed teams
 * first in each group, finished games kept in place by start under Hide until focused), with its
 * Just ended group as **FINAL** ([ended], #60). On Upcoming a game is LIVE only when ESPN says it is in
 * play; otherwise it belongs with the games starting soon. [followsSide] tells whether a followed team
 * is a card's side (by the side's card name). [liveRank] (Most exciting, #43) orders the LIVE group by
 * each game's place in Live now's order; games not in it follow in their usual order.
 */
internal fun scoreboardRows(
    items: List<SportsLiveItem>,
    tab: SportsLiveTab,
    scores: Map<String, ScoreSnapshot>,
    mode: ScoresMode?,
    revealed: Set<String>,
    nowMs: Long,
    ended: Map<String, EndedGame> = emptyMap(),
    followsSide: (SportsLiveItem, String) -> Boolean = { _, _ -> false },
    liveRank: Map<String, Int> = emptyMap()
): List<ScoreboardRow> {
    val scoresOn = mode != null && mode != ScoresMode.OFF
    return groupPanelRows(items, scores, mode, revealed, nowMs, ended).map { row ->
        val item = row.item
        val endedGame = ended[item.streamKey]
        val snapshot = (scores[item.streamKey] ?: endedGame?.event).takeIf { scoresOn }
        val art = GameArt.of(item, snapshot)
        // A kickoff with a time of day: a date alone ("Sat 21 Nov") never starts soon or counts down.
        val timedStart = art?.takeIf { it.hasTimeOfDay }?.startMs ?: item.startMs?.takeIf { item.startHasTimeOfDay }
        val group = when (row.group) {
            ZapPanelGroup.LIVE_NOW -> when {
                tab != SportsLiveTab.UPCOMING || ScoreChips.matchedEvent(item, snapshot)?.state == ScoreState.IN -> ScoreboardGroup.LIVE
                timedStart != null -> ScoreboardGroup.STARTING_SOON
                else -> ScoreboardGroup.MORE_GAMES
            }
            ZapPanelGroup.STARTING_SOON -> ScoreboardGroup.STARTING_SOON
            ZapPanelGroup.OTHER -> ScoreboardGroup.MORE_GAMES
            ZapPanelGroup.JUST_ENDED -> ScoreboardGroup.FINAL
        }
        val estimate = endedGame?.signal == EndedSignal.ESTIMATE
        val sides = ScoreChips.orderedSides(item)
        ScoreboardRow(
            item = item,
            group = group,
            chip = if (estimate) null else row.chip,
            art = art,
            firstFollowed = sides != null && followsSide(item, sides.first),
            secondFollowed = sides != null && followsSide(item, sides.second),
            probablyEnded = estimate,
            startsIn = if (group == ScoreboardGroup.STARTING_SOON) scoreboardStartsIn(timedStart, nowMs) else null
        )
    }.sortedWith(
        compareBy<ScoreboardRow> { it.group.ordinal }
            .thenBy { if (it.group == ScoreboardGroup.LIVE) liveRank[it.item.streamKey] ?: Int.MAX_VALUE else 0 }
    )
}

/** Which buttons the Scoreboard's focused game offers beside Watch (always shown). */
@Immutable
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
