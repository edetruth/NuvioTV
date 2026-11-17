package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.SportsLiveOpenGame
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsLivePlayActionTest {

    @Test
    fun `a live game or channel plays its best stream in every autoplay mode by default`() {
        StreamAutoPlayMode.entries.forEach { mode ->
            listOf(SINGLE, MERGED, CHANNEL).forEach { item ->
                assertEquals(SportsLivePlayAction.PLAY_BEST, sportsLivePlayAction(item, mode))
            }
        }
    }

    @Test
    fun `let me choose opens the Stream screen for a single source and the picker for a merged game`() {
        StreamAutoPlayMode.entries.forEach { mode ->
            assertEquals(
                SportsLivePlayAction.STREAM_SCREEN,
                sportsLivePlayAction(SINGLE, mode, SportsLiveOpenGame.LET_ME_CHOOSE)
            )
            assertEquals(SportsLivePlayAction.PICK, sportsLivePlayAction(MERGED, mode, SportsLiveOpenGame.LET_ME_CHOOSE))
        }
    }

    @Test
    fun `choose stream lets the user pick whatever the setting says`() {
        SportsLiveOpenGame.entries.forEach { openGame ->
            assertEquals(
                SportsLivePlayAction.STREAM_SCREEN,
                sportsLivePlayAction(SINGLE, StreamAutoPlayMode.FIRST_STREAM, openGame, choose = true)
            )
            assertEquals(
                SportsLivePlayAction.PICK,
                sportsLivePlayAction(MERGED, StreamAutoPlayMode.FIRST_STREAM, openGame, choose = true)
            )
        }
    }

    @Test
    fun `replays and games not started keep their behaviour`() {
        StreamAutoPlayMode.entries.forEach { mode ->
            assertEquals(SportsLivePlayAction.STREAM_SCREEN, sportsLivePlayAction(REPLAY, mode))
            assertEquals(SportsLivePlayAction.STREAM_SCREEN, sportsLivePlayAction(UPCOMING, mode))
        }
        assertEquals(SportsLivePlayAction.PICK, sportsLivePlayAction(UPCOMING_MERGED, StreamAutoPlayMode.MANUAL))
        assertEquals(SportsLivePlayAction.PLAY_BEST, sportsLivePlayAction(UPCOMING_MERGED, StreamAutoPlayMode.FIRST_STREAM))
    }

    @Test
    fun `only live games and channels are eligible`() {
        assertTrue(sportsLiveAutoPlayEligible(SINGLE))
        assertTrue(sportsLiveAutoPlayEligible(CHANNEL))
        assertFalse(sportsLiveAutoPlayEligible(UPCOMING))
        assertFalse(sportsLiveAutoPlayEligible(REPLAY))
        assertFalse(sportsLiveAutoPlayEligible(REPLAY.copy(isChannel = true)))
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
            addonBaseUrl = "https://$addon.example.invalid",
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
        val CHANNEL = SINGLE.copy(kind = SportsLiveKind.CHANNELS, isLive = false, isChannel = true)
        val UPCOMING = SINGLE.copy(isLive = false, startMs = 4_000_000_000_000L, startHasTimeOfDay = true)
        val UPCOMING_MERGED = MERGED.copy(isLive = false, startMs = 4_000_000_000_000L, startHasTimeOfDay = true)
        val REPLAY = SINGLE.copy(kind = SportsLiveKind.REPLAY, isLive = false)
    }
}
