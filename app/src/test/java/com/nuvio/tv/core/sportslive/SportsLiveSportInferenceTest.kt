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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
        team("Siena Saints", "Siena", "Siena", "SIE"),
        team("Rutgers Scarlet Knights", "Rutgers", "Rutgers", "RUTG"),
        team("Notre Dame Fighting Irish", "Notre Dame", "Notre Dame", "ND"),
        team("Illinois Fighting Illini", "Illinois", "Illinois", "ILL"),
        team("Duke Blue Devils", "Duke", "Duke", "DUKE"),
        team("Tennessee Volunteers", "Tennessee", "Tennessee", "TENN")
    )
    private val nba = listOf(team("Boston Celtics", "Celtics", "Boston", "BOS"), team("Miami Heat", "Heat", "Miami", "MIA"))

    /** Every inference league known; the named ones with their teams, the rest empty but loaded. */
    private fun directory(vararg lists: Pair<EspnLeague, List<DirectoryTeam>>): TeamDirectory {
        val dir = TeamDirectory()
        val given = lists.toMap()
        (SportsLiveSportInference.leagues + EspnLeagueMap.COLLEGE_BASKETBALL + EspnLeagueMap.COLLEGE_BASKETBALL_WOMEN + EspnLeagueMap.COLLEGE_FOOTBALL).forEach { league ->
            dir.put(league, given[league] ?: listOf(team("Placeholder ${league.league}", "Placeholder", "Nowhere", "PLC")), 0L)
        }
        return dir
    }

    private val full = directory(
        EspnLeagueMap.NHL to nhl, EspnLeagueMap.MLB to mlb, EspnLeague("soccer", "bra.1") to bra,
        EspnLeagueMap.NBA to nba, EspnLeagueMap.COLLEGE_BASKETBALL to ncaam
    )

    private fun card(
        name: String,
        sport: String? = null,
        catalog: String = "Live",
        genres: List<String> = emptyList(),
        kind: SportsLiveKind = SportsLiveKind.EVENTS
    ) = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = genres
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = catalog,
        kind = kind, isLive = kind == SportsLiveKind.EVENTS, isChannel = false, startMs = null, sport = sport,
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
        // Rangers play in the NHL and in MLB: never a guess by one side.
        assertNull(SportsLiveSportInference.leagueOf(card("Rangers vs Kings"), full))
        // Only the NHL holds both sides: My sports places the game there, and its tag says so (TV report #5).
        assertEquals("NHL", SportsLiveSportInference.infer(card("Rangers vs Kings"), full).sport)
        // A pair two leagues both hold stays untagged.
        val twice = directory(
            EspnLeagueMap.NHL to nhl,
            EspnLeagueMap.MLB to mlb + team("Kansas City Kings", "Kings", "Kansas City", "KCK")
        )
        val item = card("Rangers vs Kings")
        assertSame(item, SportsLiveSportInference.infer(item, twice))
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

    // A "College" card naming no sport (#145)

    @Test
    fun `a College card of two teams one college league knows infers that league`() {
        val dir = directory(
            EspnLeagueMap.COLLEGE_BASKETBALL to listOf(
                team("Nevada Wolf Pack", "Nevada", "Nevada", "NEV"),
                team("Idaho Vandals", "Idaho", "Idaho", "IDHO")
            ),
            EspnLeagueMap.COLLEGE_FOOTBALL to listOf(team("Nevada Wolf Pack", "Nevada", "Nevada", "NEV"))
        )
        val inferred = SportsLiveSportInference.infer(card("Nevada vs Idaho", sport = "COLLEGE", genres = listOf("COLLEGE")), dir)

        assertEquals("NCAAM", inferred.sport)
        // Now on the college basketball feed alone: its scores and logos follow.
        assertEquals(listOf(EspnLeagueMap.COLLEGE_BASKETBALL), EspnLeagueMap.leaguesFor(inferred))
    }

    @Test
    fun `a College card both college leagues know infers nothing`() {
        val both = listOf(team("Nevada Wolf Pack", "Nevada", "Nevada", "NEV"), team("Idaho Vandals", "Idaho", "Idaho", "IDHO"))
        val dir = directory(EspnLeagueMap.COLLEGE_BASKETBALL to both, EspnLeagueMap.COLLEGE_FOOTBALL to both)

        assertNull(SportsLiveSportInference.leagueOf(card("Nevada vs Idaho", sport = "COLLEGE"), dir))
    }

    @Test
    fun `a College card both men's and women's basketball know infers nothing`() {
        // Same schools, two games (#163): only the score feed's start can tell them apart.
        val both = listOf(team("Nevada Wolf Pack", "Nevada", "Nevada", "NEV"), team("Idaho Vandals", "Idaho", "Idaho", "IDHO"))
        val dir = directory(EspnLeagueMap.COLLEGE_BASKETBALL to both, EspnLeagueMap.COLLEGE_BASKETBALL_WOMEN to both)
        assertNull(SportsLiveSportInference.leagueOf(card("Nevada vs Idaho", sport = "COLLEGE"), dir))
    }

    @Test
    fun `a women's college card keeps the NCAAW tag`() {
        val both = listOf(team("Nevada Wolf Pack", "Nevada", "Nevada", "NEV"), team("Idaho Vandals", "Idaho", "Idaho", "IDHO"))
        val dir = directory(EspnLeagueMap.COLLEGE_BASKETBALL to both, EspnLeagueMap.COLLEGE_BASKETBALL_WOMEN to both)
        val inferred = SportsLiveSportInference.infer(card("Nevada vs Idaho", sport = "College Basketball", genres = listOf("Women's")), dir)
        assertEquals("NCAAW", inferred.sport)
        assertEquals(listOf(EspnLeagueMap.COLLEGE_BASKETBALL_WOMEN), EspnLeagueMap.leaguesFor(inferred))
    }

    @Test
    fun `a College card of pro teams infers nothing`() {
        assertNull(SportsLiveSportInference.leagueOf(card("Devils v Canadiens", sport = "COLLEGE"), full))
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
    fun `a bare Basketball card of two G League teams infers the G League`() {
        val gLeague = listOf(
            team("Coachella Valley Lakers", "Lakers", "Coachella Valley", "CVL"),
            team("Stockton Kings", "Kings", "Stockton", "STO")
        )
        val dir = directory(EspnLeagueMap.NBA to nba, EspnLeagueMap.COLLEGE_BASKETBALL to ncaam, EspnLeagueMap.G_LEAGUE to gLeague)
        val inferred = SportsLiveSportInference.infer(card("Coachella Valley Lakers vs Stockton Kings", sport = "Basketball"), dir)
        assertEquals("NBA G League", inferred.sport)
        assertEquals(listOf(EspnLeagueMap.G_LEAGUE), EspnLeagueMap.leaguesFor(inferred))
        // NBA teams on a bare Basketball card are still the NBA's.
        assertEquals("NBA", SportsLiveSportInference.infer(card("Celtics vs Heat", sport = "Basketball"), dir).sport)
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

    @Test
    fun `college replays with sport Basketball infer NCAAM like live cards`() {
        val names = listOf(
            "Rutgers Scarlet Knights vs Notre Dame Fighting Irish",
            "Illinois vs Duke",
            "Michigan St Spartans vs Tennessee Volunteers"
        )
        for (name in names) {
            val inferred = SportsLiveSportInference.infer(card(name, sport = "Basketball", kind = SportsLiveKind.REPLAY), full)
            assertEquals(name, "NCAAM", inferred.sport)
            assertEquals(listOf(EspnLeagueMap.COLLEGE_BASKETBALL), EspnLeagueMap.leaguesFor(inferred))
        }
    }

    @Test
    fun `a replay keeps the same precision rules`() {
        // Two colleges answer to "Miami": a replay is no more guessed than a live card.
        assertNull(SportsLiveSportInference.leagueOf(card("Miami vs Arizona", sport = "Basketball", kind = SportsLiveKind.REPLAY), full))
        assertEquals("NBA", SportsLiveSportInference.infer(card("Celtics vs Heat", sport = "Basketball", kind = SportsLiveKind.REPLAY), full).sport)
    }

    @Test
    fun `a channels catalog item is never inferred`() {
        val item = card("Devils v Canadiens", kind = SportsLiveKind.CHANNELS)
        assertSame(item, SportsLiveSportInference.infer(item, full))
    }

    // ---- Two schools on a bare "Basketball" card (item 4) ----

    private val pitt = team("Pittsburgh Panthers", "Pittsburgh", "Pittsburgh", "PITT")
    // ESPN's short name is "C Michigan": the school answers to its location.
    private val cmu = DirectoryTeam("cmu", "Central Michigan Chippewas", "C Michigan", "CMU", "Central Michigan", "Chippewas", "https://a.espncdn.com/i/teamlogos/ncaa/500/2117.png")

    @Test
    fun `Pittsburgh vs Central Michigan on a bare Basketball card is NCAAM with both logos`() {
        val dir = directory(EspnLeagueMap.NBA to nba, EspnLeagueMap.COLLEGE_BASKETBALL to listOf(pitt, cmu))
        val item = card("Pittsburgh vs Central Michigan", sport = "Basketball")
        assertEquals(listOf(EspnLeagueMap.COLLEGE_BASKETBALL), SportsLiveSportInference.collegeLeaguesOf(item, dir))
        val inferred = SportsLiveSportInference.infer(item, dir)
        assertEquals("NCAAM", inferred.sport)
        assertEquals(listOf(EspnLeagueMap.COLLEGE_BASKETBALL), EspnLeagueMap.leaguesFor(inferred))
        val logos = dir.forCard(inferred, "Pittsburgh", "Central Michigan")!!
        assertEquals("https://a.espncdn.com/i/teamlogos/ncaa/500/2117.png", logos.second.logo)
    }

    @Test
    fun `two schools both college lists hold are neutral college, on both feeds`() {
        val both = listOf(pitt, cmu)
        val dir = directory(EspnLeagueMap.NBA to nba, EspnLeagueMap.COLLEGE_BASKETBALL to both, EspnLeagueMap.COLLEGE_BASKETBALL_WOMEN to both)
        val item = card("Pittsburgh vs Central Michigan", sport = "Basketball")
        // Never a guess at the men's game.
        assertNull(SportsLiveSportInference.leagueOf(item, dir))
        val inferred = SportsLiveSportInference.infer(item, dir)
        assertEquals(EspnLeagueMap.COLLEGE_BASKETBALL_EITHER, inferred.sport)
        assertEquals(listOf(EspnLeagueMap.COLLEGE_BASKETBALL, EspnLeagueMap.COLLEGE_BASKETBALL_WOMEN), EspnLeagueMap.leaguesFor(inferred))
        assertEquals("COLLEGE", SportsLiveLeagueTags.of(inferred.sport!!))
        // A school's logos are one on both lists.
        assertEquals(pitt.logo, dir.forCard(inferred, "Pittsburgh", "Central Michigan")!!.first.logo)
        // The women's list not loaded yet: neutral too.
        val menOnly = TeamDirectory().apply {
            listOf(EspnLeagueMap.NBA to nba, EspnLeagueMap.G_LEAGUE to emptyList(), EspnLeagueMap.WNBA to emptyList(), EspnLeagueMap.COLLEGE_BASKETBALL to both)
                .forEach { (league, teams) -> put(league, teams, 0L) }
        }
        assertEquals(EspnLeagueMap.COLLEGE_BASKETBALL_EITHER, SportsLiveSportInference.infer(item, menOnly).sport)
    }

    @Test
    fun `a school that shares a pro team's name keeps the strict rules`() {
        // A pro list answering to "Pittsburgh" too.
        val pros = nba + team("Pittsburgh Pros", "Pittsburgh", "Pittsburgh", "PGH")
        val dir = directory(EspnLeagueMap.NBA to pros, EspnLeagueMap.COLLEGE_BASKETBALL to listOf(pitt, cmu))
        val item = card("Pittsburgh vs Central Michigan", sport = "Basketball")
        assertNull(SportsLiveSportInference.collegeLeaguesOf(item, dir))
        assertNull(SportsLiveSportInference.leagueOf(item, dir))
        // A pro list not loaded: no inference either.
        val partial = TeamDirectory().apply { put(EspnLeagueMap.COLLEGE_BASKETBALL, listOf(pitt, cmu), 0L) }
        assertNull(SportsLiveSportInference.collegeLeaguesOf(card("Pittsburgh vs Central Michigan", sport = "Basketball"), partial))
    }

    @Test
    fun `an unidentified college game is listed only when My sports takes college basketball`() {
        val dir = directory(EspnLeagueMap.NBA to nba, EspnLeagueMap.COLLEGE_BASKETBALL to listOf(pitt, cmu))
        val item = card("Pittsburgh vs Central Michigan", sport = "Basketball")
        assertEquals(listOf(EspnLeagueMap.COLLEGE_BASKETBALL), SportsLiveMySportsScope.directoryLeaguesOf(item, dir))
    }

    // ---- One classification for My sports and the row tag (TV report #5) ----

    private val bundesliga = listOf(
        team("Bayern Munich", "Bayern", "Bayern Munich", "MUN"),
        team("FC Cologne", "Cologne", "FC Cologne", "KOE"),
        team("Bayer Leverkusen", "Leverkusen", "Bayer Leverkusen", "B04")
    )

    @Test
    fun `an untagged Bayern vs Koln gets the Bundesliga tag even with a list not loaded`() {
        // Only the Bundesliga list loaded: the strict rule (every list loaded) infers nothing, My sports still places it.
        val dir = TeamDirectory().apply { put(EspnLeague("soccer", "ger.1"), bundesliga, 0L) }
        for (name in listOf("Bayern München vs 1. FC Köln", "FC Bayern vs Koln", "Bayern Munich vs FC Cologne", "Bayern vs Köln")) {
            val item = card(name)
            assertEquals(name, listOf(EspnLeague("soccer", "ger.1")), SportsLiveMySportsScope.directoryLeaguesOf(item, dir))
            val inferred = SportsLiveSportInference.infer(item, dir)
            assertEquals(name, "Bundesliga", inferred.sport)
            assertEquals(name, listOf(EspnLeague("soccer", "ger.1")), EspnLeagueMap.leaguesFor(inferred))
            assertEquals(name, "BUNDESLIGA", SportsLiveTags.short("BUNDESLIGA", SportsLiveSportKind.tagOf(inferred)))
        }
    }

    @Test
    fun `every game My sports places in one league shows that league's tag`() {
        val dir = directory(
            EspnLeagueMap.NHL to nhl, EspnLeagueMap.MLB to mlb, EspnLeague("soccer", "bra.1") to bra,
            EspnLeague("soccer", "ger.1") to bundesliga, EspnLeagueMap.NBA to nba
        )
        val names = listOf(
            "Devils v Canadiens", "Rangers vs Kings", "Flamengo v Athletico", "Bayern vs Köln", "Celtics vs Heat",
            "Rangers vs Royals", "Bayer Leverkusen vs Bayern Munich"
        )
        for (name in names) {
            val item = card(name)
            val placed = SportsLiveMySportsScope.directoryLeaguesOf(item, dir).singleOrNull() ?: continue
            val tag = SportsLiveSportInference.LEAGUES.first { it.first == placed }.second
            assertEquals(name, tag, SportsLiveSportInference.infer(item, dir).sport)
        }
    }

    @Test
    fun `a women's card is never tagged a men's league by its teams`() {
        val dir = TeamDirectory().apply { put(EspnLeague("soccer", "ger.1"), bundesliga, 0L) }
        val item = card("Bayern vs Köln", genres = listOf("Women's"))
        assertSame(item, SportsLiveSportInference.infer(item, dir))
    }

    // ---- Schools addons name otherwise than ESPN (TV report #5) ----

    private val fsu = team("Florida State Seminoles", "Florida St", "Florida State", "FSU")
    private val greenBay = team("Green Bay Phoenix", "Green Bay", "Green Bay", "GB")
    private val mtsu = team("Middle Tennessee Blue Raiders", "MTSU", "Middle Tennessee", "MTSU")
    private val temple = team("Temple Owls", "Temple", "Temple", "TEM")
    private val badgers = team("Wisconsin Badgers", "Wisconsin", "Wisconsin", "WIS")

    @Test
    fun `Florida State vs Wisconsin-Green Bay and Middle Tennessee State vs Temple are NCAAM, not Basketball`() {
        val dir = directory(EspnLeagueMap.NBA to nba, EspnLeagueMap.COLLEGE_BASKETBALL to listOf(fsu, greenBay, mtsu, temple, badgers))
        for (name in listOf("Florida State vs Wisconsin-Green Bay", "FSU vs UW-Green Bay", "Middle Tennessee State vs Temple", "Middle Tenn. St. vs Temple")) {
            val inferred = SportsLiveSportInference.infer(card(name, sport = "Basketball"), dir)
            assertEquals(name, "NCAAM", inferred.sport)
            assertEquals(name, listOf(EspnLeagueMap.COLLEGE_BASKETBALL), EspnLeagueMap.leaguesFor(inferred))
        }
        assertEquals("GB", dir.resolve(EspnLeagueMap.COLLEGE_BASKETBALL, "Wisconsin-Green Bay")?.abbreviation)
        assertEquals("MTSU", dir.resolve(EspnLeagueMap.COLLEGE_BASKETBALL, "Middle Tennessee State")?.abbreviation)
        // Plain "Wisconsin" stays the Badgers, never Green Bay.
        assertEquals("WIS", dir.resolve(EspnLeagueMap.COLLEGE_BASKETBALL, "Wisconsin")?.abbreviation)
        assertFalse(SportsLiveTeams.matches("Wisconsin", "Green Bay Phoenix"))
    }

    @Test
    fun `a game My sports places in several leagues of one sport shows the sport word`() {
        // Bayern and Koln in the Bundesliga and the Champions League lists: no one league, but surely soccer (live.20).
        val dir = TeamDirectory().apply {
            put(EspnLeague("soccer", "ger.1"), bundesliga, 0L)
            put(EspnLeague("soccer", "uefa.champions"), bundesliga, 0L)
        }
        val item = card("Bayern vs Köln")
        assertEquals(2, SportsLiveMySportsScope.directoryLeaguesOf(item, dir).size)
        val inferred = SportsLiveSportInference.infer(item, dir)
        assertEquals("Soccer", inferred.sport)
        assertEquals(SportsLiveSportKind.SOCCER, SportsLiveSportKind.plainTagOf(inferred))
        assertEquals("SOCCER", SportsLiveTags.short("SOCCER", SportsLiveSportKind.tagOf(inferred)))
    }

    @Test
    fun `a pair leagues of two sports hold gets no sport word`() {
        val dir = TeamDirectory().apply {
            put(EspnLeagueMap.NHL, nhl, 0L)
            put(EspnLeagueMap.MLB, mlb + team("Kansas City Kings", "Kings", "Kansas City", "KCK"), 0L)
        }
        val item = card("Rangers vs Kings")
        assertNull(SportsLiveSportInference.heldSport(item, dir))
        assertSame(item, SportsLiveSportInference.infer(item, dir))
    }

    @Test
    fun `a game with a sport already keeps it`() {
        val dir = TeamDirectory().apply {
            put(EspnLeague("soccer", "ger.1"), bundesliga, 0L)
            put(EspnLeague("soccer", "uefa.champions"), bundesliga, 0L)
        }
        assertNull(SportsLiveSportInference.heldSport(card("Bayern vs Köln", sport = "Soccer"), dir))
    }

    // ---- live.23: titles not placed (a catch-all "OTHER" tag, a league no inference list held) ----

    private val championship = listOf(
        team("Burnley", "Burnley", "Burnley", "BUR"),
        team("Blackburn Rovers", "Blackburn", "Blackburn Rovers", "BLB"),
        team("Norwich City", "Norwich", "Norwich City", "NOR")
    )
    private val eredivisie = listOf(
        team("Feyenoord Rotterdam", "Feyenoord", "Feyenoord Rotterdam", "FEY"),
        team("Excelsior", "Excelsior", "Excelsior", "EXC"),
        team("FC Groningen", "Groningen", "FC Groningen", "GRO"),
        team("SC Heerenveen", "Heerenveen", "SC Heerenveen", "HEE")
    )
    private val bundesliga2 = listOf(
        team("1. FC Kaiserslautern", "Kaiserslautern", "1. FC Kaiserslautern", "FCK"),
        team("Dynamo Dresden", "Dresden", "Dynamo Dresden", "SGD")
    )
    private val laLiga2 = listOf(team("Castellón", "Castellón", "Castellón", "CAS"), team("Eldense", "Eldense", "Eldense", "ELD"))
    private val leagueTwo = listOf(team("Gillingham", "Gillingham", "Gillingham", "GIL"), team("Crewe Alexandra", "Crewe", "Crewe Alexandra", "CRE"))
    private val premiership = listOf(team("Celtic", "Celtic", "Celtic", "CEL"), team("St. Mirren", "St Mirren", "St. Mirren", "STM"))

    @Test
    fun `untagged games of the leagues addons file under Other are placed by their teams`() {
        val dir = directory(
            EspnLeague("soccer", "eng.2") to championship, EspnLeague("soccer", "ned.1") to eredivisie,
            EspnLeague("soccer", "ger.2") to bundesliga2, EspnLeague("soccer", "esp.2") to laLiga2,
            EspnLeague("soccer", "eng.4") to leagueTwo, EspnLeague("soccer", "sco.1") to premiership
        )
        val cases = listOf(
            "Burnley vs Blackburn Rovers" to EspnLeague("soccer", "eng.2"),
            "Feyenoord vs Excelsior" to EspnLeague("soccer", "ned.1"),
            "FC Groningen vs SC Heerenveen" to EspnLeague("soccer", "ned.1"),
            "1. FC Kaiserslautern vs SG Dynamo Dresden" to EspnLeague("soccer", "ger.2"),
            "CD Castellón vs CD Eldense" to EspnLeague("soccer", "esp.2"),
            "Gillingham vs Crewe" to EspnLeague("soccer", "eng.4"),
            "Celtic vs St. Mirren" to EspnLeague("soccer", "sco.1")
        )
        for ((name, league) in cases) {
            val item = card(name, sport = "OTHER", catalog = "Other Sports", genres = listOf("OTHER"))
            val placed = SportsLiveSportInference.infer(item, dir)
            // Its tag routes back to exactly that league's feed and reads as soccer.
            assertEquals(name, listOf(league), EspnLeagueMap.leaguesFor(placed))
            assertEquals(name, SportsLiveSportKind.SOCCER, SportsLiveSportKind.tagOf(placed))
            assertEquals(name, SportsLiveSportKind.SOCCER, SportsLiveMySportsScope.kindOf(placed))
        }
        // One side only in a list: nothing is guessed.
        val cup = card("Burnley vs Arsenal", sport = "OTHER", catalog = "Other Sports", genres = listOf("OTHER"))
        assertSame(cup, SportsLiveSportInference.infer(cup, dir))
    }

    @Test
    fun `the placement lists are kept with the inference lists`() {
        for (slug in listOf("eng.2", "eng.3", "eng.4", "sco.1", "ned.1", "ger.2", "esp.2", "mex.1")) {
            assertTrue(slug, EspnLeague("soccer", slug) in SportsLiveSportInference.leagues)
        }
        // Clubs that field basketball and handball sides under one name stay out (Portugal, Turkey, Greece).
        for (slug in listOf("por.1", "tur.1", "gre.1")) assertFalse(slug, EspnLeague("soccer", slug) in SportsLiveSportInference.leagues)
    }

    // Report #23: "Arsenal vs West Ham" showed only SOCCER, and "CAW vs AVL" likewise.
    private val epl = listOf(
        team("Arsenal", "Arsenal", "Arsenal", "ARS"),
        team("West Ham United", "West Ham United", "West Ham United", "WHU"),
        team("Aston Villa", "Aston Villa", "Aston Villa", "AVL")
    )

    @Test
    fun `a Soccer card of two Premier League clubs takes the Premier League tag`() {
        val dir = directory(EspnLeague("soccer", "eng.1") to epl)
        for (sport in listOf("Soccer", "Football")) {
            val inferred = SportsLiveSportInference.infer(card("Arsenal vs West Ham", sport = sport, genres = listOf(sport)), dir)
            assertEquals(sport, "Premier League", inferred.sport)
            assertEquals(sport, listOf(EspnLeague("soccer", "eng.1")), EspnLeagueMap.leaguesFor(inferred))
        }
    }

    @Test
    fun `a card written by abbreviations only is never placed by a guess`() {
        // CAW names no team of any list (several clubs could be it): untouched, even with AVL known.
        val dir = directory(EspnLeague("soccer", "eng.1") to epl)
        val item = card("CAW vs AVL", sport = "Soccer", genres = listOf("Soccer"))
        assertNull(SportsLiveSportInference.leagueOf(item, dir))
        assertSame(item, SportsLiveSportInference.infer(item, dir))
    }
}
