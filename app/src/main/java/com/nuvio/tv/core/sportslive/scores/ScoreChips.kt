package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.domain.model.SportsLiveItem

/** A score in the card's own team order: [first] is the team its title names first. */
data class ChipScore(val first: Int, val second: Int)

/** What a game card's score chip shows; no chip at all is null. */
sealed interface ScoreChip {
    /** In progress: ESPN's clock ("Q3 4:12", "67'") and the score, each when the feed has it. */
    data class Live(val clock: String?, val score: ChipScore?) : ScoreChip

    /** Finished: ESPN's note ("FT", "Final/OT"; null → "Final") and the final score. */
    data class Final(val note: String?, val score: ChipScore) : ScoreChip

    /** Postponed, cancelled, suspended or delayed: ESPN's status text (null → "Postponed"), never a score. */
    data class Status(val text: String?) : ScoreChip

    /** Hide until focused, before the reveal: "Score hidden"; [live] keeps the live dot. */
    data class Hidden(val live: Boolean) : ScoreChip
}

/** The chip's words, from string resources (tests pass the English ones). */
data class ScoreChipStrings(
    /** "%1$d–%2$d" */
    val score: String,
    /** "%1$s · %2$s": clock, score. */
    val live: String,
    /** "%1$s %2$s": note, score. */
    val final: String,
    val finalNote: String,
    val postponed: String,
    val hidden: String
)

/**
 * The score chip of a game card. Precision first (a wrong score is worse than none):
 * - only a card the repository matched to an event has one (its strict matcher: both sides, the time
 *   window, women's/youth qualifiers, ties give no match);
 * - the card's sides must also sit on the event one way round only ([ScoreMatcher.firstIsHome]), so a
 *   score is never shown against the wrong team;
 * - upcoming and unknown states show nothing new; postponed and the like show their status, no score.
 * The score delay is already applied by [LiveScoresRepository].
 */
object ScoreChips {

    fun of(item: SportsLiveItem, snapshot: ScoreSnapshot?, mode: ScoresMode?, revealed: Boolean): ScoreChip? {
        if (mode == null || mode == ScoresMode.OFF || snapshot == null) return null
        val (first, second) = orderedSides(item) ?: return null
        val firstIsHome = ScoreMatcher.firstIsHome(first, second, snapshot) ?: return null
        val score = scoreOf(snapshot, firstIsHome)
        val hidden = mode == ScoresMode.HIDE_UNTIL_FOCUSED && !revealed
        return when (snapshot.state) {
            ScoreState.OFF -> ScoreChip.Status(snapshot.clockText?.takeIf { it.isNotBlank() })
            ScoreState.IN -> when {
                snapshot.clockText.isNullOrBlank() && score == null -> null
                hidden -> ScoreChip.Hidden(live = true)
                else -> ScoreChip.Live(snapshot.clockText?.takeIf { it.isNotBlank() }, score)
            }
            ScoreState.POST -> when {
                !snapshot.isFinal || score == null -> null
                hidden -> ScoreChip.Hidden(live = false)
                else -> ScoreChip.Final(snapshot.clockText?.takeIf { it.isNotBlank() }, score)
            }
            ScoreState.PRE, ScoreState.UNKNOWN -> null
        }
    }

    fun text(chip: ScoreChip, strings: ScoreChipStrings): String = when (chip) {
        is ScoreChip.Live -> {
            val score = chip.score?.let { String.format(strings.score, it.first, it.second) }
            when {
                chip.clock != null && score != null -> String.format(strings.live, chip.clock, score)
                else -> chip.clock ?: score.orEmpty()
            }
        }
        is ScoreChip.Final ->
            String.format(strings.final, chip.note ?: strings.finalNote, String.format(strings.score, chip.score.first, chip.score.second))
        is ScoreChip.Status -> chip.text ?: strings.postponed
        is ScoreChip.Hidden -> strings.hidden
    }

    /**
     * The card's two sides in the order its title names them ("Patriots at Jets": Patriots, then Jets),
     * or null when the card names no game (a channel only through its bracketed game).
     */
    fun orderedSides(item: SportsLiveItem): Pair<String, String>? {
        val teams = ScoreCard.from(item)?.teams ?: return null
        val title = item.meta.name
        fun at(side: String) = title.indexOf(side, ignoreCase = true).let { if (it < 0) Int.MAX_VALUE else it }
        return if (at(teams.away) < at(teams.home)) teams.away to teams.home else teams.home to teams.away
    }

    private fun scoreOf(snapshot: ScoreSnapshot, firstIsHome: Boolean): ChipScore? {
        val home = snapshot.homeScore ?: return null
        val away = snapshot.awayScore ?: return null
        return if (firstIsHome) ChipScore(home, away) else ChipScore(away, home)
    }
}
