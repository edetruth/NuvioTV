package com.nuvio.tv.core.sportslive

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Where a stream's request headers may go (#279). An addon's headers (a token, a cookie, a referer) are meant for
 * the host its link names: a follow-up request (an HLS variant) gets them only on that same host, and never down
 * from https to http. Redirects follow the same rule ([com.nuvio.tv.data.repository.SportsLiveManifestFetcher.nextHop]).
 */
object SportsLiveStreamHeaders {

    /** [headers] for a request to [next] that the addon's link [original] led to; empty unless it is the same host. */
    fun forFollowUp(original: String, next: String, headers: Map<String, String>): Map<String, String> {
        if (headers.isEmpty()) return headers
        val from = original.trim().toHttpUrlOrNull() ?: return emptyMap()
        val to = next.trim().toHttpUrlOrNull() ?: return emptyMap()
        if (from.isHttps && !to.isHttps) return emptyMap()
        return if (from.host.equals(to.host, ignoreCase = true)) headers else emptyMap()
    }
}
