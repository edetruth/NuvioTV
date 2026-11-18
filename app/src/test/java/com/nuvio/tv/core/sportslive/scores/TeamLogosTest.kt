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

    private fun team(abbr: String, logo: String?) = ScoreTeam(
        id = abbr, abbreviation = abbr, displayName = abbr, shortDisplayName = abbr, name = abbr, location = null,
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
    fun `a learned side is found by its card name in its league only`() {
        val book = TeamLogoBook()
        assertTrue(book.learn(EspnLeagueMap.NBA, "Pacers", team("IND", logo)))
        assertFalse(book.learn(EspnLeagueMap.NBA, "Pacers", team("IND", logo)))
        assertEquals(KnownTeam(logo, "IND"), book.lookup(EspnLeagueMap.NBA, " pacers "))
        assertNull(book.lookup(EspnLeagueMap.COLLEGE_BASKETBALL, "Pacers"))
        // No logo, nothing learned.
        assertFalse(book.learn(EspnLeagueMap.NBA, "Bulls", team("CHI", null)))
        // Soccer feeds share one key space.
        book.learn(EspnLeague("soccer", "eng.1"), "Arsenal", team("ARS", "https://a.espncdn.com/i/teamlogos/soccer/500/359.png"))
        assertEquals("ARS", book.lookup(EspnLeagueMap.SOCCER_ALL, "Arsenal")?.abbreviation)
    }

    @Test
    fun `the book keeps at most its bound, dropping the least recently used`() {
        val book = TeamLogoBook(maxEntries = 3)
        listOf("A", "B", "C").forEach { book.learn(EspnLeagueMap.NBA, it, team(it, "$logo#$it")) }
        book.lookup(EspnLeagueMap.NBA, "A")
        book.learn(EspnLeagueMap.NBA, "D", team("D", "$logo#D"))
        assertEquals(3, book.size)
        assertNull(book.lookup(EspnLeagueMap.NBA, "B"))
        assertTrue(book.lookup(EspnLeagueMap.NBA, "A") != null)
        assertEquals(TeamLogoBook.MAX_ENTRIES, 2_000)
    }

    @Test
    fun `the book survives a restart through its file`() {
        val dir = Files.createTempDirectory("logos").toFile()
        try {
            val file = TeamLogoFile(File(dir, "sportslive/team_logos.tsv"))
            val book = TeamLogoBook()
            book.learn(EspnLeagueMap.NBA, "Pacers", team("IND", logo))
            book.learn(EspnLeagueMap.NBA, "76ers", team("PHI", "https://a.espncdn.com/i/teamlogos/nba/500/phi.png"))
            file.write(book.encode())
            val restarted = TeamLogoBook()
            val before = restarted.revision.value
            restarted.load(file.read()!!)
            assertEquals(KnownTeam(logo, "IND"), restarted.lookup(EspnLeagueMap.NBA, "Pacers"))
            assertTrue(restarted.revision.value > before)
            // A damaged line is skipped, the rest is read.
            val damaged = TeamLogoBook().apply { load("garbage\n" + book.encode()) }
            assertEquals(2, damaged.size)
            // Loading under a bound keeps the newest entries.
            val small = TeamLogoBook(maxEntries = 1).apply { load(book.encode()) }
            assertEquals(1, small.size)
            assertTrue(small.lookup(EspnLeagueMap.NBA, "76ers") != null)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a card gets known logos only when one of its leagues knows both sides`() {
        val book = TeamLogoBook()
        book.learn(EspnLeagueMap.NBA, "Miami", team("MIA", "$logo#heat"))
        book.learn(EspnLeagueMap.NBA, "Boston", team("BOS", "$logo#celtics"))
        book.learn(EspnLeagueMap.COLLEGE_BASKETBALL, "Miami", team("MIA", "$logo#canes"))
        book.learn(EspnLeagueMap.COLLEGE_BASKETBALL, "Duke", team("DUKE", "$logo#duke"))
        // NBA knows Miami and Boston; college knows only Miami.
        assertEquals("$logo#heat", book.forCard(item("Miami vs Boston", "Basketball"), "Miami", "Boston")?.first?.logo)
        // College knows both (a bare "Basketball" card may be college); NBA does not know Duke.
        assertEquals("$logo#canes", book.forCard(item("Miami vs Duke", "Basketball"), "Miami", "Duke")?.first?.logo)
        // A card naming the NBA never reads college logos.
        assertNull(book.forCard(item("Miami vs Duke", "NBA"), "Miami", "Duke"))
        // Both leagues knowing both sides: ambiguous, none.
        book.learn(EspnLeagueMap.COLLEGE_BASKETBALL, "Boston", team("BC", "$logo#bc"))
        assertNull(book.forCard(item("Miami vs Boston", "Basketball"), "Miami", "Boston"))
        // Off: nothing.
        book.active = false
        assertNull(book.forCard(item("Miami vs Duke", "Basketball"), "Miami", "Duke"))
    }
}
