package com.nuvio.tv.core.sportslive

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsLiveLinkCauseTest {

    // Classification from the player's error

    @Test
    fun `DRM error codes classify as DRM whatever the request said`() {
        assertEquals(SportsLiveLinkCause.DRM, SportsLiveLinkCause.classify(PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED))
        assertEquals(SportsLiveLinkCause.DRM, SportsLiveLinkCause.classify(PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED, 403))
        assertEquals(SportsLiveLinkCause.DRM, SportsLiveLinkCause.classify(PlaybackException.ERROR_CODE_DRM_UNSPECIFIED))
    }

    @Test
    fun `a refused request classifies as missing headers`() {
        val bad = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        assertEquals(SportsLiveLinkCause.HEADERS, SportsLiveLinkCause.classify(bad, 403))
        assertEquals(SportsLiveLinkCause.HEADERS, SportsLiveLinkCause.classify(bad, 401))
    }

    @Test
    fun `gone or region blocked links classify as expired or geo`() {
        val bad = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        assertEquals(SportsLiveLinkCause.EXPIRED_OR_GEO, SportsLiveLinkCause.classify(bad, 404))
        assertEquals(SportsLiveLinkCause.EXPIRED_OR_GEO, SportsLiveLinkCause.classify(bad, 410))
        assertEquals(SportsLiveLinkCause.EXPIRED_OR_GEO, SportsLiveLinkCause.classify(bad, 451))
    }

    @Test
    fun `an unknown host or refused connection classifies as a dead host`() {
        assertEquals(
            SportsLiveLinkCause.DEAD_HOST,
            SportsLiveLinkCause.classify(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, deadHost = true)
        )
        assertEquals(SportsLiveLinkCause.DEAD_HOST, SportsLiveLinkCause.classify(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
    }

    @Test
    fun `decoder and container errors classify as codec`() {
        for (code in listOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED
        )) {
            assertEquals(SportsLiveLinkCause.CODEC, SportsLiveLinkCause.classify(code))
        }
    }

    @Test
    fun `anything else is other`() {
        assertEquals(SportsLiveLinkCause.OTHER, SportsLiveLinkCause.classify(PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
        assertEquals(SportsLiveLinkCause.OTHER, SportsLiveLinkCause.classify(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 500))
    }

    // DRM in manifests: DASH

    @Test
    fun `a DASH manifest with ContentProtection is protected`() {
        assertTrue(SportsLiveManifestDrm.isProtected(DASH_WIDEVINE))
        assertTrue(SportsLiveManifestDrm.isProtected(DASH_PREFIXED))
    }

    @Test
    fun `a clear DASH manifest is not protected`() {
        assertFalse(SportsLiveManifestDrm.isProtected(DASH_CLEAR))
        assertEquals(SportsLiveManifestDrm.Kind.DASH, SportsLiveManifestDrm.kindOf(DASH_CLEAR))
    }

    // DRM in manifests: HLS

    @Test
    fun `HLS keys with a Widevine, PlayReady or FairPlay key format are protected`() {
        assertTrue(SportsLiveManifestDrm.isProtected(hlsWithKey("""METHOD=SAMPLE-AES-CTR,URI="data:text/plain;base64,AAAA",KEYFORMAT="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed",KEYFORMATVERSIONS="1"""")))
        assertTrue(SportsLiveManifestDrm.isProtected(hlsWithKey("""METHOD=SAMPLE-AES,URI="data:text/plain;base64,AAAA",KEYFORMAT="com.microsoft.playready"""")))
        assertTrue(SportsLiveManifestDrm.isProtected(hlsWithKey("""METHOD=SAMPLE-AES,URI="skd://example.invalid/key",KEYFORMAT="com.apple.streamingkeydelivery"""")))
    }

    @Test
    fun `a Widevine session key in a multivariant playlist is protected`() {
        val master = """
            #EXTM3U
            #EXT-X-SESSION-KEY:METHOD=SAMPLE-AES,URI="data:text/plain;base64,AAAA",KEYFORMAT="com.widevine"
            #EXT-X-STREAM-INF:BANDWIDTH=800000
            low/index.m3u8
        """.trimIndent()
        assertTrue(SportsLiveManifestDrm.isProtected(master))
    }

    @Test
    fun `plain AES-128 and identity keys are not DRM`() {
        assertFalse(SportsLiveManifestDrm.isProtected(hlsWithKey("""METHOD=AES-128,URI="https://example.invalid/key.bin"""")))
        assertFalse(SportsLiveManifestDrm.isProtected(hlsWithKey("""METHOD=SAMPLE-AES,URI="https://example.invalid/key.bin",KEYFORMAT="identity"""")))
        assertFalse(SportsLiveManifestDrm.isProtected(hlsWithKey("""METHOD=NONE""")))
        assertFalse(SportsLiveManifestDrm.isProtected(HLS_CLEAR))
    }

    @Test
    fun `text that is not a manifest is not protected`() {
        assertNull(SportsLiveManifestDrm.kindOf("<html><body>ContentProtection</body></html>"))
        assertFalse(SportsLiveManifestDrm.isProtected("<html><body>ContentProtection</body></html>"))
    }

    @Test
    fun `the first variant of a multivariant playlist is found`() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360

            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2400000
            high/index.m3u8
        """.trimIndent()
        assertEquals("low/index.m3u8", SportsLiveManifestDrm.firstVariantUri(master))
        assertNull(SportsLiveManifestDrm.firstVariantUri(HLS_CLEAR))
        assertNull(SportsLiveManifestDrm.firstVariantUri(DASH_CLEAR))
    }

    private fun hlsWithKey(attributes: String) = """
        #EXTM3U
        #EXT-X-VERSION:6
        #EXT-X-TARGETDURATION:6
        #EXT-X-MEDIA-SEQUENCE:100
        #EXT-X-KEY:$attributes
        #EXTINF:6.0,
        seg100.ts
    """.trimIndent()

    private companion object {
        val HLS_CLEAR = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXTINF:6.0,
            seg1.ts
        """.trimIndent()

        val DASH_CLEAR = """
            <?xml version="1.0" encoding="UTF-8"?>
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="dynamic">
              <Period id="1">
                <AdaptationSet mimeType="video/mp4">
                  <Representation id="v1" bandwidth="800000" codecs="avc1.4d401f"/>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()

        val DASH_WIDEVINE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="dynamic">
              <Period id="1">
                <AdaptationSet mimeType="video/mp4">
                  <ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="cenc"/>
                  <ContentProtection schemeIdUri="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"/>
                  <Representation id="v1" bandwidth="800000"/>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()

        val DASH_PREFIXED = """
            <dash:MPD xmlns:dash="urn:mpeg:dash:schema:mpd:2011">
              <dash:Period><dash:AdaptationSet><dash:ContentProtection schemeIdUri="urn:uuid:9a04f079-9840-4286-ab92-e65be0885f95"/></dash:AdaptationSet></dash:Period>
            </dash:MPD>
        """.trimIndent()
    }
}
