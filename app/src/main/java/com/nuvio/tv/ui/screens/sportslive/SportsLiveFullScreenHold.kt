package com.nuvio.tv.ui.screens.sportslive

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.nuvio.tv.core.sportslive.SportsLiveDebugTiming
import com.nuvio.tv.core.sportslive.SportsLiveStreamThresholds
import com.nuvio.tv.ui.screens.sportslive.dual.copySize
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

    /** True while a player is held for [contentId]: its picture covers that full player (L1, live.23 review). */
    fun covers(contentId: String?): Boolean = contentId != null && _held.value?.contentId == contentId

    /**
     * The app went to the background (ON_STOP, live.23 review L2): the held player is let go at once, so the game's
     * sound never plays on behind the launcher until the timer. A configuration change keeps it.
     */
    fun onAppStop(changingConfigurations: Boolean) {
        if (!changingConfigurations) release()
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

/** The held player's last pane frame (live.23 TV test): shown full screen until its picture moves to the new view. */
internal class SportsLiveHeldStill(val player: ExoPlayer, val image: ImageBitmap)

private val heldStill = MutableStateFlow<SportsLiveHeldStill?>(null)

/**
 * The app's one held pane player (#39): dual screen hands it the pane going full screen ([holdPlayer]), and the
 * full player shows it ([SportsLiveHeldPicture]) until its own first frame.
 */
@OptIn(UnstableApi::class)
internal object SportsLiveFullScreenHold : SportsLiveHandoverHold<ExoPlayer>(
    releasePlayer = { player ->
        // Its still goes with it.
        if (heldStill.value?.player === player) heldStill.value = null
        player.release()
    }
) {

    private val main = Handler(Looper.getMainLooper())

    /** The longest side of the still copied: full screen on a 1080p TV. */
    private const val STILL_WIDTH_PX = 1920

    /** The held player's pane frame, while that player is held; drawn over its picture until the picture moves. */
    val still: StateFlow<SportsLiveHeldStill?> = heldStill.asStateFlow()

    /**
     * Copies [player]'s picture from its pane's video view ([surface]) as it shows now (live.23 TV test): the full
     * screen shows it at once, before the held player draws into its new view. A secure surface gives no copy.
     */
    fun captureStill(player: ExoPlayer, surface: View?) {
        val view: View = surface?.takeIf { it is SurfaceView || it is TextureView } ?: run {
            SportsLiveDebugTiming.log { "dualHandOver still=none (no video view)" }
            return
        }
        val (width, height) = copySize(view.width, view.height, STILL_WIDTH_PX) ?: return
        val bitmap = runCatching { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return
        val startMs = System.currentTimeMillis()
        val done: (Boolean) -> Unit = { ok ->
            SportsLiveDebugTiming.log { "dualHandOver still=${if (ok) "ok" else "failed"} ${width}x$height copyMs=${System.currentTimeMillis() - startMs}" }
            // Only while that player is held (it is by the time the copy arrives): never over another game.
            if (ok && held.value?.player === player) heldStill.value = SportsLiveHeldStill(player, bitmap.asImageBitmap())
        }
        when (view) {
            is SurfaceView -> runCatching {
                PixelCopy.request(view, bitmap, { result -> done(result == PixelCopy.SUCCESS) }, main)
            }.onFailure { done(false) }
            is TextureView -> {
                val ok = runCatching { view.getBitmap(bitmap) }.getOrNull() != null
                main.post { done(ok) }
            }
        }
    }

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
        SportsLiveDebugTiming.log { "hold start playing=${player.isPlaying} state=${player.playbackState}" }
        main.postDelayed({ releaseIfHeld(player) }, SportsLiveStreamThresholds.STARTUP_HARD_CAP_MS)
    }
}

/**
 * True while a held pane picture covers [contentId]'s full player (L1): its starting and waiting cards are not drawn
 * then, so a card the viewer cannot see never holds focus or takes OK ("Try next link", "Sources").
 */
@Composable
fun rememberSportsLiveHoldCovers(contentId: String?): Boolean {
    val held by SportsLiveFullScreenHold.held.collectAsStateWithLifecycle()
    return held != null && SportsLiveFullScreenHold.covers(contentId)
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
    val still by SportsLiveFullScreenHold.still.collectAsStateWithLifecycle()
    val mine = held ?: return
    val currentFirstFrame by rememberUpdatedState(firstFrame)
    LaunchedEffect(mine, contentId, failed, nextLink) {
        while (true) {
            val step = SportsLiveFullScreenHold.step(contentId, currentFirstFrame(), failed, nextLink, System.currentTimeMillis())
            if (step != SportsLiveHoldStep.HOLD) {
                SportsLiveDebugTiming.log { "hold release step=$step t=${System.currentTimeMillis() - mine.atMs} failed=$failed nextLink=$nextLink" }
                break
            }
            delay(HOLD_CHECK_MS)
        }
    }
    DisposableEffect(mine) { onDispose { SportsLiveFullScreenHold.releaseIfHeld(mine.player) } }
    // The held picture has drawn in its new view (live.23 TV test): until then the pane's still covers it.
    var pictureMoved by remember(mine) { mutableStateOf(false) }
    DisposableEffect(mine) {
        val sinceMs = { System.currentTimeMillis() - mine.atMs }
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (!pictureMoved) SportsLiveDebugTiming.log { "hold pictureFirstFrame t=${sinceMs()}" }
                pictureMoved = true
            }
        }
        // Debug lines: whether moving the picture restarts the held player's decoder (a stutter on some TVs).
        val analytics = object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                SportsLiveDebugTiming.log { "hold decoderInit t=${sinceMs()} initMs=$initializationDurationMs" }
            }

            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?
            ) {
                SportsLiveDebugTiming.log { "hold videoFormat t=${sinceMs()} ${format.width}x${format.height} reuse=${decoderReuseEvaluation?.result}" }
            }

            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                SportsLiveDebugTiming.log { "hold dropped=$droppedFrames t=${sinceMs()}" }
            }
        }
        runCatching {
            mine.player.addListener(listener)
            mine.player.addAnalyticsListener(analytics)
        }
        onDispose {
            runCatching {
                mine.player.removeListener(listener)
                mine.player.removeAnalyticsListener(analytics)
            }
        }
    }
    if (mine.contentId != contentId) return
    // A TextureView draws in the window, so the picture sits above the full player's video surface and cards.
    Box(modifier.background(Color.Black)) {
        AndroidView(
            factory = { context ->
                TextureView(context).also { view ->
                    mine.player.setVideoTextureView(view)
                    SportsLiveDebugTiming.log { "hold textureAttached t=${System.currentTimeMillis() - mine.atMs}" }
                }
            },
            onRelease = { view -> runCatching { mine.player.clearVideoTextureView(view) } },
            modifier = Modifier.fillMaxSize()
        )
        // The pane's last frame, full screen at once, while the held player's picture moves to its new view.
        still?.takeIf { it.player === mine.player && !pictureMoved }?.let { frame ->
            Image(bitmap = frame.image, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * Dual screen from handing a pane over until the full player shows (live.23 TV test): black over the whole screen,
 * with the kept pane's frame full screen once copied, so no pane, caption or card moves or shows on the way out.
 */
@Composable
internal fun SportsLiveHandOffCover(modifier: Modifier = Modifier) {
    val still by SportsLiveFullScreenHold.still.collectAsStateWithLifecycle()
    Box(modifier.background(Color.Black)) {
        still?.let { frame ->
            Image(bitmap = frame.image, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        }
    }
}
