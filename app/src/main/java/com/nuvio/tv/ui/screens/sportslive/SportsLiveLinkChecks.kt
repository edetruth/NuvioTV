package com.nuvio.tv.ui.screens.sportslive

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import com.nuvio.tv.core.sportslive.SportsLiveLinkCause
import com.nuvio.tv.core.sportslive.SportsLiveLinkErrors
import com.nuvio.tv.core.sportslive.SportsLiveLinkMarks
import com.nuvio.tv.core.sportslive.SportsLiveManifestDrm
import com.nuvio.tv.core.sportslive.SportsLiveWorkingLinks
import com.nuvio.tv.core.util.findCause
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.screens.player.PlayerMediaSourceFactory
import com.nuvio.tv.ui.screens.player.PlayerPlaybackNetworking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/** Why [error] stopped a sports link (#166), from its code, a failed request's HTTP status and its host. */
internal fun sportsLiveLinkCause(error: PlaybackException): SportsLiveLinkCause =
    SportsLiveLinkCause.classify(
        errorCode = error.errorCode,
        httpStatus = error.findCause<HttpDataSource.InvalidResponseCodeException>()?.responseCode,
        deadHost = SportsLiveLinkErrors.isDeadHost(error)
    )

/**
 * A sports player's error on [stream] (#166): its cause is noted for the link marks; an error that does
 * not say why (or a decoder error, which a DRM-only track also gives) has the link's manifest checked
 * for DRM in the background.
 */
internal fun noteSportsLiveLinkError(stream: Stream?, url: String?, error: PlaybackException) {
    val cause = sportsLiveLinkCause(error)
    SportsLiveLinkMarks.shared.noteCause(url, cause)
    if (cause == SportsLiveLinkCause.OTHER || cause == SportsLiveLinkCause.CODEC) {
        SportsLiveManifestSniffer.checkInBackground(url, stream?.behaviorHints?.proxyHeaders?.request.orEmpty())
    }
}

/**
 * Reads a link's manifest to tell whether it needs a DRM licence (#166, [SportsLiveManifestDrm]): the
 * DASH MPD, or the HLS playlist and, for a multivariant one, its first variant. Every request carries
 * the stream's request headers. Never logs a url or a host.
 */
internal object SportsLiveManifestSniffer {
    private const val MAX_BYTES = 512L * 1024
    private const val MAX_CHECKED = 500

    /** What a check found: [protected] (null when unknown) and the HTTP [status] of the manifest request. */
    data class Result(val protected: Boolean?, val status: Int?)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checked = LinkedHashSet<String>()

    /** Checks [url] once per process, in the background; a protected link is noted as DRM for the marks. */
    fun checkInBackground(url: String?, headers: Map<String, String>) {
        val key = SportsLiveWorkingLinks.linkKey(url) ?: return
        synchronized(checked) {
            if (!checked.add(key)) return
            while (checked.size > MAX_CHECKED) checked.remove(checked.first())
        }
        scope.launch {
            if (check(url ?: return@launch, headers).protected == true) {
                SportsLiveLinkMarks.shared.noteCause(url, SportsLiveLinkCause.DRM)
            }
        }
    }

    /** Whether [url]'s manifest needs a DRM licence. */
    suspend fun check(url: String, headers: Map<String, String>): Result = withContext(Dispatchers.IO) {
        val request = PlayerMediaSourceFactory.normalizePlaybackRequest(url, headers)
        val (status, text) = fetch(request.url, request.headers) ?: return@withContext Result(null, null)
        if (text == null) return@withContext Result(null, status)
        if (SportsLiveManifestDrm.kindOf(text) == null) return@withContext Result(null, status)
        if (SportsLiveManifestDrm.isProtected(text)) return@withContext Result(true, status)
        val variant = SportsLiveManifestDrm.firstVariantUri(text) ?: return@withContext Result(false, status)
        val variantUrl = request.url.toHttpUrlOrNull()?.resolve(variant)?.toString()
            ?: return@withContext Result(false, status)
        val inner = fetch(variantUrl, request.headers)?.second ?: return@withContext Result(null, status)
        Result(SportsLiveManifestDrm.isProtected(inner), status)
    }

    /** The response status and, when it succeeded, up to [MAX_BYTES] of its body; null when the request failed. */
    private fun fetch(url: String, headers: Map<String, String>): Pair<Int, String?>? = try {
        val client = PlayerPlaybackNetworking.createHttpClient(headers)
        val builder = Request.Builder().url(url)
        if (headers.none { it.key.equals("User-Agent", ignoreCase = true) }) {
            builder.header("User-Agent", PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
        }
        client.newCall(builder.build()).execute().use { response ->
            response.code to (if (response.isSuccessful) response.peekBody(MAX_BYTES).string() else null)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
