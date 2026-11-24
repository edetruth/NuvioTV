package com.nuvio.tv.core.sportslive.scores

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** live.23: a game first seen shows its score at once; only the game being watched keeps the first-seen hold. */
class ScoreDelayTest {

    private val nba = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA)
    private val start = 1_000_000_000_000L
    private val base = nba.first { it.eventId == "401909985" }.copy(startMs = start)
    private val key = "card"
    private val delayMs = 60_000L

    private fun live(home: Int, away: Int, clock: String = "Q2 5:00") = base.copy(
        state = ScoreState.IN, isFinal = false, clockText = clock,
        home = base.home.copy(score = home), away = base.away.copy(score = away)
    )

    private fun final(home: Int, away: Int) = base.copy(
        state = ScoreState.POST, isFinal = true, clockText = "Final",
        home = base.home.copy(score = home), away = base.away.copy(score = away)
    )

    @Test
    fun `a game first seen live shows its score at once and later changes wait the delay`() {
        val delay = ScoreDelay()
        val t0 = start + 3_600_000L
        val first = delay.apply(mapOf(key to live(50, 48)), delayMs, t0).getValue(key)
        assertEquals(50, first.homeScore)
        assertEquals("Q2 5:00", first.clockText)
        assertNull(delay.nextChangeAt)
        // A basket at 10 s: the first score stays until the delay after it arrived.
        val basket = live(53, 48, "Q2 4:40")
        assertEquals(50, delay.apply(mapOf(key to basket), delayMs, t0 + 10_000).getValue(key).homeScore)
        assertEquals(t0 + 10_000 + delayMs, delay.nextChangeAt)
        assertEquals(50, delay.apply(mapOf(key to basket), delayMs, t0 + 10_000 + delayMs - 1).getValue(key).homeScore)
        assertEquals(53, delay.apply(mapOf(key to basket), delayMs, t0 + 10_000 + delayMs).getValue(key).homeScore)
    }

    @Test
    fun `a final first seen that may have just ended shows at once`() {
        val shown = ScoreDelay().apply(mapOf(key to final(110, 100)), delayMs, start + 3 * 3_600_000L).getValue(key)
        assertEquals(110, shown.homeScore)
        assertEquals(ScoreState.POST, shown.state)
    }

    @Test
    fun `the game being watched first seen live keeps its score withheld for the delay`() {
        val delay = ScoreDelay()
        val t0 = start + 3_600_000L
        val watched = setOf(base.eventId)
        val first = delay.apply(mapOf(key to live(50, 48)), delayMs, t0, holdFirstSeen = watched).getValue(key)
        assertEquals(ScoreState.IN, first.state)
        assertNull(first.homeScore)
        assertNull(first.clockText)
        assertEquals(t0 + delayMs, delay.nextChangeAt)
        assertNull(delay.apply(mapOf(key to live(50, 48)), delayMs, t0 + delayMs - 1, watched).getValue(key).homeScore)
        assertEquals(50, delay.apply(mapOf(key to live(50, 48)), delayMs, t0 + delayMs, watched).getValue(key).homeScore)
    }

    @Test
    fun `the game being watched first seen as a final that may have just ended is withheld too`() {
        val delay = ScoreDelay()
        val t0 = start + 3 * 3_600_000L
        val watched = setOf(base.eventId)
        assertNull(delay.apply(mapOf(key to final(110, 100)), delayMs, t0, watched).getValue(key).homeScore)
        assertEquals(110, delay.apply(mapOf(key to final(110, 100)), delayMs, t0 + delayMs, watched).getValue(key).homeScore)
        // Long over: as it is.
        val longOver = final(110, 100).copy(startMs = t0 - 20 * 3_600_000L)
        assertEquals(110, ScoreDelay().apply(mapOf(key to longOver), delayMs, t0, watched).getValue(key).homeScore)
    }

    @Test
    fun `a game shown at once and then played within the delay is withheld until a delayed version is known`() {
        val delay = ScoreDelay()
        val t0 = start + 3_600_000L
        assertEquals(50, delay.apply(mapOf(key to live(50, 48)), delayMs, t0).getValue(key).homeScore)
        // Played 20 s later: nothing of it is known from the delay ago, so its score waits (today's full hold).
        val watched = setOf(base.eventId)
        assertNull(delay.apply(mapOf(key to live(50, 48)), delayMs, t0 + 20_000, watched).getValue(key).homeScore)
        assertEquals(t0 + delayMs, delay.nextChangeAt)
        assertEquals(50, delay.apply(mapOf(key to live(50, 48)), delayMs, t0 + delayMs, watched).getValue(key).homeScore)
    }

    @Test
    fun `a game first seen before play is never withheld, watched or not`() {
        val pre = base.copy(state = ScoreState.PRE, isFinal = false, clockText = "7:00 PM", home = base.home.copy(score = null), away = base.away.copy(score = null))
        val shown = ScoreDelay().apply(mapOf(key to pre), delayMs, start - 60_000L, setOf(base.eventId)).getValue(key)
        assertEquals("7:00 PM", shown.clockText)
    }

    @Test
    fun `a game zapped off within the delay stays withheld until a delay-old version exists`() {
        val delay = ScoreDelay()
        val t0 = start + 3_600_000L
        val watched = setOf(base.eventId)
        // Played from the first sight, zapped off 20 s later: the picture seen ran a delay behind.
        assertNull(delay.apply(mapOf(key to live(50, 48)), delayMs, t0, watched).getValue(key).homeScore)
        val off = delay.apply(mapOf(key to live(50, 48)), delayMs, t0 + 20_000).getValue(key)
        assertNull(off.homeScore)
        assertNull(off.clockText)
        assertNull(delay.apply(mapOf(key to live(50, 48)), delayMs, t0 + delayMs - 1).getValue(key).homeScore)
        // A delay-old version now exists: it shows.
        assertEquals(50, delay.apply(mapOf(key to live(50, 48)), delayMs, t0 + delayMs).getValue(key).homeScore)
    }

    @Test
    fun `a record and form updated at the final wait the delay with the score`() {
        val delay = ScoreDelay()
        val t0 = start + 3_600_000L
        val before = live(100, 98, "Q4 0:30").let {
            it.copy(home = it.home.copy(record = "10-4", form = "WWLDW"), away = it.away.copy(record = "9-5", form = "LLWWD"))
        }
        delay.apply(mapOf(key to before), delayMs, t0)
        // The final arrives with both records and forms already counting it: they must not tell the result early.
        val ended = final(102, 98).let {
            it.copy(home = it.home.copy(record = "11-4", form = "WLDWW"), away = it.away.copy(record = "9-6", form = "LWWDL"))
        }
        val early = delay.apply(mapOf(key to ended), delayMs, t0 + 10_000).getValue(key)
        assertEquals(ScoreState.IN, early.state)
        assertEquals("10-4", early.home.record)
        assertEquals("9-5", early.away.record)
        assertEquals("WWLDW", early.home.form)
        assertEquals("LLWWD", early.away.form)
        val due = delay.apply(mapOf(key to ended), delayMs, t0 + 10_000 + delayMs).getValue(key)
        assertEquals("11-4", due.home.record)
        assertEquals("WLDWW", due.home.form)
    }

    @Test
    fun `the game being watched first seen as a final that may have just ended withholds its records and form`() {
        val ended = final(110, 100).let { it.copy(home = it.home.copy(record = "11-4", form = "WWWLW"), away = it.away.copy(record = "9-6")) }
        val shown = ScoreDelay().apply(mapOf(key to ended), delayMs, start + 3 * 3_600_000L, setOf(base.eventId)).getValue(key)
        assertNull(shown.home.record)
        assertNull(shown.away.record)
        assertNull(shown.home.form)
    }

    private fun tennis(games: Int, state: ScoreState = ScoreState.IN, winner: Boolean = false) = base.copy(
        state = state, isFinal = state == ScoreState.POST, clockText = if (state == ScoreState.POST) "Final" else "Set 2",
        home = base.home.copy(score = null), away = base.away.copy(score = null),
        individual = TennisMatch(
            title = "Final",
            players = listOf(
                TennisPlayer("Player A", winner = winner, sets = listOf(TennisSet(6, won = true), TennisSet(games)), serving = true, setsWon = if (winner) 2 else 1),
                TennisPlayer("Player B", winner = false, sets = listOf(TennisSet(4), TennisSet(3)))
            ),
            ending = TennisEnding.COMPLETED,
            live = true
        )
    )

    private fun ScoreSnapshot.firstSets(): List<TennisSet>? = (individual as? TennisMatch)?.players?.first()?.sets

    @Test
    fun `a tennis match's games and sets wait the delay like a score`() {
        val delay = ScoreDelay()
        val t0 = start + 3_600_000L
        assertEquals(3, delay.apply(mapOf(key to tennis(3)), delayMs, t0).getValue(key).firstSets()?.get(1)?.games)
        // A game won 10 s later: the set score shown stays the delayed one.
        assertEquals(3, delay.apply(mapOf(key to tennis(4)), delayMs, t0 + 10_000).getValue(key).firstSets()?.get(1)?.games)
        assertEquals(t0 + 10_000 + delayMs, delay.nextChangeAt)
        assertEquals(4, delay.apply(mapOf(key to tennis(4)), delayMs, t0 + 10_000 + delayMs).getValue(key).firstSets()?.get(1)?.games)
    }

    @Test
    fun `a tennis match that ends waits the delay before its winner shows`() {
        val delay = ScoreDelay()
        val t0 = start + 3_600_000L
        delay.apply(mapOf(key to tennis(5)), delayMs, t0)
        val early = delay.apply(mapOf(key to tennis(6, ScoreState.POST, winner = true)), delayMs, t0 + 10_000).getValue(key)
        assertEquals(ScoreState.IN, early.state)
        assertNull((early.individual as TennisMatch).winner)
    }

    @Test
    fun `a tennis match being watched that ended retired is held with no ending`() {
        // T-1 (F4): how a match ended tells its result.
        val retired = tennis(4, ScoreState.POST, winner = true).let {
            it.copy(individual = (it.individual as TennisMatch).copy(ending = TennisEnding.RETIRED))
        }
        val shown = ScoreDelay().apply(mapOf(key to retired), delayMs, start + 3_600_000L, setOf(base.eventId)).getValue(key)
        assertEquals(TennisEnding.COMPLETED, (shown.individual as TennisMatch).ending)
    }

    @Test
    fun `a fight, a race and a golf result held back show no winner, podium or leaders`() {
        // S8: every individual sport, ready for when they are matched to cards.
        val fight = base.copy(individual = Fight("Main card", listOf(Fighter("A", winner = true), Fighter("B", winner = false)), FightMethod.KO_TKO, 2, "3:14", 0))
        val heldFight = fight.withResultWithheld().individual as Fight
        assertTrue(heldFight.fighters.none { it.winner })
        assertNull(heldFight.method)
        assertNull(heldFight.round)
        assertNull(heldFight.clock)
        val race = base.copy(individual = RaceResult("Grand Prix", listOf(RaceFinisher(1, "Driver A", "DRA"))))
        assertTrue((race.withResultWithheld().individual as RaceResult).podium.isEmpty())
        val golf = base.copy(individual = GolfResult("Open", listOf(GolfLeader(1, false, "Player A", "-12"))))
        assertTrue((golf.withResultWithheld().individual as GolfResult).leaders.isEmpty())
    }

    @Test
    fun `a tennis match being watched that ended by walkover is held with no ending`() {
        // S9: the walkover too, and the watched game's mask is the same.
        val walkover = tennis(0, ScoreState.POST, winner = true).let {
            it.copy(individual = (it.individual as TennisMatch).copy(ending = TennisEnding.WALKOVER))
        }
        assertEquals(TennisEnding.COMPLETED, (ScoreWatch.masked(walkover).individual as TennisMatch).ending)
        val shown = ScoreDelay().apply(mapOf(key to walkover), delayMs, start + 3_600_000L, setOf(base.eventId)).getValue(key)
        assertEquals(TennisEnding.COMPLETED, (shown.individual as TennisMatch).ending)
    }

    @Test
    fun `the tennis match being watched first seen live withholds its sets`() {
        val shown = ScoreDelay().apply(mapOf(key to tennis(3)), delayMs, start + 3_600_000L, setOf(base.eventId)).getValue(key)
        val match = shown.individual as TennisMatch
        assertEquals(listOf(true, true), match.players.map { it.sets.isEmpty() })
        assertEquals(listOf(0, 0), match.players.map { it.setsWon })
        assertNull(match.winner)
        assertEquals(listOf(false, false), match.players.map { it.serving })
    }
}
