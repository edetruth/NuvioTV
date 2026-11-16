package com.nuvio.tv.ui.screens.sportslive

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
import com.nuvio.tv.ui.screens.stream.StreamPlaybackInfo
import com.nuvio.tv.ui.screens.stream.streamPlaybackInfoFor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
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
@HiltViewModel
class SportsLiveZapViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    resolver: SportsLiveZapResolver,
    private val directDebridResolver: DirectDebridResolver,
    profileManager: ProfileManager
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
        onNavigate = { item, stream -> _events.send(SportsLiveZapEvent.Navigate(targetFor(item, stream))) },
        onNoStream = { item -> _events.trySend(SportsLiveZapEvent.NoStream(item.meta.name)) }
    )

    /** True when the key was used for a zap. */
    fun onKey(keyCode: Int): Boolean {
        val forward = zapDirection(keyCode) ?: return false
        return controller.zap(contentId, forward)
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
