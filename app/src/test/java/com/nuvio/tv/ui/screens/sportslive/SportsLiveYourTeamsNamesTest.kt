package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.scores.DirectoryTeam
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.GameArtTeam
import com.nuvio.tv.core.sportslive.scores.TeamDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** J4 (user TV report 2026-11-21): a Your teams strip card never cuts a name ("Da… Lo…"). */
class SportsLiveYourTeamsNamesTest {

    private val nba = TeamDirectory().apply {
        put(
            EspnLeagueMap.NBA,
            listOf(
                DirectoryTeam("6", "Dallas Mavericks", "Mavericks", "DAL", "Dallas", "Mavericks", "https://a.espncdn.com/i/teamlogos/nba/500/dal.png"),
                DirectoryTeam("12", "LA Clippers", "Clippers", "LAC", "LA", "Clippers", "https://a.espncdn.com/i/teamlogos/nba/500/lac.png")
            ),
            fetchedAt = 1L
        )
    }

    private fun fromLists(name: String): String? =
        TeamDirectory.cityVariants(name).firstNotNullOfOrNull { nba.abbreviationAnywhere(it) }

    @Test
    fun `an unmatched card takes the abbreviation our team lists give, its city written either way`() {
        val mavericks = GameArtTeam(name = "Dallas Mavericks", short = "Dallas Mavericks", logo = null)
        val clippers = GameArtTeam(name = "Los Angeles Clippers", short = "Los Angeles Clippers", logo = null)
        assertEquals("DAL", yourTeamsShortName(mavericks, ::fromLists))
        assertEquals("LAC", yourTeamsShortName(clippers, ::fromLists))
    }

    @Test
    fun `ESPN's code is kept, and a team no list knows gets a code made from its name`() {
        assertEquals("BOS", yourTeamsShortName(GameArtTeam(name = "Boston Celtics", short = "BOS", logo = null)) { null })
        val code = yourTeamsShortName(GameArtTeam(name = "Khor Fakkan Club", short = "Khor Fakkan Club", logo = null)) { null }
        assertTrue(code, code.length <= 3)
        // A long short name (ESPN's "Mavericks") is no code: the lists' one, else a made one.
        assertEquals("DAL", yourTeamsShortName(GameArtTeam(name = "Dallas Mavericks", short = "Mavericks", logo = null), ::fromLists))
    }
}
