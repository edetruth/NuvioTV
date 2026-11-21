package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.domain.model.SportsLiveItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The calendar day a game is listed under (live.20 BUG14: Replays put a game a day before its Game page and
 * Results). One rule everywhere: the day of its ESPN start in the TV's zone, as the Game page and Results say.
 *
 * Without an ESPN match, the card's own start: a time of day reads in the TV's zone; a date-only start, or one at
 * exactly midnight UTC (how addons write a bare date in `released`), is the date the addon wrote. Read in the
 * TV's zone it fell on the evening before anywhere west of UTC, the root cause of BUG14.
 */
object GameDay {
    fun of(item: SportsLiveItem, event: ScoreSnapshot?, zone: ZoneId): LocalDate? {
        event?.takeIf { it.timeValid }?.startMs?.let { return Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        val start = item.startMs ?: return null
        return Instant.ofEpochMilli(start).atZone(if (isDateOnly(item)) ZoneOffset.UTC else zone).toLocalDate()
    }

    /** True when [item]'s start is a date alone: none of its own, or exactly midnight UTC (an addon's bare date, #210). */
    fun isDateOnly(item: SportsLiveItem): Boolean {
        val start = item.startMs ?: return true
        return !item.startHasTimeOfDay || start % DAY_MS == 0L
    }

    private const val DAY_MS = 24 * 60 * 60_000L
}
