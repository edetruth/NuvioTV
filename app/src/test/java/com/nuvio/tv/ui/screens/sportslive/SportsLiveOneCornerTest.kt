package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.ui.unit.dp
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.alerts.AlertSpot
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** live.20: one corner for the toasts, the score cards and the kickoff prompt; ▲ Both on a kickoff prompt. */
class SportsLiveOneCornerTest {

    private val playerOwner = Any()
    private val dualOwner = Any()
    private val startingCard = Any()
    private val scoreCard = Any()

    @After
    fun tearDown() {
        SportsLiveToastPlace.remove(playerOwner)
        SportsLiveToastPlace.remove(dualOwner)
        SportsLiveBottomLeft.release(startingCard)
        SportsLiveBottomLeft.release(scoreCard)
    }

    // Item 2: the toasts lift above the player's seek bar and controls

    @Test
    fun `over the player the corner lifts above the controls while they show, and comes back down`() {
        val down = sportsLiveVideoCorner(controlsUp = false)
        assertEquals(SportsLiveTokens.Player.CornerStart, down.start)
        assertEquals(SportsLiveTokens.Player.CornerBottom, down.bottom)
        val up = sportsLiveVideoCorner(controlsUp = true)
        assertEquals(down.start, up.start)
        assertEquals(SportsLiveTokens.Player.ControlsClearance, up.bottom)
        assertTrue(up.bottom > down.bottom)
        // The toast takes that corner as its base, so it sits above the controls.
        assertEquals(up.bottom, sportsLiveToastBottom(up.bottom, height = 540.dp, claimedTop = null, gap = 6.dp))
    }

    @Test
    fun `the newest video screen's corner wins, a corner change keeps its place, and the screen's corner comes back`() {
        assertNull(SportsLiveToastPlace.corner.value)
        SportsLiveToastPlace.set(playerOwner, sportsLiveVideoCorner(false))
        val dualCorner = SportsLiveCorner(28.dp, 170.dp)
        SportsLiveToastPlace.set(dualOwner, dualCorner)
        assertEquals(dualCorner, SportsLiveToastPlace.corner.value)
        // The player under it shows its controls: dual screen, composed last, keeps the corner.
        SportsLiveToastPlace.set(playerOwner, sportsLiveVideoCorner(true))
        assertEquals(dualCorner, SportsLiveToastPlace.corner.value)
        SportsLiveToastPlace.remove(dualOwner)
        assertEquals(sportsLiveVideoCorner(true), SportsLiveToastPlace.corner.value)
        SportsLiveToastPlace.remove(playerOwner)
        assertNull(SportsLiveToastPlace.corner.value)
    }

    // Item 1: score cards in the toasts' corner, stacked between the player's card and the toast

    @Test
    fun `a score card sits above the player's card, and the toast above the score card`() {
        val corner = sportsLiveVideoCorner(false)
        // The player's starting card holds the corner, its top at 440 dp.
        SportsLiveBottomLeft.claim(startingCard, 440)
        assertEquals(440, SportsLiveBottomLeft.baseTopPx.value)
        val place = alertCardPlace(AlertSpot.SCREEN, 960.dp, 540.dp, corner, claimedTop = 440.dp)
        assertEquals(corner.start, place.start)
        assertEquals(540.dp - 440.dp + SportsLiveTokens.Size.RowGap, place.bottom)
        assertTrue(place.holdsCorner)
        // The score card (stacked) claims the corner above it: the toast goes above the card, the card stays put.
        SportsLiveBottomLeft.claim(scoreCard, 360, stacked = true)
        assertEquals(360, SportsLiveBottomLeft.topPx.value)
        assertEquals(440, SportsLiveBottomLeft.baseTopPx.value)
        assertEquals(540.dp - 360.dp + 6.dp, sportsLiveToastBottom(corner.bottom, 540.dp, claimedTop = 360.dp, gap = 6.dp))
        // The starting card goes: the score card comes down to the corner, the toast still above it.
        SportsLiveBottomLeft.release(startingCard)
        assertNull(SportsLiveBottomLeft.baseTopPx.value)
        assertEquals(360, SportsLiveBottomLeft.topPx.value)
        assertEquals(corner.bottom, alertCardPlace(AlertSpot.SCREEN, 960.dp, 540.dp, corner, claimedTop = null).bottom)
        SportsLiveBottomLeft.release(scoreCard)
        assertNull(SportsLiveBottomLeft.topPx.value)
    }

    @Test
    fun `with nothing in the corner a score card takes the toasts' own corner, on every screen`() {
        val place = alertCardPlace(AlertSpot.SCREEN, 960.dp, 540.dp, SportsLiveCorner.SCREEN)
        assertEquals(SportsLiveTokens.Toast.Start, place.start)
        assertEquals(SportsLiveTokens.Toast.Bottom, place.bottom)
    }

    // Item 7: ▲ Both on a kickoff prompt pairs the fresh live card, as Watch does

    @Test
    fun `Both on a kickoff prompt puts the fresh live card beside the game, not the prompt's own card`() = runTest {
        val promptCard = game("Boston Celtics at New York Knicks", live = false)
        val fresh = promptCard.copy(isLive = true, addonName = "b")
        val paired = mutableListOf<SportsLiveItem>()
        val watched = mutableListOf<SportsLiveItem>()
        sportsLiveKickoffBoth(promptCard, { fresh }, { paired += it; true }) { watched += it }
        assertEquals(listOf(fresh), paired)
        assertTrue(watched.isEmpty())
    }

    @Test
    fun `when dual screen cannot open, Both on a kickoff prompt plays the fresh card as Watch`() = runTest {
        val promptCard = game("Boston Celtics at New York Knicks", live = false)
        val fresh = promptCard.copy(isLive = true)
        val watched = mutableListOf<SportsLiveItem>()
        sportsLiveKickoffBoth(promptCard, { fresh }, { false }) { watched += it }
        assertEquals(listOf(fresh), watched)
    }

    private fun game(name: String, live: Boolean) = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
        kind = SportsLiveKind.EVENTS, isLive = live, isChannel = false,
        startMs = 1_763_240_000_000L, sport = "Basketball", startHasTimeOfDay = true,
        teams = SportsLiveTeams.parse(name)
    )
}
