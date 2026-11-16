package com.nuvio.tv.ui.screens.sportslive

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.tv.core.debrid.DirectDebridResolver
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.screens.player.PlayerMediaSourceFactory

/**
 * The lightweight ExoPlayer of the dual screen's panes and the hover preview.
 * Decoder fallback as in the full player, so a second player can fall back to a software decoder.
 * The FFmpeg audio renderer backs up the platform ones, as in the full player: live channels often
 * carry E-AC-3, which devices without that decoder could not play at all. The user's decoder
 * priority setting of the full player is not applied here.
 */
@OptIn(UnstableApi::class)
internal fun buildSportsLiveLightPlayer(appContext: Context): ExoPlayer = ExoPlayer.Builder(
    appContext,
    DefaultRenderersFactory(appContext)
        .setEnableDecoderFallback(true)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
)
    .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
    .build()

/**
 * The one correct way to describe a Sports & Live media source: the normalized playback url, the
 * proxy request headers AND response headers, and the filename hint — exactly what the full player's
 * [PlayerRuntimeControllerStreams] source switch uses. Every sports playback path builds through this
 * so none drops a header map and 403s where the details -> play path would have worked.
 */
internal data class SportsLiveMediaSourceSpec(
    val url: String,
    val requestHeaders: Map<String, String>,
    val responseHeaders: Map<String, String>,
    val filename: String?
)

internal fun sportsLiveMediaSourceSpec(stream: Stream, url: String): SportsLiveMediaSourceSpec {
    val request = PlayerMediaSourceFactory.normalizePlaybackRequest(
        url = url,
        headers = stream.behaviorHints?.proxyHeaders?.request
    )
    return SportsLiveMediaSourceSpec(
        url = request.url,
        requestHeaders = request.headers,
        responseHeaders = stream.behaviorHints?.proxyHeaders?.response.orEmpty(),
        filename = stream.behaviorHints?.filename
    )
}

/**
 * Plays [stream] (at [url], its stream url) on this player. The media source comes from the
 * player's [PlayerMediaSourceFactory], so the stream's proxy request and response headers are
 * used as in the full player.
 */
@OptIn(UnstableApi::class)
internal fun ExoPlayer.playSportsLiveStream(
    appContext: Context,
    mediaSourceFactory: PlayerMediaSourceFactory,
    stream: Stream,
    url: String
) {
    val spec = sportsLiveMediaSourceSpec(stream, url)
    setMediaSource(
        mediaSourceFactory.createMediaSource(
            context = appContext,
            url = spec.url,
            headers = spec.requestHeaders,
            filename = spec.filename,
            responseHeaders = spec.responseHeaders
        )
    )
    prepare()
    playWhenReady = true
}

/**
 * Whether a light player can play [stream] as it is: a plain url, with no Stream screen
 * preparation (server, YouTube, debrid) and no torrent or external player.
 */
internal suspend fun sportsLiveLightPlayable(stream: Stream, directDebridResolver: DirectDebridResolver): Boolean =
    !stream.isExternal() && !stream.isTorrent() && stream.getStreamUrl() != null &&
        !sportsLiveNeedsPreparation(stream, directDebridResolver)
