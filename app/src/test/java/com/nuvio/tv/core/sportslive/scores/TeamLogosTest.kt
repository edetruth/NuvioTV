package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TeamLogosTest {

    private fun team(id: String, displayName: String, abbr: String, logo: String?, short: String = displayName) = ScoreTeam(
        id = id, abbreviation = abbr, displayName = displayName, shortDisplayName = short, name = displayName, location = null,
        logo = logo, score = null, linescores = emptyList()
    )

    private fun item(name: String, sport: String?, genres: List<String> = emptyList()) = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = genres
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
        kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false, startMs = null, sport = sport,
        startHasTimeOfDay = true, teams = SportsLiveTeams.parse(name)
    )

    private val logo = "https://a.espncdn.com/i/teamlogos/nba/500/ind.png"
    private val pacers = team("11", "Indiana Pacers", "IND", logo, short = "Pacers")

    @Test
    fun `scaled logos go through ESPN's combiner, other URLs stay as they are`() {
        assertEquals("https://a.espncdn.com/combiner/i?img=/i/teamlogos/nba/500/ind.png&w=96&h=96", TeamLogoUrls.scaled(logo))
        assertEquals(
            "https://a.espncdn.com/combiner/i?img=/i/teamlogos/countries/500/usa.png&w=192&h=192",
            TeamLogoUrls.scaled("https://a.espncdn.com/i/teamlogos/countries/500/usa.png", TeamLogoUrls.LARGE_PX)
        )
        // The combiner answers HTTP 400 for /guid/ logos (checked 2026-11-17): they stay full size.
        val guid = "https://a.espncdn.com/guid/335fd2d2-97b9-336b-81ee-573eb6bdcffc/logos/default.png"
        assertEquals(guid, TeamLogoUrls.scaled(guid))
        assertEquals("https://example.invalid/x.png", TeamLogoUrls.scaled("https://example.invalid/x.png"))
        assertEquals("$logo?x=1", TeamLogoUrls.scaled("$logo?x=1"))
    }

    @Test
    fun `an ESPN team is found by its ESPN names in its league only`() {
        val book = TeamLogoBook(active = true)
        assertTrue(book.learn(EspnLeagueMap.NBA, pacers))
        assertFalse(book.learn(EspnLeagueMap.NBA, pacers))
        assertEquals(KnownTeam(logo, "IND"), book.resolve(EspnLeagueMap.NBA, "Indiana Pacers"))
        assertEquals(KnownTeam(logo, "IND"), book.resolve(EspnLeagueMap.NBA, "Pacers"))
        assertEquals(KnownTeam(logo, "IND"), book.resolve(EspnLeagueMap.NBA, "IND"))
        // The abbreviation is not canonicalised: "India" is not "IND".
        assertNull(book.resolve(EspnLeagueMap.NBA, "India"))
        assertNull(book.resolve(EspnLeagueMap.COLLEGE_BASKETBALL, "Pacers"))
        // No logo, nothing learned.
        assertFalse(book.learn(EspnLeagueMap.NBA, team("4", "Chicago Bulls", "CHI", null)))
        // Soccer feeds share one key space.
        book.learn(EspnLeague("soccer", "eng.1"), team("359", "Arsenal", "ARS", "https://a.espncdn.com/i/teamlogos/soccer/500/359.png"))
        assertEquals("ARS", book.resolve(EspnLeagueMap.SOCCER_ALL, "Arsenal")?.abbreviation)
    }

    @Test
    fun `a name two ESPN teams answer to gets no logo`() {
        val book = TeamLogoBook(active = true)
        book.learn(EspnLeagueMap.SOCCER_ALL, team("359", "Arsenal", "ARS", "$logo#eng"))
        book.learn(EspnLeagueMap.SOCCER_ALL, team("2", "Arsenal de Sarandi", "ARS", "$logo#arg", short = "Arsenal"))
        assertNull(book.resolve(EspnLeagueMap.SOCCER_ALL, "Arsenal"))
        assertEquals("$logo#arg", book.resolve(EspnLeagueMap.SOCCER_ALL, "Arsenal de Sarandí")?.logo)
    }

    @Test
    fun `the book keeps at most its bound, dropping the least recently used`() {
        val book = TeamLogoBook(maxEntries = 3)
        listOf("A", "B", "C").forEach { book.learn(EspnLeagueMap.NBA, team(it, "Team $it", it, "$logo#$it")) }
        book.resolve(EspnLeagueMap.NBA, "Team A")
        book.learn(EspnLeagueMap.NBA, team("D", "Team D", "D", "$logo#D"))
        assertEquals(3, book.size)
        assertNull(book.resolve(EspnLeagueMap.NBA, "Team B"))
        assertEquals(TeamLogoBook.MAX_ENTRIES, 2_000)
    }

    @Test
    fun `the book survives a restart through its file`() {
        val dir = Files.createTempDirectory("logos").toFile()
        try {
            val file = TeamLogoFile(File(dir, "sportslive/team_logos.tsv"))
            val book = TeamLogoBook()
            book.learn(EspnLeagueMap.NBA, pacers)
            book.learn(EspnLeagueMap.NBA, team("20", "Philadelphia 76ers", "PHI", "https://a.espncdn.com/i/teamlogos/nba/500/phi.png", short = "76ers"))
            file.write(book.encode())
            val restarted = TeamLogoBook()
            val before = restarted.revision.value
            restarted.load(file.read()!!)
            assertEquals(KnownTeam(logo, "IND"), restarted.resolve(EspnLeagueMap.NBA, "Pacers"))
            assertTrue(restarted.revision.value > before)
            // A damaged line is skipped, the rest is read.
            assertEquals(2, TeamLogoBook().apply { load("garbage\n" + book.encode()) }.size)
            // Loading under a bound keeps the newest entries.
            val small = TeamLogoBook(maxEntries = 1).apply { load(book.encode()) }
            assertEquals(1, small.size)
            assertTrue(small.resolve(EspnLeagueMap.NBA, "76ers") != null)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a card gets known logos only when one of its leagues resolves both sides, and only when active`() {
        val book = TeamLogoBook()
        book.learn(EspnLeagueMap.NBA, team("14", "Miami Heat", "MIA", "$logo#heat", short = "Heat"))
        book.learn(EspnLeagueMap.NBA, team("2", "Boston Celtics", "BOS", "$logo#celtics", short = "Celtics"))
        book.learn(EspnLeagueMap.COLLEGE_BASKETBALL, team("2390", "Miami Hurricanes", "MIA", "$logo#canes", short = "Miami"))
        book.learn(EspnLeagueMap.COLLEGE_BASKETBALL, team("150", "Duke Blue Devils", "DUKE", "$logo#duke", short = "Duke"))
        // Not active until the Scores setting is known (and while Off).
        assertNull(book.forCard(item("Miami vs Duke", "Basketball"), "Miami", "Duke"))
        book.active = true
        assertEquals("$logo#heat", book.forCard(item("Heat vs Celtics", "Basketball"), "Heat", "Celtics")?.first?.logo)
        // College resolves both (a bare "Basketball" card may be college); the NBA does not know Duke.
        assertEquals("$logo#canes", book.forCard(item("Miami vs Duke", "Basketball"), "Miami", "Duke")?.first?.logo)
        // A card naming the NBA never reads college logos.
        assertNull(book.forCard(item("Miami vs Duke", "NBA"), "Miami", "Duke"))
    }
}
