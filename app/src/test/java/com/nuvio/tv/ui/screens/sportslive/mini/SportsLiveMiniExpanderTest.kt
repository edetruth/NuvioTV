package com.nuvio.tv.ui.screens.sportslive.mini

import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.screens.sportslive.SportsLiveZapSession
import com.nuvio.tv.ui.screens.sportslive.SportsLiveZapSessions
import com.nuvio.tv.ui.screens.sportslive.SportsLiveZapTarget
import com.nuvio.tv.ui.screens.sportslive.dual.dualItem
import com.nuvio.tv.ui.screens.sportslive.dual.dualStream
import com.nuvio.tv.ui.screens.stream.StreamPlaybackInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SportsLiveMiniExpanderTest {

    private val a = dualItem("a")
    private val b = dualItem("b")
    private val miniStream = dualStream("mini")
    private val freshStream = dualStream("fresh")
    private val session = SportsLiveZapSession(listOf(a, b), 0, SportsLiveTab.LIVE_NOW, null, false)
    private var lookups = 0
    private val picked = mutableListOf<Pair<Stream, Boolean>>()

    /** Whether the targets carry the full player's route (false: the Stream screen opens instead). */
    private var withPlayback = true

    @After
    fun tearDown() {
        SportsLiveZapSessions.clear()
    }

    private class Setup(
        val mini: SportsLiveMiniPlayerController,
        val expander: SportsLiveMiniExpander,
        val targets: MutableList<SportsLiveMiniExpand>
    )

    private fun TestScope.setup(lookup: CompletableDeferred<Stream?>): Setup {
        val mini = SportsLiveMiniPlayerController(backgroundScope)
        val expander = SportsLiveMiniExpander(
            scope = backgroundScope,
            controller = mini,
            resolve = { lookups++; lookup.await() },
            toTarget = { playing, stream, fresh ->
                picked += stream to fresh
                SportsLiveZapTarget(playing.item, if (withPlayback) playbackOf(stream) else null, playing.session.returnToHomeOnBack)
            }
        )
        val targets = mutableListOf<SportsLiveMiniExpand>()
        backgroundScope.launch { expander.targets.collect { targets += it } }
        mini.onBackFromPlayer("a", session, miniStream)
        return Setup(mini, expander, targets)
    }

    private fun Setup.playing() = mini.state.value as SportsLiveMiniState.Playing

    @Test
    fun `a fresh stream opens the full player with the session restored, progress shown meanwhile`() = runTest {
        val lookup = CompletableDeferred<Stream?>()
        val s = setup(lookup)

        s.expander.expand(s.playing())
        runCurrent()
        assertEquals(a, s.expander.expanding.value)
        s.expander.expand(s.playing())
        runCurrent()
        assertEquals("a second press starts no second lookup", 1, lookups)

        lookup.complete(freshStream)
        runCurrent()
        assertEquals(listOf(freshStream to true), picked)
        assertEquals(1, s.targets.size)
        assertFalse("a looked-up stream opens on its own, the mini player stopped first", s.targets.single().handOver)
        assertSame(session, SportsLiveZapSessions.current())
        assertNull(s.expander.expanding.value)
    }

    @Test
    fun `no fresh stream falls back to the mini player's`() = runTest {
        val lookup = CompletableDeferred<Stream?>()
        val s = setup(lookup)

        s.expander.expand(s.playing())
        lookup.complete(null)
        runCurrent()

        assertEquals(listOf(miniStream to false), picked)
        assertEquals(1, s.targets.size)
        assertFalse("a link not playing is never handed over", s.targets.single().handOver)
    }

    @Test
    fun `Back cancels the lookup and the mini player plays on`() = runTest {
        val lookup = CompletableDeferred<Stream?>()
        val s = setup(lookup)

        s.expander.expand(s.playing())
        runCurrent()
        s.expander.cancel()
        lookup.complete(freshStream)
        runCurrent()

        assertNull(s.expander.expanding.value)
        assertTrue(s.targets.isEmpty())
        assertTrue(s.mini.isActive)
        assertNull(SportsLiveZapSessions.current())
    }

    @Test
    fun `a mini player stopped during the lookup opens nothing`() = runTest {
        val lookup = CompletableDeferred<Stream?>()
        val s = setup(lookup)

        s.expander.expand(s.playing())
        runCurrent()
        s.mini.stop(SportsLiveMiniStop.USER_STOP)
        lookup.complete(freshStream)
        runCurrent()

        assertTrue(s.targets.isEmpty())
        assertNull(s.expander.expanding.value)
        assertNull(SportsLiveZapSessions.current())
    }

    @Test
    fun `a playing link is reused and a link that is not playing is looked up`() {
        assertEquals(SportsLiveMiniExpandSource.REUSE, sportsLiveMiniExpandSource(hasUrl = true, linkPlaying = true))
        assertEquals(SportsLiveMiniExpandSource.RESOLVE, sportsLiveMiniExpandSource(hasUrl = true, linkPlaying = false))
        assertEquals(SportsLiveMiniExpandSource.RESOLVE, sportsLiveMiniExpandSource(hasUrl = false, linkPlaying = true))
    }

    @Test
    fun `expanding a playing mini player opens its own link with no lookup and no dialog`() = runTest {
        val lookup = CompletableDeferred<Stream?>()
        val s = setup(lookup)
        s.mini.onLinkPlaying(s.playing().sessionId, true)

        s.expander.expand(s.playing())
        assertNull("no progress dialog", s.expander.expanding.value)
        runCurrent()

        assertEquals(0, lookups)
        assertEquals(listOf(miniStream to false), picked)
        assertEquals(1, s.targets.size)
        // L (live.25): the link playing goes on in its own player, handed to the full player, never started again.
        assertTrue(s.targets.single().handOver)
        assertEquals(miniStream.url, s.targets.single().target.playback?.url)
    }

    @Test
    fun `a playing link whose target opens the Stream screen is not handed over`() = runTest {
        withPlayback = false
        val lookup = CompletableDeferred<Stream?>()
        val s = setup(lookup)
        s.mini.onLinkPlaying(s.playing().sessionId, true)

        s.expander.expand(s.playing())
        runCurrent()

        assertEquals(0, lookups)
        assertFalse(s.targets.single().handOver)
    }

    @Test
    fun `only a link drawing frames and not reconnecting is handed over`() {
        assertTrue(sportsLiveMiniHandsOver(rendered = true, reconnecting = false))
        assertFalse(sportsLiveMiniHandsOver(rendered = false, reconnecting = false))
        assertFalse(sportsLiveMiniHandsOver(rendered = true, reconnecting = true))
    }

    @Test
    fun `a mini player reconnecting is looked up again on expand`() = runTest {
        val lookup = CompletableDeferred<Stream?>()
        val s = setup(lookup)
        val id = s.playing().sessionId
        s.mini.onLinkPlaying(id, true)
        s.mini.onLinkPlaying(id, false)

        s.expander.expand(s.playing())
        runCurrent()
        assertEquals(1, lookups)
        lookup.complete(freshStream)
        runCurrent()
        assertEquals(listOf(freshStream to true), picked)
    }

    @Test
    fun `an earlier mini player's playing link is not the new one's`() = runTest {
        val lookup = CompletableDeferred<Stream?>()
        val s = setup(lookup)
        val first = s.playing().sessionId
        s.mini.onLinkPlaying(first, true)
        s.mini.onBackFromPlayer("a", session, miniStream)

        assertTrue(s.playing().sessionId != first)
        assertTrue(!s.mini.isLinkPlaying(s.playing()))
    }
}

private fun playbackOf(stream: Stream) = StreamPlaybackInfo(
    url = stream.url,
    title = "game",
    streamName = stream.name.orEmpty(),
    year = null,
    isExternal = false,
    isTorrent = false,
    infoHash = null,
    ytId = null,
    headers = null,
    contentId = "a",
    contentType = null,
    contentName = null,
    poster = null,
    backdrop = null,
    logo = null,
    videoId = null,
    season = null,
    episode = null,
    episodeTitle = null,
    bingeGroup = null,
    profileId = 1
)
