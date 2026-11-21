package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.scores.DirectoryTeam
import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.MyLeague
import com.nuvio.tv.core.sportslive.scores.MySport
import com.nuvio.tv.core.sportslive.scores.MySports
import com.nuvio.tv.core.sportslive.scores.TeamDirectory
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #146, #167: My sports scopes the addon game lists, never hiding a followed team's game; unidentified games are hidden. */
class SportsLiveMySportsScopeTest {

    private val basketballAndSoccer = MySports(mapOf(MySport.BASKETBALL to emptySet(), MySport.SOCCER to emptySet()))

    private val nba = game("Lakers vs Celtics", sport = "NBA")
    private val cricket = game("India vs Australia", sport = "Cricket")

    @Test
    fun `cricket is hidden when My sports does not hold it`() {
        assertFalse(SportsLiveMySportsScope.shows(cricket, basketballAndSoccer))
        assertTrue(SportsLiveMySportsScope.shows(nba, basketballAndSoccer))
        assertEquals(listOf(nba), SportsLiveMySportsScope.scope(listOf(cricket, nba), basketballAndSoccer))
        // Named only by its title or its catalog too.
        assertFalse(SportsLiveMySportsScope.shows(game("ICC T20: India vs Australia", sport = null), basketballAndSoccer))
        assertFalse(SportsLiveMySportsScope.shows(game("India vs Australia", sport = null, catalog = "Cricket Live"), basketballAndSoccer))
    }

    @Test
    fun `a followed team's game shows even when its sport is off`() {
        assertTrue(SportsLiveMySportsScope.shows(cricket.copy(followed = true), basketballAndSoccer))
        val hockeyOff = MySports(mapOf(MySport.BASKETBALL to emptySet()))
        assertTrue(SportsLiveMySportsScope.shows(game("Rangers vs Devils", sport = "NHL").copy(followed = true), hockeyOff))
        assertFalse(SportsLiveMySportsScope.shows(game("Rangers vs Devils", sport = "NHL"), hockeyOff))
    }

    @Test
    fun `a game whose sport cannot be told is hidden under My sports and shown under All sports`() {
        val directory = TeamDirectory()
        val unknown = game("Norwid Czestochowa vs ONICO Warszawa", sport = null)
        assertFalse(SportsLiveMySportsScope.shows(unknown, basketballAndSoccer, directory))
        // A catch-all sport word or a mixed catalog name tells nothing either.
        assertFalse(SportsLiveMySportsScope.shows(game("Team A vs Team B", sport = "Sports"), basketballAndSoccer, directory))
        assertFalse(
            SportsLiveMySportsScope.shows(
                game("Team A vs Team B", sport = null, catalog = "Live Sports (Cricket, Tennis, F1)"), basketballAndSoccer, directory
            )
        )
        // A doubles pairing with no tag.
        assertFalse(SportsLiveMySportsScope.shows(game("Harri Heliovaara / Henry Patten", sport = null), basketballAndSoccer, directory))
        // All sports (nothing picked) lists them all, and a followed team's game always shows.
        assertTrue(SportsLiveMySportsScope.shows(unknown, MySports.DEFAULT, directory))
        assertTrue(SportsLiveMySportsScope.shows(unknown.copy(followed = true), basketballAndSoccer, directory))
        // A merged game takes the first source that tells its sport.
        val merged = game("Team A vs Team B", sport = null).copy(alternates = listOf(game("Team A vs Team B", sport = "Cricket")))
        assertFalse(SportsLiveMySportsScope.shows(merged, basketballAndSoccer, directory))
        val mergedNba = game("Team A vs Team B", sport = null).copy(alternates = listOf(game("Team A vs Team B", sport = "NBA")))
        assertTrue(SportsLiveMySportsScope.shows(mergedNba, basketballAndSoccer, directory))
    }

    @Test
    fun `an untagged game whose teams the team directory knows takes their sport`() {
        val gLeague = EspnLeague("basketball", "nba-development")
        val directory = TeamDirectory().apply {
            put(
                gLeague,
                listOf(team("1", "Memphis Hustle", "Hustle", "MEM"), team("2", "Texas Legends", "Legends", "TEX")),
                fetchedAt = 1L
            )
        }
        val game = game("Memphis Hustle vs Texas Legends", sport = null)
        assertEquals(listOf(gLeague), SportsLiveMySportsScope.directoryLeaguesOf(game, directory))
        assertTrue(SportsLiveMySportsScope.shows(game, basketballAndSoccer, directory))
        assertFalse(SportsLiveMySportsScope.shows(game, MySports(mapOf(MySport.HOCKEY to emptySet())), directory))
        // One side alone is no match.
        assertFalse(SportsLiveMySportsScope.shows(game("Memphis Hustle vs Somebody Else", sport = null), basketballAndSoccer, directory))
    }

    @Test
    fun `scope keeps My sports games and followed ones and leaves out the unidentified`() {
        val directory = TeamDirectory()
        val unknown = game("Fino Kaposvar vs HAOK Mladost", sport = null)
        val followedUnknown = game("Club X vs Club Y", sport = null).copy(followed = true)
        val items = listOf(unknown, nba, followedUnknown, cricket)
        val scoped = SportsLiveMySportsScope.scope(items, basketballAndSoccer, directory)
        assertEquals(listOf(nba, followedUnknown), scoped)
        // The hidden count the "N more hidden by My sports" line shows.
        assertEquals(2, items.size - scoped.size)
        assertEquals(items, SportsLiveMySportsScope.scope(items, MySports.DEFAULT, directory))
    }

    private fun team(id: String, display: String, short: String, abbr: String) =
        DirectoryTeam(id, display, short, abbr, display.substringBeforeLast(' '), short, "https://a.espncdn.com/i/teamlogos/x/500/$id.png")

    @Test
    fun `nothing picked scopes nothing, and channels are never scoped`() {
        assertTrue(SportsLiveMySportsScope.shows(cricket, MySports.DEFAULT))
        assertEquals(listOf(cricket, nba), SportsLiveMySportsScope.scope(listOf(cricket, nba), MySports.DEFAULT))
        assertTrue(SportsLiveMySportsScope.shows(cricket.copy(isChannel = true), basketballAndSoccer))
    }

    @Test
    fun `a sport ticked without naming leagues takes its college games too`() {
        val ncaam = game("Duke vs North Carolina", sport = "NCAAM")
        val ncaaf = game("Alabama vs Georgia", sport = "College Football")
        assertTrue(SportsLiveMySportsScope.shows(ncaam, basketballAndSoccer))
        val football = MySports(mapOf(MySport.FOOTBALL to emptySet()))
        assertTrue(SportsLiveMySportsScope.shows(ncaaf, football))
        // The defaults (college off) only choose the Results feeds.
        assertFalse(MyLeague.COLLEGE_BASKETBALL in MySport.BASKETBALL.defaultLeagues)
    }

    @Test
    fun `with leagues named, a game of a known league must be one of them, and an unplaced one is left to All sports`() {
        val nbaOnly = MySports(mapOf(MySport.BASKETBALL to setOf(MyLeague.NBA)))
        assertTrue(SportsLiveMySportsScope.shows(nba, nbaOnly))
        assertFalse(SportsLiveMySportsScope.shows(game("Duke vs North Carolina", sport = "NCAAM"), nbaOnly))
        // A bare "Basketball" may be either, and no team list places it: All sports only (J3).
        assertFalse(SportsLiveMySportsScope.shows(game("Duke vs North Carolina", sport = "Basketball"), nbaOnly))

        val premierOnly = MySports(mapOf(MySport.SOCCER to setOf(MyLeague.PREMIER_LEAGUE)))
        assertTrue(SportsLiveMySportsScope.shows(game("Arsenal vs Chelsea", sport = "Premier League"), premierOnly))
        assertFalse(SportsLiveMySportsScope.shows(game("Bayern vs Dortmund", sport = "Bundesliga"), premierOnly))
        // No team list loaded: a bare "Soccer" game cannot be placed in the Premier League yet (J3).
        assertFalse(SportsLiveMySportsScope.shows(game("Arsenal vs Chelsea", sport = "Soccer"), premierOnly, TeamDirectory()))
    }

    @Test
    fun `a bare Football game shows under soccer or American football`() {
        val bare = game("Patriots vs Jets", sport = "Football")
        assertTrue(SportsLiveMySportsScope.shows(bare, MySports(mapOf(MySport.FOOTBALL to emptySet()))))
        assertTrue(SportsLiveMySportsScope.shows(bare, MySports(mapOf(MySport.SOCCER to emptySet()))))
        assertFalse(SportsLiveMySportsScope.shows(bare, MySports(mapOf(MySport.HOCKEY to emptySet()))))
        // "American Football" is football alone.
        assertFalse(SportsLiveMySportsScope.shows(game("Patriots vs Jets", sport = "American Football"), MySports(mapOf(MySport.SOCCER to emptySet()))))
    }

    @Test
    fun `a catalog name counts only when it names a single sport`() {
        assertEquals(SportsLiveSportKind.CRICKET, SportsLiveSportKind.single("Cricket Live"))
        assertEquals(SportsLiveSportKind.FOOTBALL, SportsLiveSportKind.single("American Football"))
        assertEquals(null, SportsLiveSportKind.single("Live Sports (Soccer, Tennis, F1)"))
        assertEquals(null, SportsLiveSportKind.single("Events"))
    }

    private fun game(name: String, sport: String?, catalog: String = "Events") = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "tv", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = "addon", addonName = "Sports", addonBaseUrl = "https://example.invalid", catalogKey = "addon:tv:live",
        catalogName = catalog, kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false,
        startMs = null, sport = sport, startHasTimeOfDay = false,
        teams = SportsLiveTeams.parse(name)
    )
}
