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
    private val exampleEvents = claimed("tv", "cnc_SportzXLiveEvents_tv", "⚡SportzX Live Events (tv)", SportsLiveKind.EVENTS)
    private val exampleLiveTv = claimed("tv", "cnc_EasyPlayLiveTV_tv", "EasyPlay Live TV (tv)", SportsLiveKind.CHANNELS)

    @Test
    fun `highfly live fixture's games land in LIVE_NOW and its broadcaster channels in CHANNELS`() {
        val metas = loadMetas("highfly_live.json", "sport")
        val items = metas.map { SportsLiveItemBucketer.describe(it, highflyLive, now) }
        val tabs = SportsLiveItemBucketer.bucket(items, now)
        val (channels, games) = items.partition { it.isChannel }

        assertTrue(items.all { it.isLive })
        // live.20 (TV report #4): a broadcaster's channel ("TSN 4", "ESPN HD") is a channel listing, never on Live now.
        assertEquals(6, channels.size)
        assertEquals(games.map { it.id }.toSet(), tabs.getValue(SportsLiveTab.LIVE_NOW).map { it.id }.toSet())
        assertEquals(channels.map { it.id }.toSet(), tabs.getValue(SportsLiveTab.CHANNELS).map { it.id }.toSet())
    }

    @Test
    fun `leaf items are channels and appear in CHANNELS only`() {
        val items = describeAll(loadMetas("highfly_today.json", "sport"), highflyToday)
        val leaf = items.single { it.id == "leaf:ca-tsn-44343" }
        val tabs = SportsLiveItemBucketer.bucket(items, now)

        assertTrue(leaf.isChannel)
        assertTrue(tabs.getValue(SportsLiveTab.CHANNELS).any { it.id == leaf.id })
        // A broadcaster's channel (live.20), even marked LIVE NOW by its events catalog.
        assertTrue(tabs.getValue(SportsLiveTab.LIVE_NOW).none { it.id == leaf.id })
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
    fun `an event that started 2 hours or 1 minute ago is live, 5 hours ago is in neither LIVE_NOW nor UPCOMING`() {
        // 2 hours ago, with a time of day.
        val started = SportsLiveItemBucketer.describe(
            meta("started", "Started game", releaseInfo = "15 Nov 2026 · 12:30 UTC"),
            highflyFootball,
            now
        )
        val startedTabs = SportsLiveItemBucketer.bucket(listOf(started), now)

        assertTrue(started.isLive)
        assertTrue(started.startHasTimeOfDay)
        assertEquals(listOf("started"), startedTabs.getValue(SportsLiveTab.LIVE_NOW).map { it.id })
        assertTrue(startedTabs.getValue(SportsLiveTab.UPCOMING).isEmpty())

        // 1 minute ago.
        val kickoff = SportsLiveItemBucketer.describe(
            meta("kickoff", "Kickoff", released = "2026-11-15T14:29:00Z"),
            highflyFootball,
            now
        )

        assertTrue(kickoff.isLive)
        assertEquals(listOf("kickoff"), SportsLiveItemBucketer.bucket(listOf(kickoff), now).getValue(SportsLiveTab.LIVE_NOW).map { it.id })

        // 5 hours ago. "Today" matches the UPCOMING catalog keywords, so this also checks that a known past start wins over the keyword.
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
    fun `#356 - a date-only row from an upcoming catalog leaves Upcoming once its date has passed`() {
        val upcoming = claimed("sport", "events_upcoming", "Upcoming", SportsLiveKind.EVENTS)
        val items = describeAll(
            listOf(
                meta("past", "Past date game", releaseInfo = "2026-11-13"),
                // Yesterday: its date ended everywhere at 12:00 UTC today (36 h), before now (14:30).
                meta("yesterday", "Yesterday game", releaseInfo = "2026-11-14"),
                meta("today", "Today game", releaseInfo = "2026-11-15"),
                meta("later", "Later game", releaseInfo = "2026-11-17")
            ),
            upcoming
        )
        val upcomingTab = SportsLiveItemBucketer.bucket(items, now).getValue(SportsLiveTab.UPCOMING)
        assertEquals(setOf("today", "later"), upcomingTab.map { it.id }.toSet())
        assertTrue(items.all { !it.startHasTimeOfDay })
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
    fun `Example Addon soon items are upcoming time TBA and live dot items are live, both with clean names`() {
        val raw = loadMetas("cnc_live_events.json", "tv")
        val items = describeAll(raw, exampleEvents)
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
        val item = SportsLiveItemBucketer.describe(meta("afg", "$soonEmoji  AFG vs ZIM"), exampleEvents, now)

        assertEquals("AFG vs ZIM", item.meta.name)
        assertEquals(listOf("AFG", "ZIM"), item.meta.name.split(" vs "))
        assertFalse(item.isLive)
    }

    @Test
    fun `live dot is live even with a future start and soon is not live even with a LIVE marker`() {
        val live = SportsLiveItemBucketer.describe(
            meta("l", "$liveEmoji A vs B", releaseInfo = "15 Nov 2026 · 18:00 UTC"), exampleEvents, now
        )
        val soon = SportsLiveItemBucketer.describe(meta("s", "$soonEmoji C vs D", releaseInfo = "LIVE"), exampleEvents, now)

        assertTrue(live.isLive)
        assertEquals("A vs B", live.meta.name)
        assertFalse(soon.isLive)
    }

    @Test
    fun `Example Addon Live TV channels are channels, never live, grouped by prefix`() {
        val items = describeAll(loadMetas("cnc_live_tv_sample.json", "tv"), exampleLiveTv)
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
        val liveChannel = meta("ch", "Stadium 1", releaseInfo = "LIVE", genres = listOf("Live TV"))
        val fromEvents = SportsLiveItemBucketer.describe(liveChannel, highflyLive, now)
        val fromChannels = SportsLiveItemBucketer.describe(liveChannel, exampleLiveTv, now)

        assertTrue(fromEvents.isChannel && fromChannels.isChannel)
        assertEquals(listOf("ch"), SportsLiveItemBucketer.bucket(listOf(fromEvents), now).getValue(SportsLiveTab.LIVE_NOW).map { it.id })
        assertTrue(SportsLiveItemBucketer.bucket(listOf(fromChannels), now).getValue(SportsLiveTab.LIVE_NOW).isEmpty())
        // A broadcaster's channel (live.20, "Sky Sports") is on Channels only, even live from an events catalog.
        val broadcaster = SportsLiveItemBucketer.describe(meta("sky", "Sky Sports", releaseInfo = "LIVE", genres = listOf("Live TV")), highflyLive, now)
        val tabs = SportsLiveItemBucketer.bucket(listOf(broadcaster), now)
        assertTrue(tabs.getValue(SportsLiveTab.LIVE_NOW).isEmpty())
        assertEquals(listOf("sky"), tabs.getValue(SportsLiveTab.CHANNELS).map { it.id })
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
    fun `Example Addon finished items are in no tab and lose the emoji`() {
        val doneEmoji = "✅"
        val raw = loadMetas("cnc_live_events.json", "tv")
        val doneIds = raw.filter { it.name.startsWith(doneEmoji) }.map { it.id }.toSet()
        val items = describeAll(raw, exampleEvents)
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
            exampleLiveTv
        )
        val groups = SportsLiveItemBucketer.bucket(items, now).getValue(SportsLiveTab.CHANNELS).mapNotNull { it.group }

        assertEquals(listOf("BIH"), groups.distinct())
    }

    @Test
    fun `describe parses teams for events and for channels only inside brackets`() {
        val event = SportsLiveItemBucketer.describe(meta("e", "Celtics vs 76ers"), highflyToday, now)
        val channel = SportsLiveItemBucketer.describe(meta("c", "Sky Sports - HD"), exampleLiveTv, now)
        val ppv = SportsLiveItemBucketer.describe(
            meta("p", "US| PEACOCK PPV 004 [Celtics vs. 76ers (2026-11-10 19:00:05)]"), exampleLiveTv, now
        )
        val other = SportsLiveItemBucketer.describe(meta("o", "NFL RedZone"), highflyToday, now)

        assertEquals(TeamPair("Celtics", "76ers"), event.teams)
        assertNull(channel.teams)
        assertEquals(TeamPair("Celtics", "76ers"), ppv.teams)
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
    fun `a signal-less catalog is live, upcoming Time TBA or replays by its name, and unchanged when the name is unknown`() {
        // Named live: all live.
        val metas = listOf(meta("a", "Team A vs Team B"), meta("b", "Team C vs Team D"))
        for (name in listOf("Live Events", "Fútbol en vivo", "Direct")) {
            val items = SportsLiveItemBucketer.describeCatalog(metas, claimed("sport", "x", name, SportsLiveKind.EVENTS), now)

            assertTrue(name, items.all { it.isLive })
            assertEquals(name, 2, SportsLiveItemBucketer.bucket(items, now).getValue(SportsLiveTab.LIVE_NOW).size)
        }

        // "Agenda de hoy": upcoming, Time TBA.
        val agenda = claimed("sport", "agenda", "Agenda de hoy", SportsLiveKind.EVENTS)
        val agendaItems = SportsLiveItemBucketer.describeCatalog(listOf(meta("a", "A vs B"), meta("b", "C vs D")), agenda, now)
        val agendaTabs = SportsLiveItemBucketer.bucket(agendaItems, now)

        assertTrue(agendaItems.none { it.isLive || it.startMs != null })
        assertEquals(listOf("a", "b"), agendaTabs.getValue(SportsLiveTab.UPCOMING).map { it.id })
        assertTrue(agendaTabs.getValue(SportsLiveTab.LIVE_NOW).isEmpty())

        // Named replays.
        for (name in listOf("Full Match Replays", "Full Match", "Melhores Momentos")) {
            val catalog = claimed("sport", "fm", name, SportsLiveKind.EVENTS)
            val items = SportsLiveItemBucketer.describeCatalog(listOf(meta("a", "A vs B")), catalog, now)
            val tabs = SportsLiveItemBucketer.bucket(items, now)

            assertEquals(name, listOf("a"), tabs.getValue(SportsLiveTab.REPLAYS).map { it.id })
            assertTrue(name, tabs.getValue(SportsLiveTab.UPCOMING).isEmpty())
        }

        // An unknown name: unchanged.
        val misc = claimed("sport", "misc", "Misc", SportsLiveKind.EVENTS)
        val miscMetas = listOf(meta("a", "A vs B"))
        assertEquals(
            miscMetas.map { SportsLiveItemBucketer.describe(it, misc, now) },
            SportsLiveItemBucketer.describeCatalog(miscMetas, misc, now)
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

    @Test
    fun `the same live game from two addons is one card, primary from the first addon`() {
        val example = describeAll(
            loadMetas("cnc_live_events.json", "tv"),
            claimed("tv", "example_events", "⚡SportzX Live Events (tv)", SportsLiveKind.EVENTS, addonId = "example")
        )
        val nebula = describeAll(
            loadMetas("nebula_live.json", "sports"),
            claimed("sports", "streamed-events-live-now", "Nebula Sports: Live Now", SportsLiveKind.EVENTS, addonId = "nebula")
        )
        val items = example + nebula
        val separate = SportsLiveItemBucketer.bucketTab(example, SportsLiveTab.LIVE_NOW, now).size +
            SportsLiveItemBucketer.bucketTab(nebula, SportsLiveTab.LIVE_NOW, now).size

        val live = SportsLiveItemBucketer.bucketTab(items, SportsLiveTab.LIVE_NOW, now, addonOrder = listOf("nebula", "example"))
        val game = live.single { "Raiders" in it.meta.name }

        assertEquals("nebula", game.addonId)
        assertEquals("Las Vegas Raiders vs Seattle Seahawks", game.meta.name)
        assertEquals(listOf("example"), game.alternates.map { it.addonId })
        assertEquals(2, game.sourceCount)
        // Both fixtures carry the same SportzX feed: these five games merge, and so does "ATP 250 vs Stockholm Open"
        // with Nebula's "ATP 250", whose description names Stockholm Open (F4); "NFL vs REDZONE" does not match
        // Nebula's "NFL REDZONE".
        assertEquals(
            setOf(
                "Las Vegas Raiders vs Seattle Seahawks",
                "Arizona Cardinals vs Los Angeles Rams",
                "Dallas Cowboys vs San Francisco 49ers",
                "Phoenix Suns vs Los Angeles Lakers",
                "Los Angeles Clippers vs Charlotte Hornets",
                "ATP 250"
            ),
            live.filter { it.alternates.isNotEmpty() }.map { it.meta.name }.toSet()
        )
        assertEquals(separate - 6, live.size)

        // By default the first addon seen leads: the example addon's copy.
        val byDefault = SportsLiveItemBucketer.bucket(items, now).getValue(SportsLiveTab.LIVE_NOW)
            .single { "Raiders" in it.meta.name }
        assertEquals("example", byDefault.addonId)
        assertEquals(listOf("nebula"), byDefault.alternates.map { it.addonId })
    }

    @Test
    fun `upcoming copies within 30 minutes merge either way round`() {
        val a = event("a", "a1", "Detroit Lions vs New England Patriots", "15 Nov 2026 · 18:00 UTC", listOf("American Football"))
        val b = event("b", "b1", "Patriots @ Lions", "15 Nov 2026 · 18:25 UTC")

        val upcoming = SportsLiveItemBucketer.bucketTab(listOf(b, a), SportsLiveTab.UPCOMING, now, addonOrder = listOf("a", "b"))

        assertEquals(listOf("a1"), upcoming.map { it.id })
        assertEquals(listOf("b1"), upcoming.single().alternates.map { it.id })
    }

    @Test
    fun `the same teams on consecutive days or over 30 minutes apart do not merge`() {
        val a = event("a", "a1", "Lions vs Patriots", "15 Nov 2026 · 18:00 UTC")
        val b = event("b", "b1", "Lions vs Patriots", "16 Nov 2026 · 18:00 UTC")
        val c = event("c", "c1", "Lions vs Patriots", "15 Nov 2026 · 18:31 UTC")

        val upcoming = SportsLiveItemBucketer.bucketTab(listOf(a, b, c), SportsLiveTab.UPCOMING, now)

        assertEquals(3, upcoming.size)
        assertTrue(upcoming.all { it.alternates.isEmpty() })
    }

    @Test
    fun `different sports do not merge but an unknown sport does`() {
        val football = event("a", "a1", "Arsenal vs Chelsea", "LIVE", listOf("Football"))
        val basketball = event("b", "b1", "Arsenal vs Chelsea", "LIVE", listOf("Basketball"))
        val unknown = event("c", "c1", "Arsenal FC vs Chelsea FC", "LIVE")

        val live = SportsLiveItemBucketer.bucketTab(listOf(football, basketball, unknown), SportsLiveTab.LIVE_NOW, now)

        assertEquals(setOf("a1", "b1"), live.map { it.id }.toSet())
        assertEquals(listOf("c1"), live.single { it.id == "a1" }.alternates.map { it.id })
        assertTrue(live.single { it.id == "b1" }.alternates.isEmpty())
    }

    @Test
    fun `channels and replays never merge even as copies of one game`() {
        val channelA = event("a", "ch1", "US| NFL [Raiders vs Seahawks]", "LIVE", listOf("Live TV"))
        val channelB = event("b", "ch2", "UK| NFL [Raiders vs Seahawks]", "LIVE", listOf("Live TV"))
        val replayA = describe("a", "r1", "Raiders vs Seahawks", "15 Nov 2026 · 10:00 UTC", kind = SportsLiveKind.REPLAY)
        val replayB = describe("b", "r2", "Raiders vs Seahawks", "15 Nov 2026 · 10:00 UTC", kind = SportsLiveKind.REPLAY)
        assertTrue(channelA.isChannel && channelA.teams != null && channelA.isLive)

        val tabs = SportsLiveItemBucketer.bucket(listOf(channelA, channelB, replayA, replayB), now)

        assertEquals(setOf("ch1", "ch2"), tabs.getValue(SportsLiveTab.LIVE_NOW).map { it.id }.toSet())
        assertEquals(setOf("r1", "r2"), tabs.getValue(SportsLiveTab.REPLAYS).map { it.id }.toSet())
        assertTrue(tabs.values.flatten().all { it.alternates.isEmpty() })
    }

    @Test
    fun `one addon's provider sub-catalogs listing the same game pool into one card`() {
        // One live-TV addon exposes the same match from four provider catalogs, each with its own id/stream.
        val providers = listOf(
            "cnc_SportzXLiveEvents_tv" to "⚡SportzX Live Events (tv)",
            "cnc_LivXowLiveEvents_tv" to "⚡LivXow Live Events (tv)",
            "cnc_SKTechLiveEvents_tv" to "⚡SKTech Live Events (tv)",
            "cnc_PlayFyLiveEvents_tv" to "⚡ PlayFy Live Events (tv)"
        )
        val items = providers.mapIndexed { i, (id, name) ->
            SportsLiveItemBucketer.describe(
                meta("game-$i", "PAK vs SL", releaseInfo = "LIVE"),
                claimed("tv", id, name, SportsLiveKind.EVENTS, addonId = "example"),
                now
            )
        }

        val card = SportsLiveItemBucketer.bucketTab(items, SportsLiveTab.LIVE_NOW, now).single()
        val sourceKeys = (listOf(card) + card.alternates).map { it.streamKey }

        assertEquals(4, card.sourceCount)
        // Each pooled source keeps its own stream key (resolveAll fetches each separately).
        assertEquals(listOf("example|game-0", "example|game-1", "example|game-2", "example|game-3"), sourceKeys.sorted())
        assertEquals(4, sourceKeys.toSet().size)
        // Labeled by provider, not by the one shared addon name.
        assertEquals(
            setOf("SportzX", "LivXow", "SKTech", "PlayFy"),
            SportsLiveSourceLabels.of(card).values.toSet()
        )
    }

    @Test
    fun `a cross-addon abbreviation and full name pool into one card`() {
        val abbrev = event("example", "g1", "PAK vs SL", "LIVE")
        val full = event("neb", "g2", "Pakistan vs Sri Lanka", "LIVE")

        val card = SportsLiveItemBucketer.bucketTab(
            listOf(abbrev, full), SportsLiveTab.LIVE_NOW, now, addonOrder = listOf("example", "neb")
        ).single()

        assertEquals("example", card.addonId)
        assertEquals(2, card.sourceCount)
        assertEquals(listOf("g2"), card.alternates.map { it.id })
    }

    @Test
    fun `the alias layer keeps different teams and women's games separate`() {
        // Different matchup: shares only one side.
        val differentTeams = SportsLiveItemBucketer.bucketTab(
            listOf(event("a", "1", "PAK vs SL", "LIVE"), event("b", "2", "PAK vs IND", "LIVE")),
            SportsLiveTab.LIVE_NOW, now
        )
        assertEquals(2, differentTeams.size)
        assertTrue(differentTeams.all { it.alternates.isEmpty() })

        // Women's copy does not fold into the men's game, but two women's copies do.
        val womenVsMen = SportsLiveItemBucketer.bucketTab(
            listOf(event("a", "1", "PAK (W) vs SL (W)", "LIVE"), event("b", "2", "Pakistan vs Sri Lanka", "LIVE")),
            SportsLiveTab.LIVE_NOW, now
        )
        assertEquals(2, womenVsMen.size)

        val women = SportsLiveItemBucketer.bucketTab(
            listOf(event("a", "1", "PAK (W) vs SL (W)", "LIVE"), event("b", "2", "Pakistan (W) vs Sri Lanka (W)", "LIVE")),
            SportsLiveTab.LIVE_NOW, now, addonOrder = listOf("a", "b")
        )
        assertEquals(1, women.size)
        assertEquals(2, women.single().sourceCount)
    }

    @Test
    fun `a merged card has ended only when every source has`() {
        val a = event("a", "a1", "Lions vs Patriots", "LIVE")
        val b = event("b", "b1", "Lions vs Patriots", "LIVE")
        val merged = SportsLiveItemBucketer.bucketTab(listOf(a, b), SportsLiveTab.LIVE_NOW, now).single()

        assertFalse(merged.hasEnded(setOf(a.streamKey)))
        assertFalse(merged.hasEnded(setOf(b.streamKey)))
        assertTrue(merged.hasEnded(setOf(a.streamKey, b.streamKey)))
        assertTrue(a.hasEnded(setOf(a.streamKey)))
    }

    @Test
    fun `a merged card is followed when any source is`() {
        // The primary's sport differs from the followed team's; the alternate's is unknown, so only it matches.
        val a = event("a", "a1", "Lions vs Patriots", "LIVE", listOf("NFL"))
        val b = event("b", "b1", "Detroit Lions vs New England Patriots", "LIVE")
        val merged = SportsLiveItemBucketer.merge(listOf(a, b), listOf("a", "b")).single()
        val followed = listOf(FollowedTeam("Patriots", "American Football"))

        assertEquals("a1", merged.id)
        assertFalse(SportsLiveTeams.isFollowed(merged, followed))
        assertTrue(SportsLiveItemBucketer.markFollowed(listOf(merged), followed).single().followed)
    }

    @Test
    fun `a merged card shows the first known sport of its sources`() {
        // The primary's sport is only its catalog's name ("Events"); the alternate's genre names it.
        val a = event("a", "a1", "Lions vs Patriots", "LIVE")
        val b = event("b", "b1", "Detroit Lions vs New England Patriots", "LIVE", listOf("American Football"))
        val c = event("c", "c1", "Lions vs Patriots", "LIVE", listOf("Sports"))
        val nfl = event("a", "a1", "Lions vs Patriots", "LIVE", listOf("NFL"))

        assertEquals("American Football", SportsLiveItemBucketer.merge(listOf(a, b), listOf("a", "b")).single().sport)
        assertEquals("NFL", SportsLiveItemBucketer.merge(listOf(nfl, c), listOf("a", "c")).single().sport)
        // Nothing known: the primary's own sport stays.
        assertEquals("Events", SportsLiveItemBucketer.merge(listOf(a, c), listOf("a", "c")).single().sport)
    }

    @Test
    fun `a chain of near starts merges only with the primary`() {
        // a~b and b~c are within 30 minutes, a and c are not: c stays its own card.
        val a = event("a", "a1", "Lions vs Patriots", "15 Nov 2026 · 18:00 UTC")
        val b = event("b", "b1", "Lions vs Patriots", "15 Nov 2026 · 18:25 UTC")
        val c = event("c", "c1", "Lions vs Patriots", "15 Nov 2026 · 18:50 UTC")

        val upcoming = SportsLiveItemBucketer.bucketTab(listOf(a, b, c), SportsLiveTab.UPCOMING, now, addonOrder = listOf("a", "b", "c"))

        assertEquals(listOf("a1", "c1"), upcoming.map { it.id })
        assertEquals(listOf("b1"), upcoming.first().alternates.map { it.id })
        assertTrue(upcoming.last().alternates.isEmpty())
    }

    @Test
    fun `an addon missing from the addon order ranks last`() {
        val unknown = event("x", "x1", "Lions vs Patriots", "LIVE")
        val known = event("b", "b1", "Lions vs Patriots", "LIVE")

        val merged = SportsLiveItemBucketer.merge(listOf(unknown, known), listOf("b")).single()

        assertEquals("b1", merged.id)
        assertEquals(listOf("x1"), merged.alternates.map { it.id })
    }

    @Test
    fun `a side named in full merges with its short name in the same sport (153)`() {
        val short = event("a", "a1", "Illawarra Hawks vs Adelaide", "LIVE", listOf("Basketball"))
        val full = event("b", "b1", "Illawarra Hawks vs Adelaide 36ers", "LIVE", listOf("Basketball"))

        val live = SportsLiveItemBucketer.bucketTab(listOf(full, short), SportsLiveTab.LIVE_NOW, now, addonOrder = listOf("a", "b"))

        assertEquals(listOf("a1"), live.map { it.id })
        assertEquals(listOf("b1"), live.single().alternates.map { it.id })
    }

    @Test
    fun `a women marker and a short name merge when both sports are known (153)`() {
        val short = event("a", "a1", "Brisbane W vs Hobart W", "LIVE", listOf("Cricket"))
        val full = event("b", "b1", "Brisbane Heat Women vs Hobart Hurricanes Women", "LIVE", listOf("Cricket"))
        val unknownSport = event("c", "c1", "Brisbane vs Hobart", "LIVE")

        val live = SportsLiveItemBucketer.bucketTab(listOf(short, full, unknownSport), SportsLiveTab.LIVE_NOW, now, addonOrder = listOf("a", "b", "c"))

        assertEquals(setOf("a1", "c1"), live.map { it.id }.toSet())
        assertEquals(listOf("b1"), live.single { it.id == "a1" }.alternates.map { it.id })
    }

    @Test
    fun `short names never merge across sports, other teams or other times (153)`() {
        val basketball = event("a", "a1", "Illawarra Hawks vs Adelaide", "LIVE", listOf("Basketball"))
        val soccer = event("b", "b1", "Illawarra Hawks vs Adelaide United", "LIVE", listOf("Soccer"))
        val united = event("c", "c1", "Manchester United vs Liverpool", "LIVE", listOf("Soccer"))
        val city = event("d", "d1", "Manchester City vs Liverpool", "LIVE", listOf("Soccer"))
        val today = event("e", "e1", "Perth vs Sydney Kings", "15 Nov 2026 · 18:00 UTC", listOf("Basketball"))
        val tomorrow = event("f", "f1", "Perth Wildcats vs Sydney Kings", "16 Nov 2026 · 18:00 UTC", listOf("Basketball"))

        val tabs = SportsLiveItemBucketer.bucket(listOf(basketball, soccer, united, city, today, tomorrow), now)

        assertEquals(setOf("a1", "b1", "c1", "d1"), tabs.getValue(SportsLiveTab.LIVE_NOW).map { it.id }.toSet())
        assertEquals(setOf("e1", "f1"), tabs.getValue(SportsLiveTab.UPCOMING).map { it.id }.toSet())
        assertTrue(tabs.values.flatten().all { it.alternates.isEmpty() })
    }

    @Test
    fun `two live copies merge whatever their starts`() {
        // Both are live, though their listed starts are an hour apart.
        val a = event("a", "a1", "Lions vs Patriots", "15 Nov 2026 · 12:00 UTC")
        val b = event("b", "b1", "Lions vs Patriots", "15 Nov 2026 · 13:00 UTC")
        assertTrue(a.isLive && b.isLive)

        val live = SportsLiveItemBucketer.bucketTab(listOf(a, b), SportsLiveTab.LIVE_NOW, now, addonOrder = listOf("a", "b"))

        assertEquals(listOf("b1"), live.single().alternates.map { it.id })
    }

    @Test
    fun `copies on the replays tab are not merged`() {
        // Past events of catalogs included by override are shown as replays.
        val a = SportsLiveItemBucketer.describe(
            meta("a1", "Lions vs Patriots", releaseInfo = "14 Nov 2026 · 18:00 UTC"),
            claimed("sport", "events", "Events", SportsLiveKind.EVENTS, viaOverride = true, addonId = "a"),
            now
        )
        val b = SportsLiveItemBucketer.describe(
            meta("b1", "Lions vs Patriots", releaseInfo = "14 Nov 2026 · 18:05 UTC"),
            claimed("sport", "events", "Events", SportsLiveKind.EVENTS, viaOverride = true, addonId = "b"),
            now
        )

        val replays = SportsLiveItemBucketer.bucketTab(listOf(a, b), SportsLiveTab.REPLAYS, now)

        assertEquals(setOf("a1", "b1"), replays.map { it.id }.toSet())
        assertTrue(replays.all { it.alternates.isEmpty() })
    }

    private fun event(addonId: String, id: String, name: String, releaseInfo: String?, genres: List<String> = emptyList()) =
        describe(addonId, id, name, releaseInfo, genres)

    private fun describe(
        addonId: String,
        id: String,
        name: String,
        releaseInfo: String?,
        genres: List<String> = emptyList(),
        kind: SportsLiveKind = SportsLiveKind.EVENTS
    ) = SportsLiveItemBucketer.describe(
        meta(id, name, releaseInfo = releaseInfo, genres = genres),
        claimed("sport", "events", "Events", kind, addonId = addonId),
        now
    )

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
        viaOverride: Boolean = false,
        addonId: String = "highfly"
    ): ClaimedCatalog {
        val catalog = CatalogDescriptor(type = ContentType.fromString(type), rawType = type, id = id, name = name)
        val addon = Addon(
            id = addonId,
            name = "Sports Streams",
            version = "1.0.0",
            description = null,
            logo = null,
            baseUrl = "https://highfly.example.com",
            catalogs = listOf(catalog),
            types = listOf(catalog.type),
            resources = emptyList()
        )
        return ClaimedCatalog(addon, catalog, "${addonId}_${type}_$id", kind, viaOverride)
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
