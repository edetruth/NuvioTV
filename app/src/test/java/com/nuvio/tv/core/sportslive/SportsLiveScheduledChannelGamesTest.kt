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
import org.junit.Assert.assertTrue
import org.junit.Test

/** live.20 F16, live.24 #12: a channels catalog's game entries are games (Live now, Upcoming), not channels. */
class SportsLiveScheduledChannelGamesTest {
    private val now = Instant.parse("2026-11-20T16:00:00Z").toEpochMilli()

    @Test
    fun `a scheduled game entry with a start time goes to Upcoming`() {
        val game = entry("g", "Clemson Tigers @ Duke Blue Devils", released = "2026-11-20T19:00:00Z")
        assertFalse(game.isChannel)
        assertEquals(SportsLiveKind.EVENTS, game.kind)
        assertTrue(listed(game, SportsLiveTab.UPCOMING))
        assertFalse(listed(game, SportsLiveTab.CHANNELS))
    }

    @Test
    fun `a real channel stays in Channels`() {
        val channel = entry("c", "TSN 4")
        assertTrue(channel.isChannel)
        assertTrue(listed(channel, SportsLiveTab.CHANNELS))
        assertFalse(listed(channel, SportsLiveTab.UPCOMING))
        // A timed channel name naming no game stays a channel too.
        assertTrue(entry("c2", "Sky Sports News", released = "2026-11-20T19:00:00Z").isChannel)
        // A channel airing a game named in brackets stays a channel.
        assertTrue(entry("ppv", "US| PEACOCK PPV 004 [Celtics vs. 76ers]", released = "2026-11-20T19:00:00Z").isChannel)
    }

    @Test
    fun `a game entry is a game whatever its time, live or not`() {
        val live = entry("l", "🔴 Sevilla vs. Real Betis", released = "2026-11-20T19:00:00Z")
        assertFalse(live.isChannel)
        assertTrue(live.isLive)
        assertTrue(listed(live, SportsLiveTab.LIVE_NOW))
        assertFalse(listed(live, SportsLiveTab.CHANNELS))
        // Started already: live by its clock.
        val started = entry("p", "Nice vs. Paris Saint-Germain", released = "2026-11-20T15:00:00Z")
        assertFalse(started.isChannel)
        assertTrue(listed(started, SportsLiveTab.LIVE_NOW))
        // No start at all: a game with its time to come, never a channel.
        val untimed = entry("n", "AS Monaco vs. AJ Auxerre")
        assertFalse(untimed.isChannel)
        assertEquals(SportsLiveKind.EVENTS, untimed.kind)
        assertTrue(listed(untimed, SportsLiveTab.UPCOMING))
        assertFalse(listed(untimed, SportsLiveTab.CHANNELS))
    }

    private fun listed(item: SportsLiveItem, tab: SportsLiveTab) =
        SportsLiveItemBucketer.bucketTab(listOf(item), tab, now, addonOrder = listOf("a")).any { it.id == item.id }

    private fun entry(id: String, name: String, released: String? = null): SportsLiveItem =
        SportsLiveItemBucketer.describe(
            MetaPreview(
                id = id, type = ContentType.UNKNOWN, rawType = "tv", name = name, poster = null,
                posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
                releaseInfo = null, imdbRating = null, genres = emptyList(), released = released
            ),
            claimed(),
            now
        )

    private fun claimed(): ClaimedCatalog {
        val catalog = CatalogDescriptor(type = ContentType.fromString("tv"), rawType = "tv", id = "tv", name = "TV")
        val addon = Addon(
            id = "a", name = "Channels a", version = "1.0.0", description = null, logo = null,
            baseUrl = "https://example.invalid", catalogs = listOf(catalog), types = listOf(catalog.type), resources = emptyList()
        )
        return ClaimedCatalog(addon, catalog, "a_tv_tv", SportsLiveKind.CHANNELS, false)
    }
}
