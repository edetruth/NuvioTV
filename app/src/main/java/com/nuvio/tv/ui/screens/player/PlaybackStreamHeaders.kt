package com.nuvio.tv.ui.screens.player

import okhttp3.HttpUrl
import okhttp3.Request
import java.util.concurrent.atomic.AtomicReference

/**
 * Fork (#434, #435): where a stream's request headers may go in the player.
 * - Login and cookie headers ([isCredential]) go only to the host the addon's link names (and not down from https
 *   to http); every other header (Referer, Origin, User-Agent, ...) still goes along, as content servers need them.
 * - The unchecked TLS retry ([forUncheckedRetry]) sends no stream header at all: only transport headers and the
 *   app's own User-Agent.
 */
internal object PlaybackStreamHeaders {

    private val CREDENTIAL_NAMES = setOf("authorization", "proxy-authorization", "cookie", "cookie2")
    private val CREDENTIAL_WORDS = listOf("auth", "token", "api-key", "apikey", "session", "secret", "password")

    /** Transport headers the player itself sets; the only ones kept on the unchecked retry. */
    private val TRANSPORT_NAMES = setOf("range", "if-range", "if-none-match", "if-modified-since", "accept-encoding", "icy-metadata")

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

    /** [request] for the retry without certificate checks: transport headers and the app's User-Agent only. */
    fun forUncheckedRetry(request: Request): Request {
        val builder = request.newBuilder()
        request.headers.names()
            .filter { it.lowercase() !in TRANSPORT_NAMES }
            .forEach { builder.removeHeader(it) }
        builder.header("User-Agent", PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
        return builder.build()
    }
}
