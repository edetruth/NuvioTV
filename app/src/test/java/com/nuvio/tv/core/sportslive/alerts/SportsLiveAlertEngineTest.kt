package com.nuvio.tv.core.sportslive.alerts

import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.MINUTE
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.START
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.final
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.item
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.live
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.pre
import com.nuvio.tv.core.sportslive.scores.ScoreDelay
import com.nuvio.tv.core.sportslive.scores.ScoreSide
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.domain.model.SportsLiveItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsLiveAlertEngineTest {

    private val engine = SportsLiveAlertEngine()
    private val card = item(followed = true)
    private val watchingOther = SportsLiveWatching(playingIds = setOf("movie"), inPlayer = true, streamStartedAtMs = START - 60 * MINUTE)

    private fun step(
        at: Long,
        snapshot: ScoreSnapshot?,
        reminded: Boolean = true,
        // Kickoff tests look at a reminded game alone; score and full-time tests at a followed side's game.
        followed: Set<ScoreSide> = if (reminded) emptySet() else setOf(ScoreSide.AWAY),
        settings: SportsLiveAlertSettings = SportsLiveAlertSettings(),
        mode: ScoresMode = ScoresMode.SHOW,
        watching: SportsLiveWatching = watchingOther,
        game: SportsLiveItem = card
    ): List<SportsLiveAlert> = engine.update(at, listOf(AlertGame(game, snapshot, reminded, followed)), settings, mode, watching)

    // Kickoff

    @Test
    fun `a reminded game prompts when ESPN turns it live, not before`() {
        assertEquals(emptyList<SportsLiveAlert>(), step(START - 5 * MINUTE, pre()))
        // ESPN's start slips: still pre-game at the listed start, so it waits.
        assertEquals(emptyList<SportsLiveAlert>(), step(START + MINUTE, pre()))
        val alerts = step(START + 4 * MINUTE, live(0, 0))
        assertEquals(listOf(SportsLiveAlert.Kickoff(card, live(0, 0))), alerts)
        // Asked once.
        assertEquals(emptyList<SportsLiveAlert>(), step(START + 5 * MINUTE, live(2, 0)))
    }

    @Test
    fun `a game seen live long after its start does not prompt, unless it was seen before kick-off`() {
        assertEquals(emptyList<SportsLiveAlert>(), step(START + 45 * MINUTE, live(20, 18)))

        val other = SportsLiveAlertEngine()
        other.update(START - MINUTE, listOf(AlertGame(card, pre(), true)), SportsLiveAlertSettings(), ScoresMode.SHOW, watchingOther)
        // A long delay before tip-off: the pre → live change still prompts.
        val alerts = other.update(START + 45 * MINUTE, listOf(AlertGame(card, live(0, 0), true)), SportsLiveAlertSettings(), ScoresMode.SHOW, watchingOther)
        assertEquals(1, alerts.size)
    }

    @Test
    fun `with no ESPN game the start time prompts, within the kickoff window only`() {
        assertEquals(emptyList<SportsLiveAlert>(), step(START - MINUTE, null))
        assertEquals(listOf(SportsLiveAlert.Kickoff(card, null)), step(START, null))

        val late = SportsLiveAlertEngine()
        val alerts = late.update(START + 30 * MINUTE, listOf(AlertGame(card, null, true)), SportsLiveAlertSettings(), ScoresMode.OFF, watchingOther)
        assertEquals(emptyList<SportsLiveAlert>(), alerts)
    }

    @Test
    fun `a game not reminded never prompts`() {
        assertEquals(emptyList<SportsLiveAlert>(), step(START, null, reminded = false))
    }

    @Test
    fun `Later asks again once after five minutes, and only once`() {
        step(START, null)
        engine.later(card, START)
        assertEquals(emptyList<SportsLiveAlert>(), step(START + 4 * MINUTE, null))
        assertEquals(listOf(SportsLiveAlert.Kickoff(card, null, again = true)), step(START + 5 * MINUTE, null))
        engine.later(card, START + 5 * MINUTE)
        assertEquals(emptyList<SportsLiveAlert>(), step(START + 11 * MINUTE, null))
        assertEquals(emptyList<SportsLiveAlert>(), step(START + 20 * MINUTE, null))
    }

    @Test
    fun `Watch or Back on the prompt ends it`() {
        step(START, null)
        engine.answered(card)
        assertEquals(emptyList<SportsLiveAlert>(), step(START + 6 * MINUTE, null))
    }

    @Test
    fun `a re-ask is dropped once the game is over`() {
        step(START, live(0, 0))
        engine.later(card, START)
        assertEquals(emptyList<SportsLiveAlert>(), step(START + 6 * MINUTE, final(100, 98)))
    }

    @Test
    fun `the game playing never prompts, nor later`() {
        val playing = SportsLiveWatching(playingIds = setOf(card.id), inPlayer = true, streamStartedAtMs = START - MINUTE)
        assertEquals(emptyList<SportsLiveAlert>(), step(START, null, watching = playing))
        assertEquals(emptyList<SportsLiveAlert>(), step(START + MINUTE, null))
    }

    @Test
    fun `a merged card playing from another source counts as playing`() {
        val alternate = item(id = "other", addonId = "b")
        val merged = item(followed = true, alternates = listOf(alternate))
        val playing = SportsLiveWatching(playingIds = setOf("other"), inPlayer = true)
        assertTrue(SportsLiveAlertEngine.isPlaying(merged, playing))
    }

    @Test
    fun `nothing in a stream's first ten seconds, and the prompt waits for them`() {
        val justStarted = SportsLiveWatching(playingIds = setOf("movie"), inPlayer = true, streamStartedAtMs = START - 5_000)
        assertEquals(emptyList<SportsLiveAlert>(), step(START, null, watching = justStarted))
        assertEquals(listOf(SportsLiveAlert.Kickoff(card, null)), step(START + 6_000, null, watching = justStarted))
    }

    @Test
    fun `dual screen holds the prompt`() {
        assertEquals(emptyList<SportsLiveAlert>(), step(START, null, watching = SportsLiveWatching(dual = true)))
        assertEquals(1, step(START + MINUTE, null, watching = SportsLiveWatching()).size)
    }

    // Score change

    @Test
    fun `a followed team's game scoring shows a toast naming the side that scored`() {
        step(START + 10 * MINUTE, live(10, 8), reminded = false)
        val alerts = step(START + 11 * MINUTE, live(10, 11), reminded = false)
        assertEquals(listOf(SportsLiveAlert.ScoreChange(card, live(10, 11), ScoreSide.AWAY)), alerts)
    }

    @Test
    fun `score toasts are for followed teams' games only`() {
        step(START + 10 * MINUTE, live(10, 8), reminded = false, followed = emptySet())
        assertEquals(emptyList<SportsLiveAlert>(), step(START + 11 * MINUTE, live(12, 8), reminded = false, followed = emptySet()))
    }

    @Test
    fun `at most one score toast per game every two minutes`() {
        step(START + 10 * MINUTE, live(10, 8), reminded = false)
        assertEquals(1, step(START + 10 * MINUTE + 10_000, live(12, 8), reminded = false).size)
        assertEquals(0, step(START + 11 * MINUTE, live(14, 8), reminded = false).size)
        assertEquals(0, step(START + 11 * MINUTE + 59_000, live(14, 10), reminded = false).size)
        assertEquals(1, step(START + 12 * MINUTE + 10_000, live(16, 10), reminded = false).size)
    }

    @Test
    fun `no score toast under Hide until focused or Off, nor with score alerts off`() {
        step(START + 10 * MINUTE, live(10, 8), reminded = false)
        assertEquals(0, step(START + 11 * MINUTE, live(12, 8), reminded = false, mode = ScoresMode.HIDE_UNTIL_FOCUSED).size)
        assertEquals(0, step(START + 14 * MINUTE, live(14, 8), reminded = false, mode = ScoresMode.OFF).size)
        assertEquals(0, step(START + 17 * MINUTE, live(16, 8), reminded = false, settings = SportsLiveAlertSettings(scoreAlerts = false)).size)
        assertEquals(1, step(START + 20 * MINUTE, live(18, 8), reminded = false).size)
    }

    @Test
    fun `score toasts only while watching something else in the player`() {
        step(START + 10 * MINUTE, live(10, 8), reminded = false)
        assertEquals(0, step(START + 11 * MINUTE, live(12, 8), reminded = false, watching = SportsLiveWatching()).size)
        val playingIt = SportsLiveWatching(playingIds = setOf(card.id), inPlayer = true)
        assertEquals(0, step(START + 14 * MINUTE, live(14, 8), reminded = false, watching = playingIt).size)
    }

    @Test
    fun `a first look, a withheld score and a correction never toast`() {
        // First seen with a score: no change known.
        assertEquals(0, step(START + 10 * MINUTE, live(10, 8), reminded = false).size)
        // Corrected down.
        assertEquals(0, step(START + 13 * MINUTE, live(9, 8), reminded = false).size)
        val fresh = SportsLiveAlertEngine()
        val game = { s: ScoreSnapshot -> listOf(AlertGame(card, s, false, setOf(ScoreSide.AWAY))) }
        // The delay withholds the score of a game first seen live; its arrival is not a change.
        fresh.update(START + 10 * MINUTE, game(live(null, null)), SportsLiveAlertSettings(), ScoresMode.SHOW, watchingOther)
        assertEquals(0, fresh.update(START + 11 * MINUTE, game(live(10, 8)), SportsLiveAlertSettings(), ScoresMode.SHOW, watchingOther).size)
    }

    @Test
    fun `score toasts follow the delayed feed`() {
        val delay = ScoreDelay()
        val delayMs = 30_000L
        val shown = { at: Long, raw: ScoreSnapshot -> delay.apply(mapOf(card.streamKey to raw), delayMs, at).getValue(card.streamKey) }
        var at = START + 10 * MINUTE
        // Seen live: the score is withheld for the delay, then shows.
        step(at, shown(at, live(10, 8)), reminded = false)
        at += delayMs
        step(at, shown(at, live(10, 8)), reminded = false)
        // A basket arrives: nothing until the delay has passed.
        at += 5_000
        assertEquals(0, step(at, shown(at, live(10, 11)), reminded = false).size)
        at += delayMs - 5_000
        assertEquals(0, step(at, shown(at, live(10, 11)), reminded = false).size)
        at += 5_000
        assertEquals(listOf(SportsLiveAlert.ScoreChange(card, live(10, 11), ScoreSide.AWAY)), step(at, shown(at, live(10, 11)), reminded = false))
    }

    // Full time

    @Test
    fun `full time shows the score under Show, names only under Hide until focused, nothing under Off`() {
        fun fullTime(mode: ScoresMode): List<SportsLiveAlert> {
            val e = SportsLiveAlertEngine()
            val game = { s: ScoreSnapshot -> listOf(AlertGame(card, s, false, setOf(ScoreSide.HOME))) }
            e.update(START + 100 * MINUTE, game(live(98, 98)), SportsLiveAlertSettings(), mode, SportsLiveWatching())
            return e.update(START + 130 * MINUTE, game(final(101, 99)), SportsLiveAlertSettings(), mode, SportsLiveWatching())
        }
        assertEquals(listOf(SportsLiveAlert.FullTime(card, final(101, 99), namesOnly = false)), fullTime(ScoresMode.SHOW))
        assertEquals(listOf(SportsLiveAlert.FullTime(card, final(101, 99), namesOnly = true)), fullTime(ScoresMode.HIDE_UNTIL_FOCUSED))
        assertEquals(emptyList<SportsLiveAlert>(), fullTime(ScoresMode.OFF))
    }

    @Test
    fun `full time needs the game seen live, a followed side, the setting, and not the game playing`() {
        // First seen already final: old news.
        assertEquals(0, step(START + 200 * MINUTE, final(101, 99), reminded = false).size)

        step(START + 100 * MINUTE, live(98, 98), reminded = false, followed = emptySet())
        assertEquals(0, step(START + 130 * MINUTE, final(101, 99), reminded = false, followed = emptySet()).size)

        val off = SportsLiveAlertEngine()
        val game = { s: ScoreSnapshot -> listOf(AlertGame(card, s, false, setOf(ScoreSide.HOME))) }
        off.update(START, game(live(1, 1)), SportsLiveAlertSettings(fullTimeAlerts = false), ScoresMode.SHOW, SportsLiveWatching())
        assertEquals(0, off.update(START + MINUTE, game(final(2, 1)), SportsLiveAlertSettings(fullTimeAlerts = false), ScoresMode.SHOW, SportsLiveWatching()).size)

        val playing = SportsLiveAlertEngine()
        val watchingIt = SportsLiveWatching(playingIds = setOf(card.id), inPlayer = true)
        playing.update(START, game(live(1, 1)), SportsLiveAlertSettings(), ScoresMode.SHOW, watchingIt)
        assertEquals(0, playing.update(START + MINUTE, game(final(2, 1)), SportsLiveAlertSettings(), ScoresMode.SHOW, watchingIt).size)
    }

    @Test
    fun `full time is announced once`() {
        step(START + 100 * MINUTE, live(98, 98), reminded = false)
        assertEquals(1, step(START + 130 * MINUTE, final(101, 99), reminded = false).size)
        assertEquals(0, step(START + 131 * MINUTE, final(101, 99), reminded = false).size)
    }

    // Review fixes

    @Test
    fun `over a movie or show the kickoff is a toast only, never a focused prompt`() {
        assertEquals(KickoffStyle.FULL, SportsLiveAlertEngine.kickoffStyle(SportsLiveWatching()))
        assertEquals(KickoffStyle.COMPACT, SportsLiveAlertEngine.kickoffStyle(SportsLiveWatching(inPlayer = true, sportsPlayback = true)))
        assertEquals(KickoffStyle.TOAST, SportsLiveAlertEngine.kickoffStyle(SportsLiveWatching(inPlayer = true, sportsPlayback = false)))
    }

    @Test
    fun `a queued toast is checked against the Scores setting and switches before it shows`() {
        val score = SportsLiveAlert.ScoreChange(card, live(10, 11), ScoreSide.AWAY)
        val fullTime = SportsLiveAlert.FullTime(card, final(101, 99), namesOnly = false)
        val kickoff = SportsLiveAlert.Kickoff(card, null)
        val on = SportsLiveAlertSettings()
        assertEquals(score, SportsLiveAlertEngine.stillShowable(score, ScoresMode.SHOW, on))
        assertEquals(null, SportsLiveAlertEngine.stillShowable(score, ScoresMode.HIDE_UNTIL_FOCUSED, on))
        assertEquals(null, SportsLiveAlertEngine.stillShowable(score, ScoresMode.SHOW, SportsLiveAlertSettings(scoreAlerts = false)))
        assertEquals(fullTime.copy(namesOnly = true), SportsLiveAlertEngine.stillShowable(fullTime, ScoresMode.HIDE_UNTIL_FOCUSED, on))
        assertEquals(null, SportsLiveAlertEngine.stillShowable(fullTime, ScoresMode.OFF, on))
        assertEquals(null, SportsLiveAlertEngine.stillShowable(fullTime, ScoresMode.SHOW, SportsLiveAlertSettings(fullTimeAlerts = false)))
        assertEquals(kickoff, SportsLiveAlertEngine.stillShowable(kickoff, ScoresMode.OFF, on))
    }

    @Test
    fun `prompt state belongs to the game, not the card's primary source`() {
        step(START, null)
        engine.answered(card)
        // The same game listed by another addon (now the merged card's primary): not asked again.
        val otherCopy = item(id = "x", addonId = "z", followed = true)
        assertEquals(SportsLiveAlertEngine.gameKey(card), SportsLiveAlertEngine.gameKey(otherCopy))
        assertEquals(emptyList<SportsLiveAlert>(), step(START + MINUTE, null, game = otherCopy))
        // A rematch days later is another game.
        val rematch = item(startMs = START + 2 * 24 * 60 * MINUTE)
        assertTrue(SportsLiveAlertEngine.gameKey(card) != SportsLiveAlertEngine.gameKey(rematch))
    }
}
