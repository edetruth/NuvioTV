package com.nuvio.tv.core.streams

import com.nuvio.tv.domain.model.Stream
import java.net.URI

/**
 * Detects addon "streams" that can never play, such as upsell or tip entries whose
 * `url` is a bare site root (`https://www.google.com`) or a health endpoint.
 */
object UnplayableStreamFilter {
    private val junkPaths = setOf("", "/", "/health")

    fun isUnplayableJunk(stream: Stream): Boolean {
        if (stream.debridResolved || stream.serverTarget != null) return false
        if (!stream.infoHash.isNullOrBlank() || !stream.ytId.isNullOrBlank()) return false
        val url = stream.url?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        return uri.rawPath.orEmpty().lowercase() in junkPaths
    }
}
