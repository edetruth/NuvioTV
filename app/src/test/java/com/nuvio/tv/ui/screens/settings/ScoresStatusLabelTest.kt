package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.scores.FeedFailureKind
import com.nuvio.tv.core.sportslive.scores.ScoresStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ScoresStatusLabelTest {

    @Test
    fun `each status has its text, failures with the time they began`() {
        assertEquals(ScoresStatusLabel(R.string.sports_live_scores_off), ScoresStatusLabel.of(ScoresStatus.Off))
        assertEquals(ScoresStatusLabel(R.string.sports_live_scores_status_not_checked), ScoresStatusLabel.of(ScoresStatus.NotChecked))
        assertEquals(ScoresStatusLabel(R.string.sports_live_scores_status_working), ScoresStatusLabel.of(ScoresStatus.Working))
        assertEquals(
            ScoresStatusLabel(R.string.sports_live_scores_status_some_failing, 42L),
            ScoresStatusLabel.of(ScoresStatus.SomeFailing(42L, emptyList()))
        )
        assertEquals(
            ScoresStatusLabel(R.string.sports_live_scores_status_not_responding, 7L),
            ScoresStatusLabel.of(ScoresStatus.NotResponding(7L, emptyList()))
        )
    }

    @Test
    fun `a failure that began on another day shows its weekday`() {
        val zone = ZoneId.of("America/New_York")
        val now = Instant.parse("2026-11-18T14:00:00Z").toEpochMilli() // Wed 9 AM
        assertTrue(ScoresStatusLabel.showsDay(Instant.parse("2026-11-18T04:50:00Z").toEpochMilli(), now, zone)) // Tue 11:50 PM
        assertFalse(ScoresStatusLabel.showsDay(Instant.parse("2026-11-18T05:10:00Z").toEpochMilli(), now, zone)) // Wed 12:10 AM
    }

    @Test
    fun `each failure kind has a plain-language reason`() {
        assertEquals(R.string.sports_live_scores_reason_format, ScoresStatusLabel.reasonOf(FeedFailureKind.FORMAT))
        assertEquals(R.string.sports_live_scores_reason_network, ScoresStatusLabel.reasonOf(FeedFailureKind.NETWORK))
        assertEquals(R.string.sports_live_scores_reason_timeout, ScoresStatusLabel.reasonOf(FeedFailureKind.TIMEOUT))
        assertEquals(R.string.sports_live_scores_reason_http, ScoresStatusLabel.reasonOf(FeedFailureKind.HTTP))
    }
}
