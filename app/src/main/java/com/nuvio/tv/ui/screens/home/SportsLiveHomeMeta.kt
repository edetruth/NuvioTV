package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.core.sportslive.scores.GameArt
import com.nuvio.tv.core.sportslive.scores.ScoreChip
import com.nuvio.tv.core.sportslive.scores.ScoreChipStrings
import com.nuvio.tv.core.sportslive.scores.ScoreChips
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.ui.screens.sportslive.DrawnCardFooter
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
     * ESPN's clock split into the clock and its period: "Q4 4:12" → (4:12, Q4), "9:51 - 2nd Half" → (9:51, 2nd half);
     * anything else ("78'", "Halftime") is the clock alone.
     */
    fun clockParts(clockText: String): Pair<String, String?> {
        val text = clockText.trim()
        val dashed = text.split(" - ", limit = 2).map { it.trim() }
        if (dashed.size == 2 && dashed.all { it.isNotEmpty() }) {
            val period = dashed[1].let { if (it.first().isDigit()) it.lowercase(Locale.ROOT) else it }
            return dashed[0] to period
        }
        val words = text.split(WHITESPACE)
        if (words.size == 2 && CLOCK.matches(words[1]) && !CLOCK.matches(words[0])) return words[1] to words[0]
        return text to null
    }

    /**
     * A drawn game card's bottom line for its [chip] (the spoiler setting already applied). Home has no focus
     * reveal: under Hide until focused every game reads "Score hidden", whatever the feed has.
     */
    fun cardFooter(chip: ScoreChip?, mode: ScoresMode?, strings: ScoreChipStrings, liveWord: String): DrawnCardFooter = when (chip) {
        is ScoreChip.Live -> {
            val (clock, period) = chip.clock?.let(::clockParts) ?: (liveWord to null)
            DrawnCardFooter(
                lead = clock,
                detail = period,
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
        null -> DrawnCardFooter(
            lead = liveWord,
            live = true,
            trailing = if (mode == ScoresMode.HIDE_UNTIL_FOCUSED) DrawnCardTrailing.Note(strings.hidden) else null
        )
    }

    /**
     * The hero's status line for a live game, and the card's label line: [liveFormat] ("● %1$s") round
     * "4:12 · Q4 · BOS 91–88 NYK" (the abbreviations when [art] has short ones, else the score alone). Any other
     * chip reads as its chip does ("Final 124–131").
     */
    fun statusLine(chip: ScoreChip, art: GameArt?, strings: ScoreChipStrings, liveFormat: String): String = when (chip) {
        is ScoreChip.Live -> {
            val (clock, period) = chip.clock?.let(::clockParts) ?: (null to null)
            val score = chip.score?.let { score ->
                val numbers = String.format(strings.score, score.first, score.second)
                val first = art?.first?.short?.takeIf { it.length <= ABBREVIATION_MAX }
                val second = art?.second?.short?.takeIf { it.length <= ABBREVIATION_MAX }
                if (first != null && second != null) "$first $numbers $second" else numbers
            }
            String.format(liveFormat, listOfNotNull(clock, period, score).joinToString(SEPARATOR))
        }
        else -> ScoreChips.text(chip, strings)
    }

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

    private const val SEPARATOR = " · "

    /** Longer "abbreviations" are names, which would not fit the line. */
    private const val ABBREVIATION_MAX = 5

    private val WHITESPACE = Regex("\\s+")
    private val CLOCK = Regex("\\d{1,2}:\\d{2}(\\.\\d)?")
}
