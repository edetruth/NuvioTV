package com.nuvio.tv.data.repository

import com.nuvio.tv.core.sportslive.ManifestResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the start of a game's HLS manifest for the ended-game link check (#60 phase 2). Its own bare
 * client: no cache, and none of the app client's logging or breadcrumb interceptors, so a stream url
 * is never logged. Any failure is null (no verdict), never a throw.
 */
@Singleton
class SportsLiveManifestFetcher @Inject constructor() {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .callTimeout(TIMEOUT_S * 2, TimeUnit.SECONDS)
            .build()
    }

    suspend fun fetch(url: String, headers: Map<String, String>): ManifestResponse? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).apply {
                headers.forEach { (name, value) -> runCatching { header(name, value) } }
            }.get().build()
            client.newCall(request).execute().use { response ->
                val body = if (response.isSuccessful) {
                    response.body?.source()?.let { source ->
                        source.request(MAX_BYTES)
                        val size = minOf(source.buffer.size, MAX_BYTES)
                        source.buffer.readUtf8(size)
                    }
                } else {
                    null
                }
                ManifestResponse(response.code, body)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val TIMEOUT_S = 8L
        const val MAX_BYTES = 64L * 1024
    }
}
