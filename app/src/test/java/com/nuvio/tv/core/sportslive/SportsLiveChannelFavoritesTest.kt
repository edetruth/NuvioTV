package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsLiveChannelFavoritesTest {

    private fun channel(id: String, name: String = id, addon: String = "a") = SportsLiveItem(
        meta = MetaPreview(
            id = id, type = ContentType.UNKNOWN, rawType = "tv", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = addon, addonName = addon, addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Channels",
        kind = SportsLiveKind.CHANNELS, isLive = true, isChannel = true, startMs = null, sport = null
    )

    private val f = SportsLiveChannelFavorites

    @Test
    fun `the name key drops quality and symbols and keeps the region`() {
        assertEquals(f.nameKey("US: Sports One FHD"), f.nameKey("US | Sports One HD"))
        assertTrue(f.nameKey("UK: Sports One") != f.nameKey("US: Sports One"))
    }

    @Test
    fun `adding a favorite puts it last, and adding it again changes nothing`() {
        val one = channel("1")
        val two = channel("2")
        val picks = f.toggle(f.toggle(SportsLiveChannelPicks(), one, favorite = false), two, favorite = false)
        assertEquals(listOf("1", "2"), picks.favorites.map { it.id })
        assertSame(picks, f.toggle(picks, one, favorite = false))
    }

    @Test
    fun `removing a favorite removes its own entry by id first`() {
        val hd = channel("1", "US: Sports One HD")
        val fhd = channel("2", "US: Sports One FHD")
        val picks = f.toggle(f.toggle(SportsLiveChannelPicks(), hd, false), fhd, false)
        assertEquals(listOf("2"), f.toggle(picks, hd, favorite = true).favorites.map { it.id })
    }

    @Test
    fun `removing a favorite found by name removes the stale entry`() {
        val old = f.refOf(channel("old-id", "US: Sports One"))
        val renumbered = channel("new-id", "US: Sports One")
        val picks = SportsLiveChannelPicks(favorites = listOf(old))
        assertEquals(listOf(renumbered), f.resolve(picks.favorites, listOf(renumbered)))
        assertTrue(f.toggle(picks, renumbered, favorite = true).favorites.isEmpty())
    }

    @Test
    fun `a play goes first in recent, once, and recent keeps at most ten`() {
        var picks = SportsLiveChannelPicks()
        (1..12).forEach { picks = f.played(picks, channel("$it")) }
        picks = f.played(picks, channel("5"))
        assertEquals(f.RECENT_KEPT, picks.recent.size)
        assertEquals(listOf("5", "12", "11", "10"), picks.recent.take(4).map { it.id })
        assertEquals(1, picks.recent.count { it.id == "5" })
    }

    @Test
    fun `a favorite is found by id, else by name in the same addon, never in another addon`() {
        val ref = f.refOf(channel("1", "US: Sports One", addon = "a"))
        // Same id kept: found even under a new name.
        assertEquals(listOf("1"), f.resolve(listOf(ref), listOf(channel("1", "Renamed", addon = "a"))).map { it.id })
        // New id, same name, same addon: found.
        assertEquals(listOf("9"), f.resolve(listOf(ref), listOf(channel("9", "US: Sports One HD", addon = "a"))).map { it.id })
        // The same name in another addon is another stream.
        assertTrue(f.resolve(listOf(ref), listOf(channel("1", "US: Sports One", addon = "b"))).isEmpty())
    }

    @Test
    fun `a favorite no catalog lists is hidden and kept`() {
        val gone = f.refOf(channel("gone"))
        val here = f.refOf(channel("here"))
        val picks = SportsLiveChannelPicks(favorites = listOf(gone, here))
        assertEquals(listOf("here"), f.sections(listOf(channel("x"), channel("here")), picks).favorites.map { it.id })
        assertEquals(2, picks.favorites.size)
    }

    @Test
    fun `sections list favorites in the order added, then three recent that are not favorites, then the rest as listed`() {
        val channels = (1..8).map { channel("$it") }
        val picks = SportsLiveChannelPicks(
            favorites = listOf("4", "2").map { f.refOf(channel(it)) },
            recent = listOf("2", "7", "1", "8", "3").map { f.refOf(channel(it)) }
        )
        val sections = f.sections(channels, picks)
        assertEquals(listOf("4", "2"), sections.favorites.map { it.id })
        assertEquals(listOf("7", "1", "8"), sections.recent.map { it.id })
        assertEquals(listOf("3", "5", "6"), sections.rest.map { it.id })
    }

    @Test
    fun `no picks leave the channels as they are`() {
        val channels = listOf(channel("1"), channel("2"))
        val sections = f.sections(channels, SportsLiveChannelPicks.NONE)
        assertTrue(sections.favorites.isEmpty() && sections.recent.isEmpty())
        assertSame(channels, sections.rest)
    }

    @Test
    fun `only the picks' sections count as pick sections`() {
        assertTrue(f.isPickSection(f.FAVORITES))
        assertTrue(f.isPickSection(f.RECENT))
        assertFalse(f.isPickSection("US"))
        assertFalse(f.isPickSection(null))
    }

    // live.25 J: a pick synced from another TV carries its name key alone (no addon, no id).
    private fun synced(name: String) = SportsLiveChannelRef("", "", f.nameKey(name))

    @Test
    fun `a synced pick is found by name in any addon, a merged channel's copies too`() {
        val ref = synced("US: Sports One")
        assertEquals(listOf("9"), f.resolve(listOf(ref), listOf(channel("8", "UK: Sports One", addon = "b"), channel("9", "US: Sports One HD", addon = "b"))).map { it.id })
        val merged = channel("1", "Sports One Plus", addon = "c").copy(alternates = listOf(channel("2", "US: Sports One", addon = "d")))
        assertEquals(listOf("1"), f.resolve(listOf(ref), listOf(merged)).map { it.id })
        assertTrue(f.resolve(listOf(ref), listOf(channel("3", "US: Other One", addon = "b"))).isEmpty())
    }

    @Test
    fun `removing a favorite that came synced removes it by name`() {
        val picks = SportsLiveChannelPicks(favorites = listOf(synced("US: Sports One")))
        assertTrue(f.toggle(picks, channel("5", "US: Sports One FHD", addon = "b"), favorite = true).favorites.isEmpty())
    }

    @Test
    fun `a play keeps its time and replaces a synced pick of the same name`() {
        val picks = SportsLiveChannelPicks(recent = listOf(synced("US: Sports One"), synced("US: Other One")))
        val played = f.played(picks, channel("5", "US: Sports One", addon = "b"), nowMs = 1_000L)
        assertEquals(listOf("5", ""), played.recent.map { it.id })
        assertEquals(1_000L, played.recent.first().playedAt)
    }
}
