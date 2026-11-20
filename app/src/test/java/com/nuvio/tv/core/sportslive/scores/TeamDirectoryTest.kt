package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TeamDirectoryTest {

    private val nbaTeams = TeamDirectory.parse(EspnFixtures.json("espn_teams_nba_20261118.json"), EspnFixtures.moshi)!!
    private val nflTeams = TeamDirectory.parse(EspnFixtures.json("espn_teams_nfl_20261118.json"), EspnFixtures.moshi)!!
    private val eng1Teams = TeamDirectory.parse(EspnFixtures.json("espn_teams_eng1_20261118.json"), EspnFixtures.moshi)!!
    private val cbbTeams = TeamDirectory.parse(EspnFixtures.json("espn_teams_cbb_20261118.json"), EspnFixtures.moshi)!!

    private val NHL = EspnLeagueMap.NHL
    private val NBA = EspnLeagueMap.NBA
    private val CBB = EspnLeagueMap.COLLEGE_BASKETBALL
    private val ENG1 = EspnLeague("soccer", "eng.1")

    private fun team(id: String, display: String, short: String?, nickname: String?, location: String?, abbr: String?) =
        DirectoryTeam(id, display, short, abbr, location, nickname, "https://a.espncdn.com/i/teamlogos/x/500/$id.png")

    private val nhlTeams = listOf(
        team("1", "Boston Bruins", "Bruins", "Bruins", "Boston", "BOS"),
        team("2", "Florida Panthers", "Panthers", "Panthers", "Florida", "FLA")
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

    private fun directory(vararg lists: Pair<EspnLeague, List<DirectoryTeam>>) =
        TeamDirectory().apply { lists.forEach { (league, teams) -> put(league, teams, 0L) } }

    private fun cardLogos(dir: TeamDirectory, card: SportsLiveItem): Pair<KnownTeam, KnownTeam>? {
        val (first, second) = ScoreChips.orderedSides(card)!!
        return dir.forCard(card, first, second)
    }

    // --- Parsing ---

    @Test
    fun `the NBA list parses every team with its names and default logo`() {
        assertEquals(30, nbaTeams.size)
        val celtics = nbaTeams.single { it.abbreviation == "BOS" }
        assertEquals("Boston Celtics", celtics.displayName)
        assertEquals("Celtics", celtics.shortName)
        assertEquals("Celtics", celtics.nickname)
        assertEquals("Boston", celtics.location)
        assertEquals("https://a.espncdn.com/i/teamlogos/nba/500/bos.png", celtics.logo)
    }

    @Test
    fun `the NFL, Premier League and college basketball lists parse`() {
        assertEquals(32, nflTeams.size)
        assertEquals(20, eng1Teams.size)
        assertEquals(12, cbbTeams.size)
        assertTrue(eng1Teams.any { it.displayName == "Arsenal" })
        val duke = cbbTeams.single { it.id == "150" }
        assertEquals("Duke Blue Devils", duke.displayName)
        assertEquals("https://a.espncdn.com/i/teamlogos/ncaa/500/150.png", duke.logo)
    }

    @Test
    fun `an unreadable body is null, a team without an id is left out and a guid-only logo is kept`() {
        assertNull(TeamDirectory.parse("not json", EspnFixtures.moshi))
        val body = """{"sports":[{"leagues":[{"teams":[
            {"team":{"id":"1","displayName":"A","logos":[{"href":"https://a.espncdn.com/guid/x/logos/a.png","rel":["full"]}]}},
            {"team":{"displayName":"B","logos":[{"href":"https://a.espncdn.com/i/teamlogos/x/500/b.png","rel":["default"]}]}},
            {"team":{"id":"3","displayName":"C","logos":[{"href":"https://a.espncdn.com/i/teamlogos/x/500/c.png","rel":["full","default"]}]}}
        ]}]}]}"""
        // live.18: a team ESPN gives only a /guid/ logo ("Coachella Valley Lakers") stays in the list.
        val teams = TeamDirectory.parse(body, EspnFixtures.moshi)!!
        assertEquals(listOf("1", "3"), teams.map { it.id })
        assertEquals("https://a.espncdn.com/guid/x/logos/a.png", teams.first().logo)
    }

    // --- Disk cache ---

    @Test
    fun `lists survive on disk and come back with their fetch time`() {
        val dir = Files.createTempDirectory("teamdir").toFile()
        try {
            val store = TeamDirectoryStore(dir)
            store.write(NBA, nbaTeams, 1234L)
            val read = store.readAll().single()
            assertEquals(NBA, read.first)
            assertEquals(1234L, read.second)
            assertEquals(nbaTeams, read.third)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the directory stays bounded and drops the least recently used league from disk`() = runTest {
        val dir = Files.createTempDirectory("teamdir").toFile()
        try {
            val store = TeamDirectoryStore(dir)
            val directory = TeamDirectory(maxLeagues = 2)
            val sync = TeamDirectorySync(directory, { league -> if (league == NBA) nbaTeams else nhlTeams }, store, { 0L })
            sync.refresh(listOf(NBA, NHL))
            // A third league (answered with the NHL list here): the oldest, the NBA, goes from memory and disk.
            sync.refresh(listOf(EspnLeagueMap.NFL))
            assertEquals(2, dir.listFiles()!!.count { it.name.endsWith(".tsv") })
            assertNull(directory.fetchedAt(NBA))
            assertFalse(File(dir, "basketball__nba.tsv").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the inference lists are asked for first, so a full pass never leaves them out`() = runTest {
        // More on-screen soccer leagues than one pass takes (live.20): inference still gets every list it reads.
        val inference = com.nuvio.tv.core.sportslive.SportsLiveSportInference.leagues
        val order = LiveScoresRepository.directoryLeagueOrder((1..60).map { EspnLeague("soccer", "test.$it") } + NBA)
        assertEquals(inference, order.take(inference.size))
        assertEquals(1, order.count { it == NBA })
        val asked = mutableListOf<EspnLeague>()
        TeamDirectorySync(TeamDirectory(), { asked += it; nbaTeams }, null, { 0L }).refresh(order)
        assertTrue(asked.containsAll(inference))
        assertEquals(TeamDirectorySync.MAX_PER_PASS, asked.size)
    }

    // --- Weekly refresh ---

    @Test
    fun `a list is fetched once and again only after a week`() = runTest {
        var now = 0L
        val asked = mutableListOf<EspnLeague>()
        val sync = TeamDirectorySync(TeamDirectory(), { asked += it; nbaTeams }, null, { now })
        sync.refresh(listOf(NBA))
        now += TeamDirectory.REFRESH_MS - 1
        sync.refresh(listOf(NBA))
        assertEquals(1, asked.size)
        now += 1
        sync.refresh(listOf(NBA))
        assertEquals(2, asked.size)
    }

    @Test
    fun `a failed list is not asked for again within the hour`() = runTest {
        var now = 0L
        val asked = mutableListOf<EspnLeague>()
        val sync = TeamDirectorySync(TeamDirectory(), { asked += it; null }, null, { now })
        sync.refresh(listOf(NBA))
        now += TeamDirectorySync.RETRY_MS - 1
        sync.refresh(listOf(NBA))
        assertEquals(1, asked.size)
        now += 1
        sync.refresh(listOf(NBA))
        assertEquals(2, asked.size)
    }

    @Test
    fun `a fresh list on disk is used without a request`() = runTest {
        val dir = Files.createTempDirectory("teamdir").toFile()
        try {
            TeamDirectoryStore(dir).write(NBA, nbaTeams, 1_000L)
            val asked = mutableListOf<EspnLeague>()
            val directory = TeamDirectory()
            val sync = TeamDirectorySync(directory, { asked += it; nbaTeams }, TeamDirectoryStore(dir), { 2_000L })
            sync.refresh(listOf(NBA))
            assertTrue(asked.isEmpty())
            assertEquals(30, directory.teams(NBA).size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `soccer all and individual sports are never asked for`() = runTest {
        val asked = mutableListOf<EspnLeague>()
        val sync = TeamDirectorySync(TeamDirectory(), { asked += it; nbaTeams }, null, { 0L })
        sync.refresh(listOf(EspnLeagueMap.SOCCER_ALL, EspnLeagueMap.ATP, EspnLeagueMap.UFC, EspnLeagueMap.F1, EspnLeagueMap.PGA))
        assertTrue(asked.isEmpty())
    }

    // --- Exactly one team ---

    @Test
    fun `both sides resolving to one team each give both logos`() {
        val dir = directory(NBA to nbaTeams)
        val logos = cardLogos(dir, item("Boston Celtics vs Miami Heat", "NBA"))!!
        assertEquals("https://a.espncdn.com/i/teamlogos/nba/500/bos.png", logos.first.logo)
        assertEquals("https://a.espncdn.com/i/teamlogos/nba/500/mia.png", logos.second.logo)
        assertEquals("MIA", logos.second.abbreviation)
        assertNotNull(cardLogos(dir, item("Celtics vs Heat", "NBA")))
    }

    @Test
    fun `soccer names resolve through the alias layer`() {
        val dir = directory(ENG1 to eng1Teams)
        assertNotNull(cardLogos(dir, item("Arsenal FC vs Chelsea", "Soccer", listOf("Premier League"))))
    }

    @Test
    fun `a name two teams of the league answer to keeps the circles`() {
        // Several Wildcats in Division I.
        val dir = directory(CBB to cbbTeams)
        assertNull(cardLogos(dir, item("Wildcats vs Duke", "NCAAB")))
        assertNotNull(cardLogos(dir, item("Kentucky vs Duke", "NCAAB")))
        // Miami (FL) and Miami (OH): "Miami" is ESPN's short name of the Hurricanes alone.
        assertEquals("2390", dir.resolve(CBB, "Miami")?.id)
        assertEquals("193", dir.resolve(CBB, "Miami (OH)")?.id)
        // A women's side never resolves to the men's team; a stream tag is still dropped.
        assertNull(dir.resolve(CBB, "Duke (W)"))
        assertEquals("150", dir.resolve(CBB, "Duke [HD]")?.id)
    }

    @Test
    fun `two Nacional clubs in one league keep the circles`() {
        val league = EspnLeague("soccer", "uefa.champions")
        val dir = directory(
            league to listOf(
                team("10", "Nacional", "Nacional", "Nacional", "Nacional", "NAC"),
                team("11", "CD Nacional", "Nacional", "CD Nacional", "CD Nacional", "CDN"),
                team("12", "Benfica", "Benfica", "Benfica", "Benfica", "BEN")
            )
        )
        assertNull(dir.resolve(league, "Nacional"))
        assertNull(cardLogos(dir, item("Nacional vs Benfica", "Soccer", listOf("Champions League"))))
    }

    @Test
    fun `the same city in two leagues never crosses leagues`() {
        val dir = directory(NBA to nbaTeams, NHL to nhlTeams)
        // An NBA card naming an NHL team: nothing, never the Bruins' logo.
        assertNull(cardLogos(dir, item("Boston Bruins vs Miami Heat", "NBA")))
        // The NHL card gets the Bruins, never the Celtics.
        val nhl = cardLogos(dir, item("Boston Bruins vs Florida Panthers", "NHL"))!!
        assertEquals("https://a.espncdn.com/i/teamlogos/x/500/1.png", nhl.first.logo)
        // A city alone is no name.
        assertNull(cardLogos(dir, item("Boston vs Miami", "NBA")))
    }

    @Test
    fun `a card on two leagues waits for both lists and needs exactly one to resolve`() {
        val card = item("Duke vs Kentucky", "Basketball")
        val dir = directory(NBA to nbaTeams)
        // Its college list is not known yet: nothing.
        assertNull(cardLogos(dir, card))
        dir.put(CBB, cbbTeams, 0L)
        assertEquals("https://a.espncdn.com/i/teamlogos/ncaa/500/150.png", cardLogos(dir, card)!!.first.logo)
    }

    @Test
    fun `a soccer card on soccer all gets no directory logos`() {
        val dir = directory(ENG1 to eng1Teams)
        assertNull(cardLogos(dir, item("Arsenal vs Chelsea", "Football")))
    }

    // --- Drawn card ---

    @Test
    fun `a card draws both logos with no scoreboard data`() {
        val book = TeamLogoBook(active = true, directory = directory(NBA to nbaTeams))
        val art = GameArt.of(item("Boston Celtics vs Miami Heat", "NBA"), snapshot = null, known = book)!!
        assertTrue(art.hasLogos)
        assertEquals("BOS", art.first.short)
    }

    @Test
    fun `directory logos are not drawn while Scores is Off`() {
        val book = TeamLogoBook(active = false, directory = directory(NBA to nbaTeams))
        assertFalse(GameArt.of(item("Boston Celtics vs Miami Heat", "NBA"), snapshot = null, known = book)!!.hasLogos)
    }

    @Test
    fun `a new list bumps the logo revision so cards redraw`() {
        val book = TeamLogoBook(active = true)
        val before = book.revision.value
        book.directory.put(NBA, nbaTeams, 0L)
        assertTrue(book.revision.value > before)
    }
}
