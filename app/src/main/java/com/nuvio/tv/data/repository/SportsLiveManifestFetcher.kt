package com.nuvio.tv.data.repository

import com.nuvio.tv.core.network.StreamOrigin
import com.nuvio.tv.core.sportslive.ManifestResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the start of a game's HLS manifest for the ended-game link check (#60 phase 2). Its own bare
 * client: no cache, and none of the app client's logging or breadcrumb interceptors, so a stream url
 * is never logged. Redirects are followed here, not by OkHttp ([nextHop]): never from https down to
 * http, and off the link's origin the stream's login headers stay behind ([StreamOrigin.headersFor], #463). Any failure is null (no verdict),
 * never a throw.
 */
@Singleton
class SportsLiveManifestFetcher @Inject constructor() {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .callTimeout(TIMEOUT_S * 2, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    /** [url]'s answer, the body read up to [maxBytes]; null when it could not be read or a redirect may not be followed. */
    suspend fun fetch(
        url: String,
        headers: Map<String, String>,
        maxBytes: Long = MAX_BYTES
    ): ManifestResponse? = withContext(Dispatchers.IO) {
        try {
            val link = url.toHttpUrlOrNull() ?: return@withContext null
            var target = link
            var sent = headers
            repeat(MAX_REDIRECTS + 1) {
                val request = Request.Builder().url(target).apply {
                    sent.forEach { (name, value) -> runCatching { header(name, value) } }
                }.get().build()
                val hop = client.newCall(request).execute().use { response ->
                    if (response.isRedirect) {
                        nextHop(target, response.header("Location"))
                            ?: return@withContext null
                    } else {
                        return@withContext ManifestResponse(
                            response.code,
                            if (response.isSuccessful) bodyStart(response, maxBytes) else null,
                            finalUrl = target.toString()
                        )
                    }
                }
                sent = StreamOrigin.headersFor(link, hop, headers)
                target = hop
            }
            // Too many redirects: no verdict.
            null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun bodyStart(response: okhttp3.Response, maxBytes: Long): String? =
        response.body?.source()?.let { source ->
            source.request(maxBytes)
            val size = minOf(source.buffer.size, maxBytes)
            source.buffer.readUtf8(size)
        }

    internal companion object {
        const val TIMEOUT_S = 8L
        const val MAX_BYTES = 64L * 1024
        const val MAX_REDIRECTS = 3

        /**
         * The redirect from [from] to [location]: null (not followed, no verdict) when there is no usable
         * location or it goes from https down to http.
         */
        fun nextHop(from: HttpUrl, location: String?): HttpUrl? {
            val to = location?.let { from.resolve(it) } ?: return null
            if (from.isHttps && !to.isHttps) return null
            return to
        }
    }
}
