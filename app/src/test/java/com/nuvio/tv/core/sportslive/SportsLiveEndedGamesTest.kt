package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.ScoreDelay
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsLiveEndedGamesTest {

    private val nba = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA)
    // IND @ PHI: home 76ers 131, away Pacers 124, Final; re-dated to START here.
    private val pacersFinal = nba.first { it.eventId == "401909985" }.copy(startMs = START)

    private fun live(event: ScoreSnapshot) = event.copy(
        state = ScoreState.IN, isFinal = false, clockText = "Q4 2:00",
        home = event.home.copy(score = 120), away = event.away.copy(score = 118)
    )

    private fun item(
        name: String = "Indiana Pacers at Philadelphia 76ers",
        sport: String? = "Basketball",
        startMs: Long? = START,
        timed: Boolean = true,
        isChannel: Boolean = false,
        id: String = name
    ) = SportsLiveItem(
        meta = MetaPreview(
            id = id, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = listOfNotNull(sport)
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
        kind = if (isChannel) SportsLiveKind.CHANNELS else SportsLiveKind.EVENTS, isLive = true, isChannel = isChannel,
        startMs = startMs, startHasTimeOfDay = timed, sport = sport,
        teams = if (isChannel) SportsLiveTeams.parseBracketed(name) else SportsLiveTeams.parse(name)
    )

    // --- ESPN final ---

    @Test
    fun `an ESPN final moves the card after the 10-minute grace, and a live game never moves`() {
        val games = SportsLiveEndedGames()
        val card = item()
        val inPlay = START + 2 * HOUR
        assertNull(games.verdict(card, live(pacersFinal), inPlay))
        val endedAt = START + 2 * HOUR + 20 * MINUTE
        val verdict = games.verdict(card, pacersFinal, endedAt)!!
        assertEquals(EndedSignal.FINAL, verdict.signal)
        assertEquals(endedAt, verdict.endedAtMs)
        assertEquals(pacersFinal, verdict.event)
        // In the grace: still in the main list.
        assertEquals(listOf(card), games.split(listOf(card), mapOf(card.streamKey to pacersFinal), endedAt + 9 * MINUTE).live)
        val moved = games.split(listOf(card), mapOf(card.streamKey to pacersFinal), endedAt + 10 * MINUTE)
        assertEquals(listOf(card), moved.justEnded)
        assertTrue(moved.live.isEmpty())
        assertEquals(verdict, moved.ended[card.streamKey])
    }

    @Test
    fun `a game ESPN shows live never moves, however long past its estimated length`() {
        val games = SportsLiveEndedGames()
        val card = item()
        val overtime = START + 5 * HOUR
        val split = games.split(listOf(card), mapOf(card.streamKey to live(pacersFinal)), overtime)
        assertEquals(listOf(card), split.live)
        assertTrue(split.ended.isEmpty())
    }

    @Test
    fun `the score delay holds the move back until the delayed snapshot says final`() {
        val delay = ScoreDelay()
        val games = SportsLiveEndedGames()
        val card = item()
        val key = card.streamKey
        val delayMs = 60_000L
        val t0 = START + HOUR
        // Seen live first (withheld for the delay), then final at t1.
        games.verdict(card, delay.apply(mapOf(key to live(pacersFinal)), delayMs, t0).getValue(key), t0)
        games.verdict(card, delay.apply(mapOf(key to live(pacersFinal)), delayMs, t0 + delayMs).getValue(key), t0 + delayMs)
        val t1 = START + 2 * HOUR
        val atFinal = delay.apply(mapOf(key to pacersFinal), delayMs, t1).getValue(key)
        assertEquals(ScoreState.IN, atFinal.state)
        assertNull(games.verdict(card, atFinal, t1))
        val released = delay.apply(mapOf(key to pacersFinal), delayMs, t1 + delayMs).getValue(key)
        val verdict = games.verdict(card, released, t1 + delayMs)!!
        assertEquals(t1 + delayMs, verdict.endedAtMs)
        assertEquals(t1 + delayMs + SportsLiveEndedGames.GRACE_MS, verdict.movesAtMs)
    }

    @Test
    fun `a final the delay still withholds (no score yet) does not count`() {
        val withheld = pacersFinal.copy(home = pacersFinal.home.copy(score = null), away = pacersFinal.away.copy(score = null))
        assertNull(SportsLiveEndedGames().verdict(item(), withheld, START + 3 * HOUR))
    }

    @Test
    fun `postponed and cancelled games never count as ended, nor get an estimate`() {
        val postponed = pacersFinal.copy(state = ScoreState.OFF, isFinal = false, clockText = "Postponed")
        assertNull(SportsLiveEndedGames().verdict(item(), postponed, START + 8 * HOUR))
    }

    @Test
    fun `a final first seen without the game seen in play is dated no later than its estimated end`() {
        val games = SportsLiveEndedGames()
        val card = item()
        // App opened long after: dated at start + 3 h (basketball), so it is gone, not given a fresh half hour.
        val verdict = games.verdict(card, pacersFinal, START + 5 * HOUR)!!
        assertEquals(START + SportsLiveGameLength.BASKETBALL_MS, verdict.endedAtMs)
        val split = games.split(listOf(card), mapOf(card.streamKey to pacersFinal), START + 5 * HOUR)
        assertTrue(split.live.isEmpty() && split.justEnded.isEmpty())
        // An early final (before the estimate) is dated when seen.
        assertEquals(START + 2 * HOUR, SportsLiveEndedGames().verdict(item(), pacersFinal, START + 2 * HOUR)!!.endedAtMs)
    }

    @Test
    fun `the final is remembered once the card is no longer tracked, and ESPN going live again clears it`() {
        val games = SportsLiveEndedGames()
        val card = item()
        games.verdict(card, live(pacersFinal), START + HOUR)
        val endedAt = START + 2 * HOUR
        games.verdict(card, pacersFinal, endedAt)
        // No snapshot now (untracked, Scores Off): still the ESPN final, not the estimate.
        val remembered = games.verdict(card, null, endedAt + 15 * MINUTE)!!
        assertEquals(EndedSignal.FINAL, remembered.signal)
        assertEquals(endedAt, remembered.endedAtMs)
        assertEquals(pacersFinal, remembered.event)
        assertNull(games.verdict(card, live(pacersFinal), endedAt + 16 * MINUTE))
    }

    @Test
    fun `channels are never moved, even on an ESPN final or long past their start`() {
        val games = SportsLiveEndedGames()
        val channel = item(name = "US| ESPN [Pacers vs 76ers]", isChannel = true)
        assertNull(games.verdict(channel, pacersFinal, START + 2 * HOUR))
        assertNull(games.verdict(channel, null, START + 10 * HOUR))
        assertEquals(listOf(channel), games.split(listOf(channel), mapOf(channel.streamKey to pacersFinal), START + 10 * HOUR).live)
    }

    // --- time estimate ---

    @Test
    fun `the estimate applies only to unmatched cards with a timed start`() {
        val games = SportsLiveEndedGames()
        val past = START + 4 * HOUR
        val verdict = games.verdict(item(), null, past)!!
        assertEquals(EndedSignal.ESTIMATE, verdict.signal)
        assertEquals(START + SportsLiveGameLength.BASKETBALL_MS, verdict.endedAtMs)
        assertEquals(verdict.endedAtMs, verdict.movesAtMs)
        assertNull(verdict.event)
        assertNull(games.verdict(item(), null, START + 2 * HOUR))
        assertNull(games.verdict(item(startMs = null), null, past))
        assertNull(games.verdict(item(timed = false), null, past))
    }

    @Test
    fun `a strict match ESPN shows live overrides the estimate`() {
        val games = SportsLiveEndedGames()
        assertNull(games.verdict(item(), live(pacersFinal), START + 4 * HOUR))
        assertNull(games.verdict(item(), live(pacersFinal).copy(state = ScoreState.PRE), START + 4 * HOUR))
    }

    @Test
    fun `long-session sports get no estimate`() {
        assertNull(SportsLiveEndedGames().verdict(item(name = "Sinner vs Alcaraz", sport = "Tennis"), null, START + 7 * HOUR))
        assertNull(SportsLiveEndedGames().verdict(item(name = "Jones vs Miocic", sport = "UFC"), null, START + 7 * HOUR))
    }

    @Test
    fun `a merged card counts from its latest timed start`() {
        val primary = item(id = "p", startMs = START)
        val merged = primary.copy(alternates = listOf(item(id = "alt", startMs = START + 20 * MINUTE)))
        val verdict = SportsLiveEndedGames().verdict(merged, null, START + 4 * HOUR)!!
        assertEquals(START + 20 * MINUTE + SportsLiveGameLength.BASKETBALL_MS, verdict.endedAtMs)
    }

    // --- dropping ---

    @Test
    fun `ended cards drop out of Live now 30 minutes after they ended`() {
        val games = SportsLiveEndedGames()
        val card = item()
        val end = START + SportsLiveGameLength.BASKETBALL_MS
        assertEquals(listOf(card), games.split(listOf(card), emptyMap(), end + 29 * MINUTE).justEnded)
        val gone = games.split(listOf(card), emptyMap(), end + 30 * MINUTE)
        assertTrue(gone.live.isEmpty() && gone.justEnded.isEmpty() && gone.ended.isEmpty())
        // Unless links were found for it lately: an estimate can be wrong, so it stays in the strip.
        assertEquals(listOf(card), games.split(listOf(card), emptyMap(), end + 30 * MINUTE, keepLinked = { true }).justEnded)
    }

    @Test
    fun `the split keeps list order, live first`() {
        val games = SportsLiveEndedGames()
        val ended = item(name = "Ended A vs B", id = "e", startMs = START)
        val running = item(name = "Running C vs D", id = "r", startMs = START + 3 * HOUR)
        val split = games.split(listOf(ended, running), emptyMap(), START + 3 * HOUR + 10 * MINUTE)
        assertEquals(listOf(running), split.live)
        assertEquals(listOf(ended), split.justEnded)
        assertEquals(setOf(ended.streamKey), games.endedKeys(listOf(ended, running), emptyMap(), START + 3 * HOUR + 10 * MINUTE))
    }

    // --- lengths ---

    @Test
    fun `the length table`() {
        fun length(name: String, sport: String?) = SportsLiveGameLength.of(item(name = name, sport = sport))
        assertEquals(SportsLiveGameLength.SOCCER_MS, length("Arsenal vs Chelsea", "Soccer"))
        assertEquals(SportsLiveGameLength.SOCCER_MS, length("Arsenal vs Chelsea", "Premier League"))
        assertEquals(SportsLiveGameLength.BASKETBALL_MS, length("Lakers vs Celtics", "NBA"))
        assertEquals(SportsLiveGameLength.FOOTBALL_MS, length("Jets vs Patriots", "NFL"))
        assertEquals(SportsLiveGameLength.HOCKEY_MS, length("Rangers vs Bruins", "NHL"))
        assertEquals(SportsLiveGameLength.BASEBALL_MS, length("Yankees vs Red Sox", "MLB"))
        assertEquals(SportsLiveGameLength.UNKNOWN_MS, length("Leinster vs Munster", "Rugby"))
        assertEquals(SportsLiveGameLength.UNKNOWN_MS, length("Team A vs Team B", null))
        // "Football" alone may be American football: the longer length.
        assertEquals(SportsLiveGameLength.FOOTBALL_MS, length("Arsenal vs Chelsea", "Football"))
        assertNull(length("Sinner vs Alcaraz", "Tennis"))
        assertNull(length("India vs Australia", "Cricket"))
    }

    @Test
    fun `replayFor finds a strict replay match of an ended game`() {
        val replay = item(name = "Indiana Pacers vs Philadelphia 76ers", id = "rp", startMs = START).copy(kind = SportsLiveKind.REPLAY, isLive = false)
        assertNotNull(SportsLiveEndedGames.replayFor(pacersFinal, listOf(replay), START + 4 * HOUR))
        assertNull(SportsLiveEndedGames.replayFor(pacersFinal, emptyList(), START + 4 * HOUR))
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        // 2026-11-16T00:00Z: 19:00 US Eastern on 2026-11-15.
        const val START = 1_794_787_200_000L
    }
}
