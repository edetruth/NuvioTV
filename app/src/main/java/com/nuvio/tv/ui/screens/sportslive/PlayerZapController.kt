package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Zaps the player to the next or previous game of the [SportsLiveZapSessions] session.
 *
 * The session moves to the target at once, so repeated presses move further while a stream is
 * being looked up; a newer zap cancels the older lookup, so only the last target is played.
 * When the target has no stream, the session goes back to the game still playing.
 */
class PlayerZapController(
    private val scope: CoroutineScope,
    private val resolve: suspend (SportsLiveItem) -> Stream?,
    private val onNavigate: suspend (SportsLiveItem, Stream) -> Unit,
    private val onNoStream: (SportsLiveItem) -> Unit,
    /** Stream keys of the games known to have ended, which zaps skip. */
    private val endedKeys: () -> Set<String> = { emptySet() },
    /**
     * How long a flip must settle before its stream is resolved. Flipping on cancels the previous
     * flip's job during this window, so skipping past channels to land on one costs a single resolve
     * instead of one per channel.
     */
    private val settleMs: Long = SETTLE_MS
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
        val base = session(contentId) ?: return false
        val target = base.step(forward, endedKeys()) ?: return false
        if (!inFlight) playing = base
        return zapTo(target)
    }

    /**
     * Zaps to [item], picked in the live games panel listing [panelItems]. Picking the game still
     * playing only cancels a running lookup. Returns false when nothing was zapped.
     */
    fun pick(contentId: String?, item: SportsLiveItem, panelItems: List<SportsLiveItem>): Boolean {
        val inFlight = job?.isActive == true
        val base = session(contentId) ?: return false
        if (!inFlight) playing = base
        if (item.streamKey == playing?.current?.streamKey) {
            job?.cancel()
            return false
        }
        if (inFlight && item.streamKey == base.current.streamKey) return true
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
            playing = playing?.copy(panelChip = chip)
            target = moved.copy(panelChip = chip).also(SportsLiveZapSessions::update)
            return
        }
        val base = SportsLiveZapSessions.activeFor(contentId) ?: return
        SportsLiveZapSessions.update(base.copy(panelChip = chip))
    }

    private fun zapTo(target: SportsLiveZapSession): Boolean {
        if (playing == null) return false
        SportsLiveZapSessions.update(target)
        this.target = target
        job?.cancel()
        _resolving.value = target
        val lookup = ++lookups
        job = scope.launch {
            val item = target.current
            try {
                // Settle first: a newer flip cancels this job here, so skipped channels never resolve.
                if (settleMs > 0) delay(settleMs)
                val stream = resolve(item)
                if (stream == null) {
                    revertIfCurrent(lookup)
                    onNoStream(item)
                } else {
                    onNavigate(item, stream)
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

    /** Back to the game playing (with any chip picked since), unless a newer zap moved the session on. */
    private fun revertIfCurrent(lookup: Int) {
        val revertTo = playing ?: return
        if (lookups == lookup && SportsLiveZapSessions.current() === target) SportsLiveZapSessions.update(revertTo)
    }
}
