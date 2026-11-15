package com.nuvio.tv.core.sportslive

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** A card pill's start label; [Tomorrow] is wrapped in a string resource by the UI. */
sealed interface SportsLivePillTime {
    /** A start today: the time only. */
    data class Today(val time: String) : SportsLivePillTime

    /** A start tomorrow: the time, shown as "Tomorrow <time>". */
    data class Tomorrow(val time: String) : SportsLivePillTime

    /** Any other start: weekday + time within six days, else short date (+ time). */
    data class Other(val text: String) : SportsLivePillTime

    companion object {
        private const val WEEKDAY_DAYS = 6L

        /**
         * Labels [startMs] relative to the local day of [nowMs] in [zone]. A start without a time of
         * day ([hasTimeOfDay] false) always shows as its short date.
         */
        fun of(
            startMs: Long,
            hasTimeOfDay: Boolean,
            nowMs: Long,
            zone: ZoneId,
            locale: Locale
        ): SportsLivePillTime {
            val start = Instant.ofEpochMilli(startMs).atZone(zone)
            val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale)
            if (!hasTimeOfDay) return Other(dateFormat.format(start))
            val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(start)
            val days = ChronoUnit.DAYS.between(Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate(), start.toLocalDate())
            return when (days) {
                0L -> Today(time)
                1L -> Tomorrow(time)
                in 2L..WEEKDAY_DAYS -> Other("${DateTimeFormatter.ofPattern("EEE", locale).format(start)} $time")
                else -> Other("${dateFormat.format(start)} $time")
            }
        }
    }
}
