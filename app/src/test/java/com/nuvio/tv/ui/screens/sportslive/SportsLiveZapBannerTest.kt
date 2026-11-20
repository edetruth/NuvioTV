package com.nuvio.tv.ui.screens.sportslive

import android.view.KeyEvent
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.ChipScore
import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.ScoreChip
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #76: the zap banner's content and its spoiler handling; BUG6/Q10: ▲/▼ names, ▼ always zaps. */
class SportsLiveZapBannerTest {

    private val nba = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA)
    private val pacersEvent = nba.first { it.eventId == "401909985" } // IND @ PHI

    private fun item(name: String, sport: String = "Basketball", channel: Boolean = false) = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = "a", addonName = "Source A", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
        kind = if (channel) SportsLiveKind.CHANNELS else SportsLiveKind.EVENTS, isLive = true, isChannel = channel,
        startMs = null, sport = sport, teams = if (channel) null else SportsLiveTeams.parse(name)
    )

    private val pacers = item("Pacers @ 76ers")
    private val knicks = item("Knicks vs Celtics")
    private val channel = item("Sports Channel 1", sport = "Sports", channel = true)
    private val session = SportsLiveZapSession.of(listOf(channel, pacers, knicks), pacers.streamKey, SportsLiveTab.LIVE_NOW, null, false)!!

    private fun live(event: ScoreSnapshot) = event.copy(
        state = ScoreState.IN, isFinal = false, clockText = "Q3 4:12",
        home = event.home.copy(score = 80), away = event.away.copy(score = 88)
    )

    private val scores = mapOf(pacers.streamKey to live(pacersEvent))

    @Test
    fun `the banner names the game, its league tag but never the source, its place and the next game`() {
        val banner = session.banner(switching = false, scores = scores, mode = ScoresMode.SHOW)
        assertEquals("Pacers @ 76ers", banner.name)
        assertEquals("NBA", banner.tag)
        assertEquals(2 to 3, banner.position to banner.total)
        assertEquals("Knicks vs Celtics", banner.next)
        // Logos and names come from the matched game, in the card's order.
        assertEquals("IND", banner.art?.first?.short)
        assertEquals("PHI", banner.art?.second?.short)
    }

    @Test
    fun `the score chip follows the Scores setting and the banner never reveals it`() {
        assertEquals(ScoreChip.Live("Q3 4:12", ChipScore(88, 80)), session.banner(false, scores, ScoresMode.SHOW).chip)
        assertEquals(ScoreChip.Hidden, session.banner(false, scores, ScoresMode.HIDE_UNTIL_FOCUSED).chip)
        assertNull(session.banner(false, scores, ScoresMode.OFF).chip)
        // Before the setting is read: no score at all.
        assertNull(session.banner(false, scores, mode = null).chip)
        // Off: no ESPN names either.
        assertEquals("Pacers", session.banner(false, scores, ScoresMode.OFF).art?.first?.short)
    }

    @Test
    fun `a channel has no art or chip, and an ended next game is skipped`() {
        val onChannel = session.moveTo(channel.streamKey)!!
        val banner = onChannel.banner(switching = true, scores = scores, mode = ScoresMode.SHOW, endedKeys = setOf(pacers.streamKey))
        assertNull(banner.art)
        assertNull(banner.chip)
        // A catch-all sport ("Sports") names no sport: no tag.
        assertNull(banner.tag)
        assertEquals("Knicks vs Celtics", banner.next)
        // A session of one game has no next game.
        val single = SportsLiveZapSession.of(listOf(pacers), pacers.streamKey, SportsLiveTab.LIVE_NOW, null, false)!!
        assertNull(single.banner(false).next)
    }

    @Test
    fun `the banner names the games up and down zap to, from the frozen zap order`() {
        val banner = session.banner(switching = false)
        assertEquals("Sports Channel 1", banner.previous)
        assertEquals("Knicks vs Celtics", banner.next)
        // Two games: both keys go to the other one.
        val two = SportsLiveZapSession.of(listOf(pacers, knicks), pacers.streamKey, SportsLiveTab.LIVE_NOW, null, false)!!
        assertEquals("Knicks vs Celtics", two.banner(false).previous)
        assertEquals("Knicks vs Celtics", two.banner(false).next)
        assertNull(SportsLiveZapSession.of(listOf(pacers), pacers.streamKey, SportsLiveTab.LIVE_NOW, null, false)!!.banner(false).previous)
    }

    @Test
    fun `down zaps on while the arrival banner shows, as its line says`() {
        assertEquals(true, zapDirection(KeyEvent.KEYCODE_DPAD_DOWN, partyActive = false))
        assertEquals(false, zapDirection(KeyEvent.KEYCODE_DPAD_UP, partyActive = false))
        assertNull(zapDirection(KeyEvent.KEYCODE_DPAD_DOWN, partyActive = true))
    }
}
