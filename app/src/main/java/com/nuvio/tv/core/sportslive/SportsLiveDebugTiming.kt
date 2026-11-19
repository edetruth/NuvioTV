package com.nuvio.tv.core.sportslive

import com.nuvio.tv.BuildConfig

/**
 * Debug builds only (#158): timing lines under the logcat tag [TAG] for sports lookups and link
 * starts, and overrides of the sports timeouts read from `adb shell setprop debug.nuvio.<name> <value>`.
 * A release build logs nothing and never reads an override. Lines never hold a url or a host.
 */
object SportsLiveDebugTiming {
    const val TAG = "NuvioTiming"

    /** Each addon's lookup limit, in ms (0: none). */
    const val LOOKUP_TIMEOUT = "lookup_timeout_ms"

    /** A link's start-up limit, in ms (a large value: none). */
    const val STARTUP_TIMEOUT = "startup_timeout_ms"

    private const val CACHE_MS = 3_000L

    private val enabled: Boolean get() = BuildConfig.IS_DEBUG_BUILD

    private val cache = HashMap<String, Pair<Long, Long?>>()

    fun log(message: () -> String) {
        if (!enabled) return
        try {
            android.util.Log.i(TAG, message())
        } catch (_: Throwable) {
        }
    }

    /** `debug.nuvio.[name]` as a number, on a debug build when it is set; else null. */
    fun override(name: String): Long? {
        if (!enabled) return null
        val now = System.nanoTime() / 1_000_000
        synchronized(cache) {
            cache[name]?.let { (at, value) -> if (now - at < CACHE_MS) return value }
        }
        val value = try {
            val process = ProcessBuilder("getprop", "debug.nuvio.$name").redirectErrorStream(true).start()
            process.inputStream.bufferedReader().use { it.readText() }.trim().toLongOrNull()
        } catch (_: Throwable) {
            null
        }
        synchronized(cache) { cache[name] = now to value }
        return value
    }

    /** An addon or source name fit for a log line: never a host. */
    fun label(name: String?): String = SportsLiveViewerText.forViewer(name, "addon")
}
