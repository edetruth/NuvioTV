package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.Stream
import java.net.URI

/**
 * Recognises a Sports & Live source's informational entries (QA C1): items an addon lists among its
 * streams to tell the user something ("Rate limit reached, wait 7 min", "Upgrade to premium", "Not
 * available"), which never play a game. They are never auto-picked and never count as playable; the
 * Sources panel shows them as notice rows. Matching is generic and conservative: an entry counts as a
 * notice only on a clear signal, so real stream titles ("HD", "error-free feed") are left alone.
 */
object SportsLiveNoticeEntries {
    /** The cool-down a rate-limit notice gets when it names no wait time. */
    const val DEFAULT_COOLDOWN_MIN = 5
    private const val MAX_COOLDOWN_MIN = 60

    /** A "video" this small is a placeholder clip, not a game. */
    private const val PLACEHOLDER_MAX_BYTES = 1_000_000L

    private val placeholderSuffixes = listOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".txt", ".json")

    private const val WAIT = """\bwait\s+(?:for\s+|about\s+)?\d{1,3}\s*(?:minutes?|mins?|m|seconds?|secs?|s|hours?|hrs?|h)\b"""

    private val rateLimitPatterns = listOf(
        """\brate[\s-]?limit""",
        """\blimit\s+(?:reached|exceeded|hit)\b""",
        """\bquota\s+(?:reached|exceeded)\b""",
        """\btoo\s+many\s+requests\b""",
        WAIT
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    private val noticePatterns = rateLimitPatterns + listOf(
        """\btry\s+again\b""",
        // "error" on its own, not inside a word or a compound such as "error-free".
        """(?<![\w-])error(?![\w-])""",
        """\bnot\s+available\b""",
        """\bunavailable\b""",
        """\bno\s+streams?\s+(?:found|available)\b""",
        """\bupgrade\b""",
        """\bpremium\s+(?:required|only|needed)\b""",
        """\bsubscription\s+required\b"""
    ).map { Regex(it, RegexOption.IGNORE_CASE) }

    // "wait 7 min", "try again in 10 minutes": a number of the wait, not any number in the text.
    private val duration = Regex(
        """\b(?:wait|in|after|for|about)\s+(?:for\s+|about\s+)?(\d{1,3})\s*(minutes?|mins?|m|seconds?|secs?|s|hours?|hrs?|h)\b""",
        RegexOption.IGNORE_CASE
    )

    /** True when [stream] is an informational entry rather than a stream that may play. */
    fun isNotice(stream: Stream): Boolean =
        hasNothingToPlay(stream) || isPlaceholder(stream) || noticePatterns.any { it.containsMatchIn(text(stream)) }

    /**
     * The cool-down, in minutes, a rate-limit notice asks for ("wait 7 min" is 7; none named is
     * [DEFAULT_COOLDOWN_MIN]); null when [stream] is not a rate-limit notice.
     */
    fun rateLimitMinutes(stream: Stream): Int? {
        val text = text(stream)
        if (rateLimitPatterns.none { it.containsMatchIn(text) }) return null
        val match = duration.find(text) ?: return DEFAULT_COOLDOWN_MIN
        val amount = match.groupValues[1].toInt()
        val unit = match.groupValues[2].lowercase()
        val minutes = when {
            unit.startsWith("h") -> amount * 60
            unit.startsWith("s") -> (amount + 59) / 60
            else -> amount
        }
        return minutes.coerceIn(1, MAX_COOLDOWN_MIN)
    }

    /** [stream]'s text as one line, for the notice row. */
    fun text(stream: Stream): String =
        listOfNotNull(stream.name, stream.title, stream.description)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" · ")
            .replace(Regex("""\s*\n\s*"""), " ")

    /** No url, link, torrent, video id or prepared target of any kind: there is nothing to open. */
    private fun hasNothingToPlay(stream: Stream): Boolean =
        stream.url.isNullOrBlank() && stream.externalUrl.isNullOrBlank() &&
            stream.getEffectiveInfoHash() == null && stream.ytId.isNullOrBlank() &&
            stream.serverTarget == null && stream.clientResolve == null

    /** A url to an image or a text file, or a tiny clip ([PLACEHOLDER_MAX_BYTES]) by its behaviorHints. */
    private fun isPlaceholder(stream: Stream): Boolean {
        val size = stream.behaviorHints?.videoSize
        if (size != null && size in 1..PLACEHOLDER_MAX_BYTES) return true
        val url = stream.url?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val path = runCatching { URI(url).rawPath }.getOrNull()?.lowercase() ?: return false
        return placeholderSuffixes.any { path.endsWith(it) }
    }
}
