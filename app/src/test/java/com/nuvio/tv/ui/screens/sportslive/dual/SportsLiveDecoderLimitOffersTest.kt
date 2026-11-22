package com.nuvio.tv.ui.screens.sportslive.dual

import android.view.KeyEvent
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.ui.screens.home.homeCardMenuEntries
import com.nuvio.tv.ui.screens.sportslive.SportsLiveUiState
import com.nuvio.tv.ui.screens.sportslive.offersWatchWith
import com.nuvio.tv.ui.screens.sportslive.preview.SportsLiveDecoderLimit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Context audit G11: a TV that can't decode two streams. Once the device limit is seen, no "Watch with another game" or
 * "Watch both" leads back into it, and the pane that hit it says what OK and Back do and never zaps into the same failure.
 */
class SportsLiveDecoderLimitOffersTest {

    private val a = dualItem("a")
    private val b = dualItem("b")
    private val channel = dualItem("ch").copy(isChannel = true, kind = SportsLiveKind.CHANNELS)

    private val playing = DualPane(a, dualStream("a"), PaneStatus.Playing)
    private val limit = DualPane(b, dualStream("b"), PaneStatus.DeviceLimit)

    @After
    fun tearDown() {
        SportsLiveDecoderLimit.reset()
    }

    @Test
    fun `once the device limit is seen no Watch with another game nor Watch both is offered`() {
        val liveNow = SportsLiveUiState(
            tabs = mapOf(SportsLiveTab.LIVE_NOW to listOf(a, b), SportsLiveTab.CHANNELS to listOf(channel)),
            selectedTab = SportsLiveTab.LIVE_NOW,
            visibleItems = listOf(a, b)
        )
        val channels = liveNow.copy(selectedTab = SportsLiveTab.CHANNELS, visibleItems = listOf(channel))
        assertTrue(liveNow.offersWatchWith(a, null))
        assertTrue(channels.offersWatchWith(channel, null))
        assertTrue(SportsLiveTab.LIVE_NOW.offersDual())
        assertTrue(homeCardMenuEntries(a, null, followSides = 0, partners = 1).watchWith)

        SportsLiveDecoderLimit.note()

        assertFalse(sportsLiveDualAvailable())
        assertFalse(liveNow.offersWatchWith(a, null))
        assertFalse(channels.offersWatchWith(channel, null))
        // The in-player panel, the guide's row menu and the alert's Watch both all start from offersDual.
        assertFalse(SportsLiveTab.LIVE_NOW.offersDual())
        assertFalse(SportsLiveTab.CHANNELS.offersDual())
        assertFalse(homeCardMenuEntries(a, null, followSides = 0, partners = 1).watchWith)
    }

    @Test
    fun `the device limit card owns OK and Back wherever it sits, and OK plays its game full screen`() {
        // Big and small: the ring stays on the big game; the small pane hit the limit.
        val bigSmall = DualState(playing, limit, layout = DualLayout.BIG_SMALL)
        assertTrue(dualPaneOwnsKeys(limit))
        assertEquals(Side.RIGHT, dualKeySide(bigSmall))
        // Full screen, never Make big: the small game plays alone.
        assertEquals(DualOk.FULL_SCREEN, dualOkRoute(bigSmall))
        // Side by side the ring decides, as for the error card.
        val split = DualState(playing, limit)
        assertEquals(Side.LEFT, dualKeySide(split))
        assertEquals(Side.RIGHT, dualKeySide(split.copy(highlighted = Side.RIGHT)))
        assertEquals(DualOk.FULL_SCREEN, dualOkRoute(split.copy(highlighted = Side.RIGHT)))
    }

    @Test
    fun `a pane on the device limit card never zaps another game into the same failure`() {
        for (key in listOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS
        )) {
            assertNull(dualZapKey(limit, key))
            assertNull(dualKeyRoute(DualState(playing, limit, highlighted = Side.RIGHT), key))
        }
        // The other pane, playing, still zaps.
        assertEquals(true, dualZapKey(playing, KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(DualKeyRoute.Zap(forward = true), dualKeyRoute(DualState(playing, limit), KeyEvent.KEYCODE_DPAD_DOWN))
    }
}
