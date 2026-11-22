package com.nuvio.tv.ui.screens.sportslive

import android.view.KeyEvent
import androidx.compose.ui.unit.dp
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.ui.screens.home.homeCardMenuEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Context audit 2026-11-21 (G9, G10, G14, G15) and design audit A3, on l24-dualalerts. */
class SportsLiveAuditFixesTest {

    private val center = KeyEvent.KEYCODE_DPAD_CENTER

    // G9: Hold OK opens the player menu in every player state

    @Test
    fun `with OK the player's own (a replay, OK = Pause, the end card) a short OK is the player's, a held one opens the menu`() {
        val ok = SportsLivePlayerOkKey()
        fun key(action: Int, repeat: Int, down: Long) = ok.onKey(center, action, repeat, down, guide = false, menu = false, lateMenu = true)
        // Short: every event is the player's (its pause, its card's button).
        assertEquals(SportsLivePlayerOk.NONE, key(KeyEvent.ACTION_DOWN, 0, 100L))
        assertEquals(SportsLivePlayerOk.NONE, key(KeyEvent.ACTION_UP, 0, 100L))
        // Held: the snapshot at the first repeat, the menu on the release; the release never clicks a button.
        assertEquals(SportsLivePlayerOk.NONE, key(KeyEvent.ACTION_DOWN, 0, 200L))
        assertEquals(SportsLivePlayerOk.HOLD, key(KeyEvent.ACTION_DOWN, 1, 200L))
        assertEquals(SportsLivePlayerOk.CONSUMED, key(KeyEvent.ACTION_DOWN, 2, 200L))
        assertEquals(SportsLivePlayerOk.MENU, key(KeyEvent.ACTION_UP, 0, 200L))
        // Without it (a watch party, no Sports & Live game): the player's, as before.
        assertEquals(SportsLivePlayerOk.NONE, ok.onKey(center, KeyEvent.ACTION_DOWN, 0, 300L, guide = false, menu = false))
        assertEquals(SportsLivePlayerOk.NONE, ok.onKey(center, KeyEvent.ACTION_DOWN, 1, 300L, guide = false, menu = false))
    }

    // G10: a Home Live now card's menu is the section's

    @Test
    fun `a Home Live now card offers Game stats, Follow, Watch later, Watch with another game and Report`() {
        val item = AlertFixtures.item(isLive = true)
        val full = homeCardMenuEntries(item, AlertFixtures.live(10, 8), followSides = 2, partners = 3)
        assertTrue(full.gameStats && full.watchLater && full.watchWith && full.report)
        assertEquals(2, full.followSides)
        // No strict ESPN match: no Game stats nor Watch later; no partner: no Watch with; Report always.
        val bare = homeCardMenuEntries(item, null, followSides = 0, partners = 0)
        assertFalse(bare.gameStats || bare.watchLater || bare.watchWith)
        assertTrue(bare.report)
        // A channel: no Follow, no Watch later.
        val channel = homeCardMenuEntries(AlertFixtures.item(isChannel = true), AlertFixtures.live(1, 0), followSides = 2, partners = 1)
        assertEquals(0, channel.followSides)
        assertFalse(channel.watchLater)
    }

    // G14 / G15: the list a zap session walks, by the tab it started on

    @Test
    fun `a game started from Upcoming zaps through Live now's games, Channels and Live now keep their own list`() {
        val live = listOf(AlertFixtures.item("A vs B", isLive = true), AlertFixtures.item("C vs D", isLive = true))
        val justStarted = AlertFixtures.item("E vs F", isLive = true)
        val upcoming = listOf(justStarted, AlertFixtures.item("G vs H"), AlertFixtures.item("I vs J"))
        val (items, tab) = sportsLiveZapSource(SportsLiveTab.UPCOMING, justStarted, upcoming, live)
        assertEquals(SportsLiveTab.LIVE_NOW, tab)
        // Not yet in Live now: first, then Live now's games; never the games that have not started.
        assertEquals(listOf(justStarted) + live, items)
        assertEquals(live to SportsLiveTab.LIVE_NOW, sportsLiveZapSource(SportsLiveTab.UPCOMING, live[1], upcoming, live))
        val channels = listOf(AlertFixtures.item("ESPN", isChannel = true), AlertFixtures.item("Fox", isChannel = true))
        assertEquals(channels to SportsLiveTab.CHANNELS, sportsLiveZapSource(SportsLiveTab.CHANNELS, channels[0], channels, live))
        assertEquals(live to SportsLiveTab.LIVE_NOW, sportsLiveZapSource(SportsLiveTab.LIVE_NOW, live[0], live, emptyList()))
    }

    @Test
    fun `only a Channels session gives the guide its own list, in CH plus and minus order whatever chip was picked (G15)`() {
        val uk = AlertFixtures.item("UK One", isChannel = true).copy(group = "UK")
        val us = AlertFixtures.item("US One", isChannel = true).copy(group = "US")
        val channels = SportsLiveZapSession.of(listOf(us, uk), uk.streamKey, SportsLiveTab.CHANNELS, "UK", false)!!
        assertEquals(listOf(uk, us), channels.channelGuideItems())
        assertEquals(listOf(uk, us), channels.copy(panelChip = ZapPanelChip("Soccer")).channelGuideItems())
        val live = listOf(AlertFixtures.item("A vs B", isLive = true), AlertFixtures.item("C vs D", isLive = true))
        assertEquals(null, SportsLiveZapSession.of(live, live[0].streamKey, SportsLiveTab.LIVE_NOW, null, false)!!.channelGuideItems())
    }

    // Design audit A3: one fixed home for alert cards, inside the safe area

    @Test
    fun `outside dual screen an alert card sits top right inside the TV safe area`() {
        val home = alertCardHome(960.dp, 540.dp)
        // 54 / 96 px at 1080p.
        assertEquals(27f, home.top.value, 0.01f)
        assertEquals(48f, home.end.value, 0.01f)
    }
}
