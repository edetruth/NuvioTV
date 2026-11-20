package com.nuvio.tv.ui.screens.sportslive.dual

import com.nuvio.tv.core.sportslive.scores.LiveExcitement
import com.nuvio.tv.core.sportslive.scores.ScoreChips
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.domain.model.Stream

/**
 * The game "Watch with another game" pairs with [first] (#57), and the one "Next live game" in the
 * full-time toast moves to (#60). Pure: it reads only what is at hand and never looks a stream up.
 *
 * 1. The next live game of a followed team;
 * 2. else the most exciting live game (#43's scorer, on the score-delayed snapshot only);
 * 3. else the next live game by start time.
 *
 * "Next" is by start time from [first]'s start, wrapping round. A finished game is never picked: one in
 * [finishedKeys] (Just ended, the probe's ended games), one ESPN shows final or postponed, a channel,
 * or anything sharing a source with [first].
 */
internal object SportsLiveSecondGame {

    fun pick(
        first: SportsLiveItem,
        items: List<SportsLiveItem>,
        scores: Map<String, ScoreSnapshot>,
        finishedKeys: Set<String>
    ): SportsLiveItem? {
        val candidates = candidates(first, items, scores, finishedKeys)
        if (candidates.isEmpty()) return null
        candidates.filter { it.followed }.takeIf { it.isNotEmpty() }?.let { return nextByStart(first, it) }
        val rated = candidates.mapNotNull { item ->
            val event = ScoreChips.matchedEvent(item, scores[item.streamKey]) ?: return@mapNotNull null
            LiveExcitement.rate(event)?.let { item to it.score }
        }
        // Ties keep list order (maxByOrNull keeps the first).
        rated.maxByOrNull { it.second }?.let { return it.first }
        return nextByStart(first, candidates)
    }

    /** The live games [first] may be paired with, in [items] order. */
    fun candidates(
        first: SportsLiveItem,
        items: List<SportsLiveItem>,
        scores: Map<String, ScoreSnapshot>,
        finishedKeys: Set<String>
    ): List<SportsLiveItem> {
        val firstKeys = first.sourceKeys()
        return items.filter { item ->
            item.kind == SportsLiveKind.EVENTS && !item.isChannel &&
                !item.sharesSourceWith(firstKeys) &&
                item.sourceKeys().none { it in finishedKeys } &&
                !espnOver(item, scores[item.streamKey])
        }
    }

    /**
     * "Next live game" after [current] failed (#138 follow-up; the player's error screen): the next of
     * [lineup]'s live games forward from [current] (wrapping), never one over ([finishedKeys]: ended games and those
     * marked with no working stream) nor one [tried] and failed this session ([current] included), so two dead games
     * never send the viewer back and forth. A game with a known-good link ([knownGood]: ✓ ready) goes first; one
     * checked with no working stream ([noStream] game ids: ⚠, #147) only when no other is left. Never a game saved
     * with Watch later ([savedForLater], #184): playing it would show it live. Null when every game is used up: the
     * viewer is told so, never looped.
     */
    fun nextAfterFailure(
        current: SportsLiveItem,
        lineup: List<SportsLiveItem>,
        scores: Map<String, ScoreSnapshot>,
        finishedKeys: Set<String>,
        tried: Set<String>,
        knownGood: (SportsLiveItem) -> Boolean = { false },
        noStream: Set<String> = emptySet(),
        savedForLater: (SportsLiveItem) -> Boolean = { false }
    ): SportsLiveItem? {
        val untried = candidates(current, lineup, scores, finishedKeys)
            .filter { item -> item.id !in tried && item.alternates.none { it.id in tried } }
            .filterNot(savedForLater)
        if (untried.isEmpty()) return null
        val left = untried.filterNot { it.id in noStream }.ifEmpty { untried }
        val here = lineup.indexOfFirst { it.streamKey == current.streamKey }
        val forward = if (here < 0) {
            left
        } else {
            val position = lineup.withIndex().associate { (index, item) -> item.streamKey to index }
            left.sortedBy { item -> ((position[item.streamKey] ?: 0) - here - 1 + lineup.size) % lineup.size }
        }
        return forward.firstOrNull(knownGood) ?: forward.first()
    }

    /** ESPN's delayed view shows [item]'s strict match over (final) or off (postponed, suspended). */
    private fun espnOver(item: SportsLiveItem, snapshot: ScoreSnapshot?): Boolean {
        val state = ScoreChips.matchedEvent(item, snapshot)?.state ?: return false
        return state == ScoreState.POST || state == ScoreState.OFF
    }

    /** The first of [games] (by start, unknown starts last) starting at or after [first]; else the earliest. */
    private fun nextByStart(first: SportsLiveItem, games: List<SportsLiveItem>): SportsLiveItem {
        val byStart = games.sortedBy { it.startMs ?: Long.MAX_VALUE }
        val from = first.startMs ?: return byStart.first()
        return byStart.firstOrNull { start -> start.startMs?.let { it >= from } == true } ?: byStart.first()
    }
}

/**
 * "Watch with another game" (#57) on [first], from Live now: dual screen with [first] in slot 1 (the
 * left pane, on [leftStream] when its preview plays one) and [SportsLiveSecondGame]'s pick in slot 2,
 * with the "Paired with" hint. [items]: the list shown (dual Up/Down), [zapItems]: every Live now game,
 * where slot 2 is picked from. Null when there is no game to pair with.
 */
internal fun sportsLiveWatchWithSession(
    first: SportsLiveItem,
    items: List<SportsLiveItem>,
    zapItems: List<SportsLiveItem>,
    filter: String?,
    scores: Map<String, ScoreSnapshot>,
    finishedKeys: Set<String>,
    leftStream: Stream? = null
): SportsLiveDualSession? {
    val second = SportsLiveSecondGame.pick(first, zapItems, scores, finishedKeys) ?: return null
    return SportsLiveDualSession(
        left = first,
        right = second,
        items = items.toList(),
        filter = filter,
        tab = SportsLiveTab.LIVE_NOW,
        leftStream = leftStream,
        zapItems = zapItems.toList(),
        pairedHint = true,
        finishedKeys = finishedKeys
    )
}
