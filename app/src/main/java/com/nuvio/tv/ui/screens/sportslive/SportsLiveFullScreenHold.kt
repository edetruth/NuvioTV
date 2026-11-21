package com.nuvio.tv.ui.screens.sportslive

import android.os.Handler
import android.os.Looper
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.tv.core.sportslive.SportsLiveStreamThresholds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What a held picture does next (#39). */
internal enum class SportsLiveHoldStep {
    /** The full player has no first frame yet: the held player keeps playing, its picture over the full player. */
    HOLD,

    /** The full player shows its first frame: the held player is let go, the full player takes over. */
    CROSS_OVER,

    /**
     * The full player failed (an error, or its start moved on to another link) or took longer than a link may
     * take to start: the held player is let go and the full player starts as it did before #39.
     */
    FALL_BACK
}

/**
 * #39: a game handed from a dual pane to the full player keeps playing in its pane's player until the full
 * player's first frame ([SportsLiveHoldStep.CROSS_OVER]); a full player that fails first, or never starts within
 * [capMs] (the longest a link may take to start), lets it go ([SportsLiveHoldStep.FALL_BACK]).
 */
internal fun sportsLiveHoldStep(
    firstFrame: Boolean,
    failed: Boolean,
    nextLink: Boolean,
    heldMs: Long,
    capMs: Long = SportsLiveStreamThresholds.STARTUP_HARD_CAP_MS
): SportsLiveHoldStep = when {
    firstFrame -> SportsLiveHoldStep.CROSS_OVER
    failed || nextLink || heldMs >= capMs -> SportsLiveHoldStep.FALL_BACK
    else -> SportsLiveHoldStep.HOLD
}

/**
 * Holds one player ([P]) handed over for a game's full player (#39) until [step] says to let it go; at most one at
 * a time, and a player is released exactly once ([releasePlayer]).
 */
internal open class SportsLiveHandoverHold<P : Any>(private val releasePlayer: (P) -> Unit) {

    class Held<P : Any>(val contentId: String, val player: P, val atMs: Long)

    private val _held = MutableStateFlow<Held<P>?>(null)

    /** The player held now; null when none. */
    val held: StateFlow<Held<P>?> = _held.asStateFlow()

    /** Holds [player] for [contentId]'s full player; one held before is released. */
    fun hold(contentId: String, player: P, nowMs: Long) {
        release()
        _held.value = Held(contentId, player, nowMs)
    }

    /** Releases the player held, if any. */
    fun release() {
        val held = _held.value ?: return
        _held.value = null
        releasePlayer(held.player)
    }

    /** Releases [player] if it is the one held: a late timer or error for a player already let go does nothing. */
    fun releaseIfHeld(player: P) {
        if (_held.value?.player === player) release()
    }

    /**
     * Where [contentId]'s full player is: holds on, or releases the held player on [SportsLiveHoldStep.CROSS_OVER]
     * or [SportsLiveHoldStep.FALL_BACK]. A player held for another game is released (FALL_BACK).
     */
    fun step(contentId: String?, firstFrame: Boolean, failed: Boolean, nextLink: Boolean, nowMs: Long): SportsLiveHoldStep {
        val held = _held.value ?: return SportsLiveHoldStep.FALL_BACK
        val step = if (held.contentId != contentId) {
            SportsLiveHoldStep.FALL_BACK
        } else {
            sportsLiveHoldStep(firstFrame, failed, nextLink, nowMs - held.atMs)
        }
        if (step != SportsLiveHoldStep.HOLD) release()
        return step
    }
}

/**
 * The app's one held pane player (#39): dual screen hands it the pane going full screen ([holdPlayer]), and the
 * full player shows it ([SportsLiveHeldPicture]) until its own first frame.
 */
@OptIn(UnstableApi::class)
internal object SportsLiveFullScreenHold : SportsLiveHandoverHold<ExoPlayer>(releasePlayer = { it.release() }) {

    private val main = Handler(Looper.getMainLooper())

    /**
     * Holds [player], playing [contentId] with sound, for the full player about to open. It stops handling audio
     * focus (so it keeps playing when the full player takes focus at its first frame), and it is let go if it fails
     * itself, or after the longest start a link may take when no full player ever claims it.
     */
    fun holdPlayer(contentId: String, player: ExoPlayer) {
        player.setAudioAttributes(player.audioAttributes, /* handleAudioFocus = */ false)
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                releaseIfHeld(player)
            }
        })
        hold(contentId, player, System.currentTimeMillis())
        main.postDelayed({ releaseIfHeld(player) }, SportsLiveStreamThresholds.STARTUP_HARD_CAP_MS)
    }
}

/** How often the full player is checked for its first frame while a picture is held. */
private const val HOLD_CHECK_MS = 50L

/**
 * The full player's one-line seam (#39): while a dual pane's player is held for this game ([contentId]), its picture
 * covers the full player (over its loading and starting cards) and its sound plays on; at the full player's first
 * frame ([firstFrame]) it is let go and the full player shows. [failed] or [nextLink] (its start moved on to another
 * link) let it go too, and the full player goes on as before. Leaving the player lets it go.
 */
@OptIn(UnstableApi::class)
@Composable
fun SportsLiveHeldPicture(
    contentId: String?,
    firstFrame: () -> Boolean,
    failed: Boolean,
    nextLink: Boolean,
    modifier: Modifier = Modifier
) {
    val held by SportsLiveFullScreenHold.held.collectAsStateWithLifecycle()
    val mine = held ?: return
    val currentFirstFrame by rememberUpdatedState(firstFrame)
    LaunchedEffect(mine, contentId, failed, nextLink) {
        while (
            SportsLiveFullScreenHold.step(contentId, currentFirstFrame(), failed, nextLink, System.currentTimeMillis()) ==
            SportsLiveHoldStep.HOLD
        ) {
            delay(HOLD_CHECK_MS)
        }
    }
    DisposableEffect(mine) { onDispose { SportsLiveFullScreenHold.releaseIfHeld(mine.player) } }
    if (mine.contentId != contentId) return
    // A TextureView draws in the window, so the picture sits above the full player's video surface and cards.
    Box(modifier.background(Color.Black)) {
        AndroidView(
            factory = { context -> TextureView(context).also(mine.player::setVideoTextureView) },
            onRelease = { view -> runCatching { mine.player.clearVideoTextureView(view) } },
            modifier = Modifier.fillMaxSize()
        )
    }
}
