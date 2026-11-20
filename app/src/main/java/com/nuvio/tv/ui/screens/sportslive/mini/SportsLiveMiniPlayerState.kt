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

/**
 * Back from a paused player (#148): no mini player, but Live now offers "▶ Resume <game>" for
 * [SportsLiveMiniPlayerController.RESUME_TTL_MS]. OK rejoins [stream] (the same link) at the live edge.
 * [atMs] is when it was offered.
 */
data class SportsLiveResumeOffer(val item: SportsLiveItem, val stream: Stream, val atMs: Long) {
    fun expiredAt(nowMs: Long): Boolean = nowMs - atMs >= SportsLiveMiniPlayerController.RESUME_TTL_MS
}

/** What Back from a Sports & Live player leaves behind (#148, #175). */
enum class SportsLiveBackAction { MINI_PLAYER, RESUME_OFFER, NOTHING }

/**
 * A stream that never drew a frame ([started] false: still starting, connecting or recovering) leaves
 * nothing, so a slow link can never start playing later on a screen showing another game (#175); a
 * paused one is offered for Resume (#148); a playing one goes on in the mini player.
 */
fun sportsLiveBackAction(paused: Boolean, started: Boolean): SportsLiveBackAction = when {
    !started -> SportsLiveBackAction.NOTHING
    paused -> SportsLiveBackAction.RESUME_OFFER
    else -> SportsLiveBackAction.MINI_PLAYER
}

/** Why the mini player stopped. */
enum class SportsLiveMiniStop {
    OTHER_PLAYER, PROFILE_SWITCH, USER_STOP, IDLE_TIMEOUT, DUAL_SCREEN, EXPANDED, STREAM_ERROR, APP_FINISH, APP_BACKGROUND, PIP_CLOSED,
    /** The game ended (#161): it closed with a "Final" toast. */
    GAME_ENDED,

    /** The user went to a screen outside Sports & Live (Home, Search, Library, Settings...): it stays in the section. */
    LEFT_SECTION
}

/**
 * Whether the mini player plays on at [route]: only inside Sports & Live (its tabs, Game, Team and Player card
 * pages, setup) and the player itself (an expand or another player stops it on its own). Elsewhere it would
 * cover another screen's content with video the user can no longer act on, and on a slow box it holds a decoder
 * while the user is busy elsewhere. A null route (the app still starting) changes nothing.
 */
fun sportsLiveMiniPlaysOn(route: String?): Boolean =
    route == null || route.startsWith(SPORTS_LIVE_ROUTE_PREFIX) || route.startsWith(PLAYER_ROUTE_PREFIX)

private const val SPORTS_LIVE_ROUTE_PREFIX = "sports_live"
private const val SPORTS_LIVE_ROOT_ROUTE = "sports_live"

/**
 * Whether the mini player shows as a small "▶ Playing ‹game›" chip at [route]: on Sports & Live's own pages (Game,
 * Team, Player card, ...), whose content the corner video would cover; never on the section itself (it docks in the
 * stage there) nor in the player.
 */
fun sportsLiveMiniChipOn(route: String?): Boolean =
    route != null && route.startsWith(SPORTS_LIVE_ROUTE_PREFIX) && route != SPORTS_LIVE_ROOT_ROUTE
private const val PLAYER_ROUTE_PREFIX = "player/"

/**
 * Keys that stop the mini player from any screen, whatever has focus: MEDIA_STOP, and Back held (a long press). A
 * short Back is the screens' own; on Sports & Live's top level it stops the mini player first ([SportsLiveMiniPlayerController.stopOnBack]).
 */
fun sportsLiveMiniStopKey(keyCode: Int, down: Boolean, longPress: Boolean): Boolean = when (keyCode) {
    android.view.KeyEvent.KEYCODE_MEDIA_STOP -> down
    android.view.KeyEvent.KEYCODE_BACK -> down && longPress
    else -> false
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

        /** How long Live now offers Resume after Back from a paused player (#148). */
        const val RESUME_TTL_MS = 30 * 60 * 1000L
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

    private val _resume = MutableStateFlow<SportsLiveResumeOffer?>(null)

    /** Live now's Resume chip (#148): the paused game left, until dismissed, used or expired. */
    val resume: StateFlow<SportsLiveResumeOffer?> = _resume.asStateFlow()
    private var resumeJob: Job? = null

    val isActive: Boolean get() = _state.value is SportsLiveMiniState.Playing

    /**
     * Back from a player of [contentId]: with [session] on that content and a [stream] the mini
     * player can play, it starts; otherwise nothing changes. A player the user left [paused] (#148)
     * starts no mini player: Live now offers Resume instead, for [RESUME_TTL_MS]. One left before its
     * stream drew a frame ([started] false, #175) starts nothing at all.
     */
    fun onBackFromPlayer(
        contentId: String?,
        session: SportsLiveZapSession?,
        stream: Stream?,
        paused: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
        /** The stream drew its first frame (#175); a player left while still starting starts nothing. */
        started: Boolean = true
    ) {
        if (session == null || stream == null || contentId == null || session.current.id != contentId) return
        // Back from a player opened on Home lands on Home, where no mini player plays (BUG13): Back there is a stop.
        if (session.returnToHomeOnBack && !paused) return
        when (sportsLiveBackAction(paused, started)) {
            SportsLiveBackAction.NOTHING -> return
            SportsLiveBackAction.RESUME_OFFER -> {
                offerResume(SportsLiveResumeOffer(session.current, stream, nowMs))
                return
            }
            SportsLiveBackAction.MINI_PLAYER -> Unit
        }
        dismissResume()
        onStopped?.invoke()
        _state.value = SportsLiveMiniState.Playing(session.current, stream, ++nextSessionId, session)
        restartIdleTimer()
    }

    /** The session whose link draws frames now (#157); null while none does (loading, reconnecting). */
    private var linkPlayingSessionId: Long? = null

    /** Mini player [sessionId]'s link [playing] (first frame drawn, not reconnecting) or not. */
    fun onLinkPlaying(sessionId: Long, playing: Boolean) {
        if (playing) {
            linkPlayingSessionId = sessionId
        } else if (linkPlayingSessionId == sessionId) {
            linkPlayingSessionId = null
        }
    }

    /** True while [playing] is current and its link plays: an expand reuses it, with no lookup. */
    fun isLinkPlaying(playing: SportsLiveMiniState.Playing): Boolean =
        isCurrent(playing) && linkPlayingSessionId == playing.sessionId

    fun stop(reason: SportsLiveMiniStop) {
        if (_state.value !is SportsLiveMiniState.Playing) return
        idleJob?.cancel()
        idleJob = null
        _state.value = SportsLiveMiniState.Idle
        lastStop = reason
        onStopped?.invoke()
    }

    /** The app is on [route] now: outside Sports & Live the mini player stops (BUG13, [sportsLiveMiniPlaysOn]). */
    fun onRoute(route: String?) {
        if (!sportsLiveMiniPlaysOn(route)) stop(SportsLiveMiniStop.LEFT_SECTION)
    }

    /**
     * Back that no screen took, on a top-level screen (where it would open the menu or leave the app): it stops a
     * playing mini player instead, and returns true; the next Back does the usual. False when none plays.
     */
    fun stopOnBack(): Boolean {
        if (!isActive) return false
        stop(SportsLiveMiniStop.USER_STOP)
        return true
    }

    /** MEDIA_STOP or a held Back ([sportsLiveMiniStopKey]) while one plays: it stops; true when the key was used. */
    fun onStopKey(keyCode: Int, down: Boolean, longPress: Boolean): Boolean {
        if (!isActive || !sportsLiveMiniStopKey(keyCode, down, longPress)) return false
        stop(SportsLiveMiniStop.USER_STOP)
        return true
    }

    /** Whether [sessionId] is the mini player playing now. */
    fun isSession(sessionId: Long): Boolean = (_state.value as? SportsLiveMiniState.Playing)?.sessionId == sessionId

    /** The stream of mini player [sessionId] failed: it stops quietly. */
    fun onStreamError(sessionId: Long) {
        if ((_state.value as? SportsLiveMiniState.Playing)?.sessionId == sessionId) stop(SportsLiveMiniStop.STREAM_ERROR)
    }

    /**
     * Mini player [sessionId] switched to [stream], another link for its game, after its stream went
     * bad (#138): an expand plays that one. The session stays the same, so the player is not restarted.
     */
    fun onStreamReplaced(sessionId: Long, stream: Stream) {
        val playing = _state.value as? SportsLiveMiniState.Playing ?: return
        if (playing.sessionId == sessionId) _state.value = playing.copy(stream = stream)
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

    /** Back or ✕ on the Resume chip, OK on it, another player or a profile switch: the offer ends. */
    fun dismissResume() {
        resumeJob?.cancel()
        resumeJob = null
        _resume.value = null
    }

    private fun offerResume(offer: SportsLiveResumeOffer) {
        resumeJob?.cancel()
        _resume.value = offer
        resumeJob = scope.launch {
            delay(RESUME_TTL_MS)
            if (_resume.value === offer) _resume.value = null
        }
    }

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

private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1", "[::1]")

internal fun isLoopbackUrl(url: String): Boolean {
    val host = runCatching { java.net.URI(url).host }.getOrNull()
        ?: url.substringAfter("://").substringBefore('/').substringBeforeLast(':')
    return host.lowercase() in LOOPBACK_HOSTS || host.startsWith("127.")
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
    // A loopback url is served by the player's own local server (torrent, server streams), gone once it exits.
    if (isLoopbackUrl(url)) return null
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
