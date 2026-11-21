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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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

    // ---- TV report #10: the WSL game as three rows ----

    private fun copy(addon: String, name: String, sport: String, genres: List<String> = emptyList()) = card(name, sport, genres).copy(
        addonId = addon, addonName = addon, catalogKey = "$addon:live", meta = card(name, sport, genres).meta.copy(id = "$addon-$name")
    )

    private val eplCopy = copy("a", "Liverpool FC vs Chelsea FC", "Premier League")
    private val plainCopy = copy("b", "Liverpool vs Chelsea", "Premier League")
    private val wslCopy = copy("c", "Liverpool vs Chelsea", "Women's Super League", listOf("Soccer"))

    // The Premier League list the app keeps: both names resolve to the men's clubs, so the WSL copy's gender kept it apart.
    private val eng1 = com.nuvio.tv.core.sportslive.scores.TeamDirectory().apply {
        put(
            EspnLeague("soccer", "eng.1"),
            listOf(
                com.nuvio.tv.core.sportslive.scores.DirectoryTeam("364", "Liverpool", "Liverpool", "LIV", "Liverpool", "Liverpool", "https://example.invalid/364.png"),
                com.nuvio.tv.core.sportslive.scores.DirectoryTeam("363", "Chelsea", "Chelsea", "CHE", "Chelsea", "Chelsea", "https://example.invalid/363.png")
            ),
            fetchedAt = 1L
        )
    }

    private val wslGame = ScoreSnapshot(
        eventId = "wsl-liv-che", league = EspnLeague("soccer", "eng.w.1"), startMs = 0L, state = ScoreState.IN, clockText = "34'",
        isFinal = false,
        home = ScoreTeam(id = "liv-w", abbreviation = "LIV", displayName = "Liverpool", shortDisplayName = "Liverpool", name = "Liverpool",
            location = "Liverpool", logo = null, score = 1, linescores = emptyList()),
        away = ScoreTeam(id = "che-w", abbreviation = "CHE", displayName = "Chelsea", shortDisplayName = "Chelsea", name = "Chelsea",
            location = "Chelsea", logo = null, score = 0, linescores = emptyList())
    )

    @Test
    fun `copies matched to one ESPN game are one row, shown as the copy naming its league, and star no men's club`() {
        val order = listOf("a", "b", "c")
        val copies = listOf(eplCopy, plainCopy, wslCopy)
        // Before any match: the two Premier League copies merge, the WSL copy stays apart (gender rule).
        val before = SportsLiveItemBucketer.merge(copies, order, 0L, eng1, SportsLiveMatchedEvents())
        assertEquals(2, before.size)
        // Each copy matched ESPN's WSL game (the two by the women's fallback): one row.
        val matched = SportsLiveMatchedEvents().apply { record(copies.map { it.streamKey }, wslGame) }
        val row = SportsLiveItemBucketer.merge(copies, order, 0L, eng1, matched).single()
        assertEquals(3, row.sourceCount)
        assertEquals("Women's Super League", row.sport)
        assertEquals("Liverpool vs Chelsea", row.meta.name)
        assertEquals(SportsLiveSportKind.SOCCER, SportsLiveSportKind.of(row.sport))
        // Its WSL copy says women's: a men's club followed by name never stars it (strip included: it reads followed).
        assertFalse(SportsLiveItemBucketer.markFollowed(listOf(row), listOf(mensFollow), SportsLiveWomenGames()).single().followed)
        assertTrue(SportsLiveItemBucketer.markFollowed(listOf(row), listOf(womensFollow), SportsLiveWomenGames()).single().followed)
        // Copies of two different ESPN games never merge by it.
        val other = SportsLiveMatchedEvents().apply {
            record(listOf(eplCopy.streamKey), wslGame)
            record(listOf(wslCopy.streamKey), wslGame.copy(eventId = "another"))
        }
        assertEquals(2, SportsLiveItemBucketer.merge(listOf(eplCopy, wslCopy), order, 0L, eng1, other).size)
    }

    @Test
    fun `a burst of matches that merge copies or mark women's games rebuilds the lists once`() = kotlinx.coroutines.test.runTest {
        val events = SportsLiveMatchedEvents()
        val women = SportsLiveWomenGames()
        val signals = mutableListOf<Long>()
        backgroundScope.launch { SportsLiveSharedBuckets.matchChanges(events, women).collect { signals += it } }
        runCurrent()
        // One copy of a game: nothing merges, no rebuild.
        events.record(listOf("a|1"), wslGame)
        advanceTimeBy(2_000L); runCurrent()
        assertTrue(signals.isEmpty())
        // A poll matches two more copies and marks the game women's: one rebuild, a second after the quiet.
        events.record(listOf("b|1"), wslGame)
        events.record(listOf("c|1"), wslGame)
        women.record(listOf("a|1", "b|1", "c|1"))
        advanceTimeBy(500L); runCurrent()
        events.record(listOf("b|1"), wslGame)
        assertTrue(signals.isEmpty())
        advanceTimeBy(SportsLiveSharedBuckets.MATCH_DEBOUNCE_MS + 1); runCurrent()
        assertEquals(1, signals.size)
        // The next polls match the same games again: no rebuild.
        events.record(listOf("a|1", "b|1", "c|1"), wslGame)
        women.record(listOf("a|1"))
        advanceTimeBy(5_000L); runCurrent()
        assertEquals(1, signals.size)
    }
}
