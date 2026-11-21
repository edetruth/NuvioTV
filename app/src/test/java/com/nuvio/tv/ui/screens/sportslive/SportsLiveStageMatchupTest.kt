package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

/** l24 polish P5: the stage's matchup headline carries the ranks, so no second line repeats the title. */
class SportsLiveStageMatchupTest {

    @Test
    fun `a matchup headline carries each side's rank before its name`() {
        val headline = stageMatchup("%1\$s vs %2\$s", rankedName("Oklahoma", 22, 14.sp), rankedName("Texas A&M", null, 14.sp))
        assertEquals("#22 Oklahoma vs Texas A&M", headline.text)
        // The rank is styled apart (small, muted), the name is not.
        assertEquals(1, headline.spanStyles.size)
        assertEquals(0 until 3, headline.spanStyles.single().let { it.start until it.end })
    }

    @Test
    fun `an unranked matchup reads as before, in the template's order`() {
        assertEquals("Hornets at Hawks", stageMatchup("%1\$s at %2\$s", AnnotatedString("Hornets"), AnnotatedString("Hawks")).text)
        assertEquals("Hawks - Hornets", stageMatchup("%2\$s - %1\$s", AnnotatedString("Hornets"), AnnotatedString("Hawks")).text)
    }
}
