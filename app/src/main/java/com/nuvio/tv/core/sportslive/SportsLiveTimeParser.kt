package com.nuvio.tv.core.sportslive

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.util.Locale

/** A parsed start; [hasTimeOfDay] is false when the source was a bare date (start = midnight UTC). */
data class SportsLiveStart(val startMs: Long, val hasTimeOfDay: Boolean)

/** Turns the start-time strings sports addons put in releaseInfo/released into epoch millis. */
object SportsLiveTimeParser {
    // e.g. "15 Nov 2026 13:00 UTC" once the "·" separator is normalized away.
    private val dayMonthYearUtc: DateTimeFormatter = DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .appendPattern("d MMM yyyy HH:mm 'UTC'")
        .toFormatter(Locale.ENGLISH)
    // e.g. "2026-11-15 21:56 UTC" (Nebula Sports).
    private val isoDateTimeUtc: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.ENGLISH)
    private val bareDate: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH)
    private val whitespace = Regex("\\s+")

    /** Start of the event in epoch millis, or null when neither field holds a recognizable time. */
    @Suppress("UNUSED_PARAMETER") // part of the agreed interface; no format here is relative to now
    fun parseStartMs(releaseInfo: String?, released: String?, nowMs: Long): Long? =
        parseStart(releaseInfo, released)?.startMs

    fun parseStart(releaseInfo: String?, released: String?): SportsLiveStart? {
        val info = releaseInfo?.let(::normalize).orEmpty()
        val iso = released?.trim().orEmpty()
        return parseOrNull(info, hasTimeOfDay = true) {
            LocalDateTime.parse(it, dayMonthYearUtc).toInstant(ZoneOffset.UTC).toEpochMilli()
        } ?: parseOrNull(info, hasTimeOfDay = true) {
            LocalDateTime.parse(it, isoDateTimeUtc).toInstant(ZoneOffset.UTC).toEpochMilli()
        } ?: parseOrNull(iso, hasTimeOfDay = true) {
            // ISO-8601 with "Z" or a numeric offset and any fraction length.
            OffsetDateTime.parse(it).toInstant().toEpochMilli()
        } ?: parseOrNull(info, hasTimeOfDay = false) {
            LocalDate.parse(it, bareDate).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        }
    }

    private fun normalize(text: String): String =
        text.replace("Â·", " ").replace("·", " ").replace(whitespace, " ").trim()

    private inline fun parseOrNull(text: String, hasTimeOfDay: Boolean, parse: (String) -> Long): SportsLiveStart? {
        if (text.isEmpty()) return null
        return try {
            SportsLiveStart(parse(text), hasTimeOfDay)
        } catch (e: DateTimeParseException) {
            null
        }
    }
}
