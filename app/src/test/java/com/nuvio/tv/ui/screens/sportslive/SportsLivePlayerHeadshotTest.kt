package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.scores.PlayerBio
import com.nuvio.tv.core.sportslive.scores.PlayerCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #130: the Player card draws its headshot only from a real photo, never an initials circle. */
class SportsLivePlayerHeadshotTest {

    private fun card(headshot: String?) = PlayerCard(
        bio = PlayerBio(
            name = "Joel Embiid", team = "Philadelphia 76ers", teamLogo = null, position = "Center",
            jersey = "21", headshot = headshot, details = emptyList()
        ),
        seasonTitle = null,
        season = emptyList(),
        lastGames = emptyList()
    )

    @Test
    fun `a player with a photo shows it`() {
        val url = "https://a.espncdn.com/i/headshots/nba/players/full/3059318.png"
        assertEquals(url, playerHeadshot(card(url)))
    }

    @Test
    fun `a player without a photo draws no circle`() {
        assertNull(playerHeadshot(card(null)))
        assertNull(playerHeadshot(card("  ")))
    }

    @Test
    fun `a card not loaded yet draws no circle`() {
        assertNull(playerHeadshot(null))
    }
}
