package com.nuvio.tv.core.sportslive

import java.time.ZoneId
import java.util.Locale

/** Rewrites the UTC start stamps sports addons put in descriptions into the viewer's local time. */
object SportsLiveLocalTime {
    // "15 Nov 2026 · 21:00 UTC", also with the "Â·" mojibake or a plain space as the separator.
    private val utcStamp = Regex(
        """\b\d{1,2} [A-Za-z]{3} \d{4}(?:\s*Â?·\s*|\s+)\d{2}:\d{2} UTC\b""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Replaces every UTC stamp in [text] with the card pill's label for that instant in [zone] (time
     * only today, [tomorrow] of the time tomorrow, weekday + time within six days, else short date +
     * time). Text without stamps is returned unchanged.
     */
    fun localizeUtcStamps(
        text: String,
        nowMs: Long,
        zone: ZoneId,
        locale: Locale,
        tomorrow: (time: String) -> String
    ): String = utcStamp.replace(text) { match ->
        val startMs = SportsLiveTimeParser.parseStart(match.value, null)?.startMs
            ?: return@replace match.value
        when (val pill = SportsLivePillTime.of(startMs, hasTimeOfDay = true, nowMs, zone, locale)) {
            is SportsLivePillTime.Today -> pill.time
            is SportsLivePillTime.Tomorrow -> tomorrow(pill.time)
            is SportsLivePillTime.Other -> pill.text
        }
    }
}
