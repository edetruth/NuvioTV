package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.network.StreamOrigin
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Where a stream's request headers may go in the link checks (#279, #463): the player's rule ([StreamOrigin]). On the
 * origin its link names (scheme, host and port) a follow-up request (an HLS variant) gets them all; elsewhere the login
 * headers stay behind (and Referer and Origin down from https to http), so a check reads what the player would.
 */
object SportsLiveStreamHeaders {

    /** [headers] for a request to [next] that the addon's link [original] led to; none when either is unreadable. */
    fun forFollowUp(original: String, next: String, headers: Map<String, String>): Map<String, String> {
        if (headers.isEmpty()) return headers
        val from = original.trim().toHttpUrlOrNull() ?: return emptyMap()
        val to = next.trim().toHttpUrlOrNull() ?: return emptyMap()
        return StreamOrigin.headersFor(from, to, headers)
    }
}
