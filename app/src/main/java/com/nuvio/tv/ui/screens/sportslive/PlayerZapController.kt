package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
    private val onNoStream: (SportsLiveItem) -> Unit
) {
    private var job: Job? = null

    /** The session of the game playing, from before the zaps whose lookup is still running. */
    private var playing: SportsLiveZapSession? = null

    /** Counts lookups, so a cancelled one does not clear [resolving] for a newer one. */
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
     * when that player has no session or the session has a single game.
     */
    fun zap(contentId: String?, forward: Boolean): Boolean {
        val inFlight = job?.isActive == true
        val base = session(contentId)?.takeIf { it.items.size > 1 } ?: return false
        if (!inFlight) playing = base
        return zapTo(if (forward) base.next() else base.previous())
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

    private fun zapTo(target: SportsLiveZapSession): Boolean {
        val revertTo = playing ?: return false
        SportsLiveZapSessions.update(target)
        job?.cancel()
        _resolving.value = target
        val lookup = ++lookups
        job = scope.launch {
            val item = target.current
            try {
                val stream = resolve(item)
                if (stream == null) {
                    revertIfCurrent(target, revertTo)
                    onNoStream(item)
                } else {
                    onNavigate(item, stream)
                }
            } catch (e: CancellationException) {
                // A newer zap has already moved the session on; a player left mid-lookup has not.
                revertIfCurrent(target, revertTo)
                throw e
            } finally {
                if (lookups == lookup) _resolving.value = null
            }
        }
        return true
    }

    private fun revertIfCurrent(target: SportsLiveZapSession, playing: SportsLiveZapSession) {
        if (SportsLiveZapSessions.current() === target) SportsLiveZapSessions.update(playing)
    }
}
