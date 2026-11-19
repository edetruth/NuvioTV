package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.scores.DirectoryTeam
import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.TeamDirectory
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** P4 B3: an unrouted two-team card takes the one league both its teams name, never a guess. */
class SportsLiveSportInferenceTest {

    private var nextId = 0

    private fun team(display: String, nickname: String, location: String, abbr: String) =
        (++nextId).toString().let { id -> DirectoryTeam(id, display, nickname, abbr, location, nickname, "https://a.espncdn.com/i/teamlogos/x/500/$id.png") }

    private val nhl = listOf(
        team("New Jersey Devils", "Devils", "New Jersey", "NJ"),
        team("Montreal Canadiens", "Canadiens", "Montreal", "MTL"),
        team("New York Rangers", "Rangers", "New York", "NYR"),
        team("Los Angeles Kings", "Kings", "Los Angeles", "LA")
    )
    private val mlb = listOf(
        team("Texas Rangers", "Rangers", "Texas", "TEX"),
        team("Kansas City Royals", "Royals", "Kansas City", "KC")
    )
    private val bra = listOf(
        team("Flamengo", "Flamengo", "Flamengo", "FLA"),
        team("Athletico-PR", "Athletico", "Athletico-PR", "CAP")
    )

    private val ncaam = listOf(
        team("Arizona Wildcats", "Arizona", "Arizona", "ARIZ"),
        team("UConn Huskies", "UConn", "UConn", "CONN"),
        team("Miami Hurricanes", "Miami", "Miami", "MIA"),
        team("Miami (OH) RedHawks", "Miami (OH)", "Miami (OH)", "M-OH"),
        team("Michigan State Spartans", "Michigan St", "Michigan State", "MSU"),
        team("St. John's Red Storm", "St. John's", "St. John's", "SJU"),
        team("Siena Saints", "Siena", "Siena", "SIE")
    )
    private val nba = listOf(team("Boston Celtics", "Celtics", "Boston", "BOS"), team("Miami Heat", "Heat", "Miami", "MIA"))

    /** Every inference league known; the named ones with their teams, the rest empty but loaded. */
    private fun directory(vararg lists: Pair<EspnLeague, List<DirectoryTeam>>): TeamDirectory {
        val dir = TeamDirectory()
        val given = lists.toMap()
        (SportsLiveSportInference.leagues + EspnLeagueMap.COLLEGE_BASKETBALL + EspnLeagueMap.COLLEGE_FOOTBALL).forEach { league ->
            dir.put(league, given[league] ?: listOf(team("Placeholder ${league.league}", "Placeholder", "Nowhere", "PLC")), 0L)
        }
        return dir
    }

    private val full = directory(
        EspnLeagueMap.NHL to nhl, EspnLeagueMap.MLB to mlb, EspnLeague("soccer", "bra.1") to bra,
        EspnLeagueMap.NBA to nba, EspnLeagueMap.COLLEGE_BASKETBALL to ncaam
    )

    private fun card(name: String, sport: String? = null, catalog: String = "Live", genres: List<String> = emptyList()) = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = genres
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = catalog,
        kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false, startMs = null, sport = sport,
        startHasTimeOfDay = true, teams = SportsLiveTeams.parse(name)
    )

    @Test
    fun `Devils v Canadiens infers the NHL`() {
        val inferred = SportsLiveSportInference.infer(card("Devils v Canadiens"), full)
        assertEquals("NHL", inferred.sport)
        // The usual routing follows: the card is now on the NHL feed.
        assertEquals(listOf(EspnLeagueMap.NHL), EspnLeagueMap.leaguesFor(inferred))
    }

    @Test
    fun `Flamengo v Athletico infers the Brazilian top flight`() {
        val inferred = SportsLiveSportInference.infer(card("Flamengo v Athletico"), full)
        assertEquals("Brazil Serie A", inferred.sport)
        assertEquals(listOf(EspnLeague("soccer", "bra.1")), EspnLeagueMap.leaguesFor(inferred))
    }

    @Test
    fun `a name two leagues know infers nothing`() {
        // Rangers play in the NHL and in MLB: never a guess.
        val item = card("Rangers vs Kings")
        assertSame(item, SportsLiveSportInference.infer(item, full))
    }

    @Test
    fun `teams of two different leagues infer nothing`() {
        assertNull(SportsLiveSportInference.leagueOf(card("Devils vs Royals"), full))
    }

    @Test
    fun `an unknown team infers nothing`() {
        assertNull(SportsLiveSportInference.leagueOf(card("Devils vs Somebody Else"), full))
    }

    @Test
    fun `a league not loaded yet infers nothing`() {
        val partial = TeamDirectory().apply { put(EspnLeagueMap.NHL, nhl, 0L) }
        assertNull(SportsLiveSportInference.leagueOf(card("Devils v Canadiens"), partial))
    }

    @Test
    fun `a card naming its competition keeps it`() {
        assertNull(SportsLiveSportInference.leagueOf(card("Devils v Canadiens", sport = "NBA"), full))
    }

    @Test
    fun `a bare sport word stays in that sport`() {
        // Basketball: the NBA or college basketball only, never the NHL.
        assertNull(SportsLiveSportInference.leagueOf(card("Devils v Canadiens", sport = "Basketball"), full))
    }

    @Test
    fun `a women's or college card is never inferred`() {
        assertNull(SportsLiveSportInference.leagueOf(card("Devils v Canadiens", genres = listOf("Women's")), full))
        assertNull(SportsLiveSportInference.leagueOf(card("Devils v Canadiens", catalog = "College Sports"), full))
    }

    @Test
    fun `a card naming another sport is not moved into this one`() {
        assertNull(SportsLiveSportInference.leagueOf(card("Devils v Canadiens", catalog = "Rugby Live"), full))
    }

    @Test
    fun `a bare football card narrows to a soccer league only`() {
        assertEquals(EspnLeague("soccer", "bra.1"), SportsLiveSportInference.leagueOf(card("Flamengo vs Athletico", sport = "Football"), full))
        assertNull(SportsLiveSportInference.leagueOf(card("Devils v Canadiens", sport = "Football"), full))
    }

    // A basketball card of two college teams (P4 follow-up)

    @Test
    fun `Arizona v Connecticut with sport Basketball infers NCAAM`() {
        val inferred = SportsLiveSportInference.infer(card("Arizona v Connecticut", sport = "Basketball"), full)
        assertEquals("NCAAM", inferred.sport)
        // The tag is the league's, and the card is now on the college feed.
        assertEquals(listOf(EspnLeagueMap.COLLEGE_BASKETBALL), EspnLeagueMap.leaguesFor(inferred))
    }

    @Test
    fun `UConn matches Connecticut`() {
        assertEquals(
            full.resolve(EspnLeagueMap.COLLEGE_BASKETBALL, "UConn")?.id,
            full.resolve(EspnLeagueMap.COLLEGE_BASKETBALL, "Connecticut")?.id
        )
        assertEquals(SportsLiveTeamAliases.canonical("uconn"), SportsLiveTeamAliases.canonical("connecticut"))
    }

    @Test
    fun `St and State name the same college`() {
        assertEquals("Michigan State Spartans", full.resolve(EspnLeagueMap.COLLEGE_BASKETBALL, "Michigan St.")?.displayName)
        assertEquals("Michigan State Spartans", full.resolve(EspnLeagueMap.COLLEGE_BASKETBALL, "Michigan St Spartans")?.displayName)
    }

    @Test
    fun `an apostrophe name matches written without it`() {
        assertEquals("NCAAM", SportsLiveSportInference.infer(card("St Johns vs Siena", sport = "Basketball"), full).sport)
    }

    @Test
    fun `an ambiguous college name infers nothing`() {
        // Two colleges answer to "Miami": never a guess.
        assertNull(SportsLiveSportInference.leagueOf(card("Miami vs Arizona", sport = "Basketball"), full))
        // Miami (OH) is its own school, never read as Miami (FL).
        assertNotEquals("Miami Hurricanes", full.resolve(EspnLeagueMap.COLLEGE_BASKETBALL, "Miami (OH)")?.displayName)
    }

    @Test
    fun `a bare basketball card of two NBA teams takes the NBA tag`() {
        assertEquals("NBA", SportsLiveSportInference.infer(card("Celtics vs Heat", sport = "Basketball"), full).sport)
    }

    @Test
    fun `a card already on the college feed takes the NCAAM tag when both teams are colleges`() {
        // "NCAA" in the genres routes it to college basketball; its sport word alone says "Basketball".
        val item = card("St Johns vs Siena", sport = "Basketball", genres = listOf("NCAA Basketball"))
        assertEquals("NCAAM", SportsLiveSportInference.infer(item, full).sport)
    }

    @Test
    fun `a possessive name normalises as written without the apostrophe`() {
        assertEquals(SportsLiveTeams.normalise("St Johns"), SportsLiveTeams.normalise("St. John's"))
        assertEquals("cote d ivoire", SportsLiveTeams.normalise("Cote d'Ivoire"))
    }
}
