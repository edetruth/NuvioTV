package com.nuvio.tv.core.sportslive

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SportsLiveTimeParserTest {
    private val now = Instant.parse("2026-11-15T14:30:00Z").toEpochMilli()
    private val thirteenUtc = Instant.parse("2026-11-15T13:00:00Z").toEpochMilli() // 1794747600000

    @Test
    fun `LIVE returns null`() {
        assertNull(SportsLiveTimeParser.parseStartMs("LIVE", null, now))
    }

    @Test
    fun `parses day month year dot time UTC`() {
        assertEquals(thirteenUtc, SportsLiveTimeParser.parseStartMs("15 Nov 2026 · 13:00 UTC", null, now))
    }

    @Test
    fun `parses mojibake separator`() {
        assertEquals(thirteenUtc, SportsLiveTimeParser.parseStartMs("15 Nov 2026 Â· 13:00 UTC", null, now))
    }

    @Test
    fun `parses ISO released when releaseInfo is a bare date`() {
        assertEquals(
            Instant.parse("2026-11-14T00:00:00.000Z").toEpochMilli(),
            SportsLiveTimeParser.parseStartMs("2026-11-14", "2026-11-14T00:00:00.000Z", now)
        )
        assertEquals(
            Instant.parse("2026-11-14T19:45:00Z").toEpochMilli(),
            SportsLiveTimeParser.parseStartMs("2026-11-14", "2026-11-14T19:45:00Z", now)
        )
    }

    @Test
    fun `parses ISO released with a numeric offset and any fraction length`() {
        assertEquals(
            Instant.parse("2026-11-14T00:00:00Z").toEpochMilli(),
            SportsLiveTimeParser.parseStartMs(null, "2026-11-14T00:00:00+00:00", now)
        )
        assertEquals(
            Instant.parse("2026-11-14T18:45:00.123456Z").toEpochMilli(),
            SportsLiveTimeParser.parseStartMs(null, "2026-11-14T20:45:00.123456+02:00", now)
        )
    }

    @Test
    fun `bare date without released gives midnight UTC`() {
        assertEquals(
            Instant.parse("2026-11-14T00:00:00Z").toEpochMilli(),
            SportsLiveTimeParser.parseStartMs("2026-11-14", null, now)
        )
    }

    @Test
    fun `parseStart reports whether a time of day was present`() {
        assertEquals(SportsLiveStart(thirteenUtc, hasTimeOfDay = true), SportsLiveTimeParser.parseStart("15 Nov 2026 · 13:00 UTC", null))
        assertEquals(true, SportsLiveTimeParser.parseStart("2026-11-14", "2026-11-14T19:45:00Z")?.hasTimeOfDay)
        assertEquals(
            SportsLiveStart(Instant.parse("2026-11-14T00:00:00Z").toEpochMilli(), hasTimeOfDay = false),
            SportsLiveTimeParser.parseStart("2026-11-14", null)
        )
        assertNull(SportsLiveTimeParser.parseStart("TBD", null))
    }

    @Test
    fun `garbage returns null`() {
        assertNull(SportsLiveTimeParser.parseStartMs("Season 2", null, now))
        assertNull(SportsLiveTimeParser.parseStartMs("", null, now))
        assertNull(SportsLiveTimeParser.parseStartMs("   ", null, now))
        assertNull(SportsLiveTimeParser.parseStartMs(null, null, now))
    }
}
