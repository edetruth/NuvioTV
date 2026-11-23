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
