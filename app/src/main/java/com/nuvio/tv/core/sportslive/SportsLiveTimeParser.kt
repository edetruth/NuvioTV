package com.nuvio.tv.core.sportslive

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.util.Locale

/** Turns the start-time strings sports addons put in releaseInfo/released into epoch millis. */
object SportsLiveTimeParser {
    // e.g. "15 Nov 2026 13:00 UTC" once the "·" separator is normalized away.
    private val dayMonthYearUtc: DateTimeFormatter = DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .appendPattern("d MMM yyyy HH:mm 'UTC'")
        .toFormatter(Locale.ENGLISH)
    private val isoInstant: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm[:ss[.SSS]]X", Locale.ENGLISH)
    private val bareDate: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH)
    private val whitespace = Regex("\\s+")

    /** Start of the event in epoch millis, or null when neither field holds a recognizable time. */
    @Suppress("UNUSED_PARAMETER") // part of the agreed interface; no format here is relative to now
    fun parseStartMs(releaseInfo: String?, released: String?, nowMs: Long): Long? {
        val info = releaseInfo?.let(::normalize).orEmpty()
        val iso = released?.trim().orEmpty()
        return parseOrNull(info) { LocalDateTime.parse(it, dayMonthYearUtc).toInstant(ZoneOffset.UTC).toEpochMilli() }
            ?: parseOrNull(iso) { OffsetDateTime.parse(it, isoInstant).toInstant().toEpochMilli() }
            ?: parseOrNull(info) { LocalDate.parse(it, bareDate).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }
    }

    private fun normalize(text: String): String =
        text.replace("Â·", " ").replace("·", " ").replace(whitespace, " ").trim()

    private inline fun parseOrNull(text: String, parse: (String) -> Long): Long? {
        if (text.isEmpty()) return null
        return try {
            parse(text)
        } catch (e: DateTimeParseException) {
            null
        }
    }
}
