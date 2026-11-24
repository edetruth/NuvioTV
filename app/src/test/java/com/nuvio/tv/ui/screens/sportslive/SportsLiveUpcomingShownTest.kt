package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Report #25 (live.26): Upcoming as shown drops the games that have started, between sortings too. */
class SportsLiveUpcomingShownTest {

    private val pacers = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA).first { it.eventId == "401909985" }

    private fun espn(state: ScoreState, startMs: Long = NOW - HOUR): ScoreSnapshot =
        pacers.copy(state = state, isFinal = state == ScoreState.POST, startMs = startMs, timeValid = true)

    // Listed with no time of its own (Time TBA): only ESPN says whether it is on.
    private val game = item("p", "Indiana Pacers at Philadelphia 76ers", startMs = null)
    private val next = item("n", "Next vs Game", startMs = NOW + HOUR)

    private fun shown(
        scores: Map<String, ScoreSnapshot>,
        liveNowEvents: Set<String> = emptySet(),
        focusedId: String? = null
    ) = sportsLiveUpcomingShown(listOf(game, next), listOf(game, next), scores, NOW, focusedId, liveNowEvents)

    @Test
    fun `a game ESPN shows in play leaves Upcoming when Live now lists its ESPN game`() {
        val result = shown(mapOf(game.streamKey to espn(ScoreState.IN)), liveNowEvents = setOf(pacers.eventId))
        assertEquals(listOf("n"), result.items.map { it.id })
        assertNull(result.heldId)
    }

    @Test
    fun `a game ESPN shows in play stays on Upcoming when Live now does not list it, its only row to watch it from`() {
        assertEquals(listOf("p", "n"), shown(mapOf(game.streamKey to espn(ScoreState.IN))).items.map { it.id })
    }

    @Test
    fun `a late start ESPN still has before the game stays, as the Agenda keeps it under Today`() {
        assertEquals(listOf("p", "n"), shown(mapOf(game.streamKey to espn(ScoreState.PRE))).items.map { it.id })
    }

    @Test
    fun `a timed game past its own start that ESPN still has before the game stays, a late start`() {
        val late = item("p", "Indiana Pacers at Philadelphia 76ers", startMs = NOW - MINUTE)
        val scores = mapOf(late.streamKey to espn(ScoreState.PRE, startMs = NOW + HOUR))
        val result = sportsLiveUpcomingShown(emptyList(), listOf(late, next), scores, NOW, null, emptySet())
        assertEquals(listOf("p", "n"), result.items.map { it.id })
    }

    @Test
    fun `#355 - a game ESPN still has as not started leaves once it is well past its start, ESPN being stale`() {
        // ESPN's start 2 h ago, plus a minute: past the late-start grace.
        val stale = espn(ScoreState.PRE, startMs = NOW - UPCOMING_LATE_START_GRACE_MS - MINUTE)
        assertEquals(listOf("n"), shown(mapOf(game.streamKey to stale)).items.map { it.id })
        // Within the grace it stays, a late start.
        val late = espn(ScoreState.PRE, startMs = NOW - UPCOMING_LATE_START_GRACE_MS + MINUTE)
        assertEquals(listOf("p", "n"), shown(mapOf(game.streamKey to late)).items.map { it.id })
        // No valid ESPN time: the game's own timed start decides.
        val timed = item("t", "Indiana Pacers at Philadelphia 76ers", startMs = NOW - 5 * HOUR)
        val noTime = espn(ScoreState.PRE).copy(timeValid = false)
        val result = sportsLiveUpcomingShown(emptyList(), listOf(timed, next), mapOf(timed.streamKey to noTime), NOW, null, emptySet())
        assertEquals(listOf("n"), result.items.map { it.id })
    }

    @Test
    fun `L7 - a date-only row whose date has passed everywhere leaves between sortings, today's stays`() {
        val yesterday = item("y", "Yesterday vs Game", startMs = null).copy(startMs = NOW - 36 * HOUR, startHasTimeOfDay = false)
        val today = item("t", "Today vs Game", startMs = null).copy(startMs = NOW - 36 * HOUR + MINUTE, startHasTimeOfDay = false)
        val result = sportsLiveUpcomingShown(emptyList(), listOf(yesterday, today, next), emptyMap(), NOW, null, emptySet())
        assertEquals(listOf("t", "n"), result.items.map { it.id })
        // A restored list moved by the clock alone does the same.
        val moved = com.nuvio.tv.core.sportslive.SportsLiveItemBucketer.moveKickedOff(
            mapOf(com.nuvio.tv.domain.model.SportsLiveTab.UPCOMING to listOf(yesterday, today)), NOW
        )
        assertEquals(listOf("t"), moved.getValue(com.nuvio.tv.domain.model.SportsLiveTab.UPCOMING).map { it.id })
    }

    @Test
    fun `#355 boundary - exactly at the grace stays, a stale PRE with no time at all stays`() {
        val atGrace = espn(ScoreState.PRE, startMs = NOW - UPCOMING_LATE_START_GRACE_MS)
        assertEquals(listOf("p", "n"), shown(mapOf(game.streamKey to atGrace)).items.map { it.id })
        assertEquals(listOf("p", "n"), shown(mapOf(game.streamKey to espn(ScoreState.PRE).copy(timeValid = false))).items.map { it.id })
    }

    @Test
    fun `a final leaves even with focus, an in-play game with focus is held where it was`() {
        assertEquals(listOf("n"), shown(mapOf(game.streamKey to espn(ScoreState.POST)), focusedId = "p").items.map { it.id })
        val held = shown(mapOf(game.streamKey to espn(ScoreState.IN)), liveNowEvents = setOf(pacers.eventId), focusedId = "p")
        assertEquals(listOf("p", "n"), held.items.map { it.id })
        assertEquals("p", held.heldId)
    }

    @Test
    fun `a game whose own timed start has passed leaves, one with no time stays`() {
        val started = item("s", "Started vs Game", startMs = NOW - MINUTE)
        val result = sportsLiveUpcomingShown(emptyList(), listOf(started, game, next), emptyMap(), NOW, null, emptySet())
        assertEquals(listOf("p", "n"), result.items.map { it.id })
    }

    private fun item(id: String, name: String, startMs: Long?) = SportsLiveItem(
        meta = MetaPreview(
            id = id,
            type = ContentType.UNKNOWN,
            rawType = "tv",
            name = name,
            poster = null,
            posterShape = PosterShape.LANDSCAPE,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList()
        ),
        addonId = "source.a",
        addonName = "Source A",
        addonBaseUrl = "https://a.example.invalid",
        catalogKey = "source.a_tv_events",
        catalogName = "Events",
        kind = SportsLiveKind.EVENTS,
        isLive = false,
        isChannel = false,
        startMs = startMs,
        sport = "Basketball",
        startHasTimeOfDay = startMs != null,
        teams = SportsLiveTeams.parse(name)
    )

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
