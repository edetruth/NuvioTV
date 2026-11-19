package com.nuvio.tv.ui.screens.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.hls.DefaultHlsDataSourceFactory
import com.nuvio.tv.core.sportslive.SportsLiveStreamLicence
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * #166: an addon's `behaviorHints.proxyHeaders.request` reach every request of an HLS stream (playlist,
 * segment, AES key, also after a redirect) and every licence request of a DRM stream, through the same
 * data sources the player builds.
 */
class SportsStreamHeaderApplicationTest {

    private val server = MockWebServer()
    private val headers = mapOf("Referer" to "https://example.invalid/page", "Origin" to "https://example.invalid", "User-Agent" to "AddonAgent/1.0")

    private fun uriOf(url: String): Uri = mockk(relaxed = true) {
        every { this@mockk.toString() } returns url
        every { scheme } returns "http"
        every { host } returns "127.0.0.1"
        every { path } returns url.substringAfter("127.0.0.1").substringAfter('/').substringBefore('?').let { "/$it" }
        every { lastPathSegment } returns url.substringAfterLast('/')
        every { getQueryParameter(any()) } returns null
    }

    @Before
    fun setUp() {
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } answers { uriOf(firstArg()) }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/key" -> MockResponse().setResponseCode(302).setHeader("Location", "/keys/real")
                "/keys/real" -> MockResponse().setBody("0123456789abcdef")
                "/licence" -> MockResponse().setBody("licence")
                else -> MockResponse().setBody("segment-bytes")
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        unmockkStatic(Uri::class)
    }

    private fun url(path: String) = server.url(path).toString()

    private fun open(source: DataSource, path: String) {
        try {
            source.open(DataSpec.Builder().setUri(uriOf(url(path))).build())
            val buffer = ByteArray(4096)
            while (source.read(buffer, 0, buffer.size) != C.RESULT_END_OF_INPUT) Unit
        } finally {
            source.close()
        }
    }

    private fun assertCarriesHeaders(request: RecordedRequest?) {
        checkNotNull(request) { "no request reached the server" }
        assertEquals(headers["Referer"], request.getHeader("Referer"))
        assertEquals(headers["Origin"], request.getHeader("Origin"))
        assertEquals(headers["User-Agent"], request.getHeader("User-Agent"))
    }

    private fun takeRequest(): RecordedRequest? = server.takeRequest(5, TimeUnit.SECONDS)

    @Test
    fun `HLS segment and key requests carry the stream request headers`() {
        val hls = DefaultHlsDataSourceFactory(
            HlsParameterSetInitDataSource.Factory(PlayerPlaybackNetworking.createHttpDataSourceFactory(headers))
        )

        open(hls.createDataSource(C.DATA_TYPE_MEDIA), "/seg1.ts")
        assertCarriesHeaders(takeRequest())

        // The AES key, fetched through its own data source, also after the key server redirects.
        open(hls.createDataSource(C.DATA_TYPE_DRM), "/key")
        assertCarriesHeaders(takeRequest())
        assertCarriesHeaders(takeRequest())
    }

    @Test
    fun `licence requests carry the stream request headers and the addon licence headers`() {
        val licence = SportsLiveStreamLicence(
            SportsLiveStreamLicence.Scheme.WIDEVINE,
            url("/licence"),
            headers = mapOf("X-Licence-Token" to "t")
        )
        val callback = HttpMediaDrmCallback(licence.licenseUrl, PlayerPlaybackNetworking.createHttpDataSourceFactory(headers))
        sportsLiveLicenceHeaders(licence, headers).forEach { (key, value) -> callback.setKeyRequestProperty(key, value) }

        callback.executeKeyRequest(C.WIDEVINE_UUID, ExoMediaDrm.KeyRequest(byteArrayOf(1, 2, 3), licence.licenseUrl))

        val request = takeRequest()
        assertCarriesHeaders(request)
        assertEquals("t", request?.getHeader("X-Licence-Token"))
        assertEquals("POST", request?.method)
    }

    @Test
    fun `licence headers add to the stream headers, the addon licence header winning`() {
        val licence = SportsLiveStreamLicence(
            SportsLiveStreamLicence.Scheme.WIDEVINE,
            "https://example.invalid/licence",
            headers = mapOf("Referer" to "https://example.invalid/licence-page", "X-A" to "1")
        )
        assertEquals(
            mapOf("Referer" to "https://example.invalid/licence-page", "Origin" to "https://example.invalid", "User-Agent" to "AddonAgent/1.0", "X-A" to "1"),
            sportsLiveLicenceHeaders(licence, headers)
        )
    }
}
