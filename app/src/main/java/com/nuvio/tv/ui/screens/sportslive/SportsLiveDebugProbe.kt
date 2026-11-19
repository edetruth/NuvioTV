package com.nuvio.tv.ui.screens.sportslive

import android.content.Context
import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.sportslive.SportsLiveAddonFetch
import com.nuvio.tv.core.sportslive.SportsLiveDebugTiming
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.repository.SportsLiveRepository
import com.nuvio.tv.domain.repository.StreamRepository
import com.nuvio.tv.ui.screens.player.PlayerMediaSourceFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Debug builds only (#158): measures sports lookups and link starts outside the UI. Started with
 * `am start ... --es debugSportsProbe <sport word> --ei debugProbeGames N --ei debugProbeLinks M`:
 * for up to N live games whose sport or title holds the word, one fresh lookup (per-addon lines come
 * from [SportsLiveAddonFetch]), then the first M playable links each played on an offscreen player
 * with no timeout, one [SportsLiveDebugTiming] line per link. Never a url or host in a line.
 */
@Singleton
class SportsLiveDebugProbe @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: SportsLiveRepository,
    private val streamRepository: StreamRepository,
    private val resolver: SportsLiveZapResolver
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    fun run(filter: String, games: Int, links: Int, maxMs: Long, skipGames: Int, watch: Boolean = false) {
        if (!BuildConfig.IS_DEBUG_BUILD) return
        job?.cancel()
        job = scope.launch {
            val load = repo.latest.value ?: repo.load()
            val word = filter.lowercase()
            val picked = load.items
                .filter { it.isLive && !it.isChannel && !it.isFinished }
                .filter { word == "*" || (it.sport.orEmpty() + " " + it.catalogName + " " + it.meta.name).lowercase().contains(word) }
                .distinctBy { it.id }
                .drop(skipGames)
                .take(games)
            SportsLiveDebugTiming.log { "probe begin filter=$filter games=${picked.size}" }
            picked.forEachIndexed { index, item ->
                if (watch) probeWatch(index + skipGames, item, maxMs) else probeGame(index + skipGames, item, links, maxMs)
            }
            SportsLiveDebugTiming.log { "probe end" }
        }
    }

    private suspend fun probeGame(index: Int, item: SportsLiveItem, links: Int, maxMs: Long) {
        SportsLiveAddonFetch.shared.markSports(item.id)
        val startedAt = System.currentTimeMillis()
        var last: NetworkResult<List<AddonStreams>>? = null
        withTimeoutOrNull(60_000L) {
            streamRepository.getStreamsFromAllAddons(item.apiType, item.id, forceRefresh = true).collect { last = it }
        }
        val streams = (last as? NetworkResult.Success)?.data.orEmpty().flatMap { it.streams }
            .filterNot(SportsLiveZapResolver::isNonStream)
            .filter { !it.isExternal() && !it.isTorrent() && it.getStreamUrl() != null }
        SportsLiveDebugTiming.log {
            "probeGame g=$index sport=${item.sport} source=${SportsLiveDebugTiming.label(item.addonName)} " +
                "title=${SportsLiveDebugTiming.label(item.meta.name)} lookupMs=${System.currentTimeMillis() - startedAt} links=${streams.size}"
        }
        streams.take(links).forEachIndexed { linkIndex, stream ->
            probeLink(index, linkIndex, stream, maxMs)
            delay(1_000L)
        }
    }

    /**
     * What OK on [item] does: the real lookup (its own [SportsLiveDebugTiming] line), then the stream
     * found played as [probeLink] does. With nothing found, the links that reach the shared search
     * later (in the background) are counted 30 s on.
     */
    private suspend fun probeWatch(index: Int, item: SportsLiveItem, maxMs: Long) {
        val found = resolver.lookup(item, forceRefresh = true)
        SportsLiveDebugTiming.log { "probeWatch g=$index outcome=${found::class.simpleName} title=${SportsLiveDebugTiming.label(item.meta.name)}" }
        if (found is SportsLiveLookup.Found) {
            probeLink(index, 0, found.stream, maxMs)
            return
        }
        delay(30_000L)
        val late = streamRepository.getCachedStreamsFromAllAddons(item.apiType, item.id).orEmpty()
            .flatMap { it.streams }.filterNot(SportsLiveZapResolver::isNonStream)
        SportsLiveDebugTiming.log { "probeWatch g=$index lateLinks=${late.size}" }
    }

    @OptIn(UnstableApi::class)
    private suspend fun probeLink(game: Int, index: Int, stream: Stream, maxMs: Long) {
        val url = stream.getStreamUrl() ?: return
        val factory = PlayerMediaSourceFactory(context)
        val player = buildSportsLiveLightPlayer(context)
        val reader = if (Build.VERSION.SDK_INT >= 29) {
            ImageReader.newInstance(1280, 720, ImageFormat.PRIVATE, 3).apply {
                setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close() }, null)
            }
        } else {
            null
        }
        reader?.surface?.let(player::setVideoSurface)
        player.volume = 0f
        val startedAt = System.currentTimeMillis()
        var firstFrameMs = -1L
        var readyMs = -1L
        var firstFailedRequestMs = -1L
        var failedRequests = 0
        var error: PlaybackException? = null
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (firstFrameMs < 0) firstFrameMs = System.currentTimeMillis() - startedAt
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && readyMs < 0) readyMs = System.currentTimeMillis() - startedAt
            }

            override fun onPlayerError(e: PlaybackException) {
                error = e
            }
        })
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onLoadError(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData,
                e: IOException,
                wasCanceled: Boolean
            ) {
                if (wasCanceled) return
                failedRequests++
                if (firstFailedRequestMs < 0) firstFailedRequestMs = System.currentTimeMillis() - startedAt
            }
        })
        val marks = LongArray(4) { -1 } // bytes at 8 s, 15 s; buffered at 8 s, 15 s
        var firstByteMs = -1L
        try {
            player.playSportsLiveStream(context, factory, stream, url)
            while (true) {
                delay(250L)
                val t = System.currentTimeMillis() - startedAt
                if (firstByteMs < 0 && factory.bytesLoaded > 0) firstByteMs = t
                if (t >= 8_000L && marks[0] < 0) { marks[0] = factory.bytesLoaded; marks[2] = player.totalBufferedDuration }
                if (t >= 15_000L && marks[1] < 0) { marks[1] = factory.bytesLoaded; marks[3] = player.totalBufferedDuration }
                if (firstFrameMs >= 0 || error != null || t >= maxMs) break
            }
        } finally {
            val outcome = when {
                firstFrameMs >= 0 -> "frame"
                error != null -> "error:${error?.errorCodeName}"
                else -> "noframe"
            }
            SportsLiveDebugTiming.log {
                "probeLink g=$game l=$index link=${sportsLiveLinkId(url)} source=${SportsLiveDebugTiming.label(stream.addonName)} " +
                    "outcome=$outcome ttff=$firstFrameMs ready=$readyMs firstByte=$firstByteMs " +
                    "failedReq=$failedRequests firstFailedReq=$firstFailedRequestMs " +
                    "bytes8=${marks[0]} bytes15=${marks[1]} buf8=${marks[2]} buf15=${marks[3]} total=${factory.bytesLoaded} " +
                    "hls=${url.contains(".m3u8", ignoreCase = true)}"
            }
            player.release()
            reader?.close()
        }
    }
}
