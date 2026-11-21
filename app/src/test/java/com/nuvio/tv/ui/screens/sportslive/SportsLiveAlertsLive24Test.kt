package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.ui.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** live.24 notifications redesign: where Watch from a card goes, and which screens hold back a close finish. */
class SportsLiveAlertsLive24Test {

    private val sports = setOf("sports-game")
    private fun isSports(id: String?) = id in sports

    @Test
    fun `over a movie Watch closes the movie's player, so its place is saved, and the game plays`() {
        // Closing the player runs its release, which saves the watch progress (the player's own switch-or-exit save),
        // as leaving it with Back does; the game never plays on top of the paused film.
        assertEquals(AlertPlayRoute.REPLACE_MOVIE, alertPlayRoute(Screen.Player.route, "tt-movie", ::isSports))
        assertEquals(AlertPlayRoute.REPLACE_MOVIE, alertPlayRoute(Screen.Player.route, null, ::isSports))
    }

    @Test
    fun `over a sports stream Watch zaps, and while browsing it opens the game`() {
        assertEquals(AlertPlayRoute.ZAP, alertPlayRoute(Screen.Player.route, "sports-game", ::isSports))
        assertEquals(AlertPlayRoute.OPEN, alertPlayRoute("home", null, ::isSports))
        assertEquals(AlertPlayRoute.OPEN, alertPlayRoute(Screen.SportsLive.route, null, ::isSports))
        assertEquals(AlertPlayRoute.OPEN, alertPlayRoute(null, null, ::isSports))
    }

    @Test
    fun `Sports and Live screens hold back a close finish, dual screen and the rest do not`() {
        assertTrue(isSportsLiveScreen(Screen.SportsLive.route))
        assertTrue(isSportsLiveScreen(Screen.SportsLiveGame.route))
        assertFalse(isSportsLiveScreen(Screen.SportsLiveDual.route))
        assertFalse(isSportsLiveScreen(Screen.Player.route))
        assertFalse(isSportsLiveScreen("home"))
        assertFalse(isSportsLiveScreen(null))
    }
}
