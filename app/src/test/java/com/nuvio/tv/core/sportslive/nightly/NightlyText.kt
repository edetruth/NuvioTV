package com.nuvio.tv.core.sportslive.nightly

import com.nuvio.tv.core.sportslive.SportsLiveEspnStarts
import com.nuvio.tv.core.sportslive.scores.ScoreMatcher
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import java.security.MessageDigest

/** What the nightly run (#192) found for one listed game, against ESPN. */
internal enum class Verdict(val code: String, val disagreement: Boolean, val verified: Boolean) {
    IN_PLAY("in-play", false, true),
    STARTS_SOON("starts-soon", false, true),
    SCHEDULED_LATER("scheduled-later", true, true),
    FINAL("final", true, true),
    OFF("postponed-or-cancelled", true, true),
    UPCOMING_OK("upcoming-ok", false, true),
    STARTED_EARLY("upcoming-but-in-play", true, true),
    ENDED_EARLY("upcoming-but-final", true, true),
    NOT_FOUND("not-found", false, false),
    NO_FEED("no-espn-feed", false, false),
    UNPLACED("cannot-place", false, false),
    NO_TEAMS("no-two-teams", false, false)
}

/** The nightly run's pure parts: verdicts, redaction and the text it writes. Network-free, unit tested. */
internal object NightlyText {

    /** A game its addon lists live: [match] is its ESPN match, [later] the pair's later meeting when it has none. */
    fun liveVerdict(match: ScoreSnapshot?, later: ScoreSnapshot?, nowMs: Long): Verdict {
        if (match == null) return if (later != null) Verdict.SCHEDULED_LATER else Verdict.NOT_FOUND
        return when (match.state) {
            ScoreState.IN -> Verdict.IN_PLAY
            ScoreState.POST -> Verdict.FINAL
            ScoreState.OFF -> Verdict.OFF
            ScoreState.PRE -> {
                val start = match.startMs
                if (start != null && start > nowMs + ScoreMatcher.LATER_LEAD_MS) Verdict.SCHEDULED_LATER else Verdict.STARTS_SOON
            }
            ScoreState.UNKNOWN -> Verdict.NOT_FOUND
        }
    }

    /** A game its addon lists upcoming (a timed start still to come). */
    fun upcomingVerdict(match: ScoreSnapshot?): Verdict = when (match?.state) {
        null, ScoreState.UNKNOWN -> Verdict.NOT_FOUND
        ScoreState.IN -> Verdict.STARTED_EARLY
        ScoreState.POST -> Verdict.ENDED_EARLY
        ScoreState.PRE, ScoreState.OFF -> Verdict.UPCOMING_OK
    }

    /**
     * Whether the app itself corrects [verdict]: a game scheduled later leaves Live now at ESPN's start
     * ([SportsLiveEspnStarts]); a final on the card's strict match ([strictSame]) goes to Just ended.
     */
    fun appCatches(verdict: Verdict, match: ScoreSnapshot?, later: ScoreSnapshot?, strictSame: Boolean, nowMs: Long): Boolean =
        when (verdict) {
            Verdict.SCHEDULED_LATER -> if (match != null) SportsLiveEspnStarts.laterStart(match, nowMs) != null else later != null
            Verdict.FINAL -> strictSame
            else -> false
        }

    /** A stable, private label for an addon URL: never its name or host. */
    fun label(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.trim().toByteArray(Charsets.UTF_8))
        return "addon-" + digest.take(3).joinToString("") { "%02x".format(it) }
    }

    private val URL = Regex("""(?i)\b(?:https?|wss?|ftp)://\S+""")

    /** [text] with every [secrets] key (case-insensitive) replaced by its label, and any link by `<link>`. */
    fun redact(text: String?, secrets: Map<String, String>): String {
        var result = text.orEmpty()
        // Longest first, so a base URL goes before the host inside it.
        for ((secret, label) in secrets.entries.sortedByDescending { it.key.length }) {
            if (secret.length < MIN_SECRET) continue
            result = result.replace(secret, label, ignoreCase = true)
        }
        return result.replace(URL, "<link>")
    }

    /** One CSV row (RFC 4180 quoting). */
    fun csv(fields: List<Any?>): String = fields.joinToString(",") { field ->
        val text = field?.toString().orEmpty().replace("\r", " ").replace("\n", " ")
        if (text.any { it == ',' || it == '"' } || text != text.trim()) "\"" + text.replace("\"", "\"\"") + "\"" else text
    }

    /** [text] as a Kotlin string literal, or `null`. */
    fun kotlinString(text: String?): String {
        if (text == null) return "null"
        val escaped = buildString {
            for (c in text) {
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '$' -> append("\\$")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(c)
                }
            }
        }
        return "\"" + escaped + "\""
    }

    /** A backtick test name from [text]: letters, digits, spaces and hyphens only (no ';' ':' '/' '.' '<' '>' '[' ']'). */
    fun testName(text: String): String =
        text.map { if (it.isLetterOrDigit() || it == ' ' || it == '-') it else ' ' }.joinToString("")
            .replace(Regex(" +"), " ").trim().take(MAX_TEST_NAME)

    /** Share of [agreed] in [verified] as a whole percent, or "-" when nothing was verified. */
    fun percent(agreed: Int, verified: Int): String = if (verified == 0) "-" else "${(agreed * 100 + verified / 2) / verified}%"

    private const val MIN_SECRET = 4
    private const val MAX_TEST_NAME = 100
}
