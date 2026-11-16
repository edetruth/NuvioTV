package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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

    /**
     * Zaps from the player showing [contentId]. Returns false, leaving the key to the player,
     * when that player has no session or the session has a single game.
     */
    fun zap(contentId: String?, forward: Boolean): Boolean {
        val inFlight = job?.isActive == true
        val base = (if (inFlight) SportsLiveZapSessions.current() else SportsLiveZapSessions.activeFor(contentId))
            ?.takeIf { it.items.size > 1 }
            ?: return false
        if (!inFlight) playing = base
        return zapTo(if (forward) base.next() else base.previous())
    }

    private fun zapTo(target: SportsLiveZapSession): Boolean {
        val revertTo = playing ?: return false
        SportsLiveZapSessions.update(target)
        job?.cancel()
        job = scope.launch {
            val item = target.current
            val stream = resolve(item)
            if (stream == null) {
                if (SportsLiveZapSessions.current() === target) SportsLiveZapSessions.update(revertTo)
                onNoStream(item)
            } else {
                onNavigate(item, stream)
            }
        }
        return true
    }
}
