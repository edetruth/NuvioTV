package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What one look at a game's HLS manifest says (#60 phase 2). */
enum class LinkCheckResult {
    /** 403/404, an empty manifest, or one that ends (`#EXT-X-ENDLIST`): the stream is over. */
    DEAD,

    /** A playlist that goes on. */
    ALIVE,

    /** Anything else (no answer, a timeout, a server error, a rate limit): no verdict. */
    UNKNOWN
}

/** A manifest fetch's answer: the HTTP [code], and the [body] (the first part of it) when successful. */
data class ManifestResponse(val code: Int, val body: String?)

/**
 * One link check: the card ([key], its [SportsLiveItem.streamKey]), its already-resolved [url] and the
 * request [headers] its stream asks for, the base urls of its sources (a cool-down holds the check back,
 * QA C1) and the [source] that served the link (for [SportsLiveSourceTrust]). Held in memory only; the
 * url is never logged.
 */
data class LinkCheckRequest(
    val key: String,
    val url: String,
    val headers: Map<String, String>,
    val sourceBaseUrls: List<String>,
    val source: String
)

object SportsLiveManifestVerdict {
    private const val END_LIST = "#EXT-X-ENDLIST"
    private const val PLAYLIST_HEADER = "#EXTM3U"

    /** [response]: null when there was no answer at all. */
    fun classify(response: ManifestResponse?): LinkCheckResult {
        val code = response?.code ?: return LinkCheckResult.UNKNOWN
        if (code == 403 || code == 404) return LinkCheckResult.DEAD
        if (code !in 200..299) return LinkCheckResult.UNKNOWN
        val body = response.body
        return when {
            body.isNullOrBlank() -> LinkCheckResult.DEAD
            END_LIST in body -> LinkCheckResult.DEAD
            body.trimStart().startsWith(PLAYLIST_HEADER) -> LinkCheckResult.ALIVE
            else -> LinkCheckResult.UNKNOWN
        }
    }

    /**
     * The check for [item] on its already-resolved [stream] (null: nothing resolved, no check; a link
     * is never resolved just to test it). Only a plain http(s) HLS link (`.m3u8`) can be read this way.
     */
    fun requestFor(item: SportsLiveItem, stream: Stream?): LinkCheckRequest? {
        val url = stream?.url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val scheme = url.substringBefore("://", "").lowercase()
        if (scheme != "http" && scheme != "https") return null
        val path = url.substringBefore('?').substringBefore('#')
        if (!path.endsWith(".m3u8", ignoreCase = true)) return null
        return LinkCheckRequest(
            key = item.streamKey,
            url = url,
            headers = stream.behaviorHints?.proxyHeaders?.request.orEmpty(),
            sourceBaseUrls = (listOf(item) + item.alternates).map { it.addonBaseUrl },
            source = stream.addonName
        )
    }
}

/**
 * Whether each source's links stay up after its games end (#60 phase 2): the link checks' verdicts by
 * source name, in memory only. Not used for ranking yet; read [deadShare] to see how a source does.
 */
class SportsLiveSourceTrust {
    private class Counts(var dead: Int = 0, var alive: Int = 0)

    private val counts = HashMap<String, Counts>()

    @Synchronized
    fun record(source: String, result: LinkCheckResult) {
        if (result == LinkCheckResult.UNKNOWN || source.isBlank()) return
        if (counts.size >= MAX_SOURCES && source !in counts) return
        val entry = counts.getOrPut(source) { Counts() }
        if (result == LinkCheckResult.DEAD) entry.dead++ else entry.alive++
    }

    /** The share of [source]'s checked links found dead after their game ended; null before any. */
    @Synchronized
    fun deadShare(source: String): Double? {
        val entry = counts[source] ?: return null
        val total = entry.dead + entry.alive
        return if (total == 0) null else entry.dead.toDouble() / total
    }

    companion object {
        private const val MAX_SOURCES = 200
        val shared = SportsLiveSourceTrust()
    }
}

/**
 * Confirms that a game past its estimated end is over by looking at its already-resolved link once
 * (#60 phase 2). Precision first: a dead link only moves a card to Just ended ("Probably ended"),
 * never hides it, and an unclear answer changes nothing.
 *
 * - At most one check per card, ever (for this checker), and at most [MAX_PER_MINUTE] a minute.
 * - A card whose sources are cooling down after a rate limit (QA C1) waits; it is checked later.
 * - The caller offers cards only while Live now is on screen.
 */
class SportsLiveLinkChecker(
    private val fetch: suspend (url: String, headers: Map<String, String>) -> ManifestResponse?,
    private val cooldown: SportsLiveSourceCooldown = SportsLiveSourceCooldown.shared,
    private val trust: SportsLiveSourceTrust = SportsLiveSourceTrust.shared,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private val checked = HashSet<String>()
    private val starts = ArrayDeque<Long>()

    private val _dead = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** Cards whose link was found dead, by card key, with when. */
    val dead: StateFlow<Map<String, Long>> = _dead.asStateFlow()

    /**
     * Starts, in [scope], the checks of [requests] allowed now (see the class rules), in order. Returns
     * the keys started.
     */
    fun offer(requests: List<LinkCheckRequest>, scope: CoroutineScope): List<String> {
        val started = ArrayList<String>()
        for (request in requests) {
            if (!take(request)) continue
            started += request.key
            scope.launch { check(request) }
        }
        return started
    }

    @Synchronized
    private fun take(request: LinkCheckRequest): Boolean {
        if (request.key in checked) return false
        if (request.sourceBaseUrls.any(cooldown::isCoolingDown)) return false
        val now = nowMs()
        while (starts.isNotEmpty() && now - starts.first() >= MINUTE_MS) starts.removeFirst()
        if (starts.size >= MAX_PER_MINUTE) return false
        if (checked.size >= MAX_REMEMBERED) checked.clear()
        checked += request.key
        starts.addLast(now)
        return true
    }

    private suspend fun check(request: LinkCheckRequest) {
        val response = try {
            fetch(request.url, request.headers)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val result = SportsLiveManifestVerdict.classify(response)
        trust.record(request.source, result)
        if (result == LinkCheckResult.DEAD) {
            val at = nowMs()
            _dead.update { current -> if (current.size >= MAX_REMEMBERED) mapOf(request.key to at) else current + (request.key to at) }
        }
    }

    companion object {
        const val MAX_PER_MINUTE = 4
        private const val MINUTE_MS = 60_000L
        private const val MAX_REMEMBERED = 2_000
    }
}
