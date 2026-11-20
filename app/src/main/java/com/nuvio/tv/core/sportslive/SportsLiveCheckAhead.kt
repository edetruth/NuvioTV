package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.SportsLiveItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Check-ahead in the in-player panel (#147): while the panel is open, its games are checked one at a time so
 * their rows can show ✓ ready or ⚠ no working stream, and OK or CH± starts on a link known to work.
 * - First the focused game (once focus rested on it [dwellMs]) and the game CH− would play next.
 * - Then the rest, in panel order, one at a time, [trickleMs] after the check before.
 * - A game checked (or marked elsewhere: a start's race, a first frame) within [cacheMs] is not checked
 *   again; nor one [skip] rules out (every source on a rate-limited or slow addon).
 * - [close] (the panel closed) stops at once, a check in flight included.
 * Checks never overlap. [check] does the lookup and the probe and sets the game's mark itself.
 */
class SportsLiveCheckAhead(
    private val scope: CoroutineScope,
    private val check: suspend (SportsLiveItem) -> Unit,
    private val skip: (SportsLiveItem) -> Boolean = { false },
    /** When [SportsLiveItem]'s standing mark was set elsewhere; null without one. */
    private val markedAt: (SportsLiveItem) -> Long? = { null },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val trickleMs: Long = SportsLiveStreamThresholds.CHECK_AHEAD_TRICKLE_MS,
    private val cacheMs: Long = SportsLiveStreamThresholds.CHECK_AHEAD_CACHE_MS,
    private val dwellMs: Long = SportsLiveStreamThresholds.CHECK_AHEAD_FOCUS_DWELL_MS,
    /** Runs before each pick (the caller drops marks that no longer stand). */
    private val onTick: () -> Unit = {}
) {
    private var job: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Volatile private var rows: List<SportsLiveItem> = emptyList()
    @Volatile private var focused: SportsLiveItem? = null
    @Volatile private var focusedAtMs = 0L
    @Volatile private var next: SportsLiveItem? = null

    /** Game id -> when its last check started. */
    private val checkedAt = HashMap<String, Long>()
    private var lastCheckAtMs: Long? = null

    val isOpen: Boolean get() = job?.isActive == true

    /** The panel opened: checks begin. */
    fun open() {
        if (isOpen) return
        job = scope.launch { loop() }
    }

    /** The panel closed: every check stops, the one in flight included. */
    fun close() {
        job?.cancel()
        job = null
        focused = null
        next = null
    }

    /** The panel's games in their order (the game playing left out). */
    fun update(rows: List<SportsLiveItem>) {
        if (rows == this.rows) return
        this.rows = rows
        wake.trySend(Unit)
    }

    /** Focus moved to [item] (null: no game row). */
    fun focus(item: SportsLiveItem?) {
        if (item?.streamKey == focused?.streamKey) return
        focused = item
        focusedAtMs = nowMs()
        wake.trySend(Unit)
    }

    /** The game CH− would play next. */
    fun next(item: SportsLiveItem?) {
        if (item?.streamKey == next?.streamKey) return
        next = item
        wake.trySend(Unit)
    }

    private sealed interface Pick {
        data class Now(val item: SportsLiveItem) : Pick
        data class Wait(val ms: Long) : Pick
    }

    private suspend fun loop() {
        while (true) {
            onTick()
            when (val pick = pick(nowMs())) {
                is Pick.Now -> {
                    synchronized(checkedAt) {
                        checkedAt[pick.item.id] = nowMs()
                        // Every check spaces the trickle, so the rest never follows a priority check at once.
                        lastCheckAtMs = nowMs()
                    }
                    try {
                        check(pick.item)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // No verdict: the game is left unmarked until its turn comes again.
                    }
                }
                is Pick.Wait -> withTimeoutOrNull(pick.ms) { wake.receive() }
            }
        }
    }

    /** True when [item] needs a check at [now]. */
    private fun due(item: SportsLiveItem, now: Long): Boolean {
        val last = maxOf(synchronized(checkedAt) { checkedAt[item.id] } ?: Long.MIN_VALUE, markedAt(item) ?: Long.MIN_VALUE)
        if (last != Long.MIN_VALUE && now - last < cacheMs) return false
        return !skip(item)
    }

    private fun pick(now: Long): Pick {
        var waitMs = trickleMs
        focused?.takeIf { due(it, now) }?.let { item ->
            val rested = now - focusedAtMs
            if (rested >= dwellMs) return Pick.Now(item)
            waitMs = minOf(waitMs, dwellMs - rested)
        }
        next?.takeIf { due(it, now) }?.let { return Pick.Now(it) }
        rows.firstOrNull { due(it, now) }?.let { item ->
            val since = synchronized(checkedAt) { lastCheckAtMs }?.let { now - it } ?: Long.MAX_VALUE
            if (since >= trickleMs) return Pick.Now(item)
            waitMs = minOf(waitMs, trickleMs - since)
        }
        return Pick.Wait(waitMs.coerceAtLeast(1L))
    }
}
