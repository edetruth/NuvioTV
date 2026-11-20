package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.SportsLiveDisplayText
import com.nuvio.tv.core.sportslive.SportsLiveItemBucketer
import com.nuvio.tv.core.sportslive.SportsLiveSportKind
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures
import com.nuvio.tv.domain.model.SportsLiveKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** live.20: a tennis replay filed under "MotoGP" read "MotoGP: Arthur Fils ATP Finals at Alexander Zverev". */
class SportsLiveTennisReplayTitleTest {
    private val title = "Arthur Fils ATP Finals @ Alexander Zverev"

    private fun replay(name: String, sport: String?) =
        AlertFixtures.item(name = name, id = name, sport = sport).copy(kind = SportsLiveKind.REPLAY)

    @Test
    fun `a title naming tennis is tennis whatever motorsport genre the addon gave it`() {
        val meta = replay(title, "MotoGP").meta
        assertEquals("Tennis", SportsLiveItemBucketer.sportOf(meta, "Replays"))
        // A real motorsport title keeps its genre.
        assertEquals("MotoGP", SportsLiveItemBucketer.sportOf(replay("MotoGP Valencia Race", "MotoGP").meta, "Replays"))
        assertNull(SportsLiveItemBucketer.misfiledMotorsport("Portugal Free Practice 1"))
    }

    @Test
    fun `the tournament comes off the player's name and becomes the league`() {
        assertEquals("Arthur Fils" to "ATP Finals", SportsLiveDisplayText.competitionSuffix("Arthur Fils ATP Finals"))
        assertEquals("Iga Swiatek" to "WTA 1000", SportsLiveDisplayText.competitionSuffix("Iga Swiatek WTA 1000"))
        assertNull(SportsLiveDisplayText.competitionSuffix("Alexander Zverev"))

        val row = replayRows(listOf(replay(title, "Tennis"))).single()
        val art = row.art!!
        assertEquals("Arthur Fils", art.first.name)
        assertEquals("Alexander Zverev", art.second.name)
        assertEquals("ATP Finals", art.league)
        assertEquals(SportsLiveSportKind.TENNIS, row.sportTag)
    }

    @Test
    fun `a tennis matchup reads vs in its written order, a team game written with at keeps at`() {
        val tennis = replayRows(listOf(replay(title, "Tennis"))).single()
        assertFalse(agendaSaysAt(tennis))
        val nba = replayRows(listOf(replay("Magic at Rockets", "NBA"))).single()
        assertTrue(agendaSaysAt(nba))
        assertFalse(agendaSaysAt(replayRows(listOf(replay("Magic vs Rockets", "NBA"))).single()))
    }
}
