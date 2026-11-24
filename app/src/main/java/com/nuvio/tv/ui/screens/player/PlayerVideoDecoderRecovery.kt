package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.util.findCause
import com.nuvio.tv.ui.screens.sportslive.preview.SportsLiveDecoderLimit
import com.nuvio.tv.ui.screens.sportslive.preview.SportsLiveSecondDecoders
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * Video decoder failure recovery (live.23). The software-decoder retry is ported from NuvioPlusIPTV
 * (github.com/homelessbrian/NuvioPlusIPTV, GPL-3.0, commit 08c551956: trySoftwareVideoDecoderFallback and
 * softwareVideoCodecSelector); the second-decoder yield, the live-edge rule and the decision object are ours.
 */

/** Video decoder failures a fresh decoder, or Android's software one, may get past. */
internal val VIDEO_DECODER_ERROR_CODES = setOf(
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
)

/**
 * What the full player does when its video decoder fails. Each step runs at most once per stream link, so
 * nothing loops: after them the normal chain (mpv retry, next link, error screen) takes over.
 */
internal object PlayerVideoDecoderRecovery {
    enum class Action {
        /** Not a video decoder failure, or every step was already tried on this link. */
        NONE,

        /** A preview held a second decoder: it lets go, and the game restarts on the hardware decoder. */
        YIELD_AND_RETRY_HARDWARE,

        /** The TV took the decoder away for a moment: the same hardware decoder again. */
        RETRY_HARDWARE,

        /** The hardware decoder failed: once more on Android's software decoder. */
        RETRY_SOFTWARE,
    }

    fun decide(
        errorCode: Int,
        /** The failing renderer is known to be an audio one (those have their own fallback ladder). */
        audioRendererFailed: Boolean,
        onExoPlayer: Boolean,
        /** A preview or peek holds a second decoder beside this player. */
        secondDecoderActive: Boolean,
        /** This link already had its hardware retry. */
        hardwareRetried: Boolean,
        /** This link already plays (or played) on the software decoder. */
        softwareTried: Boolean,
        /** The video is above 1080p: too heavy for a TV box's software decoder, so no software retry. */
        videoAbove1080p: Boolean = false,
    ): Action {
        if (!onExoPlayer || audioRendererFailed || errorCode !in VIDEO_DECODER_ERROR_CODES) return Action.NONE
        if (secondDecoderActive && !hardwareRetried) return Action.YIELD_AND_RETRY_HARDWARE
        if (errorCode == PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED && !hardwareRetried) {
            return Action.RETRY_HARDWARE
        }
        if (!softwareTried && !videoAbove1080p) return Action.RETRY_SOFTWARE
        return Action.NONE
    }

    /**
     * Whether a failure while a preview held a second decoder shows the TV cannot run two: a decoder that would
     * not start, failed or was taken away. A format the TV cannot play at all says nothing about two decoders.
     */
    fun showsDecoderLimit(errorCode: Int): Boolean =
        errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
            errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
            errorCode == PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED

    /**
     * Whether the "compatibility mode" note shows now: the link [currentUrl] plays on the software decoder
     * ([softwareUrl]) and the note was not shown for it yet ([notedUrl]). Once per link.
     */
    fun compatibilityNoteDue(softwareUrl: String?, currentUrl: String, notedUrl: String?): Boolean =
        currentUrl.isNotBlank() && softwareUrl == currentUrl && notedUrl != currentUrl

    /**
     * A second decoder runs beside this player (review M3, live.24): a preview or peek ([previews]), or the dual pane
     * held on until this full player's first frame ([dualHeld]). The held pane counts too, so a failure during the hold
     * lets it go and retries on the hardware decoder first, never straight to the software one.
     */
    fun secondDecoderBeside(previews: Boolean, dualHeld: Boolean): Boolean = previews || dualHeld

    /**
     * Whether a failure while a second decoder ran marks this TV's decoder limit (#106): only a preview's. The dual
     * pane held for the hand-over is a moment's overlap dual screen itself proves the TV can run.
     */
    fun notesDecoderLimit(errorCode: Int, previews: Boolean): Boolean = previews && showsDecoderLimit(errorCode)

    /** Where the retry starts: a live stream rejoins the live edge (0 = default position), anything else resumes. */
    fun resumePositionMs(isLive: Boolean, positionMs: Long): Long =
        if (isLive) 0L else positionMs.coerceAtLeast(0L)
}

/** The renderer that failed, when the error says which: an audio one, or a video one. */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlaybackException.failingRendererIsAudio(): Boolean {
    val mime = (this as? ExoPlaybackException)?.rendererFormat?.sampleMimeType
        ?: findCause<MediaCodecRenderer.DecoderInitializationException>()?.mimeType
        ?: return false
    return MimeTypes.isAudio(mime)
}

/** The failing (or else the playing) video is above 1080p. */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlaybackException.failingVideoAbove1080p(playing: Format?): Boolean {
    val format = (this as? ExoPlaybackException)?.rendererFormat?.takeIf { it.width > 0 } ?: playing ?: return false
    return format.width > 1920 || format.height > 1088
}

/**
 * Runs [PlayerVideoDecoderRecovery] for [error]: true when the stream is being rebuilt (a hardware retry
 * after the second decoder let go, or a software-decoder retry), false to let the rest of the chain run.
 * Logged to Report a problem's playback events without the link.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.tryVideoDecoderRecovery(error: PlaybackException): Boolean {
    val url = currentStreamUrl
    if (url.isBlank()) return false
    val previews = SportsLiveSecondDecoders.active
    val dualHeld = com.nuvio.tv.ui.screens.sportslive.SportsLiveFullScreenHold.held.value != null
    val action = PlayerVideoDecoderRecovery.decide(
        errorCode = error.errorCode,
        audioRendererFailed = error.failingRendererIsAudio(),
        onExoPlayer = !isUsingMpvEngine(),
        secondDecoderActive = PlayerVideoDecoderRecovery.secondDecoderBeside(previews, dualHeld),
        hardwareRetried = videoDecoderHardwareRetriedUrl == url,
        softwareTried = softwareVideoDecoderUrl == url,
        videoAbove1080p = error.failingVideoAbove1080p(_exoPlayer?.videoFormat),
    )
    when (action) {
        PlayerVideoDecoderRecovery.Action.NONE -> return false
        PlayerVideoDecoderRecovery.Action.YIELD_AND_RETRY_HARDWARE -> {
            videoDecoderHardwareRetriedUrl = url
            SportsLiveSecondDecoders.yieldAll()
            // The dual pane held for the hand-over lets its decoder go too (review M3).
            if (dualHeld) com.nuvio.tv.ui.screens.sportslive.SportsLiveFullScreenHold.release()
            // This TV could not run a preview beside a game: no more peeks over the player (#106).
            if (PlayerVideoDecoderRecovery.notesDecoderLimit(error.errorCode, previews)) {
                SportsLiveDecoderLimit.init(context)
                SportsLiveDecoderLimit.note()
            }
        }
        PlayerVideoDecoderRecovery.Action.RETRY_HARDWARE -> videoDecoderHardwareRetriedUrl = url
        PlayerVideoDecoderRecovery.Action.RETRY_SOFTWARE -> softwareVideoDecoderUrl = url
    }
    val player = _exoPlayer
    val positionMs = PlayerVideoDecoderRecovery.resumePositionMs(
        isLive = player?.isCurrentMediaItemLive == true,
        positionMs = player?.currentPosition ?: 0L
    )
    val line = "[decoder] video decoder failed (${error.errorCodeName}), retrying: " + when (action) {
        PlayerVideoDecoderRecovery.Action.YIELD_AND_RETRY_HARDWARE -> "preview stopped, hardware decoder"
        PlayerVideoDecoderRecovery.Action.RETRY_HARDWARE -> "hardware decoder again"
        else -> "software decoder"
    } + if (positionMs > 0L) " at ${positionMs / 1000}s" else " from the live edge or start"
    Log.i(PlayerRuntimeController.TAG, line)
    LoggingDataSource.recordEvent(line)
    val paused = userPausedManually
    resetErrorRetryState()
    errorRetryJob = scope.launch {
        showRecoveryOverlay()
        releasePlayer(flushPlaybackState = false)
        if (positionMs > 0L) {
            _uiState.update { it.copy(pendingSeekPosition = positionMs) }
        }
        reinitializeCurrentStream(startPaused = paused)
    }
    return true
}

/**
 * Review L2 (live.24): the full player's request was refused with a connection-limit answer ([responseCode]: 403, 429
 * or 503) while the dual pane held for it plays the same game, which may hold the source's only connection. The held
 * pane lets go and the same link is tried once more. True when the stream is being rebuilt.
 */
internal fun PlayerRuntimeController.tryHeldPaneConnectionRetry(responseCode: Int?): Boolean {
    val url = currentStreamUrl
    if (url.isBlank()) return false
    val hold = com.nuvio.tv.ui.screens.sportslive.SportsLiveFullScreenHold
    val heldForThis = hold.covers(contentId)
    if (!com.nuvio.tv.ui.screens.sportslive.sportsLiveHeldPaneRetry(heldForThis, responseCode, heldPaneRetriedUrl == url)) return false
    heldPaneRetriedUrl = url
    hold.release()
    val line = "[hold] link refused ($responseCode) beside the dual pane held for it: pane released, same link again"
    Log.i(PlayerRuntimeController.TAG, line)
    LoggingDataSource.recordEvent(line)
    val paused = userPausedManually
    resetErrorRetryState()
    errorRetryJob = scope.launch {
        showRecoveryOverlay()
        releasePlayer(flushPlaybackState = false)
        reinitializeCurrentStream(startPaused = paused)
    }
    return true
}

/**
 * A short toast when the picture comes from the software decoder ([PlayerVideoDecoderRecovery.compatibilityNoteDue]),
 * so a choppier picture has a reason. No link or decoder name on screen.
 */
internal fun PlayerRuntimeController.maybeShowCompatibilityNote() {
    val url = currentStreamUrl
    if (!PlayerVideoDecoderRecovery.compatibilityNoteDue(softwareVideoDecoderUrl, url, compatibilityNoteShownUrl)) return
    compatibilityNoteShownUrl = url
    android.widget.Toast.makeText(
        context,
        context.getString(com.nuvio.tv.R.string.player_software_decoder_note),
        android.widget.Toast.LENGTH_LONG
    ).show()
}

/** Only Android's software decoders for video; audio, and any format with no software decoder, keep [base]. */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun softwareVideoCodecSelector(base: MediaCodecSelector): MediaCodecSelector =
    MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
        val all = base.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
        if (!MimeTypes.isVideo(mimeType)) return@MediaCodecSelector all
        // Nothing in software (protected streams, Dolby Vision): the hardware list, and the normal chain after.
        all.filter(::isSoftwareDecoder).ifEmpty { all }
    }

/**
 * Debug builds only: `adb shell setprop debug.nuvio.fail_hw_video 1` makes every hardware-decoded video fail to
 * start (a decoder that does not exist), so the software retry can be tested on a TV. `setprop ... 0` turns it off.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun debugFailingHardwareVideoSelector(base: MediaCodecSelector): MediaCodecSelector {
    if (!BuildConfig.IS_DEBUG_BUILD || debugSystemProperty("debug.nuvio.fail_hw_video") != "1") return base
    Log.w(PlayerRuntimeController.TAG, "VIDEO_DECODER: debug.nuvio.fail_hw_video is set, hardware video fails")
    return MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
        val all = base.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
        if (!MimeTypes.isVideo(mimeType) || all.isEmpty()) return@MediaCodecSelector all
        listOf(
            MediaCodecInfo.newInstance(
                "nuvio.debug.failing.decoder", mimeType, mimeType, null,
                true, false, true, false, false
            )
        )
    }
}

private fun debugSystemProperty(name: String): String? = runCatching {
    Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as? String
}.getOrNull()
