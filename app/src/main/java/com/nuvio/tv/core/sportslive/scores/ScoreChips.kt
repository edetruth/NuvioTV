package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.core.sportslive.SportsLiveTeams
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

    /**
     * Hide until focused, before the reveal: "Score hidden". The same for a scheduled, live or finished
     * game (no live dot), so the chip never tells whether a game has started or ended.
     */
    data object Hidden : ScoreChip
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
        snapshot ?: return null
        val spoilers = SpoilerPolicy.visibility(
            mode, revealed, SpoilerContext(SpoilerSurface.SCORE_CHIP, eventState = snapshot.state, spoilerProtected = snapshot.spoilerProtected)
        )
        // No chip before the Scores setting is read, nor under Never show.
        if (!spoilers.mayLoad) return null
        val (first, second) = orderedSides(item) ?: return null
        val firstIsHome = ScoreMatcher.firstIsHome(first, second, snapshot) ?: return null
        val score = scoreOf(snapshot, firstIsHome)
        // Hidden: one chip for scheduled, live and finished games alike, whatever the feed has yet; a spoiler-protected
        // game (#151) whatever the setting, and a focus never reveals it.
        if (!spoilers.showsScore) return ScoreChip.Hidden
        return when (snapshot.state) {
            ScoreState.OFF -> ScoreChip.Status(snapshot.clockText?.takeIf { it.isNotBlank() })
            ScoreState.IN -> when {
                snapshot.clockText.isNullOrBlank() && score == null -> null
                else -> ScoreChip.Live(snapshot.clockText?.takeIf { it.isNotBlank() }, score)
            }
            ScoreState.POST -> when {
                !snapshot.isFinal || score == null -> null
                else -> ScoreChip.Final(snapshot.clockText?.takeIf { it.isNotBlank() }, score)
            }
            ScoreState.PRE, ScoreState.UNKNOWN -> null
        }
    }

    /**
     * [snapshot] when it is [item]'s strict match with the card's sides on it one way round only (what a
     * chip needs), else null. Only such a game offers "Game stats": a wrong game is worse than none.
     */
    fun matchedEvent(item: SportsLiveItem, snapshot: ScoreSnapshot?): ScoreSnapshot? {
        val event = snapshot ?: return null
        val (first, second) = orderedSides(item) ?: return null
        return event.takeIf { ScoreMatcher.firstIsHome(first, second, it) != null }
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
            String.format(strings.final, finalWords(chip.note, strings.finalNote), String.format(strings.score, chip.score.first, chip.score.second))
        is ScoreChip.Status -> chip.text ?: strings.postponed
        is ScoreChip.Hidden -> strings.hidden
    }

    /**
     * The game grammar's words for a finished game (live.24, TV report #19: a final read just "OT"): the final word, then
     * the way it ended when ESPN's note says so: "Final", "FT", "Final · OT", "Final · 2OT", "Final · SO", "FT · AET",
     * "FT · Pens". [note] is ESPN's final shortDetail ("Final/OT", "FT-Pens", "AET"); [final] the final word when the
     * note says nothing else; [fullTime] the word of a game that ends at full time (soccer, rugby: [fullTimeSport], or a
     * note written "FT…" / "AET").
     */
    fun finalWords(note: String?, final: String, fullTime: String = FULL_TIME, fullTimeSport: Boolean = false): String {
        val detail = note?.trim().orEmpty()
        val marker = when {
            detail.contains("pen", ignoreCase = true) -> PENS
            detail.contains("aet", ignoreCase = true) -> AET
            SHOOTOUT.containsMatchIn(detail) -> SHOOTOUT_WORD
            else -> OVERTIME.find(detail)?.value?.uppercase()
        }
        val atFullTime = fullTimeSport || detail.startsWith(FULL_TIME, ignoreCase = true) || marker == AET || marker == PENS
        val base = if (atFullTime) fullTime else final
        return if (marker != null) "$base$FINAL_SEPARATOR$marker" else base
    }

    private const val FULL_TIME = "FT"
    private const val PENS = "Pens"
    private const val AET = "AET"
    private const val SHOOTOUT_WORD = "SO"
    private const val FINAL_SEPARATOR = " · "
    private val OVERTIME = Regex("""\b\d*OT\b""", RegexOption.IGNORE_CASE)
    private val SHOOTOUT = Regex("""\bSO\b""", RegexOption.IGNORE_CASE)

    /**
     * The card's two sides in the order its title names them ("Patriots at Jets": Patriots, then Jets),
     * or null when the card names no game (a channel only through its bracketed game).
     */
    fun orderedSides(item: SportsLiveItem): Pair<String, String>? {
        val teams = ScoreCard.from(item)?.teams ?: return null
        // From the separator the title was parsed by, never by searching names (one can contain the other).
        val written = SportsLiveTeams.writtenOrder(item.meta.name, bracketed = item.isChannel)
        return when {
            written == null -> teams.home to teams.away
            written == teams.away to teams.home -> teams.away to teams.home
            else -> teams.home to teams.away
        }
    }

    private fun scoreOf(snapshot: ScoreSnapshot, firstIsHome: Boolean): ChipScore? {
        val home = snapshot.homeScore ?: return null
        val away = snapshot.awayScore ?: return null
        return if (firstIsHome) ChipScore(home, away) else ChipScore(away, home)
    }
}
