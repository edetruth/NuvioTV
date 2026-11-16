package com.nuvio.tv.ui.screens.player

/**
 * One-shot proxy response headers for the Sports & Live stream that is about to open in the full
 * player, keyed by playback URL like [StreamSidecarSubtitles].
 *
 * The full-player launch hands off through [PlayerNavigationArgs], which carries only the proxy
 * *request* headers; the response headers (referer/origin echoed back, etc.) are dropped, so a
 * channel whose host needs them 403s on launch even though the same stream's hover preview — which
 * keeps them — plays. The sports play/zap paths stash the resolved stream's response headers here and
 * [PlayerRuntimeController] seeds them at init. A leftover entry cannot attach to a different stream
 * because the URL must match, so non-sports launches are unaffected.
 */
internal object SportsLiveLaunchHeaders {
    @Volatile
    private var playbackUrl: String? = null

    @Volatile
    private var responseHeaders: Map<String, String> = emptyMap()

    fun set(url: String?, responseHeaders: Map<String, String>) {
        val playbackUrl = url?.takeIf { it.isNotBlank() && responseHeaders.isNotEmpty() }
        this.playbackUrl = playbackUrl
        this.responseHeaders = if (playbackUrl == null) emptyMap() else responseHeaders
    }

    /** The stashed response headers when either [initialUrl] or [normalizedUrl] matches; else empty. */
    fun forUrl(initialUrl: String, normalizedUrl: String): Map<String, String> {
        val stored = playbackUrl ?: return emptyMap()
        return if (stored == initialUrl || stored == normalizedUrl) responseHeaders else emptyMap()
    }
}
