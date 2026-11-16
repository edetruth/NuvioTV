package com.nuvio.tv.ui.screens.sportslive.mini

import android.content.Context
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.screens.player.PlayerMediaSourceFactory
import com.nuvio.tv.ui.screens.sportslive.buildSportsLiveLightPlayer
import com.nuvio.tv.ui.screens.sportslive.playSportsLiveStream
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The mini player's one ExoPlayer, built like a dual screen pane's and audible (it holds audio
 * focus like the full player). It exists only while a mini player plays; [release] frees it.
 */
@OptIn(UnstableApi::class)
internal class SportsLiveMiniPlayback(context: Context, private val onError: (sessionId: Long) -> Unit) {
    private val appContext = context.applicationContext
    private var player: ExoPlayer? = null
    private var view: PlayerView? = null
    private var loadedSessionId: Long? = null

    fun attach(view: PlayerView?) {
        if (this.view !== view) this.view?.player = null
        this.view = view
        view?.player = player
    }

    fun play(sessionId: Long, stream: Stream) {
        if (loadedSessionId == sessionId && player != null) return
        release()
        val url = stream.getStreamUrl() ?: return onError(sessionId)
        val built = buildSportsLiveLightPlayer(appContext).apply {
            setAudioAttributes(MEDIA_AUDIO, /* handleAudioFocus = */ true)
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    // Only this load's errors count: a released or replaced player is ignored.
                    if (player === this@apply) onError(sessionId)
                }
            })
        }
        player = built
        loadedSessionId = sessionId
        view?.player = built
        built.playSportsLiveStream(appContext, PlayerMediaSourceFactory(appContext), stream, url)
    }

    fun release() {
        val current = player ?: return
        player = null
        loadedSessionId = null
        view?.player = null
        current.release()
    }

    private companion object {
        val MEDIA_AUDIO: AudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
    }
}

/** Stops the mini player when the active profile changes. */
@HiltViewModel
class SportsLiveMiniPlayerViewModel @Inject constructor(
    profileManager: ProfileManager
) : ViewModel() {
    init {
        viewModelScope.launch {
            profileManager.activeProfileId.drop(1).collect {
                SportsLiveMiniPlayers.controller.stop(SportsLiveMiniStop.PROFILE_SWITCH)
            }
        }
    }
}

/**
 * The in-app mini player, drawn above the NavHost: bottom-right, 24% of the screen's width, 16:9,
 * with "<game> · Play/Pause to expand" under it. It never takes focus, so the D-pad keeps working
 * the screen underneath.
 */
@OptIn(UnstableApi::class)
@Composable
fun SportsLiveMiniPlayerHost(
    modifier: Modifier = Modifier,
    viewModel: SportsLiveMiniPlayerViewModel = hiltViewModel()
) {
    val controller = SportsLiveMiniPlayers.controller
    val context = LocalContext.current
    val state by controller.state.collectAsStateWithLifecycle()
    val playing = state as? SportsLiveMiniState.Playing
    val playback = remember { SportsLiveMiniPlayback(context, controller::onStreamError) }

    DisposableEffect(playback) {
        val release: () -> Unit = playback::release
        controller.onStopped = release
        onDispose {
            if (controller.onStopped === release) controller.onStopped = null
            playback.release()
        }
    }
    LaunchedEffect(playing?.sessionId) {
        if (playing == null) playback.release() else playback.play(playing.sessionId, playing.stream)
    }

    if (playing == null) return
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .focusProperties { canFocus = false }
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 24.dp, bottom = 24.dp)
                .width(maxWidth * 0.24f),
            horizontalAlignment = Alignment.Start
        ) {
            val shape = RoundedCornerShape(NuvioTheme.radii.md)
            Box(
                modifier = Modifier
                    .aspectRatio(16f / 9f)
                    .shadow(elevation = 12.dp, shape = shape)
                    .clip(shape)
                    .background(Color.Black)
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
                            isFocusable = false
                            isFocusableInTouchMode = false
                            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                        }.also(playback::attach)
                    },
                    onRelease = { playback.attach(null) },
                    modifier = Modifier.fillMaxSize()
                )
            }
            Text(
                text = stringResource(R.string.sports_live_mini_hint, playing.item.meta.name),
                style = MaterialTheme.typography.labelMedium,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(NuvioTheme.radii.sm))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}
