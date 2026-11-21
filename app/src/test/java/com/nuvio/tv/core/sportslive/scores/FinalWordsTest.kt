package com.nuvio.tv.core.sportslive.scores

import org.junit.Assert.assertEquals
import org.junit.Test

/** live.24 (TV report #19, BYU 31–29 Kansas read just "OT"): a final always says Final (or FT), then how it ended. */
class FinalWordsTest {

    private fun words(note: String?, fullTimeSport: Boolean = false) = ScoreChips.finalWords(note, "Final", "FT", fullTimeSport)

    @Test
    fun `an overtime final reads Final then the overtime marker`() {
        assertEquals("Final · OT", words("Final/OT"))
        assertEquals("Final · OT", words("OT"))
        assertEquals("Final · 2OT", words("Final/2OT"))
        assertEquals("Final · SO", words("Final/SO"))
    }

    @Test
    fun `a soccer final reads FT then extra time or penalties`() {
        assertEquals("FT · AET", words("AET", fullTimeSport = true))
        assertEquals("FT · Pens", words("FT-Pens", fullTimeSport = true))
        assertEquals("FT", words("FT", fullTimeSport = true))
        // ESPN's own "FT" names full time even without the sport.
        assertEquals("FT", words("FT"))
    }

    @Test
    fun `a plain end reads the sport's word alone`() {
        assertEquals("Final", words(null))
        assertEquals("Final", words("Final"))
        assertEquals("FT", words(null, fullTimeSport = true))
    }
}
