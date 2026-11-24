package com.nuvio.tv.ui.screens.player

import okhttp3.HttpUrl
import okhttp3.Request
import java.util.concurrent.atomic.AtomicReference

/**
 * Fork (#434): where a stream's request headers may go in the player.
 * - Login and cookie headers ([isCredential]) go only to the host the addon's link names (and not down from https
 *   to http); every other header (Referer, Origin, User-Agent, ...) still goes along, as content servers need them.
 */
internal object PlaybackStreamHeaders {

    private val CREDENTIAL_NAMES = setOf("authorization", "proxy-authorization", "cookie", "cookie2")
    private val CREDENTIAL_WORDS = listOf("auth", "token", "api-key", "apikey", "session", "secret", "password")

    fun isCredential(name: String): Boolean {
        val lower = name.trim().lowercase()
        return lower in CREDENTIAL_NAMES || CREDENTIAL_WORDS.any { it in lower }
    }

    /**
     * One player client's header scope: the first request it sends is the addon's link, and its host is the
     * scope. A request elsewhere has its login headers removed.
     */
    class HostScope {
        private val link = AtomicReference<HttpUrl?>(null)

        fun apply(request: Request): Request {
            link.compareAndSet(null, request.url)
            val scope = link.get() ?: return request
            val downgrade = scope.isHttps && !request.url.isHttps
            if (!downgrade && request.url.host == scope.host) return request
            val names = request.headers.names().filter(::isCredential)
            if (names.isEmpty()) return request
            return request.newBuilder().apply { names.forEach { removeHeader(it) } }.build()
        }
    }

}
