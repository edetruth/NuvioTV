package com.nuvio.tv.ui.screens.sportslive

import androidx.annotation.VisibleForTesting
import com.nuvio.tv.core.sportslive.SportsLiveDebugTiming
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What a screen's [SportsLiveStreamPrefetcher] runs (live.20 B2), from [SportsLiveZapResolver]: [prefetch] looks a game
 * up as OK would (cached for the start), [isWarm] says a start would need no addon request, [isCooling] that every source
 * is rate-limited (C1). [verify] probes a zap neighbour's found link once ([SportsLiveZapResolver.verifyAndHold]).
 */
class SportsLivePrefetchHooks(
    val prefetch: suspend (SportsLiveItem) -> SportsLiveLookup,
    val isWarm: (SportsLiveItem) -> Boolean,
    val isCooling: (SportsLiveItem) -> Boolean,
    val verify: suspend (SportsLiveItem, Stream) -> Unit = { _, _ -> },
    /** True while a game's prefetch is running ([SportsLiveZapResolver.isPrefetching]). */
    val isPrefetching: (SportsLiveItem) -> Boolean = { false }
) {
    companion object {
        fun of(resolver: SportsLiveZapResolver) = SportsLivePrefetchHooks(
            prefetch = resolver::prefetch,
            isWarm = resolver::isWarm,
            isCooling = resolver::allSourcesCooling,
            // A link the lookup's own race just held is not probed again.
            verify = { item, stream -> if (!resolver.holds(item, stream)) resolver.verifyAndHold(item, stream) },
            isPrefetching = resolver::isPrefetching
        )
    }
}

/**
 * The light-traffic budget of every prefetch (live.20 B2), process-wide: at most [PER_MINUTE] lookups a minute, and a
 * game at most once per [PER_GAME_MS] (the resolve cache's life: a prefetch that found a link is cached that long,
 * one that found nothing is not asked again meanwhile). OK and CH± are never limited by it.
 */
class SportsLivePrefetchBudget(private val nowMs: () -> Long = { System.currentTimeMillis() }) {
    companion object {
        /**
         * Six a minute: the focused row and both zap neighbours of two zaps, or a row every 10 s while browsing; the
         * hover preview's three a minute (C1) come on top only with the preview on.
         */
        const val PER_MINUTE = 6
        const val PER_GAME_MS = SportsLiveResolveCache.TTL_MS
        private const val MAX_GAMES = 200

        val shared = SportsLivePrefetchBudget()
    }

    private val starts = ArrayDeque<Long>()
    private val lastByGame = HashMap<String, Long>()

    /** Spends a prefetch of the game [key]: false (nothing spent) when the minute's budget or the game's window is used. */
    @Synchronized
    fun tryTake(key: String): Boolean {
        val now = nowMs()
        while (starts.isNotEmpty() && now - starts.first() >= 60_000L) starts.removeFirst()
        if (starts.size >= PER_MINUTE) return false
        lastByGame[key]?.let { if (now - it < PER_GAME_MS) return false }
        starts.addLast(now)
        lastByGame[key] = now
        if (lastByGame.size > MAX_GAMES) lastByGame.values.removeAll { now - it >= PER_GAME_MS }
        return true
    }

    @VisibleForTesting
    @Synchronized
    internal fun clear() {
        starts.clear()
        lastByGame.clear()
    }
}

/**
 * Resolves streams before the viewer asks (live.20 B2), so OK and CH± start on a cached link instead of waiting for the
 * addons (17 s on the real box). A row focused for [dwellMs] is looked up ([onFocus]): focus moving on within the dwell
 * cancels it, so scrolling never sends a request. A zap's neighbours are looked up at once ([prefetchNow]). Nothing is
 * sent for a game a start would not ask for anyway ([SportsLivePrefetchHooks.isWarm]), whose every source is cooling
 * down after a rate limit, or past the [budget]. A lookup once sent is not cancelled when focus moves: the addons'
 * answer is coming anyway (the stream search is shared and outlives its callers) and fills the caches.
 */
class SportsLiveStreamPrefetcher(
    private val scope: CoroutineScope,
    private val hooks: SportsLivePrefetchHooks,
    private val budget: SportsLivePrefetchBudget = SportsLivePrefetchBudget.shared,
    private val dwellMs: Long = FOCUS_DWELL_MS
) {
    companion object {
        /**
         * A row focused this long is looked up. Scrolling a list on the D-pad moves focus every 150–300 ms (key
         * repeat; live.19 TV QA burst: ~6 rows a second), so 600 ms is a row the viewer stopped on, well before the
         * hover preview's 2 s settle.
         */
        const val FOCUS_DWELL_MS = 600L
    }

    private var focusedKey: String? = null
    private var dwell: Job? = null

    /** The row focused now (null: none, or focus left the list); a game not eligible should be passed as null. */
    fun onFocus(item: SportsLiveItem?) {
        if (item?.streamKey == focusedKey) return
        focusedKey = item?.streamKey
        dwell?.cancel()
        dwell = null
        if (item == null) return
        dwell = scope.launch {
            delay(dwellMs)
            start(item, reason = "focus", verify = false)
        }
    }

    /**
     * [items] (a zap's neighbours) looked up now; each found link is probed once ([verify]), so it is known live
     * ("▶ Ready") and its stream server has opened it once before CH± plays it.
     */
    fun prefetchNow(items: List<SportsLiveItem>, verify: Boolean = true) {
        items.distinctBy { it.streamKey }.forEach { start(it, reason = "neighbour", verify = verify) }
    }

    /** Whether [item] was sent (for tests): false when skipped. */
    @VisibleForTesting
    internal fun start(item: SportsLiveItem, reason: String, verify: Boolean): Boolean {
        val skip = when {
            hooks.isWarm(item) -> "warm"
            hooks.isCooling(item) -> "cooling"
            !budget.tryTake(item.streamKey) -> "budget"
            else -> null
        }
        if (skip != null) {
            SportsLiveDebugTiming.log { "prefetch skip=$skip why=$reason source=${SportsLiveDebugTiming.label(item.addonName)}" }
            return false
        }
        scope.launch {
            val startedAt = System.currentTimeMillis()
            val found = try {
                hooks.prefetch(item)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                SportsLiveLookup.NothingFound
            }
            SportsLiveDebugTiming.log {
                "prefetch why=$reason ms=${System.currentTimeMillis() - startedAt} outcome=${found::class.simpleName} " +
                    "source=${SportsLiveDebugTiming.label(item.addonName)}"
            }
            val stream = (found as? SportsLiveLookup.Found)?.stream ?: return@launch
            if (verify) {
                try {
                    hooks.verify(item, stream)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }
        return true
    }

    /** Stops a pending dwell (the screen left); lookups already sent finish. */
    fun stop() = onFocus(null)
}
