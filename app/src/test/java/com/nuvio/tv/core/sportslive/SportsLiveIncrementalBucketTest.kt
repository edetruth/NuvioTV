package com.nuvio.tv.core.sportslive

import com.nuvio.tv.data.mapper.toDomainOrNull
import com.nuvio.tv.data.remote.dto.CatalogResponseDto
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.ClaimedCatalog
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * live.24: a refresh of one catalog merges again only the groups of copies it touched, and the rows are exactly a full
 * bucketing's (property style: random catalogs of copies across addons, refreshed one at a time, and real fixtures).
 */
class SportsLiveIncrementalBucketTest {
    private val now = Instant.parse("2026-11-21T18:00:00Z").toEpochMilli()
    private val order = listOf("a", "b", "c")
    private val catalogs = listOf(
        claimed("a", "events", "Events"),
        claimed("a", "today", "Today"),
        claimed("b", "games", "Games"),
        claimed("c", "schedule", "Schedule")
    )
    private val tabs = listOf(SportsLiveTab.LIVE_NOW, SportsLiveTab.UPCOMING, SportsLiveTab.REPLAYS)

    @Test
    fun `incremental re-bucketing equals a full one after random catalog refreshes`() {
        for (seed in 1..40) {
            val random = Random(seed)
            val games = List(30) { game(random) }
            var listings = catalogs.associateWith { List(random.nextInt(4, 20)) { pick(random, games) } }
            var previous: Map<SportsLiveTab, SportsLiveTabBucket> = emptyMap()
            repeat(6) { step ->
                val items = SportsLiveItemBucketer.reclock(describeAll(listings), now)
                previous = tabs.associateWith { tab ->
                    val incremental = SportsLiveItemBucketer.bucketClockedIncremental(previous[tab], items, tab, now, order, stamp = 0L)
                    assertEquals("seed $seed step $step ${tab.name}", SportsLiveItemBucketer.bucketClocked(items, tab, now, order), incremental.rows)
                    incremental
                }
                // One catalog refreshes: some games leave, some come, some change their title or start.
                val refreshed = catalogs[random.nextInt(catalogs.size)]
                listings = listings + (refreshed to refresh(random, listings.getValue(refreshed), games))
            }
        }
    }

    @Test
    fun `an unchanged load keeps its rows and merges nothing again`() {
        val random = Random(7)
        val games = List(30) { game(random) }
        val listings = catalogs.associateWith { List(12) { pick(random, games) } }
        val first = SportsLiveItemBucketer.bucketClockedIncremental(null, describeAll(listings), SportsLiveTab.UPCOMING, now, order, stamp = 0L)
        // A new load of the same games: new item objects, equal values.
        val second = SportsLiveItemBucketer.bucketClockedIncremental(first, describeAll(listings), SportsLiveTab.UPCOMING, now, order, stamp = 0L)
        assertSame(first, second)
        // A changed team directory merges everything again.
        val third = SportsLiveItemBucketer.bucketClockedIncremental(first, describeAll(listings), SportsLiveTab.UPCOMING, now, order, stamp = 1L)
        assertTrue(third.merge!!.full)
        assertEquals(first.rows, third.rows)
    }

    @Test
    fun `one refreshed catalog merges again only the groups it touches`() {
        val a = claimed("a", "events", "Events")
        val b = claimed("b", "games", "Games")
        fun timed(id: String, name: String, start: String) = meta(id, name, released = start)
        val before = listOf(
            a to timed("1", "Arsenal vs Chelsea", "2026-11-22T15:00:00Z"),
            a to timed("2", "Duke vs Clemson", "2026-11-22T20:00:00Z"),
            b to timed("1", "Chelsea v Arsenal", "2026-11-22T15:10:00Z"),
            b to timed("2", "Clemson vs Duke", "2026-11-22T20:00:00Z")
        )
        val first = SportsLiveItemBucketer.bucketClockedIncremental(null, describePairs(before), SportsLiveTab.UPCOMING, now, order, stamp = 0L)
        assertEquals(2, first.rows.size)
        // b's catalog refreshes: its Duke game moves by an hour (no longer a copy); Arsenal's group is kept as it was.
        val after = before.dropLast(1) + (b to timed("2", "Clemson vs Duke", "2026-11-22T21:00:00Z"))
        val second = SportsLiveItemBucketer.bucketClockedIncremental(first, describePairs(after), SportsLiveTab.UPCOMING, now, order, stamp = 0L)
        assertEquals(SportsLiveItemBucketer.bucketClocked(describePairs(after), SportsLiveTab.UPCOMING, now, order), second.rows)
        assertEquals(3, second.rows.size)
        assertEquals(2, second.merge!!.remergedCount)
        assertEquals(1, second.merge!!.changedCount)
        // The kept group's row is the same object (later stages keep their work for it).
        assertSame(first.rows.first { it.id == "1" }, second.rows.first { it.id == "1" })
    }

    @Test
    fun `fixtures listed by two addons, one refreshed, bucket as a full bucketing does`() {
        val metas = listOf("highfly_today.json", "nebula_live.json", "cnc_live_events.json").flatMap { loadMetas(it) }
        val a = claimed("a", "events", "Events")
        val b = claimed("b", "games", "Games")
        val all = metas.map { a to it } + metas.map { b to it }
        val dropped = metas.map { a to it } + metas.filterIndexed { index, _ -> index % 3 != 0 }.map { b to it }
        var previous: Map<SportsLiveTab, SportsLiveTabBucket> = emptyMap()
        for ((step, listed) in listOf(all, dropped, all).withIndex()) {
            val items = SportsLiveItemBucketer.reclock(describePairs(listed), now)
            previous = tabs.associateWith { tab ->
                val incremental = SportsLiveItemBucketer.bucketClockedIncremental(previous[tab], items, tab, now, order, stamp = 0L)
                assertEquals("step $step ${tab.name}", SportsLiveItemBucketer.bucketClocked(items, tab, now, order), incremental.rows)
                incremental
            }
        }
    }

    private class Game(val home: String, val away: String, val sport: String?, val startMs: Long)

    private fun game(random: Random): Game {
        val home = TEAMS[random.nextInt(TEAMS.size)]
        var away = TEAMS[random.nextInt(TEAMS.size)]
        while (away == home) away = TEAMS[random.nextInt(TEAMS.size)]
        // From three hours ago (live) to four days ahead, on the quarter hour.
        val start = now + (random.nextInt(-12, 4 * 24 * 4)) * 15 * MINUTE
        return Game(home, away, SPORTS[random.nextInt(SPORTS.size)], start)
    }

    // One addon's listing of a game: either way round, "vs" or "v", its start a little off, sometimes a date only or LIVE.
    private fun pick(random: Random, games: List<Game>): MetaPreview {
        val index = random.nextInt(games.size)
        val game = games[index]
        val name = if (random.nextBoolean()) "${game.home} vs ${game.away}" else "${game.away} v ${game.home}"
        val start = game.startMs + JITTER[random.nextInt(JITTER.size)]
        val live = start <= now && start > now - 3 * 60 * MINUTE
        val sport = game.sport.takeIf { random.nextInt(4) != 0 }
        return when {
            live && random.nextInt(3) == 0 -> meta("g$index", name, releaseInfo = "LIVE", genres = listOfNotNull(sport))
            random.nextInt(8) == 0 -> meta("g$index", name, releaseInfo = DATE.format(Instant.ofEpochMilli(start)), genres = listOfNotNull(sport))
            else -> meta("g$index", name, released = Instant.ofEpochMilli(start).toString(), genres = listOfNotNull(sport))
        }
    }

    private fun refresh(random: Random, metas: List<MetaPreview>, games: List<Game>): List<MetaPreview> {
        val kept = metas.filter { random.nextInt(5) != 0 }.map { meta ->
            if (random.nextInt(5) == 0) pick(random, games).copy(id = meta.id) else meta
        }
        return kept + List(random.nextInt(0, 5)) { pick(random, games) }
    }

    private fun describeAll(listings: Map<ClaimedCatalog, List<MetaPreview>>): List<SportsLiveItem> =
        listings.flatMap { (catalog, metas) -> metas.map { SportsLiveItemBucketer.describe(it, catalog, now) } }

    private fun describePairs(listed: List<Pair<ClaimedCatalog, MetaPreview>>): List<SportsLiveItem> =
        listed.map { (catalog, meta) -> SportsLiveItemBucketer.describe(meta, catalog, now) }

    private fun loadMetas(name: String): List<MetaPreview> {
        val json = javaClass.classLoader!!.getResourceAsStream("sportslive/$name")!!.bufferedReader().use { it.readText() }
        val response = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(CatalogResponseDto::class.java).fromJson(json)!!
        return response.metas.mapNotNull { it?.toDomainOrNull("tv") }
    }

    private fun claimed(addonId: String, id: String, name: String): ClaimedCatalog {
        val catalog = CatalogDescriptor(type = ContentType.fromString("tv"), rawType = "tv", id = id, name = name)
        val addon = Addon(
            id = addonId,
            name = "Source $addonId",
            version = "1.0.0",
            description = null,
            logo = null,
            baseUrl = "https://$addonId.example.invalid",
            catalogs = listOf(catalog),
            types = listOf(catalog.type),
            resources = emptyList()
        )
        return ClaimedCatalog(addon, catalog, "${addonId}_tv_$id", SportsLiveKind.EVENTS)
    }

    private fun meta(
        id: String,
        name: String,
        releaseInfo: String? = null,
        released: String? = null,
        genres: List<String> = emptyList()
    ) = MetaPreview(
        id = id,
        type = ContentType.UNKNOWN,
        rawType = "tv",
        name = name,
        poster = null,
        posterShape = PosterShape.LANDSCAPE,
        background = null,
        logo = null,
        description = null,
        releaseInfo = releaseInfo,
        imdbRating = null,
        genres = genres,
        released = released
    )

    private companion object {
        const val MINUTE = 60_000L
        val JITTER = listOf(0L, 0L, 0L, 10 * MINUTE, -10 * MINUTE, 40 * MINUTE)
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)
        val SPORTS = listOf("Soccer", "Football", "Basketball", "Hockey", null)
        val TEAMS = listOf(
            "Arsenal", "Chelsea", "Liverpool", "Everton", "Los Angeles Lakers", "Lakers", "Boston Celtics", "Real Madrid",
            "Barcelona", "Duke", "Clemson", "Toronto Maple Leafs", "Boston Bruins", "Los Angeles Kings", "Miami Heat"
        )
    }
}
