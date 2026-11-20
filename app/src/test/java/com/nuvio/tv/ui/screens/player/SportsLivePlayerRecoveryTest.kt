package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.ui.screens.sportslive.SportsLivePlayerErrorAction.CHOOSE_STREAM
import com.nuvio.tv.ui.screens.sportslive.SportsLivePlayerErrorAction.NEXT_LIVE_GAME
import com.nuvio.tv.ui.screens.sportslive.SportsLivePlayerErrorAction.RETRY
import com.nuvio.tv.ui.screens.sportslive.SportsLivePlayerErrorAction.TRY_NEXT_LINK
import com.nuvio.tv.ui.screens.sportslive.sportsLivePlayerErrorActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** live.19 QA F9 and the VC-1 gap: the full player's Sports & Live recovery card and where errors go. */
class SportsLivePlayerRecoveryTest {

    @Test
    fun `the recovery card offers Try next link first, then Choose stream and Next live game`() {
        assertEquals(listOf(TRY_NEXT_LINK, CHOOSE_STREAM, NEXT_LIVE_GAME), sportsLivePlayerErrorActions(hasOtherLinks = true, canNextLiveGame = true))
        // No zap session: no Next live game.
        assertEquals(listOf(TRY_NEXT_LINK, CHOOSE_STREAM), sportsLivePlayerErrorActions(hasOtherLinks = true, canNextLiveGame = false))
        // The game's only link: Retry it instead.
        assertEquals(listOf(RETRY, CHOOSE_STREAM, NEXT_LIVE_GAME), sportsLivePlayerErrorActions(hasOtherLinks = false, canNextLiveGame = true))
    }

    @Test
    fun `after the tries gave up the card names how many links were tried`() {
        assertNull(sportsLiveGaveUpLinks(0))
        // One link: its own error says it best ("That link was slow to start").
        assertNull(sportsLiveGaveUpLinks(1))
        assertEquals(9, sportsLiveGaveUpLinks(9))
    }

    @Test
    fun `a VC-1 failure of a sports link goes to the sports recovery first`() {
        val root = listOf("src/main/java", "app/src/main/java").map(::File).first { it.exists() }
        val source = File(root, "com/nuvio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt").readText()
        val body = source.substringAfter("fun PlayerRuntimeController.handleVc1PlaybackFailure(")
            .substringBefore("internal fun PlayerRuntimeController.")
        val sports = body.indexOf("trySportsRecoveryAfterError(")
        val upstream = body.indexOf("tryFallbackToNextSourceAfterError(")
        assertTrue("the sports recovery is not tried", sports >= 0)
        assertTrue("the sports recovery comes after the upstream fallback", sports < upstream)
    }
}
