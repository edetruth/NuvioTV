package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.network.StreamOrigin
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Where a stream's request headers may go (#279). An addon's headers (a token, a cookie, a referer) are meant for
 * the origin its link names ([StreamOrigin]: scheme, host and port): a follow-up request (an HLS variant) gets them
 * only there, and never down from https to http. Redirects follow the same rule ([com.nuvio.tv.data.repository.SportsLiveManifestFetcher.nextHop]).
 */
object SportsLiveStreamHeaders {

    /** [headers] for a request to [next] that the addon's link [original] led to; empty unless it is the same host. */
    fun forFollowUp(original: String, next: String, headers: Map<String, String>): Map<String, String> {
        if (headers.isEmpty()) return headers
        val from = original.trim().toHttpUrlOrNull() ?: return emptyMap()
        val to = next.trim().toHttpUrlOrNull() ?: return emptyMap()
        return if (StreamOrigin.same(from, to)) headers else emptyMap()
    }
}
