package com.nuvio.tv.ui.screens.sportslive.mini

import com.nuvio.tv.domain.model.ProxyHeaders
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.ui.screens.sportslive.SportsLiveZapSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SportsLiveMiniState {
    data object Idle : SportsLiveMiniState

    /**
     * [stream] (the full player's stream when Back was pressed) of [item] plays in the corner.
     * [session] is the zap session the full player had, restored on expand. Each start has its own
     * [sessionId], so a late event of an earlier mini player does not touch this one.
     */
    data class Playing(
        val item: SportsLiveItem,
        val stream: Stream,
        val sessionId: Long,
        val session: SportsLiveZapSession
    ) : SportsLiveMiniState
}

/** Why the mini player stopped. */
enum class SportsLiveMiniStop {
    OTHER_PLAYER, PROFILE_SWITCH, USER_STOP, IDLE_TIMEOUT, DUAL_SCREEN, HOVER_PREVIEW, EXPANDED, STREAM_ERROR, APP_FINISH
}

/**
 * The in-app mini player's state. It starts only on Back from a player that had a Sports & Live
 * zap session for its content, so movies and series never start it. Any [stop] ends it, as does
 * [idleTimeoutMs] without [onInteraction]. [onStopped] (the host's player release) runs inside
 * [stop], so the mini player's sound is gone before whatever stopped it starts its own.
 * Main thread only.
 */
class SportsLiveMiniPlayerController(
    private val scope: CoroutineScope,
    private val idleTimeoutMs: Long = IDLE_TIMEOUT_MS
) {
    companion object {
        const val IDLE_TIMEOUT_MS = 2 * 60 * 60 * 1000L
    }

    private val _state = MutableStateFlow<SportsLiveMiniState>(SportsLiveMiniState.Idle)
    val state: StateFlow<SportsLiveMiniState> = _state.asStateFlow()

    private val _expandRequests = MutableSharedFlow<SportsLiveMiniState.Playing>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** The mini player to expand, from Play/Pause or the Now playing card's Expand. */
    val expandRequests: SharedFlow<SportsLiveMiniState.Playing> = _expandRequests.asSharedFlow()

    /** Set by the host while it holds a player: frees it at once. */
    var onStopped: (() -> Unit)? = null

    /** The reason of the last [stop], for tests. */
    var lastStop: SportsLiveMiniStop? = null
        private set

    private var nextSessionId = 0L
    private var idleJob: Job? = null

    val isActive: Boolean get() = _state.value is SportsLiveMiniState.Playing

    /**
     * Back from a player of [contentId]: with [session] on that content and a [stream] the mini
     * player can play, it starts; otherwise nothing changes.
     */
    fun onBackFromPlayer(contentId: String?, session: SportsLiveZapSession?, stream: Stream?) {
        if (session == null || stream == null || contentId == null || session.current.id != contentId) return
        onStopped?.invoke()
        _state.value = SportsLiveMiniState.Playing(session.current, stream, ++nextSessionId, session)
        restartIdleTimer()
    }

    fun stop(reason: SportsLiveMiniStop) {
        if (_state.value !is SportsLiveMiniState.Playing) return
        idleJob?.cancel()
        idleJob = null
        _state.value = SportsLiveMiniState.Idle
        lastStop = reason
        onStopped?.invoke()
    }

    /** The stream of mini player [sessionId] failed: it stops quietly. */
    fun onStreamError(sessionId: Long) {
        if ((_state.value as? SportsLiveMiniState.Playing)?.sessionId == sessionId) stop(SportsLiveMiniStop.STREAM_ERROR)
    }

    /** A key press anywhere in the app: the idle timeout starts over. */
    fun onInteraction() {
        if (isActive) restartIdleTimer()
    }

    /** Asks the host to expand the mini player; false (key not handled) when none plays. */
    fun requestExpand(): Boolean {
        val playing = _state.value as? SportsLiveMiniState.Playing ?: return false
        _expandRequests.tryEmit(playing)
        return true
    }

    /** True while [playing] is still the mini player on screen (an expand may go on). */
    fun isCurrent(playing: SportsLiveMiniState.Playing): Boolean =
        (_state.value as? SportsLiveMiniState.Playing)?.sessionId == playing.sessionId

    private fun restartIdleTimer() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(idleTimeoutMs)
            stop(SportsLiveMiniStop.IDLE_TIMEOUT)
        }
    }
}

/** The app's one mini player state, process-wide like the zap session; lost on process death. */
object SportsLiveMiniPlayers {
    val controller: SportsLiveMiniPlayerController by lazy {
        SportsLiveMiniPlayerController(MainScope())
    }
}

/**
 * The stream the full player plays now as a [Stream] the mini player can play, or null when it is
 * not a plain http(s) url (a torrent, or nothing).
 */
internal fun sportsLiveMiniStream(
    url: String?,
    headers: Map<String, String>,
    responseHeaders: Map<String, String>,
    filename: String?,
    name: String?,
    addonName: String?
): Stream? {
    if (url == null || !(url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true))) {
        return null
    }
    return Stream(
        name = name,
        title = null,
        description = null,
        url = url,
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null,
            bingeGroup = null,
            countryWhitelist = null,
            proxyHeaders = ProxyHeaders(request = headers.ifEmpty { null }, response = responseHeaders.ifEmpty { null }),
            filename = filename
        ),
        addonName = addonName.orEmpty(),
        addonLogo = null
    )
}
