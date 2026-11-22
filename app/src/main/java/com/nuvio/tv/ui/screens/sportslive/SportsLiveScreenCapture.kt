package com.nuvio.tv.ui.screens.sportslive

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.annotation.RequiresApi
import com.nuvio.tv.ui.screens.sportslive.dual.copySize
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

/**
 * The screenshot a remote Report a problem saves next to its report (live.25): the app's own window as it shows, copied
 * with PixelCopy (Android 8+, no permission). A window copy leaves each video SurfaceView out (its picture is a layer of
 * its own, a see-through hole in the window), so every visible SurfaceView is copied too and drawn under the window:
 * the shot shows the video as on screen. A secure (DRM) video cannot be copied and stays black. Nothing is shared: the
 * reporter keeps the shot next to the report only ([com.nuvio.tv.core.sportslive.report.SportsLiveReportFiles.saveShot]).
 */
object SportsLiveScreenCapture {
    /** The shot's width at most: sharp enough to read a row on a 1080p or 4K TV, and a small JPEG. */
    const val MAX_WIDTH_PX = 1280

    @Volatile private var resumed: WeakReference<Activity>? = null
    private val main = Handler(Looper.getMainLooper())

    /** Follows the activity on screen; once, from [SportsLiveCrashCapture.install]. */
    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumed = WeakReference(activity)
            }

            override fun onActivityPaused(activity: Activity) {
                if (resumed?.get() === activity) resumed = null
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /**
     * The screen copied [delayMs] from now (at once on the main thread for 0); null when there is nothing to copy or
     * the copy fails. Any thread; never throws.
     */
    fun take(delayMs: Long = 0L): Deferred<Bitmap?> {
        val out = CompletableDeferred<Bitmap?>()
        val start = Runnable {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) copy(out) else out.complete(null)
            } catch (_: Exception) {
                out.complete(null)
            }
        }
        if (delayMs <= 0L && Looper.myLooper() == Looper.getMainLooper()) start.run() else main.postDelayed(start, delayMs.coerceAtLeast(0L))
        return out
    }

    private class Video(val view: SurfaceView, val rect: Rect, val bitmap: Bitmap) {
        var ok = false
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun copy(out: CompletableDeferred<Bitmap?>) {
        val window: Window = resumed?.get()?.window ?: run { out.complete(null); return }
        val decor = window.peekDecorView() ?: run { out.complete(null); return }
        val (width, height) = copySize(decor.width, decor.height, MAX_WIDTH_PX) ?: run { out.complete(null); return }
        val scale = width.toFloat() / decor.width
        val screen = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val videos = videoSurfaces(decor).mapNotNull { view ->
            val at = IntArray(2).also { view.getLocationInWindow(it) }
            val rect = Rect(
                (at[0] * scale).roundToInt(),
                (at[1] * scale).roundToInt(),
                ((at[0] + view.width) * scale).roundToInt(),
                ((at[1] + view.height) * scale).roundToInt()
            )
            if (rect.width() <= 0 || rect.height() <= 0) null
            else Video(view, rect, Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888))
        }
        var pending = 1 + videos.size
        var screenOk = false
        // Every callback arrives on the main thread: no locking.
        val done: () -> Unit = {
            pending -= 1
            if (pending == 0) {
                if (screenOk) {
                    out.complete(compose(screen, videos))
                } else {
                    screen.recycle()
                    videos.forEach { it.bitmap.recycle() }
                    out.complete(null)
                }
            }
        }
        // The window first: it is the frame on screen now, before a menu opened by the same key draws.
        PixelCopy.request(window, screen, { result ->
            screenOk = result == PixelCopy.SUCCESS
            done()
        }, main)
        videos.forEach { video ->
            try {
                PixelCopy.request(video.view, video.bitmap, { result ->
                    video.ok = result == PixelCopy.SUCCESS
                    done()
                }, main)
            } catch (_: Exception) {
                done()
            }
        }
    }

    /** The videos that copied, then the window over them (see-through where each video shows). */
    private fun compose(screen: Bitmap, videos: List<Video>): Bitmap {
        if (videos.none { it.ok }) {
            videos.forEach { it.bitmap.recycle() }
            return screen
        }
        val shot = Bitmap.createBitmap(screen.width, screen.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(shot)
        canvas.drawColor(Color.BLACK)
        videos.forEach { video ->
            if (video.ok) canvas.drawBitmap(video.bitmap, null, video.rect, null)
            video.bitmap.recycle()
        }
        canvas.drawBitmap(screen, 0f, 0f, null)
        screen.recycle()
        return shot
    }

    /** Every shown SurfaceView under [view] with a picture to copy, in drawing order. */
    private fun videoSurfaces(view: View): List<SurfaceView> {
        val found = mutableListOf<SurfaceView>()
        fun visit(node: View) {
            if (node.visibility != View.VISIBLE) return
            if (node is SurfaceView && node.width > 0 && node.height > 0 && node.holder.surface?.isValid == true) found += node
            if (node is ViewGroup) for (i in 0 until node.childCount) visit(node.getChildAt(i))
        }
        visit(view)
        return found
    }
}
