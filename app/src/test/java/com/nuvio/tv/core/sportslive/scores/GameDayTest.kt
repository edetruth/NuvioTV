package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.core.sportslive.alerts.AlertFixtures
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.ui.screens.sportslive.AgendaDayKind
import com.nuvio.tv.ui.screens.sportslive.SportsLiveAgenda
import com.nuvio.tv.ui.screens.sportslive.replayRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** live.20 BUG14: one rule for the day a game is listed under, on Replays as on Results and the Game page. */
class GameDayTest {
    private val eastern = ZoneId.of("America/New_York")
    private val pacific = ZoneId.of("America/Los_Angeles")

    private fun ms(day: Int, hour: Int, zone: ZoneId, minute: Int = 0): Long =
        ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun replay(name: String, startMs: Long?, timed: Boolean = startMs != null) =
        AlertFixtures.item(name = name, id = name, startMs = startMs).copy(kind = SportsLiveKind.REPLAY, startHasTimeOfDay = timed)

    private val utcMidnight18 = ms(18, 0, ZoneId.of("UTC"))

    @Test
    fun `an addon's bare date is that date, not the evening before west of UTC`() {
        // The addon writes "2026-11-18" (as midnight UTC): Tue 17 Nov 19:00 in New York before the fix.
        assertEquals(LocalDate.of(2026, 11, 18), GameDay.of(replay("Celtics vs Nuggets", utcMidnight18, timed = false), null, eastern))
        // The same date sent in `released` as "2026-11-18T00:00:00.000Z", which reads as timed.
        assertEquals(LocalDate.of(2026, 11, 18), GameDay.of(replay("Celtics vs Nuggets", utcMidnight18, timed = true), null, eastern))
    }

    @Test
    fun `a real time of day is read in the TV's zone`() {
        // 22:30 Pacific on the 18th is the 19th in UTC: still the 18th here.
        assertEquals(LocalDate.of(2026, 11, 18), GameDay.of(replay("Warriors vs Grizzlies", ms(18, 22, pacific, 30)), null, pacific))
        assertNull(GameDay.of(replay("Highlights", null), null, eastern))
    }

    @Test
    fun `a matched ESPN game's start wins, as on its Game page`() {
        val event = AlertFixtures.final(101, 99).copy(startMs = ms(19, 19, eastern, 30))
        assertEquals(LocalDate.of(2026, 11, 19), GameDay.of(replay("Warriors vs Grizzlies", utcMidnight18), event, eastern))
        // A start ESPN marks as not valid (time to be decided) is not used.
        assertEquals(LocalDate.of(2026, 11, 18), GameDay.of(replay("Warriors vs Grizzlies", utcMidnight18), event.copy(timeValid = false), eastern))
    }

    @Test
    fun `Replays put a bare-dated replay under its own day and a matched one under its game's day`() {
        val now = ms(20, 10, eastern)
        val bare = replay("Celtics vs Nuggets", utcMidnight18, timed = false)
        val matched = replay("Warriors vs Grizzlies", utcMidnight18)
        val game = AlertFixtures.final(101, 99).copy(startMs = ms(19, 22, eastern))
        val rows = replayRows(listOf(bare, matched)) { _, _ -> null }
        val agenda = SportsLiveAgenda.ofReplays(rows, now, eastern) { if (it.streamKey == matched.streamKey) game else null }
        assertEquals(listOf(AgendaDayKind.YESTERDAY, AgendaDayKind.DATE), agenda.days.map { it.kind })
        assertEquals(listOf(19, 18), agenda.days.mapNotNull { it.date?.dayOfMonth })
        assertEquals(listOf(matched.streamKey, bare.streamKey), agenda.items.map { it.streamKey })
    }
}
