package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.network.StreamOrigin
import okhttp3.Call
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
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
    class HostScope(initial: HttpUrl? = null) {
        private val link = AtomicReference(initial)

        /**
         * [request] with the stream's login headers removed when it leaves the link's origin. With [streamHeaders]
         * (the values the stream set), a header holding another value stays: it is the other host's own, e.g. a
         * cookie the cookie jar holds for it (P7).
         */
        fun apply(request: Request, streamHeaders: Headers? = null): Request {
            if (inOrigin(request.url)) return request
            val scope = link.get() ?: return request
            val downgrade = StreamOrigin.isDowngrade(scope, request.url)
            val names = request.headers.names().filter { name ->
                (isCredential(name) || (downgrade && name.lowercase() in DOWNGRADE_NAMES)) &&
                    (streamHeaders == null || request.header(name) in streamHeaders.values(name))
            }
            if (names.isEmpty()) return request
            return request.newBuilder().apply { names.forEach { removeHeader(it) } }.build()
        }

        /** Whether [url] is within the link's origin (the first url seen is the link). */
        fun inOrigin(url: HttpUrl): Boolean {
            link.compareAndSet(null, url)
            val scope = link.get() ?: return true
            return StreamOrigin.same(scope, url)
        }
    }

    /**
     * Every playback client's base guard (P5): helpers that set a stream's headers on their own requests (frame-rate
     * and MIME probes, Blu-ray probes, subtitle indexing, the speed test) send the login values of a call's first
     * request to that request's origin only, also across redirects.
     */
    object CallOriginGuard : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val first = chain.call().request()
            return chain.proceed(HostScope(first.url).apply(chain.request(), first.headers))
        }
    }

    /**
     * The network interceptor of a stream's player client (#434): sets the stream's [injected] headers on every
     * hop (login headers only within the link's origin) and removes the stream's login headers elsewhere ([scope]).
     * Installed whether or not the stream has headers of its own, since a caller can set them on a request (P8).
     */
    class StreamGuard(private val scope: HostScope, private val injected: Map<String, String> = emptyMap()) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val inOrigin = scope.inOrigin(request.url)
            val next = request.newBuilder()
            for ((key, value) in injected) {
                if (inOrigin || !isCredential(key)) next.header(key, value)
            }
            val stream = chain.call().request().headers.newBuilder().apply {
                injected.forEach { (key, value) -> runCatching { add(key, value) } }
            }.build()
            return chain.proceed(scope.apply(next.build(), stream))
        }
    }

    /**
     * [request] for the retry without certificate checks: to a home server as it is; to any other host with
     * transport headers and the app's User-Agent only.
     */
    fun forUncheckedRetry(request: Request): Request =
        if (isLocalHost(request.url.host)) request else transportOnly(request)

    /**
     * The retry without certificate checks as a call on [client] (#435, P1). Every hop, redirects included, is
     * checked on its own: the stream's headers reach only a home server on the retried request's own origin; any
     * other hop gets transport headers and the app's User-Agent only.
     */
    fun uncheckedRetry(client: OkHttpClient, request: Request): Call =
        client.newBuilder().addNetworkInterceptor(UncheckedRetryGuard).build().newCall(request)

    private object UncheckedRetryGuard : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val first = chain.call().request().url
            val hop = chain.request()
            val stream = chain.call().request().headers
            return chain.proceed(if (StreamOrigin.same(first, hop.url)) forUncheckedRetry(hop) else transportOnly(hop, stream))
        }
    }

    /**
     * [request] without the stream's headers ([stream]: the headers the caller set) and without login headers; the
     * headers OkHttp adds itself (Host, Connection, ...) and the transport ones stay (#457: a request with no Host
     * is answered 400). The User-Agent becomes the app's own.
     */
    private fun transportOnly(request: Request, stream: Headers = request.headers): Request {
        val builder = request.newBuilder()
        val streamNames = stream.names().map { it.lowercase() }.toSet()
        request.headers.names()
            .filter { name ->
                val lower = name.lowercase()
                lower !in TRANSPORT_NAMES && (lower in streamNames || isCredential(name))
            }
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

    /**
     * [client] with its own [HostScope] (#434), for a player client that sets the stream's headers itself. Give
     * [linkUrl], the addon's link, when it is known (P2): a warm session's first request can go straight to where
     * an earlier redirect led, and must not become the scope.
     */
    fun scoped(client: OkHttpClient, linkUrl: String? = null): OkHttpClient =
        client.newBuilder().addNetworkInterceptor(StreamGuard(HostScope(linkUrl?.trim()?.toHttpUrlOrNull()))).build()
}
