package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import org.junit.Assert.assertEquals
import org.junit.Test

class SportsLivePlayActionTest {

    @Test
    fun `a single-source card opens the Stream screen in every mode`() {
        StreamAutoPlayMode.entries.forEach { mode ->
            assertEquals(SportsLivePlayAction.STREAM_SCREEN, sportsLivePlayAction(SINGLE, mode))
        }
    }

    @Test
    fun `a merged card in manual mode opens the picker`() {
        assertEquals(SportsLivePlayAction.PICK, sportsLivePlayAction(MERGED, StreamAutoPlayMode.MANUAL))
    }

    @Test
    fun `a merged card with autoplay plays the best stream`() {
        StreamAutoPlayMode.entries.filter { it != StreamAutoPlayMode.MANUAL }.forEach { mode ->
            assertEquals(SportsLivePlayAction.PLAY_BEST, sportsLivePlayAction(MERGED, mode))
        }
    }

    private companion object {
        fun item(id: String, addon: String) = SportsLiveItem(
            meta = MetaPreview(
                id = id,
                type = ContentType.UNKNOWN,
                rawType = "sport",
                name = "A vs B",
                poster = null,
                posterShape = PosterShape.LANDSCAPE,
                background = null,
                logo = null,
                description = null,
                releaseInfo = "LIVE",
                imdbRating = null,
                genres = emptyList()
            ),
            addonId = addon,
            addonName = addon,
            addonBaseUrl = "https://$addon.example",
            catalogKey = "${addon}_sport_live",
            catalogName = "Live",
            kind = SportsLiveKind.EVENTS,
            isLive = true,
            isChannel = false,
            startMs = null,
            sport = null
        )

        val SINGLE = item("game", "one")
        val MERGED = SINGLE.copy(alternates = listOf(item("other-game", "two")))
    }
}
