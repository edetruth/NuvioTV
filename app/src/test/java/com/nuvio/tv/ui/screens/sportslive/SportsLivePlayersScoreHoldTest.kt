package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.sportslive.scores.ScoreWatch
import com.nuvio.tv.core.sportslive.scores.WatchingNow
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.screens.sportslive.dual.Side
import com.nuvio.tv.ui.screens.sportslive.dual.SportsLiveDualSession
import com.nuvio.tv.ui.screens.sportslive.dual.SportsLiveDualSessions
import com.nuvio.tv.ui.screens.sportslive.dual.SportsLiveDualViewModel
import com.nuvio.tv.ui.screens.sportslive.dual.dualItem
import com.nuvio.tv.ui.screens.sportslive.dual.dualWatchOwner
import com.nuvio.tv.ui.screens.sportslive.mini.MINI_WATCH_OWNER
import com.nuvio.tv.ui.screens.sportslive.mini.reportMiniBehindLive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * G7 (context audit 2026-11-21): dual panes and the mini player report how far their streams are behind live into
 * the same score hold as the full player, so other games' scores wait for the stream furthest behind, not a fixed
 * minute.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SportsLivePlayersScoreHoldTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val owners = listOf(dualWatchOwner(Side.LEFT), dualWatchOwner(Side.RIGHT), MINI_WATCH_OWNER)

    @After
    fun tearDown() {
        owners.forEach(WatchingNow.shared::stop)
        SportsLiveDualSessions.clear()
    }

    private fun holdMs(): Long =
        ScoreWatch.delayMs(settingMs = 0L, realTime = false, playing = WatchingNow.shared.state.value.filterKeys { it in owners }.values, nudges = emptyMap())

    @Test
    fun `a dual pane 90 s behind live holds other games' scores 105 s, not 60`() = runTest(mainDispatcherRule.dispatcher) {
        val a = dualItem("a")
        val b = dualItem("b")
        val viewModel = SportsLiveDualViewModel(
            // Still looking up their links: both panes keep their games.
            resolve = { CompletableDeferred<Stream?>().await() },
            endedKeys = { emptySet() },
            session = SportsLiveDualSession(a, b, listOf(a, b), null, SportsLiveTab.LIVE_NOW)
        )
        runCurrent()
        // Both panes registered, nothing measured yet: the fixed floor.
        assertEquals(ScoreWatch.MIN_PLAYING_DELAY_MS, holdMs())

        viewModel.onBehindLive(b, 90_000L)
        viewModel.onBehindLive(a, 20_000L)
        val sideOfB = viewModel.state.value!!.sideOf(b)!!
        assertEquals(90_000L, WatchingNow.shared.state.value.getValue(dualWatchOwner(sideOfB)).behindLiveMs)
        // The pane furthest behind decides, plus the margin.
        assertEquals(90_000L + ScoreWatch.MARGIN_MS, holdMs())

        // A game not in either pane reports nothing.
        viewModel.onBehindLive(dualItem("c"), 300_000L)
        assertEquals(90_000L + ScoreWatch.MARGIN_MS, holdMs())
    }

    @Test
    fun `the mini player 90 s behind live holds other games' scores 105 s, and reports nothing while it plays nothing`() {
        reportMiniBehindLive(90_000L)
        assertNull(WatchingNow.shared.state.value[MINI_WATCH_OWNER])

        WatchingNow.shared.watch(MINI_WATCH_OWNER, setOf(dualItem("a").streamKey), "addon")
        assertEquals(ScoreWatch.MIN_PLAYING_DELAY_MS, holdMs())
        reportMiniBehindLive(90_000L)
        assertEquals(90_000L, WatchingNow.shared.state.value.getValue(MINI_WATCH_OWNER).behindLiveMs)
        assertEquals(90_000L + ScoreWatch.MARGIN_MS, holdMs())
    }
}
