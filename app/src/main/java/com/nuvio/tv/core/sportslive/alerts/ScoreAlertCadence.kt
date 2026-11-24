package com.nuvio.tv.core.sportslive.alerts

import com.nuvio.tv.core.sportslive.scores.CrunchTime
import com.nuvio.tv.core.sportslive.scores.LiveExcitement
import com.nuvio.tv.core.sportslive.scores.ScoreSide
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import kotlin.math.abs
import kotlin.math.sign

/**
 * Settings → Alerts → Score alerts (live.28, user decision 2026-11-23): one level for every followed team. Big moments
 * is the default (research-alert-cadence-20261123-2126-gemini.md: no leading app pings every basket).
 */
enum class ScoreAlertLevel {
    OFF, BIG_MOMENTS, EVERY_SCORE;

    companion object {
        val DEFAULT = BIG_MOMENTS

        /** The stored level; with none stored, the On / Off switch before live.28 ([legacyOn] false reads Off). */
        fun fromStored(stored: String?, legacyOn: Boolean?): ScoreAlertLevel =
            entries.firstOrNull { it.name == stored } ?: if (legacyOn == false) OFF else DEFAULT
    }
}

/** What a score card is about (live.28). */
enum class ScoreMoment {
    /** A score: a goal, a score late in a close game, or any score under Every score. */
    SCORE,
    LEAD_CHANGE,
    TIED,

    /** Halftime or the end of the 3rd (basketball, football): the clock line says which. */
    PERIOD_BREAK,

    /** An overtime started (basketball, football). */
    OVERTIME,

    /** A big lead cut to a close game late (basketball, football). */
    COMEBACK
}

/** A score card due: what it is about, and the side that scored (null: both, or none as at a break). */
data class ScoreCall(val moment: ScoreMoment, val scorer: ScoreSide?)

/**
 * When a followed team's game is worth a score card (live.28 item a), from the successive delayed snapshots alone.
 * In memory, by ESPN event id; a first look never alerts. Main-thread or otherwise serialised use only.
 *
 * Big moments, by sport:
 * - Soccer, hockey: every goal.
 * - Basketball, football: early only halftime and the end of the 3rd; a lead change in the last 10 minutes or in
 *   overtime; an overtime starting; and, late and close (the last 5 minutes, within two possessions, or overtime) =
 *   crunch mode: a tie, and any score in the last 2 minutes; a big lead cut to a close game (comeback), once.
 * - Other team sports: a lead change or a tie late, any score in crunch time ([CrunchTime]).
 * Every score: all of that, plus any other score, at most one card per game per [EVERY_SCORE_GAP_MS].
 *
 * Bundling: a moment inside its game's gap waits; when the gap is over the next snapshot gives one card, measured
 * from the last card (its score, a lead change or tie since then), never a stale label.
 */
class ScoreAlertCadence {
    private class Track(
        var home: Int,
        var away: Int,
        /** The last side that led (sign of home − away), 0 when nobody has led yet: a tie in between is no reset. */
        var leader: Int,
        var maxHomeLead: Int,
        var maxAwayLead: Int,
        var breaks: Int,
        var overtimes: Int,
        var comebackDone: Boolean = false,
        var cardAt: Long? = null,
        var cardHome: Int = 0,
        var cardAway: Int = 0,
        var cardLeader: Int = 0,
        /** A moment waited inside the gap: the gap it waits for. */
        var pendingGapMs: Long? = null
    )

    private val tracks = HashMap<String, Track>()

    /** The game is on screen (or its score is blanked): what it did meanwhile is never announced afterwards. */
    fun forget(eventId: String) {
        tracks.remove(eventId)
    }

    /**
     * [event] seen at [nowMs]: the card due, or null. [allowed]: a card may show now (a game plays elsewhere, Scores =
     * Show, not spoiler-protected); when it may not, what happened is remembered but never announced later.
     */
    fun observe(event: ScoreSnapshot, nowMs: Long, level: ScoreAlertLevel, allowed: Boolean): ScoreCall? {
        if (event.state != ScoreState.IN || event.individual != null) {
            tracks.remove(event.eventId)
            return null
        }
        val home = event.homeScore ?: return null
        val away = event.awayScore ?: return null
        val sport = event.league.sport
        val clock = LiveExcitement.clockOf(sport, event.clockText)
        val breaks = breaksOf(sport, event.clockText, clock.overtime)
        val overtimes = if (clock.overtime) overtimeNumber(event.clockText) else 0
        val lead = (home - away).sign
        val t = tracks[event.eventId]
        if (t == null) {
            if (tracks.size >= MAX_TRACKED) tracks.clear()
            tracks[event.eventId] = Track(home, away, lead, maxOf(0, home - away), maxOf(0, away - home), breaks, overtimes)
            return null
        }
        val corrected = home < t.home || away < t.away
        val scored = !corrected && (home > t.home || away > t.away)
        val scorer = sideOf(home - t.home, away - t.away)
        val leadChange = scored && lead != 0 && t.leader != 0 && lead != t.leader
        val tied = scored && lead == 0 && t.home != t.away
        val newBreak = breaks > t.breaks
        val newOvertime = overtimes > t.overtimes
        val comeback = scored && !t.comebackDone && comebackNow(sport, clock, home, away, t)
        t.home = home
        t.away = away
        if (lead != 0) t.leader = lead
        t.maxHomeLead = maxOf(t.maxHomeLead, home - away)
        t.maxAwayLead = maxOf(t.maxAwayLead, away - home)
        t.breaks = maxOf(t.breaks, breaks)
        t.overtimes = maxOf(t.overtimes, overtimes)
        if (comeback) t.comebackDone = true
        if (corrected || !allowed || level == ScoreAlertLevel.OFF) {
            t.pendingGapMs = null
            return null
        }
        val margin = abs(home - away)
        val everyScore: Pair<ScoreMoment, Long>? = if (level == ScoreAlertLevel.EVERY_SCORE && scored) {
            val moment = when {
                leadChange -> ScoreMoment.LEAD_CHANGE
                tied -> ScoreMoment.TIED
                else -> ScoreMoment.SCORE
            }
            moment to EVERY_SCORE_GAP_MS
        } else {
            null
        }
        val due = bigMoment(sport, clock, event, margin, scored, leadChange, tied, newBreak, newOvertime, comeback) ?: everyScore
        val since = t.cardAt?.let { nowMs - it }
        if (due != null) {
            val (moment, gap) = due
            if (since == null || since >= gap) {
                val who = if (moment == ScoreMoment.PERIOD_BREAK || moment == ScoreMoment.OVERTIME) null else scorer
                return card(t, nowMs, ScoreCall(moment, who))
            }
            t.pendingGapMs = minOf(t.pendingGapMs ?: gap, gap)
            return null
        }
        val pending = t.pendingGapMs ?: return null
        if (since != null && since < pending) return null
        t.pendingGapMs = null
        // Bundled: one card for what happened since the last one, labelled from it.
        if (home == t.cardHome && away == t.cardAway) return null
        val cardLead = (t.cardHome - t.cardAway).sign
        val moment = when {
            // A goal is the news in soccer and hockey: the card names who scored, as an unbundled one does.
            sport == LiveExcitement.SOCCER || sport == LiveExcitement.HOCKEY -> ScoreMoment.SCORE
            lead != 0 && t.cardLeader != 0 && lead != t.cardLeader -> ScoreMoment.LEAD_CHANGE
            lead == 0 && cardLead != 0 -> ScoreMoment.TIED
            else -> ScoreMoment.SCORE
        }
        if (moment == ScoreMoment.SCORE && level != ScoreAlertLevel.EVERY_SCORE && !plainScoreCounts(sport, clock, event, margin)) return null
        return card(t, nowMs, ScoreCall(moment, sideOf(home - t.cardHome, away - t.cardAway)))
    }

    private fun card(t: Track, nowMs: Long, call: ScoreCall): ScoreCall {
        t.cardAt = nowMs
        t.cardHome = t.home
        t.cardAway = t.away
        t.cardLeader = t.leader
        t.pendingGapMs = null
        return call
    }

    /** The Big moments rules: the moment due now with the gap it keeps from the game's last card, or null. */
    private fun bigMoment(
        sport: String,
        clock: LiveExcitement.Clock,
        event: ScoreSnapshot,
        margin: Int,
        scored: Boolean,
        leadChange: Boolean,
        tied: Boolean,
        newBreak: Boolean,
        newOvertime: Boolean,
        comeback: Boolean
    ): Pair<ScoreMoment, Long>? = when (sport) {
        LiveExcitement.SOCCER, LiveExcitement.HOCKEY -> if (scored) ScoreMoment.SCORE to GOAL_GAP_MS else null
        LiveExcitement.BASKETBALL, LiveExcitement.FOOTBALL -> {
            val left = clock.secondsLeft
            val crunch = (clock.overtime || (left != null && left <= CRUNCH_SECONDS)) && margin <= twoPossessions(sport)
            when {
                newOvertime -> ScoreMoment.OVERTIME to 0L
                comeback -> ScoreMoment.COMEBACK to 0L
                leadChange && (clock.overtime || (left != null && left <= LEAD_CHANGE_SECONDS)) -> ScoreMoment.LEAD_CHANGE to CRUNCH_GAP_MS
                crunch && tied -> ScoreMoment.TIED to CRUNCH_GAP_MS
                crunch && scored && left != null && left <= FINAL_SECONDS -> ScoreMoment.SCORE to CRUNCH_GAP_MS
                newBreak -> ScoreMoment.PERIOD_BREAK to 0L
                else -> null
            }
        }
        else -> {
            val late = clock.late || clock.overtime
            when {
                leadChange && late -> ScoreMoment.LEAD_CHANGE to CRUNCH_GAP_MS
                tied && late -> ScoreMoment.TIED to CRUNCH_GAP_MS
                scored && CrunchTime.isCrunch(event) -> ScoreMoment.SCORE to CRUNCH_GAP_MS
                else -> null
            }
        }
    }

    /** Whether a plain score (no lead change, no tie) is news now under Big moments: a bundled one needs this. */
    private fun plainScoreCounts(sport: String, clock: LiveExcitement.Clock, event: ScoreSnapshot, margin: Int): Boolean = when (sport) {
        LiveExcitement.SOCCER, LiveExcitement.HOCKEY -> true
        LiveExcitement.BASKETBALL, LiveExcitement.FOOTBALL -> {
            val left = clock.secondsLeft
            left != null && left <= FINAL_SECONDS && margin <= twoPossessions(sport)
        }
        else -> CrunchTime.isCrunch(event)
    }

    /** A side that led by [BIG_LEAD] or more now leads by [COMEBACK_WITHIN] or less, in the final period or overtime. */
    private fun comebackNow(sport: String, clock: LiveExcitement.Clock, home: Int, away: Int, t: Track): Boolean {
        val big = BIG_LEAD[sport] ?: return false
        val within = COMEBACK_WITHIN[sport] ?: return false
        if (!clock.overtime && clock.secondsLeft == null) return false
        return (t.maxHomeLead >= big && home - away <= within) || (t.maxAwayLead >= big && away - home <= within)
    }

    companion object {
        /** Every score: at most one card per game this often (decision 4, as before live.28). */
        const val EVERY_SCORE_GAP_MS = 2 * 60_000L

        /** Goals: short, so a quick equaliser is never lost; it still debounces a feed's flicker. */
        const val GOAL_GAP_MS = 30_000L

        /** Lead changes, ties and late scores: at most one card a minute per game (fouls stretch the last 2 minutes). */
        const val CRUNCH_GAP_MS = 60_000L

        /** Late and close: the last 5 minutes (NBA "clutch time") within two possessions. */
        const val CRUNCH_SECONDS = 5 * 60

        /** In crunch mode, any score in the last 2 minutes. */
        const val FINAL_SECONDS = 2 * 60

        /** A lead change counts in the last 10 minutes of the final period (or overtime). */
        const val LEAD_CHANGE_SECONDS = 10 * 60

        /** Two possessions: 6 points in basketball, 16 in football. */
        fun twoPossessions(sport: String): Int = if (sport == LiveExcitement.FOOTBALL) 16 else 6

        private val BIG_LEAD = mapOf(LiveExcitement.BASKETBALL to 15, LiveExcitement.FOOTBALL to 14)
        private val COMEBACK_WITHIN = mapOf(LiveExcitement.BASKETBALL to 5, LiveExcitement.FOOTBALL to 7)

        private const val MAX_TRACKED = 500

        private fun sideOf(homeGain: Int, awayGain: Int): ScoreSide? = when {
            homeGain > 0 && awayGain <= 0 -> ScoreSide.HOME
            awayGain > 0 && homeGain <= 0 -> ScoreSide.AWAY
            else -> null
        }

        /**
         * The breaks a basketball or football game has reached by its clock text: 1 from halftime on, 2 from the end of
         * the 3rd on (four quarters). Other sports have none here. Seen as the text says ("Halftime", "End of 3rd") or
         * by the period after it, so a poll that misses the break still counts it.
         */
        internal fun breaksOf(sport: String, clockText: String?, overtime: Boolean): Int {
            if (sport != LiveExcitement.BASKETBALL && sport != LiveExcitement.FOOTBALL) return 0
            val text = clockText?.trim().orEmpty()
            if (text.isEmpty()) return 0
            val halves = text.contains("half", ignoreCase = true) && !HALFTIME.containsMatchIn(text)
            if (overtime) return if (halves) 1 else 2
            if (HALFTIME.containsMatchIn(text)) return 1
            val period = PERIOD.find(text)?.let { match -> match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.toIntOrNull() } ?: return 0
            val ended = text.startsWith("End", ignoreCase = true)
            return if (halves) {
                if (period >= 2 || (ended && period == 1)) 1 else 0
            } else {
                when {
                    period >= 4 || (ended && period == 3) -> 2
                    period == 3 || (ended && period == 2) -> 1
                    else -> 0
                }
            }
        }

        /** "OT" is 1, "2OT" 2; a shootout or an unnumbered overtime is 1. */
        internal fun overtimeNumber(clockText: String?): Int =
            OT_NUMBER.find(clockText.orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: 1

        private val HALFTIME = Regex("""^half""", RegexOption.IGNORE_CASE)
        private val PERIOD = Regex("""\bQ(\d)\b|\b(\d)(?:st|nd|rd|th)\b""", RegexOption.IGNORE_CASE)
        private val OT_NUMBER = Regex("""\b(\d+)OT\b""", RegexOption.IGNORE_CASE)
    }
}
