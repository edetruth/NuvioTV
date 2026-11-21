package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoreTeam
import com.nuvio.tv.core.sportslive.scores.TeamFollows
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** live.23 TV report: "Liverpool vs Chelsea · Premier League" was ESPN's WSL game, not the men's. */
class SportsLiveWomenGamesTest {

    private fun card(name: String, sport: String?, genres: List<String> = emptyList()) = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = genres
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
        kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false, startMs = null, sport = sport,
        startHasTimeOfDay = false, teams = SportsLiveTeams.parse(name)
    )

    private val epl = card("Liverpool vs Chelsea", "Premier League")
    // Followed by name, no sport (a sport word must equal the card's to agree).
    private val mensFollow = FollowedTeam("Liverpool")
    private val womensFollow = FollowedTeam("Liverpool", "Soccer", espnId = "liv-w", league = "soccer/eng.w.1")

    @Test
    fun `a men's league card may also be on its country's women's league, a card that says its gender never`() {
        assertEquals(EspnLeague("soccer", "eng.w.1"), EspnLeagueMap.womenFallbackFor(epl))
        assertEquals(EspnLeague("soccer", "esp.w.1"), EspnLeagueMap.womenFallbackFor(card("Barcelona vs Real Madrid", "La Liga")))
        assertEquals(EspnLeagueMap.NWSL, EspnLeagueMap.womenFallbackFor(card("Portland vs Seattle", "MLS")))
        // No women's feed for the Bundesliga; a men's or women's card, or a bare sport word, gets none.
        assertNull(EspnLeagueMap.womenFallbackFor(card("Bayern vs Dortmund", "Bundesliga")))
        assertNull(EspnLeagueMap.womenFallbackFor(card("Liverpool vs Chelsea", "Premier League", listOf("Men's"))))
        assertNull(EspnLeagueMap.womenFallbackFor(card("Liverpool W vs Chelsea W", "Premier League")))
        assertNull(EspnLeagueMap.womenFallbackFor(card("Liverpool vs Chelsea", "Soccer")))
        assertTrue(EspnLeagueMap.isWomenFallback(EspnLeague("soccer", "eng.w.1")))
        assertFalse(EspnLeagueMap.isWomenFallback(EspnLeague("soccer", "eng.1")))
    }

    @Test
    fun `a followed men's club never makes its women's game my team, a followed women's team does`() {
        val games = SportsLiveWomenGames()
        assertTrue(SportsLiveItemBucketer.markFollowed(listOf(epl), listOf(mensFollow), games).single().followed)
        games.record(listOf(epl.streamKey))
        assertFalse(SportsLiveItemBucketer.markFollowed(listOf(epl), listOf(mensFollow), games).single().followed)
        assertTrue(SportsLiveItemBucketer.markFollowed(listOf(epl), listOf(womensFollow), games).single().followed)
        assertTrue(SportsLiveTeams.followsWomen(FollowedTeam("Arsenal Women", "Soccer")))
        assertTrue(SportsLiveTeams.followsWomen(FollowedTeam("Portland Thorns", "NWSL")))
        assertFalse(SportsLiveTeams.followsWomen(mensFollow))
    }

    @Test
    fun `on the WSL game a men's club followed by name stars no side`() {
        fun club(id: String, name: String) = ScoreTeam(
            id = id, abbreviation = null, displayName = name, shortDisplayName = name, name = name, location = name,
            logo = null, score = 1, linescores = emptyList()
        )
        val wsl = ScoreSnapshot(
            eventId = "wsl", league = EspnLeague("soccer", "eng.w.1"), startMs = 0L, state = ScoreState.IN, clockText = null,
            isFinal = false, home = club("liv-w", "Liverpool"), away = club("che-w", "Chelsea")
        )
        assertFalse(TeamFollows.followsCardSide(mensFollow, "Liverpool", epl, wsl))
        // The ESPN-followed women's team is that side by its id.
        assertTrue(TeamFollows.followsCardSide(womensFollow, "Liverpool", epl, wsl))
    }
}
