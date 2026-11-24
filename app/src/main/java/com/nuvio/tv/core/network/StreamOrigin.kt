package com.nuvio.tv.core.network

import okhttp3.HttpUrl

/**
 * Fork: the one rule for where a stream's headers may go (#279, #434, #463), for the player and the link checks
 * alike. A stream's origin is its scheme, host and port (another port is another service). Up from http to https on
 * the default ports stays the same origin; down from https to http never is. Outside the link's origin, login headers
 * ([isCredential]) stay behind, and Referer and Origin too when going down to http; every other header goes along,
 * as content servers need them.
 */
object StreamOrigin {

    private val CREDENTIAL_NAMES = setOf("authorization", "proxy-authorization", "cookie", "cookie2")
    private val CREDENTIAL_WORDS = listOf(
        "auth", "token", "key", "session", "secret", "pass", "sig", "jwt", "cred", "ticket", "login", "account"
    )

    /** Not credentials themselves, but they can carry a private link: never sent down from https to http. */
    private val DOWNGRADE_NAMES = setOf("referer", "origin")

    /** A login, cookie or token header. */
    fun isCredential(name: String): Boolean {
        val lower = name.trim().lowercase()
        if (lower in CREDENTIAL_NAMES || CREDENTIAL_WORDS.any { it in lower }) return true
        return "user" in lower && lower != "user-agent"
    }

    /** Whether header [name] of the stream whose link is [link] stays off a request to [to]. */
    fun drops(link: HttpUrl, to: HttpUrl, name: String): Boolean {
        if (same(link, to)) return false
        return isCredential(name) || (isDowngrade(link, to) && name.trim().lowercase() in DOWNGRADE_NAMES)
    }

    /** The stream's [headers] for a request to [to], when its link is [link]. */
    fun headersFor(link: HttpUrl, to: HttpUrl, headers: Map<String, String>): Map<String, String> =
        if (same(link, to)) headers else headers.filterKeys { !drops(link, to, it) }

    fun isDowngrade(from: HttpUrl, to: HttpUrl): Boolean = from.isHttps && !to.isHttps

    fun same(from: HttpUrl, to: HttpUrl): Boolean {
        if (!from.host.equals(to.host, ignoreCase = true)) return false
        if (from.scheme == to.scheme) return from.port == to.port
        return !from.isHttps && to.isHttps && from.port == 80 && to.port == 443
    }
}
