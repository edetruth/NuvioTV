package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.CatchUpItem
import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.GameArt
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.ui.screens.home.SportsLiveHomeRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Catch up row (#58) is spoiler-safe by design: no score, winner or final note anywhere it shows. */
class SportsLiveCatchUpTest {

    private val pacersGame = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA).first { it.eventId == "401909985" }

    // An addon's replay whose title and description give the score away.
    private val replay = SportsLiveItem(
        meta = MetaPreview(
            id = "r1", type = ContentType.UNKNOWN, rawType = "sport", name = "Indiana Pacers 117-109 Philadelphia 76ers",
            poster = "https://example.invalid/p.jpg", posterShape = PosterShape.LANDSCAPE, background = "https://example.invalid/b.jpg",
            logo = null, description = "Final: Pacers win 117-109", releaseInfo = "FT 117-109", imdbRating = null, genres = listOf("NBA")
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Replays",
        kind = SportsLiveKind.REPLAY, isLive = false, isChannel = false, startMs = pacersGame.startMs, sport = "Basketball",
        startHasTimeOfDay = true, teams = SportsLiveTeams.parse("Indiana Pacers vs Philadelphia 76ers")
    )
    private val item = CatchUpItem(pacersGame, replay)
    private val scoreTexts = listOfNotNull(pacersGame.homeScore, pacersGame.awayScore).map { it.toString() } + listOf("117", "109", "FT", "win")

    @Test
    fun `a Home card shows ESPN's names and Replay, never a score or the replay's title`() {
        val row = SportsLiveCatchUpHomeRow.build(
            items = listOf(item),
            title = "Catch up · your teams",
            names = { "${it.event.away.catchUpName} at ${it.event.home.catchUpName}" },
            replayLabel = "Replay"
        )
        val card = row.items.single()
        assertEquals("Indiana Pacers at Philadelphia 76ers", card.name)
        assertEquals("Replay", card.releaseInfo)
        assertNull(card.description)
        assertNull(card.runtime)
        // No image from the replay at all: Home draws GameArt in its place.
        assertNull(card.poster)
        assertNull(card.background)
        assertNull(card.landscapePoster)
        assertNull(card.logo)
        val shown = listOfNotNull(card.name, card.releaseInfo, card.description) + card.genres
        assertTrue(shown.none { text -> scoreTexts.any { text.contains(it) } })
        // Its own row (no score labels), opening the section from See all, read back to the item.
        assertTrue(SportsLiveCatchUpHomeRow.isCatchUpRow(row.addonBaseUrl))
        assertFalse(SportsLiveHomeRow.isLiveNowRow(row.addonBaseUrl))
        assertEquals(SportsLiveHomeRow.ADDON_ID, row.addonId)
        assertEquals(item.key, SportsLiveCatchUpHomeRow.eventIdOf(card.id))
    }

    @Test
    fun `the drawn art is ESPN's teams and league, away first, with no time`() {
        val art = GameArt.ofEvent(item.event)
        assertEquals("Indiana Pacers", art.first.name)
        assertEquals("Philadelphia 76ers", art.second.name)
        assertEquals(pacersGame.away.logo, art.first.logo)
        assertEquals(pacersGame.leagueName, art.league)
        assertNull(art.startMs)
    }

    @Test
    fun `the Game page from the row keeps the score behind Reveal in every Scores mode`() {
        val request = catchUpGameRequest(item)
        assertTrue(request.spoilerSafe)
        assertFalse(request.revealed)
        assertEquals(replay, request.replay)
        for (mode in ScoresMode.entries) {
            val page = SportsLiveGameUiState(event = item.event, mode = mode, revealed = false, spoilerSafe = true)
            assertFalse("$mode", page.showsScore)
            assertTrue("$mode: no sections before Reveal", page.sections.isEmpty())
            assertTrue("$mode: Reveal shows it", page.copy(revealed = true).showsScore)
        }
        // Elsewhere Show still shows the score at once.
        assertTrue(SportsLiveGameUiState(event = item.event, mode = ScoresMode.SHOW).showsScore)
    }
}
