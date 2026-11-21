package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.scores.DirectoryTeam
import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
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

/** #159: with leagues named, a game whose league cannot be told is placed by its teams in those leagues' lists. */
class SportsLiveMySportsTeamListTest {

    private val premierLeague = EspnLeague("soccer", "eng.1")

    // Basketball: the NBA; soccer: the Premier League.
    private val picked = MySports(
        mapOf(MySport.BASKETBALL to setOf(MyLeague.NBA), MySport.SOCCER to setOf(MyLeague.PREMIER_LEAGUE))
    )

    private fun loaded(vararg leagues: EspnLeague) = TeamDirectory().apply {
        if (premierLeague in leagues) {
            put(premierLeague, listOf(team("359", "Arsenal"), team("363", "Chelsea")), fetchedAt = 1L)
        }
        if (EspnLeagueMap.NBA in leagues) {
            put(EspnLeagueMap.NBA, listOf(team("13", "Los Angeles Lakers", "Lakers"), team("2", "Boston Celtics", "Celtics")), fetchedAt = 1L)
        }
    }

    private val both = loaded(premierLeague, EspnLeagueMap.NBA)

    @Test
    fun `an unnamed-league game whose teams no picked league knows is hidden`() {
        assertFalse(SportsLiveMySportsScope.shows(game("Beltinci vs Celje", "Football"), picked, both))
        assertFalse(SportsLiveMySportsScope.shows(game("Cong An Ha Noi vs Johor DT", "Football"), picked, both))
        assertFalse(SportsLiveMySportsScope.shows(game("Al Wasl vs Hatta", "Soccer"), picked, both))
        // A bare "Basketball" of two college teams, with only the NBA named.
        assertFalse(SportsLiveMySportsScope.shows(game("Duke vs Kansas", "Basketball"), picked, both))
    }

    @Test
    fun `one team of a picked league shows the game`() {
        assertTrue(SportsLiveMySportsScope.shows(game("Arsenal vs Beltinci", "Football"), picked, both))
        assertTrue(SportsLiveMySportsScope.shows(game("Celje vs Chelsea", "Football"), picked, both))
        assertTrue(SportsLiveMySportsScope.shows(game("Lakers vs Maccabi Tel Aviv", "Basketball"), picked, both))
    }

    @Test
    fun `a followed team's game always shows`() {
        assertTrue(SportsLiveMySportsScope.shows(game("Beltinci vs Celje", "Football").copy(followed = true), picked, both))
    }

    @Test
    fun `a game whose league cannot be told is listed under All sports only`() {
        val soccerOnly = loaded(premierLeague)
        // J3: the NBA list is not there, so the basketball game cannot be placed: not under My sports.
        assertFalse(SportsLiveMySportsScope.shows(game("Duke vs Kansas", "Basketball"), picked, soccerOnly))
        assertFalse(SportsLiveMySportsScope.shows(game("Beltinci vs Celje", "Football"), picked, soccerOnly))
        assertFalse(SportsLiveMySportsScope.shows(game("Beltinci vs Celje", "Football"), picked, TeamDirectory()))
        // The TV report's games: soccer of an unknown league, with leagues named for soccer.
        assertFalse(SportsLiveMySportsScope.shows(game("Khor Fakkan vs Al-Jazira", "Soccer"), picked, both))
        assertFalse(SportsLiveMySportsScope.shows(game("Csikszereda Miercurea Ciuc vs Corvinul Hunedoara", "Soccer"), picked, TeamDirectory()))
        // A placed game of a named league still shows before every list is in.
        assertTrue(SportsLiveMySportsScope.shows(game("Arsenal vs Chelsea", "Football"), picked, soccerOnly))
        // A sport picked whole keeps them all.
        val wholeSoccer = MySports(mapOf(MySport.SOCCER to emptySet()))
        assertTrue(SportsLiveMySportsScope.shows(game("Khor Fakkan vs Al-Jazira", "Soccer"), wholeSoccer, TeamDirectory()))
    }

    @Test
    fun `a sport with no league named, and All sports, keep every game`() {
        val anySoccer = MySports(mapOf(MySport.SOCCER to emptySet()))
        assertTrue(SportsLiveMySportsScope.shows(game("Beltinci vs Celje", "Football"), anySoccer, both))
        assertTrue(SportsLiveMySportsScope.shows(game("Beltinci vs Celje", "Football"), MySports.DEFAULT, both))
        // Internationals has no team list: alone, it cannot rule a game out.
        val internationals = MySports(mapOf(MySport.SOCCER to setOf(MyLeague.INTERNATIONALS)))
        assertTrue(SportsLiveMySportsScope.shows(game("Beltinci vs Celje", "Football"), internationals, both))
    }

    @Test
    fun `the hidden count is every game the team lists rule out`() {
        val items = listOf(
            game("Beltinci vs Celje", "Football"),
            game("Arsenal vs Chelsea", "Football"),
            game("Cong An Ha Noi vs Johor DT", "Football"),
            game("Al Wasl vs Hatta", "Football").copy(followed = true),
            game("Lakers vs Celtics", "NBA")
        )
        val scoped = SportsLiveMySportsScope.scope(items, picked, both)
        assertEquals(listOf(items[1], items[3], items[4]), scoped)
        assertEquals(2, items.size - scoped.size)
        assertEquals(items, SportsLiveMySportsScope.scope(items, MySports.DEFAULT, both))
    }

    @Test
    fun `the named leagues' team lists are the ones fetched`() {
        assertEquals(listOf(EspnLeagueMap.NBA, premierLeague), picked.teamListLeagues())
        assertEquals(emptyList<EspnLeague>(), MySports(mapOf(MySport.SOCCER to setOf(MyLeague.INTERNATIONALS))).teamListLeagues())
        assertEquals(emptyList<EspnLeague>(), MySports(mapOf(MySport.SOCCER to emptySet())).teamListLeagues())
    }

    private fun team(id: String, display: String, short: String = display) =
        DirectoryTeam(id, display, short, null, null, short, "https://a.espncdn.com/i/teamlogos/x/500/$id.png")

    private fun game(name: String, sport: String?) = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "tv", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = "addon", addonName = "Sports", addonBaseUrl = "https://example.invalid", catalogKey = "addon:tv:live",
        catalogName = "Events", kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false,
        startMs = null, sport = sport, startHasTimeOfDay = false,
        teams = SportsLiveTeams.parse(name)
    )
}
