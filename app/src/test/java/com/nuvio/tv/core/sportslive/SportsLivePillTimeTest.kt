package com.nuvio.tv.core.sportslive

import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsLivePillTimeTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val locale = Locale.US
    // Sunday 15 Nov 2026, 22:30 local: late in the day, so the day boundary is in the local zone, not UTC.
    private val now = ZonedDateTime.of(2026, 11, 15, 22, 30, 0, 0, zone)
    private val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    private val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale)

    private fun pill(start: ZonedDateTime, hasTimeOfDay: Boolean = true) = SportsLivePillTime.of(
        startMs = start.toInstant().toEpochMilli(),
        hasTimeOfDay = hasTimeOfDay,
        nowMs = now.toInstant().toEpochMilli(),
        zone = zone,
        locale = locale
    )

    @Test
    fun `a start today shows the time only`() {
        val start = now.withHour(23).withMinute(45)
        assertEquals(SportsLivePillTime.Today(timeFormat.format(start)), pill(start))
    }

    @Test
    fun `a start tomorrow shows as Tomorrow with the time`() {
        // 00:15 local is still "tomorrow" although it is less than two hours away.
        val start = now.plusDays(1).withHour(0).withMinute(15)
        assertEquals(SportsLivePillTime.Tomorrow(timeFormat.format(start)), pill(start))
    }

    @Test
    fun `a start in 3 days shows the abbreviated weekday and time`() {
        val start = now.plusDays(3).withHour(20).withMinute(0)
        val label = pill(start)
        assertEquals(SportsLivePillTime.Other("Wed ${timeFormat.format(start)}"), label)
    }

    @Test
    fun `a start in 2 weeks shows the short date and time`() {
        val start = now.plusWeeks(2).withHour(13).withMinute(0)
        val label = pill(start) as SportsLivePillTime.Other
        assertEquals("${dateFormat.format(start)} ${timeFormat.format(start)}", label.text)
        assertTrue(label.text.startsWith("11/29/26"))
    }

    @Test
    fun `a start without a time of day shows the date only`() {
        val start = now.withHour(0).withMinute(0)
        assertEquals(SportsLivePillTime.Other(dateFormat.format(start)), pill(start, hasTimeOfDay = false))
    }
}
