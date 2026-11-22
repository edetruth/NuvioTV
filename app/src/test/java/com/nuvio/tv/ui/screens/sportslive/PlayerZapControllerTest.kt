package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.SportsLiveStreamThresholds
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
    private val skipped = mutableListOf<String>()
    private var ended = emptySet<String>()
    private var tried = emptySet<String>()

    @After
    fun tearDown() {
        SportsLiveZapSessions.clear()
    }

    private fun TestScope.controller(
        scope: CoroutineScope = backgroundScope,
        settleMs: Long = 0L,
        prefetching: (SportsLiveItem) -> Boolean = { false }
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
        onSkipped = { item -> skipped += item.id },
        endedKeys = { ended },
        triedIds = { tried },
        settleMs = settleMs,
        prefetching = prefetching
    )

    private fun startOn(id: String) {
        SportsLiveZapSessions.start(
            SportsLiveZapSession.of(items, "addon|$id", SportsLiveTab.LIVE_NOW, sportKey("Football"), false)!!
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
    fun `a late lookup that ignores cancellation never plays after a newer zap`() = runTest {
        startOn("a")
        val slowB = CompletableDeferred<Stream?>()
        val controller = PlayerZapController(
            scope = backgroundScope,
            // Like a slow lookup that keeps running in the background (#158) and returns anyway.
            resolve = { item -> if (item.id == "b") kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { slowB.await() } else STREAM },
            onNavigate = { item, stream -> navigated += item.id to stream },
            onNoStream = { item -> noStream += item.id },
            onSkipped = { item -> skipped += item.id },
            endedKeys = { ended },
            settleMs = 0L
        )

        controller.zap("a", forward = true)
        runCurrent()
        controller.zap("a", forward = true)
        runCurrent()
        assertEquals(listOf("c" to STREAM), navigated)
        slowB.complete(STREAM)
        runCurrent()

        assertEquals(listOf("c" to STREAM), navigated)
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
        // c had no stream: the zap skipped on to d (#138), which has none either; the next would be a, playing.
        assertEquals(listOf("c"), skipped)
        pending.getValue("d").complete(null)
        runCurrent()

        assertEquals(listOf("d"), noStream)
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
    fun `a zap onto a game with no streams skips on in the same direction`() = runTest {
        startOn("a")
        val controller = controller()

        controller.zap("a", forward = false)
        runCurrent()
        pending.getValue("d").complete(null)
        runCurrent()

        assertEquals(listOf("d"), skipped)
        assertEquals("c", controller.resolving.value?.current?.id)
        pending.getValue("c").complete(STREAM)
        runCurrent()
        assertEquals(listOf("c" to STREAM), navigated)
        assertTrue(noStream.isEmpty())
    }

    @Test
    fun `a zap stops after five skips and stays on the playing game`() = runTest {
        val many = (0 until 9).map { item("g$it") }
        SportsLiveZapSessions.start(
            SportsLiveZapSession.of(many, "addon|g0", SportsLiveTab.LIVE_NOW, sportKey("Football"), false)!!
        )
        val controller = controller()
        many.forEach { pending[it.id] = CompletableDeferred<Stream?>().apply { complete(null) } }

        controller.zap("g0", forward = true)
        runCurrent()

        assertEquals(listOf("g1", "g2", "g3", "g4", "g5"), skipped)
        assertEquals(listOf("g6"), noStream)
        assertEquals(listOf("g1", "g2", "g3", "g4", "g5", "g6"), resolveCalls)
        assertTrue(navigated.isEmpty())
        assertEquals("g0", SportsLiveZapSessions.activeFor("g0")?.current?.id)
        assertNull(controller.resolving.value)
    }

    @Test
    fun `a CH zap waits at most 8 s on a slow lookup and shows Looking for streams first`() = runTest {
        startOn("a")
        val controller = controller()

        controller.zap("a", forward = true)
        runCurrent()
        assertEquals(listOf("b"), resolveCalls)
        // No card for a lookup that answers quickly.
        advanceTimeBy(SportsLiveStreamThresholds.ZAP_LOOKING_CARD_AFTER_MS - 1)
        runCurrent()
        assertNull(controller.looking.value)
        advanceTimeBy(2)
        runCurrent()
        assertEquals("b", controller.looking.value?.id)
        // Still nothing just before 8 s; at 8 s it skips on to the next game.
        advanceTimeBy(SportsLiveStreamThresholds.ZAP_SKIP_WAIT_MS - SportsLiveStreamThresholds.ZAP_LOOKING_CARD_AFTER_MS - 2)
        runCurrent()
        assertTrue(skipped.isEmpty())
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("b"), skipped)
        assertTrue("b" in cancelled)
        assertEquals("c", controller.resolving.value?.current?.id)
        assertNull(controller.looking.value)
        pending.getValue("c").complete(STREAM)
        runCurrent()
        assertEquals(listOf("c" to STREAM), navigated)
    }

    @Test
    fun `a CH zap on a game being prefetched waits for the prefetch instead of skipping`() = runTest {
        startOn("a")
        val prefetchingIds = mutableSetOf("b")
        val controller = controller(prefetching = { it.id in prefetchingIds })

        controller.zap("a", forward = true)
        runCurrent()
        // Past the 8 s skip wait: b's prefetch is still running, so the zap stays on b with the card up.
        advanceTimeBy(SportsLiveStreamThresholds.ZAP_SKIP_WAIT_MS + 3_000)
        runCurrent()
        assertTrue(skipped.isEmpty())
        assertEquals("b", controller.resolving.value?.current?.id)
        assertEquals("b", controller.looking.value?.id)
        // The prefetch answers: b plays.
        pending.getValue("b").complete(STREAM)
        runCurrent()
        assertEquals(listOf("b" to STREAM), navigated)
        assertTrue(skipped.isEmpty())
    }

    @Test
    fun `the wait on a prefetch is bounded by the lookup's cap and Skip still works`() = runTest {
        startOn("a")
        val controller = controller(prefetching = { it.id == "b" || it.id == "c" })

        controller.zap("a", forward = true)
        runCurrent()
        advanceTimeBy(SportsLiveStreamThresholds.LOOKUP_HARD_CAP_MS - 1)
        runCurrent()
        assertTrue(skipped.isEmpty())
        advanceTimeBy(2)
        runCurrent()
        // At the lookup's own 20 s cap the zap skips on, as it would have at 8 s.
        assertEquals(listOf("b"), skipped)
        assertEquals("c", controller.resolving.value?.current?.id)

        // c is being prefetched too: Skip on the card moves on at once, past 8 s or not.
        advanceTimeBy(SportsLiveStreamThresholds.ZAP_LOOKING_CARD_AFTER_MS + 1)
        runCurrent()
        assertTrue(controller.skip())
        runCurrent()
        assertEquals(listOf("b", "c"), skipped)
    }

    @Test
    fun `a lookup that found nothing within 8 s is not waited on even while prefetching`() = runTest {
        startOn("a")
        val controller = controller(prefetching = { true })

        controller.zap("a", forward = true)
        runCurrent()
        pending.getValue("b").complete(null)
        runCurrent()
        // b answered with nothing: skipped at once, no extra wait.
        assertEquals(listOf("b"), skipped)
    }

    @Test
    fun `Skip on the Looking for streams card moves on at once`() = runTest {
        startOn("a")
        val controller = controller()

        controller.zap("a", forward = true)
        runCurrent()
        // No card yet: OK is left to the player.
        assertFalse(controller.skip())
        advanceTimeBy(SportsLiveStreamThresholds.ZAP_LOOKING_CARD_AFTER_MS + 1)
        runCurrent()
        assertTrue(controller.skip())
        runCurrent()
        assertEquals(listOf("b"), skipped)
        assertEquals("c", controller.resolving.value?.current?.id)
    }

    @Test
    fun `an explicit pick keeps waiting past 8 s for its lookup`() = runTest {
        startOn("a")
        val controller = controller()

        controller.pick("a", items[2], items)
        runCurrent()
        advanceTimeBy(15_000)
        runCurrent()
        assertTrue(skipped.isEmpty())
        assertTrue(noStream.isEmpty())
        assertNull(controller.looking.value)
        assertEquals("c", controller.resolving.value?.current?.id)
        pending.getValue("c").complete(STREAM)
        runCurrent()
        assertEquals(listOf("c" to STREAM), navigated)
        // The pick waits for the lookup's own 20 s cap (Watch, OK), the CH zap for 8 s.
        assertEquals(20_000L, SportsLiveZapResolver.TIMEOUT_MS)
        assertEquals(8_000L, SportsLiveStreamThresholds.ZAP_SKIP_WAIT_MS)
    }

    @Test
    fun `the auto-skip passes games tried and failed this session`() = runTest {
        startOn("a")
        tried = setOf("c")
        val controller = controller()

        controller.zap("a", forward = true)
        runCurrent()
        pending.getValue("b").complete(null)
        runCurrent()
        // b had no stream: the skip goes past c (failed earlier) straight to d.
        assertEquals(listOf("b"), skipped)
        assertEquals("d", controller.resolving.value?.current?.id)
    }

    @Test
    fun `a game picked in the panel with no stream is not skipped past`() = runTest {
        startOn("a")
        val controller = controller()

        controller.pick("a", items[2], items)
        runCurrent()
        pending.getValue("c").complete(null)
        runCurrent()

        assertTrue(skipped.isEmpty())
        assertEquals(listOf("c"), noStream)
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
        pending.getValue("b").complete(STREAM)
        runCurrent()
        assertNull(controller.resolving.value)
    }

    @Test
    fun `a chip picked during a lookup keeps the zap going and is stored on its target`() = runTest {
        startOn("a")
        val controller = controller()

        controller.zap("a", forward = true)
        runCurrent()
        controller.pickChip("a", sportKey("Soccer"))
        runCurrent()

        assertTrue(cancelled.isEmpty())
        assertEquals("b", controller.resolving.value?.current?.id)
        assertEquals("b", SportsLiveZapSessions.current()?.current?.id)
        assertEquals(ZapPanelChip(sportKey("Soccer")), SportsLiveZapSessions.current()?.panelChip)
        pending.getValue("b").complete(STREAM)
        runCurrent()

        assertEquals(listOf("b" to STREAM), navigated)
        assertEquals(ZapPanelChip(sportKey("Soccer")), SportsLiveZapSessions.activeFor("b")?.panelChip)
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
        second.pickChip("a", sportKey("Soccer"))
        assertEquals("d", SportsLiveZapSessions.current()?.current?.id)
        playerScope.cancel()
        runCurrent()
        assertEquals(ZapPanelChip(sportKey("Soccer")), SportsLiveZapSessions.activeFor("a")?.panelChip)
    }

    /**
     * live.25 I: the player's scope on the TV (viewModelScope, Main.immediate) resumes a cancelled lookup inside cancel(),
     * so its catch runs before the newer zap returns. The unconfined test dispatcher does the same; backgroundScope's
     * standard dispatcher queues it, which hid the race from the tests above.
     */
    private fun TestScope.immediateScope(): CoroutineScope =
        CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))

    @Test
    fun `two quick flips land in a player that keeps the session`() = runTest {
        startOn("a")
        val controller = controller(immediateScope(), settleMs = 600L)

        controller.zap("a", forward = true)
        advanceTimeBy(300)
        controller.zap("a", forward = true)
        // The cancelled first flip must not put the session back on the game playing.
        assertEquals("c", SportsLiveZapSessions.current()?.current?.id)
        advanceTimeBy(601)
        pending.getValue("c").complete(STREAM)
        runCurrent()

        assertEquals(listOf("c" to STREAM), navigated)
        // The player opened for c keeps its session, so CH, the guide and the sports menu work there.
        SportsLiveZapSessions.keepOnlyFor("c")
        assertEquals("c", SportsLiveZapSessions.activeFor("c")?.current?.id)
        assertTrue(controller(immediateScope()).zap("c", forward = true))
    }

    @Test
    fun `a flip during the previous flip's lookup lands in a player that keeps the session`() = runTest {
        startOn("a")
        val controller = controller(immediateScope(), settleMs = 600L)

        controller.zap("a", forward = true)
        advanceTimeBy(601)
        assertEquals(listOf("b"), resolveCalls)
        // The field log's second press, while the first flip's lookup runs.
        controller.zap("a", forward = true)
        assertEquals("c", SportsLiveZapSessions.current()?.current?.id)
        runCurrent()
        assertEquals(listOf("b"), cancelled)
        advanceTimeBy(601)
        pending.getValue("c").complete(STREAM)
        runCurrent()

        assertEquals(listOf("c" to STREAM), navigated)
        SportsLiveZapSessions.keepOnlyFor("c")
        assertEquals("c", SportsLiveZapSessions.activeFor("c")?.current?.id)
    }

    @Test
    fun `a double flip right after a zapped player started keeps the session for the next player`() = runTest {
        startOn("a")
        val oldPlayer = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler) + Job())
        controller(oldPlayer, settleMs = 600L).zap("a", forward = true)
        advanceTimeBy(601)
        pending.getValue("b").complete(STREAM)
        runCurrent()
        assertEquals(listOf("b" to STREAM), navigated)
        // The player for b opens, as SportsLiveZapViewModel does it.
        SportsLiveZapSessions.markZapArrival()
        SportsLiveZapSessions.keepOnlyFor("b")
        assertTrue(SportsLiveZapSessions.consumeZapArrival())
        val newPlayer = controller(immediateScope(), settleMs = 600L)

        // A flip at once as b starts, a second one while its lookup runs; the old player leaves meanwhile.
        assertTrue(newPlayer.zap("b", forward = true))
        advanceTimeBy(601)
        assertTrue(newPlayer.zap("b", forward = true))
        oldPlayer.cancel()
        runCurrent()
        assertEquals("d", SportsLiveZapSessions.current()?.current?.id)
        advanceTimeBy(601)
        pending.getValue("d").complete(STREAM)
        runCurrent()

        assertEquals(listOf("b" to STREAM, "d" to STREAM), navigated)
        SportsLiveZapSessions.keepOnlyFor("d")
        assertEquals("d", SportsLiveZapSessions.activeFor("d")?.current?.id)
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
