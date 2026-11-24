package com.nuvio.tv.core.sportslive.scores

/**
 * Score delay (#41): IPTV streams run behind the broadcast, so a live score could show a goal before
 * it happens on screen. Every change to a game's score, clock or state is kept with the time it
 * arrived, and the version from the delay ago is shown. Everything else (names, logos, start time,
 * league) is always the newest. The game situation (down and distance, count, runners, last play),
 * the teams' season records and soccer form (updated as a game ends, so they tell its result) and an
 * individual sport's result (a tennis match's games and sets) are held back with the score (#281).
 *
 * A game first seen (app start, first visit, a re-track, the delay turned on) shows its score at once,
 * live or just ended: nothing on screen is ahead of it yet, and a blank card for a minute on every
 * app open read as broken (live.23). Only the changes after that wait out the delay.
 *
 * The exception is a game being watched ([apply]'s `holdFirstSeen`: the player's game, each dual pane,
 * the mini player): until a version of it is known from the delay ago, it shows with its score, clock
 * and scorers withheld when it is live, or a final that may have just ended ([mayHaveJustEnded]: ESPN's
 * scoreboard gives no end time, so a final whose start is within [RECENT_START_MS]). Its picture runs
 * behind, so its newest score would leak. A game first seen before play, or long over, shows as it is.
 * The hold lasts one full delay after the game stops being watched (a zap, CH+/-, the mini player closed): the
 * picture seen was that far behind, so its row must not jump ahead of it (live.23 review S1).
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
        val situation: GameSituation?,
        val homeRecord: String?,
        val awayRecord: String?,
        val homeForm: String?,
        val awayForm: String?,
        val individual: IndividualResult?
    )

    private fun live(s: ScoreSnapshot) = Live(
        s.state, s.clockText, s.isFinal, s.home.score, s.away.score, s.home.linescores, s.away.linescores, s.scorers, s.situation,
        s.home.record, s.away.record, s.home.form, s.away.form, s.individual
    )

    private class History(val versions: ArrayDeque<Version> = ArrayDeque(), var lastSeen: Long = 0L)

    /** Event id → its versions, oldest first. Kept for [FORGET_AFTER_MS] after the event was last seen. */
    private val history = HashMap<String, History>()

    /** Event id → until when it keeps the watched game's hold after it was last watched (S1). */
    private val watchedUntil = HashMap<String, Long>()

    /** When the next held-back change is due (null: nothing waiting); set by [apply]. */
    var nextChangeAt: Long? = null
        private set

    /**
     * Records [raw] (newest data, by card key) and returns what to show with [delayMs]. Off (0) passes
     * [raw] through and forgets the history. [holdFirstSeen] are the event ids of the games being watched:
     * they keep the first-seen hold (see the class KDoc).
     */
    @Synchronized
    fun apply(
        raw: Map<String, ScoreSnapshot>,
        delayMs: Long,
        now: Long,
        holdFirstSeen: Set<String> = emptySet()
    ): Map<String, ScoreSnapshot> {
        if (delayMs <= 0) {
            history.clear()
            watchedUntil.clear()
            nextChangeAt = null
            return raw
        }
        val cutoff = now - delayMs
        for (id in holdFirstSeen) watchedUntil[id] = now + delayMs
        watchedUntil.values.removeAll { it <= now }
        var next: Long? = null
        // Several cards can show one event: record each event once.
        val shownByEvent = HashMap<String, ScoreSnapshot>()
        for (snapshot in raw.values) {
            if (snapshot.eventId in shownByEvent) continue
            val entry = history.getOrPut(snapshot.eventId) { History() }
            entry.lastSeen = now
            val versions = entry.versions
            val last = versions.lastOrNull()
            if (last == null || live(last.snapshot) != live(snapshot)) versions.addLast(Version(now, snapshot))
            // The newest version old enough to show; older ones are no longer needed. With none old enough
            // yet, the first one seen shows.
            while (versions.size > 1 && versions[1].at <= cutoff) versions.removeFirst()
            val first = versions.first()
            // A game being watched with nothing known from the delay ago: its score waits.
            val held = snapshot.eventId in watchedUntil && first.at > cutoff &&
                (first.snapshot.state == ScoreState.IN || mayHaveJustEnded(first.snapshot, now))
            val due = if (held) first.at + delayMs else versions.getOrNull(1)?.let { it.at + delayMs }
            if (due != null && (next == null || due < next!!)) next = due
            shownByEvent[snapshot.eventId] = withLive(snapshot, if (held) withheld(first.snapshot) else first.snapshot)
        }
        // By age, not presence: a game that drops out for a moment (a paused feed, a re-track) keeps its history.
        history.values.removeAll { now - it.lastSeen > FORGET_AFTER_MS }
        nextChangeAt = next
        return raw.mapValues { (_, snapshot) -> shownByEvent.getValue(snapshot.eventId) }
    }

    private fun withheld(s: ScoreSnapshot): ScoreSnapshot = s.withResultWithheld()

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
                home = newest.home.copy(
                    score = shown.home.score, linescores = shown.home.linescores, record = shown.home.record, form = shown.home.form
                ),
                away = newest.away.copy(
                    score = shown.away.score, linescores = shown.away.linescores, record = shown.away.record, form = shown.away.form
                ),
                scorers = shown.scorers,
                situation = shown.situation,
                individual = shown.individual
            )
        }
}

/**
 * This game with nothing of its result (F4): no score, clock, lines, scorers or situation; no season records or form
 * (updated as it ends); a tennis match keeps its players, not its sets, winner, server or how it ended. Its state stays.
 * The one mask of a game held back: the score delay's first-seen hold ([ScoreDelay]) and the game being watched
 * ([ScoreWatch.masked]).
 */
internal fun ScoreSnapshot.withResultWithheld(): ScoreSnapshot = copy(
    clockText = null,
    home = home.copy(score = null, linescores = emptyList(), record = null, form = null),
    away = away.copy(score = null, linescores = emptyList(), record = null, form = null),
    scorers = emptyList(),
    situation = null,
    // Every individual sport (S8): today only a tennis match (#160) is matched to a card, the others may follow.
    individual = when (val result = individual) {
        is TennisMatch -> result.copy(
            players = result.players.map { it.copy(winner = false, sets = emptyList(), setsWon = 0, serving = false) },
            ending = TennisEnding.COMPLETED
        )
        is Fight -> result.copy(fighters = result.fighters.map { it.copy(winner = false) }, method = null, round = null, clock = null)
        is RaceResult -> result.copy(podium = emptyList())
        is GolfResult -> result.copy(leaders = emptyList())
        null -> null
    }
)
