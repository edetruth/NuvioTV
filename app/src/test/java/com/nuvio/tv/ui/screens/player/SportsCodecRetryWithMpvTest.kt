package com.nuvio.tv.ui.screens.player

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsCodecRetryWithMpvTest {

    private fun retries(
        errorCode: Int,
        hasRenderedFirstFrame: Boolean = false,
        onMpv: Boolean = false,
        alreadyRetried: Boolean = false,
        probeAttempted: Boolean = false,
        isProtected: Boolean = false
    ) = sportsRetriesWithMpv(errorCode, hasRenderedFirstFrame, onMpv, alreadyRetried, probeAttempted, isProtected)

    @Test
    fun `a decoder or unsupported format failure at start is retried on mpv`() {
        for (code in listOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
        )) {
            assertTrue("code $code", retries(code))
        }
    }

    @Test
    fun `the same link is retried on mpv only once`() {
        assertFalse(retries(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, alreadyRetried = true))
    }

    @Test
    fun `a link already on mpv is not retried, it fails over`() {
        assertFalse(retries(PlaybackException.ERROR_CODE_DECODING_FAILED, onMpv = true))
    }

    @Test
    fun `a link that already played is left to the recovery ladder`() {
        assertFalse(retries(PlaybackException.ERROR_CODE_DECODING_FAILED, hasRenderedFirstFrame = true))
    }

    @Test
    fun `a protected link is never retried on mpv`() {
        assertFalse(retries(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, isProtected = true))
    }

    @Test
    fun `an unparseable container is retried on mpv only after the parsing probe`() {
        assertFalse(retries(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED))
        assertTrue(retries(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, probeAttempted = true))
    }

    @Test
    fun `network, http and DRM errors are not codec failures`() {
        assertFalse(retries(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
        assertFalse(retries(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertFalse(retries(PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED))
        assertFalse(retries(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW))
    }
}
