package com.nuvio.tv.ui.screens.sportslive.dual

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Dual screen opened from the full player without a black pane (live.20). The full player's ExoPlayer cannot be
 * handed to the pane: the player's controller owns it (its listeners, media source factory, cache, audio processors
 * and error recovery are bound to the player screen and released with it), so taking it over would mean changing
 * upstream's player lifecycle. Instead the full player's last frame is copied as the dual screen opens and shows in
 * the game's pane, under its starting card, until the pane's own first frame: the game stays on screen, not black.
 */
internal object SportsLiveDualHandoffFrame {

    /** A frame older than this is not shown (dual screen never opened, or opened much later). */
    const val MAX_AGE_MS = 15_000L

    /** The copy's width: the pane is at most half the screen, so a 960 px wide copy is sharp enough. */
    private const val WIDTH_PX = 960

    /** The copied frame and when it was taken. */
    class Frame(val image: ImageBitmap, val atMs: Long)

    private val _frame = MutableStateFlow<Frame?>(null)

    /** The last frame copied from the full player; null when none (or once dual screen let it go). */
    val frame: StateFlow<Frame?> = _frame.asStateFlow()

    /**
     * Copies the full player's video under [root] (the largest video surface on screen) as it plays now; the copy
     * arrives a moment later on the main thread. A secure (DRM) surface cannot be copied: no frame then.
     */
    fun capture(root: View, nowMs: Long = System.currentTimeMillis()) {
        _frame.value = null
        val target = largestVideoView(root.rootView) ?: return
        val (width, height) = copySize(target.width, target.height, WIDTH_PX) ?: return
        val bitmap = runCatching { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return
        when (target) {
            is SurfaceView -> runCatching {
                PixelCopy.request(target, bitmap, { result ->
                    if (result == PixelCopy.SUCCESS) _frame.value = Frame(bitmap.asImageBitmap(), nowMs)
                }, Handler(Looper.getMainLooper()))
            }
            is TextureView -> runCatching { target.getBitmap(bitmap) }.getOrNull()?.let {
                _frame.value = Frame(it.asImageBitmap(), nowMs)
            }
        }
    }

    /** Lets the frame go: the pane has its own picture, or dual screen closed. */
    fun clear() {
        _frame.value = null
    }

    /** The largest visible video view (a SurfaceView or TextureView) under [view], by area. */
    private fun largestVideoView(view: View): View? {
        var best: View? = null
        fun visit(node: View) {
            if (node.visibility != View.VISIBLE) return
            if (node is SurfaceView || node is TextureView) {
                val area = node.width.toLong() * node.height
                if (area > 0 && area > (best?.let { it.width.toLong() * it.height } ?: 0L)) best = node
            }
            if (node is ViewGroup) for (i in 0 until node.childCount) visit(node.getChildAt(i))
        }
        visit(view)
        return best
    }
}

/** The copy's size for a [width] × [height] view: at most [maxWidth] wide, its aspect kept; null for an empty view. */
internal fun copySize(width: Int, height: Int, maxWidth: Int): Pair<Int, Int>? {
    if (width <= 0 || height <= 0) return null
    if (width <= maxWidth) return width to height
    return maxWidth to (height.toLong() * maxWidth / width).toInt().coerceAtLeast(1)
}

/**
 * Whether [pane] shows the full player's last frame ([frame], live.20): it plays the game the frame is of
 * ([frameKey], the game dual screen opened with on the left), its link is still starting (no first frame, not
 * reconnecting, not failed or over), and the frame is fresh ([SportsLiveDualHandoffFrame.MAX_AGE_MS]).
 */
internal fun dualShowsHandoffFrame(frameAtMs: Long?, frameKey: String?, pane: DualPane, nowMs: Long): Boolean =
    frameAtMs != null && frameKey != null &&
        pane.item.streamKey == frameKey &&
        pane.status == PaneStatus.Loading && !pane.reconnecting &&
        nowMs - frameAtMs <= SportsLiveDualHandoffFrame.MAX_AGE_MS
