package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.OffsetDateTime

/** What one probe of a link found (#147). */
enum class ProbeVerdict {
    /** A live media playlist with recent segments, or a direct link that answered. */
    OK,

    /** It will not play now: an HTTP error, an ended or empty playlist, or one whose segments are old. */
    DEAD,

    /** No verdict: no answer in time, a redirect not followed, a body that is not a playlist. */
    UNKNOWN
}

/**
 * Reads an HLS playlist for a start's probe (#147). Only a *media* playlist with recent segments counts as
 * live (#110): a master playlist says nothing on its own, so the caller fetches its first variant
 * ([firstVariant]) and asks again.
 */
object SportsLiveMediaPlaylist {
    private const val HEADER = "#EXTM3U"
    private const val VARIANT = "#EXT-X-STREAM-INF"
    private const val SEGMENT = "#EXTINF"
    private const val END_LIST = "#EXT-X-ENDLIST"
    private const val DATE_TIME = "#EXT-X-PROGRAM-DATE-TIME:"

    fun isMaster(body: String): Boolean = VARIANT in body

    /** The first variant's uri of master playlist [body], as written (relative or absolute); null when none. */
    fun firstVariant(body: String): String? {
        val lines = body.lineSequence().map { it.trim() }.toList()
        val at = lines.indexOfFirst { it.startsWith(VARIANT) }
        if (at < 0) return null
        return lines.drop(at + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
    }

    /**
     * Media playlist [body] read at [nowMs]: live ([ProbeVerdict.OK]) when it has segments and no end, and its
     * newest program date (when it writes one) is within [SportsLiveStreamThresholds.MEDIA_PLAYLIST_STALE_MS].
     */
    fun verdict(body: String?, nowMs: Long): ProbeVerdict {
        if (body.isNullOrBlank()) return ProbeVerdict.DEAD
        if (!body.trimStart().startsWith(HEADER)) return ProbeVerdict.UNKNOWN
        if (isMaster(body)) return ProbeVerdict.UNKNOWN
        if (END_LIST in body || SEGMENT !in body) return ProbeVerdict.DEAD
        val newest = body.lineSequence()
            .filter { it.startsWith(DATE_TIME) }
            .mapNotNull { line -> runCatching { OffsetDateTime.parse(line.removePrefix(DATE_TIME).trim()).toInstant().toEpochMilli() }.getOrNull() }
            .maxOrNull()
        return if (newest != null && nowMs - newest > SportsLiveStreamThresholds.MEDIA_PLAYLIST_STALE_MS) ProbeVerdict.DEAD else ProbeVerdict.OK
    }

    /** True when [url]'s path is an HLS playlist. */
    fun isHls(url: String): Boolean = url.substringBefore('?').substringBefore('#').endsWith(".m3u8", ignoreCase = true)
}

/**
 * The start's probe race (#147): the best link of each addon is probed at the same time; the first that
 * proves live wins and the others are cancelled, so one player and decoder still open only the winner.
 * At most one probe per addon, so a rate-limited addon sees one request.
 */
object SportsLiveLinkRace {
    /** One probe's result: [stream], its [verdict], after [ms]. */
    data class Result(val stream: Stream, val verdict: ProbeVerdict, val ms: Long)

    /**
     * Who races from [ordered] (best first): the first [probeable] link of each addon, in order, at most
     * [max]. Empty when fewer than [minAddons] addons have one: a start does not race one addon, which only
     * costs a request.
     */
    fun entrants(
        ordered: List<Stream>,
        probeable: (Stream) -> Boolean,
        max: Int = SportsLiveStreamThresholds.RACE_MAX_ADDONS,
        minAddons: Int = 2
    ): List<Stream> {
        val seen = HashSet<String>()
        val picked = ordered.filter { probeable(it) && seen.add(it.addonName.trim()) }.take(max)
        return if (picked.size < minAddons) emptyList() else picked
    }

    /** True when [stream] can be probed: a plain http(s) link (not a torrent, nor one the app prepares). */
    fun probeable(stream: Stream): Boolean {
        val url = stream.getStreamUrl()?.trim() ?: return false
        val scheme = url.substringBefore("://", "").lowercase()
        return (scheme == "http" || scheme == "https") && stream.serverTarget == null && stream.ytId == null
    }

    /**
     * Probes [entrants] at once, each within [timeoutMs]. Returns the first that is [ProbeVerdict.OK] (the
     * rest are cancelled at that moment) and every result that came back before (losers that already
     * answered, for the learning), or a null winner when none proved live.
     */
    suspend fun race(
        entrants: List<Stream>,
        timeoutMs: Long,
        nowMs: () -> Long,
        probe: suspend (Stream) -> ProbeVerdict
    ): Pair<Stream?, List<Result>> = coroutineScope {
        val results = Channel<Result>(Channel.UNLIMITED)
        val startedAt = nowMs()
        val jobs: List<Job> = entrants.map { stream ->
            launch {
                val verdict = try {
                    withTimeoutOrNull(timeoutMs) { probe(stream) } ?: ProbeVerdict.UNKNOWN
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    ProbeVerdict.UNKNOWN
                }
                results.send(Result(stream, verdict, nowMs() - startedAt))
            }
        }
        val seen = ArrayList<Result>()
        repeat(entrants.size) {
            val result = results.receive()
            seen += result
            if (result.verdict == ProbeVerdict.OK) {
                jobs.forEach { it.cancel() }
                return@coroutineScope result.stream to seen
            }
        }
        null to seen
    }

    /**
     * The link a start plays after a race over [ordered] that found no live link (#147): the first whose probe
     * did not find it dead ([results]), else [fallback].
     */
    fun afterNoWinner(ordered: List<Stream>, results: List<Result>, fallback: Stream): Stream {
        val dead = results.filter { it.verdict == ProbeVerdict.DEAD }.mapTo(HashSet()) { it.stream }
        if (fallback !in dead) return fallback
        return ordered.firstOrNull { it !in dead } ?: fallback
    }
}
