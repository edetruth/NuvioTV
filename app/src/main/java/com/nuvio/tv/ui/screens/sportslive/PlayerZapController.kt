package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.SportsLiveSpeedClock
import com.nuvio.tv.core.sportslive.SportsLiveStreamThresholds
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Zaps the player to the next or previous game of the [SportsLiveZapSessions] session.
 *
 * The session moves to the target at once, so repeated presses move further while a stream is
 * being looked up; a newer zap cancels the older lookup, so only the last target is played.
 * When an Up/Down zap lands on a game with no stream, it skips on in the same direction
 * ([onSkipped]), at most [SportsLiveStreamThresholds.MAX_ZAP_SKIPS] games (#138). Such a zap waits at most
 * [skipWaitMs] for a game's lookup (#158), showing "Looking for streams…" with Skip ([looking], [skip]) once it
 * runs past [lookingAfterMs]; a panel pick waits for the lookup's own cap. When nothing is
 * found (or a game picked from the panel has no stream), the session goes back to the game still playing.
 */
class PlayerZapController(
    private val scope: CoroutineScope,
    private val resolve: suspend (SportsLiveItem) -> Stream?,
    private val onNavigate: suspend (SportsLiveItem, Stream) -> Unit,
    private val onNoStream: (SportsLiveItem) -> Unit,
    /** A zap skipped [SportsLiveItem], which had no stream, and goes on to the next game (#138). */
    private val onSkipped: (SportsLiveItem) -> Unit = {},
    /** Stream keys of the games known to have ended, which zaps skip. */
    private val endedKeys: () -> Set<String> = { emptySet() },
    /** Ids of the games tried and failed this session ([com.nuvio.tv.core.sportslive.SportsLiveTriedGames]): auto-skips pass them. */
    private val triedIds: () -> Set<String> = { emptySet() },
    /**
     * The live games panel's one ordered list (#142), as the panel shows it (or would, while closed); null
     * while it is not known. Zaps and picks walk it ([SportsLiveZapSession.withLineup]).
     */
    private val lineup: () -> List<ZapPanelRow>? = { null },
    /**
     * How long a flip must settle before its stream is resolved. Flipping on cancels the previous
     * flip's job during this window, so skipping past channels to land on one costs a single resolve
     * instead of one per channel.
     */
    private val settleMs: Long = SETTLE_MS,
    /** A CH± zap gives up on a slow lookup after this long and skips on (#158); a pick waits for the lookup. */
    private val skipWaitMs: Long = SportsLiveStreamThresholds.ZAP_SKIP_WAIT_MS,
    /** A CH± zap's lookup running this long shows the Looking for streams card. */
    private val lookingAfterMs: Long = SportsLiveStreamThresholds.ZAP_LOOKING_CARD_AFTER_MS,
    /** True while [SportsLiveItem]'s prefetch is running (live.20 B2): a CH± zap on it waits past [skipWaitMs]. */
    private val prefetching: (SportsLiveItem) -> Boolean = { false },
    /**
     * How long in all a CH± zap waits on a game whose prefetch is running: the lookup's own hard cap, which the
     * prefetch (started earlier) reaches first, so the wait never outlasts the lookup it joined.
     */
    private val prefetchWaitMs: Long = SportsLiveStreamThresholds.LOOKUP_HARD_CAP_MS
) {
    companion object {
        const val SETTLE_MS = 600L
    }

    private var job: Job? = null

    /** The session of the game playing, from before the zaps whose lookup is still running. */
    private var playing: SportsLiveZapSession? = null

    /** The session moved to the game whose lookup runs: a chip picked meanwhile is stored on it. */
    private var target: SportsLiveZapSession? = null

    /** Counts lookups, so a cancelled one does not clear [resolving] or revert for a newer one. */
    private var lookups = 0

    private val _resolving = MutableStateFlow<SportsLiveZapSession?>(null)

    /** The session moved to the game whose stream is being looked up; null when no lookup runs. */
    val resolving: StateFlow<SportsLiveZapSession?> = _resolving.asStateFlow()

    private val _looking = MutableStateFlow<SportsLiveItem?>(null)

    /** A CH± zap's game whose lookup is slow (#158): "Looking for streams…" shows, Skip moves on; null otherwise. */
    val looking: StateFlow<SportsLiveItem?> = _looking.asStateFlow()

    /** Completed by [skip]: the slow lookup in progress is given up and the zap moves on. */
    private var skipRequest: CompletableDeferred<Unit>? = null

    /** Skip on the Looking for streams card: the zap moves on to the next game now. False when no card shows. */
    fun skip(): Boolean {
        if (_looking.value == null) return false
        return skipRequest?.complete(Unit) == true
    }

    /**
     * The session the player showing [contentId] zaps from: while a lookup runs, the session already
     * moved to its target; null when that player has no session.
     */
    fun session(contentId: String?): SportsLiveZapSession? =
        if (job?.isActive == true) SportsLiveZapSessions.current() else SportsLiveZapSessions.activeFor(contentId)

    /**
     * Zaps from the player showing [contentId]. Returns false, leaving the key to the player,
     * when that player has no session or every other game of the session is known to have ended.
     */
    fun zap(contentId: String?, forward: Boolean): Boolean {
        val inFlight = job?.isActive == true
        val base = session(contentId)?.let(::ordered) ?: return false
        val target = base.step(forward, endedKeys()) ?: return false
        if (!inFlight) playing = base
        SportsLiveSpeedClock.action("zap")
        return zapTo(target, direction = forward)
    }

    /**
     * Zaps to [item], picked in the live games panel listing [panelItems]. Picking the game still
     * playing only cancels a running lookup. Returns false when nothing was zapped.
     */
    fun pick(contentId: String?, item: SportsLiveItem, panelItems: List<SportsLiveItem>): Boolean {
        val inFlight = job?.isActive == true
        val base = session(contentId)?.let(::ordered) ?: return false
        if (!inFlight) playing = base
        if (item.streamKey == playing?.current?.streamKey) {
            job?.cancel()
            return false
        }
        if (inFlight && item.streamKey == base.current.streamKey) return true
        SportsLiveSpeedClock.action("pick")
        return zapTo(base.pickFromPanel(item, panelItems) ?: return false)
    }

    /**
     * A chip ([filter], null is All) was picked in the live games panel of the player showing
     * [contentId]: from now on Up/Down follow it (#68). A running lookup goes on: its target takes
     * the chip, and so does the game playing, should the lookup find no stream.
     */
    fun pickChip(contentId: String?, filter: String?) {
        val chip = ZapPanelChip(filter)
        if (job?.isActive == true) {
            val moved = target?.takeIf { SportsLiveZapSessions.current() === it } ?: return
            // The zap order is taken afresh under the new chip (BUG6/Q10: frozen until then).
            playing = playing?.copy(panelChip = chip, lineup = null)
            target = moved.copy(panelChip = chip, lineup = null).also(SportsLiveZapSessions::update)
            return
        }
        val base = SportsLiveZapSessions.activeFor(contentId) ?: return
        SportsLiveZapSessions.update(base.copy(panelChip = chip, lineup = null))
    }

    /**
     * The live games panel opened over the player showing [contentId]: the zap order is taken afresh from the list
     * it shows (BUG6/Q10: until then CH± keep the order frozen at the first zap). Nothing changes while a lookup runs.
     */
    fun thaw(contentId: String?) {
        if (job?.isActive == true) return
        val base = SportsLiveZapSessions.activeFor(contentId) ?: return
        if (base.lineup != null) SportsLiveZapSessions.update(base.copy(lineup = null))
    }

    /**
     * "Choose stream" on [item] in the live games panel listing [panelItems] (#140): any lookup stops and the
     * session moves to [item], whose Stream screen the player then opens. Null when that player has no session.
     */
    fun chooseStream(contentId: String?, item: SportsLiveItem, panelItems: List<SportsLiveItem>): SportsLiveZapSession? {
        val base = session(contentId)?.let(::ordered) ?: return null
        val moved = base.pickFromPanel(item, panelItems) ?: return null
        job?.cancel()
        SportsLiveZapSessions.update(moved)
        return moved
    }

    /** The game a CH± zap ([forward]: CH−) from the player showing [contentId] would land on first (#147); null when none. */
    fun nextTarget(contentId: String?, forward: Boolean): SportsLiveItem? =
        session(contentId)?.let(::ordered)?.step(forward, endedKeys())?.current

    /**
     * [session] on the panel's ordered list, when it is known (#142), frozen once taken (BUG6/Q10): later list changes
     * add new games at the end and pass over games gone, but never move a game, so ▲/▼ and the banner's names stay
     * true. The order is taken afresh when the panel opens ([thaw]) or a chip is picked ([pickChip]).
     */
    internal fun ordered(session: SportsLiveZapSession): SportsLiveZapSession =
        lineup()?.let(session::withFrozenLineup) ?: session

    /** [direction]: an Up/Down zap's (true = next), which skips games with no stream; null for a panel pick. */
    private fun zapTo(target: SportsLiveZapSession, direction: Boolean? = null): Boolean {
        if (playing == null) return false
        SportsLiveZapSessions.update(target)
        this.target = target
        job?.cancel()
        _resolving.value = target
        val lookup = ++lookups
        job = scope.launch {
            try {
                // Settle first: a newer flip cancels this job here, so skipped channels never resolve.
                if (settleMs > 0) delay(settleMs)
                SportsLiveSpeedClock.stage("settled")
                var landed = target
                var skips = 0
                val skipped = mutableSetOf<String>()
                while (true) {
                    val item = landed.current
                    val stream = if (direction == null) resolve(item) else resolveOrSkip(item)
                    SportsLiveSpeedClock.stage("resolved", "found=${stream != null} skips=$skips")
                    // A newer zap or pick made this lookup stale (#175): its result never plays.
                    if (lookups != lookup) break
                    if (stream != null) {
                        SportsLiveSpeedClock.stage("navigate")
                        onNavigate(item, stream)
                        break
                    }
                    // No stream: an Up/Down zap skips on in its direction, never back to the game playing.
                    skipped += item.streamKey
                    item.alternates.mapTo(skipped) { it.streamKey }
                    val next = direction
                        ?.takeIf { skips < SportsLiveStreamThresholds.MAX_ZAP_SKIPS }
                        ?.let { landed.step(it, endedKeys() + skipped + triedKeysOf(landed)) }
                        ?.takeUnless { it.current.streamKey == playing?.current?.streamKey }
                    if (next == null || SportsLiveZapSessions.current() !== landed) {
                        revertIfCurrent(lookup)
                        onNoStream(item)
                        break
                    }
                    skips++
                    onSkipped(item)
                    SportsLiveZapSessions.update(next)
                    this@PlayerZapController.target = next
                    _resolving.value = next
                    landed = next
                }
            } catch (e: CancellationException) {
                // A newer zap has already moved the session on; a player left mid-lookup has not.
                revertIfCurrent(lookup)
                throw e
            } finally {
                if (lookups == lookup) _resolving.value = null
            }
        }
        return true
    }

    /**
     * [item]'s stream for a CH± zap: null when its lookup finds none, takes over [skipWaitMs] (over [prefetchWaitMs]
     * when its prefetch is running), or Skip is pressed. The card shows from [lookingAfterMs] on; the addons keep
     * answering in the background for the next lookup.
     */
    private suspend fun resolveOrSkip(item: SportsLiveItem): Stream? = coroutineScope {
        val skipped = CompletableDeferred<Unit>()
        skipRequest = skipped
        val found = async { resolve(item) }
        val card = launch {
            delay(lookingAfterMs)
            _looking.value = item
        }
        suspend fun waitFor(ms: Long): Stream? = withTimeoutOrNull(ms) {
            select<Stream?> {
                found.onAwait { it }
                skipped.onAwait { null }
            }
        }
        try {
            val stream = waitFor(skipWaitMs)
            // B2: a game being prefetched is about to answer (its addons were asked earlier): skipping it now would
            // throw that answer away. Keep waiting, Skip still works, up to the prefetch's own cap.
            if (stream == null && !found.isCompleted && !skipped.isCompleted && prefetching(item)) {
                SportsLiveSpeedClock.stage("waitPrefetch")
                waitFor((prefetchWaitMs - skipWaitMs).coerceAtLeast(0L))
            } else {
                stream
            }
        } finally {
            card.cancel()
            found.cancel()
            if (skipRequest === skipped) skipRequest = null
            _looking.value = null
        }
    }

    /** The stream keys of [session]'s games tried and failed this session. */
    private fun triedKeysOf(session: SportsLiveZapSession): Set<String> {
        val ids = triedIds()
        if (ids.isEmpty()) return emptySet()
        return session.items.filter { item -> item.id in ids || item.alternates.any { it.id in ids } }
            .flatMapTo(HashSet()) { item -> listOf(item.streamKey) + item.alternates.map { it.streamKey } }
    }

    /** Back to the game playing (with any chip picked since), unless a newer zap moved the session on. */
    private fun revertIfCurrent(lookup: Int) {
        val revertTo = playing ?: return
        if (lookups == lookup && SportsLiveZapSessions.current() === target) SportsLiveZapSessions.update(revertTo)
    }
}
