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

/** Where an expand's stream comes from (#157). */
internal enum class SportsLiveMiniExpandSource {
    /** The link the mini player plays now: no lookup, no link choice, the full player picks up at the live edge. */
    REUSE,

    /** A fresh lookup: the mini player's link is not playing (no first frame yet, or reconnecting). */
    RESOLVE
}

/**
 * Reuse the mini player's link while it plays ([linkPlaying]: it has drawn a frame and is not reconnecting)
 * and has a url; else look one up, as a stream that is not playing would also fail in the full player.
 */
internal fun sportsLiveMiniExpandSource(hasUrl: Boolean, linkPlaying: Boolean): SportsLiveMiniExpandSource =
    if (hasUrl && linkPlaying) SportsLiveMiniExpandSource.REUSE else SportsLiveMiniExpandSource.RESOLVE

/**
 * An expand's full player [target]. [handOver] (L, live.25): it plays the link the mini player plays now, so the mini
 * player's own player is handed over as it plays ([SportsLiveMiniPlayback.handOver]) and goes on over the full player
 * until that one's first frame, never stopped and started again; false for a looked-up stream, or a target that opens
 * the Stream screen, where the mini player stops first as before.
 */
data class SportsLiveMiniExpand(val target: SportsLiveZapTarget, val handOver: Boolean)

/**
 * Whether the mini player's player can be handed to the full player as it plays (L): its link draws frames
 * ([rendered]) and is not [reconnecting]. A link not playing would only fail or stall over the full player.
 */
internal fun sportsLiveMiniHandsOver(rendered: Boolean, reconnecting: Boolean): Boolean = rendered && !reconnecting

/**
 * Expands the mini player: the link it plays now when that plays ([sportsLiveMiniExpandSource], #157),
 * else a fresh stream for the session's current game from [resolve] (the mini player's own when none
 * comes back), then, with the zap session and the game's other sources
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

    private val _targets = Channel<SportsLiveMiniExpand>(Channel.BUFFERED)
    val targets: Flow<SportsLiveMiniExpand> = _targets.receiveAsFlow()

    private var job: Job? = null

    /** Counts expands; a cancelled lookup finishing late does not clear a newer one's dialog. */
    private var generation = 0

    fun expand(playing: SportsLiveMiniState.Playing) {
        if (job?.isActive == true) return
        val current = ++generation
        val source = sportsLiveMiniExpandSource(
            hasUrl = playing.stream.getStreamUrl() != null,
            linkPlaying = controller.isLinkPlaying(playing)
        )
        // Only a lookup shows the progress dialog.
        if (source == SportsLiveMiniExpandSource.RESOLVE) _expanding.value = playing.item
        job = scope.launch {
            try {
                val fresh = if (source == SportsLiveMiniExpandSource.RESOLVE) resolve(playing.item) else null
                val stream = fresh ?: playing.stream
                // Stopped meanwhile (Stop, another player, a profile switch): nothing opens.
                if (!controller.isCurrent(playing)) return@launch
                val target = toTarget(playing, stream, fresh != null)
                if (!controller.isCurrent(playing)) return@launch
                SportsLiveZapSessions.start(playing.session)
                SportsLiveExtraSources.register(playing.item.id, playing.item)
                // The link playing now, to the full player: its player is handed over, not restarted (L).
                _targets.send(SportsLiveMiniExpand(target, handOver = source == SportsLiveMiniExpandSource.REUSE && target.playback != null))
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
