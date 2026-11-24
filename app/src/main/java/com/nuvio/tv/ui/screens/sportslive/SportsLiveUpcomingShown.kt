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
        // #355: a late start stays, but not for ever: once the game's own timed start has passed, an ESPN still saying
        // "not started" more than [UPCOMING_LATE_START_GRACE_MS] past the later of ESPN's start and its own is stale, and
        // the game leaves. #460: a game whose own start is still ahead, or that has no time of its own (Time TBA), stays
        // whatever ESPN's (possibly stale or session) time says: it is on no other list until then.
        if (event?.state == ScoreState.PRE) {
            val own = item.startMs?.takeIf { item.startHasTimeOfDay } ?: return false
            if (own > nowMs) return false
            val start = maxOf(own, event.startMs?.takeIf { event.timeValid } ?: own)
            return nowMs - start > UPCOMING_LATE_START_GRACE_MS
        }
        // L7: a date-only row whose date has passed everywhere goes, between sortings too.
        if (com.nuvio.tv.core.sportslive.SportsLiveItemBucketer.dateOnlyPast(item, nowMs)) return true
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

/** #355: how long past its start a game ESPN still has as not started stays on Upcoming (a late start); then ESPN is stale. */
internal const val UPCOMING_LATE_START_GRACE_MS = 2 * 60 * 60_000L
