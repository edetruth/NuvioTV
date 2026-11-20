package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        // A competition ESPN's league index names has its own feed (#169).
        assertEquals(listOf("soccer/ger.2"), slugs("Soccer", "2. Bundesliga: HSV vs Schalke"))
        assertEquals(listOf("soccer/caf.champions"), slugs("Soccer", "CAF Champions League"))
    }

    @Test
    fun `a named competition beats a generic sport word`() {
        assertEquals(listOf("football/nfl"), slugs("Football", "Sunday Night Football: Chiefs vs Raiders", "NFL"))
        assertEquals(listOf("soccer/eng.1"), slugs("Football", "Arsenal vs Chelsea", "Premier League"))
    }

    @Test
    fun `women's and minor leagues never route to the pro feeds`() {
        // Women's leagues have their own feeds (#163).
        assertEquals(listOf("basketball/wnba"), slugs("WNBA", "Liberty vs Aces"))
        assertEquals(listOf("basketball/wnba"), slugs("Basketball", "Liberty vs Aces", "WNBA"))
        assertEquals(emptyList<String>(), slugs("Hockey", "Toronto vs Boston", "PWHL"))
        assertEquals(emptyList<String>(), slugs("Hockey", "AHL: Toronto Marlies vs Belleville"))
        assertEquals(emptyList<String>(), slugs("Ice Hockey", "ECHL"))
        // The G League has its own feed (#169), never the NBA's.
        assertEquals(listOf("basketball/nba-development"), slugs("Basketball", "NBA G League: Stockton vs Santa Cruz"))
        assertEquals(
            listOf("basketball/womens-college-basketball", "basketball/wnba"),
            slugs("Basketball", "Women's Basketball: Lakers")
        )
        // A women's competition ESPN lists (#169) is its own women's feed.
        assertEquals(listOf("soccer/eng.w.1"), slugs("Soccer", "Womens Super League"))
        assertEquals(listOf("hockey/nhl"), slugs("Hockey", "Maple Leafs vs Bruins"))
    }

    @Test
    fun `a College card naming no sport routes to every college feed`() {
        val both = listOf("basketball/mens-college-basketball", "basketball/womens-college-basketball", "football/college-football")
        assertEquals(both, slugs("COLLEGE", "Nevada vs Idaho"))
        assertEquals(both, slugs(null, "Nevada vs Idaho", "College"))
        // A sport word still picks one, and a pro sport word still routes nowhere for a college card.
        assertEquals(listOf("football/college-football"), slugs("College Football", "Nevada vs Idaho"))
        assertEquals(emptyList<String>(), slugs("COLLEGE", "Nevada vs Idaho", "Hockey"))
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

    private fun leagueItem(title: String, sport: String?, genres: List<String>, catalog: String) = SportsLiveItem(
        meta = MetaPreview(
            id = title, type = ContentType.UNKNOWN, rawType = "sport", name = title, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = genres
        ),
        addonId = "a", addonName = "A", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = catalog,
        kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false, startMs = null, sport = sport,
        teams = SportsLiveTeams.parse(title)
    )

    @Test
    fun `a mixed catalog name routes but never excludes`() {
        val mixed = "Live Sports (Soccer, Tennis, F1)"
        assertEquals(listOf("soccer/all"), EspnLeagueMap.leaguesFor(leagueItem("Arsenal vs Chelsea", "Soccer", listOf("Soccer"), mixed)).map { it.path })
        assertEquals(emptyList<String>(), EspnLeagueMap.leaguesFor(leagueItem("Sinner vs Alcaraz", "Tennis", listOf("Tennis"), mixed)).map { it.path })
        // The catalog still routes when the card itself says nothing.
        assertEquals(listOf("soccer/eng.1"), EspnLeagueMap.leaguesFor(leagueItem("Arsenal vs Chelsea", null, emptyList(), "Premier League")).map { it.path })
    }

    @Test
    fun `short excluded tokens are whole words only`() {
        assertEquals(listOf("soccer/all"), slugs("Soccer", "Odisha FC vs Kerala Blasters"))
        assertEquals(listOf("soccer/all"), slugs("Soccer", "Rot Weiss Ahlen vs Wuppertal"))
        assertEquals(listOf("soccer/all"), slugs("Soccer", "Ipswich vs Norwich", "Football"))
        assertEquals(emptyList<String>(), slugs(null, "ODI: India vs Australia"))
        assertEquals(emptyList<String>(), slugs(null, "IPL: CSK vs MI"))
    }

    @Test
    fun `a women's card routes to the women's feeds only`() {
        val ncaaw = listOf("basketball/womens-college-basketball")
        assertEquals(ncaaw, slugs("NCAAW", "Purdue at Florida State"))
        assertEquals(ncaaw, slugs("COLLEGE", "Purdue at Florida State", "Women's"))
        assertEquals(ncaaw, slugs("Women's College Basketball", "Purdue at Florida State"))
        assertEquals(ncaaw, slugs("College Basketball", "Purdue W vs Florida State W"))
        assertEquals(ncaaw, slugs("WBB", "Purdue vs Florida State", "NCAA"))
        assertEquals(listOf("soccer/usa.nwsl"), slugs("Soccer", "NWSL - Portland Thorns vs Orlando Pride"))
        // A women's sport with no women's feed still gets none; a "W" side on soccer/all keeps it, as before.
        assertEquals(emptyList<String>(), slugs("Ice Hockey", "Women's Hockey: Canada vs USA"))
        assertEquals(listOf("soccer/all"), slugs("Soccer", "Arsenal W vs Chelsea W"))
    }

    @Test
    fun `a men's card routes to the men's feeds only`() {
        assertEquals(listOf("basketball/mens-college-basketball", "football/college-football"), slugs("COLLEGE", "Purdue at Florida State", "Men's"))
        assertEquals(listOf("basketball/mens-college-basketball"), slugs("NCAAM", "Purdue at Florida State"))
        assertEquals(listOf("basketball/mens-college-basketball"), slugs("College Basketball", "Purdue at Florida State"))
        assertEquals(listOf("basketball/nba"), slugs("Basketball", "Men's Basketball: Celtics vs Knicks", "NBA"))
        assertEquals(EspnLeagueMap.COLLEGE_BASKETBALL, EspnLeagueMap.collegeFallbackFor("Basketball", listOf("Duke vs North Carolina")))
    }

    @Test
    fun `women's and men's copies of one pairing are told apart`() {
        fun card(sport: String) = SportsLiveItem(
            meta = MetaPreview(
                id = sport, type = ContentType.UNKNOWN, rawType = "sport", name = "Purdue at Florida State", poster = null,
                posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null, releaseInfo = null,
                imdbRating = null, genres = listOf(sport)
            ),
            addonId = "a", addonName = "A", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Events",
            kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false, startMs = null, startHasTimeOfDay = false, sport = sport
        )
        assertEquals(true, EspnLeagueMap.otherGender(card("NCAAW"), card("NCAAM")))
        assertEquals(false, EspnLeagueMap.otherGender(card("NCAAW"), card("COLLEGE")))
        assertEquals(false, EspnLeagueMap.otherGender(card("Basketball"), card("NCAAM")))
    }

    @Test
    fun `a curly apostrophe still reads as women's`() {
        // Read as women's: the women's competition's own feed (#169), never a men's one.
        assertEquals(listOf("soccer/eng.w.1"), slugs("Soccer", "Women’s Super League: Arsenal vs Chelsea"))
    }

    @Test
    fun `only a bare basketball or football card gets a college fallback (#66)`() {
        fun fallback(sport: String?, vararg hints: String) = EspnLeagueMap.collegeFallbackFor(sport, hints.toList())?.path
        assertEquals("basketball/mens-college-basketball", fallback("Basketball", "Duke vs North Carolina"))
        assertEquals("football/college-football", fallback("American Football", "Alabama vs Auburn"))
        assertEquals("football/college-football", fallback("Football", "Alabama vs Auburn"))
        // A competition named: it decides, no fallback.
        assertNull(fallback("Basketball", "NBA: Celtics vs Knicks"))
        assertNull(fallback("Basketball", "College Basketball: Duke vs UNC"))
        assertNull(fallback("Football", "Premier League: Arsenal vs Chelsea"))
        // Soccer said outright, women's, youth-excluded and other sports: none.
        assertNull(fallback("Soccer", "Arsenal vs Chelsea"))
        assertNull(fallback("Football", "Arsenal vs Chelsea", "Soccer"))
        assertNull(fallback("Women's Basketball", "UConn vs South Carolina"))
        assertNull(fallback("Basketball", "WNBA: Aces vs Liberty"))
        assertNull(fallback("Hockey", "Rangers vs Devils"))
        assertNull(fallback(null, "Mystery Match"))
    }

}
