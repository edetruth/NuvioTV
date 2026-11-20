package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.scores.TeamStanding
import com.nuvio.tv.core.sportslive.scores.StandingsRepository
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoreChips
import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.BigGame
import androidx.compose.runtime.Immutable
import com.nuvio.tv.core.sportslive.EndedGame
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.GameArt
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.core.sportslive.scores.WhyTag
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveTab
import java.time.ZoneId

/** The scores, setting and reveals the screen has: what the Scoreboard and drawn cards read. */
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
    val items: List<SportsLiveItem>,
    val scores: SportsLiveBoardScores,
    /** Live now's Just ended verdicts (#60); empty on other tabs. */
    val ended: Map<String, EndedGame>,
    /** Live now's "no streams yet" cards by card key (#145): drawn dimmed; empty on other tabs. */
    val noStreams: Set<String> = emptySet(),
    /** Live now's excitement badges by card key (#43, Scores shown); empty otherwise. */
    val excitement: Map<String, WhyTag>,
    val followed: List<FollowedTeam>,
    /** The view model's clock, to the minute. */
    val clockMs: Long,
    /** Learned logos' revision (#66): the drawn cards are built again when it moves. */
    val logoRevision: Int,
    /** Standings by league (#137): Upcoming's big games read them; empty on the other tabs. */
    val standings: Map<EspnLeague, Map<String, TeamStanding>> = emptyMap()
)

/**
 * What the list draws for one tab and filter (#71): the items in the order shown, the Scoreboard's rows
 * (Live now and Upcoming, V2: one view per tab) and each card's drawn art. Built in the view model on its
 * compute dispatcher, never in composition, so a large list (60 games on Upcoming) opens without holding
 * the main thread.
 */
@Immutable
data class SportsLiveBoard(
    val tab: SportsLiveTab,
    val filter: String?,
    val items: List<SportsLiveItem>,
    /** The list's rows: the Scoreboard's (Live now, Upcoming), Channels' by region, Replays' (V2 P4); null for Results. */
    val rows: List<ScoreboardRow>?,
    /** Each item's drawn card by `addonId|id` (null: the addon's art). */
    val arts: Map<String, GameArt?>,
    /** Upcoming's Agenda (V2, P3) or Replays' days (P4): [rows] by day; null on the other tabs. */
    val agenda: SportsLiveAgenda? = null
) {
    /** True when this board shows [tab] with [filter]. */
    fun shows(tab: SportsLiveTab, filter: String?): Boolean = this.tab == tab && this.filter == filter

    val rowOf: Map<String, ScoreboardRow> by lazy { rows?.associateBy { it.item.streamKey }.orEmpty() }

    companion object {
        /** The tabs drawn from Scoreboard rows (V2, #132): Live now, and Upcoming (as its Agenda). */
        fun isScoreboard(tab: SportsLiveTab): Boolean = tab == SportsLiveTab.LIVE_NOW || tab == SportsLiveTab.UPCOMING

        /**
         * Upcoming's big games (#137): each row with a strict ESPN match rated ([BigGame]) from its ranks, records,
         * channel and note, and its league's standings; a big one carries its rating (the badge, Games to watch).
         */
        internal fun withBigGames(
            rows: List<ScoreboardRow>,
            scores: Map<String, ScoreSnapshot>,
            standings: Map<EspnLeague, Map<String, TeamStanding>>
        ): List<ScoreboardRow> = rows.map { row ->
            val event = ScoreChips.matchedEvent(row.item, scores[row.item.streamKey])?.takeIf { it.state == ScoreState.PRE }
                ?: return@map row
            val rating = BigGame.rate(event, standings[StandingsRepository.leagueOf(event)].orEmpty(), followed = row.followed)
            if (rating.isBig) row.copy(bigGame = rating) else row
        }

        /** Builds the board for [inputs]: pure, and slow for a large list, so callers run it off the main thread. */
        fun build(inputs: SportsLiveBoardInputs, zone: ZoneId = ZoneId.systemDefault()): SportsLiveBoard {
            val tab = inputs.tab
            val scores = inputs.scores
            val rows = if (isScoreboard(tab)) {
                scoreboardRows(
                    items = inputs.items,
                    tab = tab,
                    scores = scores.scores,
                    mode = scores.mode,
                    revealed = scores.revealed,
                    nowMs = inputs.clockMs,
                    ended = inputs.ended,
                    followsSide = { item, side -> inputs.followed.any { SportsLiveTeams.followsSideOfAnySource(it, side, item) } },
                    excitement = inputs.excitement
                ).map { row -> if (row.item.streamKey in inputs.noStreams) row.copy(noStreams = true) else row }
                    .let { built -> if (tab == SportsLiveTab.UPCOMING) withBigGames(built, scores.scores, inputs.standings) else built }
            } else {
                when (tab) {
                    // V2 P4: Channels by region, Replays under their spoiler-free titles, by day.
                    SportsLiveTab.CHANNELS -> channelRows(inputs.items)
                    SportsLiveTab.REPLAYS -> replayRows(inputs.items)
                    else -> null
                }
            }
            val agenda = when {
                rows == null -> null
                tab == SportsLiveTab.UPCOMING -> SportsLiveAgenda.of(rows, inputs.clockMs, zone)
                tab == SportsLiveTab.REPLAYS -> SportsLiveAgenda.ofReplays(rows, inputs.clockMs, zone)
                else -> null
            }
            val items = agenda?.items ?: rows?.map { it.item } ?: inputs.items
            // The cards' drawn art; a row already carries its own.
            val arts = HashMap<String, GameArt?>(items.size * 2)
            rows?.forEach { arts[it.item.streamKey] = it.art }
            for (item in items) {
                if (item.streamKey !in arts) arts[item.streamKey] = GameArt.of(item, scores.scores[item.streamKey])
            }
            return SportsLiveBoard(tab = tab, filter = inputs.filter, items = items, rows = rows, arts = arts, agenda = agenda)
        }
    }
}
