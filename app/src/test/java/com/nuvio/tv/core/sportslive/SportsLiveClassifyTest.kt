package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.identify.GameIdentity
import com.nuvio.tv.core.sportslive.identify.SportsLiveTeamIdentity
import com.nuvio.tv.core.sportslive.identify.TeamCandidate
import com.nuvio.tv.core.sportslive.identify.TeamIdentityBook
import com.nuvio.tv.core.sportslive.identify.TeamIdentityRules
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.ClaimedCatalog
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** live.20, user TV report #4: shows, suffix-less channels, untagged games and the unidentified log. */
class SportsLiveClassifyTest {
    private val now = Instant.parse("2026-11-20T16:00:00Z").toEpochMilli()

    private fun item(
        name: String,
        genres: List<String> = emptyList(),
        description: String? = null,
        catalogName: String = "Events",
        kind: SportsLiveKind = SportsLiveKind.EVENTS,
        id: String = name
    ): SportsLiveItem {
        val catalog = CatalogDescriptor(type = ContentType.fromString("tv"), rawType = "tv", id = "live", name = catalogName)
        val addon = Addon(
            id = "a", name = "Addon a", version = "1.0.0", description = null, logo = null,
            baseUrl = "https://example.invalid", catalogs = listOf(catalog), types = listOf(catalog.type), resources = emptyList()
        )
        val meta = MetaPreview(
            id = id, type = ContentType.UNKNOWN, rawType = "tv", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = description,
            releaseInfo = "LIVE", imdbRating = null, genres = genres
        )
        return SportsLiveItemBucketer.describe(meta, ClaimedCatalog(addon, catalog, "a_tv_live", kind, false), now)
    }

    private fun liveIds(vararg items: SportsLiveItem): Set<String> =
        SportsLiveItemBucketer.bucketTab(items.toList(), SportsLiveTab.LIVE_NOW, now).map { it.id }.toSet()

    // ---- 1. TV shows ----

    @Test
    fun `known shows, season words and TV genres leave Live now`() {
        val shows = listOf(
            item("Family Guy"),
            item("South Park"),
            item("LIVE: South Park"),
            item("The Simpsons - Treehouse of Horror"),
            item("Bob's Burgers 24/7"),
            item("Some Sitcom Season 3"),
            item("Some Cartoon Episode 12"),
            item("Treehouse Night", genres = listOf("Animation")),
            item("Late Laughs", genres = listOf("Sitcom"))
        )
        shows.forEach { assertTrue(it.meta.name, SportsLiveNonSport.isNonSport(it)) }
        assertTrue(liveIds(*shows.toTypedArray()).isEmpty())
    }

    @Test
    fun `a sport-named catalog does not keep a show`() {
        val show = item("Family Guy", catalogName = "Live Football")

        assertTrue(SportsLiveNonSport.isNonSport(show))
    }

    @Test
    fun `real games and sport-named shows stay`() {
        val games = listOf(
            item("Arsenal vs Chelsea", genres = listOf("Comedy")),
            item("Simpsons vs Griffins"),
            item("NFL Season 2026 Kickoff"),
            item("Family Guy Bowl", genres = listOf("American Football")),
            item("Hard Knocks Episode 3", description = "NFL training camp"),
            item("ATP 250 - Stockholm Open"),
            item("Some Big Event")
        )
        games.forEach { assertFalse(it.meta.name, SportsLiveNonSport.isNonSport(it)) }
        assertEquals(games.map { it.id }.toSet(), liveIds(*games.toTypedArray()))
    }

    @Test
    fun `a show title matches whole words only`() {
        assertTrue(SportsLiveNonSport.isShowTitle("South Park"))
        assertTrue(SportsLiveNonSport.isShowTitle("SOUTH PARK marathon"))
        assertFalse(SportsLiveNonSport.isShowTitle("Southampton vs Parkhead"))
        assertFalse(SportsLiveNonSport.isShowTitle("South Parkway Derby"))
    }

    @Test
    fun `a 24-7 show channel in a channels catalog stays a channel`() {
        val channel = item("Family Guy 24/7", kind = SportsLiveKind.CHANNELS, catalogName = "Channels")

        assertFalse(SportsLiveNonSport.isNonSport(channel))
        assertEquals(listOf(channel.id), SportsLiveItemBucketer.bucketTab(listOf(channel), SportsLiveTab.CHANNELS, now).map { it.id })
    }

    // ---- 2. Channels without a suffix ----

    @Test
    fun `broadcaster channels without a suffix are channels`() {
        listOf(
            "Fox Footy", "Fox League", "Sky Sports Main Event", "beIN Sports 1", "TSN 4", "ESPN 2", "ESPN2", "Fox Sports 1",
            "TNT Sport 1", "US| ESPN 2", "Sky Sports Premier League HD", "beIN Sports MAX 4", "DAZN 1 ES", "Sky Sports Racing"
        ).forEach { assertTrue(it, SportsLiveItemBucketer.isChannelName(it)) }
    }

    @Test
    fun `a broadcaster name with more words or two sides is no channel`() {
        listOf(
            "Sky Sports F1 Grand Prix Brazil", "ESPN FC", "Fox Sports NFL Sunday", "TSN 4 - Oilers vs Flames",
            "Movistar Inter vs Barcelona", "UFC 310 on ESPN", "Foxes vs Leagues", "Sky Blues vs Reds"
        ).forEach { assertFalse(it, SportsLiveItemBucketer.isChannelName(it)) }
    }

    @Test
    fun `a suffix-less channel in a live catalog goes to Channels, not Live now`() {
        val footy = item("Fox Footy")
        val league = item("Fox League")
        val game = item("Penrith Panthers vs Melbourne Storm", genres = listOf("NRL"))
        val tabs = SportsLiveItemBucketer.bucket(listOf(footy, league, game), now)

        assertTrue(footy.isChannel)
        assertEquals(SportsLiveKind.CHANNELS, footy.kind)
        assertEquals(setOf(footy.id, league.id), tabs.getValue(SportsLiveTab.CHANNELS).map { it.id }.toSet())
        assertEquals(listOf(game.id), tabs.getValue(SportsLiveTab.LIVE_NOW).map { it.id })
    }

    @Test
    fun `stream placeholder titles are channels unless they name a sport`() {
        listOf("Streams", "streams", "Live Stream 2", "Backup Links", "HD Streams").forEach {
            assertTrue(it, item(it).isChannel)
        }
        // A sport's feed stays an event; a bare "Live" is no placeholder.
        assertFalse(item("Main Feed", genres = listOf("Tennis")).isChannel)
        assertFalse(item("Live").isChannel)
        assertFalse(item("Streams", catalogName = "Live Tennis").isChannel)
    }

    // ---- 3. Missing sport tags ----

    @Test
    fun `league and competition words tag untagged games`() {
        assertEquals(SportsLiveSportKind.SOCCER, SportsLiveSportKind.tagOf(item("Independiente del Valle vs Macara", genres = listOf("LigaPro"))))
        assertEquals(SportsLiveSportKind.SOCCER, SportsLiveSportKind.tagOf(item("Ind. del Valle vs Macara", description = "Copa Libertadores")))
        assertEquals(SportsLiveSportKind.BASKETBALL, SportsLiveSportKind.tagOf(item("Anadolu Efes vs ASVEL", description = "Liga Endesa round 9")))
        assertEquals(SportsLiveSportKind.BASKETBALL, SportsLiveSportKind.tagOf(item("Anadolu Efes vs ASVEL", catalogName = "EuroLeague")))
        assertEquals(SportsLiveSportKind.CRICKET, SportsLiveSportKind.tagOf(item("UAE vs Bangladesh", description = "2nd T20I")))
        assertEquals(SportsLiveSportKind.HOCKEY, SportsLiveSportKind.tagOf(item("Frolunda vs Farjestad", genres = listOf("SHL"))))
        assertEquals(SportsLiveSportKind.RUGBY, SportsLiveSportKind.tagOf(item("Toulouse vs Racing 92", genres = listOf("Top 14"))))
        // A side's own words are no league: "del" in a name, "Copacabana".
        assertNull(SportsLiveSportKind.of("Independiente del Valle"))
        assertNull(SportsLiveSportKind.of("Copacabana Beach"))
    }

    @Test
    fun `two plain nations with nothing else stay untagged`() {
        // Cricket, football and hockey all play them: precision first, the unidentified log records it.
        assertNull(SportsLiveSportKind.tagOf(item("UAE vs Bangladesh")))
    }

    @Test
    fun `two basketball clubs of different leagues agree on basketball with no league`() {
        val efes = listOf(TeamCandidate("Anadolu Efes", emptyList(), "basketball", listOf("Turkish Basketball Super League"), "https://example.invalid/a.png"))
        val asvel = listOf(TeamCandidate("LDLC ASVEL", listOf("ASVEL"), "basketball", listOf("French LNB Pro A"), null))
        val identity = TeamIdentityRules.agree(efes, asvel)!!

        assertEquals("basketball", identity.sport)
        assertEquals("", identity.league)
        assertEquals("https://example.invalid/a.png" to null, identity.badges)
    }

    @Test
    fun `two soccer clubs of different leagues still need a shared league`() {
        val a = listOf(TeamCandidate("Club A", emptyList(), "soccer", listOf("League A"), null))
        val b = listOf(TeamCandidate("Club B", emptyList(), "soccer", listOf("League B"), null))

        assertNull(TeamIdentityRules.agree(a, b))
    }

    @Test
    fun `a sport-only identity tags the row and adds no genre`() {
        val book = TeamIdentityBook()
        book.put("Anadolu Efes", "ASVEL", GameIdentity("basketball", "", null to null))
        val game = item("Anadolu Efes vs ASVEL")
        val identified = SportsLiveTeamIdentity.identified(game, book)

        assertEquals("Basketball", identified.sport)
        assertEquals(game.meta.genres, identified.meta.genres)
        assertEquals(SportsLiveSportKind.BASKETBALL, SportsLiveSportKind.tagOf(identified))
        assertEquals("BASKETBALL", SportsLiveTags.short("Basketball", SportsLiveSportKind.tagOf(identified)))
    }

    @Test
    fun `a sport with no kind of its own gets a readable short tag`() {
        assertEquals("VOLLEYBALL", SportsLiveTags.short("Volleyball", null))
        assertEquals("HANDBALL", SportsLiveTags.short("Handball", null))
    }

    // ---- 4. Unidentified log ----

    @Test
    fun `unplaced rows are recorded once per load with titles and addon names only`() {
        val log = SportsLiveUnidentified()
        val unknown = item("Pias vs MKS", description = "https://example.invalid/stream")
        val tagged = item("Lakers vs Celtics", genres = listOf("NBA"))
        val channel = item("Fox Footy")
        val keep: (SportsLiveItem) -> SportsLiveItem = { it }

        log.record(listOf(unknown, tagged, channel), loadToken = 1, nowMs = now, place = keep)
        log.record(listOf(unknown), loadToken = 1, nowMs = now, place = keep)
        assertEquals(listOf(SportsLiveUnidentified.Entry("Addon a", "Pias vs MKS", 1, now)), log.snapshot())

        log.record(listOf(unknown), loadToken = 2, nowMs = now + 1, place = keep)
        assertEquals(2, log.snapshot().single().loads)
        assertEquals("2x | Addon a | Pias vs MKS", log.report())
        assertFalse(log.report().contains("example.invalid"))
    }

    @Test
    fun `a row placed later leaves the log`() {
        val log = SportsLiveUnidentified()
        val unknown = item("Pias vs MKS")
        log.record(listOf(unknown), loadToken = 1, nowMs = now, place = { it })
        log.record(listOf(unknown), loadToken = 2, nowMs = now, place = { it.copy(sport = "Volleyball", meta = it.meta.copy(genres = listOf("Volleyball"))) })

        assertTrue(log.snapshot().isEmpty())
    }

    @Test
    fun `the log is bounded and survives a restart`() {
        val saved = mutableListOf<List<SportsLiveUnidentified.Entry>>()
        val log = SportsLiveUnidentified(maxEntries = 2)
        log.install(emptyList()) { saved += it }
        listOf("A vs B", "C vs D", "E vs F").forEachIndexed { index, name ->
            log.record(listOf(item(name)), loadToken = index, nowMs = now + index, place = { it })
        }
        assertEquals(listOf("E vs F", "C vs D"), log.snapshot().map { it.title })

        val restored = SportsLiveUnidentified()
        restored.install(SportsLiveUnidentified.decode(SportsLiveUnidentified.encode(saved.last()))) {}
        assertEquals(log.snapshot(), restored.snapshot())
        assertTrue(SportsLiveUnidentified.decode("bad line\n1\tx\ta\tb").isEmpty())
    }
}
