package com.nuvio.tv.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #147 x #166: the probe race and check-ahead never call a DRM playlist ready. */
class SportsLiveLinkProberDrmTest {

    private val url = "https://cdn.example.invalid/live/index.m3u8"

    private val widevineMedia = """
        #EXTM3U
        #EXT-X-VERSION:6
        #EXT-X-TARGETDURATION:6
        #EXT-X-KEY:METHOD=SAMPLE-AES-CTR,URI="data:text/plain;base64,AAAA",KEYFORMAT="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed",KEYFORMATVERSIONS="1"
        #EXTINF:6.0,
        seg1.ts
    """.trimIndent()

    private val aesMedia = """
        #EXTM3U
        #EXT-X-TARGETDURATION:6
        #EXT-X-KEY:METHOD=AES-128,URI="https://cdn.example.invalid/key"
        #EXTINF:6.0,
        seg1.ts
    """.trimIndent()

    @Test
    fun `a Widevine playlist with no licence from its addon is unlicensed DRM`() {
        assertTrue(SportsLiveLinkProber.isUnlicensedDrm(url, widevineMedia))
    }

    @Test
    fun `plain AES-128 is ordinary encryption the player handles, not DRM`() {
        assertFalse(SportsLiveLinkProber.isUnlicensedDrm(url, aesMedia))
    }

    @Test
    fun `no body says nothing`() {
        assertFalse(SportsLiveLinkProber.isUnlicensedDrm(url, null))
    }
}
