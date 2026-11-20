package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.scores.ScoreMatcher
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ESPN's start for games an addon lists live that ESPN has not started and schedules later (live.20: "Nice / PSG ·
 * LIVE" the day before the game), by source key ([com.nuvio.tv.domain.model.SportsLiveItem.streamKey]). A wrong LIVE is
 * worse than none: such a card is listed on Upcoming at ESPN's start and goes live by the clock from then
 * ([SportsLiveItemBucketer.reclock]). Filled by the score repository from its matches; never a score. A start is kept
 * until [KEEP_AFTER_START_MS] after it, so a card that left Live now (and is no longer tracked there) never flips back.
 * Thread-safe.
 */
class SportsLiveEspnStarts {

    private val starts = HashMap<String, Long>()
    private val revisionFlow = MutableStateFlow(0L)

    /** Bumped on every change: lists bucketed under an older revision are built again. */
    val revision: StateFlow<Long> = revisionFlow.asStateFlow()

    /** ESPN's start for the source [key] at [nowMs], or null when ESPN gave none (or it is long past). */
    @Synchronized
    fun startOf(key: String, nowMs: Long): Long? = starts[key]?.takeIf { nowMs < it + KEEP_AFTER_START_MS }

    @Synchronized
    fun has(key: String): Boolean = key in starts

    /** ESPN's game for the sources [keys] starts at [startMs]. */
    @Synchronized
    fun record(keys: Collection<String>, startMs: Long, nowMs: Long) {
        var changed = false
        for (key in keys) {
            if (starts[key] == startMs) continue
            if (starts.size >= MAX_KEPT) {
                starts.values.removeAll { nowMs >= it + KEEP_AFTER_START_MS }
                if (starts.size >= MAX_KEPT) starts.clear()
            }
            starts[key] = startMs
            changed = true
        }
        if (changed) revisionFlow.value += 1
    }

    @Synchronized
    internal fun clear() {
        starts.clear()
        revisionFlow.value += 1
    }

    companion object {
        /** A start is used this long after it, so the game stays live by the clock (as long as a live window). */
        const val KEEP_AFTER_START_MS = 6 * 60 * 60_000L
        private const val MAX_KEPT = 2_000

        /** What every list uses. */
        val shared = SportsLiveEspnStarts()

        /**
         * [event]'s start when ESPN has not started it and schedules it more than [ScoreMatcher.LATER_LEAD_MS] after
         * [nowMs] (a card matched to it is not live yet); null when it is in play, over, near or has no time.
         */
        fun laterStart(event: ScoreSnapshot, nowMs: Long): Long? {
            val start = event.startMs ?: return null
            return start.takeIf { event.state == ScoreState.PRE && event.timeValid && start > nowMs + ScoreMatcher.LATER_LEAD_MS }
        }
    }
}
