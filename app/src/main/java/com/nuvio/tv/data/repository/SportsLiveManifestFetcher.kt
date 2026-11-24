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
 * http, and the stream's headers are not sent on to another host. Any failure is null (no verdict),
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

    suspend fun fetch(url: String, headers: Map<String, String>): ManifestResponse? = withContext(Dispatchers.IO) {
        try {
            var target = url.toHttpUrlOrNull() ?: return@withContext null
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
                            if (response.isSuccessful) bodyStart(response) else null,
                            finalUrl = target.toString()
                        )
                    }
                }
                if (!hop.keepHeaders) sent = emptyMap()
                target = hop.url
            }
            // Too many redirects: no verdict.
            null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun bodyStart(response: okhttp3.Response): String? =
        response.body?.source()?.let { source ->
            source.request(MAX_BYTES)
            val size = minOf(source.buffer.size, MAX_BYTES)
            source.buffer.readUtf8(size)
        }

    /** Where a redirect goes, and whether the stream's headers go with it. */
    internal data class Hop(val url: HttpUrl, val keepHeaders: Boolean)

    internal companion object {
        const val TIMEOUT_S = 8L
        const val MAX_BYTES = 64L * 1024
        const val MAX_REDIRECTS = 3

        /**
         * The redirect from [from] to [location]: null (not followed, no verdict) when there is no usable
         * location or it goes from https down to http; the headers only go on to the same origin ([StreamOrigin]).
         */
        fun nextHop(from: HttpUrl, location: String?): Hop? {
            val to = location?.let { from.resolve(it) } ?: return null
            if (from.isHttps && !to.isHttps) return null
            return Hop(to, keepHeaders = StreamOrigin.same(from, to))
        }
    }
}
