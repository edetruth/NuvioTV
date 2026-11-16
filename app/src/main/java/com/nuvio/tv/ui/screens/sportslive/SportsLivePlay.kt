package com.nuvio.tv.ui.screens.sportslive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.debrid.DirectDebridResolver
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.screens.stream.streamPlaybackInfoFor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What OK on a Sports & Live card does. */
enum class SportsLivePlayAction {
    /** A single-source card: the item's Stream screen, as upstream. */
    STREAM_SCREEN,

    /** A merged card with autoplay: the best stream across its sources plays at once. */
    PLAY_BEST,

    /** A merged card in MANUAL mode: the user picks from every source's streams. */
    PICK
}

fun sportsLivePlayAction(item: SportsLiveItem, mode: StreamAutoPlayMode): SportsLivePlayAction = when {
    item.alternates.isEmpty() -> SportsLivePlayAction.STREAM_SCREEN
    mode == StreamAutoPlayMode.MANUAL -> SportsLivePlayAction.PICK
    else -> SportsLivePlayAction.PLAY_BEST
}

/** True when [stream] needs the Stream screen's preparation (server, YouTube or debrid resolve). */
internal suspend fun sportsLiveNeedsPreparation(stream: Stream, directDebridResolver: DirectDebridResolver): Boolean =
    stream.serverTarget != null ||
        stream.youTubeIdToResolve() != null ||
        directDebridResolver.shouldResolveToPlayableStream(stream)

/**
 * Where playing [stream] for [item] goes: the Player route, or (null playback) the item's Stream
 * screen when the stream needs that screen's preparation (server, YouTube or debrid resolve).
 */
internal suspend fun sportsLiveZapTarget(
    item: SportsLiveItem,
    stream: Stream,
    profileId: Int,
    returnToHomeOnBack: Boolean,
    directDebridResolver: DirectDebridResolver,
    autoPicked: Boolean = false
): SportsLiveZapTarget {
    val playback = if (sportsLiveNeedsPreparation(stream, directDebridResolver)) {
        null
    } else {
        // Same mapping as the Stream screen's getStreamForPlayback, with the item as the content.
        streamPlaybackInfoFor(
            stream = stream,
            title = item.meta.name,
            year = null,
            contentId = item.id,
            contentType = item.apiType,
            contentName = item.meta.name,
            poster = item.meta.poster,
            backdrop = item.meta.backdropUrl,
            logo = item.meta.logo,
            videoId = item.id,
            season = null,
            episode = null,
            episodeTitle = null,
            profileId = profileId,
            contentLanguage = null
        ).takeIf { it.url != null || it.isTorrent }
    }
    return SportsLiveZapTarget(item, playback, returnToHomeOnBack, autoPicked)
}

sealed interface SportsLivePlayState {
    data object Idle : SportsLivePlayState
    data class Resolving(val item: SportsLiveItem) : SportsLivePlayState
    data class Picking(val item: SportsLiveItem, val streams: List<Stream>) : SportsLivePlayState
}

sealed interface SportsLivePlayEvent {
    data class OpenStreamScreen(val item: SportsLiveItem) : SportsLivePlayEvent
    data class Play(val target: SportsLiveZapTarget) : SportsLivePlayEvent
    data class NoStream(val name: String) : SportsLivePlayEvent
}

/**
 * OK on a section card or a Home Live now card: single-source cards open the Stream screen; merged
 * cards play across sources. The screen shows [state] and runs [events] with [SportsLivePlayHost].
 */
@HiltViewModel
class SportsLivePlayViewModel @Inject constructor(
    private val resolver: SportsLiveZapResolver,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val directDebridResolver: DirectDebridResolver,
    private val profileManager: ProfileManager
) : ViewModel() {

    private val _state = MutableStateFlow<SportsLivePlayState>(SportsLivePlayState.Idle)
    val state: StateFlow<SportsLivePlayState> = _state.asStateFlow()

    private val _events = Channel<SportsLivePlayEvent>(Channel.BUFFERED)
    val events: Flow<SportsLivePlayEvent> = _events.receiveAsFlow()

    private var job: Job? = null

    /** Whether Back from the played stream returns to Home (the Home Live now row) rather than the section. */
    private var returnToHomeOnBack = false

    fun play(item: SportsLiveItem, returnToHomeOnBack: Boolean = false) {
        if (item.alternates.isEmpty()) {
            _events.trySend(SportsLivePlayEvent.OpenStreamScreen(item))
            return
        }
        job?.cancel()
        this.returnToHomeOnBack = returnToHomeOnBack
        _state.value = SportsLivePlayState.Resolving(item)
        job = viewModelScope.launch {
            val mode = playerSettingsDataStore.playerSettings.first().streamAutoPlayMode
            // STREAM_SCREEN is only for single-source cards, handled above.
            when (sportsLivePlayAction(item, mode)) {
                SportsLivePlayAction.STREAM_SCREEN, SportsLivePlayAction.PLAY_BEST -> {
                    val stream = resolver.resolve(item)
                    _state.value = SportsLivePlayState.Idle
                    if (stream == null) noStream(item) else playStream(item, stream, autoPicked = true)
                }
                SportsLivePlayAction.PICK -> {
                    val streams = resolver.resolveAll(item)
                    if (streams.isEmpty()) {
                        _state.value = SportsLivePlayState.Idle
                        noStream(item)
                    } else {
                        _state.value = SportsLivePlayState.Picking(item, streams)
                    }
                }
            }
        }
    }

    /** A stream picked in the picker plays. */
    fun pick(stream: Stream) {
        val picking = _state.value as? SportsLivePlayState.Picking ?: return
        _state.value = SportsLivePlayState.Idle
        job = viewModelScope.launch { playStream(picking.item, stream) }
    }

    /**
     * Back on the picker or while a stream is looked up, or the screen stopped: nothing started
     * before plays later, including an event not yet collected.
     */
    fun dismiss() {
        job?.cancel()
        _state.value = SportsLivePlayState.Idle
        while (_events.tryReceive().isSuccess) Unit
    }

    private suspend fun playStream(item: SportsLiveItem, stream: Stream, autoPicked: Boolean = false) {
        val target = sportsLiveZapTarget(
            item = item,
            stream = stream,
            profileId = profileManager.activeProfileId.value,
            returnToHomeOnBack = returnToHomeOnBack,
            directDebridResolver = directDebridResolver,
            autoPicked = autoPicked
        )
        // The player (or the primary's Stream screen) gets the other addons' streams for fallback and Sources.
        SportsLiveExtraSources.register(item.id, item)
        _events.send(SportsLivePlayEvent.Play(target))
    }

    private suspend fun noStream(item: SportsLiveItem) {
        _events.send(SportsLivePlayEvent.NoStream(item.meta.name))
    }
}
