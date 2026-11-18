package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.scores.LiveExcitement
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.domain.model.SportsLiveItem

/**
 * How far along a live game is (#132): the share of regulation played, for the 2 dp bar along a live
 * row's bottom (the Timeline's one idea kept on Live Now). Pure.
 *
 * 1. A strictly matched ESPN game in play ([event]): its clock read per sport ("Q4 4:12", "67'").
 * 2. Otherwise the time since its timed start against its sport's length ([SportsLiveGameLength.knownOf], #60).
 * 3. Null (no bar) when neither is known: no start, a long-session or unknown sport.
 */
object SportsLiveProgress {

    /** [item]'s share (0..1) at [nowMs]; [event] is its strict ESPN match ([com.nuvio.tv.core.sportslive.scores.ScoreChips.matchedEvent]) or null. */
    fun share(item: SportsLiveItem, event: ScoreSnapshot?, nowMs: Long): Float? {
        if (event != null && event.state == ScoreState.IN) {
            LiveExcitement.clockOf(event.league.sport, clockTextOf(event)).progress?.let { return it.toFloat().coerceIn(0f, 1f) }
        }
        val start = event?.takeIf { it.timeValid }?.startMs ?: item.startMs?.takeIf { item.startHasTimeOfDay } ?: return null
        val length = SportsLiveGameLength.knownOf(item) ?: return null
        if (nowMs <= start) return null
        return ((nowMs - start).toFloat() / length).coerceIn(0f, 1f)
    }

    /**
     * [event]'s clock as the scorer reads it. College basketball plays two halves, but ESPN's short clock
     * names them like periods ("3:25 - 1st"): read as halves there, never as quarters.
     */
    internal fun clockTextOf(event: ScoreSnapshot): String? {
        val text = event.clockText ?: return null
        val halves = event.league.sport == "basketball" && event.league.league.contains("college")
        return if (halves && !text.contains("half", ignoreCase = true) && COLLEGE_PERIOD.containsMatchIn(text)) "$text Half" else text
    }

    private val COLLEGE_PERIOD = Regex("""-\s*(1st|2nd)\s*$""")
}
