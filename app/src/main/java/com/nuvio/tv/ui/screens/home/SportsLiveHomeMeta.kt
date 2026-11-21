package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.core.sportslive.scores.GameArt
import com.nuvio.tv.core.sportslive.scores.ScoreChip
import com.nuvio.tv.core.sportslive.scores.ScoreChipStrings
import com.nuvio.tv.core.sportslive.scores.ScoreChips
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.ui.screens.sportslive.DrawnCardFooter
import com.nuvio.tv.ui.screens.sportslive.gameClockText
import com.nuvio.tv.ui.screens.sportslive.DrawnCardTrailing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The words of a drawn sports card and of Home's hero for it (V2 phase 10), kept pure so tests read them:
 * - a live card's bottom line: "● 4:12 Q4 … 91–88" ("Score hidden" under Hide, the clock alone with no score);
 * - the hero's status line: "● 4:12 · Q4 · BOS 91–88 NYK";
 * - a Catch up card's: "Final · Sun 15 Nov" and the Replay pill, never a score.
 */
internal object SportsLiveHomeMeta {

    /**
     * A drawn game card's bottom line for its [chip] (the spoiler setting already applied). Home has no focus
     * reveal: under Hide until focused every game reads "Score hidden", whatever the feed has.
     */
    fun cardFooter(
        chip: ScoreChip?,
        mode: ScoresMode?,
        strings: ScoreChipStrings,
        liveWord: String,
        listedLive: String? = null,
        /** Men's college basketball's halves, for the game grammar's clock ("2H 9:51"). */
        halves: Boolean = false
    ): DrawnCardFooter = when (chip) {
        is ScoreChip.Live -> {
            // The game grammar's one clock line (live.24): "Q4 4:12", period first.
            DrawnCardFooter(
                lead = chip.clock?.let { gameClockText(it, halves) } ?: liveWord,
                live = true,
                trailing = chip.score?.let { DrawnCardTrailing.Score(String.format(strings.score, it.first, it.second)) }
            )
        }
        ScoreChip.Hidden -> DrawnCardFooter(lead = liveWord, live = true, trailing = DrawnCardTrailing.Note(strings.hidden))
        is ScoreChip.Final -> DrawnCardFooter(
            lead = chip.note ?: strings.finalNote,
            trailing = DrawnCardTrailing.Score(String.format(strings.score, chip.score.first, chip.score.second))
        )
        is ScoreChip.Status -> DrawnCardFooter(lead = chip.text ?: strings.postponed)
        // #215, live.24 grammar: a live game with no score of ours reads "● Live · No score yet", never "Listed live".
        null -> if (listedLive != null) DrawnCardFooter(lead = liveWord, live = true, trailing = DrawnCardTrailing.Note(listedLive)) else DrawnCardFooter(
            lead = liveWord,
            live = true,
            trailing = if (mode == ScoresMode.HIDE_UNTIL_FOCUSED) DrawnCardTrailing.Note(strings.hidden) else null
        )
    }

    /**
     * The hero's status line for a live game, and the card's label line: [liveFormat] ("● %1$s") round
     * "Q4 4:12 · BOS 91–88 NYK" (the game grammar's clock, then the abbreviations when [art] has short ones, else the
     * score alone). Any other chip reads as its chip does ("Final 124–131").
     */
    fun statusLine(chip: ScoreChip, art: GameArt?, strings: ScoreChipStrings, liveFormat: String, halves: Boolean = false): String = when (chip) {
        is ScoreChip.Live -> {
            val clock = chip.clock?.let { gameClockText(it, halves) }
            val score = chip.score?.let { score ->
                val numbers = String.format(strings.score, score.first, score.second)
                val first = art?.first?.short?.takeIf { it.length <= ABBREVIATION_MAX }
                val second = art?.second?.short?.takeIf { it.length <= ABBREVIATION_MAX }
                if (first != null && second != null) "$first $numbers $second" else numbers
            }
            String.format(liveFormat, listOfNotNull(clock, score).joinToString(SEPARATOR))
        }
        else -> ScoreChips.text(chip, strings)
    }

    /**
     * #215, as the section's rule ([com.nuvio.tv.ui.screens.sportslive.listedLiveOnly]): a Live now card reads "Listed
     * live" when Scores is on, the score feeds confirmed they do not have its game, and it has no strict ESPN match.
     */
    fun listedLiveOnly(mode: ScoresMode?, confirmedUnmatched: Boolean, matched: Boolean): Boolean =
        mode != null && mode != ScoresMode.OFF && confirmedUnmatched && !matched

    /** The hero's top line: "NBA · Live now", or [what] alone with no league. */
    fun eyebrow(league: String?, what: String): String = listOfNotNull(league?.trim()?.takeIf { it.isNotEmpty() }, what).joinToString(SEPARATOR)

    /** The hero's last line: "★ Your team · 2 streams"; null when neither applies (one stream says nothing). */
    fun extraLine(followed: Boolean, streams: Int, yourTeam: String, streamsText: (Int) -> String): String? =
        listOfNotNull(yourTeam.takeIf { followed }, streams.takeIf { it > 1 }?.let(streamsText))
            .joinToString(SEPARATOR)
            .takeIf { it.isNotEmpty() }

    /** A Catch up card's bottom line: "Final · Sun 15 Nov" (the start's local date); [finalWord] alone with no start. */
    fun catchUpLead(startMs: Long?, finalWord: String, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val start = startMs ?: return finalWord
        val date = DateTimeFormatter.ofPattern("EEE d MMM", locale).format(Instant.ofEpochMilli(start).atZone(zone))
        return finalWord + SEPARATOR + date
    }

    /**
     * Home draws a card for [item] (live.19 F3, #182): every game, the neutral card when it names no two sides; a
     * channel keeps its own art.
     */
    fun drawsCard(item: SportsLiveItem): Boolean = !item.isChannel && item.kind == SportsLiveKind.EVENTS

    /** An addon title made readable (live.19 F3): " Moto3 | Round 21 | FP1" → "Moto3 · Round 21 · FP1". */
    fun cleanTitle(title: String): String =
        title.split('|').map { it.trim().replace(WHITESPACE, " ") }.filter { it.isNotEmpty() }.joinToString(SEPARATOR).ifEmpty { title.trim() }

    private const val SEPARATOR = " · "

    /** Longer "abbreviations" are names, which would not fit the line. */
    private const val ABBREVIATION_MAX = 5

    private val WHITESPACE = Regex("\\s+")
}
