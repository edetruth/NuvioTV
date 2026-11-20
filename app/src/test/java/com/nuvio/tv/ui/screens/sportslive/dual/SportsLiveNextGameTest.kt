package com.nuvio.tv.ui.screens.sportslive.dual

import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.MINUTE
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.START
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.item
import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.core.sportslive.SportsLiveGameReadiness
import com.nuvio.tv.core.sportslive.SportsLiveWorkingLinks
import com.nuvio.tv.core.sportslive.scores.ProtectedGame
import com.nuvio.tv.core.sportslive.scores.SpoilerProtection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `a game marked with no working stream is never Up next, and null when only such games are left`() {
        val lineup = listOf(nbaQuiet, ended, nbaBlowout)
        assertEquals(nbaQuiet, SportsLiveNextGame.pick(ended, lineup, scores, emptySet(), emptySet(), noStream = setOf(nbaBlowout.id)))
        assertNull(SportsLiveNextGame.pick(ended, lineup, scores, emptySet(), emptySet(), noStream = setOf(nbaBlowout.id, nbaQuiet.id)))
    }

    // #184: a game saved with Watch later is never a next-game pick, which would play it live.

    private fun savedProtection(vararg events: ScoreSnapshot) =
        SpoilerProtection(games = events.fold(emptyList<ProtectedGame>()) { games, event -> SpoilerProtection.saved(games, event, START) }, nowMs = START)

    @Test
    fun `a game saved for later is never Up next, even the big game in the same league`() {
        val lineup = listOf(ended, nbaClose, nflOvertime)
        assertEquals(nbaClose, SportsLiveNextGame.pick(ended, lineup, scores, emptySet(), emptySet()))
        val saved = sportsLiveSavedForLater(savedProtection(nba[1]), scores)
        assertEquals(nflOvertime, SportsLiveNextGame.pick(ended, lineup, scores, emptySet(), emptySet(), savedForLater = saved))
        // Every live game saved: nothing is Up next.
        val all = sportsLiveSavedForLater(savedProtection(nba[1], nfl), scores)
        assertNull(SportsLiveNextGame.pick(ended, lineup, scores, emptySet(), emptySet(), savedForLater = all))
    }

    @Test
    fun `a saved game with no score match is known by its two sides near its start`() {
        val sameSides = nbaClose.copy(startMs = nba[1].startMs)
        val saved = sportsLiveSavedForLater(savedProtection(nba[1]), emptyMap())
        assertTrue(saved(sameSides))
        assertFalse(saved(nbaQuiet))
        assertEquals(nbaQuiet, SportsLiveNextGame.pick(ended, listOf(ended, sameSides, nbaQuiet), emptyMap(), emptySet(), emptySet(), savedForLater = saved))
    }

    @Test
    fun `only Watch later keeps a game out, not my teams' protection nor a revealed game`() {
        assertFalse(sportsLiveSavedForLater(SpoilerProtection(myTeams = true, nowMs = START), scores)(nbaClose))
        val revealed = SpoilerProtection(
            games = SpoilerProtection.ended(SpoilerProtection.saved(emptyList(), nba[1], START), nba[1], START),
            nowMs = START
        )
        assertFalse(sportsLiveSavedForLater(revealed, scores)(nbaClose))
    }

    @Test
    fun `next live game after a failure skips a game saved for later`() {
        val lineup = listOf(ended, nbaClose, nbaQuiet)
        assertEquals(nbaClose, SportsLiveSecondGame.nextAfterFailure(ended, lineup, scores, emptySet(), emptySet()))
        val saved = sportsLiveSavedForLater(savedProtection(nba[1]), scores)
        assertEquals(nbaQuiet, SportsLiveSecondGame.nextAfterFailure(ended, lineup, scores, emptySet(), emptySet(), savedForLater = saved))
    }

    @Test
    fun `a game whose every known link failed counts as having no working stream`() {
        val links = SportsLiveWorkingLinks(nowMs = { START })
        val readiness = SportsLiveGameReadiness(nowMs = { START })
        val only = dualStream("quiet-only")
        links.noteLinks(nbaQuiet.id, listOf(only))
        assertEquals(emptySet<String>(), sportsLiveNoStreamIds(listOf(nbaQuiet, nbaBlowout), readiness, links))
        links.failed(nbaQuiet.id, only.url)
        assertEquals(setOf(nbaQuiet.id), sportsLiveNoStreamIds(listOf(nbaQuiet, nbaBlowout), readiness, links))
        // Up next goes past it: lineup order would pick it first.
        val lineup = listOf(ended, nbaQuiet, nbaBlowout)
        assertEquals(nbaQuiet, SportsLiveNextGame.pick(ended, lineup, scores, emptySet(), emptySet()))
        val noStream = sportsLiveNoStreamIds(lineup, readiness, links)
        assertEquals(nbaBlowout, SportsLiveNextGame.pick(ended, lineup, scores, emptySet(), emptySet(), noStream = noStream))
    }

    @Test
    fun `a ready game goes first within the lineup step`() {
        val lineup = listOf(nbaQuiet, ended, nbaBlowout)
        // Lineup order alone picks the blowout (not big); the quiet game is ready.
        val pick = SportsLiveNextGame.pick(ended, lineup, scores, emptySet(), emptySet(), knownGood = { it == nbaQuiet })
        assertEquals(nbaQuiet, pick)
        // Nothing ready: lineup order as before.
        assertEquals(nbaBlowout, SportsLiveNextGame.pick(ended, lineup, scores, emptySet(), emptySet(), knownGood = { false }))
    }

    @Test
    fun `a ready game goes first among followed teams' games, but never over a higher step`() {
        val followedBlowout = nbaBlowout.copy(followed = true)
        val followedQuiet = nbaQuiet.copy(followed = true)
        val lineup = listOf(ended, followedBlowout, followedQuiet, nbaClose)
        assertEquals(followedBlowout, SportsLiveNextGame.pick(ended, lineup, scores, emptySet(), emptySet()))
        assertEquals(followedQuiet, SportsLiveNextGame.pick(ended, lineup, scores, emptySet(), emptySet(), knownGood = { it == followedQuiet }))
        // A ready game of no followed team does not beat a followed one.
        assertEquals(followedBlowout, SportsLiveNextGame.pick(ended, lineup, scores, emptySet(), emptySet(), knownGood = { it == nbaClose }))
        // Nor does a ready quiet game beat a big one.
        assertEquals(nbaClose, SportsLiveNextGame.pick(ended, listOf(ended, nbaQuiet, nbaClose), scores, emptySet(), emptySet(), knownGood = { it == nbaQuiet }))
    }

    @Test
    fun `a ready big game goes first among the big games live anywhere`() {
        // A game no ESPN feed matches, in a sport none of these is in: the top big game live anywhere.
        val hockey = item(name = "Rangers at Bruins", id = "hockey", sport = "Hockey")
        val lineup = listOf(hockey, nflOvertime, nbaClose)
        val top = SportsLiveNextGame.pick(hockey, lineup, scores, emptySet(), emptySet())
        assertTrue(top == nflOvertime || top == nbaClose)
        // The other big game, once it is the ready one, goes first.
        val other = if (top == nflOvertime) nbaClose else nflOvertime
        assertEquals(other, SportsLiveNextGame.pick(hockey, lineup, scores, emptySet(), emptySet(), knownGood = { it == other }))
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
