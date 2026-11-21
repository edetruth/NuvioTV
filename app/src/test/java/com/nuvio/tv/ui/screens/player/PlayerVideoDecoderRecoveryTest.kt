package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlaybackException
import com.nuvio.tv.ui.screens.player.PlayerVideoDecoderRecovery.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerVideoDecoderRecoveryTest {

    private fun decide(
        errorCode: Int,
        audio: Boolean = false,
        exo: Boolean = true,
        second: Boolean = false,
        hardwareRetried: Boolean = false,
        softwareTried: Boolean = false,
        above1080p: Boolean = false
    ) = PlayerVideoDecoderRecovery.decide(
        errorCode = errorCode,
        audioRendererFailed = audio,
        onExoPlayer = exo,
        secondDecoderActive = second,
        hardwareRetried = hardwareRetried,
        softwareTried = softwareTried,
        videoAbove1080p = above1080p
    )

    @Test
    fun `every video decoder failure retries on the software decoder`() {
        listOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
        ).forEach { code -> assertEquals("code $code", Action.RETRY_SOFTWARE, decide(code)) }
    }

    @Test
    fun `network, parsing and audio track errors are not decoder failures`() {
        listOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_UNSPECIFIED
        ).forEach { code -> assertEquals("code $code", Action.NONE, decide(code)) }
    }

    @Test
    fun `the software retry runs once per link`() {
        assertEquals(Action.NONE, decide(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, softwareTried = true))
        assertEquals(
            Action.NONE,
            decide(PlaybackException.ERROR_CODE_DECODING_FAILED, softwareTried = true, hardwareRetried = true)
        )
    }

    @Test
    fun `an audio decoder failure is left to the audio fallbacks`() {
        assertEquals(Action.NONE, decide(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, audio = true))
        assertEquals(Action.NONE, decide(PlaybackException.ERROR_CODE_DECODING_FAILED, audio = true, second = true))
    }

    @Test
    fun `mpv has its own decoders and is left alone`() {
        assertEquals(Action.NONE, decide(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, exo = false))
    }

    @Test
    fun `video above 1080p gets no software retry`() {
        assertEquals(
            Action.NONE,
            decide(PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES, above1080p = true)
        )
        // A preview still lets go of its decoder for a 4K game.
        assertEquals(
            Action.YIELD_AND_RETRY_HARDWARE,
            decide(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, second = true, above1080p = true)
        )
    }

    @Test
    fun `a decoder taken away gets the hardware decoder again first, then software`() {
        val reclaimed = PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED
        assertEquals(Action.RETRY_HARDWARE, decide(reclaimed))
        assertEquals(Action.RETRY_SOFTWARE, decide(reclaimed, hardwareRetried = true))
        assertEquals(Action.NONE, decide(reclaimed, hardwareRetried = true, softwareTried = true))
    }

    @Test
    fun `with a preview holding a second decoder it yields and the game retries on hardware, once`() {
        val code = PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
        assertEquals(Action.YIELD_AND_RETRY_HARDWARE, decide(code, second = true))
        assertEquals(Action.RETRY_SOFTWARE, decide(code, second = true, hardwareRetried = true))
        assertEquals(Action.NONE, decide(code, second = true, hardwareRetried = true, softwareTried = true))
    }

    @Test
    fun `live rejoins the live edge and anything else keeps its position`() {
        assertEquals(0L, PlayerVideoDecoderRecovery.resumePositionMs(isLive = true, positionMs = 3_600_000L))
        assertEquals(1_234_000L, PlayerVideoDecoderRecovery.resumePositionMs(isLive = false, positionMs = 1_234_000L))
        assertEquals(0L, PlayerVideoDecoderRecovery.resumePositionMs(isLive = false, positionMs = -5L))
    }

    @Test
    fun `only contention-like failures mark the decoder limit`() {
        assertTrue(PlayerVideoDecoderRecovery.showsDecoderLimit(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        assertTrue(PlayerVideoDecoderRecovery.showsDecoderLimit(PlaybackException.ERROR_CODE_DECODING_FAILED))
        assertTrue(PlayerVideoDecoderRecovery.showsDecoderLimit(PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED))
        assertFalse(PlayerVideoDecoderRecovery.showsDecoderLimit(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
        assertFalse(
            PlayerVideoDecoderRecovery.showsDecoderLimit(PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES)
        )
    }

    private fun rendererError(mime: String, width: Int = Format.NO_VALUE, height: Int = Format.NO_VALUE) =
        ExoPlaybackException.createForRenderer(
            RuntimeException("decoder"),
            "renderer",
            0,
            Format.Builder().setSampleMimeType(mime).setWidth(width).setHeight(height).build(),
            C.FORMAT_HANDLED,
            false,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
        )

    @Test
    fun `the failing renderer is read from the error`() {
        assertTrue(rendererError(MimeTypes.AUDIO_E_AC3).failingRendererIsAudio())
        assertFalse(rendererError(MimeTypes.VIDEO_H264).failingRendererIsAudio())
    }

    @Test
    fun `above 1080p is read from the failing format, else the playing one`() {
        assertTrue(rendererError(MimeTypes.VIDEO_H265, 3840, 2160).failingVideoAbove1080p(null))
        assertFalse(rendererError(MimeTypes.VIDEO_H264, 1920, 1080).failingVideoAbove1080p(null))
        val playing4k = Format.Builder().setWidth(3840).setHeight(2160).build()
        assertTrue(rendererError(MimeTypes.VIDEO_H265).failingVideoAbove1080p(playing4k))
    }
}
