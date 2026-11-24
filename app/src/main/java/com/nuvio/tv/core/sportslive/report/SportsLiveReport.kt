package com.nuvio.tv.core.sportslive.report

import com.nuvio.tv.core.LogRedact
import com.nuvio.tv.domain.model.Addon
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What one Report a problem file says (#172), gathered the moment the user asked. Plain values: [SportsLiveReport]
 * removes every secret when it writes them. No score is ever part of it (spoilers): a game says only whether it is live.
 */
data class SportsLiveReportInput(
    val number: Int,
    val savedAtMs: Long,
    val zone: ZoneId,
    /** How it was asked for ("Hold Play/Pause", "Settings"). */
    val trigger: String,
    val appVersion: String,
    val appBuild: String,
    val device: String,
    /** The screen open (its route). */
    val screen: String?,
    /** The Sports & Live card focused last (its clean title). */
    val focused: String?,
    val game: Game?,
    val linkChecks: LinkChecks,
    /** Scores mode, delay and real-time, as one line. */
    val scores: String,
    /** The player's last data-source opens ([com.nuvio.tv.ui.screens.player.LoggingDataSource.recentEvents]). */
    val playbackEvents: List<String> = emptyList(),
    val signInLog: List<String> = emptyList(),
    /** The titles the app could not place, `<loads>x | <title>` (no addon). */
    val unplaced: List<String> = emptyList(),
    /** Recent merge near misses; null when this build does not collect them (release). */
    val nearMisses: List<String>? = null,
    /** Score feeds ESPN answered with data the app could not read, newest first (live.20, [com.nuvio.tv.core.sportslive.scores.EspnFeedHealth.dataErrorLines]). */
    val feedDataErrors: List<String> = emptyList(),
    /** The profile's My sports ([SportsLiveReportTrail.mySports]); null when not known (#218). */
    val mySports: String? = null,
    /** The in-player panel's or guide's scope when it showed; null when it was closed (#218). */
    val panelScope: String? = null,
    /** Its rows, "‹tag› | ‹score shown / hidden / no score›" (#218: "no scores" and "wrong sports" reports explain themselves). */
    val panelRows: List<String> = emptyList(),
    /** Channels' last board: loaded per source, merged and listed, no names (#26); null when Channels was not opened. */
    val channels: String? = null
) {
    /** The game playing (or watched last). */
    data class Game(
        val title: String,
        val teams: String?,
        val sport: String?,
        val league: String?,
        val live: Boolean,
        val sources: Int,
        /** ESPN's event id and state ("401585 in"), when a game matched. */
        val espn: String?
    )

    /** What the link checks say now: the game's own, and counts over every game. */
    data class LinkChecks(
        /** "READY", "NO_STREAM" or null (no mark). */
        val gameMark: String? = null,
        val gameChecking: Boolean = false,
        /** Failed links of the game by reason. */
        val gameFailed: Map<String, Int> = emptyMap(),
        val gamePlayed: Int = 0,
        val ready: Int = 0,
        val noStream: Int = 0,
        val checking: Int = 0
    )
}

/** The report's text (#172) and its QR summary. Every value passes [LogRedact.report] with the secrets given. */
object SportsLiveReport {
    /**
     * What [text] removes for [addons]: each one's names, id, address and host, and its catalogs' names (#220: a
     * channel's genre can be its catalog's name, which names the addon as surely as the addon's own name).
     */
    fun addonSecrets(addons: List<Addon>): List<String> = addons.flatMap { addon ->
        listOfNotNull(addon.name, addon.displayName, addon.id, addon.baseUrl, runCatching { URI(addon.baseUrl).host }.getOrNull()) +
            addon.catalogs.map { it.name }.filterNot(::isGenericCatalogName)
    }

    /**
     * A catalog name made only of plain words ("Basketball", "Live Sports", "TV Channels") names no addon, and
     * removing it would blank those words everywhere in the report, the My sports line included (TV check, #220).
     */
    internal fun isGenericCatalogName(name: String): Boolean {
        val words = name.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
        return words.isEmpty() || words.all { it in GENERIC_CATALOG_WORDS }
    }

    private val GENERIC_CATALOG_WORDS = setOf(
        "sport", "sports", "live", "tv", "channel", "channels", "event", "events", "replay", "replays", "highlights",
        "movie", "movies", "series", "show", "shows", "all", "top", "popular", "new", "today", "upcoming", "schedule",
        "basketball", "football", "soccer", "hockey", "baseball", "tennis", "golf", "mma", "ufc", "boxing", "fight",
        "fights", "wrestling", "motorsport", "motorsports", "racing", "f1", "cricket", "rugby", "nba", "nfl", "nhl",
        "mlb", "ncaa", "college", "and", "the", "us", "uk", "usa", "hd", "fhd", "4k"
    )

    /** The line between the summary (which the QR code carries) and the details. */
    const val DETAILS = "---- details ----"

    /** The most a QR summary holds, in UTF-8 bytes: at most a version-19 code (93 modules), still sharp at 420 px. */
    const val QR_MAX_BYTES = 700

    /** A panel row's ending when its score showed ([SportsLiveReportInput.panelRows]). */
    const val SCORE_SHOWN = "score shown"

    /** The longest note the summary keeps; the details keep it whole. */
    const val NOTE_MAX_CHARS = 200

    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx", Locale.ROOT)
    private val FILE_FIELD = Regex("""file=(\S+)""")
    // A result split across a title's two sides ("Arsenal 2 vs 1 Chelsea"); four-digit numbers (1860, years) are no score.
    private val SPLIT_SCORE = Regex("""(?<!\d)\d{1,3}\s+(?:vs?\.?|@)\s+\d{1,3}(?!\d)""", RegexOption.IGNORE_CASE)
    private val MEDIA = setOf("m3u8", "mpd", "ts", "m4s", "mp4", "mkv", "aac", "vtt", "srt", "webm")

    fun text(input: SportsLiveReportInput, secrets: Collection<String>): String {
        fun clean(value: String?): String = oneLine(LogRedact.report(value, secrets))
        // Titles are an addon's words, and a replay's can carry its result ("A 2-1 B", its sides "A 2" and "1 B"): no
        // score-shaped pair survives (spoilers).
        fun title(value: String?): String = SportsLiveCrashReport.noScores(SPLIT_SCORE.replace(clean(value), "–"))
        val game = input.game
        return buildString {
            appendLine("Nuvio Live report #${input.number}")
            appendLine("Saved: ${STAMP.format(Instant.ofEpochMilli(input.savedAtMs).atZone(input.zone))}")
            appendLine("App: ${clean(input.appVersion)} (${clean(input.appBuild)})")
            appendLine("Screen: ${input.screen?.let(::clean) ?: "unknown"}")
            appendLine("Focused: ${input.focused?.let(::title) ?: "none"}")
            appendLine("Game: ${game?.let { title(gameLine(it)) } ?: "none"}")
            appendLine("Links: ${linksLine(input.linkChecks)}")
            appendLine("Scores: ${clean(input.scores)}" + if (input.feedDataErrors.isEmpty()) "" else " · ${input.feedDataErrors.size} feed data errors")
            appendLine("My sports: ${input.mySports?.let(::clean) ?: "unknown"}")
            input.panelScope?.let { scope ->
                val scored = input.panelRows.count { it.endsWith(SCORE_SHOWN) }
                appendLine("Panel: ${clean(scope)} · ${input.panelRows.size} rows, $scored with a score")
            }
            appendLine("Device: ${clean(input.device)}")
            appendLine("From: ${clean(input.trigger)}")
            appendLine(DETAILS)
            section("Playback events, newest last", input.playbackEvents.map { clean(playbackLine(it)) })
            section("Sign-in log", input.signInLog.map(::clean))
            section("Titles not placed, loads | title", input.unplaced.map(::title))
            section("Merge near misses", input.nearMisses?.map(::title) ?: listOf("not collected in this build"))
            section("Score feed data errors, newest first", input.feedDataErrors.map(::clean))
            // A row with no score names its game (live.25): its title, never anything shaped like a score.
            if (input.panelScope != null) section("Panel rows, tag | score", input.panelRows.map(::title))
            input.channels?.let { section("Channels, loaded per source and listed", listOf(clean(it))) }
            appendLine("Links, hosts, IP addresses, tokens, addon names and ids, e-mail and account ids are removed. No scores.")
        }
    }

    /**
     * [reportText] with the user's [note] (a voice note's words) added: a `Note:` line under the summary's time,
     * cut to [NOTE_MAX_CHARS], and the whole note in its own details section. A later note replaces the earlier one.
     */
    fun withNote(reportText: String, note: String, secrets: Collection<String>): String {
        val clean = oneLine(LogRedact.report(note.trim(), secrets))
        if (clean.isEmpty()) return reportText
        val lines = reportText.lines()
        val details = lines.indexOf(DETAILS)
        if (details < 0) return reportText
        val summary = lines.subList(0, details).filterNot { it.startsWith(NOTE_PREFIX) }
        var rest = lines.subList(details + 1, lines.size).toList()
        val oldNote = rest.indexOf(NOTE_SECTION)
        if (oldNote >= 0) {
            var end = oldNote + 1
            while (end < rest.size && rest[end].isNotEmpty()) end++
            rest = rest.take(oldNote) + rest.drop(minOf(end + 1, rest.size))
        }
        val short = if (clean.length > NOTE_MAX_CHARS) clean.take(NOTE_MAX_CHARS - 1) + "…" else clean
        // Right under the time: the user's own words are what the QR code must not cut.
        return (summary.take(2) + (NOTE_PREFIX + short) + summary.drop(2) + DETAILS + NOTE_SECTION + clean + "" + rest).joinToString("\n")
    }

    /**
     * The QR code's text: the summary lines of [reportText] (above [DETAILS]), in order, within [maxBytes] of UTF-8.
     * A line that does not fit is cut with "…" and nothing follows it.
     */
    fun qrPayload(reportText: String, maxBytes: Int = QR_MAX_BYTES): String {
        val out = StringBuilder()
        var used = 0
        for (line in reportText.lines()) {
            if (line == DETAILS) break
            if (line.isBlank()) continue
            val piece = if (out.isEmpty()) line else "\n" + line
            val size = utf8(piece)
            if (used + size <= maxBytes) {
                out.append(piece)
                used += size
                continue
            }
            val room = maxBytes - used - utf8("…")
            val cut = cutToBytes(piece, room)
            if (cut.isNotBlank()) out.append(cut).append("…")
            break
        }
        return out.toString()
    }

    internal fun gameLine(game: SportsLiveReportInput.Game): String = listOfNotNull(
        game.title,
        game.teams?.takeIf { it.isNotBlank() && it != game.title },
        game.league?.takeIf { it.isNotBlank() },
        game.sport?.takeIf { it.isNotBlank() },
        if (game.live) "live" else "not live",
        "${game.sources} source" + if (game.sources == 1) "" else "s",
        game.espn?.let { "ESPN $it" } ?: "no ESPN match"
    ).joinToString(" · ")

    internal fun linksLine(checks: SportsLiveReportInput.LinkChecks): String {
        val game = buildList {
            add(
                when (checks.gameMark) {
                    "READY" -> "ready"
                    "NO_STREAM" -> "no stream"
                    else -> "no mark"
                }
            )
            if (checks.gameChecking) add("checking")
            checks.gameFailed.toSortedMap().forEach { (reason, count) -> add("$count failed ($reason)") }
            if (checks.gamePlayed > 0) add("${checks.gamePlayed} played")
        }.joinToString(", ")
        return "game: $game · all games: ${checks.ready} ready, ${checks.noStream} no stream, ${checks.checking} checking"
    }

    /** A playback line's file keeps only a media extension: a path's last part can be a token. */
    internal fun playbackLine(line: String): String = FILE_FIELD.replace(line) { m ->
        val ext = m.groupValues[1].substringBefore('?').substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext in MEDIA) "file=….$ext" else "file=…"
    }

    private fun StringBuilder.section(title: String, lines: List<String>) {
        appendLine("[$title]")
        if (lines.isEmpty()) appendLine("none") else lines.forEach { appendLine(it) }
        appendLine()
    }

    private fun oneLine(text: String): String = text.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim()

    private fun utf8(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    private fun cutToBytes(text: String, maxBytes: Int): String {
        if (maxBytes <= 0) return ""
        var used = 0
        var end = 0
        while (end < text.length) {
            val cp = text.codePointAt(end)
            val size = utf8(String(Character.toChars(cp)))
            if (used + size > maxBytes) break
            used += size
            end += Character.charCount(cp)
        }
        return text.substring(0, end)
    }

    private const val NOTE_PREFIX = "Note: "
    private const val NOTE_SECTION = "[Voice note]"
}
