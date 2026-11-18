package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.runtime.Immutable
import com.nuvio.tv.core.sportslive.EndedGame
import com.nuvio.tv.core.sportslive.SportsLiveLayout
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.GameArt
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveTab
import java.time.ZoneId

/** The scores, setting and reveals the screen has: what the Scoreboard, Timeline and drawn cards read. */
data class SportsLiveBoardScores(
    val scores: Map<String, ScoreSnapshot> = emptyMap(),
    val mode: ScoresMode? = null,
    val revealed: Set<String> = emptySet()
)

/**
 * Everything one build of a [SportsLiveBoard] reads; equal inputs give an equal board, so a focus move
 * (which changes the selection, not these) builds nothing.
 */
data class SportsLiveBoardInputs(
    val tab: SportsLiveTab,
    val filter: String?,
    val layout: SportsLiveLayout,
    val items: List<SportsLiveItem>,
    val scores: SportsLiveBoardScores,
    /** Live now's Just ended verdicts (#60); empty on other tabs. */
    val ended: Map<String, EndedGame>,
    /** Most exciting's order (#43); empty on other tabs. */
    val liveRank: Map<String, Int>,
    val followed: List<FollowedTeam>,
    /** The view model's clock, to the minute. */
    val clockMs: Long,
    /** Learned logos' revision (#66): the drawn cards are built again when it moves. */
    val logoRevision: Int
)

/**
 * What the list draws for one tab, filter and layout (#71): the items in the order shown, the
 * Scoreboard's rows (Scoreboard and Timeline), the Timeline's model and each card's drawn art. Built in
 * the view model on its compute dispatcher, never in composition, so a large list (60 games on Upcoming)
 * opens and switches layout without holding the main thread.
 */
@Immutable
data class SportsLiveBoard(
    val tab: SportsLiveTab,
    val filter: String?,
    val layout: SportsLiveLayout,
    val items: List<SportsLiveItem>,
    val rows: List<ScoreboardRow>?,
    val timeline: TimelineModel?,
    /** Each item's drawn card by `addonId|id` (null: the addon's art). */
    val arts: Map<String, GameArt?>
) {
    /** True when this board shows [tab] with [filter] (in whatever layout it was built for). */
    fun shows(tab: SportsLiveTab, filter: String?): Boolean = this.tab == tab && this.filter == filter

    val rowOf: Map<String, ScoreboardRow> by lazy { rows?.associateBy { it.item.streamKey }.orEmpty() }

    companion object {
        /** Builds the board for [inputs]: pure, and slow for a large list, so callers run it off the main thread. */
        fun build(inputs: SportsLiveBoardInputs, zone: ZoneId = ZoneId.systemDefault()): SportsLiveBoard {
            val tab = inputs.tab
            val offered = inputs.layout in SportsLiveLayout.offeredFor(tab)
            val board = offered && (inputs.layout == SportsLiveLayout.SCOREBOARD || inputs.layout == SportsLiveLayout.TIMELINE)
            val scores = inputs.scores
            val rows = if (board) {
                scoreboardRows(
                    items = inputs.items,
                    tab = tab,
                    scores = scores.scores,
                    mode = scores.mode,
                    revealed = scores.revealed,
                    nowMs = inputs.clockMs,
                    ended = inputs.ended,
                    followsSide = { item, side -> inputs.followed.any { SportsLiveTeams.followsSideOfAnySource(it, side, item) } },
                    liveRank = inputs.liveRank
                )
            } else {
                null
            }
            val timeline = if (rows != null && inputs.layout == SportsLiveLayout.TIMELINE) {
                SportsLiveTimeline.model(rows = rows, tab = tab, nowMs = inputs.clockMs, zone = zone, ended = inputs.ended)
            } else {
                null
            }
            val items = timeline?.items ?: rows?.map { it.item } ?: inputs.items
            // The Cards' drawn art; a row already carries its own.
            val arts = HashMap<String, GameArt?>(items.size * 2)
            rows?.forEach { arts[it.item.streamKey] = it.art }
            for (item in items) {
                if (item.streamKey !in arts) arts[item.streamKey] = GameArt.of(item, scores.scores[item.streamKey])
            }
            return SportsLiveBoard(
                tab = tab,
                filter = inputs.filter,
                layout = if (board) inputs.layout else SportsLiveLayout.CARDS,
                items = items,
                rows = rows,
                timeline = timeline,
                arts = arts
            )
        }
    }
}
