package com.nuvio.tv.ui.screens.sportslive.dual

import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.MINUTE
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.START
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.item
import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.domain.model.SportsLiveItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SportsLiveNextGameTest {

    private val nba = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA).filter { it.individual == null }.take(4)
    private val nfl = EspnFixtures.events(EspnFixtures.NFL_20261115, EspnLeagueMap.NFL).first { it.individual == null }

    private fun card(event: ScoreSnapshot, sport: String, followed: Boolean = false): SportsLiveItem =
        item(name = "${event.away.displayName} at ${event.home.displayName}", id = event.eventId, sport = sport, followed = followed)

    private fun inPlay(event: ScoreSnapshot, home: Int, away: Int, clock: String) = event.copy(
        state = ScoreState.IN, isFinal = false, clockText = clock,
        home = event.home.copy(score = home), away = event.away.copy(score = away)
    )

    private fun over(event: ScoreSnapshot) = event.copy(
        state = ScoreState.POST, isFinal = true, clockText = "Final",
        home = event.home.copy(score = 100), away = event.away.copy(score = 90)
    )

    private val ended = card(nba[0], "Basketball")
    private val nbaClose = card(nba[1], "Basketball")
    private val nbaBlowout = card(nba[2], "Basketball")
    private val nbaQuiet = card(nba[3], "Basketball")
    private val nflOvertime = card(nfl, "American Football")

    private val scores = mapOf(
        ended.streamKey to over(nba[0]),
        nbaClose.streamKey to inPlay(nba[1], 98, 98, "Q4 2:00"),
        nbaBlowout.streamKey to inPlay(nba[2], 120, 80, "Q4 2:00"),
        nflOvertime.streamKey to inPlay(nfl, 20, 20, "5:00 - OT")
    )

    @Test
    fun `a followed team's live game comes first, even a blowout`() {
        val followedBlowout = nbaBlowout.copy(followed = true)
        val pick = SportsLiveNextGame.pick(ended, listOf(ended, nbaClose, followedBlowout, nflOvertime), scores, emptySet(), emptySet())
        assertEquals(followedBlowout, pick)
    }

    @Test
    fun `then a big game in the same league, over a bigger one elsewhere`() {
        val pick = SportsLiveNextGame.pick(ended, listOf(ended, nflOvertime, nbaClose, nbaBlowout), scores, emptySet(), emptySet())
        assertEquals(nbaClose, pick)
    }

    @Test
    fun `then the top big game live anywhere`() {
        val pick = SportsLiveNextGame.pick(ended, listOf(ended, nbaBlowout, nflOvertime), scores, emptySet(), emptySet())
        assertEquals(nflOvertime, pick)
    }

    @Test
    fun `then the next game in the zap lineup, forward and wrapping`() {
        val lineup = listOf(nbaQuiet, ended, nbaBlowout)
        // The blowout is not big: lineup order from the game that ended.
        assertEquals(nbaBlowout, SportsLiveNextGame.pick(ended, lineup, scores, emptySet(), emptySet()))
        assertEquals(nbaQuiet, SportsLiveNextGame.pick(ended, listOf(nbaBlowout, ended, nbaQuiet), scores - nbaBlowout.streamKey, setOf(nbaBlowout.streamKey), emptySet()))
    }

    @Test
    fun `never a game tried and failed, finished, or the one that ended, and null when nothing is left`() {
        val lineup = listOf(ended, nbaClose, nbaQuiet)
        assertEquals(nbaQuiet, SportsLiveNextGame.pick(ended, lineup, scores, emptySet(), tried = setOf(nbaClose.id)))
        assertNull(SportsLiveNextGame.pick(ended, lineup, scores, finishedKeys = setOf(nbaQuiet.streamKey), tried = setOf(nbaClose.id)))
        assertNull(SportsLiveNextGame.pick(ended, listOf(ended), scores, emptySet(), emptySet()))
    }

    @Test
    fun `the nothing-live state names the soonest game still to start, a followed one first`() {
        val now = START
        val soon = item(name = "Bulls @ Nets", id = "soon", startMs = START + 30 * MINUTE)
        val later = item(name = "Heat @ Magic", id = "later", startMs = START + 90 * MINUTE, followed = true)
        val live = item(name = "Jazz @ Kings", id = "live", startMs = START - 30 * MINUTE, isLive = true)
        assertEquals(later, SportsLiveNextGame.upcoming(listOf(live, soon, later), now))
        assertEquals(soon, SportsLiveNextGame.upcoming(listOf(live, soon, later.copy(followed = false)), now))
        assertNull(SportsLiveNextGame.upcoming(listOf(live), now))
    }
}
