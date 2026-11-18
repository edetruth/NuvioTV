package com.nuvio.tv.core.sportslive.scores

/**
 * Score delay (#41): IPTV streams run behind the broadcast, so a live score could show a goal before
 * it happens on screen. Every change to a game's score, clock or state is kept with the time it
 * arrived, and the version from the delay ago is shown. Everything else (names, logos, start time,
 * league) is always the newest. The game situation (down and distance, count, runners, last play)
 * is held back with the score. A game first seen while live shows with its score, clock and scorers
 * withheld until the delay has passed (enabling the delay, app start, first visit, a re-track), so the
 * newest score never leaks. So is a game first seen already final that may have just ended: ESPN's
 * scoreboard gives no end time, so a final whose start is within [RECENT_START_MS] is held for the
 * delay too ([mayHaveJustEnded]). One first seen before play, or long over, shows as it is.
 *
 * Adapted from SportsScoreDelay in homelessbrian/NuvioPlusIPTV (GPL-3.0, as this project).
 */
internal class ScoreDelay {

    private class Version(val at: Long, val snapshot: ScoreSnapshot)

    /** The parts of a game the delay holds back. */
    private data class Live(
        val state: ScoreState,
        val clockText: String?,
        val isFinal: Boolean,
        val homeScore: Int?,
        val awayScore: Int?,
        val homeLines: List<Int>,
        val awayLines: List<Int>,
        val scorers: List<ScoreScorer>,
        val situation: GameSituation?
    )

    private fun live(s: ScoreSnapshot) =
        Live(s.state, s.clockText, s.isFinal, s.home.score, s.away.score, s.home.linescores, s.away.linescores, s.scorers, s.situation)

    private class History(val versions: ArrayDeque<Version> = ArrayDeque(), var lastSeen: Long = 0L)

    /** Event id → its versions, oldest first. Kept for [FORGET_AFTER_MS] after the event was last seen. */
    private val history = HashMap<String, History>()

    /** When the next held-back change is due (null: nothing waiting); set by [apply]. */
    var nextChangeAt: Long? = null
        private set

    /**
     * Records [raw] (newest data, by card key) and returns what to show with [delayMs]. Off (0) passes
     * [raw] through and forgets the history.
     */
    @Synchronized
    fun apply(raw: Map<String, ScoreSnapshot>, delayMs: Long, now: Long): Map<String, ScoreSnapshot> {
        if (delayMs <= 0) {
            history.clear()
            nextChangeAt = null
            return raw
        }
        val cutoff = now - delayMs
        var next: Long? = null
        // Several cards can show one event: record each event once.
        val shownByEvent = HashMap<String, ScoreSnapshot>()
        for (snapshot in raw.values) {
            if (snapshot.eventId in shownByEvent) continue
            val entry = history.getOrPut(snapshot.eventId) { History() }
            entry.lastSeen = now
            val versions = entry.versions
            val last = versions.lastOrNull()
            if (last == null && (snapshot.state == ScoreState.IN || mayHaveJustEnded(snapshot, now))) {
                // First seen live, or final but maybe only just: nothing is known from [delayMs] ago,
                // so the score waits that long.
                versions.addLast(Version(now, withheld(snapshot)))
            }
            if (last == null || live(last.snapshot) != live(snapshot)) versions.addLast(Version(now, snapshot))
            // The newest version old enough to show; older ones are no longer needed.
            while (versions.size > 1 && versions[1].at <= cutoff) versions.removeFirst()
            versions.getOrNull(1)?.let { pending ->
                val due = pending.at + delayMs
                if (next == null || due < next!!) next = due
            }
            shownByEvent[snapshot.eventId] = withLive(snapshot, versions.first().snapshot)
        }
        // By age, not presence: a game that drops out for a moment (a paused feed, a re-track) keeps its history.
        history.values.removeAll { now - it.lastSeen > FORGET_AFTER_MS }
        nextChangeAt = next
        return raw.mapValues { (_, snapshot) -> shownByEvent.getValue(snapshot.eventId) }
    }

    private fun withheld(s: ScoreSnapshot): ScoreSnapshot = s.copy(
        clockText = null,
        home = s.home.copy(score = null, linescores = emptyList()),
        away = s.away.copy(score = null, linescores = emptyList()),
        scorers = emptyList(),
        situation = null
    )

    companion object {
        private const val FORGET_AFTER_MS = 10 * 60_000L

        /** A game that started longer ago than this has not just ended (ESPN gives no end time). */
        private const val RECENT_START_MS = 12 * 60 * 60_000L

        /**
         * A golf final is dated by its final round's day (US Eastern midnight): that round, a Monday
         * finish included, is over within this long of it.
         */
        private const val GOLF_FINAL_DAY_MS = 36 * 60 * 60_000L

        /**
         * True when [s] is a final that may have ended within a score delay: ESPN gives no end or
         * last-update time, so any final that started within [RECENT_START_MS] (or at an unknown time);
         * golf, dated by its final round's day, within [GOLF_FINAL_DAY_MS] of that day's start.
         */
        fun mayHaveJustEnded(s: ScoreSnapshot, now: Long): Boolean {
            if (s.state != ScoreState.POST || !s.isFinal) return false
            val start = s.startMs ?: return true
            return now - start < if (s.individual is GolfResult) GOLF_FINAL_DAY_MS else RECENT_START_MS
        }
    }

    private fun withLive(newest: ScoreSnapshot, shown: ScoreSnapshot): ScoreSnapshot =
        if (shown === newest || live(shown) == live(newest)) {
            newest
        } else {
            newest.copy(
                state = shown.state,
                clockText = shown.clockText,
                isFinal = shown.isFinal,
                home = newest.home.copy(score = shown.home.score, linescores = shown.home.linescores),
                away = newest.away.copy(score = shown.away.score, linescores = shown.away.linescores),
                scorers = shown.scorers,
                situation = shown.situation
            )
        }
}
