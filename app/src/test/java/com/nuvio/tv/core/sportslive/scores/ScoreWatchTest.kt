package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.core.sportslive.alerts.AlertFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** live.20 Q18 (#85): the game being watched shows no score from us; the others wait while video plays. */
class ScoreWatchTest {
    private fun session(behind: Long? = null, source: String? = "a") = WatchingNow.Session(setOf("card"), source, behind)

    @Test
    fun `nothing playing keeps the profile's Score delay, Real-time scores none`() {
        assertEquals(30_000L, ScoreWatch.delayMs(30_000L, realTime = false, playing = emptyList(), nudges = emptyMap()))
        assertEquals(0L, ScoreWatch.delayMs(30_000L, realTime = true, playing = emptyList(), nudges = emptyMap()))
        assertEquals(0L, ScoreWatch.delayMs(30_000L, realTime = true, playing = listOf(session(90_000L)), nudges = mapOf("a" to 45)))
    }

    @Test
    fun `while video plays the delay is the measured offset plus 15 s, at least 60 s, plus the source's nudge`() {
        // Unknown or small offset: the 60 s floor, whatever the profile's own setting.
        assertEquals(60_000L, ScoreWatch.delayMs(0L, false, listOf(session()), emptyMap()))
        assertEquals(60_000L, ScoreWatch.delayMs(90_000L, false, listOf(session(20_000L)), emptyMap()))
        // 50 s behind live: 65 s.
        assertEquals(65_000L, ScoreWatch.delayMs(0L, false, listOf(session(50_000L)), emptyMap()))
        // Two players: the one further behind counts, and the largest nudge of the sources playing.
        assertEquals(
            125_000L + 30_000L,
            ScoreWatch.delayMs(0L, false, listOf(session(110_000L, "a"), session(5_000L, "b")), mapOf("a" to 15, "b" to 30))
        )
        // A nudge for a source not playing changes nothing.
        assertEquals(60_000L, ScoreWatch.delayMs(0L, false, listOf(session(source = "a")), mapOf("b" to 60)))
    }

    @Test
    fun `a nudge adds 15 s a press, up to 3 min`() {
        assertEquals(15, ScoreWatch.nudged(0))
        assertEquals(30, ScoreWatch.nudged(15))
        assertEquals(180, ScoreWatch.nudged(175))
        assertEquals(180, ScoreWatch.nudged(180))
        assertEquals(180_000L + 60_000L, ScoreWatch.delayMs(0L, false, listOf(session()), mapOf("a" to 900)))
    }

    @Test
    fun `the watched game is served with no records, form or tennis sets either`() {
        // F4: a watched game is masked as the score delay withholds one, records and form updated at the end included.
        val final = AlertFixtures.final(101, 99).let {
            it.copy(home = it.home.copy(record = "11-4", form = "WWWLW"), away = it.away.copy(record = "9-6", form = "LLWDW"))
        }
        val masked = ScoreWatch.masked(final)
        assertNull(masked.home.record)
        assertNull(masked.away.record)
        assertNull(masked.home.form)
        assertNull(masked.away.form)
        val match = TennisMatch(
            title = "Final",
            players = listOf(
                TennisPlayer("Player A", winner = true, sets = listOf(TennisSet(6, won = true)), serving = true, setsWon = 1),
                TennisPlayer("Player B", winner = false, sets = listOf(TennisSet(2)))
            ),
            ending = TennisEnding.RETIRED,
            live = true
        )
        val tennis = ScoreWatch.masked(final.copy(individual = match)).individual as TennisMatch
        assertTrue(tennis.players.all { it.sets.isEmpty() && !it.winner && !it.serving && it.setsWon == 0 })
        assertEquals(TennisEnding.COMPLETED, tennis.ending)
    }

    @Test
    fun `the watched game is served with no score, clock, scorers or situation, its state kept`() {
        val live = AlertFixtures.live(54, 50)
        val masked = ScoreWatch.masked(live)
        assertTrue(masked.watched)
        assertNull(masked.homeScore)
        assertNull(masked.awayScore)
        assertNull(masked.clockText)
        assertTrue(masked.home.linescores.isEmpty())
        assertTrue(masked.scorers.isEmpty())
        assertNull(masked.situation)
        assertEquals(live.state, masked.state)
        assertEquals(live.eventId, masked.eventId)
    }

    @Test
    fun `watched events are those of the cards playing, as matched`() {
        val event = AlertFixtures.live(54, 50)
        val scores = mapOf("card" to event, "other" to event.copy(eventId = "x"))
        assertEquals(setOf(event.eventId), ScoreWatch.watchedEvents(listOf(session()), scores))
        assertTrue(ScoreWatch.watchedEvents(listOf(WatchingNow.Session(setOf("unmatched"), null)), scores).isEmpty())
    }

    @Test
    fun `the live label says how far behind, and Go live from more than 10 s`() {
        assertEquals(ScoreWatch.LiveLabel(null, false), ScoreWatch.liveLabel(null))
        assertEquals(ScoreWatch.LiveLabel(null, false), ScoreWatch.liveLabel(400L))
        assertEquals(ScoreWatch.LiveLabel(8_000L, false), ScoreWatch.liveLabel(8_000L))
        assertEquals(ScoreWatch.LiveLabel(45_000L, true), ScoreWatch.liveLabel(45_000L))
    }

    @Test
    fun `players register, report their distance behind live in whole seconds, and stop`() {
        val watching = WatchingNow()
        watching.behindLive("p", 5_000L)
        assertTrue(watching.state.value.isEmpty())
        watching.watch("p", setOf("card"), "a")
        watching.behindLive("p", 45_400L)
        assertEquals(45_000L, watching.state.value.getValue("p").behindLiveMs)
        // The same cards again keep the measurement; others start fresh.
        watching.watch("p", setOf("card"), "a")
        assertEquals(45_000L, watching.state.value.getValue("p").behindLiveMs)
        watching.watch("p", setOf("next"), "a")
        assertNull(watching.state.value.getValue("p").behindLiveMs)
        watching.watch("p", emptySet(), "a")
        assertFalse("p" in watching.state.value)
        watching.watch("q", setOf("card"), null)
        watching.stop("q")
        assertTrue(watching.state.value.isEmpty())
    }
}
