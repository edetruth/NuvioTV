package com.nuvio.tv.ui.screens.sportslive

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import coil3.compose.AsyncImagePainter
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Dark logos (live.24, design audit B5: Wake Forest, Queens, the Jazz vanish on a near-black row): a logo whose visible
 * pixels are dark on average gets a subtle light plate behind it ([LOGO_PLATE]). Measured once per logo, off the main
 * thread, on a sample of at most [SAMPLE] × [SAMPLE] pixels; a light or colourful logo is drawn as it is.
 */
internal object DarkLogos {
    /** A sample's side: enough to read a logo's tone, cheap enough for every logo on a list. */
    const val SAMPLE = 24

    /** Mean relative luminance (0..1) of the visible pixels below which a logo reads as dark on the app's near-black rows. */
    const val DARK_LUMA = 0.22

    /** Pixels at least this opaque (0..255) count; the transparent ground around a logo does not. */
    private const val VISIBLE_ALPHA = 128

    private val measured = ConcurrentHashMap<Any, Boolean>()

    /** Whether the logo loaded from [key] (its request data) was measured dark; null until measured. */
    fun known(key: Any): Boolean? = measured[key]

    /** Measures [bitmap] (once per [key]); false when it cannot be read. */
    fun measure(key: Any, bitmap: Bitmap): Boolean = measured.getOrPut(key) {
        runCatching {
            // A hardware bitmap cannot be read pixel by pixel: a small software copy can.
            val hardware = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE
            val soft = if (hardware) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
            val sample = Bitmap.createScaledBitmap(soft, SAMPLE, SAMPLE, true)
            val pixels = IntArray(SAMPLE * SAMPLE)
            sample.getPixels(pixels, 0, SAMPLE, 0, 0, SAMPLE, SAMPLE)
            isDark(pixels)
        }.getOrDefault(false)
    }

    /** [pixels] (ARGB) read dark: the mean luminance of the visible ones is under [DARK_LUMA]; false with none visible. */
    fun isDark(pixels: IntArray): Boolean {
        var sum = 0.0
        var count = 0
        for (argb in pixels) {
            val alpha = (argb ushr 24) and 0xFF
            if (alpha < VISIBLE_ALPHA) continue
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            sum += (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
            count++
        }
        return count > 0 && sum / count < DARK_LUMA
    }
}

/** The light plate behind a dark logo: soft, so it reads as the logo's ground and never as a button. */
internal val LOGO_PLATE = Color(0xE6E8ECF2)

/** Whether [painter]'s loaded logo is dark ([DarkLogos]); false until it has loaded and been measured. */
@Composable
internal fun rememberDarkLogo(painter: Painter): Boolean {
    val async = painter as? AsyncImagePainter ?: return false
    val state by async.state.collectAsState()
    val success = state as? AsyncImagePainter.State.Success ?: return false
    val key = success.result.request.data
    val dark by produceState(initialValue = DarkLogos.known(key) ?: false, key) {
        if (DarkLogos.known(key) == null) {
            value = withContext(Dispatchers.Default) { DarkLogos.measure(key, success.result.image.toBitmap()) }
        }
    }
    return dark
}
