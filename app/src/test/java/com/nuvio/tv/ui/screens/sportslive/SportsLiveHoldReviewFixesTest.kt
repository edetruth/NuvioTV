package com.nuvio.tv.ui.screens.sportslive

import androidx.media3.common.PlaybackException
import com.nuvio.tv.ui.screens.player.PlayerVideoDecoderRecovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fable review of live.23: M3 (the held dual pane counts as a second decoder), L1 (a pause is healthy), L2 (one connection). */
class SportsLiveHoldReviewFixesTest {

    @Test
    fun `a decoder failure during the dual hand-over hold retries on the hardware decoder first`() {
        val beside = PlayerVideoDecoderRecovery.secondDecoderBeside(previews = false, dualHeld = true)
        assertTrue(beside)
        assertEquals(
            PlayerVideoDecoderRecovery.Action.YIELD_AND_RETRY_HARDWARE,
            PlayerVideoDecoderRecovery.decide(
                errorCode = PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                audioRendererFailed = false,
                onExoPlayer = true,
                secondDecoderActive = beside,
                hardwareRetried = false,
                softwareTried = false
            )
        )
        // The hand-over's overlap never marks the TV's decoder limit; a preview's does.
        assertFalse(PlayerVideoDecoderRecovery.notesDecoderLimit(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, previews = false))
        assertTrue(PlayerVideoDecoderRecovery.notesDecoderLimit(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, previews = true))
        assertFalse(PlayerVideoDecoderRecovery.secondDecoderBeside(previews = false, dualHeld = false))
    }

    @Test
    fun `OK on the paused game keeps it, only a buffering or failed stream starts again`() {
        // Paused by the viewer: healthy, so picking the game playing only closes the panel and keeps its place.
        assertTrue(panelStreamHealthy(isBuffering = false, hasError = false))
        assertFalse(panelStreamHealthy(isBuffering = true, hasError = false))
        assertFalse(panelStreamHealthy(isBuffering = false, hasError = true))
    }

    @Test
    fun `a connection-limit refusal beside the held pane releases it and retries the same link once`() {
        assertTrue(sportsLiveHeldPaneRetry(heldForThis = true, responseCode = 403, retried = false))
        assertTrue(sportsLiveHeldPaneRetry(heldForThis = true, responseCode = 429, retried = false))
        assertTrue(sportsLiveHeldPaneRetry(heldForThis = true, responseCode = 503, retried = false))
        // Once per link, only with a pane held for this player, only for a refusal.
        assertFalse(sportsLiveHeldPaneRetry(heldForThis = true, responseCode = 403, retried = true))
        assertFalse(sportsLiveHeldPaneRetry(heldForThis = false, responseCode = 403, retried = false))
        assertFalse(sportsLiveHeldPaneRetry(heldForThis = true, responseCode = 404, retried = false))
        assertFalse(sportsLiveHeldPaneRetry(heldForThis = true, responseCode = null, retried = false))
    }
}
