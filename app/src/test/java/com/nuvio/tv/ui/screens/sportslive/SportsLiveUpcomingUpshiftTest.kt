package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.SportsLiveChannelLists
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoreTeam
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.core.sportslive.scores.TeamStanding
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Live.23 RC (TV report): back on Upcoming from Channels, the list first drew its time slots, then shifted to put
 * Games to watch on top. A board built before Upcoming's feeds answer again keeps the sections it was left with, and
 * Upcoming built ahead reads what its normal build reads.
 */
class SportsLiveUpcomingUpshiftTest {

    private val zone = ZoneId.of("UTC")
    private val now = ZonedDateTime.of(2026, 11, 18, 13, 9, 0, 0, zone).toInstant().toEpochMilli()

    private fun at(hour: Int): Long = ZonedDateTime.of(2026, 11, 18, hour, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun item(name: String, startMs: Long) = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = listOf("College Basketball")
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Events",
        kind = SportsLiveKind.EVENTS, isLive = false, isChannel = false,
        startMs = startMs, sport = "College Basketball", startHasTimeOfDay = true,
        teams = SportsLiveTeams.parse(name)
    )

    private fun team(name: String, rank: Int?) = ScoreTeam(null, null, name, name, name, null, null, null, emptyList(), rank = rank)

    private fun event(id: String, startMs: Long, home: ScoreTeam, away: ScoreTeam, state: ScoreState = ScoreState.PRE) = ScoreSnapshot(
        eventId = id, league = EspnLeagueMap.COLLEGE_BASKETBALL, startMs = startMs, state = state,
        clockText = null, isFinal = false, home = home, away = away
    )

    private val big = item("Florida vs Duke", at(20))
    private val small = item("Iowa vs Purdue", at(19))
    private val other = item("Kansas vs Houston", at(22))
    private val items = listOf(small, big, other)

    /** Upcoming's scores as tracked on Upcoming: every game matched, one of them big. */
    private val tracked = mapOf(
        big.streamKey to event("1", at(20), team("Florida", 1), team("Duke", 2)),
        small.streamKey to event("2", at(19), team("Iowa", null), team("Purdue", null)),
        other.streamKey to event("3", at(22), team("Kansas", null), team("Houston", null))
    )

    private fun inputs(scores: Map<String, ScoreSnapshot>, mode: ScoresMode? = ScoresMode.SHOW) = SportsLiveBoardInputs(
        tab = SportsLiveTab.UPCOMING, filter = null, items = items,
        scores = SportsLiveBoardScores(scores = scores, mode = mode), ended = emptyMap(), excitement = emptyMap(),
        followed = emptyList(), clockMs = now, logoRevision = 0
    )

    /** What the viewer sees move: each day, slot (Games to watch or a time) and game in list order. */
    private fun structure(board: SportsLiveBoard): List<String> {
        val agenda = board.agenda!!
        return agenda.days.map { "day ${it.kind}" } +
            agenda.slots.map { "slot ${it.day} picks=${it.picks} start=${it.startMs} count=${it.count}" } +
            agenda.games.map { "game ${it.row.item.streamKey} day=${it.day} slot=${it.slot} pick=${it.pick}" }
    }

    @Test
    fun `back on Upcoming before its scores answer, the board keeps its Games to watch and every section`() {
        val known = SportsLiveKnownMatches()
        val left = inputs(tracked)
        known.learn(left.scores.scores, generation = 0)
        val kept = SportsLiveBoard.build(known.fill(left), zone)
        assertTrue(kept.agenda!!.slots.first().picks)

        // On Channels nothing tracks Upcoming's cards, so its scores are gone when the tab comes back.
        val back = inputs(emptyMap())
        known.learn(back.scores.scores, generation = 0)
        // The bug: built from what the screen has, the board lost its Games to watch (then got them back).
        assertNotEquals(structure(kept), structure(SportsLiveBoard.build(back, zone)))
        // Fixed: the same sections, rows and positions as the board kept.
        assertEquals(structure(kept), structure(SportsLiveBoard.build(known.fill(back), zone)))

        // Once its feeds answer again (same data), still the same structure: rows update in place.
        known.learn(left.scores.scores, generation = 0)
        assertEquals(structure(kept), structure(SportsLiveBoard.build(known.fill(left), zone)))
    }

    @Test
    fun `a game no longer pre-game, Scores Off and other tabs are never filled in`() {
        val known = SportsLiveKnownMatches()
        known.learn(tracked, generation = 0)
        // Scores Off (or not known yet): as it is.
        val off = inputs(emptyMap(), mode = ScoresMode.OFF)
        assertSame(off, known.fill(off))
        val unknown = inputs(emptyMap(), mode = null)
        assertSame(unknown, known.fill(unknown))
        // Another tab: as it is.
        val live = inputs(emptyMap()).copy(tab = SportsLiveTab.LIVE_NOW)
        assertSame(live, known.fill(live))
        // A game in play forgets its card: no score is ever kept.
        known.learn(mapOf(big.streamKey to event("1", at(20), team("Florida", 1), team("Duke", 2), ScoreState.IN)), generation = 0)
        val filled = known.fill(inputs(emptyMap())).scores.scores
        assertTrue(big.streamKey !in filled)
        assertEquals(setOf(small.streamKey, other.streamKey), filled.keys)
        // A match the screen has now wins over the one known.
        val fresh = event("9", at(19), team("Iowa", null), team("Purdue", null))
        assertSame(fresh, known.fill(inputs(mapOf(small.streamKey to fresh))).scores.scores[small.streamKey])
    }

    @Test
    fun `a drop of every board on a profile or setting change forgets the matches`() {
        val known = SportsLiveKnownMatches()
        known.learn(tracked, generation = 0)
        known.learn(emptyMap(), generation = 1)
        val back = inputs(emptyMap())
        assertSame(back, known.fill(back))
    }

    @Test
    fun `Upcoming built ahead reads what its normal build reads and lays out the same board`() {
        val scores = SportsLiveBoardScores(scores = tracked, mode = ScoresMode.SHOW)
        val standings = mapOf(EspnLeagueMap.COLLEGE_BASKETBALL to emptyMap<String, TeamStanding>())
        // Normal: Upcoming selected.
        val onUpcoming = SportsLiveUiState(
            selectedTab = SportsLiveTab.UPCOMING, visibleItems = items, tabs = mapOf(SportsLiveTab.UPCOMING to items), nowMs = now + 5_000
        )
        val normal = sportsLiveBoardInputsOf(
            state = onUpcoming, tab = onUpcoming.selectedTab, filter = onUpcoming.selectedFilter, items = onUpcoming.visibleItems,
            scores = scores, followed = emptyList(), logoRevision = 7, standings = standings,
            channelLists = SportsLiveChannelLists.NONE, unmatched = setOf("x")
        )
        // Ahead: Live now selected, with its own Live-now-only state.
        val onLiveNow = onUpcoming.copy(selectedTab = SportsLiveTab.LIVE_NOW, visibleItems = emptyList(), noStreams = setOf("y"))
        val ahead = sportsLiveBoardInputsOf(
            state = onLiveNow, tab = SportsLiveTab.UPCOMING, filter = null, items = items,
            scores = scores, followed = emptyList(), logoRevision = Int.MIN_VALUE, standings = standings,
            channelLists = SportsLiveChannelLists.NONE, unmatched = emptySet()
        )
        // Only the logo revision differs (on purpose: a board built ahead is never taken as up to date).
        assertEquals(normal, ahead.copy(logoRevision = normal.logoRevision))
        assertEquals(standings, ahead.standings)
        assertEquals(structure(SportsLiveBoard.build(normal, zone)), structure(SportsLiveBoard.build(ahead, zone)))
        assertTrue(SportsLiveBoard.build(ahead, zone).agenda!!.slots.first().picks)
    }
}
