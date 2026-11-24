package com.nuvio.tv.core.sportslive.alerts

import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.MINUTE
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.START
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.final
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.item
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.live
import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.ScoreSide
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Live.28 item a: score alert cadence (research-alert-cadence / -escalation, user decisions 2026-11-23 21:50 and 22:00).
 * One level for every followed team; Big moments by sport; late and close = crunch mode; bundling inside a game's gap.
 */
class SportsLiveScoreCadenceTest {

    private val card = item(followed = true)
    private val watchingOther = SportsLiveWatching(playingIds = setOf("movie"), inPlayer = true, streamStartedAtMs = START - 60 * MINUTE)

    private fun nba(home: Int, away: Int, clock: String) = live(home, away, clock)
    private fun nfl(home: Int, away: Int, clock: String) = live(home, away, clock).copy(league = EspnLeague("football", "nfl"))
    private fun nhl(home: Int, away: Int, clock: String) = live(home, away, clock).copy(league = EspnLeague("hockey", "nhl"))
    private fun epl(home: Int, away: Int, clock: String) = live(home, away, clock).copy(league = EspnLeague("soccer", "eng.1"))
    private fun mlb(home: Int, away: Int, clock: String) = live(home, away, clock).copy(league = EspnLeague("baseball", "mlb"))

    /** One followed game seen poll by poll while another game plays; [see] returns the score cards raised. */
    private inner class Game(private val settings: SportsLiveAlertSettings = SportsLiveAlertSettings()) {
        val engine = SportsLiveAlertEngine()
        var at = START + 10 * MINUTE

        fun all(snapshot: ScoreSnapshot, afterMs: Long = 90_000L, watching: SportsLiveWatching = watchingOther): List<SportsLiveAlert> {
            at += afterMs
            return engine.update(at, listOf(AlertGame(card, snapshot, false, setOf(ScoreSide.HOME))), settings, ScoresMode.SHOW, watching)
        }

        fun see(snapshot: ScoreSnapshot, afterMs: Long = 90_000L, watching: SportsLiveWatching = watchingOther): List<SportsLiveAlert.ScoreChange> =
            all(snapshot, afterMs, watching).filterIsInstance<SportsLiveAlert.ScoreChange>()
    }

    private fun List<SportsLiveAlert.ScoreChange>.moments() = map { it.moment }

    // Basketball

    @Test
    fun `basketball early is quiet but for halftime and the end of the 3rd`() {
        val game = Game()
        assertEquals(emptyList<Any>(), game.see(nba(10, 8, "8:00 - 1st")))
        assertEquals(emptyList<Any>(), game.see(nba(12, 8, "7:30 - 1st")))
        // A lead change in the 2nd quarter is not a big moment.
        assertEquals(emptyList<Any>(), game.see(nba(12, 14, "5:00 - 2nd")))
        assertEquals(emptyList<Any>(), game.see(nba(50, 48, "0:30 - 2nd")))
        val half = game.see(nba(52, 48, "Halftime")).single()
        assertEquals(ScoreMoment.PERIOD_BREAK, half.moment)
        assertEquals(null, half.scorer)
        assertEquals(emptyList<Any>(), game.see(nba(54, 50, "11:00 - 3rd")))
        assertEquals(emptyList<Any>(), game.see(nba(70, 75, "6:00 - 3rd")))
        assertEquals(listOf(ScoreMoment.PERIOD_BREAK), game.see(nba(80, 82, "End of 3rd")).moments())
        // Early in the 4th (over 10 minutes left) a lead change still waits.
        assertEquals(emptyList<Any>(), game.see(nba(84, 82, "11:00 - 4th")))
    }

    @Test
    fun `a halftime the polls missed counts once, from the 3rd quarter`() {
        val game = Game()
        game.see(nba(40, 38, "1:00 - 2nd"))
        assertEquals(listOf(ScoreMoment.PERIOD_BREAK), game.see(nba(56, 50, "11:20 - 3rd")).moments())
        assertEquals(emptyList<Any>(), game.see(nba(58, 50, "10:00 - 3rd")))
    }

    @Test
    fun `late and close is crunch mode - ties, late lead changes and scores in the last two minutes, a minute apart`() {
        val game = Game()
        game.see(nba(90, 88, "6:00 - 4th"))
        // Late but not yet the last 5 minutes; then the last 5, a basket that neither ties nor flips the lead.
        assertEquals(emptyList<Any>(), game.see(nba(92, 88, "5:30 - 4th"), 30_000))
        assertEquals(emptyList<Any>(), game.see(nba(92, 91, "4:40 - 4th"), 30_000))
        val tie = game.see(nba(92, 92, "4:10 - 4th"), 30_000).single()
        assertEquals(ScoreMoment.TIED, tie.moment)
        assertEquals(ScoreSide.AWAY, tie.scorer)
        // A lead change 30 s later waits inside the game's minute, then comes as one card measured from the tie card.
        assertEquals(emptyList<Any>(), game.see(nba(92, 94, "3:50 - 4th"), 30_000))
        val bundled = game.see(nba(92, 94, "3:30 - 4th"), 40_000).single()
        assertEquals(ScoreMoment.LEAD_CHANGE, bundled.moment)
        assertTrue(bundled.leadChange)
        assertEquals(ScoreSide.AWAY, bundled.scorer)
        assertEquals(listOf(ScoreMoment.LEAD_CHANGE), game.see(nba(95, 94, "1:50 - 4th"), 70_000).moments())
        // The last 2 minutes: any score.
        val score = game.see(nba(97, 94, "1:30 - 4th"), 70_000).single()
        assertEquals(ScoreMoment.SCORE, score.moment)
        assertEquals(ScoreSide.HOME, score.scorer)
        assertEquals(emptyList<Any>(), game.see(nba(97, 95, "1:10 - 4th"), 20_000))
        val free = game.see(nba(97, 95, "0:50 - 4th"), 45_000).single()
        assertEquals(ScoreMoment.SCORE, free.moment)
        assertEquals(ScoreSide.AWAY, free.scorer)
    }

    @Test
    fun `late but not close stays quiet until the final`() {
        val game = Game()
        game.see(nba(90, 70, "6:00 - 4th"))
        assertEquals(emptyList<Any>(), game.see(nba(92, 72, "4:00 - 4th")))
        assertEquals(emptyList<Any>(), game.see(nba(94, 72, "1:00 - 4th")))
        assertTrue(game.all(final(101, 80)).single() is SportsLiveAlert.FullTime)
    }

    @Test
    fun `each overtime start shows once, and a lead change in overtime shows`() {
        val game = Game()
        game.see(nba(100, 100, "0:05 - 4th"))
        val ot = game.see(nba(100, 100, "5:00 - OT")).single()
        assertEquals(ScoreMoment.OVERTIME, ot.moment)
        assertEquals(null, ot.scorer)
        assertEquals(emptyList<Any>(), game.see(nba(102, 100, "4:30 - OT"), 30_000))
        assertEquals(listOf(ScoreMoment.LEAD_CHANGE), game.see(nba(102, 104, "3:00 - OT"), 70_000).moments())
        assertEquals(listOf(ScoreMoment.OVERTIME), game.see(nba(110, 110, "5:00 - 2OT"), 70_000).moments())
    }

    @Test
    fun `a big lead cut to five late is a comeback, once`() {
        val game = Game()
        game.see(nba(60, 80, "2:00 - 3rd"))
        assertEquals(listOf(ScoreMoment.PERIOD_BREAK), game.see(nba(64, 82, "End of 3rd")).moments())
        assertEquals(emptyList<Any>(), game.see(nba(75, 82, "9:00 - 4th")))
        val comeback = game.see(nba(78, 82, "8:00 - 4th")).single()
        assertEquals(ScoreMoment.COMEBACK, comeback.moment)
        assertEquals(ScoreSide.HOME, comeback.scorer)
        assertEquals(emptyList<Any>(), game.see(nba(80, 82, "7:30 - 4th")))
    }

    // Football

    @Test
    fun `football early is halftime and the end of the 3rd, a late field goal within two scores shows`() {
        val game = Game()
        game.see(nfl(14, 7, "5:00 - 2nd"))
        // A field goal in the 2nd quarter waits for halftime (the lead stays under the comeback size).
        assertEquals(emptyList<Any>(), game.see(nfl(17, 7, "2:00 - 2nd")))
        assertEquals(listOf(ScoreMoment.PERIOD_BREAK), game.see(nfl(17, 10, "Halftime")).moments())
        assertEquals(emptyList<Any>(), game.see(nfl(17, 10, "10:00 - 3rd")))
        assertEquals(listOf(ScoreMoment.PERIOD_BREAK), game.see(nfl(24, 17, "End of 3rd")).moments())
        assertEquals(emptyList<Any>(), game.see(nfl(24, 17, "4:00 - 4th")))
        val fieldGoal = game.see(nfl(24, 20, "1:50 - 4th")).single()
        assertEquals(ScoreMoment.SCORE, fieldGoal.moment)
        assertEquals(ScoreSide.AWAY, fieldGoal.scorer)
    }

    // Soccer and hockey

    @Test
    fun `every soccer goal shows, a quick equaliser too, after half a minute`() {
        val game = Game()
        game.see(epl(0, 0, "20'"))
        val goal = game.see(epl(1, 0, "23'")).single()
        assertEquals(ScoreMoment.SCORE, goal.moment)
        assertEquals(ScoreSide.HOME, goal.scorer)
        assertEquals(emptyList<Any>(), game.see(epl(1, 1, "23'"), 20_000))
        val equaliser = game.see(epl(1, 1, "24'"), 15_000).single()
        assertEquals(ScoreMoment.SCORE, equaliser.moment)
        assertEquals(ScoreSide.AWAY, equaliser.scorer)
    }

    @Test
    fun `every hockey goal shows`() {
        val game = Game()
        game.see(nhl(1, 1, "10:00 - 2nd"))
        assertEquals(ScoreSide.HOME, game.see(nhl(2, 1, "9:00 - 2nd")).single().scorer)
        assertEquals(ScoreSide.AWAY, game.see(nhl(2, 2, "4:00 - 3rd")).single().scorer)
    }

    // Other sports

    @Test
    fun `baseball shows a lead change late, not early`() {
        val game = Game()
        game.see(mlb(1, 0, "Top 3rd"))
        assertEquals(emptyList<Any>(), game.see(mlb(1, 2, "Bot 3rd")))
        assertEquals(listOf(ScoreMoment.LEAD_CHANGE), game.see(mlb(3, 2, "Top 8th")).moments())
    }

    // Levels

    @Test
    fun `Every score shows each score at most every two minutes, the ones in between bundled`() {
        val game = Game(AlertFixtures.EVERY)
        game.see(nba(10, 8, "8:00 - 1st"))
        assertEquals(listOf(ScoreMoment.SCORE), game.see(nba(12, 8, "7:30 - 1st")).moments())
        assertEquals(emptyList<Any>(), game.see(nba(14, 8, "7:00 - 1st"), 30_000))
        val bundled = game.see(nba(14, 8, "6:40 - 1st"), 100_000).single()
        assertEquals(ScoreMoment.SCORE, bundled.moment)
        assertEquals(nba(14, 8, "6:40 - 1st"), bundled.snapshot)
    }

    @Test
    fun `Off shows no score card, not even halftime`() {
        val game = Game(SportsLiveAlertSettings(scoreAlertLevel = ScoreAlertLevel.OFF))
        game.see(nba(40, 38, "1:00 - 2nd"))
        assertEquals(emptyList<Any>(), game.see(nba(48, 44, "Halftime")))
        assertEquals(emptyList<Any>(), game.see(epl(1, 0, "23'")))
    }

    @Test
    fun `a moment while nothing else plays is never announced later`() {
        val game = Game()
        game.see(nba(40, 40, "1:00 - 2nd"))
        assertEquals(emptyList<Any>(), game.see(nba(48, 44, "Halftime"), watching = SportsLiveWatching()))
        assertEquals(emptyList<Any>(), game.see(nba(50, 44, "11:00 - 3rd")))
    }

    @Test
    fun `the card says what the moment is`() {
        val alert = SportsLiveAlert.ScoreChange(card, live(10, 11), ScoreSide.AWAY, leadChange = true, moment = ScoreMoment.LEAD_CHANGE)
        val content = SportsLiveAlertContent.of(alert) as AlertContent.Score
        assertEquals(ScoreMoment.LEAD_CHANGE, content.moment)
        assertEquals(ScoreMoment.SCORE, (SportsLiveAlertContent.of(alert.copy(leadChange = false, moment = ScoreMoment.SCORE)) as AlertContent.Score).moment)
        // A lead change keeps its longer time on screen.
        assertEquals(SportsLiveAlertCards.LEAD_CHANGE_MS, SportsLiveAlertCards.durationMs(alert))
    }

    @Test
    fun `the level reads from the old switch until one is stored`() {
        assertEquals(ScoreAlertLevel.BIG_MOMENTS, ScoreAlertLevel.fromStored(null, null))
        assertEquals(ScoreAlertLevel.BIG_MOMENTS, ScoreAlertLevel.fromStored(null, true))
        assertEquals(ScoreAlertLevel.OFF, ScoreAlertLevel.fromStored(null, false))
        assertEquals(ScoreAlertLevel.EVERY_SCORE, ScoreAlertLevel.fromStored("EVERY_SCORE", false))
        assertEquals(ScoreAlertLevel.BIG_MOMENTS, ScoreAlertLevel.fromStored("unknown", null))
        assertEquals(ScoreAlertLevel.BIG_MOMENTS, SportsLiveAlertSettings().scoreAlertLevel)
    }

    @Test
    fun `breaks read from the clock, quarters and halves`() {
        assertEquals(0, ScoreAlertCadence.breaksOf("basketball", "5:00 - 2nd", overtime = false))
        assertEquals(1, ScoreAlertCadence.breaksOf("basketball", "Halftime", overtime = false))
        assertEquals(1, ScoreAlertCadence.breaksOf("basketball", "End of 2nd", overtime = false))
        assertEquals(1, ScoreAlertCadence.breaksOf("basketball", "8:00 - 3rd", overtime = false))
        assertEquals(2, ScoreAlertCadence.breaksOf("basketball", "End of 3rd", overtime = false))
        assertEquals(2, ScoreAlertCadence.breaksOf("basketball", "Q4 2:00", overtime = false))
        assertEquals(2, ScoreAlertCadence.breaksOf("basketball", "5:00 - OT", overtime = true))
        // College basketball's two halves: halftime only.
        assertEquals(0, ScoreAlertCadence.breaksOf("basketball", "8:00 - 1st Half", overtime = false))
        assertEquals(1, ScoreAlertCadence.breaksOf("basketball", "End of 1st Half", overtime = false))
        assertEquals(1, ScoreAlertCadence.breaksOf("basketball", "12:00 - 2nd Half", overtime = false))
        assertEquals(0, ScoreAlertCadence.breaksOf("hockey", "End of 2nd", overtime = false))
        assertEquals(2, ScoreAlertCadence.overtimeNumber("5:00 - 2OT"))
        assertEquals(1, ScoreAlertCadence.overtimeNumber("5:00 - OT"))
    }
}
