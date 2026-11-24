package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.net.URI
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** Whether a link is live now (#162). */
enum class LiveVerdict {
    /** Its media playlist has recent segments. */
    LIVE,

    /** It answered, but nothing is live: no segments, an ended playlist, stale segments, or a master alone. */
    NOT_LIVE,

    /** It could not be read (not an HLS link, no answer, an error): no verdict either way. */
    UNKNOWN
}

/**
 * Reads an HLS playlist for [SportsLiveLiveCheck] (#162). A master playlist answering is never enough
 * (#110): only a media playlist with segments that are recent counts as live.
 */
object SportsLiveHlsPlaylist {
    private const val HEADER = "#EXTM3U"
    private const val VARIANT = "#EXT-X-STREAM-INF"
    private const val END_LIST = "#EXT-X-ENDLIST"
    private const val SEGMENT = "#EXTINF:"
    private const val SEQUENCE = "#EXT-X-MEDIA-SEQUENCE:"
    private const val TARGET = "#EXT-X-TARGETDURATION:"
    private const val DATE_TIME = "#EXT-X-PROGRAM-DATE-TIME:"

    /** Segments whose wall-clock end is within this of now are recent. */
    const val RECENT_MS = 2 * 60_000L

    fun isPlaylist(body: String?): Boolean = body?.trimStart()?.startsWith(HEADER) == true

    fun isMaster(body: String): Boolean = VARIANT in body

    /** The first variant's url of master [body] read from [baseUrl], or null. */
    fun firstVariant(body: String, baseUrl: String): String? {
        val lines = body.lineSequence().map(String::trim).toList()
        val at = lines.indexOfFirst { it.startsWith(VARIANT) }
        if (at < 0) return null
        val uri = lines.drop(at + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return null
        return resolve(baseUrl, uri)
    }

    /** A media playlist's state: its segment count, media sequence, last segment and recency. */
    data class Media(
        val segments: Int,
        val sequence: Long,
        val lastSegment: String?,
        val ended: Boolean,
        val targetDurationS: Int?,
        /** The wall-clock end of its last segment, when it dates its segments; else null. */
        val lastEndMs: Long?
    )

    fun media(body: String): Media {
        var segments = 0
        var sequence = 0L
        var last: String? = null
        var target: Int? = null
        var dated: Long? = null
        var sinceDated = 0.0
        var pendingDuration: Double? = null
        for (raw in body.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith(SEQUENCE) -> sequence = line.removePrefix(SEQUENCE).trim().toLongOrNull() ?: 0L
                line.startsWith(TARGET) -> target = line.removePrefix(TARGET).trim().toDoubleOrNull()?.toInt()
                line.startsWith(DATE_TIME) -> {
                    dated = parseTime(line.removePrefix(DATE_TIME).trim())
                    sinceDated = 0.0
                }
                line.startsWith(SEGMENT) -> pendingDuration = line.removePrefix(SEGMENT).substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                line.startsWith("#") -> Unit
                else -> if (pendingDuration != null) {
                    segments++
                    last = line
                    if (dated != null) sinceDated += pendingDuration!!
                    pendingDuration = null
                }
            }
        }
        val lastEnd = dated?.let { it + (sinceDated * 1_000).toLong() }
        return Media(segments, sequence, last, END_LIST in body, target, lastEnd)
    }

    /**
     * [first]'s verdict on its own: no segments or an ended playlist is not live; dated segments decide by
     * their age at [nowMs]; undated ones need a second read ([advanced]), so null.
     */
    fun verdict(first: Media, nowMs: Long): LiveVerdict? {
        if (first.ended || first.segments == 0) return LiveVerdict.NOT_LIVE
        val end = first.lastEndMs ?: return null
        return if (nowMs - end <= RECENT_MS) LiveVerdict.LIVE else LiveVerdict.NOT_LIVE
    }

    /** A second read of an undated playlist: it moved on (new segments) since [first]. */
    fun advanced(first: Media, second: Media): Boolean =
        !second.ended && second.segments > 0 &&
            (second.sequence > first.sequence || (second.lastSegment != null && second.lastSegment != first.lastSegment))

    private fun parseTime(text: String): Long? = try {
        OffsetDateTime.parse(text).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }

    private fun resolve(base: String, uri: String): String? = try {
        URI(base).resolve(uri).toString()
    } catch (_: Exception) {
        null
    }
}

/**
 * Checks whether a game's links are live (#162), one link per addon (rate limits): its first readable
 * link (#147's order: the caller's), fetched with the same redirect and header rules as the link check
 * ([fetch]). A master playlist leads to its first variant; an undated media playlist is read twice, one
 * target duration apart, and is live only when it moved on. Urls are never logged.
 */
class SportsLiveLiveCheck(
    private val fetch: suspend (url: String, headers: Map<String, String>) -> ManifestResponse?,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val wait: suspend (Long) -> Unit = { delay(it) }
) {
    /** What a probe of a game's links found. */
    data class Outcome(
        /** The first live link; null when none is. */
        val live: Stream?,
        /** At least one link could be read (an HLS link): with none, the game cannot be checked at all. */
        val checkable: Boolean
    )

    /** [streams] in order, one per addon: the first live one, as soon as it is found. */
    suspend fun firstLive(streams: List<Stream>): Outcome {
        val perAddon = LinkedHashMap<String, Stream>()
        for (stream in streams) {
            if (!readable(stream)) continue
            perAddon.putIfAbsent(stream.addonName.trim(), stream)
        }
        var checkable = false
        for (stream in perAddon.values) {
            val verdict = check(stream)
            if (verdict != LiveVerdict.UNKNOWN) checkable = true
            if (verdict == LiveVerdict.LIVE) return Outcome(stream, checkable = true)
        }
        return Outcome(null, checkable)
    }

    suspend fun check(stream: Stream): LiveVerdict {
        val url = stream.getStreamUrl()?.trim() ?: return LiveVerdict.UNKNOWN
        val headers = stream.behaviorHints?.proxyHeaders?.request.orEmpty()
        val top = read(url, headers) ?: return LiveVerdict.UNKNOWN
        if (top.code == 404) return LiveVerdict.NOT_LIVE
        val body = top.body?.takeIf { top.code in 200..299 } ?: return LiveVerdict.UNKNOWN
        if (!SportsLiveHlsPlaylist.isPlaylist(body)) return LiveVerdict.UNKNOWN
        var mediaUrl = url
        var mediaHeaders = headers
        var mediaBody = body
        if (SportsLiveHlsPlaylist.isMaster(body)) {
            // #279: relative to where the master was finally read; the headers only go on to the link's own host.
            mediaUrl = SportsLiveHlsPlaylist.firstVariant(body, top.finalUrl ?: url) ?: return LiveVerdict.NOT_LIVE
            mediaHeaders = SportsLiveStreamHeaders.forFollowUp(url, mediaUrl, headers)
            val variant = read(mediaUrl, mediaHeaders) ?: return LiveVerdict.UNKNOWN
            if (variant.code == 404) return LiveVerdict.NOT_LIVE
            mediaBody = variant.body?.takeIf { variant.code in 200..299 && SportsLiveHlsPlaylist.isPlaylist(it) }
                ?: return LiveVerdict.UNKNOWN
            // A master listing masters: nothing to play from here.
            if (SportsLiveHlsPlaylist.isMaster(mediaBody)) return LiveVerdict.UNKNOWN
        }
        val first = SportsLiveHlsPlaylist.media(mediaBody)
        SportsLiveHlsPlaylist.verdict(first, nowMs())?.let { return it }
        wait(((first.targetDurationS ?: DEFAULT_TARGET_S).coerceIn(MIN_WAIT_S, MAX_WAIT_S)) * 1_000L)
        val again = read(mediaUrl, mediaHeaders)?.body?.takeIf(SportsLiveHlsPlaylist::isPlaylist) ?: return LiveVerdict.UNKNOWN
        return if (SportsLiveHlsPlaylist.advanced(first, SportsLiveHlsPlaylist.media(again))) LiveVerdict.LIVE else LiveVerdict.NOT_LIVE
    }

    private suspend fun read(url: String, headers: Map<String, String>): ManifestResponse? = try {
        fetch(url, headers)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val DEFAULT_TARGET_S = 6
        private const val MIN_WAIT_S = 2
        private const val MAX_WAIT_S = 10

        /** A plain http(s) link: the only kind a probe can read (a torrent or prepared link cannot). */
        fun readable(stream: Stream): Boolean {
            val url = stream.getStreamUrl()?.trim() ?: return false
            val scheme = url.substringBefore("://", "").lowercase()
            return scheme == "http" || scheme == "https"
        }
    }
}
