package com.nuvio.tv.core.debug

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.tv.BuildConfig
import java.io.File
import java.util.Collections
import java.util.WeakHashMap

/**
 * Debug-only "test mute" for automated TV tests: the HDMI output has fixed volume, so adb cannot lower it.
 * Active only when this is a debuggable build AND the file [FILE_NAME] exists in the app's external files dir
 * (adb shell touch /sdcard/Android/data/com.nuviodebug.com/files/test-mute). Checked each time a player is built
 * or prepared, so no polling. Release builds: BuildConfig.IS_DEBUG_BUILD is false, so nothing here ever runs.
 */
object TestMute {
    const val FILE_NAME = "test-mute"

    /** The whole decision: debuggable build and the flag file present. */
    internal fun shouldMute(debugBuild: Boolean, flagFileExists: Boolean): Boolean = debugBuild && flagFileExists

    fun isActive(context: Context): Boolean {
        if (!BuildConfig.IS_DEBUG_BUILD) return false
        val exists = runCatching { File(context.getExternalFilesDir(null), FILE_NAME).exists() }.getOrDefault(false)
        return shouldMute(BuildConfig.IS_DEBUG_BUILD, exists)
    }

    private val guarded: MutableSet<Player> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<Player, Boolean>()))

    /**
     * Silences [player] when active: volume 0 plus the audio track type off (passthrough ignores volume), and a
     * [MuteGuard] listener that undoes any later change (volume restore, track-selection rebuild, peek restore).
     * [who] names the player in the logcat line "TestMute: muted <who>".
     */
    fun applyTo(context: Context, player: ExoPlayer, who: String = player.javaClass.simpleName) {
        if (!isActive(context)) return
        player.volume = 0f
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .build()
        if (guarded.add(player)) player.addListener(MuteGuard(player) { isActive(context) })
        Log.i("TestMute", "muted $who")
    }
}

/** Keeps [player] silent while [active] is true: resets any non-zero volume and re-disables the audio track type. */
internal class MuteGuard(private val player: Player, private val active: () -> Boolean) : Player.Listener {
    override fun onVolumeChanged(volume: Float) {
        if (volume != 0f && active()) player.volume = 0f
    }

    override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
        if (C.TRACK_TYPE_AUDIO !in parameters.disabledTrackTypes && active()) {
            player.trackSelectionParameters = parameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
        }
    }
}
