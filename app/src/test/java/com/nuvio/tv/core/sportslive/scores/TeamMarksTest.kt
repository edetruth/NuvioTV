package com.nuvio.tv.core.sportslive.scores

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #130: a team mark is a real logo or nothing, never letters; a game shows logos only when both teams have one. */
class TeamMarksTest {

    private val celtics = "https://a.espncdn.com/i/teamlogos/nba/500/bos.png"
    private val heat = "https://a.espncdn.com/i/teamlogos/nba/500/mia.png"

    private fun event(awayLogo: String?, homeLogo: String?): ScoreSnapshot {
        val nba = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA).first()
        return nba.copy(
            away = nba.away.copy(logo = awayLogo),
            home = nba.home.copy(logo = homeLogo)
        )
    }

    @Test
    fun `both logos present gives both logos`() {
        assertEquals(celtics to heat, TeamMarks.pair(celtics, heat))
        assertTrue(GameArt.ofEvent(event(celtics, heat)).hasLogos)
    }

    @Test
    fun `one logo missing gives no logos at all`() {
        assertNull(TeamMarks.pair(celtics, null))
        assertNull(TeamMarks.pair(null, heat))
        assertNull(TeamMarks.pair(celtics, "  "))
        assertFalse(GameArt.ofEvent(event(celtics, null)).hasLogos)
        assertFalse(GameArt.ofEvent(event(null, heat)).hasLogos)
    }

    @Test
    fun `no logos gives no logos`() {
        assertNull(TeamMarks.pair(null, null))
        assertNull(TeamMarks.pair("", ""))
        assertFalse(GameArt.ofEvent(event(null, null)).hasLogos)
    }

    @Test
    fun `the pair shows only once both logos have loaded and one failing hides both`() {
        assertTrue(TeamMarks.pairShown(firstLoaded = true, secondLoaded = true))
        assertFalse(TeamMarks.pairShown(firstLoaded = true, secondLoaded = false))
        assertFalse(TeamMarks.pairShown(firstLoaded = false, secondLoaded = true))
        assertFalse(TeamMarks.pairShown(firstLoaded = false, secondLoaded = false))
    }

    @Test
    fun `a team alone shows its logo when it has one and nothing otherwise`() {
        assertEquals(celtics, TeamMarks.single(celtics))
        assertEquals(celtics, TeamMarks.single(" $celtics "))
        assertNull(TeamMarks.single(null))
        assertNull(TeamMarks.single(" "))
    }
}
