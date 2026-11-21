package com.nuvio.tv.core.sportslive.alerts

import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.MINUTE
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.START
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.final
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.item
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.live
import com.nuvio.tv.core.sportslive.scores.ScoreSide
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fable review of live.23, M1 (user decision): a followed team's hidden result gets a spoiler-free Full time card. */
class SportsLiveReviewFixesTest {

    private val card = item(followed = true)

    private fun fullTime(mode: ScoresMode, resultProtected: Boolean, settings: SportsLiveAlertSettings = SportsLiveAlertSettings()): List<SportsLiveAlert> {
        val engine = SportsLiveAlertEngine()
        val game = { s: ScoreSnapshot -> listOf(AlertGame(card, s, false, setOf(ScoreSide.HOME))) }
        engine.update(START + 100 * MINUTE, game(live(98, 98)), settings, mode, SportsLiveWatching())
        // The final is stamped protected in the same publish ("Hide my teams' results", on by default).
        return engine.update(START + 130 * MINUTE, game(final(101, 99).copy(spoilerProtected = resultProtected)), settings, mode, SportsLiveWatching())
    }

    @Test
    fun `a protected final gives the spoiler-free card, never silence`() {
        val alert = fullTime(ScoresMode.SHOW, resultProtected = true).single() as SportsLiveAlert.FullTime
        assertTrue(alert.resultHidden)
        assertTrue(alert.namesOnly)
        // The followed side by ESPN data: the home team here.
        assertTrue(alert.team?.isNotBlank() == true)
        // Its card takes OK (Watch replay) and says nothing of the result: no score on either side.
        assertTrue(SportsLiveAlertCards.actionable(alert))
        val content = SportsLiveAlertContent.of(alert) as AlertContent.FullTimeHidden
        assertNull(content.first.score)
        assertNull(content.second.score)
        // Under Hide until focused too; nothing under Off or with Full time alerts off.
        assertTrue((fullTime(ScoresMode.HIDE_UNTIL_FOCUSED, resultProtected = true).single() as SportsLiveAlert.FullTime).resultHidden)
        assertEquals(emptyList<SportsLiveAlert>(), fullTime(ScoresMode.OFF, resultProtected = true))
        assertEquals(emptyList<SportsLiveAlert>(), fullTime(ScoresMode.SHOW, resultProtected = true, SportsLiveAlertSettings(fullTimeAlerts = false)))
        // Kept as it is when it waited while the Scores setting changed to Hide.
        assertEquals(alert, SportsLiveAlertEngine.stillShowable(alert, ScoresMode.HIDE_UNTIL_FOCUSED, SportsLiveAlertSettings()))
    }

    @Test
    fun `with the result shown Full time stays information only`() {
        val alert = fullTime(ScoresMode.SHOW, resultProtected = false).single() as SportsLiveAlert.FullTime
        assertTrue(!alert.resultHidden)
        assertTrue(!SportsLiveAlertCards.actionable(alert))
    }
}
