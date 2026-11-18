package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.scores.ChipScore
import com.nuvio.tv.core.sportslive.scores.ScoreChip
import com.nuvio.tv.core.sportslive.scores.ScoreChipStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** The 2026-11-18 emulator QA fixes: winner bold, the score chip's layout parts, the plural copy. */
class SportsLiveQaPolishTest {

    private val strings = ScoreChipStrings(
        score = "%1\$d–%2\$d",
        live = "%1\$s · %2\$s",
        final = "%1\$s %2\$s",
        finalNote = "Final",
        postponed = "Postponed",
        hidden = "Score hidden"
    )

    @Test
    fun `the winner is bold only while the result shows`() {
        assertTrue(sportsLiveWinnerBold(showScore = true, mine = 111, theirs = 102))
        assertFalse(sportsLiveWinnerBold(showScore = true, mine = 102, theirs = 111))
        // Hide until focused before the reveal, and Off: no bold, it would tell who won.
        assertFalse(sportsLiveWinnerBold(showScore = false, mine = 111, theirs = 102))
        // A tie or a missing score has no winner.
        assertFalse(sportsLiveWinnerBold(showScore = true, mine = 2, theirs = 2))
        assertFalse(sportsLiveWinnerBold(showScore = true, mine = 3, theirs = null))
        // Tennis: the feed's winner flag, under the same rule.
        assertTrue(sportsLiveWinnerBold(showScore = true, won = true))
        assertFalse(sportsLiveWinnerBold(showScore = false, won = true))
        assertFalse(sportsLiveWinnerBold(showScore = true, won = false))
    }

    @Test
    fun `a live chip splits into its clock and a score that never gives way`() {
        val chip = ScoreChip.Live(clock = "11:39 - 2nd", score = ChipScore(82, 53))

        assertEquals("11:39 - 2nd ·" to "82–53", scoreChipParts(chip, strings))
    }

    @Test
    fun `a final chip splits into its note and the score`() {
        assertEquals("Final" to "102–111", scoreChipParts(ScoreChip.Final(note = null, score = ChipScore(102, 111)), strings))
        assertEquals("FT" to "1–0", scoreChipParts(ScoreChip.Final(note = "FT", score = ChipScore(1, 0)), strings))
    }

    @Test
    fun `chips without a score keep their whole text as the lead`() {
        assertEquals("" to "3–1", scoreChipParts(ScoreChip.Live(clock = null, score = ChipScore(3, 1)), strings))
        assertEquals("67'" to null, scoreChipParts(ScoreChip.Live(clock = "67'", score = null), strings))
        assertEquals("Postponed" to null, scoreChipParts(ScoreChip.Status(text = null), strings))
        assertEquals("Score hidden" to null, scoreChipParts(ScoreChip.Hidden, strings))
    }

    @Test
    fun `the unanswered sources line is a plural, without a (s)`() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/values/strings.xml"))
        val plurals = document.getElementsByTagName("plurals")
        val items = (0 until plurals.length).map { plurals.item(it) }
            .single { it.attributes.getNamedItem("name").nodeValue == "sports_live_partial_failure" }
            .childNodes.let { nodes -> (0 until nodes.length).map { nodes.item(it) } }
            .filter { it.nodeName == "item" }
            .associate { it.attributes.getNamedItem("quantity").nodeValue to it.textContent }

        assertEquals("%1\$d source didn\\'t respond", items["one"])
        assertEquals("%1\$d sources didn\\'t respond", items["other"])
        val strings = document.getElementsByTagName("string")
        val stringNames = (0 until strings.length).map { strings.item(it).attributes.getNamedItem("name").nodeValue }
        assertFalse("sports_live_partial_failure" in stringNames)
    }
}
