package com.nuvio.tv.core.sportslive.alerts

import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.START
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.item
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.live
import com.nuvio.tv.core.sportslive.scores.CrunchMoment
import com.nuvio.tv.core.sportslive.scores.CrunchTime
import com.nuvio.tv.core.sportslive.scores.MyLeague
import com.nuvio.tv.core.sportslive.scores.MySport
import com.nuvio.tv.core.sportslive.scores.MySports
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The crunch-time alert (#137): spoilers, the setting, its words and which games are tracked for it. */
class SportsLiveCrunchAlertTest {

    // IND 100 - 98 PHI with a minute left: crunch time.
    private val close = live(home = 98, away = 100, clock = "1:00 - 4th")
    private val moment = CrunchTime.of(close)!!
    private val alert = SportsLiveAlert.Crunch(item(isLive = true), close, moment, namesOnly = false)

    @Test
    fun `the score shows under Show, names only under Hide, nothing under Off or with the setting off`() {
        assertEquals(alert, SportsLiveAlertEngine.stillShowable(alert, ScoresMode.SHOW, SportsLiveAlertSettings()))
        assertEquals(alert.copy(namesOnly = true), SportsLiveAlertEngine.stillShowable(alert, ScoresMode.HIDE_UNTIL_FOCUSED, SportsLiveAlertSettings()))
        assertNull(SportsLiveAlertEngine.stillShowable(alert, ScoresMode.OFF, SportsLiveAlertSettings()))
        assertNull(SportsLiveAlertEngine.stillShowable(alert, ScoresMode.SHOW, SportsLiveAlertSettings(closeGameAlerts = false)))
    }

    @Test
    fun `its words carry the score only when it may show, and say where the game stands`() {
        val shown = SportsLiveAlertContent.of(alert) as AlertContent.Crunch
        assertTrue(shown.withScore)
        assertEquals(listOf(100, 98), listOf(shown.first.score, shown.second.score))
        assertEquals(CrunchMoment(false, 1, "1:00 - 4th"), shown.moment)
        val hidden = SportsLiveAlertContent.of(alert.copy(namesOnly = true)) as AlertContent.Crunch
        assertEquals(false, hidden.withScore)
        assertEquals(listOf(null, null), listOf(hidden.first.score, hidden.second.score))
    }

    @Test
    fun `a crunch alert is a big moment and groups with other toasts`() {
        assertTrue(SportsLiveAlertCards.isBigMoment(alert))
        assertTrue(SportsLiveAlertCards.groupable(alert))
    }

    @Test
    fun `live games in My sports are tracked for crunch time, others and upcoming games are not`() {
        val nba = item("Pacers @ 76ers", isLive = true)
        val hockey = item("Bruins vs Rangers", isLive = true, sport = "Hockey")
        val later = item("Celtics vs Knicks", startMs = START + 5 * 60 * 60_000L)
        val basketballOnly = MySports(mapOf(MySport.BASKETBALL to setOf(MyLeague.NBA)))
        assertEquals(listOf(nba), SportsLiveAlertScope.crunchCardsToTrack(listOf(nba, hockey, later), basketballOnly, START + 60_000L))
        assertEquals(listOf(nba, hockey), SportsLiveAlertScope.crunchCardsToTrack(listOf(nba, hockey, later), MySports.DEFAULT, START + 60_000L))
        // A game ESPN said is over is not tracked any more.
        assertTrue(SportsLiveAlertScope.crunchCardsToTrack(listOf(nba), MySports.DEFAULT, START + 60_000L, ended = setOf(SportsLiveAlertEngine.gameKey(nba))).isEmpty())
    }
}
