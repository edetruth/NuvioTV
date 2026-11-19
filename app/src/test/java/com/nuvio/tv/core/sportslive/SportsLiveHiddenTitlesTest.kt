package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Hide from Live Now" (#134): the profile's hidden list, its round trip, and what it leaves off Live Now. */
class SportsLiveHiddenTitlesTest {

    private fun item(name: String, alternates: List<SportsLiveItem> = emptyList()) = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "tv", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
        kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false, startMs = null, sport = null,
        alternates = alternates
    )

    @Test
    fun `a hidden title round-trips through the stored list`() {
        val hidden = SportsLiveHiddenTitles.add(emptySet(), "LIVE: Late Night Talk Show [HD]")
        assertEquals(setOf("Late Night Talk Show"), hidden)
        // The same title again, in another case or spacing, is not stored twice.
        assertEquals(hidden, SportsLiveHiddenTitles.add(hidden, "late  night TALK show"))
        // Settings' OK takes it off the list.
        assertEquals(emptySet<String>(), SportsLiveHiddenTitles.remove(hidden, "Late Night Talk Show"))
        // Removing a title that is not there changes nothing.
        assertEquals(hidden, SportsLiveHiddenTitles.remove(hidden, "Another Show"))
    }

    @Test
    fun `hidden titles leave Live Now and the rest stay in order`() {
        val show = item("LIVE: Late Night Talk Show")
        val game = item("Celtics vs Knicks")
        val other = item("ATP Finals - Sinner vs Alcaraz")
        val hidden = SportsLiveHiddenTitles.add(emptySet(), "Late Night Talk Show")
        assertEquals(listOf(game, other), SportsLiveHiddenTitles.filter(listOf(show, game, other), hidden))
        // Nothing hidden: the list as it was.
        val all = listOf(show, game, other)
        assertTrue(SportsLiveHiddenTitles.filter(all, emptySet()) === all)
    }

    @Test
    fun `a merged card is hidden when any of its sources is`() {
        val merged = item("Talk Show Tonight (FHD)", alternates = listOf(item("Late Night Talk Show")))
        val keys = setOf(SportsLiveHiddenTitles.keyOf("Late Night Talk Show"))
        assertTrue(SportsLiveHiddenTitles.isHidden(merged, keys))
        assertFalse(SportsLiveHiddenTitles.isHidden(item("Celtics vs Knicks"), keys))
    }

    @Test
    fun `settings lists hidden titles A to Z`() {
        assertEquals(listOf("alpha show", "Beta Show", "Gamma"), SportsLiveHiddenTitles.sorted(setOf("Gamma", "Beta Show", "alpha show")))
    }
}
