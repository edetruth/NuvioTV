package com.nuvio.tv.ui.screens.sportslive.dual

import com.nuvio.tv.core.sportslive.scores.LiveExcitement
import com.nuvio.tv.core.sportslive.scores.ScoreChips
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind

/**
 * Where the end card's "Up next" goes when the game playing ends (#161). Pure: it reads only what is at hand.
 *
 * 1. a followed team's live game;
 * 2. else a big game in the same league (ESPN's, when both are matched) or sport;
 * 3. else the top big game live;
 * 4. else the next game in the zap lineup, forward from the one that ended (wrapping).
 *
 * "Big" is a live game the excitement scorer (#43, on the score-delayed snapshot) rates at least
 * [BIG_GAME_MIN]; the strongest wins, ties keep lineup order. Never a game over or with no working stream
 * ([finishedKeys]), one tried and failed this session ([tried], as "Next live game" never offers one), a
 * channel, or one sharing a source with the game that ended. Null: nothing else is live.
 */
internal object SportsLiveNextGame {
    /** A live game rated at least this is a big game (a close game past half way rates about 2). */
    const val BIG_GAME_MIN = 1.0

    fun pick(
        ended: SportsLiveItem,
        lineup: List<SportsLiveItem>,
        scores: Map<String, ScoreSnapshot>,
        finishedKeys: Set<String>,
        tried: Set<String>
    ): SportsLiveItem? {
        val left = SportsLiveSecondGame.candidates(ended, lineup, scores, finishedKeys)
            .filter { item -> item.id !in tried && item.alternates.none { it.id in tried } }
        if (left.isEmpty()) return null
        val forward = forwardFrom(ended, lineup, left)
        forward.firstOrNull { it.followed }?.let { return it }
        val rated = forward.mapNotNull { item ->
            val event = ScoreChips.matchedEvent(item, scores[item.streamKey]) ?: return@mapNotNull null
            val score = LiveExcitement.rate(event)?.score ?: return@mapNotNull null
            if (score < BIG_GAME_MIN) null else Triple(item, event, score)
        }
        val endedEvent = ScoreChips.matchedEvent(ended, scores[ended.streamKey])
        rated.filter { (item, event, _) -> sameLeague(ended, endedEvent, item, event) }
            .maxByOrNull { it.third }?.let { return it.first }
        rated.maxByOrNull { it.third }?.let { return it.first }
        return forward.first()
    }

    /**
     * The game the nothing-live state names (#161): the soonest game of [items] still to start (timed, not
     * live, not a channel or replay), a followed team's first when one starts within [FOLLOWED_AHEAD_MS].
     */
    fun upcoming(items: List<SportsLiveItem>, nowMs: Long): SportsLiveItem? {
        val ahead = items.filter { item ->
            val start = item.startMs
            item.kind == SportsLiveKind.EVENTS && !item.isChannel && !item.isLive && item.startHasTimeOfDay &&
                start != null && start > nowMs
        }.sortedBy { it.startMs }
        val soonest = ahead.firstOrNull() ?: return null
        return ahead.firstOrNull { it.followed && (it.startMs ?: Long.MAX_VALUE) - nowMs <= FOLLOWED_AHEAD_MS } ?: soonest
    }

    private const val FOLLOWED_AHEAD_MS = 3 * 60 * 60_000L

    private fun sameLeague(ended: SportsLiveItem, endedEvent: ScoreSnapshot?, item: SportsLiveItem, event: ScoreSnapshot): Boolean {
        if (endedEvent != null) return endedEvent.league == event.league
        val sport = ended.sport?.trim()?.lowercase() ?: return false
        return sport.isNotEmpty() && item.sport?.trim()?.lowercase() == sport
    }

    /** [games] in [lineup] order forward from [from] (wrapping); games not in the lineup keep their order, last. */
    private fun forwardFrom(from: SportsLiveItem, lineup: List<SportsLiveItem>, games: List<SportsLiveItem>): List<SportsLiveItem> {
        val here = lineup.indexOfFirst { it.streamKey == from.streamKey }
        if (here < 0 || lineup.isEmpty()) return games
        val position = lineup.withIndex().associate { (index, item) -> item.streamKey to index }
        return games.sortedBy { item ->
            position[item.streamKey]?.let { (it - here - 1 + lineup.size) % lineup.size } ?: Int.MAX_VALUE
        }
    }
}
