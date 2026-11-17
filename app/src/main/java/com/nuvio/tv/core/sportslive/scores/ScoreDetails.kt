package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.domain.model.SportsLiveItem

/** A linescore column's heading: a regulation period's number, an overtime, or soccer's extra time. */
sealed interface PeriodLabel {
    /** "1", "2", … (quarters, halves, periods, innings). */
    data class Number(val n: Int) : PeriodLabel

    /** The [n]th overtime: "OT", then "2OT", "3OT". */
    data class Overtime(val n: Int) : PeriodLabel

    /** Soccer's [n]th extra-time period: "ET1", "ET2". */
    data class ExtraTime(val n: Int) : PeriodLabel
}

/** One side of the details pane's score, in the card's team order. */
data class ScoreDetailsSide(
    /** ESPN's abbreviation ("ARS"), else its short name, else the card's own name. */
    val short: String,
    val score: Int?,
    /** Points per period, padded with null where the side has not played it yet (bottom of an inning). */
    val linescores: List<Int?>,
    /** Soccer goal scorers credited to this side, in order. */
    val scorers: List<ScoreScorer>
)

/** What the details pane shows of a matched game's score; no block at all is null. */
sealed interface ScoreDetails {
    /** Hide until focused, before the card's reveal: "Score hidden". */
    data class Hidden(val live: Boolean) : ScoreDetails

    /** Postponed, cancelled, suspended or delayed: the status only, never a score. */
    data class Status(val text: String?) : ScoreDetails

    /**
     * The scoreline ([first] and [second] in the card's order), ESPN's clock or final note ([status];
     * null on a final means "Final"), the linescore headings ([periods], empty when there is no row)
     * and [soccer] (scorers are shown only for soccer).
     */
    data class Shown(
        val first: ScoreDetailsSide,
        val second: ScoreDetailsSide,
        val status: String?,
        val live: Boolean,
        val periods: List<PeriodLabel>,
        val soccer: Boolean
    ) : ScoreDetails
}

/** The details block's words, from string resources (tests pass the English ones). */
data class ScoreDetailsStrings(
    /** "%1$s %2$d–%3$d %4$s": first short name, first score, second score, second short name. */
    val scoreline: String,
    /** "%1$s – %2$s" (no score yet). */
    val sides: String,
    val overtime: String,
    /** "%1$dOT" */
    val overtimeN: String,
    /** "ET%1$d" */
    val extraTime: String,
    /** "%1$s %2$s": scorer name, minutes. */
    val scorer: String,
    /** "%1$s (pen)" */
    val penalty: String,
    /** "%1$s (OG)" */
    val ownGoal: String,
    /** ", " between a scorer's minutes and between scorers. */
    val separator: String
)

/**
 * The score block of the Sports & Live details pane: a scoreline with short team names and the clock or
 * state, a linescore row and, for soccer, the goal scorers. All of it comes from the scoreboard snapshot
 * the card already has (no extra request), and only when [ScoreChips] would show a chip for it: the same
 * strict match and one-way-round side order, so a score is never put against the wrong team. The
 * spoiler setting applies the same way as the chip.
 */
object ScoreDetailsBuilder {

    fun of(item: SportsLiveItem, snapshot: ScoreSnapshot?, mode: ScoresMode?, revealed: Boolean): ScoreDetails? {
        val chip = ScoreChips.of(item, snapshot, mode, revealed) ?: return null
        val event = snapshot ?: return null
        return when (chip) {
            is ScoreChip.Hidden -> ScoreDetails.Hidden(chip.live)
            is ScoreChip.Status -> ScoreDetails.Status(chip.text)
            is ScoreChip.Live -> shown(item, event, chip.clock, live = true)
            is ScoreChip.Final -> shown(item, event, chip.note, live = false)
        }
    }

    private fun shown(item: SportsLiveItem, event: ScoreSnapshot, status: String?, live: Boolean): ScoreDetails? {
        val (first, second) = ScoreChips.orderedSides(item) ?: return null
        val firstIsHome = ScoreMatcher.firstIsHome(first, second, event) ?: return null
        val art = GameArt.of(item, event)
        val firstTeam = if (firstIsHome) event.home else event.away
        val secondTeam = if (firstIsHome) event.away else event.home
        val firstSide = if (firstIsHome) ScoreSide.HOME else ScoreSide.AWAY
        val secondSide = if (firstIsHome) ScoreSide.AWAY else ScoreSide.HOME
        val soccer = event.league.sport == SOCCER
        val periods = maxOf(firstTeam.linescores.size, secondTeam.linescores.size)
        // A row needs both sides' periods; one side alone would read as the other's zeros.
        val hasRow = firstTeam.linescores.isNotEmpty() && secondTeam.linescores.isNotEmpty()
        fun side(team: ScoreTeam, short: String, side: ScoreSide) = ScoreDetailsSide(
            short = short,
            score = team.score,
            linescores = if (hasRow) List(periods) { team.linescores.getOrNull(it) } else emptyList(),
            scorers = if (soccer) event.scorers.filter { it.side == side } else emptyList()
        )
        return ScoreDetails.Shown(
            first = side(firstTeam, art?.first?.short ?: first, firstSide),
            second = side(secondTeam, art?.second?.short ?: second, secondSide),
            status = status,
            live = live,
            periods = if (hasRow) periodLabels(event.league, periods) else emptyList(),
            soccer = soccer
        )
    }

    /** The headings of [count] linescore columns for [league]'s sport. */
    fun periodLabels(league: EspnLeague, count: Int): List<PeriodLabel> {
        val regulation = regulationPeriods(league)
        return List(count) { index ->
            val n = index + 1
            when {
                regulation == null || n <= regulation -> PeriodLabel.Number(n)
                league.sport == SOCCER -> PeriodLabel.ExtraTime(n - regulation)
                else -> PeriodLabel.Overtime(n - regulation)
            }
        }
    }

    /** Regulation periods, or null when every column is a plain number (baseball innings, unknown sports). */
    private fun regulationPeriods(league: EspnLeague): Int? = when (league.sport) {
        // Men's college basketball plays two halves; the women's game and the pros play quarters.
        "basketball" -> if (league.league == "mens-college-basketball") 2 else 4
        "football" -> 4
        "hockey" -> 3
        SOCCER -> 2
        else -> null
    }

    fun periodText(label: PeriodLabel, strings: ScoreDetailsStrings): String = when (label) {
        is PeriodLabel.Number -> label.n.toString()
        is PeriodLabel.Overtime -> if (label.n == 1) strings.overtime else String.format(strings.overtimeN, label.n)
        is PeriodLabel.ExtraTime -> String.format(strings.extraTime, label.n)
    }

    /** "ARS 2–1 CHE", or "ARS – CHE" while a live game has no score yet. */
    fun scorelineText(details: ScoreDetails.Shown, strings: ScoreDetailsStrings): String {
        val a = details.first.score
        val b = details.second.score
        return if (a != null && b != null) {
            String.format(strings.scoreline, details.first.short, a, b, details.second.short)
        } else {
            String.format(strings.sides, details.first.short, details.second.short)
        }
    }

    /**
     * A side's scorers, one per name in first-goal order with every minute: "Saka 23', 67' (pen)".
     * Null when the side has none.
     */
    fun scorersText(side: ScoreDetailsSide, strings: ScoreDetailsStrings): String? {
        if (side.scorers.isEmpty()) return null
        val byName = LinkedHashMap<String, MutableList<String>>()
        for (goal in side.scorers) {
            var minute = goal.clock.orEmpty()
            if (goal.penalty) minute = String.format(strings.penalty, minute).trim()
            if (goal.ownGoal) minute = String.format(strings.ownGoal, minute).trim()
            byName.getOrPut(goal.name) { mutableListOf() }.add(minute)
        }
        return byName.entries.joinToString(strings.separator) { (name, minutes) ->
            val shownMinutes = minutes.filter { it.isNotEmpty() }
            if (shownMinutes.isEmpty()) name else String.format(strings.scorer, name, shownMinutes.joinToString(strings.separator))
        }
    }

    private const val SOCCER = "soccer"
}
