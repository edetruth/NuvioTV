package com.nuvio.tv.core.sportslive.scores

import kotlin.math.abs

/**
 * Where a crunch-time game stands (#137), for the alert's words: [overtime] (overtime, extra time, extra innings,
 * a shootout or penalties), else [minutesLeft] in regulation when the clock gives it; [clock] is ESPN's own text
 * ("0:40 - 2nd Half", "Bot 9th", "88'").
 */
data class CrunchMoment(val overtime: Boolean, val minutesLeft: Int?, val clock: String?)

/**
 * Crunch time (#137): a game close *and* late, by sport, read from the (delayed) snapshot alone. The same
 * detection drives the crunch alert, the "Close game" chip and Live Now's big-game slot.
 * - Basketball: margin ≤ 5 in the last 2:00 of regulation, or any overtime.
 * - Football: margin ≤ 8 with under 5:00 left in the 4th, or overtime.
 * - Hockey: ≤ 1 goal in the last 5:00 of the 3rd, or overtime (a shootout too).
 * - Soccer: level or one goal apart from the 80th minute (extra time and penalties too).
 * - Baseball: the 9th inning or later with the tying run on base or at bat (a tie counts).
 * Anything else, a game not in play or without both scores, is never crunch time.
 */
object CrunchTime {
    const val BASKETBALL_MARGIN = 5
    const val BASKETBALL_SECONDS = 2 * 60
    const val FOOTBALL_MARGIN = 8
    const val FOOTBALL_SECONDS = 5 * 60
    const val HOCKEY_MARGIN = 1
    const val HOCKEY_SECONDS = 5 * 60
    const val SOCCER_MARGIN = 1
    const val SOCCER_MINUTE = 80
    const val BASEBALL_INNING = 9

    /** [event]'s crunch moment, or null when it is not crunch time. */
    fun of(event: ScoreSnapshot): CrunchMoment? {
        if (event.state != ScoreState.IN || event.individual != null) return null
        val home = event.homeScore ?: return null
        val away = event.awayScore ?: return null
        val margin = abs(home - away)
        val text = event.clockText
        return when (event.league.sport) {
            BASKETBALL -> timed(BASKETBALL, text, margin, BASKETBALL_MARGIN, BASKETBALL_SECONDS)
            FOOTBALL -> timed(FOOTBALL, text, margin, FOOTBALL_MARGIN, FOOTBALL_SECONDS, under = true)
            HOCKEY -> timed(HOCKEY, text, margin, HOCKEY_MARGIN, HOCKEY_SECONDS)
            SOCCER -> soccer(text, margin)
            BASEBALL -> baseball(event, home, away)
            else -> null
        }
    }

    fun isCrunch(event: ScoreSnapshot): Boolean = of(event) != null

    /** Any overtime, or the margin within [maxMargin] with [seconds] or less left ([under]: strictly less). */
    private fun timed(sport: String, text: String?, margin: Int, maxMargin: Int, seconds: Int, under: Boolean = false): CrunchMoment? {
        val clock = LiveExcitement.clockOf(sport, text)
        if (clock.overtime) return CrunchMoment(true, null, text)
        val left = clock.secondsLeft ?: return null
        val inWindow = if (under) left < seconds else left <= seconds
        if (!inWindow || margin > maxMargin) return null
        return CrunchMoment(false, ((left + 59) / 60).coerceAtLeast(1), text)
    }

    private fun soccer(text: String?, margin: Int): CrunchMoment? {
        if (margin > SOCCER_MARGIN) return null
        val clock = LiveExcitement.clockOf(SOCCER, text)
        if (clock.overtime) return CrunchMoment(true, null, text)
        val minute = MINUTE.find(text?.trim().orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (minute < SOCCER_MINUTE) return null
        return CrunchMoment(false, (90 - minute).takeIf { it > 0 }, text)
    }

    /**
     * The 9th or later, the side batting trailing by no more than the runners on base plus the batter (the tying
     * run on base or at bat), or level. "Mid" and "End" (between halves) never count: no one is at bat.
     */
    private fun baseball(event: ScoreSnapshot, home: Int, away: Int): CrunchMoment? {
        val text = event.clockText?.trim().orEmpty()
        val match = INNING.find(text) ?: return null
        val inning = match.groupValues[2].toIntOrNull() ?: return null
        if (inning < BASEBALL_INNING) return null
        val half = match.groupValues[1].lowercase()
        val battingHome = when (half) {
            "top" -> false
            "bot", "bottom" -> true
            else -> return null
        }
        val deficit = if (battingHome) away - home else home - away
        val situation = event.situation
        val runners = listOf(situation?.onFirst, situation?.onSecond, situation?.onThird).count { it == true }
        val crunch = deficit == 0 || (deficit > 0 && deficit <= runners + 1)
        return if (crunch) CrunchMoment(inning > BASEBALL_INNING, null, event.clockText) else null
    }

    private const val BASKETBALL = "basketball"
    private const val FOOTBALL = "football"
    private const val HOCKEY = "hockey"
    private const val SOCCER = "soccer"
    private const val BASEBALL = "baseball"
    private val MINUTE = Regex("""^(\d{1,3})'""")
    private val INNING = Regex("""^(Top|Bot|Bottom|Mid|End)\s+(\d+)""", RegexOption.IGNORE_CASE)
}

/** A live game the crunch alerts may raise ([CrunchAlerts]): its card key, ESPN event and big-game score. */
data class CrunchCandidate(val key: String, val event: ScoreSnapshot, val bigScore: Double, val watching: Boolean)

/**
 * The crunch alerts' limits (#137): at most one per game ever, at most one every [GAP_MS] overall (live.24: 15 min), never for the
 * game being watched; when several games are in crunch time at once, the biggest (the big-game score: ranked and
 * big games first) goes. Pure apart from what it remembers; main-thread or serialised use.
 */
class CrunchAlerts(private val gapMs: Long = GAP_MS) {
    private val alerted = LinkedHashSet<String>()
    private var lastAt: Long? = null

    /** The one candidate to alert now, or null; it is remembered as alerted. */
    fun next(nowMs: Long, candidates: List<CrunchCandidate>): CrunchCandidate? {
        if (lastAt?.let { nowMs - it < gapMs } == true) return null
        val pick = candidates.asSequence()
            .filter { !it.watching && it.event.eventId !in alerted && CrunchTime.isCrunch(it.event) }
            .sortedByDescending { it.bigScore }
            .firstOrNull() ?: return null
        if (alerted.size >= MAX_REMEMBERED) alerted.remove(alerted.first())
        alerted += pick.event.eventId
        lastAt = nowMs
        return pick
    }

    /** Debug builds (the emulator check): [candidate] alerted now, past every limit. */
    fun force(nowMs: Long, candidate: CrunchCandidate) {
        alerted += candidate.event.eventId
        lastAt = nowMs
    }

    companion object {
        /** At most one crunch alert this often, whatever the games (live.24: the user found one every 3 min noisy). */
        const val GAP_MS = 15 * 60_000L
        private const val MAX_REMEMBERED = 500
    }
}
