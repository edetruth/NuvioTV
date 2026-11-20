package com.nuvio.tv.ui.screens.sportslive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The excitement badge never ends in an ellipsis (live.20 TV report: "Close · 8 m…" in the 75 dp status column). */
class SportsLiveExcitementBadgeTest {

    private val strings = File("src/main/res/values/strings_sports_live.xml").readText()

    private fun string(name: String): String =
        Regex("""<string name="$name">(.*?)</string>""").find(strings)!!.groupValues[1]

    private fun texts(minutesLeft: Int?): List<String> = closeLateTexts(
        minutesLeft,
        long = { String.format(string("sports_live_why_close_minutes"), it) },
        short = { String.format(string("sports_live_why_close_minutes_short"), it) },
        late = string("sports_live_why_close_late")
    )

    @Test
    fun `a close game's badge offers shorter words, ending on Close late`() {
        assertEquals(listOf("Close · 8 min left", "Close · 8 min", "Close · late"), texts(8))
        assertEquals(listOf("Close · late"), texts(null))
    }

    @Test
    fun `every badge's last words are short enough for the status column`() {
        // 10 sp SemiBold in 75 dp less 8 dp padding holds about 13 characters (TV report: "Close · 8 m…" fitted); the badge measures, this keeps a fallback that fits.
        val shortest = listOf(
            texts(88).last(),
            string("sports_live_why_scoring_position_short"),
            string("sports_live_why_penalties"), string("sports_live_why_shootout"), string("sports_live_why_overtime"),
            string("sports_live_why_extra_time"), string("sports_live_why_extra_innings"), string("sports_live_why_lead_change"),
            string("sports_live_why_just_scored"), string("sports_live_why_red_zone"), string("sports_live_why_bases_loaded"),
            string("sports_live_close_game")
        )
        for (text in shortest) assertTrue(text, text.length <= 13)
    }
}
