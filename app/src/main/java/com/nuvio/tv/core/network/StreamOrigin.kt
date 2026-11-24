package com.nuvio.tv.core.network

import okhttp3.HttpUrl

/**
 * Fork: the one rule for where a stream's headers may go (#279, #434). A stream's origin is its scheme, host and
 * port (another port is another service). Up from http to https on the default ports stays the same origin; down
 * from https to http never is.
 */
object StreamOrigin {

    fun isDowngrade(from: HttpUrl, to: HttpUrl): Boolean = from.isHttps && !to.isHttps

    fun same(from: HttpUrl, to: HttpUrl): Boolean {
        if (!from.host.equals(to.host, ignoreCase = true)) return false
        if (from.scheme == to.scheme) return from.port == to.port
        return !from.isHttps && to.isHttps && from.port == 80 && to.port == 443
    }
}
