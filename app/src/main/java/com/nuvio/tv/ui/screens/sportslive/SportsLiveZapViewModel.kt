package com.nuvio.tv.ui.screens.sportslive

import android.os.SystemClock
import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.debrid.DirectDebridResolver
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.repository.SportsLiveRepository
import com.nuvio.tv.ui.util.LongPressKeyTracker
import com.nuvio.tv.ui.screens.stream.StreamPlaybackInfo
import com.nuvio.tv.ui.screens.stream.streamPlaybackInfoFor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Where a zap goes. [playback] is the Player route's stream; null when the stream needs the
 * Stream screen's preparation (server, YouTube or debrid resolve), so the item's Stream screen opens.
 */
data class SportsLiveZapTarget(
    val item: SportsLiveItem,
    val playback: StreamPlaybackInfo?,
    val returnToHomeOnBack: Boolean
)

sealed interface SportsLiveZapEvent {
    data class Navigate(val target: SportsLiveZapTarget) : SportsLiveZapEvent
    data class NoStream(val name: String) : SportsLiveZapEvent
}

/** Zap state of one Player back stack entry, whose route arguments it reads. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SportsLiveZapViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    resolver: SportsLiveZapResolver,
    private val directDebridResolver: DirectDebridResolver,
    profileManager: ProfileManager,
    private val repo: SportsLiveRepository
) : ViewModel() {

    private val contentId: String? = savedStateHandle.get<String>("contentId").also { id ->
        // A player for other content ends the session; zap targets match because the
        // controller moves the session before navigating.
        SportsLiveZapSessions.keepOnlyFor(id)
    }

    // The player's profile, resolved the way PlayerViewModel resolves it.
    private val profileId: Int = savedStateHandle.get<String>("profileId")?.toIntOrNull()
        ?: profileManager.activeProfileId.value

    private val _events = Channel<SportsLiveZapEvent>(Channel.BUFFERED)
    val events: Flow<SportsLiveZapEvent> = _events.receiveAsFlow()

    private val controller = PlayerZapController(
        scope = viewModelScope,
        resolve = resolver::resolve,
        onNavigate = { item, stream ->
            val target = targetFor(item, stream)
            SportsLiveZapSessions.markZapArrival()
            _events.send(SportsLiveZapEvent.Navigate(target))
        },
        onNoStream = { item -> _events.trySend(SportsLiveZapEvent.NoStream(item.meta.name)) }
    )

    /** True when this player was opened with a zap session for its content; only then is the overlay shown. */
    val hasSession: Boolean = SportsLiveZapSessions.activeFor(contentId) != null

    // A player reached by a zap shows the banner for BANNER_MS.
    private val arrival = MutableStateFlow(
        SportsLiveZapSessions.activeFor(contentId)?.takeIf { SportsLiveZapSessions.consumeZapArrival() }
    )

    /** The zap banner: "Switching to …" while a stream is looked up, then the arrival banner. */
    val banner: StateFlow<SportsLiveZapBanner?> = combine(controller.resolving, arrival) { resolving, arrived ->
        resolving?.banner(switching = true) ?: arrived?.banner(switching = false)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // The session when the panel opened; null while it is closed.
    private val panelSession = MutableStateFlow<SportsLiveZapSession?>(null)

    private val _panelOpen = MutableStateFlow(false)
    val panelOpen: StateFlow<Boolean> = _panelOpen.asStateFlow()

    /** The live games panel's list, kept current while the panel is open. */
    val panel: StateFlow<SportsLiveZapPanelState?> = panelSession.flatMapLatest { session ->
        if (session == null) {
            flowOf(null)
        } else {
            combine(repo.latest, repo.claimedCatalogKeys, repo.endedKeys) { load, keys, ended ->
                SportsLiveZapPanelState(
                    items = liveGamesPanelItems(load?.items.orEmpty(), keys, ended, session, System.currentTimeMillis()),
                    currentKey = session.current.streamKey
                )
            }.flowOn(Dispatchers.Default)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val leftKeyTracker = LongPressKeyTracker()
    private var leftDownSeen = false
    private var leftLongPress = false

    init {
        if (arrival.value != null) {
            viewModelScope.launch {
                delay(BANNER_MS)
                arrival.value = null
            }
        }
    }

    /** True when the key was used for a zap. */
    fun onKey(keyCode: Int): Boolean {
        val forward = zapDirection(keyCode) ?: return false
        return controller.zap(contentId, forward)
    }

    /**
     * Left, pressed with the controls hidden: a short press opens the live games panel (on release).
     * A long press is left to the player, so it gets the repeats and the release.
     */
    fun onLeftKey(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_DPAD_LEFT) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            leftDownSeen = controller.session(contentId) != null
            leftLongPress = false
        }
        if (!leftDownSeen) return false
        leftKeyTracker.handle(event, { it == KeyEvent.KEYCODE_DPAD_LEFT }) { leftLongPress = true }
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> !leftLongPress
            KeyEvent.ACTION_UP -> {
                leftDownSeen = false
                if (!leftLongPress) openPanel()
                !leftLongPress
            }
            else -> false
        }
    }

    private fun openPanel() {
        val session = controller.session(contentId) ?: return
        panelSession.value = session
        _panelOpen.value = true
        viewModelScope.launch {
            try {
                repo.load(force = false, includeChannels = false)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The panel keeps showing the last load.
            }
        }
    }

    /** Closes the live games panel; false when it was not open. */
    fun closePanel(): Boolean {
        if (!_panelOpen.value) return false
        _panelOpen.value = false
        panelSession.value = null
        return true
    }

    // Uptime at which Back closed the panel; see isBackKeyOfPanelClose.
    private var panelClosedOnBackAtMs = Long.MIN_VALUE

    /**
     * Back while the panel is open closes it; false when it was not open. One Back press reaches the
     * player twice on recent Android (the back callback, then the key event), so the press is remembered.
     */
    fun closePanelOnBack(): Boolean {
        if (!closePanel()) return false
        panelClosedOnBackAtMs = SystemClock.uptimeMillis()
        return true
    }

    /** True for the key events of the Back press that has already closed the panel. */
    fun isBackKeyOfPanelClose(event: KeyEvent): Boolean =
        event.keyCode == KeyEvent.KEYCODE_BACK && event.downTime <= panelClosedOnBackAtMs

    /** [item] was picked in the live games panel: the panel closes and the player zaps to it. */
    fun onPanelPick(item: SportsLiveItem) {
        val items = panel.value?.items.orEmpty()
        closePanel()
        controller.pick(contentId, item, items)
    }

    private suspend fun targetFor(item: SportsLiveItem, stream: Stream): SportsLiveZapTarget {
        val returnToHomeOnBack = SportsLiveZapSessions.current()?.returnToHomeOnBack == true
        val needsPreparation = stream.serverTarget != null ||
            stream.youTubeIdToResolve() != null ||
            directDebridResolver.shouldResolveToPlayableStream(stream)
        val playback = if (needsPreparation) {
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
        return SportsLiveZapTarget(item, playback, returnToHomeOnBack)
    }
}

private const val BANNER_MS = 3_000L

/** true = next game, false = previous game, null = not a zap key. */
internal fun zapDirection(keyCode: Int): Boolean? = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT -> true
    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> false
    else -> null
}

/**
 * The player's zap key handler: returns true when a key (pressed with the controls hidden) zapped
 * to another Sports & Live game. Players without a session for their content get false for every key.
 */
@Composable
fun rememberSportsLiveZapKeyHandler(onZap: (SportsLiveZapTarget) -> Unit): (keyCode: Int) -> Boolean {
    val viewModel: SportsLiveZapViewModel = hiltViewModel()
    val context = LocalContext.current
    val currentOnZap by rememberUpdatedState(onZap)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is SportsLiveZapEvent.Navigate -> currentOnZap(event.target)
                is SportsLiveZapEvent.NoStream -> Toast.makeText(
                    context,
                    context.getString(R.string.sports_live_zap_no_stream, event.name),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
    return remember(viewModel) { viewModel::onKey }
}
