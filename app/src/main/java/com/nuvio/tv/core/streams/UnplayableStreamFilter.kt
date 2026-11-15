package com.nuvio.tv.core.streams

import com.nuvio.tv.domain.model.Stream
import java.net.URI

/**
 * Detects addon "streams" that can never play, such as upsell or tip entries whose
 * `url` is a bare site root (`https://www.google.com`), a health endpoint, or a web page.
 */
object UnplayableStreamFilter {
    private val junkPaths = setOf("", "/", "/health", "/health/")
    private val webPageSuffixes = listOf(".html", ".htm")

    fun isUnplayableJunk(stream: Stream): Boolean {
        if (stream.debridResolved || stream.serverTarget != null) return false
        if (stream.getEffectiveInfoHash() != null || !stream.ytId.isNullOrBlank()) return false
        val url = stream.url?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        val path = uri.rawPath.orEmpty().lowercase()
        return path in junkPaths || webPageSuffixes.any { path.endsWith(it) }
    }
}
