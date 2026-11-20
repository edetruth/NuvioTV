package com.nuvio.tv.ui.screens.sportslive.dual

import android.view.KeyEvent
import com.nuvio.tv.core.sportslive.GameEndReason
import com.nuvio.tv.ui.screens.sportslive.SportsLiveEndCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Research 8: dual screen works with the D-pad and OK alone (Google TV TV-DM: no Menu button needed; Google TV
 * remotes have no channel keys either). MENU and CH+/CH- stay as shortcuts.
 */
class DualKeyModelTest {

    private val a = dualItem("a")
    private val b = dualItem("b")
    private val c = dualItem("c")

    private val playing = DualPane(a, dualStream("a"), PaneStatus.Playing)
    private val otherPlaying = DualPane(b, dualStream("b"), PaneStatus.Playing)
    private val split = DualState(playing, otherPlaying)
    private val bigSmall = split.copy(layout = DualLayout.BIG_SMALL)

    private fun ended(next: Boolean) = DualPane(
        a, dualStream("a"), PaneStatus.Ended,
        end = SportsLiveEndCard(
            item = a, reason = GameEndReason.STREAM_ENDED, eventId = null, snapshot = null, mode = null,
            next = if (next) c else null, upcoming = null, boxScore = false, streamStopped = true
        )
    )

    private val starting = DualPane(a, dualStream("a"), PaneStatus.Loading)

    // Left and Right: the ring, and the sound with it

    @Test
    fun `Left and Right move the ring, and the sound follows it`() {
        assertEquals(DualKeyRoute.Highlight(Side.RIGHT), dualKeyRoute(split, KeyEvent.KEYCODE_DPAD_RIGHT))
        val right = split.reduce(DualAction.Highlight(Side.RIGHT))
        assertEquals(Side.RIGHT, right.audibleSide)
        assertEquals(DualKeyRoute.Highlight(Side.LEFT), dualKeyRoute(right, KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(Side.LEFT, right.reduce(DualAction.Highlight(Side.LEFT)).audibleSide)
    }

    @Test
    fun `Left on the left pane picks the other game`() {
        assertEquals(DualKeyRoute.PickOtherGame, dualKeyRoute(split, KeyEvent.KEYCODE_DPAD_LEFT))
    }

    @Test
    fun `Left and Right move the ring on a pane card too`() {
        val state = split.copy(left = starting)
        assertEquals(DualKeyRoute.Highlight(Side.RIGHT), dualKeyRoute(state, KeyEvent.KEYCODE_DPAD_RIGHT))
    }

    // Up and Down: the game, with no channel keys

    @Test
    fun `Up and Down change the ringed pane's game`() {
        assertEquals(DualKeyRoute.Zap(forward = true), dualKeyRoute(split, KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(DualKeyRoute.Zap(forward = false), dualKeyRoute(split, KeyEvent.KEYCODE_DPAD_UP))
    }

    @Test
    fun `CH+ and CH- and next and previous are shortcuts for the same`() {
        assertEquals(DualKeyRoute.Zap(forward = true), dualKeyRoute(split, KeyEvent.KEYCODE_CHANNEL_DOWN))
        assertEquals(DualKeyRoute.Zap(forward = false), dualKeyRoute(split, KeyEvent.KEYCODE_CHANNEL_UP))
        assertEquals(DualKeyRoute.Zap(forward = true), dualKeyRoute(split, KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(DualKeyRoute.Zap(forward = false), dualKeyRoute(split, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
    }

    @Test
    fun `a starting card's buttons take Up and Down first, then the game changes past them`() {
        val state = split.copy(left = starting)
        assertEquals(2, dualCardButtons(starting))
        // Try next link selected: Down moves to Sources, Down again changes the game.
        assertEquals(DualKeyRoute.CardButton(1), dualKeyRoute(state, KeyEvent.KEYCODE_DPAD_DOWN, cardSelected = 0))
        assertEquals(DualKeyRoute.Zap(forward = true), dualKeyRoute(state, KeyEvent.KEYCODE_DPAD_DOWN, cardSelected = 1))
        // Up from Sources back to Try next link, then the previous game.
        assertEquals(DualKeyRoute.CardButton(0), dualKeyRoute(state, KeyEvent.KEYCODE_DPAD_UP, cardSelected = 1))
        assertEquals(DualKeyRoute.Zap(forward = false), dualKeyRoute(state, KeyEvent.KEYCODE_DPAD_UP, cardSelected = 0))
        // CH+/CH- change the game at once, as before.
        assertEquals(DualKeyRoute.Zap(forward = true), dualKeyRoute(state, KeyEvent.KEYCODE_CHANNEL_DOWN, cardSelected = 0))
    }

    @Test
    fun `an end card's buttons take Up and Down first, then the game changes past them`() {
        val state = split.copy(left = ended(next = true))
        assertEquals(2, dualCardButtons(ended(next = true)))
        assertEquals(DualKeyRoute.CardButton(1), dualKeyRoute(state, KeyEvent.KEYCODE_DPAD_DOWN, cardSelected = 0))
        assertEquals(DualKeyRoute.Zap(forward = true), dualKeyRoute(state, KeyEvent.KEYCODE_DPAD_DOWN, cardSelected = 1))
        assertEquals(DualKeyRoute.Zap(forward = false), dualKeyRoute(state, KeyEvent.KEYCODE_DPAD_UP, cardSelected = 0))
        // Nothing next: Stay alone, and both Up and Down change the game.
        val alone = split.copy(left = ended(next = false))
        assertEquals(1, dualCardButtons(ended(next = false)))
        assertEquals(DualKeyRoute.Zap(forward = true), dualKeyRoute(alone, KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(DualKeyRoute.Zap(forward = false), dualKeyRoute(alone, KeyEvent.KEYCODE_DPAD_UP))
    }

    @Test
    fun `a selection out of range never traps the keys`() {
        val state = split.copy(left = starting)
        assertEquals(DualKeyRoute.Zap(forward = true), dualKeyRoute(state, KeyEvent.KEYCODE_DPAD_DOWN, cardSelected = 7))
        assertEquals(DualKeyRoute.CardButton(1), dualKeyRoute(state, KeyEvent.KEYCODE_DPAD_DOWN, cardSelected = -1))
    }

    @Test
    fun `the error card, a closed end card and a muted pane's card leave Up and Down to the game`() {
        val error = DualPane(a, dualStream("a"), PaneStatus.Error)
        assertEquals(0, dualCardButtons(error))
        assertEquals(DualKeyRoute.Zap(forward = true), dualKeyRoute(split.copy(left = error), KeyEvent.KEYCODE_DPAD_DOWN))
        val closed = DualPane(a, dualStream("a"), PaneStatus.Ended)
        assertEquals(DualKeyRoute.Zap(forward = true), dualKeyRoute(split.copy(left = closed), KeyEvent.KEYCODE_DPAD_DOWN))
        // The card is on the right pane; the ring is on the left one, playing.
        assertEquals(DualKeyRoute.Zap(forward = true), dualKeyRoute(split.copy(right = starting), KeyEvent.KEYCODE_DPAD_DOWN))
    }

    @Test
    fun `other keys are no D-pad route`() {
        assertNull(dualKeyRoute(split, KeyEvent.KEYCODE_DPAD_CENTER))
        assertNull(dualKeyRoute(split, KeyEvent.KEYCODE_INFO))
        assertNull(dualKeyRoute(split, KeyEvent.KEYCODE_BACK))
    }

    // OK

    @Test
    fun `OK in split, or on the big pane, plays full screen`() {
        assertEquals(DualOk.FULL_SCREEN, dualOkRoute(split))
        assertEquals(DualOk.FULL_SCREEN, dualOkRoute(split.copy(highlighted = Side.RIGHT)))
        assertEquals(DualOk.FULL_SCREEN, dualOkRoute(bigSmall))
    }

    @Test
    fun `OK on the small pane makes it the big one, with its sound`() {
        val small = bigSmall.copy(highlighted = Side.RIGHT)
        assertEquals(DualOk.MAKE_BIG, dualOkRoute(small))
        val swapped = small.reduce(DualAction.Swap)
        assertEquals(b, swapped.left.item)
        assertEquals(Side.LEFT, swapped.highlighted)
        assertEquals(Side.LEFT, swapped.audibleSide)
        // OK again: full screen.
        assertEquals(DualOk.FULL_SCREEN, dualOkRoute(swapped))
    }

    @Test
    fun `OK on a pane card presses its button, on the error card it offers the streams`() {
        assertEquals(DualOk.CARD_BUTTON, dualOkRoute(split.copy(left = starting)))
        assertEquals(DualOk.CARD_BUTTON, dualOkRoute(split.copy(left = ended(next = true))))
        assertEquals(DualOk.CHOOSE_STREAM, dualOkRoute(split.copy(left = DualPane(a, dualStream("a"), PaneStatus.Error))))
        // A card on the small pane keeps OK for its buttons.
        assertEquals(DualOk.CARD_BUTTON, dualOkRoute(bigSmall.copy(right = starting, highlighted = Side.RIGHT)))
    }

    // Hold OK and MENU

    @Test
    fun `a short OK acts on its release, a held one once, and never both`() {
        val key = DualHoldKey(holdMs = 500L)
        assertNull(key.onDown(0))
        assertEquals(DualPress.SHORT, key.onUp(heldMs = 120L))
        // Held until the key repeats: the layout changes once, the release does nothing.
        assertNull(key.onDown(0))
        assertEquals(DualPress.HELD, key.onDown(1))
        assertNull(key.onDown(2))
        assertNull(key.onUp(heldMs = 900L))
        // A remote that sends no repeats: a long press counts on its release.
        assertNull(key.onDown(0))
        assertEquals(DualPress.HELD, key.onUp(heldMs = 600L))
    }

    @Test
    fun `an OK release whose press went to the screen before does nothing`() {
        val key = DualHoldKey()
        assertNull(key.onUp(heldMs = 50L))
        assertNull(key.onDown(3))
        assertNull(key.onUp(heldMs = 900L))
    }

    @Test
    fun `MENU stays a shortcut, short swaps and held changes the layout`() {
        val key = DualMenuKey()
        assertNull(key.onDown(0))
        assertEquals(DualMenuAction.SWAP, key.onUp())
        assertNull(key.onDown(0))
        assertEquals(DualMenuAction.TOGGLE_LAYOUT, key.onDown(1))
        assertNull(key.onUp())
    }

    // The keys hint

    @Test
    fun `the keys hint names the D-pad model and what OK and Hold OK do here`() {
        assertEquals(
            listOf(DualHint.MOVE, DualHint.ZAP, DualHint.OK_FULL_SCREEN, DualHint.HOLD_OK_BIG_SMALL),
            dualKeyHints(split)
        )
        assertEquals(
            listOf(DualHint.MOVE, DualHint.ZAP, DualHint.OK_FULL_SCREEN, DualHint.HOLD_OK_SIDE_BY_SIDE),
            dualKeyHints(bigSmall)
        )
        assertEquals(
            listOf(DualHint.MOVE, DualHint.ZAP, DualHint.OK_MAKE_BIG, DualHint.HOLD_OK_SIDE_BY_SIDE),
            dualKeyHints(bigSmall.copy(highlighted = Side.RIGHT))
        )
    }
}
