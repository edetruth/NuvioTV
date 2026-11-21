package com.nuvio.tv.ui.screens.sportslive.dual

import com.nuvio.tv.core.sportslive.SportsLiveDisplayText
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.displayName
import com.nuvio.tv.core.sportslive.SportsLiveSportKind
import com.nuvio.tv.core.sportslive.scores.ChipScore
import com.nuvio.tv.core.sportslive.scores.ScoreChip
import com.nuvio.tv.core.sportslive.scores.ScoreChipStrings
import com.nuvio.tv.core.sportslive.scores.ScoreChips
import com.nuvio.tv.core.sportslive.scores.ScoreDetailsStrings
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.ui.screens.sportslive.GameIdentities
import com.nuvio.tv.ui.screens.sportslive.GameSideIdentity
import com.nuvio.tv.ui.screens.sportslive.gameClockText
import com.nuvio.tv.ui.screens.sportslive.panelPlaysHalves

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
 * The caption under a dual pane (#124; live.24, user TV photo: "Philadelphia 76ers vs Mil…" cut, no logos, no score):
 * the one game identity ([GameIdentities]): both teams' logos and codes, never cut ("PHI", "#12 KU"), in the card's
 * order, the score and the clock with a red dot while live, and the league tag. The score is [ScoreChips]' chip, so the
 * same rules hold as in the full player: a strict ESPN match only, the Scores setting, the reveal of Hide until focused
 * and the score delay, whose hold for a game being watched (each pane's) the scores repository already applied. With no
 * chip (Scores Off, not matched, not started, or held back) the logos and codes show alone. Null (the pane's clean name
 * shows, [dualPaneCaption]) for anything that is not a two-sided game.
 */
internal data class DualScoreStrip(
    val first: GameSideIdentity,
    val second: GameSideIdentity,
    val chip: ScoreChip?,
    val tag: String = "",
    /** Men's college basketball's halves, for the game grammar's clock ("2H 9:51"). */
    val halves: Boolean = false
) {

    /** The red dot shows beside the clock only while the game is live (never on a hidden score). */
    val live: Boolean get() = chip is ScoreChip.Live

    /** The score in the card's order, when one shows. */
    val score: ChipScore?
        get() = when (chip) {
            is ScoreChip.Live -> chip.score
            is ScoreChip.Final -> chip.score
            is ScoreChip.Status, is ScoreChip.Hidden, null -> null
        }

    companion object {
        /**
         * [item]'s caption: [snapshot] is its score event (the delay already applied), used only with Scores on;
         * [label] words a sport for the tag; [codeOf] finds a code by name ([GameIdentities.knownCode]).
         */
        fun of(
            item: SportsLiveItem,
            snapshot: ScoreSnapshot?,
            mode: ScoresMode?,
            revealed: Boolean,
            label: (SportsLiveSportKind) -> String = { it.name },
            codeOf: (String) -> String? = GameIdentities::knownCode
        ): DualScoreStrip? {
            val shown = snapshot.takeIf { mode != null && mode != ScoresMode.OFF }
            val chip = ScoreChips.of(item, shown, mode, revealed)
            val identity = GameIdentities.of(item, shown, chip, label, codeOf = codeOf)
            val first = identity.first ?: return null
            val second = identity.second ?: return null
            return DualScoreStrip(first, second, chip, identity.tag, halves = panelPlaysHalves(shown?.league))
        }
    }

    /** "BOS 91–88 NYK" (the codes); the sides alone ("BOS – NYK") before a score, for a status or a hidden score. */
    fun scoreline(strings: ScoreDetailsStrings): String {
        val shown = score
        return if (shown != null) String.format(strings.scoreline, first.code, shown.first, shown.second, second.code)
        else String.format(strings.sides, first.code, second.code)
    }

    /** The game grammar's clock ("4th 4:12", "78'", "End 2H"), "Final/OT", "Postponed", "Score hidden"; null for a live game ESPN gives no clock for, or no chip. */
    fun status(strings: ScoreChipStrings): String? = when (chip) {
        is ScoreChip.Live -> chip.clock?.let { gameClockText(it, halves) }
        is ScoreChip.Final -> ScoreChips.finalWords(chip.note, strings.finalNote)
        is ScoreChip.Status -> chip.text ?: strings.postponed
        is ScoreChip.Hidden -> strings.hidden
        null -> null
    }
}
