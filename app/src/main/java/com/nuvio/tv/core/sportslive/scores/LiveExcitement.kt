package com.nuvio.tv.core.sportslive.scores

import kotlin.math.abs
import kotlin.math.sign

/** Why a game ranks high in Live now's Most exciting sort (#43): one short tag per card. */
enum class WhyKind { PENALTIES, SHOOTOUT, OVERTIME, EXTRA_TIME, EXTRA_INNINGS, CLOSE_LATE, LEAD_CHANGE, JUST_SCORED, RED_ZONE, BASES_LOADED, SCORING_POSITION, CLOSE }

/** A card's why tag: [minutesLeft] goes with [WhyKind.CLOSE_LATE] ("Close · 2 min left"); null there reads "Close · late". */
data class WhyTag(val kind: WhyKind, val minutesLeft: Int? = null)

/** A live game's excitement: higher ranks first; [tag] is its top reason, null when it has none. */
data class ExcitementRating(val score: Double, val tag: WhyTag?)

/** What changed in a game lately, from successive snapshots ([ExcitementHistory]). */
data class RecentChange(val leadChange: Boolean = false, val scored: Boolean = false) {
    companion object {
        val NONE = RecentChange()
    }
}

/**
 * The Most exciting sort's scorer (#43). Pure: it reads only the (already delayed) snapshot and what
 * [ExcitementHistory] saw change, never a request. Closeness counts more the later the game is; final
 * minutes, overtime, penalties, a recent lead change or score and the situation (red zone, runners in
 * scoring position late) add to it. Only team sports with a known margin scale are rated; anything
 * else, or a game with no score, is null and keeps its start-time place.
 */
object LiveExcitement {

    /** [event]'s rating when it is in play with both scores and its sport is rated; else null. */
    fun rate(event: ScoreSnapshot, recent: RecentChange = RecentChange.NONE): ExcitementRating? {
        if (event.state != ScoreState.IN || event.individual != null) return null
        val home = event.homeScore ?: return null
        val away = event.awayScore ?: return null
        val sport = event.league.sport
        val closeWithin = CLOSE_WITHIN[sport] ?: return null
        val clock = clockOf(sport, event.clockText)
        val margin = abs(home - away)
        val close = margin <= closeWithin
        // A tie is 1, the edge of "close" a little above 0, anything wider 0.
        val closeness = (closeWithin + 1 - margin).coerceAtLeast(0).toDouble() / (closeWithin + 1)
        val progress = clock.progress ?: 0.5
        val situation = event.situation
        val redZone = sport == FOOTBALL && situation?.redZone == true
        val lateInnings = sport == BASEBALL && clock.late
        val basesLoaded = lateInnings && situation != null && situation.onFirst && situation.onSecond && situation.onThird
        val scoringPosition = lateInnings && close && situation != null && (situation.onSecond || situation.onThird)

        var score = closeness * (1 + 2 * progress)
        when {
            clock.penalties -> score += 6
            clock.overtime -> score += 4
        }
        if (close && clock.late) score += 3
        if (recent.leadChange) score += 2 else if (recent.scored) score += 1
        if (redZone) score += 1
        if (basesLoaded) score += 1.5 else if (scoringPosition) score += 0.75

        val tag = when {
            clock.penalties -> WhyTag(if (sport == HOCKEY) WhyKind.SHOOTOUT else WhyKind.PENALTIES)
            clock.overtime -> WhyTag(
                when (sport) {
                    SOCCER -> WhyKind.EXTRA_TIME
                    BASEBALL -> WhyKind.EXTRA_INNINGS
                    else -> WhyKind.OVERTIME
                }
            )
            // Late in baseball, the runners say more than "Close · late".
            basesLoaded -> WhyTag(WhyKind.BASES_LOADED)
            scoringPosition -> WhyTag(WhyKind.SCORING_POSITION)
            close && clock.late -> WhyTag(WhyKind.CLOSE_LATE, clock.minutesLeft?.coerceAtLeast(1))
            recent.leadChange -> WhyTag(WhyKind.LEAD_CHANGE)
            recent.scored -> WhyTag(WhyKind.JUST_SCORED)
            redZone -> WhyTag(WhyKind.RED_ZONE)
            // Only with a clock that says the game is past halfway.
            close && (clock.progress ?: 0.0) >= 0.5 -> WhyTag(WhyKind.CLOSE)
            else -> null
        }
        return ExcitementRating(score, tag)
    }

    /**
     * Where a game stands by its ESPN clock text: [progress] through regulation (0..1, null when the
     * text says nothing readable), [minutesLeft] in the final period, overtime or extra time, [late]
     * for the final minutes or innings.
     */
    internal data class Clock(
        val progress: Double? = null,
        val minutesLeft: Int? = null,
        val late: Boolean = false,
        val overtime: Boolean = false,
        val penalties: Boolean = false,
        /** Seconds left in the final period or overtime, when the clock says (#137's crunch time). */
        val secondsLeft: Int? = null,
        /** The period the clock names (a soccer minute, a baseball inning are not periods). */
        val period: Int? = null
    )

    internal fun clockOf(sport: String, clockText: String?): Clock {
        val text = clockText?.trim().orEmpty()
        if (text.isEmpty()) return Clock()
        return when (sport) {
            SOCCER -> soccer(text)
            BASEBALL -> baseball(text)
            else -> PERIODS[sport]?.let { timed(text, it) } ?: Clock()
        }
    }

    /** "8:42 - 3rd", "Q4 2:00", "45.2 - 4th" (under a minute), "2:00 - OT", "12:34 - 2nd Half", "Halftime", "End of 3rd", "SO". */
    private fun timed(text: String, periods: Periods): Clock {
        if (SHOOTOUT.containsMatchIn(text)) return Clock(progress = 1.0, late = true, overtime = true, penalties = true)
        val left = CLOCK.find(text)?.let { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }
            ?: SECONDS.find(text)?.groupValues?.get(1)?.toInt()
        val minutesLeft = left?.let { (it + 59) / 60 }
        if (OVERTIME.containsMatchIn(text)) return Clock(progress = 1.0, minutesLeft = minutesLeft, late = true, overtime = true, secondsLeft = left)
        if (HALFTIME.containsMatchIn(text)) return Clock(progress = 0.5)
        val halves = text.contains("half", ignoreCase = true)
        val count = if (halves) 2 else periods.count
        val length = if (halves) periods.halfMinutes else periods.minutes
        val period = (QUARTER.find(text) ?: ORDINAL.find(text))?.groupValues?.get(1)?.toInt()?.takeIf { it in 1..count }
            ?: return Clock()
        if (text.startsWith("End", ignoreCase = true)) {
            return Clock(progress = period.toDouble() / count)
        }
        left ?: return Clock(progress = (period - 0.5) / count)
        val inPeriod = (1 - left / (length * 60.0)).coerceIn(0.0, 1.0)
        val final = period == count
        return Clock(
            progress = (period - 1 + inPeriod) / count,
            minutesLeft = minutesLeft.takeIf { final },
            late = final && left <= LATE_SECONDS,
            secondsLeft = left.takeIf { final },
            period = period
        )
    }

    /** "67'", "45'+2'", "90'+4'", "HT", "105'", "ET", "Pens". */
    private fun soccer(text: String): Clock {
        if (PENS.containsMatchIn(text)) return Clock(progress = 1.0, late = true, overtime = true, penalties = true)
        if (text.equals("HT", ignoreCase = true)) return Clock(progress = 0.5)
        // Stoppage time keeps the minute it was added to: "90'+4'" is 90.
        val minute = MINUTE.find(text)?.groupValues?.get(1)?.toInt()
        if ((minute != null && minute > 90) || EXTRA_TIME.containsMatchIn(text)) {
            return Clock(progress = 1.0, minutesLeft = minute?.let { (120 - it).coerceAtLeast(0) }, late = true, overtime = true)
        }
        minute ?: return Clock()
        // From the 90th minute on (stoppage time) there are no whole minutes left to count: "Close · late".
        return Clock(progress = minute / 90.0, minutesLeft = (90 - minute).takeIf { it > 0 }, late = minute >= SOCCER_LATE_MINUTE)
    }

    /** "Top 9th", "Bot 7th", "Mid 5th", "End 8th"; the 10th on is extra innings. */
    private fun baseball(text: String): Clock {
        val match = INNING.find(text) ?: return Clock()
        val inning = match.groupValues[2].toInt()
        val half = when (match.groupValues[1].lowercase()) {
            "top" -> 0.0
            "end" -> 1.0
            else -> 0.5
        }
        return Clock(
            progress = ((inning - 1 + half) / 9).coerceIn(0.0, 1.0),
            late = inning >= LATE_INNING,
            overtime = inning > 9
        )
    }

    private data class Periods(val count: Int, val minutes: Int, val halfMinutes: Int)

    const val BASKETBALL = "basketball"
    const val FOOTBALL = "football"
    const val SOCCER = "soccer"
    const val HOCKEY = "hockey"
    const val BASEBALL = "baseball"

    /** "Close" by sport: points, goals or runs. */
    internal val CLOSE_WITHIN = mapOf(BASKETBALL to 5, FOOTBALL to 8, SOCCER to 1, HOCKEY to 1, BASEBALL to 2)

    private val PERIODS = mapOf(
        BASKETBALL to Periods(count = 4, minutes = 12, halfMinutes = 20),
        FOOTBALL to Periods(count = 4, minutes = 15, halfMinutes = 30),
        HOCKEY to Periods(count = 3, minutes = 20, halfMinutes = 20)
    )

    /** The final five minutes. */
    private const val LATE_SECONDS = 5 * 60
    private const val LATE_INNING = 8
    private const val SOCCER_LATE_MINUTE = 80

    private val CLOCK = Regex("""(\d{1,2}):(\d{2})""")
    // Under a minute ESPN shows seconds and tenths: "45.2 - 4th", "0.0 - 3rd".
    private val SECONDS = Regex("""(?<![\d:.])(\d{1,2})\.\d(?!\d)""")
    private val QUARTER = Regex("""\bQ(\d)\b""", RegexOption.IGNORE_CASE)
    private val ORDINAL = Regex("""\b(\d)(?:st|nd|rd|th)\b""", RegexOption.IGNORE_CASE)
    private val OVERTIME = Regex("""\b\d*OT\b""", RegexOption.IGNORE_CASE)
    private val SHOOTOUT = Regex("""\bSO\b|shootout""", RegexOption.IGNORE_CASE)
    private val HALFTIME = Regex("""^half""", RegexOption.IGNORE_CASE)
    private val PENS = Regex("""\bpen""", RegexOption.IGNORE_CASE)
    private val EXTRA_TIME = Regex("""\bET\b|extra time""", RegexOption.IGNORE_CASE)
    private val MINUTE = Regex("""^(\d{1,3})'""")
    private val INNING = Regex("""^(Top|Bot|Mid|End)\s+(\d+)""", RegexOption.IGNORE_CASE)
}

/**
 * What changed in each live game lately (#43), from the successive delayed snapshots this screen saw:
 * a score (the total went up) and a lead change (the leader flipped, a tie in between too), each
 * remembered for [windowMs]. In memory only; the first sight of a game records nothing, and games that
 * finish or leave the feed are forgotten (a snapshot that briefly lacks a score is not).
 */
class ExcitementHistory(private val windowMs: Long = RECENT_WINDOW_MS) {

    private class Track(var total: Int, var leader: Int, var scoredAtMs: Long? = null, var leadChangedAtMs: Long? = null)

    private val tracks = HashMap<String, Track>()

    @Synchronized
    fun observe(events: Collection<ScoreSnapshot>, nowMs: Long) {
        val seen = HashSet<String>()
        for (event in events) {
            if (event.state == ScoreState.POST || event.state == ScoreState.OFF) continue
            // Still listed: a snapshot that briefly lacks a score (or state) keeps what was seen.
            seen += event.eventId
            if (event.state != ScoreState.IN) continue
            val home = event.homeScore ?: continue
            val away = event.awayScore ?: continue
            val lead = (home - away).sign
            val track = tracks[event.eventId]
            if (track == null) {
                tracks[event.eventId] = Track(home + away, lead)
                continue
            }
            if (home + away > track.total) track.scoredAtMs = nowMs
            if (lead != 0 && track.leader != 0 && lead != track.leader) track.leadChangedAtMs = nowMs
            if (lead != 0) track.leader = lead
            track.total = home + away
        }
        tracks.keys.retainAll(seen)
    }

    @Synchronized
    fun recent(eventId: String, nowMs: Long): RecentChange {
        val track = tracks[eventId] ?: return RecentChange.NONE
        fun within(atMs: Long?) = atMs != null && nowMs - atMs <= windowMs
        return RecentChange(leadChange = within(track.leadChangedAtMs), scored = within(track.scoredAtMs))
    }

    companion object {
        const val RECENT_WINDOW_MS = 5 * 60_000L
    }
}
