package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import org.junit.Assert.assertEquals
import org.junit.Test

class EspnLeagueMapTest {

    private fun slugs(sport: String?, vararg hints: String) =
        EspnLeagueMap.leaguesFor(sport, hints.toList()).map { it.path }

    @Test
    fun `US leagues route by keyword`() {
        assertEquals(listOf("basketball/nba"), slugs(null, "NBA: Pacers @ 76ers"))
        assertEquals(listOf("football/nfl"), slugs("NFL", "Seahawks vs Raiders"))
        assertEquals(listOf("hockey/nhl"), slugs(null, "Bruins vs Rangers", "NHL"))
        assertEquals(listOf("baseball/mlb"), slugs("MLB", "Yankees vs Red Sox"))
    }

    @Test
    fun `college leagues only when the card says so`() {
        assertEquals(listOf("football/college-football"), slugs(null, "NCAAF: Ohio State vs Michigan"))
        assertEquals(listOf("football/college-football"), slugs("College Football", "Alabama vs Auburn"))
        assertEquals(listOf("basketball/mens-college-basketball"), slugs("NCAA Basketball", "Duke vs UNC"))
        assertEquals(listOf("basketball/nba"), slugs("Basketball", "Duke vs UNC"))
        assertEquals(listOf("football/nfl"), slugs("American Football", "Chiefs vs Raiders"))
    }

    @Test
    fun `soccer competitions route by keyword`() {
        val cases = mapOf(
            "Premier League: Arsenal vs Chelsea" to listOf("soccer/eng.1"),
            "EPL - Arsenal v Chelsea" to listOf("soccer/eng.1"),
            "LaLiga: Real Madrid vs Barcelona" to listOf("soccer/esp.1"),
            "La Liga" to listOf("soccer/esp.1"),
            "Serie A: Inter vs Milan" to listOf("soccer/ita.1"),
            "Bundesliga: Bayern vs Dortmund" to listOf("soccer/ger.1"),
            "Ligue 1: PSG vs OM" to listOf("soccer/fra.1"),
            "UEFA Champions League" to listOf("soccer/uefa.champions"),
            "UCL: Inter vs Bayern" to listOf("soccer/uefa.champions"),
            "UEFA Europa League" to listOf("soccer/uefa.europa"),
            "UEFA Europa Conference League" to listOf("soccer/uefa.europa.conf"),
            "UEFA Nations League" to listOf("soccer/uefa.nations"),
            "MLS: LAFC vs Galaxy" to listOf("soccer/usa.1"),
            "AFCON Qualifiers: NAM vs CMR" to listOf("soccer/caf.nations_qual"),
            "Africa Cup of Nations Qualifying" to listOf("soccer/caf.nations_qual"),
            "AFCON 2027: Morocco vs Egypt" to listOf("soccer/caf.nations", "soccer/caf.nations_qual"),
            "International Friendly: Brazil vs Japan" to listOf("soccer/fifa.friendly"),
            "World Cup Qualifier (UEFA): England vs Serbia" to listOf("soccer/fifa.worldq.uefa"),
            "CONMEBOL World Cup Qualifiers" to listOf("soccer/fifa.worldq.conmebol"),
            "World Cup Qualifying - CAF" to listOf("soccer/fifa.worldq.caf"),
            "Côte d'Ivoire vs Mali - Africa Cup of Nations" to listOf("soccer/caf.nations", "soccer/caf.nations_qual")
        )
        cases.forEach { (title, expected) -> assertEquals(title, expected, slugs("Soccer", title)) }
        assertEquals(6, slugs(null, "World Cup Qualifier: X vs Y").size)
    }

    @Test
    fun `other competitions and plain soccer fall back to soccer all`() {
        assertEquals(listOf("soccer/all"), slugs("Soccer", "Celtic vs Rangers"))
        assertEquals(listOf("soccer/all"), slugs("Football", "Arsenal vs Chelsea"))
        assertEquals(listOf("soccer/all"), slugs(null, "Football: Egyptian Premier League"))
        assertEquals(listOf("soccer/all"), slugs("Soccer", "2. Bundesliga: HSV vs Schalke"))
        assertEquals(listOf("soccer/all"), slugs("Soccer", "CAF Champions League"))
    }

    @Test
    fun `a named competition beats a generic sport word`() {
        assertEquals(listOf("football/nfl"), slugs("Football", "Sunday Night Football: Chiefs vs Raiders", "NFL"))
        assertEquals(listOf("soccer/eng.1"), slugs("Football", "Arsenal vs Chelsea", "Premier League"))
    }

    @Test
    fun `women's and minor leagues never route to the pro feeds`() {
        assertEquals(emptyList<String>(), slugs("WNBA", "Liberty vs Aces"))
        assertEquals(emptyList<String>(), slugs("Basketball", "Liberty vs Aces", "WNBA"))
        assertEquals(emptyList<String>(), slugs("Hockey", "Toronto vs Boston", "PWHL"))
        assertEquals(emptyList<String>(), slugs("Hockey", "AHL: Toronto Marlies vs Belleville"))
        assertEquals(emptyList<String>(), slugs("Ice Hockey", "ECHL"))
        assertEquals(emptyList<String>(), slugs("Basketball", "NBA G League: Stockton vs Santa Cruz"))
        assertEquals(emptyList<String>(), slugs("Basketball", "Women's Basketball: Lakers"))
        assertEquals(emptyList<String>(), slugs("Soccer", "Womens Super League"))
        assertEquals(listOf("hockey/nhl"), slugs("Hockey", "Maple Leafs vs Bruins"))
    }

    @Test
    fun `college cards route only to college feeds`() {
        assertEquals(listOf("football/college-football"), slugs("College American Football", "Alabama vs Auburn"))
        assertEquals(listOf("football/college-football"), slugs("American Football", "NCAA Football: Alabama vs Auburn"))
        assertEquals(emptyList<String>(), slugs("American Football", "Alabama vs Auburn", "NCAA"))
        assertEquals(emptyList<String>(), slugs("Basketball", "College Hoops: Duke vs UNC", "NCAA"))
    }

    @Test
    fun `a specific sport genre beats a generic Football sport`() {
        assertEquals(listOf("football/nfl"), slugs("Football", "Chiefs vs Raiders", "American Football"))
        assertEquals(emptyList<String>(), slugs("Football", "Alabama vs Auburn", "NCAA"))
    }

    @Test
    fun `unsupported sports in any hint get no feed`() {
        assertEquals(emptyList<String>(), slugs(null, "Rugby World Cup Final: NZ vs SA"))
        assertEquals(emptyList<String>(), slugs(null, "ICC T20 World Cup: India vs Pakistan"))
        assertEquals(emptyList<String>(), slugs("Soccer", "Arsenal vs Chelsea", "Tennis"))
    }

    @Test
    fun `unsupported or unknown sports get no feed`() {
        assertEquals(emptyList<String>(), slugs("Cricket", "Bangladesh Premier League: A vs B"))
        assertEquals(emptyList<String>(), slugs("Tennis", "Sinner vs Alcaraz"))
        assertEquals(emptyList<String>(), slugs("UFC", "UFC 334"))
        assertEquals(emptyList<String>(), slugs(null, "Arsenal vs Chelsea", "Live Events"))
    }

    @Test
    fun `an item routes by its sport, title, genres and catalog`() {
        val item = SportsLiveItem(
            meta = MetaPreview(
                id = "1", type = ContentType.UNKNOWN, rawType = "sport", name = "Arsenal vs Chelsea", poster = null,
                posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
                releaseInfo = null, imdbRating = null, genres = listOf("Premier League")
            ),
            addonId = "a", addonName = "A", addonBaseUrl = "https://example.invalid", catalogKey = "k",
            catalogName = "Live", kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false, startMs = null,
            sport = "Premier League", teams = SportsLiveTeams.parse("Arsenal vs Chelsea")
        )
        assertEquals(listOf(EspnLeague("soccer", "eng.1")), EspnLeagueMap.leaguesFor(item))
    }
}
