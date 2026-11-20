package com.nuvio.tv.ui.screens.sportslive.mini

import com.nuvio.tv.ui.screens.sportslive.noteSportsLiveLinkError
import com.nuvio.tv.core.sportslive.GameEndStrings
import com.nuvio.tv.core.sportslive.SportsLiveToastKind
import com.nuvio.tv.core.sportslive.SportsLiveToasts
import com.nuvio.tv.core.sportslive.SportsLiveGameEnd
import com.nuvio.tv.core.sportslive.SportsLiveGameWatch
import com.nuvio.tv.core.sportslive.displayName
import android.content.Context
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.animateIntSizeAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.em
import com.nuvio.tv.ui.screens.sportslive.sportsLiveBold
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import java.io.IOException
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.debrid.DirectDebridResolver
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sportslive.SportsLiveLinkErrors
import com.nuvio.tv.core.sportslive.SportsLiveLinkMarks
import com.nuvio.tv.core.sportslive.SportsLiveRecoveryLadder
import com.nuvio.tv.core.sportslive.SportsLiveStartupTimeout
import com.nuvio.tv.core.sportslive.SportsLiveStreamThresholds
import com.nuvio.tv.core.sportslive.SportsLiveSourceReliability
import com.nuvio.tv.core.sportslive.SportsLiveStreamHealth
import com.nuvio.tv.core.sportslive.SportsLiveWorkingLinks
import com.nuvio.tv.data.local.SportsLivePreferencesDataStore
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.screens.player.PlayerMediaSourceFactory
import com.nuvio.tv.ui.screens.sportslive.SportsLiveAwakeHolds
import com.nuvio.tv.ui.screens.sportslive.SportsLiveExtraSources
import com.nuvio.tv.ui.screens.sportslive.SportsLiveHealthTicker
import com.nuvio.tv.ui.screens.sportslive.SportsLiveReconnectingChip
import com.nuvio.tv.ui.screens.sportslive.SportsLiveResolvingDialog
import com.nuvio.tv.ui.screens.sportslive.SportsLiveZapResolver
import com.nuvio.tv.ui.screens.sportslive.SportsLiveZapTarget
import com.nuvio.tv.ui.screens.sportslive.buildSportsLiveLightPlayer
import com.nuvio.tv.ui.screens.sportslive.isDecoderInitFailure
import com.nuvio.tv.ui.screens.sportslive.playSportsLiveStream
import com.nuvio.tv.ui.screens.sportslive.retrySportsLiveLink
import com.nuvio.tv.ui.screens.sportslive.sportsLiveHealthSample
import com.nuvio.tv.ui.screens.sportslive.sportsLiveZapTarget
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The mini player's one ExoPlayer, built like a dual screen pane's and audible (it holds audio
 * focus like the full player). It exists only while a mini player plays; [release] frees it.
 *
 * Its stream health is watched (#138): a stream that freezes, buffers too long, ends while live or
 * fails after its first frame is loaded again once as is, then replaced by the next link for the
 * game ([lookup], with [onStreamReplaced]), keeping the last frame with "Reconnecting…"; when nothing
 * is left (or the game's recovery budget is used up) the mini player stops quietly ([onError]).
 */
@OptIn(UnstableApi::class)
internal class SportsLiveMiniPlayback(
    context: Context,
    private val onError: (sessionId: Long) -> Unit,
    private val scope: CoroutineScope? = null,
    private val lookup: suspend (item: SportsLiveItem, tried: Set<String>) -> Stream? = { _, _ -> null },
    private val onStreamReplaced: (sessionId: Long, stream: Stream) -> Unit = { _, _ -> },
    /** Whether the link draws frames now (first frame in, not reconnecting): an expand reuses it (#157). */
    private val onLinkPlaying: (sessionId: Long, playing: Boolean) -> Unit = { _, _ -> },
    /** The stream stopped as its game ended (#161): the mini player closes with a small "Final" toast. */
    private val onGameEnded: (sessionId: Long, item: SportsLiveItem) -> Unit = { id, _ -> onError(id) }
) {
    private val appContext = context.applicationContext
    private var player: ExoPlayer? = null
    private var view: PlayerView? = null
    private var loadedSessionId: Long? = null

    /** Reports [Player.isPlaying] of the current player (false once none plays): the screen stays awake meanwhile. */
    private fun reportPlaying(playing: Boolean) {
        SportsLiveAwakeHolds.shared.set(this, playing)
    }

    /** A recovery runs (#138): "Reconnecting…" shows over the last frame. */
    var reconnecting by mutableStateOf(false)
        private set

    private fun reportLink(sessionId: Long?, playing: Boolean) {
        sessionId?.let { onLinkPlaying(it, playing) }
    }

    private val health = SportsLiveStreamHealth()
    private var ladder = SportsLiveRecoveryLadder()
    private val tried = mutableSetOf<String>()
    private var item: SportsLiveItem? = null
    private var stream: Stream? = null
    private var rendered = false

    /** The start-up clock of the link loading now (#138, #158), and whether a request of it failed. */
    private var startWatch = SportsLiveStartupTimeout.Watch()
    private var startRequestFailed = false

    /** The media source factory of the link loading now: its bytes are the link's start-up progress (#158). */
    private var mediaSourceFactory: PlayerMediaSourceFactory? = null
    private var recoveryJob: Job? = null
    private val ticker = SportsLiveHealthTicker(::checkHealth)

    fun attach(view: PlayerView?) {
        if (this.view !== view) this.view?.player = null
        this.view = view
        view?.player = player
    }

    fun play(playing: SportsLiveMiniState.Playing) {
        val sessionId = playing.sessionId
        if (loadedSessionId == sessionId && player != null) return
        release()
        val stream = playing.stream
        val url = stream.getStreamUrl() ?: return onError(sessionId)
        val built = buildSportsLiveLightPlayer(appContext).apply {
            setAudioAttributes(MEDIA_AUDIO, /* handleAudioFocus = */ true)
            addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    if (player !== this@apply) return
                    rendered = true
                    reconnecting = false
                    health.onFirstFrame()
                    reportLink(loadedSessionId, true)
                    // The link plays: its "didn't work" mark clears in the stream lists (#156).
                    val shown = this@SportsLiveMiniPlayback.stream
                    this@SportsLiveMiniPlayback.item?.let { SportsLiveLinkMarks.shared.played(it.id, shown?.getStreamUrl()) }
                }

                override fun onPlayerError(error: PlaybackException) {
                    // Only this load's errors count: a released or replaced player is ignored.
                    if (player !== this@apply) return
                    // Fell behind the live window (#138): back to the live edge in place, not an error.
                    if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                        val behind = health.onBehindLiveWindow()
                        if (behind == null) retrySportsLiveLink() else recover(behind)
                        return
                    }
                    // Why the link failed, for the stream lists' marks (#166).
                    val failing = this@SportsLiveMiniPlayback.stream
                    noteSportsLiveLinkError(failing, failing?.getStreamUrl(), error)
                    val decoderInit = error.isDecoderInitFailure()
                    // A dead host (#138): the next link at once, even before the first frame.
                    if (!decoderInit && SportsLiveLinkErrors.isDeadHost(error)) {
                        recover(SportsLiveStreamHealth.Problem.DEAD_LINK)
                        return
                    }
                    val problem = health.onError()
                    // After its first frame, or on a link a recovery tries, the stream recovers (#138).
                    if ((rendered || reconnecting) && !decoderInit) recover(problem) else onError(sessionId)
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (player === this@apply) reportPlaying(isPlaying)
                }
            })
            addAnalyticsListener(object : AnalyticsListener {
                override fun onLoadError(
                    eventTime: AnalyticsListener.EventTime,
                    loadEventInfo: LoadEventInfo,
                    mediaLoadData: MediaLoadData,
                    error: IOException,
                    wasCanceled: Boolean
                ) {
                    if (player === this@apply && !rendered && !wasCanceled) startRequestFailed = true
                }
            })
        }
        player = built
        loadedSessionId = sessionId
        item = playing.item
        this.stream = stream
        rendered = false
        startWatch = SportsLiveStartupTimeout.Watch()
        startRequestFailed = false
        tried.clear()
        ladder = SportsLiveRecoveryLadder()
        health.reset()
        view?.player = built
        built.playSportsLiveStream(appContext, PlayerMediaSourceFactory(appContext).also { mediaSourceFactory = it }, stream, url)
        ticker.start()
    }

    fun release() {
        val current = player ?: return
        reportLink(loadedSessionId, false)
        ticker.stop()
        recoveryJob?.cancel()
        recoveryJob = null
        player = null
        loadedSessionId = null
        item = null
        stream = null
        reportPlaying(false)
        reconnecting = false
        view?.player = null
        current.release()
    }

    /**
     * Samples the stream's health (#138): a problem starts a recovery; moving again ends one. A link
     * with no first frame in time ([SportsLiveStartupTimeout]) is a recovery too: the next link.
     */
    private fun checkHealth() {
        val current = player ?: return
        if (!rendered) {
            // A slow link that keeps loading is not cut off (#158).
            val progress = mediaSourceFactory?.let { SportsLiveStartupTimeout.Progress(it.bytesLoaded, current.totalBufferedDuration) }
            if (recoveryJob?.isActive != true &&
                startWatch.advance(SportsLiveStreamThresholds.POLL_INTERVAL_MS, progress, startRequestFailed)
            ) {
                startWatch = SportsLiveStartupTimeout.Watch()
                recover(SportsLiveStreamHealth.Problem.NO_START)
            }
            return
        }
        val problem = health.sample(current.sportsLiveHealthSample())
        if (problem != null) {
            recover(problem)
        } else if (reconnecting && health.isProgressing) {
            reconnecting = false
            reportLink(loadedSessionId, true)
        }
    }

    /** The recovery ladder for [problem] (#138): retry as is, next link, or stop. */
    private fun recover(problem: SportsLiveStreamHealth.Problem) {
        val current = player ?: return
        val sessionId = loadedSessionId ?: return
        val game = item ?: return
        val failed = stream ?: return
        val url = failed.getStreamUrl()
        reportLink(sessionId, false)
        // The game is over (#161): no recovery, the mini player closes.
        if (SportsLiveGameWatch.shared.failureIsEnd(game.id, game) != null) {
            onGameEnded(sessionId, game)
            return
        }
        SportsLiveSourceReliability.record(
            SportsLiveExtraSources.sourceKeyFor(game, failed),
            if (problem.isStall) SportsLiveSourceReliability.Outcome.STALL else SportsLiveSourceReliability.Outcome.FAILURE
        )
        SportsLiveWorkingLinks.shared.failed(game.id, url, problem)
        SportsLiveWorkingLinks.linkKey(url)?.let(tried::add)
        when (ladder.decide(game.id, url, problem)) {
            SportsLiveRecoveryLadder.Step.RETRY_SAME -> {
                reconnecting = true
                health.rebaseline()
                current.retrySportsLiveLink()
            }
            SportsLiveRecoveryLadder.Step.NEXT_LINK -> {
                val recoveryScope = scope ?: return onError(sessionId)
                reconnecting = true
                recoveryJob?.cancel()
                recoveryJob = recoveryScope.launch {
                    val next = lookup(game, tried.toSet())
                    val nextUrl = next?.getStreamUrl()
                    if (player !== current) return@launch
                    if (next == null || nextUrl == null) {
                        onError(sessionId)
                        return@launch
                    }
                    stream = next
                    rendered = false
                    startWatch = SportsLiveStartupTimeout.Watch()
                    startRequestFailed = false
                    health.reset()
                    current.playSportsLiveStream(appContext, PlayerMediaSourceFactory(appContext).also { mediaSourceFactory = it }, next, nextUrl)
                    onStreamReplaced(sessionId, next)
                }
            }
            SportsLiveRecoveryLadder.Step.CHOICES -> onError(sessionId)
        }
    }

    private companion object {
        val MEDIA_AUDIO: AudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
    }
}

/**
 * Runs the mini player's expand ([SportsLiveMiniExpander]) with the zap resolver, and stops the
 * mini player when the active profile changes.
 */
@HiltViewModel
class SportsLiveMiniPlayerViewModel @Inject constructor(
    private val resolver: SportsLiveZapResolver,
    private val directDebridResolver: DirectDebridResolver,
    private val profileManager: ProfileManager,
    prefs: SportsLivePreferencesDataStore
) : ViewModel() {
    private val controller = SportsLiveMiniPlayers.controller

    private val expander = SportsLiveMiniExpander(
        scope = viewModelScope,
        controller = controller,
        resolve = resolver::resolve,
        toTarget = { playing, stream, fresh ->
            sportsLiveZapTarget(
                item = playing.item,
                stream = stream,
                profileId = profileManager.activeProfileId.value,
                returnToHomeOnBack = playing.session.returnToHomeOnBack,
                directDebridResolver = directDebridResolver,
                autoPicked = fresh && playing.item.alternates.isNotEmpty()
            )
        }
    )

    /** The game whose stream is looked up for an expand; the progress dialog shows meanwhile. */
    val expanding: StateFlow<SportsLiveItem?> = expander.expanding
    val targets: Flow<SportsLiveZapTarget> = expander.targets

    init {
        viewModelScope.launch {
            profileManager.activeProfileId.drop(1).collect {
                controller.stop(SportsLiveMiniStop.PROFILE_SWITCH)
                controller.dismissResume()
            }
        }
        viewModelScope.launch { controller.expandRequests.collect(expander::expand) }
        // Home reads the picture-in-picture setting synchronously, so it is mirrored here (this
        // view model lives as long as the activity).
        viewModelScope.launch { prefs.pipEnabled.collect { SportsLivePip.userEnabled = it } }
        // Every stream list reads "Hide links this TV can't play" (#166) from the link marks.
        viewModelScope.launch { prefs.hideUnplayable.collect { SportsLiveLinkMarks.shared.hideUnplayable.value = it } }
        // A mini player stopped elsewhere ends its lookup and dialog.
        viewModelScope.launch {
            controller.state.collect { if (it !is SportsLiveMiniState.Playing) expander.cancel() }
        }
    }

    /** Back on the progress dialog: the mini player plays on. */
    fun cancelExpand() = expander.cancel()

    /** The next link the mini player may switch to for [item] after [tried] failed (#138). */
    suspend fun recoveryStream(item: SportsLiveItem, tried: Set<String>): Stream? = resolver.recoveryStream(item, tried)
}

/**
 * The in-app mini player, drawn above the NavHost: bottom-right, 24% of the screen's width, 16:9,
 * with "<game> · Play/Pause full screen" under it. [onExpand] opens the full player for an expand's
 * target, after the mini player has stopped.
 *
 * It is focusable but never asks for focus: it comes after the whole app in the hierarchy, so a
 * screen's initial focus never lands on it, and only a D-pad move (Down/Right) with nothing else
 * that way reaches it (a list scrolls its items in before focus leaves it). Focused, it shows a
 * ring and "OK full screen · Back stop": OK expands, Back stops it and focus goes back up to the
 * content, and any arrow hands focus back to [contentFocusRequester] (the content, which restores
 * its last focused element) while the mini player plays on.
 */
@OptIn(UnstableApi::class)
@Composable
fun SportsLiveMiniPlayerHost(
    onExpand: (SportsLiveZapTarget) -> Unit,
    modifier: Modifier = Modifier,
    /** The app content under the mini player; requesting it gives focus back where it last was. */
    contentFocusRequester: FocusRequester? = null,
    viewModel: SportsLiveMiniPlayerViewModel = hiltViewModel()
) {
    val controller = SportsLiveMiniPlayers.controller
    val context = LocalContext.current
    val state by controller.state.collectAsStateWithLifecycle()
    val playing = state as? SportsLiveMiniState.Playing
    val recoveryScope = rememberCoroutineScope()
    val playback = remember {
        SportsLiveMiniPlayback(
            context = context,
            onError = controller::onStreamError,
            scope = recoveryScope,
            lookup = viewModel::recoveryStream,
            onStreamReplaced = controller::onStreamReplaced,
            onLinkPlaying = controller::onLinkPlaying,
            onGameEnded = { sessionId, item ->
                if (controller.isSession(sessionId)) {
                    controller.stop(SportsLiveMiniStop.GAME_ENDED)
                    val title = SportsLiveGameEnd.title(
                        item, SportsLiveGameWatch.shared.snapshot(item.id), null,
                        GameEndStrings(context.getString(R.string.sports_live_end_game_over), context.getString(R.string.sports_live_end_final_score))
                    )
                    SportsLiveToasts.show(SportsLiveToastKind.FINAL, title, item.displayName)
                }
            }
        )
    }
    val currentOnExpand by rememberUpdatedState(onExpand)
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }

    DisposableEffect(playback) {
        val release: () -> Unit = playback::release
        controller.onStopped = release
        onDispose {
            if (controller.onStopped === release) controller.onStopped = null
            playback.release()
        }
    }
    LaunchedEffect(playing?.sessionId) {
        if (playing == null) {
            playback.release()
            // Removed while focused: the next mini player starts without the focus ring.
            focused = false
        } else {
            playback.play(playing)
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.targets.collect { target ->
            // The mini player's sound stops before the full player starts.
            controller.stop(SportsLiveMiniStop.EXPANDED)
            currentOnExpand(target)
        }
    }

    val expanding by viewModel.expanding.collectAsStateWithLifecycle()
    expanding?.let { SportsLiveResolvingDialog(it, onDismiss = viewModel::cancelExpand) }

    if (playing == null) return
    // In picture-in-picture only the video shows, filling the window.
    val inPip by SportsLivePip.inPip.collectAsState()
    // Inside Sports Live, docked over the stage's media slot on every tab (V2, #132, #157), held through a tab
    // switch; else the usual corner. The one PlayerView is only moved and resized, never recreated, so the
    // decoder keeps its surface and the video flows on through a switch.
    val dock = rememberSportsLiveMiniDock()
    val stageSlot = (dock as? SportsLiveMiniDock.Docked)?.slot?.takeIf { !inPip }
    var lastDocked by remember { mutableStateOf<SportsLiveMiniSlot?>(null) }
    if (stageSlot != null) lastDocked = stageSlot
    // The host's own place in the window, so a slot (window pixels) is placed relative to it.
    var hostX by remember { mutableIntStateOf(0) }
    var hostY by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                val origin = coordinates.positionInWindow()
                hostX = origin.x.toInt()
                hostY = origin.y.toInt()
            }
            .then(if (inPip) Modifier.background(Color.Black) else Modifier)
    ) {
        // A stage at another place or size: the video glides there (its first dock lands at once).
        val docked = (stageSlot ?: lastDocked)?.let { animatedDockBounds(it, hostX, hostY) }
        // Back or the close key on the focused mini player: it stops, and focus goes back to the content first, as
        // the Now playing card's Stop; docked, to where it was in the stage or list (#157), whose preview resumes.
        val close: () -> Unit = {
            if (controller.isActive) {
                val restored = stageSlot != null && contentFocusRequester != null && runCatching {
                    contentFocusRequester.restoreFocusedChild() ||
                        contentFocusRequester.requestFocus(FocusDirection.Enter)
                }.getOrDefault(false)
                if (!restored &&
                    !focusManager.moveFocus(FocusDirection.Up) &&
                    !focusManager.moveFocus(FocusDirection.Left)
                ) {
                    focusManager.clearFocus()
                }
                controller.stop(SportsLiveMiniStop.USER_STOP)
            }
        }
        // With predictive back (target SDK 36) Back also reaches the back callbacks, where a screen's would leave
        // Sports Live: while the mini player has focus its own callback, added last, takes Back first (#157).
        if (focused && !inPip) BackHandler { close() }
        val placement = when {
            inPip -> Modifier.fillMaxSize()
            stageSlot != null && docked != null -> with(density) {
                val (dockOffset, dockSize) = docked
                Modifier
                    .offset { dockOffset }
                    .size(dockSize.width.toDp(), dockSize.height.toDp())
            }
            else -> Modifier
                .align(Alignment.BottomEnd)
                .padding(end = SportsLiveMiniGeometry.EDGE_DP.dp, bottom = SportsLiveMiniGeometry.EDGE_DP.dp)
                .width(maxWidth * SportsLiveMiniGeometry.CORNER_WIDTH_FRACTION)
        }
        Column(
            modifier = if (inPip) {
                placement
            } else {
                placement
                    .onFocusChanged { focused = it.isFocused }
                    .onKeyEvent { event ->
                        val native = event.nativeKeyEvent
                        val key = sportsLiveMiniKey(
                            keyCode = native.keyCode,
                            down = event.type == KeyEventType.KeyDown,
                            repeatCount = native.repeatCount
                        ) ?: return@onKeyEvent false
                        when (key) {
                            SportsLiveMiniKey.EXPAND -> controller.requestExpand()
                            SportsLiveMiniKey.CLOSE -> close()
                            SportsLiveMiniKey.RELEASE -> {
                                // Up: the header controls over the stage (#170's columns), when the screen names them.
                                val up = SportsLiveMiniFocus.up?.takeIf { native.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP }
                                // Else the element the content last had focused (saved as focus left it), else
                                // the content's default entry.
                                val restored = (up != null && runCatching { up.requestFocus(FocusDirection.Enter) }.getOrDefault(false)) ||
                                    contentFocusRequester != null && runCatching {
                                    contentFocusRequester.restoreFocusedChild() ||
                                        contentFocusRequester.requestFocus(FocusDirection.Enter)
                                }.getOrDefault(false)
                                if (!restored) focusManager.clearFocus()
                            }
                            SportsLiveMiniKey.CONSUME -> Unit
                        }
                        true
                    }
                    .focusRequester(SportsLiveMiniFocus.requester)
                    .focusable()
            },
            horizontalAlignment = Alignment.Start
        ) {
            val shape = RoundedCornerShape(NuvioTheme.radii.md)
            Box(
                modifier = if (inPip) {
                    Modifier.fillMaxSize()
                } else if (stageSlot != null) {
                    // The stage's media box: the same size, no shadow over the page; the ring when focused.
                    Modifier
                        .fillMaxSize()
                        .border(2.dp, if (focused) NuvioTheme.colors.FocusRing else Color.Transparent, shape)
                        .clip(shape)
                        .background(Color.Black)
                } else {
                    Modifier
                        .aspectRatio(16f / 9f)
                        .shadow(elevation = 12.dp, shape = shape)
                        // The ring sits outside the video, which would cover it.
                        .border(3.dp, if (focused) NuvioTheme.colors.FocusRing else Color.Transparent, shape)
                        .padding(3.dp)
                        .clip(shape)
                        .background(Color.Black)
                }
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            setShutterBackgroundColor(android.graphics.Color.BLACK)
                            // A recovery switching links keeps the last frame (#138).
                            setKeepContentOnPlayerReset(true)
                            isFocusable = false
                            isFocusableInTouchMode = false
                            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                        }.also(playback::attach)
                    },
                    onRelease = { playback.attach(null) },
                    modifier = Modifier.fillMaxSize()
                )
                // A recovery (#138): a small chip at the video's top-left, over the last frame.
                if (playback.reconnecting) {
                    SportsLiveReconnectingChip(small = true, modifier = Modifier.align(Alignment.TopStart).padding(6.dp))
                }
                // Docked, the PLAYING strip along the video's top (#157): which game plays, never read as the
                // stage's selected one; never over the broadcast's scorebug along the bottom.
                if (!playback.reconnecting && stageSlot != null &&
                    SportsLiveMiniGeometry.captionSpot(inStage = true) == SportsLiveMiniCaptionSpot.TOP_START
                ) {
                    WatchingStrip(
                        name = playing.item.displayName,
                        hint = stringResource(if (focused) R.string.sports_live_mini_watching_ok_hint else R.string.sports_live_mini_watching_hint),
                        modifier = Modifier.align(Alignment.TopStart).fillMaxWidth()
                    )
                }
            }
            if (!inPip && stageSlot == null) {
                MiniCaption(
                    text = stringResource(
                        if (focused) R.string.sports_live_mini_ok_hint else R.string.sports_live_mini_hint,
                        playing.item.displayName
                    ),
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

private const val DOCK_MOVE_MS = 220

/** [slot] relative to the host at [hostX], [hostY], animated as it changes; it starts where it is first asked. */
@Composable
private fun animatedDockBounds(slot: SportsLiveMiniSlot, hostX: Int, hostY: Int): Pair<IntOffset, IntSize> {
    val offset by animateIntOffsetAsState(IntOffset(slot.x - hostX, slot.y - hostY), tween(DOCK_MOVE_MS), label = "miniDockOffset")
    val size by animateIntSizeAsState(IntSize(slot.width, slot.height), tween(DOCK_MOVE_MS), label = "miniDockSize")
    return offset to size
}

/**
 * The docked mini player's label strip (#157): "PLAYING <game>" and its keys, on a scrim that is near-solid behind
 * the text and fades out below it, so the broadcast's own graphics never show through the words (live.19 F14).
 */
@Composable
private fun WatchingStrip(name: String, hint: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(
                Brush.verticalGradient(
                    0f to SportsLiveTokens.Colors.Background.copy(alpha = 0.94f),
                    0.62f to SportsLiveTokens.Colors.Background.copy(alpha = 0.88f),
                    1f to Color.Transparent
                )
            )
            .padding(start = 8.dp, end = 8.dp, top = 5.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.sports_live_mini_watching),
            style = SportsLiveTokens.style(SportsLiveTokens.Type.Tag).copy(letterSpacing = 0.08.em),
            color = SportsLiveTokens.Colors.LiveText,
            maxLines = 1
        )
        Text(
            text = name,
            style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta).sportsLiveBold(),
            color = SportsLiveTokens.Colors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Text(
            text = hint,
            style = SportsLiveTokens.style(SportsLiveTokens.Type.Tag),
            color = SportsLiveTokens.Colors.TextSecondary,
            maxLines = 1,
            softWrap = false
        )
    }
}

/** The mini player's caption ("<game> · Play/Pause full screen"), on a dark chip. */
@Composable
private fun MiniCaption(text: String, modifier: Modifier = Modifier, small: Boolean = false) {
    Text(
        text = text,
        style = if (small) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
        color = NuvioTheme.colors.TextPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(RoundedCornerShape(NuvioTheme.radii.sm))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}
