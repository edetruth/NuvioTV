package com.nuvio.tv.core.sportslive

import com.nuvio.tv.data.mapper.toDomainOrNull
import com.nuvio.tv.data.remote.dto.CatalogResponseDto
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogStats
import com.nuvio.tv.domain.model.ClaimedCatalog
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsLiveItemBucketerTest {
    private val now = Instant.parse("2026-11-15T14:30:00Z").toEpochMilli()
    private val soonEmoji = "🔜" // 🔜
    private val liveEmoji = "🔴" // 🔴

    private val highflyLive = claimed("sport", "sports_live", "Live Now", SportsLiveKind.EVENTS)
    private val highflyToday = claimed("sport", "sports_today", "Today", SportsLiveKind.EVENTS)
    private val highflyFootball = claimed("sport", "sports_football", "Football", SportsLiveKind.EVENTS)
    private val cncEvents = claimed("tv", "cnc_SportzXLiveEvents_tv", "⚡SportzX Live Events (tv)", SportsLiveKind.EVENTS)
    private val cncLiveTv = claimed("tv", "cnc_EasyPlayLiveTV_tv", "EasyPlay Live TV (tv)", SportsLiveKind.CHANNELS)

    @Test
    fun `highfly live fixture all land in LIVE_NOW`() {
        val metas = loadMetas("highfly_live.json", "sport")
        val items = metas.map { SportsLiveItemBucketer.describe(it, highflyLive, now) }

        assertTrue(items.all { it.isLive })
        assertEquals(metas.size, SportsLiveItemBucketer.bucket(items, now).getValue(SportsLiveTab.LIVE_NOW).size)
    }

    @Test
    fun `leaf items are channels and appear in CHANNELS and LIVE_NOW`() {
        val items = describeAll(loadMetas("highfly_today.json", "sport"), highflyToday)
        val leaf = items.single { it.id == "leaf:ca-tsn-44343" }
        val tabs = SportsLiveItemBucketer.bucket(items, now)

        assertTrue(leaf.isChannel)
        assertTrue(tabs.getValue(SportsLiveTab.CHANNELS).any { it.id == leaf.id })
        assertTrue(tabs.getValue(SportsLiveTab.LIVE_NOW).any { it.id == leaf.id })
    }

    @Test
    fun `leaf channels from fixture get country groups`() {
        val channels = describeAll(loadMetas("highfly_live.json", "sport"), highflyLive).filter { it.isChannel }

        assertTrue(channels.isNotEmpty())
        assertEquals(setOf("CA", "UK", "US"), channels.map { it.group }.toSet())
    }

    @Test
    fun `non channel items have null group`() {
        val events = describeAll(loadMetas("highfly_live.json", "sport"), highflyLive).filter { !it.isChannel }

        assertTrue(events.isNotEmpty())
        assertTrue(events.all { it.group == null })
    }

    @Test
    fun `streamed event with LIVE NOW is not a channel`() {
        val items = describeAll(loadMetas("highfly_today.json", "sport"), highflyToday)
        val event = items.single { it.id == "streamed:football-tenerife-ceuta-20772" }

        assertFalse(event.isChannel)
        assertTrue(event.isLive)
        assertFalse(SportsLiveItemBucketer.bucket(items, now).getValue(SportsLiveTab.CHANNELS).any { it.id == event.id })
    }

    @Test
    fun `future start goes to UPCOMING sorted by start`() {
        val items = describeAll(
            listOf(
                meta("late", "Late game", releaseInfo = "15 Nov 2026 · 16:00 UTC"),
                meta("early", "Early game", releaseInfo = "15 Nov 2026 · 15:00 UTC")
            ),
            highflyFootball
        )
        val tabs = SportsLiveItemBucketer.bucket(items, now)

        assertEquals(listOf("early", "late"), tabs.getValue(SportsLiveTab.UPCOMING).map { it.id })
        assertTrue(tabs.getValue(SportsLiveTab.LIVE_NOW).isEmpty())
    }

    @Test
    fun `highfly today timed items land in UPCOMING in ascending start order`() {
        val metas = loadMetas("highfly_today.json", "sport")
        val timedIds = metas.filter { it.releaseInfo != "LIVE" }.map { it.id }.toSet()
        val upcoming = SportsLiveItemBucketer.bucket(describeAll(metas, highflyToday), now)
            .getValue(SportsLiveTab.UPCOMING)

        assertTrue(timedIds.isNotEmpty())
        assertEquals(timedIds, upcoming.map { it.id }.toSet())
        val starts = upcoming.map { it.startMs!! }
        assertEquals(starts.sorted(), starts)
        assertTrue(starts.all { it > now })
    }

    @Test
    fun `event that started 2 hours ago with a time of day is live`() {
        val item = SportsLiveItemBucketer.describe(
            meta("started", "Started game", releaseInfo = "15 Nov 2026 · 12:30 UTC"),
            highflyFootball,
            now
        )
        val tabs = SportsLiveItemBucketer.bucket(listOf(item), now)

        assertTrue(item.isLive)
        assertTrue(item.startHasTimeOfDay)
        assertEquals(listOf("started"), tabs.getValue(SportsLiveTab.LIVE_NOW).map { it.id })
        assertTrue(tabs.getValue(SportsLiveTab.UPCOMING).isEmpty())
    }

    @Test
    fun `event that started 1 minute ago is live`() {
        val item = SportsLiveItemBucketer.describe(
            meta("kickoff", "Kickoff", released = "2026-11-15T14:29:00Z"),
            highflyFootball,
            now
        )

        assertTrue(item.isLive)
        assertEquals(listOf("kickoff"), SportsLiveItemBucketer.bucket(listOf(item), now).getValue(SportsLiveTab.LIVE_NOW).map { it.id })
    }

    @Test
    fun `event that started 5 hours ago is in neither LIVE_NOW nor UPCOMING`() {
        // "Today" matches the UPCOMING catalog keywords, so this also checks that a known past start wins over the keyword.
        val item = SportsLiveItemBucketer.describe(
            meta("old", "Old game", releaseInfo = "15 Nov 2026 · 09:30 UTC"),
            highflyToday,
            now
        )
        val tabs = SportsLiveItemBucketer.bucket(listOf(item), now)

        assertFalse(item.isLive)
        assertTrue(tabs.getValue(SportsLiveTab.LIVE_NOW).isEmpty())
        assertTrue(tabs.getValue(SportsLiveTab.UPCOMING).isEmpty())
        assertTrue(tabs.getValue(SportsLiveTab.REPLAYS).isEmpty())
    }

    @Test
    fun `included catalog's past items land in Replays`() {
        val included = claimed("movie", "top", "Popular", SportsLiveKind.EVENTS, viaOverride = true)
        val items = describeAll(
            listOf(
                meta("old", "Old game", releaseInfo = "15 Nov 2026 · 09:30 UTC"),
                meta("film", "Old film", released = "2024-03-01T00:00:00.000Z"),
                meta("live", "Live game", releaseInfo = "LIVE"),
                meta("next", "Next game", releaseInfo = "15 Nov 2026 · 18:00 UTC"),
                meta("tba", "Untimed", releaseInfo = "2025")
            ),
            included
        )
        val tabs = SportsLiveItemBucketer.bucket(items, now)

        assertTrue(items.all { it.viaOverride })
        assertEquals(listOf("old", "film"), tabs.getValue(SportsLiveTab.REPLAYS).map { it.id })
        assertEquals(listOf("live"), tabs.getValue(SportsLiveTab.LIVE_NOW).map { it.id })
        assertEquals(listOf("next", "tba"), tabs.getValue(SportsLiveTab.UPCOMING).map { it.id })
    }

    @Test
    fun `events item with bare date yesterday is not live`() {
        val item = SportsLiveItemBucketer.describe(
            meta("yesterday", "Yesterday game", releaseInfo = "2026-11-14"),
            highflyFootball,
            now
        )

        assertFalse(item.startHasTimeOfDay)
        assertFalse(item.isLive)
        assertTrue(SportsLiveItemBucketer.bucket(listOf(item), now).getValue(SportsLiveTab.LIVE_NOW).isEmpty())
    }

    @Test
    fun `releaseInfo with the word LIVE among emoji is live`() {
        val item = SportsLiveItemBucketer.describe(meta("e", "Game", releaseInfo = "🔴 LIVE"), highflyFootball, now)

        assertTrue(item.isLive)
        assertFalse(item.isChannel)
        assertFalse(SportsLiveItemBucketer.describe(meta("f", "Game", releaseInfo = "LIVERPOOL"), highflyFootball, now).isLive)
    }

    @Test
    fun `description with the phrase live now in any case is live`() {
        val item = SportsLiveItemBucketer.describe(
            meta("e", "Game", description = "American Football · Live now\nRaiders v Seahawks"),
            highflyFootball,
            now
        )

        assertTrue(item.isLive)
        assertFalse(item.isChannel)
        assertFalse(
            SportsLiveItemBucketer.describe(meta("f", "Game", description = "Alive nowhere"), highflyFootball, now).isLive
        )
    }

    @Test
    fun `nebula live fixture all land in LIVE_NOW from the live and schedule catalogs`() {
        val metas = loadMetas("nebula_live.json", "sports")
        listOf(
            claimed("sports", "streamed-events-live-now", "Nebula Sports: Live Now", SportsLiveKind.EVENTS),
            claimed("sports", "all", "Nebula Sports: Schedule", SportsLiveKind.EVENTS)
        ).forEach { catalog ->
            val items = describeAll(metas, catalog)
            val tabs = SportsLiveItemBucketer.bucket(items, now)

            assertTrue(items.all { it.isLive && !it.isChannel })
            assertEquals(metas.map { it.id }.toSet(), tabs.getValue(SportsLiveTab.LIVE_NOW).map { it.id }.toSet())
        }
    }

    @Test
    fun `nebula replays fixture lands in REPLAYS only with timed starts`() {
        val items = describeAll(
            loadMetas("nebula_replays.json", "sports"),
            claimed("sports", "streamed-replays", "Replays", SportsLiveKind.REPLAY)
        )
        val tabs = SportsLiveItemBucketer.bucket(items, now)

        assertEquals(20, tabs.getValue(SportsLiveTab.REPLAYS).size)
        assertTrue(items.all { it.startMs != null && it.startHasTimeOfDay })
        assertTrue(tabs.getValue(SportsLiveTab.LIVE_NOW).isEmpty())
        assertTrue(tabs.getValue(SportsLiveTab.UPCOMING).isEmpty())
        assertTrue(tabs.getValue(SportsLiveTab.CHANNELS).isEmpty())
    }

    @Test
    fun `lowercase live on in a description does not make an event a channel`() {
        val item = SportsLiveItemBucketer.describe(
            meta("espn", "Big game", releaseInfo = "15 Nov 2026 · 18:00 UTC", description = "Watch it live on ESPN+"),
            highflyFootball,
            now
        )

        assertFalse(item.isChannel)
    }

    @Test
    fun `events item with no time and not live lands in upcoming as time TBA`() {
        val popular = claimed("movie", "top", "Popular", SportsLiveKind.EVENTS)
        val items = describeAll(
            listOf(
                meta("untimed", "Untimed film", releaseInfo = "2025"),
                meta("timed", "Timed game", releaseInfo = "15 Nov 2026 · 18:00 UTC")
            ),
            popular
        )
        val tabs = SportsLiveItemBucketer.bucket(items, now)
        val upcomingTab = tabs.getValue(SportsLiveTab.UPCOMING)

        assertEquals(listOf("timed", "untimed"), upcomingTab.map { it.id })
        assertNull(upcomingTab.last().startMs)
        assertFalse(upcomingTab.last().isLive)
        assertTrue(tabs.getValue(SportsLiveTab.LIVE_NOW).isEmpty())
    }

    @Test
    fun `unparseable time from an upcoming catalog goes last with null start`() {
        val upcoming = claimed("sport", "events_upcoming", "Upcoming", SportsLiveKind.EVENTS)
        val items = describeAll(
            listOf(
                meta("tba", "A game TBA", releaseInfo = "TBD"),
                meta("timed", "Timed game", releaseInfo = "15 Nov 2026 · 18:00 UTC")
            ),
            upcoming
        )
        val upcomingTab = SportsLiveItemBucketer.bucket(items, now).getValue(SportsLiveTab.UPCOMING)

        assertEquals(listOf("timed", "tba"), upcomingTab.map { it.id })
        assertNull(upcomingTab.last().startMs)
    }

    @Test
    fun `replay fixtures land in REPLAYS only`() {
        val items = describeAll(
            loadMetas("replay_football.json", "movie"),
            claimed("movie", "football", "Football", SportsLiveKind.REPLAY)
        ) + describeAll(
            loadMetas("replay_football_teams.json", "series"),
            claimed("series", "football_teams", "Football Teams", SportsLiveKind.REPLAY)
        )
        val tabs = SportsLiveItemBucketer.bucket(items, now)

        assertEquals(items.size, tabs.getValue(SportsLiveTab.REPLAYS).size)
        assertTrue(tabs.getValue(SportsLiveTab.LIVE_NOW).isEmpty())
        assertTrue(tabs.getValue(SportsLiveTab.UPCOMING).isEmpty())
        assertTrue(tabs.getValue(SportsLiveTab.CHANNELS).isEmpty())
    }

    @Test
    fun `sport comes from first genre then category then catalog name`() {
        assertEquals("Football", SportsLiveItemBucketer.sportOf(meta("a", "A", genres = listOf("Football", "NFL")), "Today"))
        assertEquals("Basketball", SportsLiveItemBucketer.sportOf(meta("b", "B"), "Basketball"))
    }

    @Test
    fun `live first then by start then by name within a tab`() {
        val channel = "Live on Channel 1"
        val items = describeAll(
            listOf(
                meta("tba-b", "Bravo", releaseInfo = "TBD", description = channel),
                meta("timed-late", "Zulu", releaseInfo = "15 Nov 2026 · 17:00 UTC", description = channel),
                meta("live", "Yankee", releaseInfo = "LIVE", description = channel),
                meta("tba-a", "alpha", releaseInfo = "TBD", description = channel),
                meta("timed-early", "Xray", releaseInfo = "15 Nov 2026 · 15:00 UTC", description = channel)
            ),
            highflyFootball
        )
        val channels = SportsLiveItemBucketer.bucket(items, now).getValue(SportsLiveTab.CHANNELS)

        assertEquals(listOf("live", "timed-early", "timed-late", "tba-a", "tba-b"), channels.map { it.id })
    }

    @Test
    fun `same item from two catalogs of one addon appears once per tab`() {
        val live = meta("dup", "Granada vs Las Palmas", releaseInfo = "LIVE", description = "Live on Channel 1")
        val items = listOf(
            SportsLiveItemBucketer.describe(live, highflyLive, now),
            SportsLiveItemBucketer.describe(live, highflyToday, now)
        )
        val tabs = SportsLiveItemBucketer.bucket(items, now)

        val liveNow = tabs.getValue(SportsLiveTab.LIVE_NOW)
        assertEquals(listOf("dup"), liveNow.map { it.id })
        assertEquals("highfly_sport_sports_live", liveNow.single().catalogKey)
        assertEquals(listOf("dup"), tabs.getValue(SportsLiveTab.CHANNELS).map { it.id })
    }

    @Test
    fun `CNCVerse soon items are upcoming time TBA and live dot items are live, both with clean names`() {
        val raw = loadMetas("cnc_live_events.json", "tv")
        val items = describeAll(raw, cncEvents)
        val tabs = SportsLiveItemBucketer.bucket(items, now)
        val soonIds = raw.filter { it.name.startsWith(soonEmoji) }.map { it.id }.toSet()
        val liveIds = raw.filter { it.name.startsWith(liveEmoji) }.map { it.id }.toSet()
        val upcoming = tabs.getValue(SportsLiveTab.UPCOMING).map { it.id }.toSet()
        val liveNow = tabs.getValue(SportsLiveTab.LIVE_NOW).map { it.id }.toSet()

        assertTrue(soonIds.isNotEmpty() && liveIds.isNotEmpty())
        assertTrue(items.filter { it.id in soonIds }.none { it.isLive || it.startMs != null })
        assertTrue(upcoming.containsAll(soonIds))
        assertTrue(liveNow.intersect(soonIds).isEmpty())
        assertTrue(items.filter { it.id in liveIds }.all { it.isLive })
        assertTrue(liveNow.containsAll(liveIds))
        assertTrue(items.none { it.meta.name.startsWith(soonEmoji) || it.meta.name.startsWith(liveEmoji) })
    }

    @Test
    fun `stripped name keeps the match for team parsing`() {
        val item = SportsLiveItemBucketer.describe(meta("afg", "$soonEmoji  AFG vs ZIM"), cncEvents, now)

        assertEquals("AFG vs ZIM", item.meta.name)
        assertEquals(listOf("AFG", "ZIM"), item.meta.name.split(" vs "))
        assertFalse(item.isLive)
    }

    @Test
    fun `live dot is live even with a future start and soon is not live even with a LIVE marker`() {
        val live = SportsLiveItemBucketer.describe(
            meta("l", "$liveEmoji A vs B", releaseInfo = "15 Nov 2026 · 18:00 UTC"), cncEvents, now
        )
        val soon = SportsLiveItemBucketer.describe(meta("s", "$soonEmoji C vs D", releaseInfo = "LIVE"), cncEvents, now)

        assertTrue(live.isLive)
        assertEquals("A vs B", live.meta.name)
        assertFalse(soon.isLive)
    }

    @Test
    fun `CNCVerse Live TV channels are channels, never live, grouped by prefix`() {
        val items = describeAll(loadMetas("cnc_live_tv_sample.json", "tv"), cncLiveTv)
        val tabs = SportsLiveItemBucketer.bucket(items, now)

        assertEquals(200, items.size)
        assertTrue(items.all { it.isChannel && !it.isLive })
        assertTrue(tabs.getValue(SportsLiveTab.LIVE_NOW).isEmpty())
        assertEquals(items.map { it.id }.toSet(), tabs.getValue(SportsLiveTab.CHANNELS).map { it.id }.toSet())
        assertTrue(items.map { it.group }.toSet().containsAll(setOf("UK", "US", "ASIA-IN")))
    }

    @Test
    fun `plain live TV addon items are channels grouped by catalog name, never live or upcoming`() {
        // Ultra MAX TV: genres ["Live TV"], no time, no live marker, in a catalog the policy claims as events.
        val ultraUk = claimed("tv", "tv_uk", "🇬🇧 United Kingdom TV", SportsLiveKind.EVENTS)
        val items = describeAll(loadMetas("ultra_tv_uk.json", "tv"), ultraUk)
        val tabs = SportsLiveItemBucketer.bucket(items, now)

        assertEquals(91, items.size)
        assertTrue(items.all { it.isChannel && !it.isLive })
        assertTrue(tabs.getValue(SportsLiveTab.LIVE_NOW).isEmpty())
        assertTrue(tabs.getValue(SportsLiveTab.UPCOMING).isEmpty())
        assertEquals(items.map { it.id }.toSet(), tabs.getValue(SportsLiveTab.CHANNELS).map { it.id }.toSet())
        assertEquals(setOf("UNITED KINGDOM TV"), items.map { it.group }.toSet())
    }

    @Test
    fun `channels catalog items marked live stay out of LIVE_NOW`() {
        val nebulaChannels = claimed("sports", "streamed-events-dlhd-channels", "Channels", SportsLiveKind.CHANNELS)
        val items = describeAll(loadMetas("nebula_channels.json", "sports"), nebulaChannels)
        val tabs = SportsLiveItemBucketer.bucket(items, now)

        assertEquals(30, items.size)
        assertTrue(items.all { it.isChannel })
        assertTrue(items.any { it.isLive })
        assertTrue(tabs.getValue(SportsLiveTab.LIVE_NOW).isEmpty())
        assertTrue(tabs.getValue(SportsLiveTab.UPCOMING).isEmpty())
        assertEquals(items.map { it.id }.toSet(), tabs.getValue(SportsLiveTab.CHANNELS).map { it.id }.toSet())
    }

    @Test
    fun `Nebula channels without a prefix group under the addon name, not the generic catalog name`() {
        val base = claimed("sports", "streamed-events-dlhd-channels", "Channels", SportsLiveKind.CHANNELS)
        val nebula = base.copy(addon = base.addon.copy(name = "Nebula Sports"))
        val items = describeAll(loadMetas("nebula_channels.json", "sports"), nebula)

        assertTrue(items.any { it.group == "NEBULA SPORTS" })
        assertTrue(items.none { it.group == "CHANNELS" })
    }

    @Test
    fun `a Live TV genre makes an events catalog item a channel, which shows live only from an events catalog`() {
        val liveChannel = meta("ch", "Sky Sports", releaseInfo = "LIVE", genres = listOf("Live TV"))
        val fromEvents = SportsLiveItemBucketer.describe(liveChannel, highflyLive, now)
        val fromChannels = SportsLiveItemBucketer.describe(liveChannel, cncLiveTv, now)

        assertTrue(fromEvents.isChannel && fromChannels.isChannel)
        assertEquals(listOf("ch"), SportsLiveItemBucketer.bucket(listOf(fromEvents), now).getValue(SportsLiveTab.LIVE_NOW).map { it.id })
        assertTrue(SportsLiveItemBucketer.bucket(listOf(fromChannels), now).getValue(SportsLiveTab.LIVE_NOW).isEmpty())
        assertFalse(SportsLiveItemBucketer.describe(meta("x", "X", genres = listOf("Live TVs")), highflyLive, now).isChannel)
    }

    @Test
    fun `replays are sorted newest first with undated last`() {
        val replays = claimed("sports", "streamed-replays", "Replays", SportsLiveKind.REPLAY)
        val items = describeAll(
            listOf(
                meta("undated", "Undated", releaseInfo = "TBD"),
                meta("older", "Older", releaseInfo = "14 Nov 2026 · 18:00 UTC"),
                meta("newer", "Newer", releaseInfo = "15 Nov 2026 · 12:00 UTC")
            ),
            replays
        ) + describeAll(loadMetas("nebula_replays.json", "sports"), replays)
        val tab = SportsLiveItemBucketer.bucket(items, now).getValue(SportsLiveTab.REPLAYS)
        val starts = tab.mapNotNull { it.startMs }

        assertEquals(starts.sortedDescending(), starts)
        assertEquals("undated", tab.last().id)
        assertTrue(tab.indexOfFirst { it.id == "newer" } < tab.indexOfFirst { it.id == "older" })
    }

    @Test
    fun `CNCVerse finished items are in no tab and lose the emoji`() {
        val doneEmoji = "✅"
        val raw = loadMetas("cnc_live_events.json", "tv")
        val doneIds = raw.filter { it.name.startsWith(doneEmoji) }.map { it.id }.toSet()
        val items = describeAll(raw, cncEvents)
        val tabs = SportsLiveItemBucketer.bucket(items, now)

        assertTrue(doneIds.isNotEmpty())
        assertTrue(items.filter { it.id in doneIds }.all { it.isFinished && !it.isLive })
        assertTrue(items.none { it.meta.name.startsWith(doneEmoji) })
        assertTrue(tabs.values.flatten().none { it.id in doneIds })
        assertTrue(tabs.getValue(SportsLiveTab.LIVE_NOW).isNotEmpty())
    }

    @Test
    fun `channel groups differing only in case make one filter`() {
        val items = describeAll(
            listOf(meta("a", "BiH| FACE TV"), meta("b", "BIH| N1"), meta("c", "bih| HAYAT")),
            cncLiveTv
        )
        val groups = SportsLiveItemBucketer.bucket(items, now).getValue(SportsLiveTab.CHANNELS).mapNotNull { it.group }

        assertEquals(listOf("BIH"), groups.distinct())
    }

    @Test
    fun `describe parses teams for events but never for channels`() {
        val event = SportsLiveItemBucketer.describe(meta("e", "Celtics vs 76ers"), highflyToday, now)
        val channel = SportsLiveItemBucketer.describe(meta("c", "Sky Sports - HD"), cncLiveTv, now)
        val other = SportsLiveItemBucketer.describe(meta("o", "NFL RedZone"), highflyToday, now)

        assertEquals(TeamPair("Celtics", "76ers"), event.teams)
        assertNull(channel.teams)
        assertNull(other.teams)
        assertFalse(event.followed)
    }

    @Test
    fun `pinFollowed marks followed games and moves them first in order`() {
        val items = listOf("Lakers vs Suns", "Celtics vs Knicks", "Heat vs Bulls", "76ers vs Celtics", "NFL RedZone")
            .mapIndexed { i, name -> SportsLiveItemBucketer.describe(meta("$i", name), highflyToday, now) }

        val pinned = SportsLiveItemBucketer.pinFollowed(items, listOf(FollowedTeam("Boston Celtics")))

        assertEquals(listOf("1", "3", "0", "2", "4"), pinned.map { it.id })
        assertEquals(listOf(true, true, false, false, false), pinned.map { it.followed })
        assertEquals(items, SportsLiveItemBucketer.pinFollowed(items, emptyList()))
    }

    @Test
    fun `describe copies addon and catalog details`() {
        val item = SportsLiveItemBucketer.describe(meta("x", "X"), highflyToday, now)

        assertEquals("highfly", item.addonId)
        assertEquals("Sports Streams", item.addonName)
        assertEquals("https://highfly.example.com", item.addonBaseUrl)
        assertEquals("highfly_sport_sports_today", item.catalogKey)
        assertEquals("Today", item.catalogName)
        assertEquals(SportsLiveKind.EVENTS, item.kind)
    }

    @Test
    fun `signal-less catalog named Live Events is all live`() {
        val metas = listOf(meta("a", "Team A vs Team B"), meta("b", "Team C vs Team D"))
        for (name in listOf("Live Events", "Fútbol en vivo", "Direct")) {
            val items = SportsLiveItemBucketer.describeCatalog(metas, claimed("sport", "x", name, SportsLiveKind.EVENTS), now)

            assertTrue(name, items.all { it.isLive })
            assertEquals(name, 2, SportsLiveItemBucketer.bucket(items, now).getValue(SportsLiveTab.LIVE_NOW).size)
        }
    }

    @Test
    fun `signal-less catalog named Agenda de hoy is upcoming Time TBA`() {
        val catalog = claimed("sport", "agenda", "Agenda de hoy", SportsLiveKind.EVENTS)
        val items = SportsLiveItemBucketer.describeCatalog(listOf(meta("a", "A vs B"), meta("b", "C vs D")), catalog, now)
        val tabs = SportsLiveItemBucketer.bucket(items, now)

        assertTrue(items.none { it.isLive || it.startMs != null })
        assertEquals(listOf("a", "b"), tabs.getValue(SportsLiveTab.UPCOMING).map { it.id })
        assertTrue(tabs.getValue(SportsLiveTab.LIVE_NOW).isEmpty())
    }

    @Test
    fun `signal-less catalog named Full Match Replays is replays`() {
        for (name in listOf("Full Match Replays", "Full Match", "Melhores Momentos")) {
            val catalog = claimed("sport", "fm", name, SportsLiveKind.EVENTS)
            val items = SportsLiveItemBucketer.describeCatalog(listOf(meta("a", "A vs B")), catalog, now)
            val tabs = SportsLiveItemBucketer.bucket(items, now)

            assertEquals(name, listOf("a"), tabs.getValue(SportsLiveTab.REPLAYS).map { it.id })
            assertTrue(name, tabs.getValue(SportsLiveTab.UPCOMING).isEmpty())
        }
    }

    @Test
    fun `signal-less catalog with an unknown name is unchanged`() {
        val catalog = claimed("sport", "misc", "Misc", SportsLiveKind.EVENTS)
        val metas = listOf(meta("a", "A vs B"))

        assertEquals(
            metas.map { SportsLiveItemBucketer.describe(it, catalog, now) },
            SportsLiveItemBucketer.describeCatalog(metas, catalog, now)
        )
    }

    @Test
    fun `a catalog with one signalled item keeps the per-item rules`() {
        val metas = listOf(
            meta("timed", "Timed", releaseInfo = "15 Nov 2026 · 18:00 UTC"),
            meta("plain", "Plain")
        )
        for ((name, kind) in listOf("Full Match" to SportsLiveKind.EVENTS, "Channels" to SportsLiveKind.CHANNELS)) {
            val catalog = claimed("sport", "c", name, kind)
            assertEquals(
                name,
                metas.map { SportsLiveItemBucketer.describe(it, catalog, now) },
                SportsLiveItemBucketer.describeCatalog(metas, catalog, now)
            )
        }
        // A channels catalog's items are channels: a signal, so "Live" in its name changes nothing.
        val liveTv = claimed("tv", "ltv", "Live TV", SportsLiveKind.CHANNELS)
        assertTrue(SportsLiveItemBucketer.describeCatalog(listOf(meta("ch", "ESPN")), liveTv, now).none { it.isLive })
        val liveMarked = claimed("sport", "l", "Full Match", SportsLiveKind.EVENTS)
        val marked = SportsLiveItemBucketer.describeCatalog(
            listOf(meta("m", "M", releaseInfo = "LIVE"), meta("p", "P")),
            liveMarked,
            now
        )
        assertTrue(marked.none { it.kind == SportsLiveKind.REPLAY })
    }

    @Test
    fun `stats count each item once on a mixed catalog`() {
        val items = describeAll(
            listOf(
                meta("live1", "Live 1", releaseInfo = "LIVE"),
                meta("live2", "$liveEmoji Live 2"),
                meta("timed", "Timed", releaseInfo = "15 Nov 2026 · 18:00 UTC"),
                meta("tba", "TBA"),
                meta("channel", "Channel", description = "IPTV feed"),
                meta("old", "Old", releaseInfo = "15 Nov 2026 · 04:00 UTC"),
                meta("done", "✅ Done")
            ),
            highflyFootball
        )

        assertEquals(
            CatalogStats(live = 2, timed = 1, upcomingTba = 1, channels = 1, replays = 0, unrecognised = 0, ended = 1),
            SportsLiveItemBucketer.stats(items, now)
        )
        val replays = describeAll(listOf(meta("r", "R")), claimed("sports", "r", "Replays", SportsLiveKind.REPLAY))
        assertEquals(CatalogStats(replays = 1), SportsLiveItemBucketer.stats(replays, now))
    }

    @Test
    fun `stats count past timed events as ended, not unrecognised`() {
        val items = describeAll(
            listOf(
                meta("old", "Old", releaseInfo = "15 Nov 2026 · 04:00 UTC"),
                meta("older", "Older", releaseInfo = "14 Nov 2026 · 20:00 UTC")
            ),
            highflyFootball
        )

        assertEquals(CatalogStats(ended = 2), SportsLiveItemBucketer.stats(items, now))
    }

    @Test
    fun `a Live TV channel in a Live-named events catalog with no item signal is a channel, not live`() {
        val usaLive = claimed("tv", "usa_live", "USA Live", SportsLiveKind.EVENTS)
        val item = SportsLiveItemBucketer.describe(meta("ch", "ESPN", genres = listOf("Live TV")), usaLive, now)
        val tabs = SportsLiveItemBucketer.bucket(listOf(item), now)

        assertFalse(item.isLive)
        assertTrue(tabs.getValue(SportsLiveTab.LIVE_NOW).isEmpty())
        assertEquals(listOf("ch"), tabs.getValue(SportsLiveTab.CHANNELS).map { it.id })
        // An event in the same catalog is still live from the catalog name.
        assertTrue(SportsLiveItemBucketer.describe(meta("ev", "A vs B"), usaLive, now).isLive)
    }

    @Test
    fun `bucketTab matches the tab bucket returns`() {
        val items = describeAll(loadMetas("highfly_today.json", "sport"), highflyToday)
        val tabs = SportsLiveItemBucketer.bucket(items, now)

        SportsLiveTab.entries.forEach { tab ->
            assertEquals(tab.name, tabs.getValue(tab), SportsLiveItemBucketer.bucketTab(items, tab, now))
        }
    }

    private fun describeAll(metas: List<MetaPreview>, claimed: ClaimedCatalog) =
        metas.map { SportsLiveItemBucketer.describe(it, claimed, now) }

    private fun loadMetas(name: String, catalogType: String): List<MetaPreview> {
        val json = javaClass.classLoader!!.getResourceAsStream("sportslive/$name")!!
            .bufferedReader().use { it.readText() }
        val response = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
            .adapter(CatalogResponseDto::class.java)
            .fromJson(json)!!
        return response.metas.mapNotNull { it?.toDomainOrNull(catalogType) }
            .also { assertTrue("fixture $name is empty", it.isNotEmpty()) }
    }

    private fun claimed(
        type: String,
        id: String,
        name: String,
        kind: SportsLiveKind,
        viaOverride: Boolean = false
    ): ClaimedCatalog {
        val catalog = CatalogDescriptor(type = ContentType.fromString(type), rawType = type, id = id, name = name)
        val addon = Addon(
            id = "highfly",
            name = "Sports Streams",
            version = "1.0.0",
            description = null,
            logo = null,
            baseUrl = "https://highfly.example.com",
            catalogs = listOf(catalog),
            types = listOf(catalog.type),
            resources = emptyList()
        )
        return ClaimedCatalog(addon, catalog, "highfly_${type}_$id", kind, viaOverride)
    }

    private fun meta(
        id: String,
        name: String,
        releaseInfo: String? = null,
        description: String? = null,
        genres: List<String> = emptyList(),
        released: String? = null
    ) = MetaPreview(
        id = id,
        type = ContentType.UNKNOWN,
        rawType = "sport",
        name = name,
        poster = null,
        posterShape = PosterShape.LANDSCAPE,
        background = null,
        logo = null,
        description = description,
        releaseInfo = releaseInfo,
        imdbRating = null,
        genres = genres,
        released = released
    )
}
