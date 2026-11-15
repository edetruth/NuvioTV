package com.nuvio.tv.core.sportslive

import com.nuvio.tv.data.mapper.toDomainOrNull
import com.nuvio.tv.data.remote.dto.CatalogResponseDto
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.ClaimedCatalog
import com.nuvio.tv.domain.model.ContentType
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

    private val highflyLive = claimed("sport", "sports_live", "Live Now", SportsLiveKind.EVENTS)
    private val highflyToday = claimed("sport", "sports_today", "Today", SportsLiveKind.EVENTS)
    private val highflyFootball = claimed("sport", "sports_football", "Football", SportsLiveKind.EVENTS)

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
    fun `start more than 10 minutes past in an events catalog is live`() {
        val item = SportsLiveItemBucketer.describe(
            meta("started", "Started game", releaseInfo = "15 Nov 2026 · 14:00 UTC"),
            highflyFootball,
            now
        )
        val tabs = SportsLiveItemBucketer.bucket(listOf(item), now)

        assertTrue(item.isLive)
        assertEquals(listOf("started"), tabs.getValue(SportsLiveTab.LIVE_NOW).map { it.id })
        assertTrue(tabs.getValue(SportsLiveTab.UPCOMING).isEmpty())
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
    fun `describe copies addon and catalog details`() {
        val item = SportsLiveItemBucketer.describe(meta("x", "X"), highflyToday, now)

        assertEquals("highfly", item.addonId)
        assertEquals("Sports Streams", item.addonName)
        assertEquals("https://highfly.example.com", item.addonBaseUrl)
        assertEquals("highfly_sport_sports_today", item.catalogKey)
        assertEquals("Today", item.catalogName)
        assertEquals(SportsLiveKind.EVENTS, item.kind)
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

    private fun claimed(type: String, id: String, name: String, kind: SportsLiveKind): ClaimedCatalog {
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
        return ClaimedCatalog(addon, catalog, "highfly_${type}_$id", kind)
    }

    private fun meta(
        id: String,
        name: String,
        releaseInfo: String? = null,
        description: String? = null,
        genres: List<String> = emptyList()
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
        genres = genres
    )
}
