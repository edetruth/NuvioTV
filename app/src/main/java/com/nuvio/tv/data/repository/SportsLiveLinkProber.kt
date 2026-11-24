package com.nuvio.tv.data.repository

import com.nuvio.tv.core.network.StreamOrigin
import com.nuvio.tv.core.sportslive.ProbeVerdict
import com.nuvio.tv.core.sportslive.SportsLiveLinkCause
import com.nuvio.tv.core.sportslive.SportsLiveLinkMarks
import com.nuvio.tv.core.sportslive.SportsLiveManifestDrm
import com.nuvio.tv.core.sportslive.SportsLiveMediaPlaylist
import com.nuvio.tv.core.sportslive.SportsLiveStreamHeaders
import com.nuvio.tv.core.sportslive.SportsLiveStreamLicences
import com.nuvio.tv.core.sportslive.SportsLiveStreamThresholds
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Probes a link before a start (#147): an HLS link's playlist, and for a master its first variant, must be a
 * live media playlist ([SportsLiveMediaPlaylist.verdict], #110); any other link must answer. A playlist that needs a
 * DRM licence its addon does not give (#166) is dead here: it is noted as protected, never raced or marked ready. Its own bare
 * client, like [SportsLiveManifestFetcher]'s: no cache, no logging interceptor, so a url is never logged.
 * Redirects follow the same rules ([SportsLiveManifestFetcher.nextHop]). Calls are cancelled with their
 * coroutine, so a race's losers stop at once.
 */
@Singleton
class SportsLiveLinkProber @Inject constructor() {
    companion object {
        private const val MAX_BYTES = 64L * 1024
        private const val MAX_REDIRECTS = 3

        /** True when [text], [url]'s playlist, needs a DRM licence that [url]'s addon does not provide (#166). */
        internal fun isUnlicensedDrm(url: String, text: String?): Boolean =
            text != null && SportsLiveManifestDrm.isProtected(text) && !SportsLiveStreamLicences.has(url)
    }

    /** [isUnlicensedDrm]: the link is remembered as protected for every game, as the player's sniffer does. */
    private fun protectedHere(url: String, text: String?): Boolean =
        isUnlicensedDrm(url, text).also { if (it) SportsLiveLinkMarks.shared.noteCause(url, SportsLiveLinkCause.DRM) }

    private val client: OkHttpClient by lazy {
        val timeout = SportsLiveStreamThresholds.RACE_PROBE_TIMEOUT_MS
        OkHttpClient.Builder()
            .connectTimeout(timeout, TimeUnit.MILLISECONDS)
            .readTimeout(timeout, TimeUnit.MILLISECONDS)
            .callTimeout(timeout, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    private class Answer(val code: Int, val url: HttpUrl, val body: String?, val failed: ProbeVerdict? = null)

    /** [url] (credentials already moved into [headers], as the player plays it) probed now. */
    suspend fun probe(url: String, headers: Map<String, String>): ProbeVerdict {
        val hls = SportsLiveMediaPlaylist.isHls(url)
        val first = get(url.toHttpUrlOrNull() ?: return ProbeVerdict.UNKNOWN, headers, readBody = hls) ?: return ProbeVerdict.UNKNOWN
        first.failed?.let { return it }
        if (first.code !in 200..299) return codeVerdict(first.code)
        if (!hls) return ProbeVerdict.OK
        val body = first.body ?: return ProbeVerdict.DEAD
        if (protectedHere(url, body)) return ProbeVerdict.DEAD
        if (!SportsLiveMediaPlaylist.isMaster(body)) return SportsLiveMediaPlaylist.verdict(body, System.currentTimeMillis())
        val variant = SportsLiveMediaPlaylist.firstVariant(body)?.let(first.url::resolve) ?: return ProbeVerdict.UNKNOWN
        // #279: the headers only go on to the addon link's own host, not to wherever a redirect led.
        val media = get(variant, SportsLiveStreamHeaders.forFollowUp(url, variant.toString(), headers), readBody = true)
            ?: return ProbeVerdict.UNKNOWN
        media.failed?.let { return it }
        if (media.code !in 200..299) return codeVerdict(media.code)
        if (protectedHere(url, media.body)) return ProbeVerdict.DEAD
        return SportsLiveMediaPlaylist.verdict(media.body, System.currentTimeMillis())
    }

    /** A non-2xx answer: an error will not play; a redirect with nowhere to go says nothing. */
    private fun codeVerdict(code: Int): ProbeVerdict = if (code in 400..599) ProbeVerdict.DEAD else ProbeVerdict.UNKNOWN

    /** [target] fetched with its redirects followed; null when a redirect may not be followed. */
    private suspend fun get(target: HttpUrl, headers: Map<String, String>, readBody: Boolean): Answer? {
        var url = target
        var sent = headers
        repeat(MAX_REDIRECTS + 1) {
            val request = Request.Builder().url(url).apply {
                sent.forEach { (name, value) -> runCatching { header(name, value) } }
            }.get().build()
            val (answer, location) = call(request, readBody)
            if (answer.failed != null || location == null) return answer
            val hop = SportsLiveManifestFetcher.nextHop(url, location) ?: return null
            sent = StreamOrigin.headersFor(target, hop, headers)
            url = hop
        }
        return null
    }

    /** One request: its answer, and the redirect's location when it is one. */
    private suspend fun call(request: Request, readBody: Boolean): Pair<Answer, String?> =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    // A host that does not exist or refuses the connection will not play; a timeout says nothing.
                    val verdict = if (e is UnknownHostException || e is ConnectException) ProbeVerdict.DEAD else ProbeVerdict.UNKNOWN
                    if (cont.isActive) cont.resume(Answer(0, request.url, null, verdict) to null)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use {
                            val location = if (it.isRedirect) it.header("Location") else null
                            val body = if (readBody && it.isSuccessful && location == null) bodyStart(it) else null
                            Answer(it.code, request.url, body) to location
                        }
                    } catch (_: Exception) {
                        Answer(0, request.url, null, ProbeVerdict.UNKNOWN) to null
                    }
                    if (cont.isActive) cont.resume(result)
                }
            })
        }

    private fun bodyStart(response: Response): String? =
        response.body?.source()?.let { source ->
            source.request(MAX_BYTES)
            source.buffer.readUtf8(minOf(source.buffer.size, MAX_BYTES))
        }
}
