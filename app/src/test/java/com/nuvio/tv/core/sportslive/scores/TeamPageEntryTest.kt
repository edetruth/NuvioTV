package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** QA pass 3: "Team page ‹team›" in hold-OK menus (Decision 6), and the standing line's wording. */
class TeamPageEntryTest {

    private fun team(id: String, display: String, short: String, abbr: String) =
        DirectoryTeam(id, display, short, abbr, display.substringBeforeLast(' '), short, "https://a.espncdn.com/i/teamlogos/nhl/500/$id.png")

    private val nhlTeams = listOf(
        team("1", "Boston Bruins", "Bruins", "BOS"),
        team("26", "Florida Panthers", "Panthers", "FLA")
    )

    private fun item(name: String, sport: String?) = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Upcoming",
        kind = SportsLiveKind.EVENTS, isLive = false, isChannel = false, startMs = 1_800_000_000_000L, sport = sport,
        startHasTimeOfDay = true, teams = SportsLiveTeams.parse(name)
    )

    @Test
    fun `a pro game no scoreboard lists yet offers both Team pages in the card's order`() {
        val directory = TeamDirectory().apply { put(EspnLeagueMap.NHL, nhlTeams, 0L) }
        val refs = TeamPages.refsForCard(item("Boston Bruins vs Florida Panthers", "NHL"), directory)
        assertEquals(listOf("1", "26"), refs.map { it.teamId })
        assertTrue(refs.all { it.league == EspnLeagueMap.NHL })
        assertEquals(listOf("Boston Bruins", "Florida Panthers"), refs.map { it.name })
    }

    @Test
    fun `no Team page without a known league list, for an unknown side, or outside the pro leagues`() {
        // The league's list is not known yet.
        assertTrue(TeamPages.refsForCard(item("Boston Bruins vs Florida Panthers", "NHL"), TeamDirectory()).isEmpty())
        val directory = TeamDirectory().apply { put(EspnLeagueMap.NHL, nhlTeams, 0L) }
        // One side is no team of the list.
        assertTrue(TeamPages.refsForCard(item("Boston Bruins vs Springfield Isotopes", "NHL"), directory).isEmpty())
        // A channel-style title with no two sides.
        assertTrue(TeamPages.refsForCard(item("NHL Network", "NHL"), directory).isEmpty())
    }

    @Test
    fun `the standing line reads properly`() {
        assertEquals("2nd in Western Conference", TeamPages.standingText("2nd in Western Conference Division"))
        assertEquals("1st in Atlantic Division", TeamPages.standingText("1st in Atlantic Division"))
        assertEquals("4th in NFC East", TeamPages.standingText(" 4th in NFC East "))
        assertNull(TeamPages.standingText("  "))
    }
}
