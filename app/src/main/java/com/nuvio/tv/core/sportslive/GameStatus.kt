package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.scores.ScoreChip
import com.nuvio.tv.core.sportslive.scores.ScoreChipStrings
import com.nuvio.tv.core.sportslive.scores.ScoreChips
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.domain.model.SportsLiveItem

/**
 * Where a game stands (#102, F5 in #133): the one answer every Sports & Live view uses (the Scoreboard,
 * the in-player panel, the list cards, the Timeline), so a game is never LIVE in one place and Starting
 * soon in another. Built by [GameStatus.of]; worded by [GameStatusText].
 */
sealed interface GameStatus {

    /** In play: ESPN says so, or (no strict ESPN match) its addon lists it live and its start has passed. */
    data class Live(val event: ScoreSnapshot?) : GameStatus

    /**
     * Starts within [SOON_WINDOW_MS] (a timed start; ESPN's when matched). [startsIn] is (hours, minutes)
     * to it, rounded up; null once the start has passed (a late start still waiting).
     */
    data class StartingSoon(val startMs: Long, val startsIn: Pair<Int, Int>?) : GameStatus

    /** Later: a timed start beyond the soon window, or a date with no time ([hasTimeOfDay] false), or none. */
    data class Upcoming(val startMs: Long?, val hasTimeOfDay: Boolean) : GameStatus

    /** Moved to Just ended (#60): an ESPN final ([EndedGame.event]) or an estimate ("Probably ended"). */
    data class JustEnded(val ended: EndedGame) : GameStatus {
        val estimate: Boolean get() = ended.signal == EndedSignal.ESTIMATE
    }

    /** ESPN says it is over (its strict match), not (yet) in the Just ended set. Never a channel. */
    data class Final(val event: ScoreSnapshot) : GameStatus

    /** Postponed, cancelled, suspended or delayed (ESPN's [text]). */
    data class Postponed(val text: String?) : GameStatus

    /** A 24/7 channel with no game ESPN places. */
    data object Channel : GameStatus

    companion object {
        /** How far ahead a game counts as starting soon. */
        const val SOON_WINDOW_MS = 3 * 60 * 60 * 1000L

        /**
         * [item]'s status at [nowMs]. [snapshot] is its tracked ESPN game (null under Scores Off: then the
         * addon and the clock decide); only a strict match with the card's sides on it one way round counts
         * ([ScoreChips.matchedEvent]). [ended] is its Just ended verdict, which wins. [listedLive]: its list
         * says it is live (Live now's games; by default its addon's own flag), which decides an unmatched game.
         */
        fun of(
            item: SportsLiveItem,
            snapshot: ScoreSnapshot?,
            ended: EndedGame?,
            nowMs: Long,
            listedLive: Boolean = item.isLive
        ): GameStatus {
            if (ended != null) return JustEnded(ended)
            val event = ScoreChips.matchedEvent(item, snapshot)
            val start = event?.takeIf { it.timeValid }?.startMs ?: item.startMs?.takeIf { item.startHasTimeOfDay }
            fun notStarted(): GameStatus = when {
                start != null && start <= nowMs + SOON_WINDOW_MS -> StartingSoon(start, startsIn(start, nowMs))
                start != null -> Upcoming(start, hasTimeOfDay = true)
                else -> Upcoming(item.startMs, hasTimeOfDay = false)
            }
            return when (event?.state) {
                ScoreState.IN -> Live(event)
                ScoreState.PRE -> notStarted()
                ScoreState.POST -> if (item.isChannel) Channel else Final(event)
                ScoreState.OFF -> Postponed(event.clockText?.takeIf { it.isNotBlank() })
                ScoreState.UNKNOWN, null -> when {
                    item.isChannel -> Channel
                    start != null && start > nowMs -> notStarted()
                    // Unmatched: the list decides (Live now lists live games, Upcoming the rest).
                    listedLive -> Live(null)
                    else -> notStarted()
                }
            }
        }

        /** (hours, minutes) to [startMs], rounded up to the minute; null once it has passed. */
        fun startsIn(startMs: Long, nowMs: Long): Pair<Int, Int>? {
            val left = startMs - nowMs
            if (left <= 0) return null
            val minutes = ((left + 59_999) / 60_000).toInt()
            return minutes / 60 to minutes % 60
        }
    }
}

/** The status line's words, from string resources (tests pass the English ones). */
data class GameStatusStrings(
    /** "● %1$s": a live clock. */
    val liveClock: String,
    /** "LIVE": live with no clock from ESPN. */
    val live: String,
    /** "%1$s · %2$s": clock, period. */
    val clockPeriod: String,
    /** "%1$s · %2$s": start time, countdown. */
    val timeCountdown: String,
    /** "in %1$d h %2$d m" */
    val inHoursMinutes: String,
    /** "in %1$d h" */
    val inHours: String,
    /** "in %1$d min" */
    val inMinutes: String,
    /** "Final" (US sports). */
    val final: String,
    /** "FT" (soccer, rugby). */
    val fullTime: String,
    val probablyEnded: String,
    val postponed: String,
    val chip: ScoreChipStrings
)

/**
 * The one status formatter (#102): "● 9:51 · 2nd half", "3:30 PM · in 2 h 21 m", "FT 2–1" / "Final"
 * per sport. Spoilers stay with the [ScoreChip] (Hide until focused gives "Score hidden").
 */
object GameStatusText {

    /**
     * [status] as one line. [chip] is the card's score chip (spoiler setting applied), [timeOf] words a
     * start ("3:30 PM", "Tomorrow 3:30 PM", "Sat 21 Nov"), [sport] is ESPN's sport slug when matched.
     */
    fun of(
        status: GameStatus,
        chip: ScoreChip?,
        strings: GameStatusStrings,
        timeOf: (startMs: Long, hasTimeOfDay: Boolean) -> String,
        sport: String? = null
    ): String = when (status) {
        is GameStatus.Live -> when (chip) {
            is ScoreChip.Hidden -> strings.chip.hidden
            is ScoreChip.Live -> chip.clock?.let { String.format(strings.liveClock, liveClock(it, strings)) } ?: strings.live
            else -> strings.live
        }
        is GameStatus.StartingSoon -> withCountdown(timeOf(status.startMs, true), status.startsIn, strings)
        is GameStatus.Upcoming -> when (val start = status.startMs) {
            null -> ""
            else -> timeOf(start, status.hasTimeOfDay)
        }
        is GameStatus.JustEnded -> if (status.estimate) strings.probablyEnded else finalText(chip, strings, sport ?: status.ended.event?.league?.sport)
        is GameStatus.Final -> finalText(chip, strings, sport ?: status.event.league.sport)
        is GameStatus.Postponed -> status.text ?: strings.postponed
        GameStatus.Channel -> ""
    }

    /** "9:51 - 2nd Half" (ESPN) → "9:51 · 2nd half"; "Q3 4:12" and "67'" stay as they are. */
    fun liveClock(clockText: String, strings: GameStatusStrings): String {
        val parts = clockText.split(" - ", limit = 2).map { it.trim() }
        if (parts.size < 2 || parts.any { it.isEmpty() }) return clockText.trim()
        val period = parts[1].let { if (it.first().isDigit()) it.lowercase() else it }
        return String.format(strings.clockPeriod, parts[0], period)
    }

    /** "in 12 min", "in 2 h", "in 2 h 21 m"; null when [startsIn] is. */
    fun countdown(startsIn: Pair<Int, Int>?, strings: GameStatusStrings): String? {
        val (hours, minutes) = startsIn ?: return null
        return when {
            hours == 0 -> String.format(strings.inMinutes, minutes)
            minutes == 0 -> String.format(strings.inHours, hours)
            else -> String.format(strings.inHoursMinutes, hours, minutes)
        }
    }

    /** ESPN's sport slug behind [status] ("soccer", "basketball"), when an ESPN game is known. */
    fun sportOf(status: GameStatus): String? = when (status) {
        is GameStatus.Live -> status.event?.league?.sport
        is GameStatus.Final -> status.event.league.sport
        is GameStatus.JustEnded -> status.ended.event?.league?.sport
        else -> null
    }

    /** Soccer and rugby end at "FT"; the US sports (and the rest) at "Final". */
    fun finalWord(sport: String?, strings: GameStatusStrings): String =
        if (endsAtFullTime(sport)) strings.fullTime else strings.final

    /** ESPN's sport slug for a sport whose games end at full time. */
    fun endsAtFullTime(sport: String?): Boolean = sport?.lowercase()?.let { it == "soccer" || it == "rugby" || it == "rugby-league" } == true

    private fun withCountdown(time: String, startsIn: Pair<Int, Int>?, strings: GameStatusStrings): String =
        countdown(startsIn, strings)?.let { String.format(strings.timeCountdown, time, it) } ?: time

    private fun finalText(chip: ScoreChip?, strings: GameStatusStrings, sport: String?): String = when (chip) {
        // ESPN's own note ("FT", "Final/OT") when it gave one, else the sport's word.
        is ScoreChip.Final -> ScoreChips.text(chip, strings.chip.copy(finalNote = finalWord(sport, strings)))
        is ScoreChip.Hidden -> strings.chip.hidden
        else -> finalWord(sport, strings)
    }
}
