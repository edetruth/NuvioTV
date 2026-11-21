package com.nuvio.tv.ui.screens.sportslive.preview

import android.media.MediaCodecList
import android.os.Build
import java.util.concurrent.ConcurrentHashMap

/**
 * Previews never take the decoder of the game being watched (#106). A preview or peek that plays beside the full
 * player [hold]s a second decoder until it [drop]s it; when the full player loses its decoder anyway, [yieldAll]
 * makes every holder let go at once, so the game can start again. [allowedBesideMain] is the rule for starting
 * one. Dual screen is meant to run two decoders and does not take part. Main thread.
 */
object SportsLiveSecondDecoders {
    private val holders = LinkedHashMap<Any, () -> Unit>()

    /** A preview or peek holds a second decoder now. */
    val active: Boolean
        @Synchronized get() = holders.isNotEmpty()

    /** [owner] plays on a second decoder; [yieldNow] stops it and frees the decoder. */
    @Synchronized
    fun hold(owner: Any, yieldNow: () -> Unit) {
        holders[owner] = yieldNow
    }

    @Synchronized
    fun drop(owner: Any) {
        holders.remove(owner)
    }

    /** The main player lost its decoder: each holder lets go. True when any did. */
    fun yieldAll(): Boolean {
        val now = synchronized(this) { holders.values.toList().also { holders.clear() } }
        now.forEach { runCatching { it() } }
        return now.isNotEmpty()
    }

    /**
     * Whether a second video decoder may start beside the main player: always when nothing plays there; else only
     * when this TV has not shown a decoder limit ([SportsLiveDecoderLimit]) and its hardware decoder for the main
     * video runs [hardwareInstances] at once, at least 2 (3 when the main video is above 1080p).
     */
    fun allowedBesideMain(mainPlaying: Boolean, mainAbove1080p: Boolean, hardwareInstances: Int, limitSeen: Boolean): Boolean {
        if (!mainPlaying) return true
        if (limitSeen) return false
        return hardwareInstances >= if (mainAbove1080p) 3 else 2
    }

    private val instances = ConcurrentHashMap<String, Int>()

    /** How many [mime] videos this TV's hardware decoders say they play at once (the best one); 0 when none. */
    fun hardwareInstances(mime: String): Int = instances.getOrPut(mime) {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { info -> !info.isEncoder && !isSoftware(info) && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
                .maxOfOrNull { info -> runCatching { info.getCapabilitiesForType(mime).maxSupportedInstances }.getOrDefault(1) }
                ?: 0
        }.getOrDefault(0)
    }

    private fun isSoftware(info: android.media.MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 29) info.isSoftwareOnly
        else info.name.let { it.startsWith("OMX.google.", true) || it.startsWith("c2.android.", true) }

    internal fun clear() = synchronized(this) { holders.clear() }
}
