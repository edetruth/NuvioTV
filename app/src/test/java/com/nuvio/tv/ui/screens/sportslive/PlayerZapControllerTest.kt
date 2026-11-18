package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerZapControllerTest {

    private val items = listOf(item("a"), item("b"), item("c"), item("d"))
    private val pending = mutableMapOf<String, CompletableDeferred<Stream?>>()
    private val resolveCalls = mutableListOf<String>()
    private val cancelled = mutableListOf<String>()
    private val navigated = mutableListOf<Pair<String, Stream>>()
    private val noStream = mutableListOf<String>()
    private var ended = emptySet<String>()

    @After
    fun tearDown() {
        SportsLiveZapSessions.clear()
    }

    private fun TestScope.controller(
        scope: CoroutineScope = backgroundScope,
        settleMs: Long = 0L
    ) = PlayerZapController(
        scope = scope,
        resolve = { item ->
            resolveCalls += item.id
            val deferred = pending.getOrPut(item.id) { CompletableDeferred() }
            try {
                deferred.await()
            } catch (e: CancellationException) {
                cancelled += item.id
                throw e
            }
        },
        onNavigate = { item, stream -> navigated += item.id to stream },
        onNoStream = { item -> noStream += item.id },
        endedKeys = { ended },
        settleMs = settleMs
    )

    private fun startOn(id: String) {
        SportsLiveZapSessions.start(
            SportsLiveZapSession.of(items, "addon|$id", SportsLiveTab.LIVE_NOW, "Football", false)!!
        )
    }

    @Test
    fun `no session or a different content leaves the key to the player`() = runTest {
        val controller = controller()

        assertFalse(controller.zap("a", forward = true))
        startOn("a")
        assertFalse(controller.zap("movie", forward = true))
        assertTrue(resolveCalls.isEmpty())
    }

    @Test
    fun `a single game session leaves the key to the player`() = runTest {
        SportsLiveZapSessions.start(SportsLiveZapSession.of(listOf(item("a")), "addon|a", SportsLiveTab.LIVE_NOW, null, false)!!)

        assertFalse(controller().zap("a", forward = true))
    }

    @Test
    fun `zap navigates to the next game and moves the session`() = runTest {
        startOn("a")
        val controller = controller()

        assertTrue(controller.zap("a", forward = true))
        assertEquals("b", SportsLiveZapSessions.current()?.current?.id)
        runCurrent()
        assertTrue(navigated.isEmpty())
        pending.getValue("b").complete(STREAM)
        runCurrent()

        assertEquals(listOf("b" to STREAM), navigated)
        assertEquals("b", SportsLiveZapSessions.activeFor("b")?.current?.id)
    }

    @Test
    fun `zaps skip games known to have ended`() = runTest {
        startOn("a")
        ended = setOf("addon|b", "addon|d")
        val controller = controller()

        assertTrue(controller.zap("a", forward = true))
        runCurrent()
        assertEquals("c", SportsLiveZapSessions.current()?.current?.id)
        controller.zap("a", forward = true)
        runCurrent()
        // From "c" forward: "d" ended, wraps to "a".
        assertEquals("a", SportsLiveZapSessions.current()?.current?.id)
        controller.zap("a", forward = false)
        runCurrent()

        assertEquals(listOf("c", "a", "c"), resolveCalls)
        assertEquals(4, SportsLiveZapSessions.current()?.items?.size)
    }

    @Test
    fun `when every other game has ended the zap stays on the playing game`() = runTest {
        startOn("a")
        ended = setOf("addon|b", "addon|c", "addon|d")
        val controller = controller()

        assertFalse(controller.zap("a", forward = true))
        assertFalse(controller.zap("a", forward = false))
        assertTrue(resolveCalls.isEmpty())
        assertEquals("a", SportsLiveZapSessions.activeFor("a")?.current?.id)
    }

    @Test
    fun `starting a session drops a pending zap arrival`() {
        SportsLiveZapSessions.markZapArrival()
        startOn("a")

        assertFalse(SportsLiveZapSessions.consumeZapArrival())
        SportsLiveZapSessions.markZapArrival()
        assertTrue(SportsLiveZapSessions.consumeZapArrival())
    }

    @Test
    fun `previous wraps around to the last game`() = runTest {
        startOn("a")
        val controller = controller()

        controller.zap("a", forward = false)
        runCurrent()

        assertEquals(listOf("d"), resolveCalls)
    }

    @Test
    fun `a newer zap cancels the older resolve and only the last target navigates`() = runTest {
        startOn("a")
        val controller = controller()

        controller.zap("a", forward = true)
        runCurrent()
        // The player still shows "a"; the session already points at "b", so this moves on to "c".
        controller.zap("a", forward = true)
        runCurrent()
        controller.zap("a", forward = false)
        runCurrent()

        assertEquals(listOf("b", "c", "b"), resolveCalls)
        assertEquals(listOf("b", "c"), cancelled)
        pending.getValue("b").complete(STREAM)
        runCurrent()

        assertEquals(listOf("b" to STREAM), navigated)
        assertEquals("b", SportsLiveZapSessions.current()?.current?.id)
    }

    @Test
    fun `flipping past channels only resolves the one settled on`() = runTest {
        startOn("a")
        val controller = controller(settleMs = 600L)

        // Flip forward three times quickly, each within the settle window.
        controller.zap("a", forward = true)
        runCurrent()
        controller.zap("a", forward = true)
        runCurrent()
        controller.zap("a", forward = true)
        runCurrent()
        // Nothing resolved yet: still settling.
        assertTrue(resolveCalls.isEmpty())

        advanceTimeBy(601)
        runCurrent()
        // Only the channel settled on ("d") resolved — one request, not three.
        assertEquals(listOf("d"), resolveCalls)
        pending.getValue("d").complete(STREAM)
        runCurrent()
        assertEquals(listOf("d" to STREAM), navigated)
    }

    @Test
    fun `no stream shows the toast and puts the session back on the playing game`() = runTest {
        startOn("a")
        val controller = controller()

        controller.zap("a", forward = true)
        runCurrent()
        controller.zap("a", forward = true)
        runCurrent()
        pending.getValue("c").complete(null)
        runCurrent()

        assertEquals(listOf("c"), noStream)
        assertTrue(navigated.isEmpty())
        assertEquals("a", SportsLiveZapSessions.activeFor("a")?.current?.id)
        // The player keeps zapping from the game it shows.
        assertTrue(controller.zap("a", forward = true))
        assertEquals("b", SportsLiveZapSessions.current()?.current?.id)
    }

    @Test
    fun `leaving the player mid lookup puts the session back on the playing game`() = runTest {
        startOn("a")
        val playerScope = CoroutineScope(backgroundScope.coroutineContext + Job())
        val controller = controller(playerScope)

        controller.zap("a", forward = true)
        runCurrent()
        controller.zap("a", forward = true)
        runCurrent()
        playerScope.cancel()
        runCurrent()

        assertEquals(listOf("b", "c"), cancelled)
        assertTrue(navigated.isEmpty())
        assertTrue(noStream.isEmpty())
        assertEquals("a", SportsLiveZapSessions.activeFor("a")?.current?.id)
    }

    @Test
    fun `a player for other content ends the session and a matching one keeps it`() {
        startOn("a")

        SportsLiveZapSessions.keepOnlyFor("a")
        assertEquals("a", SportsLiveZapSessions.current()?.current?.id)

        SportsLiveZapSessions.keepOnlyFor("movie")
        assertNull(SportsLiveZapSessions.current())

        startOn("a")
        SportsLiveZapSessions.keepOnlyFor(null)
        assertNull(SportsLiveZapSessions.current())
    }

    @Test
    fun `factory copies the list and finds the item, or returns null`() {
        val source = items.toMutableList()
        val session = SportsLiveZapSession.of(source, "addon|c", SportsLiveTab.CHANNELS, "UK", true)!!
        source.clear()

        assertEquals(2, session.index)
        assertEquals(4, session.items.size)
        assertEquals(SportsLiveTab.CHANNELS, session.tab)
        assertEquals("UK", session.filter)
        assertTrue(session.returnToHomeOnBack)
        // Null for an empty list or a missing item.
        assertNull(SportsLiveZapSession.of(emptyList(), "addon|a", SportsLiveTab.LIVE_NOW, null, false))
        assertNull(SportsLiveZapSession.of(items, "addon|missing", SportsLiveTab.LIVE_NOW, null, false))
        assertNull(SportsLiveZapSession.of(items, "other|a", SportsLiveTab.LIVE_NOW, null, false))
    }

    @Test
    fun `picking a game of the session moves the session to it and shows it as switching`() = runTest {
        startOn("a")
        val controller = controller()

        assertTrue(controller.pick("a", items[2], panelItems = listOf(items[2])))
        runCurrent()

        assertEquals(listOf("c"), resolveCalls)
        assertEquals("c", controller.resolving.value?.current?.id)
        assertEquals(4, SportsLiveZapSessions.current()?.items?.size)
        pending.getValue("c").complete(STREAM)
        runCurrent()

        assertEquals(listOf("c" to STREAM), navigated)
        assertNull(controller.resolving.value)
    }

    @Test
    fun `picking a game the session lacks starts a live now session over the panel list`() = runTest {
        startOn("a")
        val panel = listOf(item("x"), item("a"), item("y"))
        val controller = controller()

        assertTrue(controller.pick("a", panel[2], panel))

        val session = SportsLiveZapSessions.current()!!
        assertEquals(listOf("x", "a", "y"), session.items.map { it.id })
        assertEquals(2, session.index)
        assertEquals(SportsLiveTab.LIVE_NOW, session.tab)
    }

    @Test
    fun `picking the game playing zaps nothing and cancels a running lookup`() = runTest {
        startOn("a")
        val controller = controller()

        assertFalse(controller.pick("a", items[0], items))
        assertTrue(resolveCalls.isEmpty())

        controller.zap("a", forward = true)
        runCurrent()
        assertFalse(controller.pick("a", items[0], items))
        runCurrent()

        assertEquals(listOf("b"), cancelled)
        assertNull(controller.resolving.value)
        assertEquals("a", SportsLiveZapSessions.activeFor("a")?.current?.id)
    }

    @Test
    fun `a cancelled lookup leaves the newer lookup shown as switching`() = runTest {
        startOn("a")
        val controller = controller()

        controller.zap("a", forward = true)
        runCurrent()
        controller.zap("a", forward = false)
        runCurrent()
        controller.zap("a", forward = true)
        runCurrent()

        assertEquals("b", controller.resolving.value?.current?.id)
        pending.getValue("b").complete(null)
        runCurrent()
        assertNull(controller.resolving.value)
    }

    @Test
    fun `a chip picked during a lookup keeps the zap going and is stored on its target`() = runTest {
        startOn("a")
        val controller = controller()

        controller.zap("a", forward = true)
        runCurrent()
        controller.pickChip("a", "Soccer")
        runCurrent()

        assertTrue(cancelled.isEmpty())
        assertEquals("b", controller.resolving.value?.current?.id)
        assertEquals("b", SportsLiveZapSessions.current()?.current?.id)
        assertEquals(ZapPanelChip("Soccer"), SportsLiveZapSessions.current()?.panelChip)
        pending.getValue("b").complete(STREAM)
        runCurrent()

        assertEquals(listOf("b" to STREAM), navigated)
        assertEquals(ZapPanelChip("Soccer"), SportsLiveZapSessions.activeFor("b")?.panelChip)
    }

    @Test
    fun `a chip picked during a lookup that finds no stream stays with the game playing`() = runTest {
        startOn("a")
        val controller = controller()

        controller.zap("a", forward = true)
        runCurrent()
        controller.pickChip("a", null)
        pending.getValue("b").complete(null)
        runCurrent()

        assertEquals(listOf("b"), noStream)
        val back = SportsLiveZapSessions.activeFor("a")!!
        assertEquals(ZapPanelChip(null), back.panelChip)
        // Leaving the player mid lookup after a chip also goes back to the game playing.
        val playerScope = CoroutineScope(backgroundScope.coroutineContext + Job())
        val second = controller(playerScope)
        second.zap("a", forward = false)
        runCurrent()
        second.pickChip("a", "Soccer")
        assertEquals("d", SportsLiveZapSessions.current()?.current?.id)
        playerScope.cancel()
        runCurrent()
        assertEquals(ZapPanelChip("Soccer"), SportsLiveZapSessions.activeFor("a")?.panelChip)
    }

    private companion object {
        val STREAM = Stream(
            name = "HD",
            title = null,
            description = null,
            url = "https://cdn.example/hd.m3u8",
            ytId = null,
            infoHash = null,
            fileIdx = null,
            externalUrl = null,
            behaviorHints = null,
            addonName = "Sports",
            addonLogo = null
        )

        fun item(id: String) = SportsLiveItem(
            meta = MetaPreview(
                id = id,
                type = ContentType.UNKNOWN,
                rawType = "tv",
                name = id,
                poster = null,
                posterShape = PosterShape.LANDSCAPE,
                background = null,
                logo = null,
                description = null,
                releaseInfo = null,
                imdbRating = null,
                genres = emptyList()
            ),
            addonId = "addon",
            addonName = "addon",
            addonBaseUrl = "https://addon",
            catalogKey = "addon:tv:live",
            catalogName = "Live",
            kind = SportsLiveKind.EVENTS,
            isLive = true,
            isChannel = false,
            startMs = null,
            sport = null
        )
    }
}
