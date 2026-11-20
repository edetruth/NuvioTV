package com.nuvio.tv.ui.screens.sportslive.dual

import com.nuvio.tv.core.sportslive.SportsLiveGameReadiness
import com.nuvio.tv.core.sportslive.SportsLiveReadyMark
import com.nuvio.tv.core.sportslive.SportsLiveWorkingLinks
import com.nuvio.tv.core.sportslive.scores.LiveExcitement
import com.nuvio.tv.core.sportslive.scores.ScoreChips
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.SpoilerProtection
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
 * [BIG_GAME_MIN]; the strongest wins, ties keep lineup order. Within each step a game ✓ ready ([knownGood],
 * #147) goes first (#183). Never a game over or with no working stream ([finishedKeys]: Live now's ended and
 * "Probably ended" games too; [noStream]: game ids marked ⚠ no working stream, or whose every known link failed,
 * [sportsLiveNoStreamIds]), one tried and failed this session ([tried], as "Next live game" never offers one), a
 * game saved with Watch later ([savedForLater], #184: playing it would show it live), a channel, or one sharing
 * a source with the game that ended. Null: nothing else is live.
 */
internal object SportsLiveNextGame {
    /** A live game rated at least this is a big game (a close game past half way rates about 2). */
    const val BIG_GAME_MIN = 1.0

    fun pick(
        ended: SportsLiveItem,
        lineup: List<SportsLiveItem>,
        scores: Map<String, ScoreSnapshot>,
        finishedKeys: Set<String>,
        tried: Set<String>,
        knownGood: (SportsLiveItem) -> Boolean = { false },
        noStream: Set<String> = emptySet(),
        savedForLater: (SportsLiveItem) -> Boolean = { false }
    ): SportsLiveItem? {
        val left = SportsLiveSecondGame.candidates(ended, lineup, scores, finishedKeys)
            .filter { item -> item.id !in tried && item.alternates.none { it.id in tried } && item.id !in noStream }
            .filterNot(savedForLater)
        if (left.isEmpty()) return null
        val forward = forwardFrom(ended, lineup, left)
        forward.filter { it.followed }.readyFirst(knownGood).firstOrNull()?.let { return it }
        val rated = forward.mapNotNull { item ->
            // A spoiler-protected game (#151) is never picked for how close it is.
            val event = ScoreChips.matchedEvent(item, scores[item.streamKey])?.takeUnless { it.spoilerProtected } ?: return@mapNotNull null
            val score = LiveExcitement.rate(event)?.score ?: return@mapNotNull null
            if (score < BIG_GAME_MIN) null else Triple(item, event, score)
        }
        val endedEvent = ScoreChips.matchedEvent(ended, scores[ended.streamKey])
        rated.filter { (item, event, _) -> sameLeague(ended, endedEvent, item, event) }
            .readyFirst { knownGood(it.first) }
            .maxByOrNull { it.third }?.let { return it.first }
        rated.readyFirst { knownGood(it.first) }.maxByOrNull { it.third }?.let { return it.first }
        return forward.readyFirst(knownGood).first()
    }

    /** The ✓ ready ones of these games (#183), when there are any; else all of them. */
    private fun <T> List<T>.readyFirst(ready: (T) -> Boolean): List<T> = filter(ready).ifEmpty { this }

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

/**
 * The games saved with Watch later (#151), as every next-game pick reads them (#184): [item] is one when its ESPN
 * match in [scores] (or a merged source's) is a game [protection] holds saved, else when it names the same two
 * sides near a saved game's start ([SpoilerProtection.sameGame]). Only Watch later counts: "my teams" protection
 * hides a score but never keeps a live game from playing.
 */
internal fun sportsLiveSavedForLater(protection: SpoilerProtection, scores: Map<String, ScoreSnapshot>): (SportsLiveItem) -> Boolean {
    val saved = protection.saved
    if (saved.isEmpty()) return { false }
    val ids = saved.mapTo(HashSet()) { it.eventId }
    return { item ->
        (listOf(item) + item.alternates).any { source ->
            ScoreChips.matchedEvent(source, scores[source.streamKey])?.eventId?.let { it in ids } == true
        } || saved.any { SpoilerProtection.sameGame(it, item) }
    }
}

/**
 * Game ids with no working stream now: those marked ⚠ no working stream ([readiness], #147), and those of [items]
 * whose every link a lookup found failed this session and is still quarantined ([SportsLiveWorkingLinks.allQuarantined]).
 */
internal fun sportsLiveNoStreamIds(
    items: List<SportsLiveItem>,
    readiness: SportsLiveGameReadiness,
    workingLinks: SportsLiveWorkingLinks
): Set<String> =
    readiness.marks.value.filterValues { it == SportsLiveReadyMark.NO_STREAM }.keys +
        items.filter { workingLinks.allQuarantined(it.id) }.map { it.id }
