package com.nuvio.tv.core.debug

import android.content.Context
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.tv.BuildConfig
import java.io.File

/**
 * Debug-only "test mute" for automated TV tests: the HDMI output has fixed volume, so adb cannot lower it.
 * Active only when this is a debuggable build AND the file [FILE_NAME] exists in the app's external files dir
 * (adb shell touch /sdcard/Android/data/com.nuviodebug.com/files/test-mute). Checked each time a player is built
 * or prepared, so no polling. Release builds: BuildConfig.DEBUG is a constant false, so nothing here ever runs.
 */
object TestMute {
    const val FILE_NAME = "test-mute"

    /** The whole decision: debuggable build and the flag file present. */
    internal fun shouldMute(debugBuild: Boolean, flagFileExists: Boolean): Boolean = debugBuild && flagFileExists

    fun isActive(context: Context): Boolean {
        if (!BuildConfig.DEBUG) return false
        val exists = runCatching { File(context.getExternalFilesDir(null), FILE_NAME).exists() }.getOrDefault(false)
        return shouldMute(BuildConfig.DEBUG, exists)
    }

    /** Silences [player] when active: volume 0 plus the audio track type off (passthrough ignores volume). */
    fun applyTo(context: Context, player: ExoPlayer) {
        if (!isActive(context)) return
        player.volume = 0f
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .build()
    }
}
