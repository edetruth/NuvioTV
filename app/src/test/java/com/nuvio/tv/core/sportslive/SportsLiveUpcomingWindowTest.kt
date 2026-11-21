package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.scores.MySport
import com.nuvio.tv.core.sportslive.scores.MySports
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.ClaimedCatalog
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.domain.repository.SportsLiveLoad
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** live.24: Upcoming sorts today, tomorrow and the day after at load; a later day only when asked, once. */
class SportsLiveUpcomingWindowTest {
    private val zone = ZoneOffset.UTC
    private val now = Instant.parse("2026-11-21T10:00:00Z").toEpochMilli()
    private val today = LocalDate.of(2026, 11, 21)
    private val a = claimed("a")
    private val b = claimed("b")
    private val keys = setOf(a.key, b.key)
    private val order = listOf("a", "b")

    // Two addons list the same games (copies to merge), at noon of each day; day 4 has two, day 6 one.
    private fun games(): List<SportsLiveItem> {
        val days = listOf(0, 0, 1, 2, 3, 4, 4, 6)
        return days.flatMapIndexed { index, day ->
            val start = today.plusDays(day.toLong()).atTime(12, 0).toInstant(zone).toString()
            val name = "${TEAMS[index * 2]} vs ${TEAMS[index * 2 + 1]}"
            listOf(item(a, "g$index", name, start), item(b, "g$index", name, start))
        }
    }

    private fun load(items: List<SportsLiveItem>) = SportsLiveLoad(items = items, failures = emptyList(), loadedAtMs = now, claimedKeys = keys)

    @Test
    fun `day 4 and later are not sorted until asked, and asking sorts that day once`() {
        val shared = SportsLiveSharedBuckets()
        val load = load(games())
        val near = shared.upcoming(load, keys, order, now, SportsLiveUpcomingWindow(zone), stamp = 0L)
        // Today (2 games), tomorrow, the day after: 4 merged rows from 8 listings; nothing later was merged.
        assertEquals(4, near.rows.size)
        assertTrue(near.rows.all { it.alternates.size == 1 })
        assertEquals(8, shared.upcomingSorted)
        assertEquals(listOf(today.plusDays(3), today.plusDays(4), today.plusDays(6)), near.pendingDates)

        // The viewer goes to day 4 (index 3): only its games are merged.
        val fourDays = shared.upcoming(load, keys, order, now, SportsLiveUpcomingWindow(zone, days = 4), stamp = 0L)
        assertEquals(5, fourDays.rows.size)
        assertEquals(10, shared.upcomingSorted)
        assertEquals(listOf(today.plusDays(4), today.plusDays(6)), fourDays.pendingDates)

        // Asked again: nothing is merged again, the same rows come back.
        assertSame(fourDays, shared.upcoming(load, keys, order, now, SportsLiveUpcomingWindow(zone, days = 4), stamp = 0L))
        assertEquals(10, shared.upcomingSorted)

        // The next load (same games, new objects) merges nothing again for the days already sorted.
        val again = shared.upcoming(load(games()), keys, order, now, SportsLiveUpcomingWindow(zone, days = 4), stamp = 0L)
        assertEquals(fourDays.rows, again.rows)
    }

    @Test
    fun `a followed team's game is sorted whatever its day`() {
        val shared = SportsLiveSharedBuckets()
        // Game 7 (day 6) is Grizzlies vs Heat.
        val window = SportsLiveUpcomingWindow(zone, followed = listOf(FollowedTeam(TEAMS[15], "Basketball")))
        val rows = shared.upcoming(load(games()), keys, order, now, window, stamp = 0L).rows
        assertEquals(5, rows.size)
        assertTrue(rows.any { it.id == "g7" && it.alternates.size == 1 })
    }

    @Test
    fun `My sports leaves out games of a sport it does not hold, counted, until All sports`() {
        val shared = SportsLiveSharedBuckets()
        val cricket = listOf(item(a, "c1", "India vs Australia", today.atTime(14, 0).toInstant(zone).toString(), genres = listOf("Cricket")))
        val load = load(games() + cricket)
        val mySports = MySports(mapOf(MySport.BASKETBALL to emptySet()))
        val scoped = shared.upcoming(load, keys, order, now, SportsLiveUpcomingWindow(zone, mySports = mySports), stamp = 0L)
        assertFalse(scoped.rows.any { it.id == "c1" })
        assertEquals(1, scoped.waitingBySport)
        val all = shared.upcoming(load, keys, order, now, SportsLiveUpcomingWindow(zone, mySports = null), stamp = 0L)
        assertTrue(all.rows.any { it.id == "c1" })
        assertEquals(0, all.waitingBySport)
    }

    @Test
    fun `with every day asked the rows are a full bucketing's`() {
        for (seed in 1..25) {
            val random = Random(seed)
            // Games up to nine days ahead on the quarter hour (some near midnight), each listed by one or both addons, the
            // copies up to 20 minutes apart and either way round; some with a date only, some with no date.
            val items = (0 until 40).flatMap { game ->
                val home = TEAMS[random.nextInt(TEAMS.size)]
                var away = TEAMS[random.nextInt(TEAMS.size)]
                while (away == home) away = TEAMS[random.nextInt(TEAMS.size)]
                val start = now + random.nextInt(1, 9 * 24 * 4) * 15 * MINUTE
                val form = random.nextInt(10)
                (0 until random.nextInt(1, 3)).map { copy ->
                    val catalog = if (copy == 0) a else b
                    val name = if (random.nextBoolean()) "$home vs $away" else "$away vs $home"
                    when (form) {
                        0 -> item(catalog, "g$game", name, released = null, releaseInfo = Instant.ofEpochMilli(start).atZone(zone).toLocalDate().toString())
                        1 -> item(catalog, "g$game", name, released = null)
                        else -> item(catalog, "g$game", name, Instant.ofEpochMilli(start + random.nextInt(0, 3) * 10 * MINUTE).toString())
                    }
                }
            }
            val load = load(items)
            val windowed = SportsLiveSharedBuckets().upcoming(load, keys, order, now, SportsLiveUpcomingWindow(zone, days = 12), stamp = 0L)
            val full = SportsLiveItemBucketer.bucketTab(SportsLiveItemBucketer.reclock(items, now), SportsLiveTab.UPCOMING, now, order)
            assertEquals("seed $seed", full, windowed.rows)
            assertTrue(windowed.pendingDates.isEmpty())
        }
    }

    private fun item(
        catalog: ClaimedCatalog,
        id: String,
        name: String,
        released: String?,
        releaseInfo: String? = null,
        genres: List<String> = listOf("Basketball")
    ): SportsLiveItem = SportsLiveItemBucketer.describe(
        MetaPreview(
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
        ),
        catalog,
        now
    )

    private fun claimed(addonId: String): ClaimedCatalog {
        val catalog = CatalogDescriptor(type = ContentType.fromString("tv"), rawType = "tv", id = "games", name = "Games")
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
        return ClaimedCatalog(addon, catalog, "${addonId}_tv_games", SportsLiveKind.EVENTS)
    }

    private companion object {
        const val MINUTE = 60_000L
        val TEAMS = listOf(
            "Hawks", "Celtics", "Nets", "Hornets", "Bulls", "Cavaliers", "Mavericks", "Nuggets", "Pistons", "Warriors", "Rockets",
            "Pacers", "Clippers", "Lakers", "Grizzlies", "Heat", "Bucks", "Timberwolves", "Pelicans", "Knicks", "Thunder", "Magic",
            "Suns", "Blazers", "Kings", "Spurs"
        )
    }
}
