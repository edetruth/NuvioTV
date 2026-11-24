package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.network.StreamOrigin
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.atomic.AtomicReference

/**
 * Fork (#434, #435): where a stream's request headers may go in the player.
 * - Login and cookie headers ([isCredential]) go only to the origin the addon's link names ([StreamOrigin]: scheme,
 *   host and port; never down from https to http); every other header (Referer, Origin, User-Agent, ...) still goes
 *   along, as content servers need them, except Referer and Origin down from https to http.
 * - The unchecked TLS retry ([forUncheckedRetry]) sends no stream header to a public host: only transport headers
 *   and the app's own User-Agent. A home server ([isLocalHost], self-signed certificates are common there) keeps them.
 */
internal object PlaybackStreamHeaders {

    private val CREDENTIAL_NAMES = setOf("authorization", "proxy-authorization", "cookie", "cookie2")
    private val CREDENTIAL_WORDS = listOf(
        "auth", "token", "key", "session", "secret", "pass", "sig", "jwt", "cred", "ticket", "login", "account"
    )

    /** Not credentials themselves, but they can carry a private link: never sent down from https to http. */
    private val DOWNGRADE_NAMES = setOf("referer", "origin")

    /** Transport headers the player itself sets; the only ones kept on the unchecked retry. */
    private val TRANSPORT_NAMES = setOf("range", "if-range", "if-none-match", "if-modified-since", "accept-encoding", "icy-metadata")

    fun isCredential(name: String): Boolean {
        val lower = name.trim().lowercase()
        if (lower in CREDENTIAL_NAMES || CREDENTIAL_WORDS.any { it in lower }) return true
        return "user" in lower && lower != "user-agent"
    }

    /**
     * One player client's header scope: the first request it sends is the addon's link, and its origin is the
     * scope. A request elsewhere has the stream's login headers removed.
     */
    class HostScope {
        private val link = AtomicReference<HttpUrl?>(null)

        /**
         * [request] with the stream's login headers removed when it leaves the link's origin. With [streamHeaders]
         * (the values the stream set), a header holding another value stays: it is the other host's own, e.g. a
         * cookie the cookie jar holds for it (P7).
         */
        fun apply(request: Request, streamHeaders: Headers? = null): Request {
            link.compareAndSet(null, request.url)
            val scope = link.get() ?: return request
            if (StreamOrigin.same(scope, request.url)) return request
            val downgrade = StreamOrigin.isDowngrade(scope, request.url)
            val names = request.headers.names().filter { name ->
                (isCredential(name) || (downgrade && name.lowercase() in DOWNGRADE_NAMES)) &&
                    (streamHeaders == null || request.header(name) in streamHeaders.values(name))
            }
            if (names.isEmpty()) return request
            return request.newBuilder().apply { names.forEach { removeHeader(it) } }.build()
        }
    }

    /**
     * [request] for the retry without certificate checks: to a home server as it is; to any other host with
     * transport headers and the app's User-Agent only.
     */
    fun forUncheckedRetry(request: Request): Request {
        if (isLocalHost(request.url.host)) return request
        val builder = request.newBuilder()
        request.headers.names()
            .filter { it.lowercase() !in TRANSPORT_NAMES }
            .forEach { builder.removeHeader(it) }
        builder.header("User-Agent", PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
        return builder.build()
    }

    /**
     * A private or local address, read from the name alone (no lookup): 10/8, 172.16/12, 192.168/16, 127/8, ::1,
     * fc00::/7, fe80::/10, a `.local` name or a single-label name.
     */
    fun isLocalHost(host: String): Boolean {
        val name = host.trim().trimStart('[').trimEnd(']').lowercase()
        if (name.isEmpty()) return false
        if (':' in name) {
            if (name == "::1") return true
            val first = name.substringBefore(':').ifEmpty { "0" }.toIntOrNull(16) ?: return false
            return (first and 0xfe00) == 0xfc00 || (first and 0xffc0) == 0xfe80
        }
        val octets = name.split('.')
        if (octets.size == 4 && octets.all { it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) }) {
            val (a, b) = octets.map { it.toInt() }
            return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31)
        }
        return name.endsWith(".local") || '.' !in name.trimEnd('.')
    }

    /** [client] with its own [HostScope] (#434), for a player client that sets the stream's headers itself. */
    fun scoped(client: OkHttpClient): OkHttpClient {
        val scope = HostScope()
        return client.newBuilder()
            .addNetworkInterceptor(Interceptor { chain -> chain.proceed(scope.apply(chain.request(), chain.call().request().headers)) })
            .build()
    }
}
