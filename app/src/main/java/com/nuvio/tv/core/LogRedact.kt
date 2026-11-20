package com.nuvio.tv.core

import com.nuvio.tv.BuildConfig

/**
 * Fork: keeps addon links out of text the app hands on. A private addon's link carries the user's
 * token in its path or query, and a private host is itself a secret, so a link shows as
 * `https://host-3fa2/…` (a short stable label for the host, never the host) and links, hosts, IP
 * addresses and bearer tokens are scrubbed out of exception text. The class name of an exception and
 * plain words are kept. Used by fork code and by the Report-a-problem export (#172). A release build
 * logs nothing at all (proguard-fork-logging.pro); debug builds pass every value through as-is.
 */
object LogRedact {
    // IS_DEBUG_BUILD, not DEBUG: debug installs are built non-debuggable by default (BuildConfig.DEBUG false).
    private val enabled: Boolean = !BuildConfig.IS_DEBUG_BUILD

    /** A link for a log line: `scheme://host-xxxx/…` in a release build. */
    fun url(value: Any?): String = if (enabled) redactUrl(value?.toString()) else value.toString()

    /** Free text for a log line (an exception message, a built line) with every link and host scrubbed. */
    fun text(value: Any?): String = if (enabled) redactText(value?.toString()) else value.toString()

    /** An exception to pass to Log.w / Log.e: same class name and stack, scrubbed messages. */
    fun error(t: Throwable?): Throwable? = if (enabled && t != null) redactThrowable(t) else t

    /**
     * Text for a Report a problem file (#172), in every build (a debug build too): links (whole, with any user and
     * password in them), hosts, IP addresses and tokens go as in [text]; then each of [secrets] left (the addons'
     * names and ids, the account's e-mail and id) becomes `<private>`; then e-mail addresses, IPv6 addresses, UUIDs,
     * bare query strings and long token-like words go too.
     */
    fun report(value: String?, secrets: Collection<String> = emptyList()): String {
        if (value == null) return "null"
        // Links first, whole: a secret replaced inside one would hide the rest of it from the link pattern.
        var out: String = redactText(value)
        for (secret in secrets.map { it.trim() }.filter { it.length >= MIN_SECRET }.distinct().sortedByDescending { it.length }) {
            out = Regex("(?<![A-Za-z0-9])" + Regex.escape(secret) + "(?![A-Za-z0-9])", RegexOption.IGNORE_CASE).replace(out, PRIVATE)
        }
        out = EMAIL.replace(out, "<email>")
        out = IPV6.replace(out) { m ->
            val v = m.value
            if ("::" in v || v.any { it in 'a'..'f' || it in 'A'..'F' } || v.count { it == ':' } >= 5) "<ip>" else v
        }
        out = UUID.replace(out, "<id>")
        out = QUERY.replace(out, "?…")
        out = LONG_TOKEN.replace(out, REDACTED)
        return out
    }

    internal fun redactUrl(value: String?): String {
        if (value == null) return "null"
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return value
        if (trimmed.startsWith("magnet:", ignoreCase = true)) return "magnet:…"
        val match = URL_SHAPE.matchEntire(trimmed) ?: return REDACTED
        val scheme = match.groupValues[1].lowercase()
        val host = hostOf(match.groupValues[2])
        val rest = match.groupValues[3]
        val label = if (host.isEmpty()) "" else hostLabel(host)
        val tail = if (rest.isEmpty() || rest == "/") "" else "/…" + extensionOf(rest)
        return "$scheme://$label$tail"
    }

    internal fun redactText(value: String?): String {
        if (value == null) return "null"
        var out = URL_IN_TEXT.replace(value) { m ->
            val raw = m.value
            val link = raw.trimEnd(*TRAILING_PUNCTUATION)
            redactUrl(link) + raw.substring(link.length)
        }
        out = MAGNET_IN_TEXT.replace(out, "magnet:…")
        out = BEARER.replace(out) { m -> "${m.groupValues[1]} $REDACTED" }
        out = JWT.replace(out, REDACTED)
        out = SECRET_PARAM.replace(out) { m -> "${m.groupValues[1]}=$REDACTED" }
        out = IPV4.replace(out) { m -> hostLabel(m.value) }
        out = BARE_HOST.replace(out) { m ->
            if (m.groupValues[1] in FILE_SUFFIXES) m.value else hostLabel(m.value)
        }
        return out
    }

    internal fun redactThrowable(t: Throwable): Throwable = RedactedThrowable(t, 0)

    /** A short stable label for a host: the same host always gives the same label. */
    internal fun hostLabel(host: String): String {
        var hash = FNV_OFFSET
        for (c in host.lowercase()) {
            hash = hash xor c.code
            hash *= FNV_PRIME
        }
        val folded = (hash ushr 16) xor (hash and 0xFFFF)
        return "host-" + folded.toString(16).padStart(4, '0')
    }

    private fun hostOf(authority: String): String {
        val noUser = authority.substringAfterLast('@')
        val noPort = if (noUser.startsWith("[")) noUser.substringBefore(']') + "]" else noUser.substringBefore(':')
        return noPort.lowercase()
    }

    private fun extensionOf(rest: String): String {
        val last = rest.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val ext = last.substringAfterLast('.', "").lowercase()
        return if (ext in MEDIA_EXTENSIONS) ".$ext" else ""
    }

    private class RedactedThrowable(original: Throwable, depth: Int) : Throwable(
        original.message?.let { redactText(it) },
        original.cause
            ?.takeIf { it !== original && depth < MAX_CAUSES }
            ?.let { RedactedThrowable(it, depth + 1) }
    ) {
        private val type: String = original.javaClass.name

        init {
            stackTrace = original.stackTrace
        }

        override fun fillInStackTrace(): Throwable = this

        override fun toString(): String = message?.let { "$type: $it" } ?: type
    }

    private const val REDACTED = "<redacted>"
    private const val PRIVATE = "<private>"
    private const val MIN_SECRET = 3
    private val EMAIL = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9\-]+(?:\.[A-Za-z0-9\-]+)+""")
    private val IPV6 = Regex("""(?<![\w:.])[0-9A-Fa-f]{0,4}(?::[0-9A-Fa-f]{0,4}){2,7}(?![\w:])""")
    private val UUID = Regex("""(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b""")
    private val QUERY = Regex("""\?[^\s"'<>?]*=[^\s"'<>]*""")
    private val LONG_TOKEN = Regex("""(?<![\w\-])(?=[\w\-]*\d)(?=[\w\-]*[A-Za-z])[\w\-]{24,}(?![\w\-])""")
    private const val MAX_CAUSES = 8
    private const val FNV_OFFSET = -0x7ee3623b // 0x811C9DC5
    private const val FNV_PRIME = 0x01000193
    private val TRAILING_PUNCTUATION = charArrayOf(')', ']', '}', ',', '.', ';', ':', '!', '?')
    private val URL_SHAPE = Regex("""^([A-Za-z][A-Za-z0-9+.\-]*)://([^/?#\s]*)(.*)$""", RegexOption.DOT_MATCHES_ALL)
    private val URL_IN_TEXT = Regex("""[A-Za-z][A-Za-z0-9+.\-]*://[^\s"'<>]+""")
    private val MAGNET_IN_TEXT = Regex("""(?i)magnet:\?[^\s"'<>]+""")
    private val BEARER = Regex("""(?i)\b(bearer|basic)\s+[A-Za-z0-9._~+/=\-]+""")
    private val JWT = Regex("""eyJ[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+(?:\.[A-Za-z0-9_\-]*)?""")
    private val SECRET_PARAM = Regex(
        """(?i)\b(api[_-]?key|access[_-]?token|refresh[_-]?token|token|password|passwd|secret|auth|key)=([^&\s"',;]+)"""
    )
    private val IPV4 = Regex("""(?<![\w.])\d{1,3}(?:\.\d{1,3}){3}(?![\w.])""")
    private val BARE_HOST = Regex(
        """(?<![\w@/.\-])(?:[a-z0-9](?:[a-z0-9\-]{0,61}[a-z0-9])?\.)+([a-z]{2,24})(?![\w\-]|\.[A-Za-z0-9])"""
    )
    private val FILE_SUFFIXES = setOf("json", "xml", "txt", "kt", "java", "html", "so", "js", "png", "jpg", "log")
    private val MEDIA_EXTENSIONS = setOf(
        "m3u8", "m3u", "mpd", "mp4", "mkv", "ts", "m4s", "webm", "avi", "mov", "aac", "mp3",
        "json", "srt", "vtt", "ass", "ssa", "sub", "xml", "zip", "gz"
    )
}
