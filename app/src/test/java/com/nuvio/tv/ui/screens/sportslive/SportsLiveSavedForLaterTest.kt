package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.alerts.AlertFixtures
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.START
import com.nuvio.tv.domain.model.SportsLiveKind
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneOffset

/** Replays' "Saved for later" (#151): the saved games' replays lead the list under their own header. */
class SportsLiveSavedForLaterTest {

    private val hour = 60 * 60_000L
    private fun replay(name: String, startMs: Long?) =
        AlertFixtures.item(name = name, id = name, startMs = startMs).copy(kind = SportsLiveKind.REPLAY)

    @Test
    fun `saved games' replays come first under Saved for later, the rest by day`() {
        val newest = replay("Celtics vs Knicks", START + 2 * hour)
        val saved = replay("Pacers vs 76ers", START - 30 * hour)
        val undated = replay("Highlights", null)
        val rows = replayRows(listOf(newest, saved, undated)) { _, _ -> null }

        val agenda = SportsLiveAgenda.ofReplays(rows, START + 3 * hour, ZoneOffset.UTC, saved = setOf(saved.streamKey))

        assertEquals(AgendaDayKind.SAVED, agenda.days.first().kind)
        assertEquals(listOf(saved.streamKey, newest.streamKey, undated.streamKey), agenda.items.map { it.streamKey })
        assertEquals(listOf(AgendaDayKind.SAVED, AgendaDayKind.TODAY, AgendaDayKind.UNDATED), agenda.days.map { it.kind })
    }

    @Test
    fun `with nothing saved the list is as before`() {
        val rows = replayRows(listOf(replay("Celtics vs Knicks", START))) { _, _ -> null }
        val agenda = SportsLiveAgenda.ofReplays(rows, START + hour, ZoneOffset.UTC)
        assertEquals(listOf(AgendaDayKind.TODAY), agenda.days.map { it.kind })
    }
}
