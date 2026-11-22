package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.ui.navigation.Screen
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Context audit G30: sports toasts and the held Play/Pause report shortcut stay out of a movie or show. */
class SportsLiveOverMovieTest {

    @After
    fun tearDown() {
        SportsLiveOverMovie.onScreen(null, null)
        SportsLiveExtraSources.reset()
    }

    @Test
    fun `only the full player playing something that is not Sports and Live is a movie`() {
        val isSports: (String?) -> Boolean = { it == "game" }
        assertTrue(sportsLiveMoviePlaying(Screen.Player.route, "tt0111161", isSports))
        assertTrue(sportsLiveMoviePlaying(Screen.Player.route, null, isSports))
        assertFalse(sportsLiveMoviePlaying(Screen.Player.route, "game", isSports))
        // Browsing, dual screen and the section are no movie.
        assertFalse(sportsLiveMoviePlaying(Screen.Home.route, null, isSports))
        assertFalse(sportsLiveMoviePlaying(Screen.SportsLiveDual.route, null, isSports))
        assertFalse(sportsLiveMoviePlaying(null, null, isSports))
    }

    @Test
    fun `the screen on top says whether a movie plays, a game marked before the player opens is none`() {
        SportsLiveExtraSources.markSportsPlayback("game")
        SportsLiveOverMovie.onScreen(Screen.Player.route, "game")
        assertFalse(SportsLiveOverMovie.now)
        SportsLiveOverMovie.onScreen(Screen.Player.route, "tt0111161")
        assertTrue(SportsLiveOverMovie.now)
        SportsLiveOverMovie.onScreen(Screen.Home.route, null)
        assertFalse(SportsLiveOverMovie.now)
    }
}
