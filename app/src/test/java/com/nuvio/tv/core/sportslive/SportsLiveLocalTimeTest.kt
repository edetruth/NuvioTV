package com.nuvio.tv.core.sportslive

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsLiveLocalTimeTest {
    private val zone = ZoneId.of("America/Chicago")
    private val locale = Locale.US
    // Sunday 15 Nov 2026, 09:00 Chicago.
    private val nowMs = ZonedDateTime.of(2026, 11, 15, 9, 0, 0, 0, zone).toInstant().toEpochMilli()

    // Newer JDKs put a narrow no-break space before "PM"; compare with a plain space.
    private fun localize(text: String) =
        SportsLiveLocalTime.localizeUtcStamps(text, nowMs, zone, locale) { "Tomorrow $it" }
            .replace(' ', ' ')

    @Test
    fun `utc stamp becomes chicago local`() {
        val result = localize("Tenerife vs Ceuta\n15 Nov 2026 · 21:00 UTC")
        assertEquals("Tenerife vs Ceuta\n3:00 PM", result)
        assertFalse(result.contains("UTC"))
    }

    @Test
    fun `mojibake separator handled`() {
        assertEquals("Kick-off 3:00 PM", localize("Kick-off 15 Nov 2026 Â· 21:00 UTC"))
    }

    @Test
    fun `plain space separator handled`() {
        assertEquals("Kick-off 3:00 PM", localize("Kick-off 15 Nov 2026 21:00 UTC"))
    }

    @Test
    fun `text without stamp unchanged`() {
        val text = "Tenerife vs Ceuta · Segunda División"
        assertEquals(text, localize(text))
    }

    @Test
    fun `multiple stamps all replaced`() {
        // Tomorrow, in 3 days (weekday) and in 2 weeks (short date), using the card pill's rules.
        val result = localize(
            "A: 16 Nov 2026 · 21:00 UTC\nB: 18 Nov 2026 · 02:30 UTC\nC: 29 Nov 2026 · 19:00 UTC"
        )
        assertEquals("A: Tomorrow 3:00 PM\nB: Tue 8:30 PM\nC: 11/29/26 1:00 PM", result)
        assertTrue(!result.contains("UTC"))
    }
}
