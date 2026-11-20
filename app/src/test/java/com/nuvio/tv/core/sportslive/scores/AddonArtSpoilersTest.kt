package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #182: addon art that may carry a painted-in score never shows under Hide or Off, nor for a saved game. */
class AddonArtSpoilersTest {
    private val now = 1_800_000_000_000L
    private val hour = 60 * 60_000L

    private fun item(
        name: String = "Team A vs Team B",
        live: Boolean = false,
        startMs: Long? = null,
        kind: SportsLiveKind = SportsLiveKind.EVENTS,
        finished: Boolean = false
    ) = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = "https://example.invalid/p.png",
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
        kind = kind, isLive = live, isChannel = kind == SportsLiveKind.CHANNELS, startMs = startMs, sport = null,
        startHasTimeOfDay = startMs != null, isFinished = finished
    )

    private fun hides(item: SportsLiveItem, mode: ScoresMode?, protected: Boolean = false) =
        AddonArtSpoilers.hidesAddonArt(item, mode, protected, now)

    @Test
    fun `under Show every game keeps its art`() {
        assertFalse(hides(item(live = true), ScoresMode.SHOW))
        assertFalse(hides(item(finished = true), ScoresMode.SHOW))
        // Settings not loaded yet read as the default, Show.
        assertFalse(hides(item(live = true), null))
    }

    @Test
    fun `under Hide or Off a live, finished, started or replay game never shows addon art`() {
        for (mode in listOf(ScoresMode.HIDE_UNTIL_FOCUSED, ScoresMode.OFF)) {
            assertTrue(hides(item(live = true), mode))
            assertTrue(hides(item(finished = true), mode))
            assertTrue(hides(item(startMs = now - 2 * hour), mode))
            assertTrue(hides(item(kind = SportsLiveKind.REPLAY), mode))
            // A listing that says it is over ("FINISHED 92–83") whatever its time.
            assertTrue(hides(item(name = "FINISHED: Team A vs Team B"), mode))
        }
    }

    @Test
    fun `a game not started and a channel keep their art`() {
        assertFalse(hides(item(startMs = now + 2 * hour), ScoresMode.HIDE_UNTIL_FOCUSED))
        assertFalse(hides(item(name = "Sports Channel 1", live = true, kind = SportsLiveKind.CHANNELS), ScoresMode.OFF))
    }

    @Test
    fun `a game saved for later never shows addon art, even under Show`() {
        assertTrue(hides(item(live = true), ScoresMode.SHOW, protected = true))
        assertTrue(hides(item(kind = SportsLiveKind.REPLAY), ScoresMode.SHOW, protected = true))
        assertFalse(hides(item(startMs = now + 2 * hour), ScoresMode.SHOW, protected = true))
    }
}
