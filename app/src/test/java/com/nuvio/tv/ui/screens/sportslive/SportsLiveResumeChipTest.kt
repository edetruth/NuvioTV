package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.EndedGame
import com.nuvio.tv.core.sportslive.EndedSignal
import com.nuvio.tv.core.sportslive.GameStatus
import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.ui.screens.sportslive.dual.dualItem
import com.nuvio.tv.ui.screens.sportslive.dual.dualStream
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveMiniPlayerController
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveResumeOffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #148: Live now's Resume chip, from the offer and the screen's one status source. */
class SportsLiveResumeChipTest {

    private val game = dualItem("devils")
    private val stream = dualStream("devils")
    private val offer = SportsLiveResumeOffer(game, stream, atMs = 0L)
    private val final: ScoreSnapshot =
        EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA).first().copy(state = ScoreState.POST, isFinal = true)

    private fun chip(status: GameStatus, listed: SportsLiveItem? = game, nowMs: Long = 60_000L) =
        sportsLiveResumeChip(offer, listed, { status }, nowMs)

    @Test
    fun `a game still live shows Resume on the same link`() {
        assertEquals(SportsLiveResumeChip.Live(game, stream), chip(GameStatus.Live(null)))
    }

    @Test
    fun `the chip turns final when the game ends`() {
        val status = GameStatus.Final(final)
        assertEquals(SportsLiveResumeChip.Final(game, status, final), chip(status))
    }

    @Test
    fun `a Just ended game with ESPN's final turns final, even once off the list`() {
        val status = GameStatus.JustEnded(EndedGame(EndedSignal.FINAL, endedAtMs = 0L, event = final))
        assertEquals(SportsLiveResumeChip.Final(game, status, final), chip(status, listed = null))
    }

    @Test
    fun `an estimated end keeps Resume, its stream may still play`() {
        val status = GameStatus.JustEnded(EndedGame(EndedSignal.ESTIMATE, endedAtMs = 0L))
        assertEquals(SportsLiveResumeChip.Live(game, stream), chip(status))
    }

    @Test
    fun `a game gone from Live now without a final shows no chip`() {
        assertNull(chip(GameStatus.Live(null), listed = null))
    }

    @Test
    fun `the chip expires after 30 min`() {
        assertNull(chip(GameStatus.Live(null), nowMs = SportsLiveMiniPlayerController.RESUME_TTL_MS))
        assertNull(chip(GameStatus.Final(final), nowMs = SportsLiveMiniPlayerController.RESUME_TTL_MS))
    }

    @Test
    fun `no offer shows no chip`() {
        assertNull(sportsLiveResumeChip(null, game, { GameStatus.Live(null) }, 0L))
    }

    @Test
    fun `the listed card wins over the offered snapshot`() {
        val fresher = game.copy(isLive = true)
        assertEquals(SportsLiveResumeChip.Live(fresher, stream), chip(GameStatus.Live(null), listed = fresher))
    }

    @Test
    fun `OK on a live chip rejoins the same link at the live edge`() {
        assertEquals(
            SportsLiveResumeOk.RejoinLiveEdge(game, stream),
            sportsLiveResumeOk(SportsLiveResumeChip.Live(game, stream))
        )
    }

    @Test
    fun `OK on a final chip opens the Game page`() {
        assertEquals(
            SportsLiveResumeOk.OpenGame(game, final),
            sportsLiveResumeOk(SportsLiveResumeChip.Final(game, GameStatus.Final(final), final))
        )
    }

    @Test
    fun `Live now and Channels show the chip, the other tabs do not`() {
        // live.19 F15: Back from a channel paused on Channels showed nothing there.
        assertTrue(sportsLiveResumeShownOn(SportsLiveTab.LIVE_NOW))
        assertTrue(sportsLiveResumeShownOn(SportsLiveTab.CHANNELS))
        listOf(SportsLiveTab.UPCOMING, SportsLiveTab.REPLAYS, SportsLiveTab.RESULTS).forEach { assertFalse(sportsLiveResumeShownOn(it)) }
    }

    @Test
    fun `the paused card is found on Channels too, Live now's card first`() {
        val channel = game.copy(isChannel = true)
        assertEquals(channel, sportsLiveResumeListed(offer, mapOf(SportsLiveTab.CHANNELS to listOf(dualItem("other"), channel))))
        val live = game.copy(isLive = true)
        assertEquals(live, sportsLiveResumeListed(offer, mapOf(SportsLiveTab.LIVE_NOW to listOf(live), SportsLiveTab.CHANNELS to listOf(channel))))
        // Other tabs never list it, and no offer finds nothing.
        assertNull(sportsLiveResumeListed(offer, mapOf(SportsLiveTab.REPLAYS to listOf(game))))
        assertNull(sportsLiveResumeListed(null, mapOf(SportsLiveTab.LIVE_NOW to listOf(game))))
    }
}
