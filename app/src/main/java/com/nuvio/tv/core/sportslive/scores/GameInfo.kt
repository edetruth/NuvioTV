package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.domain.model.SportsLiveItem

/**
 * The in-player game info overlay's content (#139): the score block (scoreline, clock or final note,
 * linescore, as the details pane shows it), the [situation] line's source and the top performers, one
 * line each, in the card's team order.
 */
data class GameInfo(
    val details: ScoreDetails,
    /** The delayed scoreboard event, for the situation line's team names and inning. */
    val event: ScoreSnapshot,
    val situation: GameSituation?,
    val leaders: List<Performer>
)

/**
 * Builds [GameInfo] from what the player already has: the delayed scoreboard snapshot of the game playing
 * and, when the Game page's summary for it is at hand ([GameSummaryRepository], no new kind of request),
 * its top performers.
 *
 * Spoilers: Scores Off shows no overlay at all; under Hide until focused the overlay is asked for on
 * purpose, so it shows the score (as Reveal does on the Game page). The score delay is already applied to
 * the snapshot; the summary is not delayed, so its performers show only while its score is the delayed
 * one ([leadersOf]): a performer's line never runs ahead of the score on screen.
 */
object GameInfoBuilder {

    /** Null when the overlay has nothing to show: Scores Off or not read yet, or no strict match for [item]. */
    fun of(item: SportsLiveItem, snapshot: ScoreSnapshot?, mode: ScoresMode?, summary: GameSummary?): GameInfo? {
        if (mode == null || mode == ScoresMode.OFF) return null
        val event = ScoreChips.matchedEvent(item, snapshot) ?: return null
        val details = ScoreDetailsBuilder.of(item, event, mode, revealed = true) ?: return null
        val live = details is ScoreDetails.Shown && details.live
        return GameInfo(
            details = details,
            event = event,
            situation = if (live) event.situation else null,
            leaders = if (details is ScoreDetails.Shown) leadersOf(item, event, summary) else emptyList()
        )
    }

    /** True when [item] has a game info overlay to show now (the key opens it; else the key does what it did). */
    fun available(item: SportsLiveItem?, snapshot: ScoreSnapshot?, mode: ScoresMode?): Boolean =
        item != null && of(item, snapshot, mode, summary = null) != null

    /**
     * One top performer per team, the card's first team first; empty unless [summary] is [event]'s game
     * and its score is the delayed score ([event]'s), so a performer's points never reveal a later score.
     */
    fun leadersOf(item: SportsLiveItem, event: ScoreSnapshot, summary: GameSummary?): List<Performer> {
        summary ?: return emptyList()
        if (summary.eventId != event.eventId) return emptyList()
        if (summary.home.score != event.homeScore || summary.away.score != event.awayScore) return emptyList()
        if (summary.isFinal && !event.isFinal) return emptyList()
        val (first, second) = ScoreChips.orderedSides(item) ?: return emptyList()
        val firstIsHome = ScoreMatcher.firstIsHome(first, second, event) ?: return emptyList()
        val order = if (firstIsHome) listOf(ScoreSide.HOME, ScoreSide.AWAY) else listOf(ScoreSide.AWAY, ScoreSide.HOME)
        return order.mapNotNull { side -> summary.performers.firstOrNull { it.side == side } }
    }
}
