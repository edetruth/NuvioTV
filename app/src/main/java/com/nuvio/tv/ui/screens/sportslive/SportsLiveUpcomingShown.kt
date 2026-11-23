package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.scores.ScoreChips
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.domain.model.SportsLiveItem

/** Upcoming as shown ([sportsLiveUpcomingShown]): its rows, and the started one kept only because it has focus. */
internal class SportsLiveUpcomingShown(val items: List<SportsLiveItem>, val heldId: String?)

/**
 * Upcoming's [sorted] rows without the games that have started, at [nowMs] (field report #25, live.26). The sorting reads
 * the clock only when it runs (a load, a directory change, a tip-off of a game whose start alone says it is on), so a
 * game an addon marks itself, sorted at 13:00, stayed listed at 20:18; and a game listed with no time of its own
 * (Time TBA, a date alone) is placed at ESPN's time and stayed there after ESPN called it final. Each state build drops:
 * - a game ESPN shows final ([ScoreState.POST]), even with focus on it (as Live now's O rule: nothing to wait for);
 * - a game ESPN shows in play whose ESPN game Live now lists ([liveNowEvents], ESPN event ids): it is there now;
 * - a game whose own timed start has passed (what the next sorting drops too).
 * A game ESPN shows in play that Live now does not list stays: Upcoming is then its only row to watch it from. A late
 * start ESPN still has before the game ([ScoreState.PRE]) and a game with no time of its own stay, as the Agenda has them.
 * Rows never jump under focus: a started game other than a final keeps its place while it has focus ([focusedId]),
 * where it was in [previous] (Upcoming as last shown), and leaves on the next build once focus moves on.
 */
internal fun sportsLiveUpcomingShown(
    previous: List<SportsLiveItem>,
    sorted: List<SportsLiveItem>,
    scores: Map<String, ScoreSnapshot>,
    nowMs: Long,
    focusedId: String?,
    liveNowEvents: Set<String>
): SportsLiveUpcomingShown {
    fun stateOf(item: SportsLiveItem): ScoreSnapshot? = ScoreChips.matchedEvent(item, scores[item.streamKey])
    fun final(item: SportsLiveItem): Boolean = stateOf(item)?.state == ScoreState.POST
    fun started(item: SportsLiveItem): Boolean {
        val event = stateOf(item)
        if (event?.state == ScoreState.POST) return true
        if (event?.state == ScoreState.IN && event.eventId in liveNowEvents) return true
        val start = item.startMs
        return item.startHasTimeOfDay && start != null && start <= nowMs
    }
    val listed = sorted.filterNot(::started)
    if (focusedId == null || listed.any { it.id == focusedId }) return SportsLiveUpcomingShown(listed, null)
    // The focused row as it is now (a sorting that still has it), else as last shown.
    val focused = sorted.firstOrNull { it.id == focusedId } ?: previous.firstOrNull { it.id == focusedId }
    if (focused == null || final(focused)) return SportsLiveUpcomingShown(listed, null)
    val index = previous.indexOfFirst { it.id == focusedId }
    if (index < 0) return SportsLiveUpcomingShown(listed, null)
    val listedIds = listed.mapTo(HashSet()) { it.id }
    val before = previous.subList(0, index).lastOrNull { it.id in listedIds }
    val at = if (before == null) 0 else listed.indexOfFirst { it.id == before.id } + 1
    return SportsLiveUpcomingShown(listed.toMutableList().apply { add(at, focused) }, focusedId)
}
