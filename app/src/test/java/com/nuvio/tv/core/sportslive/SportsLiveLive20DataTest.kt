package com.nuvio.tv.core.sportslive

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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** live.20 data hygiene (F4 and the TV photos): raw addon text, duplicate rows, sport tags, channels in Live now. */
class SportsLiveLive20DataTest {
    private val now = Instant.parse("2026-11-20T16:00:00Z").toEpochMilli()

    private fun event(
        addonId: String,
        id: String,
        name: String,
        genres: List<String> = emptyList(),
        live: Boolean = true,
        description: String? = null
    ): SportsLiveItem =
        SportsLiveItemBucketer.describe(
            MetaPreview(
                id = id, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
                posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = description,
                releaseInfo = if (live) "LIVE" else null, imdbRating = null, genres = genres
            ),
            claimed(addonId),
            now
        )

    private fun claimed(addonId: String): ClaimedCatalog {
        val catalog = CatalogDescriptor(type = ContentType.fromString("sport"), rawType = "sport", id = "events", name = "Events")
        val addon = Addon(
            id = addonId, name = "Sports $addonId", version = "1.0.0", description = null, logo = null,
            baseUrl = "https://example.invalid", catalogs = listOf(catalog), types = listOf(catalog.type), resources = emptyList()
        )
        return ClaimedCatalog(addon, catalog, "${addonId}_sport_events", SportsLiveKind.EVENTS, false)
    }

    private fun live(vararg items: SportsLiveItem, order: List<String> = listOf("a", "b")) =
        SportsLiveItemBucketer.bucketTab(items.toList(), SportsLiveTab.LIVE_NOW, now, addonOrder = order)

    // ---- Raw text ----

    @Test
    fun `emoji, pipes and a misspelt sport leave the title`() {
        assertEquals("Moto3 · Round 22 · Portugal", SportsLiveDisplayText.name("📺 Moto3 | Round 22 | Portugal"))
        assertEquals("MotoGP", SportsLiveDisplayText.name(" 📺 MotorGP"))
        assertEquals("FC Bayern München vs 1. FC Köln", SportsLiveDisplayText.name("⏱ FC Bayern München vs 1. FC Köln"))
        assertEquals("Highlights", SportsLiveDisplayText.name("Highlights 🏆"))
        // The degree sign is no emoji.
        assertEquals("Stage 2°", SportsLiveDisplayText.name("Stage 2°"))
        assertEquals("Watch live\nFinal", SportsLiveDisplayText.description("📺 Watch live\n🏆 Final"))
    }

    @Test
    fun `a tournament written as a game reads as its two parts and names no game`() {
        assertEquals("ATP 250 · Stockholm Open", SportsLiveDisplayText.name("ATP 250 vs Stockholm Open"))
        assertNull(SportsLiveTeams.parse("ATP 250 vs Stockholm Open"))
        assertEquals("ATP 250" to "Stockholm Open", SportsLiveTeams.nonGamePair("ATP 250 vs Stockholm Open"))
        // A real pairing stays one, a tournament before it too.
        assertEquals("Sinner vs Alcaraz", SportsLiveDisplayText.name("Sinner vs Alcaraz"))
        assertEquals(TeamPair("UFC 310: Pantoja", "Asakura"), SportsLiveTeams.parse("UFC 310: Pantoja vs Asakura"))
        assertNull(SportsLiveTeams.nonGamePair("Sinner vs Alcaraz"))
    }

    @Test
    fun `a side loses its emoji and keeps its 1 dot`() {
        assertEquals(TeamPair("FC Bayern München", "1. FC Köln"), SportsLiveTeams.parse("⏱ FC Bayern München vs 1. FC Köln"))
    }

    @Test
    fun `a feed titled only by its tournament takes the fuller line of its description`() {
        val feed = event("a", "t", "ATP 250", listOf("Tennis"), description = "Tennis · Live now\nATP 250 v Stockholm Open")
        assertEquals("ATP 250 · Stockholm Open", feed.displayName)
        // Nothing fuller: the title as it is.
        assertEquals("ATP", event("a", "u", "ATP", listOf("Tennis"), description = "Tennis").displayName)
    }

    // ---- One game, one row ----

    @Test
    fun `local and English club names are one side`() {
        val full = SportsLiveTeams.MergeMatch.FULL
        assertEquals(full, SportsLiveTeams.mergeMatch("FC Bayern München", "Bayern Munich"))
        assertEquals(full, SportsLiveTeams.mergeMatch("1. FC Köln", "FC Cologne"))
        assertEquals(full, SportsLiveTeams.mergeMatch("1. FC Köln", "FC Köln"))
        assertEquals(full, SportsLiveTeams.mergeMatch("Athletic Bilbao", "Athletic Club"))
        assertEquals("cologne", SportsLiveTeams.normalise("1. FC Koeln"))
    }

    @Test
    fun `a club's full name with Club in it is its short name`() {
        assertEquals(SportsLiveTeams.MergeMatch.PREFIX, SportsLiveTeams.mergeMatch("Sporting Club Delhi", "Sporting Delhi"))
        // Filler alone still names no club.
        assertEquals(SportsLiveTeams.MergeMatch.NONE, SportsLiveTeams.mergeMatch("Racing Club", "Racing Club de Montevideo"))
    }

    @Test
    fun `language variants and a flipped home and away merge`() {
        val rows = live(
            event("a", "1", "⏱ FC Bayern München vs 1. FC Köln", listOf("Soccer")),
            event("b", "2", "FC Cologne vs Bayern Munich", listOf("Bundesliga")),
            event("a", "3", "Athletic Bilbao vs Espanyol", listOf("Soccer")),
            event("b", "4", "Espanyol vs Athletic Club", listOf("La Liga")),
            event("a", "5", "East Bengal FC vs Sporting Club Delhi", listOf("Football")),
            event("b", "6", "East Bengal FC vs Sporting Delhi", listOf("Soccer"))
        )
        assertEquals(listOf("1", "3", "5"), rows.map { it.id }.sorted())
        assertTrue(rows.all { it.sourceCount == 2 })
    }

    @Test
    fun `live copies of one teamless event from different addons are one row`() {
        val motoGp = listOf(
            event("a", "m1", "📺 MotoGP", listOf("Motorsport")),
            event("b", "m2", "MotoGP", listOf("Motorsport"))
        )
        val draw = listOf(
            event("a", "d1", "UEFA Nations League Draw", listOf("Football")),
            event("b", "d2", "UEFA Nations League Draw", listOf("Soccer"))
        )
        val rows = live(*(motoGp + draw).toTypedArray())
        assertEquals(listOf("d1", "m1"), rows.map { it.id }.sorted())
        assertEquals(2, rows.single { it.id == "m1" }.sourceCount)
        val drawRow = rows.single { it.id == "d1" }
        assertEquals(2, drawRow.sourceCount)
        // One tag word for both copies: the soccer the title and the other copy say.
        assertEquals(SportsLiveSportKind.SOCCER, SportsLiveSportKind.plainTagOf(drawRow))
    }

    @Test
    fun `a tournament feed merges with the copy its description names`() {
        val rows = live(
            event("a", "n", "ATP 250", listOf("Tennis"), description = "Tennis · Live now\nATP 250 v Stockholm Open"),
            event("b", "c", "ATP 250 vs Stockholm Open")
        )
        assertEquals(listOf("n"), rows.map { it.id })
        assertEquals(2, rows.single().sourceCount)
        assertEquals("ATP 250 · Stockholm Open", rows.single().displayName)
    }

    @Test
    fun `one addon's feeds of one title stay separate rows`() {
        val rows = live(
            event("a", "m1", "MotoGP", listOf("Motorsport")),
            event("a", "m2", "MotoGP", listOf("Motorsport")),
            event("b", "m3", "MotoGP", listOf("Motorsport"))
        )
        // The other addon's copy joins the first; the first addon's second feed stays its own row.
        assertEquals(listOf("m1", "m2"), rows.map { it.id }.sorted())
        assertEquals(listOf("m3"), rows.single { it.id == "m1" }.alternates.map { it.id })
    }

    @Test
    fun `a generic title never merges, even across addons`() {
        // A bare "Tennis" / "ATP" / "WTA" is a sport feed, listed on Channels (TV report #5).
        for (title in listOf("Court 3", "Day 6 Singles", "Main Feed")) {
            val rows = live(event("a", "1", title, listOf("Tennis")), event("b", "2", title, listOf("Tennis")))
            assertEquals(title, 2, rows.size)
        }
        assertTrue(SportsLiveItemBucketer.isGenericTitle("ice hockey"))
        assertFalse(SportsLiveItemBucketer.isGenericTitle("atp 250 · stockholm open"))
    }

    @Test
    fun `teamless events of other sports, or not live, stay apart`() {
        val sports = live(event("a", "1", "Live Coverage", listOf("Tennis")), event("b", "2", "Live Coverage", listOf("Basketball")))
        assertEquals(2, sports.size)
        val upcoming = SportsLiveItemBucketer.bucketTab(
            listOf(event("a", "1", "WWE", live = false), event("a", "2", "WWE", live = false)), SportsLiveTab.UPCOMING, now
        )
        assertEquals(2, upcoming.size)
    }

    // ---- Tags ----

    @Test
    fun `a plain sport word is the sport's one name, a bare Football is soccer unless it says American football`() {
        assertEquals(SportsLiveSportKind.SOCCER, SportsLiveSportKind.plainTagOf(event("a", "1", "East Bengal FC vs Sporting Delhi", listOf("Soccer"))))
        assertEquals(SportsLiveSportKind.HOCKEY, SportsLiveSportKind.plainTagOf(event("a", "2", "Devils vs Rangers", listOf("Ice Hockey"))))
        // A lone "Football" is soccer unless the card says American football (#206).
        assertEquals(SportsLiveSportKind.SOCCER, SportsLiveSportKind.plainTagOf(event("a", "3", "East Bengal FC vs Sporting Delhi", listOf("Football"))))
        // A league stays as written.
        assertNull(SportsLiveSportKind.plainTagOf(event("a", "4", "Arsenal vs Chelsea", listOf("Premier League"))))
        // A bare "Football" with an NFL genre is American football.
        assertEquals(SportsLiveSportKind.FOOTBALL, SportsLiveSportKind.tagOf(event("a", "5", "Bears vs Packers", listOf("Football", "NFL"))))
    }

    @Test
    fun `horse racing and figure skating filed under motorsport get their own tag`() {
        assertEquals("Racing", SportsLiveItemBucketer.misfiledMotorsport("Live racing from Catterick and Meydan"))
        assertEquals("Skating", SportsLiveItemBucketer.misfiledMotorsport("ISU Grand Prix | Finlandia Trophy"))
        assertNull(SportsLiveItemBucketer.misfiledMotorsport("Ferrari Challenge Europe"))
        assertNull(SportsLiveItemBucketer.misfiledMotorsport("Portugal Free Practice 1"))
        assertNull(SportsLiveItemBucketer.misfiledMotorsport("British Touring Car racing"))
        assertEquals("Racing", event("a", "1", "Live racing from Catterick and Meydan", listOf("Motorsport")).sport)
        assertEquals("Motorsport", event("a", "2", "MotoGP", listOf("Motorsport")).sport)
        // The ISU's Grand Prix is no motor race.
        assertNull(SportsLiveSportKind.of("ISU Grand Prix Finlandia Trophy"))
        assertEquals(SportsLiveSportKind.MOTORSPORT, SportsLiveSportKind.of("Portugal Grand Prix"))
    }

    // ---- Channels listed as games ----

    @Test
    fun `a channel's name in a live events catalog is a channel`() {
        assertTrue(event("a", "1", "NFL Network", listOf("AF")).isChannel)
        assertTrue(event("a", "2", "Tennis Channel").isChannel)
        assertTrue(event("a", "3", "📺 Racing TV").isChannel)
        assertFalse(event("a", "4", "Raw on USA Network").isChannel)
        assertFalse(event("a", "5", "Arsenal vs Chelsea").isChannel)
        assertFalse(event("a", "6", "MotoGP").isChannel)
        val rows = live(event("a", "1", "NFL Network", listOf("AF")), event("a", "5", "Arsenal vs Chelsea"))
        assertEquals(listOf("5"), rows.map { it.id })
        assertNotNull(SportsLiveItemBucketer.bucketTab(listOf(event("a", "1", "NFL Network")), SportsLiveTab.CHANNELS, now).singleOrNull())
    }

    @Test
    fun `a title naming only a sport or a tour is a feed on Channels, a tournament stays an event`() {
        for (title in listOf("Tennis", "ATP", "WTA", "Live Tennis", "ATP Tour", "Ice Hockey HD", "Soccer")) {
            val feed = event("a", title, title, listOf("Tennis"))
            assertTrue(title, feed.isChannel)
            assertTrue(title, live(feed).isEmpty())
            assertNotNull(title, SportsLiveItemBucketer.bucketTab(listOf(feed), SportsLiveTab.CHANNELS, now).singleOrNull())
        }
        for (title in listOf("ATP Finals 2026", "ATP 250 · Stockholm", "ATP 250", "Court 3", "Main Feed", "Sinner vs Alcaraz")) {
            assertFalse(title, event("a", title, title, listOf("Tennis")).isChannel)
        }
        // A description naming the event it carries keeps it that event's feed.
        assertFalse(event("a", "d", "ATP", listOf("Tennis"), description = "ATP v Stockholm Open").isChannel)
        // A channel's name with a sport tag from the addon is still a channel (NFL Network tagged AF, Tennis Channel tagged Tennis).
        assertTrue(event("a", "n", "NFL Network", listOf("AF")).isChannel)
        assertTrue(event("a", "t", "Tennis Channel", listOf("Tennis")).isChannel)
    }
}
