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
    /** 404, an empty manifest, or one that ends (`#EXT-X-ENDLIST`): the stream is over. */
    DEAD,

    /** A playlist that goes on. */
    ALIVE,

    /** Anything else (no answer, a timeout, a 403 (often a geo block or token), a server error): no verdict. */
    UNKNOWN
}

/** A manifest fetch's answer: the HTTP [code], and the [body] (the first part of it) when successful. */
data class ManifestResponse(val code: Int, val body: String?)

/**
 * One link check: the card ([key], its [SportsLiveItem.streamKey]), its already-resolved [url] and the
 * request [headers] its stream asks for, the base urls of its sources (a cool-down holds the check back,
 * QA C1), the [source] that served the link (for [SportsLiveSourceTrust]) and when it was resolved
 * ([resolvedAtMs]: a later resolve is a new link to check). Held in memory only; the url is never logged.
 */
data class LinkCheckRequest(
    val key: String,
    val url: String,
    val headers: Map<String, String>,
    val sourceBaseUrls: List<String>,
    val source: String,
    val resolvedAtMs: Long
)

object SportsLiveManifestVerdict {
    private const val END_LIST = "#EXT-X-ENDLIST"
    private const val PLAYLIST_HEADER = "#EXTM3U"

    /** [response]: null when there was no answer at all. */
    fun classify(response: ManifestResponse?): LinkCheckResult {
        val code = response?.code ?: return LinkCheckResult.UNKNOWN
        // A 403 is often a geo block or an expired token, not an ended stream: no verdict.
        if (code == 404) return LinkCheckResult.DEAD
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
     * The check for [item] on [stream], resolved at [resolvedAtMs]: only a fresh resolve (the resolve
     * cache's, at most five minutes old), never an older link that once played, and never a resolve
     * just to test (null [stream]: no check). Only a plain http(s) HLS link (`.m3u8`) can be read.
     */
    fun requestFor(item: SportsLiveItem, stream: Stream?, resolvedAtMs: Long): LinkCheckRequest? {
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
            source = stream.addonName,
            resolvedAtMs = resolvedAtMs
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
 * The link checks' dead verdicts by card key (#60 phase 2), shared by the section (Just ended), dual
 * pairing and the player's "Next live game", so all of them agree while a verdict stands.
 */
class SportsLiveLinkVerdicts {
    /** Found dead at [atMs], on the link resolved at [resolvedAtMs]. */
    data class Dead(val atMs: Long, val resolvedAtMs: Long)

    private val _dead = MutableStateFlow<Map<String, Dead>>(emptyMap())
    val dead: StateFlow<Map<String, Dead>> = _dead.asStateFlow()

    /** Card keys whose dead verdict stands. */
    val deadKeys: Set<String> get() = _dead.value.keys

    internal fun markDead(key: String, dead: Dead) {
        _dead.update { current -> if (current.size >= MAX_REMEMBERED) mapOf(key to dead) else current + (key to dead) }
    }

    internal fun clear(key: String) {
        if (key in _dead.value) _dead.update { it - key }
    }

    companion object {
        private const val MAX_REMEMBERED = 2_000

        /** What the app uses. */
        val shared = SportsLiveLinkVerdicts()
    }
}

/**
 * Confirms that a game past its estimated end is over by looking at its freshly resolved link (#60
 * phase 2). Precision first: a dead link only moves a card to Just ended ("Probably ended"), never
 * hides it, and an unclear answer changes nothing.
 *
 * - One check per card and resolved link. A later resolve (fresh links) clears a dead verdict found on
 *   an older link ([clearIfSuperseded]) and is checked in its turn; a check that finds a link alive
 *   clears the verdict too.
 * - At most [MAX_PER_MINUTE] checks a minute.
 * - A card whose sources are cooling down after a rate limit (QA C1) waits; it is checked later.
 * - The caller offers cards only while Live now is on screen.
 */
class SportsLiveLinkChecker(
    private val fetch: suspend (url: String, headers: Map<String, String>) -> ManifestResponse?,
    private val cooldown: SportsLiveSourceCooldown = SportsLiveSourceCooldown.shared,
    private val trust: SportsLiveSourceTrust = SportsLiveSourceTrust.shared,
    private val verdicts: SportsLiveLinkVerdicts = SportsLiveLinkVerdicts(),
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    /** The resolve each card was last checked on, by card key. */
    private val checked = HashMap<String, Long>()
    private val starts = ArrayDeque<Long>()

    /** Cards whose link was found dead, by card key, while the verdict stands. */
    val dead: StateFlow<Map<String, SportsLiveLinkVerdicts.Dead>> = verdicts.dead

    /**
     * True when [request] is a link resolved after its card's dead verdict was found: fresh links came
     * back, so the verdict is dropped (the card is live again until that link is checked).
     */
    fun clearIfSuperseded(request: LinkCheckRequest): Boolean {
        val dead = verdicts.dead.value[request.key] ?: return false
        if (request.resolvedAtMs <= dead.resolvedAtMs) return false
        verdicts.clear(request.key)
        return true
    }

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
        val last = checked[request.key]
        if (last != null && request.resolvedAtMs <= last) return false
        if (request.sourceBaseUrls.any(cooldown::isCoolingDown)) return false
        val now = nowMs()
        while (starts.isNotEmpty() && now - starts.first() >= MINUTE_MS) starts.removeFirst()
        if (starts.size >= MAX_PER_MINUTE) return false
        if (checked.size >= MAX_REMEMBERED) checked.clear()
        checked[request.key] = request.resolvedAtMs
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
        when (result) {
            LinkCheckResult.DEAD -> verdicts.markDead(request.key, SportsLiveLinkVerdicts.Dead(nowMs(), request.resolvedAtMs))
            // A later link that answers: the card is live again.
            LinkCheckResult.ALIVE -> verdicts.clear(request.key)
            LinkCheckResult.UNKNOWN -> Unit
        }
    }

    companion object {
        const val MAX_PER_MINUTE = 4
        private const val MINUTE_MS = 60_000L
        private const val MAX_REMEMBERED = 2_000
    }
}
