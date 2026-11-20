package com.nuvio.tv.ui.screens.sportslive.dual

import com.nuvio.tv.core.sportslive.SportsLiveDisplayText
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.displayName
import com.nuvio.tv.core.sportslive.scores.GameArt
import com.nuvio.tv.core.sportslive.scores.GameArtTeam
import com.nuvio.tv.core.sportslive.scores.ScoreChip
import com.nuvio.tv.core.sportslive.scores.ScoreChipStrings
import com.nuvio.tv.core.sportslive.scores.ScoreChips
import com.nuvio.tv.core.sportslive.scores.ScoreDetailsStrings
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.domain.model.SportsLiveItem

/** The dual screen's [com.nuvio.tv.core.sportslive.scores.LiveScoreTracking] screen id: its two panes' games (#124). */
internal const val SCREEN_DUAL = "dual"

/**
 * A pane's caption when it has no score strip: "Clemson Tigers @ Duke Blue Devils · American Football". Words only
 * (F13, the 10-ft rule): the addon's emoji and symbols ("⏱", "🏈") go from the name and the sport.
 */
internal fun dualPaneCaption(item: SportsLiveItem): String {
    val name = DUAL_SYMBOLS.replace(item.displayName, " ").replace(DUAL_SPACES, " ").trim().ifEmpty { item.displayName }
    val sport = SportsLiveDisplayText.label(SportsLiveTeams.sportLabelOf(item))
    return listOfNotNull(name, sport).joinToString(" · ")
}

// Emoji and other pictographs, with their joiners and variation selectors.
private val DUAL_SYMBOLS = Regex("""[\p{So}\p{Cs}\x{200D}\x{FE0E}\x{FE0F}\x{20E3}]+""")
private val DUAL_SPACES = Regex("""\s+""")

/**
 * The score strip under a dual pane (#124, the V2 dual board's ②): the two teams in the card's order with
 * their logos, the score, and the clock with a red dot while live. It is [ScoreChips]' chip, so the same
 * rules hold: a strict ESPN match only, the Scores setting, the score delay (already applied to the
 * snapshot) and the reveal of Hide until focused. Null (no chip: the pane's clean name shows) when
 * unmatched, Scores Off, or the game has not started.
 */
data class DualScoreStrip(val first: GameArtTeam, val second: GameArtTeam, val chip: ScoreChip) {

    /** The red dot shows beside the clock only while the game is live (never on a hidden score). */
    val live: Boolean get() = chip is ScoreChip.Live

    companion object {
        fun of(item: SportsLiveItem, snapshot: ScoreSnapshot?, mode: ScoresMode?, revealed: Boolean): DualScoreStrip? {
            val chip = ScoreChips.of(item, snapshot, mode, revealed) ?: return null
            val art = GameArt.of(item, snapshot) ?: return null
            return DualScoreStrip(art.first, art.second, chip)
        }
    }

    /** "BOS 91–88 NYK"; the sides alone ("BOS – NYK") before a score, for a status or a hidden score. */
    fun scoreline(strings: ScoreDetailsStrings): String {
        val score = when (chip) {
            is ScoreChip.Live -> chip.score
            is ScoreChip.Final -> chip.score
            is ScoreChip.Status, is ScoreChip.Hidden -> null
        }
        return if (score != null) String.format(strings.scoreline, first.short, score.first, score.second, second.short)
        else String.format(strings.sides, first.short, second.short)
    }

    /** "Q4 4:12", "78'", "Final/OT", "Postponed", "Score hidden"; null for a live game ESPN gives no clock for. */
    fun status(strings: ScoreChipStrings): String? = when (chip) {
        is ScoreChip.Live -> chip.clock
        is ScoreChip.Final -> chip.note ?: strings.finalNote
        is ScoreChip.Status -> chip.text ?: strings.postponed
        is ScoreChip.Hidden -> strings.hidden
    }
}
