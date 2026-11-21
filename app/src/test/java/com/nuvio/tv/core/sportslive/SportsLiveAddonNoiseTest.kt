package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.alerts.GameReminder
import com.nuvio.tv.core.sportslive.alerts.GameReminders
import com.nuvio.tv.core.sportslive.alerts.RemindersState
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.ClaimedCatalog
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #145: a high-volume addon's networks, TV episodes, TBD placeholders and unmerged copies. */
class SportsLiveAddonNoiseTest {
    private val now = Instant.parse("2026-11-18T14:30:00Z").toEpochMilli()

    // Networks

    @Test
    fun `a networks catalog is claimed as channels`() {
        val addon = addon("a", emptyList())
        val networks = catalog("tv", "networks", "Networks")
        val tv = catalog("tv", "tv", "TV")
        val events = catalog("tv", "live", "Live")
        val networkEvents = catalog("tv", "network_events", "Network Events")

        assertEquals(SportsLiveKind.CHANNELS, SportsLiveCatalogPolicy.classify(addon, networks, emptyMap())!!.kind)
        assertEquals(SportsLiveKind.CHANNELS, SportsLiveCatalogPolicy.classify(addon, tv, emptyMap())!!.kind)
        assertEquals(SportsLiveKind.EVENTS, SportsLiveCatalogPolicy.classify(addon, events, emptyMap())!!.kind)
        assertEquals(SportsLiveKind.EVENTS, SportsLiveCatalogPolicy.classify(addon, networkEvents, emptyMap())!!.kind)
    }

    @Test
    fun `a networks catalog's items go to Channels and never to Live now`() {
        val networks = claimed("a", "networks", "Networks", SportsLiveKind.CHANNELS)
        val items = listOf(meta("abc", "ABC", releaseInfo = "LIVE"), meta("ae", "A&E", releaseInfo = "LIVE"))
            .map { SportsLiveItemBucketer.describe(it, networks, now) }
        val tabs = SportsLiveItemBucketer.bucket(items, now)

        assertEquals(setOf("abc", "ae"), tabs.getValue(SportsLiveTab.CHANNELS).map { it.id }.toSet())
        assertTrue(tabs.getValue(SportsLiveTab.LIVE_NOW).isEmpty())
    }

    @Test
    fun `an events catalog item tagged NETWORKS is a channel, never a live game`() {
        val events = claimed("a", "live", "Live", SportsLiveKind.EVENTS)
        val item = SportsLiveItemBucketer.describe(meta("amc", "AMC", releaseInfo = "LIVE", genres = listOf("NETWORKS")), events, now)
        val tabs = SportsLiveItemBucketer.bucket(listOf(item), now)

        assertTrue(item.isChannel)
        assertEquals(SportsLiveKind.CHANNELS, item.kind)
        assertEquals(listOf("amc"), tabs.getValue(SportsLiveTab.CHANNELS).map { it.id })
        assertTrue(tabs.getValue(SportsLiveTab.LIVE_NOW).isEmpty())
    }

    // TV episodes and non-sport tags

    @Test
    fun `a TV episode tagged OTHER is filtered out of the sports tabs`() {
        val episode = event("a", "ep", "LIVE: Chicago P.D. S14, E7", "LIVE", listOf("OTHER"))
        val compact = event("a", "ep2", "Some Show S03E04", "LIVE")
        val show = event("a", "show", "The Amazing Race", "LIVE", listOf("OTHER"))
        val game = event("a", "game", "Vasco da Gama vs Internacional", "LIVE", listOf("OTHER"))
        val tennis = event("a", "atp", "ATP 250 - Stockholm Open", "LIVE", listOf("OTHER"))

        val live = SportsLiveItemBucketer.bucketTab(listOf(episode, compact, show, game, tennis), SportsLiveTab.LIVE_NOW, now)

        assertEquals(setOf("game", "atp"), live.map { it.id }.toSet())
    }

    @Test
    fun `an episode whose text names a sport is kept`() {
        val item = event("a", "x", "Hard Knocks S2E3", "LIVE", listOf("American Football"))

        assertFalse(SportsLiveNonSport.isNonSport(item))
    }

    @Test
    fun `OTHER is a generic tag, never a known sport`() {
        val item = event("a", "x", "Vasco da Gama vs Internacional", "LIVE", listOf("OTHER"))

        assertNull(SportsLiveTeams.knownSport(item))
        assertNull(SportsLiveSportKind.filterKeyOf(item))
        listOf("OTHER", "Misc", "general", "Entertainment", "NETWORKS").forEach { assertTrue(it, SportsLiveTeams.isNonSportTag(it)) }
        assertFalse(SportsLiveTeams.isNonSportTag("Soccer"))
    }

    @Test
    fun `the addon's raw sport tags map to sports`() {
        assertEquals(SportsLiveSportKind.FOOTBALL, SportsLiveSportKind.of("american_football"))
        assertEquals(SportsLiveSportKind.MOTORSPORT, SportsLiveSportKind.of("MOTORSPORT"))
        assertEquals(SportsLiveSportKind.DARTS, SportsLiveSportKind.of("darts"))
        assertEquals(SportsLiveSportKind.SOCCER, SportsLiveSportKind.of("Futbol"))
        assertEquals(listOf(EspnLeagueMap.NFL), EspnLeagueMap.leaguesFor("american_football", emptyList()))
        assertNull(SportsLiveSportKind.of("OTHER"))
        assertNull(SportsLiveSportKind.of("COLLEGE"))
    }

    // Cricket

    @Test
    fun `cricket competition words name cricket and route no ESPN feed`() {
        listOf("WBBL", "BBL 2026", "Big Bash League", "IPL", "T20 Blast", "ODI series", "Test match").forEach {
            assertEquals(it, SportsLiveSportKind.CRICKET, SportsLiveSportKind.of(it))
        }
        assertEquals(emptyList<Any>(), EspnLeagueMap.leaguesFor("WBBL", listOf("Hobart Hurricanes vs Sydney Sixers")))
    }

    @Test
    fun `untagged cricket is told from the teams only where no other sport uses them`() {
        fun tag(name: String) = SportsLiveSportKind.tagOf(event("a", name, name, "LIVE"))

        assertEquals(SportsLiveSportKind.CRICKET, tag("Hobart Hurricanes Women vs Sydney Sixers Women"))
        assertEquals(SportsLiveSportKind.CRICKET, tag("Mumbai Indians vs Chennai Super Kings"))
        assertEquals(SportsLiveSportKind.CRICKET, tag("Bangladesh A vs Hong Kong"))
        assertEquals(SportsLiveSportKind.CRICKET, tag("India A vs SA A"))
        assertEquals(SportsLiveSportKind.CRICKET, tag("BAN-A vs HK"))
        // Review A2 (live.23, replaces the 2026-11-21 mainly-cricket guess, c828ff485, that pinned this as cricket): two
        // plain nations are never guessed (rugby, hockey, football and volleyball play them too); ESPN's live cricket list
        // confirms one while live (NationalPairIdentityTest). An A side needs cricket nations.
        assertNull(tag("NZ vs IND"))
        assertNull(tag("Australia vs England"))
        assertNull(tag("Brazil A vs Argentina"))
        assertNull(tag("Hobart Hurricanes vs Carolina Hurricanes"))
    }

    @Test
    fun `an A side written as a code merges with its full name and is out under My sports`() {
        val full = event("a", "a1", "Bangladesh A vs Hong Kong", "LIVE", listOf("CRICKET"))
        val coded = event("b", "b1", "BAN-A vs HK", "LIVE")

        val merged = SportsLiveItemBucketer.merge(listOf(full, coded), listOf("a", "b"), now).single()

        assertEquals(listOf("b1"), merged.alternates.map { it.id })
        val basketball = com.nuvio.tv.core.sportslive.scores.MySports(mapOf(com.nuvio.tv.core.sportslive.scores.MySport.BASKETBALL to emptySet()))
        assertFalse(SportsLiveMySportsScope.shows(coded, basketball))
    }

    @Test
    fun `detail lines never name a non-sport tag and Hong Kong China is Hong Kong`() {
        assertNull(SportsLiveTeams.sportLabelOf(claimedEvent("OTHER", "other")))
        assertEquals("Events", SportsLiveTeams.sportLabelOf(claimedEvent("OTHER", "Events")))
        assertEquals("Cricket", SportsLiveTeams.sportLabelOf(claimedEvent("Cricket", "other")))
        assertTrue(SportsLiveTeams.matches("Hong Kong China", "HK"))
    }

    private fun claimedEvent(genre: String, catalogName: String) =
        SportsLiveItemBucketer.describe(meta("x", "A vs B", "LIVE", listOf(genre)), claimed("a", "c", catalogName, SportsLiveKind.EVENTS), now)

    // Wrestling

    @Test
    fun `wrestling an addon files under MMA is wrestling`() {
        val events = claimed("a", "mma", "MMA", SportsLiveKind.EVENTS)
        fun sport(name: String, genres: List<String>) =
            SportsLiveItemBucketer.describe(meta(name, name, "LIVE", genres), events, now).sport

        assertEquals("Wrestling", sport("AEW Dynamite", listOf("MMA")))
        assertEquals("Wrestling", sport("WWE Monday Night Raw", listOf("MMA")))
        assertEquals("Wrestling", sport("NXT", emptyList()))
        assertEquals("Wrestling", sport("SmackDown", listOf("MMA")))
        assertEquals("Wrestling", sport("AEW Collision", listOf("Sports")))
        // A real MMA card stays MMA; a show word under another sport is left alone.
        assertEquals("MMA", sport("UFC 310: Pantoja vs Asakura", listOf("MMA")))
        assertEquals("Soccer", sport("Dynamo Kyiv vs Rampage FC", listOf("Soccer")))
        assertEquals(SportsLiveSportKind.WRESTLING, SportsLiveSportKind.of("SmackDown"))
    }

    // TBD placeholders

    @Test
    fun `TBD vs TBD is hidden from Live now and Upcoming, a one-sided TBD is kept`() {
        val liveTbd = event("a", "t1", "TBD vs TBD", "LIVE", listOf("COLLEGE"))
        val upcomingTbd = event("a", "t2", "TBA v TBA", "18 Nov 2026 · 20:00 UTC", listOf("COLLEGE"))
        val oneSided = event("a", "t3", "Lakers vs TBD", "18 Nov 2026 · 20:00 UTC", listOf("Basketball"))
        val tabs = SportsLiveItemBucketer.bucket(listOf(liveTbd, upcomingTbd, oneSided), now)

        assertTrue(tabs.getValue(SportsLiveTab.LIVE_NOW).isEmpty())
        assertEquals(listOf("t3"), tabs.getValue(SportsLiveTab.UPCOMING).map { it.id })
        assertTrue(SportsLiveTeams.isPlaceholderGame(TeamPair("College TBD", "To be announced")))
        assertFalse(SportsLiveTeams.isPlaceholderGame(TeamPair("Lakers", "TBD")))
    }

    @Test
    fun `different TBD vs TBD games at one time never merge`() {
        val a = event("a", "a1", "TBD vs TBD", "18 Nov 2026 · 20:00 UTC")
        val b = event("b", "b1", "TBD vs TBD", "18 Nov 2026 · 20:00 UTC")

        val merged = SportsLiveItemBucketer.merge(listOf(a, b), listOf("a", "b"), now)

        assertEquals(2, merged.size)
        assertTrue(merged.all { it.alternates.isEmpty() })
    }

    // Duplicates

    @Test
    fun `copies merge across sport aliases`() {
        val soccer = event("a", "a1", "Vasco da Gama vs Internacional", "18 Nov 2026 · 21:30 UTC", listOf("Soccer"))
        val football = event("b", "b1", "Vasco da Gama vs Internacional", "18 Nov 2026 · 21:30 UTC", listOf("Football"))
        val futbol = event("c", "c1", "Vasco da Gama vs Internacional", "18 Nov 2026 · 21:40 UTC", listOf("Futbol"))
        val other = event("d", "d1", "Vasco da Gama vs Internacional", "18 Nov 2026 · 21:30 UTC", listOf("OTHER"))

        val upcoming = SportsLiveItemBucketer.bucketTab(
            listOf(soccer, football, futbol, other), SportsLiveTab.UPCOMING, now, addonOrder = listOf("a", "b", "c", "d")
        )

        assertEquals(listOf("a1"), upcoming.map { it.id })
        assertEquals(listOf("b1", "c1", "d1"), upcoming.single().alternates.map { it.id })
    }

    @Test
    fun `different sports still do not merge`() {
        val soccer = event("a", "a1", "Arsenal vs Chelsea", "LIVE", listOf("Soccer"))
        val basketball = event("b", "b1", "Arsenal vs Chelsea", "LIVE", listOf("Basketball"))

        assertEquals(2, SportsLiveItemBucketer.merge(listOf(soccer, basketball), listOf("a", "b"), now).size)
    }

    @Test
    fun `an untimed copy merges with a timed one today and the merged row keeps the start and the reminder`() {
        // The first addon's copies carry no time; the third names the kickoff.
        val untimed = event("a", "a1", "Vasco da Gama vs Internacional", null, listOf("Soccer"))
        val untimedToo = event("b", "b1", "Vasco da Gama vs Internacional", null)
        val timed = event("c", "c1", "Vasco da Gama vs Internacional", "18 Nov 2026 · 21:30 UTC", listOf("Football"))

        val upcoming = SportsLiveItemBucketer.bucketTab(
            listOf(untimed, untimedToo, timed), SportsLiveTab.UPCOMING, now, addonOrder = listOf("a", "b", "c")
        )

        val row = upcoming.single()
        assertEquals("a1", row.id)
        assertEquals(setOf("b1", "c1"), row.alternates.map { it.id }.toSet())
        assertEquals(timed.startMs, row.startMs)
        assertTrue(row.startHasTimeOfDay)
        // The bell is offered on the merged row, and a reminder set on any copy shows on it.
        assertTrue(GameReminders.canRemind(row, now))
        val onTimedCopy = GameReminder(setOf(timed.streamKey), timed.meta.name, null, null, timed.startMs!!)
        assertTrue(RemindersState(reminders = listOf(onTimedCopy)).isReminded(row))
    }

    @Test
    fun `an untimed copy does not merge with a timed one on another day`() {
        val untimed = event("a", "a1", "Vasco da Gama vs Internacional", null)
        val nextWeek = event("b", "b1", "Vasco da Gama vs Internacional", "25 Nov 2026 · 21:30 UTC")

        assertEquals(2, SportsLiveItemBucketer.merge(listOf(untimed, nextWeek), listOf("a", "b"), now).size)
    }

    // Helpers

    private fun event(addonId: String, id: String, name: String, releaseInfo: String?, genres: List<String> = emptyList()) =
        SportsLiveItemBucketer.describe(meta(id, name, releaseInfo, genres), claimed(addonId, "events", "Events", SportsLiveKind.EVENTS), now)

    private fun meta(id: String, name: String, releaseInfo: String? = null, genres: List<String> = emptyList()) = MetaPreview(
        id = id, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
        posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
        releaseInfo = releaseInfo, imdbRating = null, genres = genres
    )

    private fun catalog(type: String, id: String, name: String) =
        CatalogDescriptor(type = ContentType.fromString(type), rawType = type, id = id, name = name)

    private fun addon(id: String, catalogs: List<CatalogDescriptor>) = Addon(
        id = id, name = "Addon $id", version = "1.0.0", description = null, logo = null,
        baseUrl = "https://example.invalid", catalogs = catalogs, types = catalogs.map { it.type }, resources = emptyList()
    )

    private fun claimed(addonId: String, id: String, name: String, kind: SportsLiveKind): ClaimedCatalog {
        val catalog = catalog("sport", id, name)
        return ClaimedCatalog(addon(addonId, listOf(catalog)), catalog, "${addonId}_sport_$id", kind, false)
    }
}
