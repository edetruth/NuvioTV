package com.nuvio.tv.ui.screens.sportslive.mini

import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.screens.sportslive.SportsLiveExtraSources
import com.nuvio.tv.ui.screens.sportslive.SportsLiveZapSessions
import com.nuvio.tv.ui.screens.sportslive.SportsLiveZapTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Expands the mini player: a fresh stream for the session's current game from [resolve] (the mini
 * player's own when none comes back), then, with the zap session and the game's other sources
 * registered as on the section's play path, the full player's target goes to [targets].
 * [expanding] is the game while its stream is looked up (the progress dialog); [cancel] (Back)
 * drops the lookup and the mini player plays on. Presses during a lookup start no second one.
 */
internal class SportsLiveMiniExpander(
    private val scope: CoroutineScope,
    private val controller: SportsLiveMiniPlayerController,
    private val resolve: suspend (SportsLiveItem) -> Stream?,
    /** The target for [stream]; [fresh]: it came from [resolve], not the mini player. */
    private val toTarget: suspend (playing: SportsLiveMiniState.Playing, stream: Stream, fresh: Boolean) -> SportsLiveZapTarget
) {
    private val _expanding = MutableStateFlow<SportsLiveItem?>(null)
    val expanding: StateFlow<SportsLiveItem?> = _expanding.asStateFlow()

    private val _targets = Channel<SportsLiveZapTarget>(Channel.BUFFERED)
    val targets: Flow<SportsLiveZapTarget> = _targets.receiveAsFlow()

    private var job: Job? = null

    /** Counts expands; a cancelled lookup finishing late does not clear a newer one's dialog. */
    private var generation = 0

    fun expand(playing: SportsLiveMiniState.Playing) {
        if (job?.isActive == true) return
        val current = ++generation
        _expanding.value = playing.item
        job = scope.launch {
            try {
                val fresh = resolve(playing.item)
                val stream = fresh ?: playing.stream
                // Stopped meanwhile (Stop, another player, a profile switch): nothing opens.
                if (!controller.isCurrent(playing)) return@launch
                val target = toTarget(playing, stream, fresh != null)
                if (!controller.isCurrent(playing)) return@launch
                SportsLiveZapSessions.start(playing.session)
                SportsLiveExtraSources.register(playing.item.id, playing.item)
                _targets.send(target)
            } finally {
                if (current == generation) _expanding.value = null
            }
        }
    }

    fun cancel() {
        generation++
        job?.cancel()
        job = null
        _expanding.value = null
    }
}
