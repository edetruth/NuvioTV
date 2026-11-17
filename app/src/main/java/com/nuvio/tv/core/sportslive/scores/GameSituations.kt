package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.domain.model.SportsLiveItem

/** The situation line's words, from string resources (tests pass the English ones). */
data class GameSituationStrings(
    /** " · " between the line's parts. */
    val separator: String,
    /** "%1$s ball": the team with the ball. */
    val possession: String,
    /** "Red zone" */
    val redZone: String,
    /** "%1$s %2$d": inning half ("Top", "Bot"), inning. */
    val inning: String,
    /** "%1$d out" */
    val outs: String,
    /** "Runner on %1$s" */
    val runnerOn: String,
    /** "%1$s & %2$s" */
    val twoRunners: String,
    /** "Bases loaded" */
    val basesLoaded: String,
    val first: String,
    val second: String,
    val third: String
)

/**
 * The Scoreboard's situation line (#23): possession and down & distance, the inning with outs and
 * runners. It follows the score exactly: shown only when [ScoreChips] shows a live chip for the card
 * (strict match, sides one way round, not hidden, scores not Off), and taken from the snapshot the
 * repository already delayed, so it never runs ahead of the score.
 */
object GameSituations {

    /** [snapshot]'s situation when [item] would show its live score; else null (hidden, final, unmatched…). */
    fun of(item: SportsLiveItem, snapshot: ScoreSnapshot?, mode: ScoresMode?, revealed: Boolean): GameSituation? {
        val chip = ScoreChips.of(item, snapshot, mode, revealed)
        return if (chip is ScoreChip.Live) snapshot?.situation else null
    }

    /**
     * "SEA ball · 2nd & 6 · LV 34", "Bot 7 · 2 out · 1st & 3rd"; empty when the situation says nothing
     * the line shows. [event] gives the possession team's abbreviation and the inning (its clock text).
     */
    fun situationLine(situation: GameSituation?, event: ScoreSnapshot, strings: GameSituationStrings): String {
        situation ?: return ""
        val parts = when (event.league.sport) {
            FOOTBALL -> football(situation, event, strings)
            BASEBALL -> baseball(situation, event, strings)
            else -> emptyList()
        }
        return parts.joinToString(strings.separator)
    }

    private fun football(s: GameSituation, event: ScoreSnapshot, strings: GameSituationStrings): List<String> = buildList {
        val team = when (s.possession) {
            ScoreSide.HOME -> event.home
            ScoreSide.AWAY -> event.away
            null -> null
        }
        (team?.abbreviation ?: team?.shortDisplayName)?.let { add(String.format(strings.possession, it)) }
        s.downDistance?.let(::add)
        // The long form ("2nd & 6 at LV 34") already names the spot.
        s.yardLine?.takeIf { s.downDistance?.contains(it) != true }?.let(::add)
        if (s.redZone) add(strings.redZone)
    }

    private fun baseball(s: GameSituation, event: ScoreSnapshot, strings: GameSituationStrings): List<String> = buildList {
        event.clockText?.let { INNING.find(it.trim()) }?.let { match ->
            add(String.format(strings.inning, match.groupValues[1], match.groupValues[2].toInt()))
        }
        s.outs?.let { add(String.format(strings.outs, it)) }
        val bases = listOfNotNull(
            strings.first.takeIf { s.onFirst },
            strings.second.takeIf { s.onSecond },
            strings.third.takeIf { s.onThird }
        )
        when (bases.size) {
            1 -> add(String.format(strings.runnerOn, bases[0]))
            2 -> add(String.format(strings.twoRunners, bases[0], bases[1]))
            3 -> add(strings.basesLoaded)
        }
    }

    // ESPN's baseball clock: "Bot 7th", "Top 1st", "Mid 5th", "End 9th".
    private val INNING = Regex("""^(Top|Bot|Mid|End)\s+(\d+)""", RegexOption.IGNORE_CASE)

    private const val FOOTBALL = "football"
    private const val BASEBALL = "baseball"
}
