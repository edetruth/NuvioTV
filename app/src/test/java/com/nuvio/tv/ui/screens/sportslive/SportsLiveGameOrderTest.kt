package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** N10: the Game page names the teams in the order of the card it was opened from. */
class SportsLiveGameOrderTest {

    private val pacersEvent = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA)
        .first { it.eventId == "401909985" } // IND (away) @ PHI (home)

    private fun item(name: String) = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
        kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false, startMs = null, sport = "Basketball",
        teams = SportsLiveTeams.parse(name)
    )

    @Test
    fun `a card naming the home team first opens the page home first`() {
        assertTrue(sportsLiveGameHomeFirst(item("76ers vs Pacers"), pacersEvent))
        assertFalse(sportsLiveGameHomeFirst(item("Pacers @ 76ers"), pacersEvent))
        // A card with no two sides: ESPN's away–home.
        assertFalse(sportsLiveGameHomeFirst(item("NBA TV"), pacersEvent))
    }

    @Test
    fun `header sides and Follow buttons follow that order`() {
        assertEquals("away" to "home", inCardOrder("away", "home", homeFirst = false))
        assertEquals("home" to "away", inCardOrder("away", "home", homeFirst = true))
        assertEquals(
            listOf(GameHeaderButton.FOLLOW_HOME, GameHeaderButton.FOLLOW_AWAY),
            gameHeaderRows(showsScore = true, hasReplay = false, homeFirst = true).last()
        )
        assertEquals(
            listOf(GameHeaderButton.FOLLOW_AWAY, GameHeaderButton.FOLLOW_HOME),
            gameHeaderRows(showsScore = true, hasReplay = false).last()
        )
        // First focus: the card's first team's Follow when nothing comes before it.
        assertEquals(GameHeaderButton.FOLLOW_HOME, gameHeaderFirstFocus(gameHeaderRows(showsScore = true, hasReplay = false, homeFirst = true)))
    }
}
