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
import com.nuvio.tv.ui.screens.home.HomeUiState
import com.nuvio.tv.ui.screens.home.sportsLiveHomeLiveGames
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
    fun `Home's final keys take an ESPN final at once, in the grace too, never a live game or an estimate`() {
        val games = SportsLiveEndedGames()
        val card = item()
        val overdue = item(name = "Team A vs Team B", sport = "Soccer", id = "o", startMs = START - 10 * HOUR)
        val end = START + 2 * HOUR
        // A cold start from the kept scores: the final is first seen with no play seen before.
        assertEquals(setOf(card.streamKey), games.finalKeys(listOf(card, overdue), mapOf(card.streamKey to pacersFinal), end))
        // Seen in play, then final: in the section's grace, already out of Home's Live now row.
        val watched = SportsLiveEndedGames()
        watched.verdict(card, live(pacersFinal), START + HOUR)
        val finals = mapOf(card.streamKey to pacersFinal)
        assertTrue(watched.confirmedEndedKeys(listOf(card), finals, end + MINUTE).isEmpty())
        assertEquals(setOf(card.streamKey), watched.finalKeys(listOf(card), finals, end + MINUTE))
        val running = SportsLiveEndedGames()
        assertTrue(running.finalKeys(listOf(card), mapOf(card.streamKey to live(pacersFinal)), end).isEmpty())
        assertTrue(running.finalKeys(listOf(overdue), emptyMap(), START + 8 * HOUR).isEmpty())
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
        // Seen live first (shown at once), then final at t1.
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
        assertEquals(START + BASKETBALL, verdict.endedAtMs)
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
        assertEquals(START + BASKETBALL, verdict.endedAtMs)
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
        assertEquals(START + 20 * MINUTE + BASKETBALL, verdict.endedAtMs)
    }

    // --- dropping ---

    @Test
    fun `ESPN finals drop out of Live now 30 minutes after they ended`() {
        val games = SportsLiveEndedGames()
        val card = item()
        val scores = mapOf(card.streamKey to pacersFinal)
        games.verdict(card, live(pacersFinal), START + HOUR)
        val end = START + 2 * HOUR
        games.verdict(card, pacersFinal, end)
        assertEquals(listOf(card), games.split(listOf(card), scores, end + 29 * MINUTE).justEnded)
        val gone = games.split(listOf(card), scores, end + 30 * MINUTE)
        assertTrue(gone.live.isEmpty() && gone.justEnded.isEmpty() && gone.ended.isEmpty())
    }

    @Test
    fun `an estimate never hides a game - it stays in the strip until six hours past its estimated end`() {
        val games = SportsLiveEndedGames()
        val card = item()
        val end = START + BASKETBALL
        assertEquals(listOf(card), games.split(listOf(card), emptyMap(), end + 30 * MINUTE).justEnded)
        assertEquals(listOf(card), games.split(listOf(card), emptyMap(), end + 6 * HOUR - 1).justEnded)
        assertTrue(games.split(listOf(card), emptyMap(), end + 6 * HOUR).justEnded.isEmpty())
    }

    @Test
    fun `a card ESPN saw in play is never estimated, even once it is no longer tracked`() {
        val games = SportsLiveEndedGames()
        val card = item()
        games.verdict(card, live(pacersFinal), START + 2 * HOUR)
        assertNull(games.verdict(card, null, START + 6 * HOUR))
    }

    @Test
    fun `no estimate while the gate holds a coverable card (its feed has not answered yet)`() {
        val games = SportsLiveEndedGames()
        val nbaCard = item()
        val rugby = item(name = "Leinster vs Munster", sport = "Rugby")
        val past = START + 5 * HOUR
        assertNull(games.verdict(nbaCard, null, past, canEstimate = false))
        val waiting = SportsLiveEndedGames.estimateGate(scoresOff = false, unmatched = emptySet())
        assertTrue(!waiting(nbaCard))
        // No feed covers rugby: estimated at once.
        assertTrue(waiting(rugby))
        assertTrue(SportsLiveEndedGames.estimateGate(scoresOff = false, unmatched = setOf(nbaCard.streamKey))(nbaCard))
        assertTrue(SportsLiveEndedGames.estimateGate(scoresOff = true, unmatched = emptySet())(nbaCard))
        val split = games.split(listOf(nbaCard, rugby), emptyMap(), past, waiting)
        assertEquals(listOf(nbaCard), split.live)
        assertEquals(listOf(rugby), split.justEnded)
    }

    @Test
    fun `Home drops only ESPN-confirmed finals past the grace, never an estimate, live games or channels`() {
        val games = SportsLiveEndedGames()
        val card = item()
        val overdue = item(name = "Team A vs Team B", sport = "Soccer", id = "o", startMs = START - 10 * HOUR)
        // No score data (Hide until focused, Off): nothing is dropped, however overdue.
        assertTrue(games.confirmedEndedKeys(listOf(card, overdue), emptyMap(), START + 8 * HOUR).isEmpty())
        games.verdict(card, live(pacersFinal), START + HOUR)
        val end = START + 2 * HOUR
        val scores = mapOf(card.streamKey to pacersFinal)
        games.verdict(card, pacersFinal, end)
        assertTrue(games.confirmedEndedKeys(listOf(card), scores, end + 9 * MINUTE).isEmpty())
        assertEquals(setOf(card.streamKey), games.confirmedEndedKeys(listOf(card, overdue), scores, end + 10 * MINUTE))

        // Home's list: the final (matched to a re-titled card) goes, live games and channels stay.
        val arsenal = item(name = "Arsenal vs Chelsea", sport = "Soccer", startMs = START - 2 * HOUR)
        val running = item(name = "Leeds vs Everton", sport = "Soccer", startMs = START - HOUR)
        val channel = item(name = "Sky Sports Main Event", sport = "Soccer", startMs = START - 6 * HOUR, isChannel = true)
        val shown = listOf(arsenal, running, channel)
        // Crystal Palace 1-3 Liverpool, re-titled for the card.
        val eplFinal = EspnFixtures.events(EspnFixtures.ENG1_20261108, EspnFixtures.ENG1).first { it.eventId == "401879243" }
        val palace = item(name = "Crystal Palace vs Liverpool", sport = "Soccer", startMs = START - 2 * HOUR)
        val home = SportsLiveEndedGames()
        home.verdict(palace, eplFinal.copy(state = ScoreState.IN, isFinal = false), START - 30 * MINUTE)
        home.verdict(palace, eplFinal, START - 15 * MINUTE)
        val homeEnded = home.confirmedEndedKeys(shown + palace, mapOf(palace.streamKey to eplFinal), START)
        assertEquals(setOf(palace.streamKey), homeEnded)
        assertEquals(shown, HomeUiState(liveNowItems = shown + palace, liveNowEnded = homeEnded).liveNowShown)
        assertEquals(shown + palace, HomeUiState(liveNowItems = shown + palace).liveNowShown)
        // With no score data (Hide until focused or Off) Home drops neither an overdue nor an untimed game.
        val quiet = listOf(
            item(name = "Arsenal vs Chelsea", sport = "Soccer", startMs = START - 8 * HOUR),
            item(name = "Ajax vs PSV", sport = "Soccer", startMs = null, timed = false)
        )
        val quietEnded = SportsLiveEndedGames().confirmedEndedKeys(quiet, emptyMap(), START)
        assertTrue(quietEnded.isEmpty())
        assertEquals(quiet, HomeUiState(liveNowItems = quiet, liveNowEnded = quietEnded).liveNowShown)
    }

    @Test
    fun `the split keeps list order, live first`() {
        val games = SportsLiveEndedGames()
        val ended = item(name = "Ended A vs B", id = "e", startMs = START)
        val running = item(name = "Running C vs D", id = "r", startMs = START + 3 * HOUR)
        val split = games.split(listOf(ended, running), emptyMap(), START + 3 * HOUR + 30 * MINUTE)
        assertEquals(listOf(running), split.live)
        assertEquals(listOf(ended), split.justEnded)
    }

    // --- lengths ---

    @Test
    fun `the length table`() {
        fun length(name: String, sport: String?) = SportsLiveGameLength.of(item(name = name, sport = sport))?.minus(LATE)
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
        assertNull(length("24 Hours of Le Mans", "Motorsport"))
        assertNull(length("Le Mans", null))
        assertNull(length("Tour de France Stage 4", null))
        assertEquals(175 * MINUTE, SportsLiveGameLength.SOCCER_MS)
        assertEquals(270 * MINUTE, SportsLiveGameLength.BASEBALL_MS)
        assertEquals(20 * MINUTE, LATE)
    }

    // --- C3: finals after a restart, persisted times, never LIVE ---

    @Test
    fun `after a restart a long-over final is not live - it is already gone from Live now`() {
        // A fresh instance (nothing remembered) first sees the final 5 h after tip-off: dated at
        // start + length, so its grace and its half hour have both run out.
        val games = SportsLiveEndedGames()
        val now = START + 5 * HOUR
        val verdict = games.verdict(item(), pacersFinal, now)!!
        assertEquals(START + BASKETBALL, verdict.endedAtMs)
        val split = games.split(listOf(item()), mapOf(item().streamKey to pacersFinal), now)
        assertTrue(split.live.isEmpty())
        assertTrue(split.justEnded.isEmpty())
    }

    @Test
    fun `after a restart a final older than the grace goes straight to Just ended`() {
        val now = START + BASKETBALL + 15 * MINUTE
        val split = SportsLiveEndedGames().split(listOf(item()), mapOf(item().streamKey to pacersFinal), now)
        assertTrue(split.live.isEmpty())
        assertEquals(listOf(item()), split.justEnded)
    }

    @Test
    fun `a final with no start is dated now with no grace - straight to Just ended, never live`() {
        val card = item(startMs = null, timed = false)
        val final = pacersFinal.copy(startMs = null)
        val now = START + 3 * HOUR
        val verdict = SportsLiveEndedGames().verdict(card, final, now)!!
        assertEquals(now, verdict.endedAtMs)
        assertEquals(false, verdict.graced)
        assertEquals(now, verdict.movesAtMs)
        val split = SportsLiveEndedGames().split(listOf(card), mapOf(card.streamKey to final), now)
        assertTrue(split.live.isEmpty())
        assertEquals(listOf(card), split.justEnded)
    }

    @Test
    fun `a sport with no length has an unknown end too - no grace`() {
        val card = item(name = "Indiana Pacers at Philadelphia 76ers", sport = "Basketball UFC")
        val verdict = SportsLiveEndedGames().verdict(card, pacersFinal, START + 3 * HOUR)!!
        assertEquals(false, verdict.graced)
    }

    @Test
    fun `final times are kept across a restart, so the timers never reset`() {
        val card = item()
        val saved = ArrayList<Map<String, SportsLiveEndedGames.FinalTime>>()
        val before = SportsLiveEndedGames()
        before.install(emptyMap(), START) { saved += it }
        // Seen in play, then final: dated when first seen.
        before.verdict(card, live(pacersFinal), START + 2 * HOUR)
        val endedAt = START + 2 * HOUR + 5 * MINUTE
        before.verdict(card, pacersFinal, endedAt)
        val stored = SportsLiveEndedGames.encodeTimes(saved.last())

        // Restart 8 min later: the stored time stands, so 2 min of grace are left, not a fresh 10.
        val after = SportsLiveEndedGames()
        after.install(SportsLiveEndedGames.decodeTimes(stored), endedAt + 8 * MINUTE) { }
        val verdict = after.verdict(card, pacersFinal, endedAt + 8 * MINUTE)!!
        assertEquals(endedAt, verdict.endedAtMs)
        assertEquals(endedAt + SportsLiveEndedGames.GRACE_MS, verdict.movesAtMs)

        // Times older than 6 h are not restored; unreadable lines are skipped.
        val old = SportsLiveEndedGames.decodeTimes(stored + "\nnot a line\n1,x,k")
        assertEquals(setOf(card.streamKey), old.keys)
        val late = SportsLiveEndedGames()
        val saw = ArrayList<Map<String, SportsLiveEndedGames.FinalTime>>()
        late.install(old, endedAt + SportsLiveEndedGames.FORGET_AFTER_MS + MINUTE) { saw += it }
        assertTrue(saw.last().isEmpty())
    }

    @Test
    fun `a final seen before a restart stands before any score comes in - a cold open never shows it live`() {
        // #267: the visit's first list is built before the score feeds answer; a coverable card is not estimated meanwhile.
        val card = item()
        val saved = ArrayList<Map<String, SportsLiveEndedGames.FinalTime>>()
        val before = SportsLiveEndedGames()
        before.install(emptyMap(), START) { saved += it }
        before.verdict(card, live(pacersFinal), START + 2 * HOUR)
        val endedAt = START + 2 * HOUR + 5 * MINUTE
        before.verdict(card, pacersFinal, endedAt)

        val after = SportsLiveEndedGames()
        after.install(saved.last(), endedAt + 12 * MINUTE) { }
        val verdict = after.verdict(card, null, endedAt + 12 * MINUTE, canEstimate = false)!!
        assertEquals(EndedSignal.FINAL, verdict.signal)
        assertEquals(endedAt, verdict.endedAtMs)
        // No score to show: nothing to spoil.
        assertNull(verdict.event)
        val split = after.split(listOf(card), emptyMap(), endedAt + 12 * MINUTE) { false }
        assertTrue(split.live.isEmpty())
        assertEquals(listOf(card), split.justEnded)
        // Home leaves it out too, in its grace already.
        assertEquals(setOf(card.streamKey), after.finalKeys(listOf(card), emptyMap(), endedAt + 2 * MINUTE))
    }

    @Test
    fun `F1 - a remembered final inside its grace goes straight to Just ended, never back to the live list`() {
        val card = item()
        val endedAt = START + 2 * HOUR
        val after = SportsLiveEndedGames()
        after.install(mapOf(card.streamKey to SportsLiveEndedGames.FinalTime(endedAt, eventId = pacersFinal.eventId)), endedAt + 2 * MINUTE) { }
        val split = after.split(listOf(card), emptyMap(), endedAt + 2 * MINUTE) { false }
        assertTrue(split.live.isEmpty())
        assertEquals(listOf(card), split.justEnded)
        val status = GameStatus.of(card, null, split.ended[card.streamKey], endedAt + 2 * MINUTE)
        assertTrue(status is GameStatus.JustEnded)
        // Nothing to link-check: it is over.
        assertNull(after.linkCheckEnd(card, null, endedAt + 4 * HOUR))
    }

    @Test
    fun `F3 - a remembered final never applies to a card with no timed start, which it would hide untracked`() {
        val endedAt = START + 2 * HOUR
        val games = SportsLiveEndedGames()
        games.install(mapOf(item().streamKey to SportsLiveEndedGames.FinalTime(endedAt, eventId = pacersFinal.eventId)), endedAt + HOUR) { }
        val untimed = item(startMs = null, timed = false)
        assertNull(games.verdict(untimed, null, endedAt + HOUR, canEstimate = false))
        val split = games.split(listOf(untimed), emptyMap(), endedAt + HOUR) { false }
        assertEquals(listOf(untimed), split.live)
        assertNull(games.verdict(item(timed = false), null, endedAt + HOUR, canEstimate = false))
    }

    @Test
    fun `F1 - ESPN showing the game scheduled clears a remembered final too`() {
        val card = item()
        val endedAt = START + 2 * HOUR
        val games = SportsLiveEndedGames()
        games.install(mapOf(card.streamKey to SportsLiveEndedGames.FinalTime(endedAt, eventId = pacersFinal.eventId)), endedAt) { }
        assertNull(games.verdict(card, live(pacersFinal).copy(state = ScoreState.PRE), endedAt + MINUTE))
        assertNull(games.verdict(card, null, endedAt + 2 * MINUTE, canEstimate = false))
    }

    @Test
    fun `a final remembered from a restart never ends a later game under the same card key, and ESPN in play clears it`() {
        val endedAt = START + 2 * HOUR
        val stored = mapOf(item().streamKey to SportsLiveEndedGames.FinalTime(endedAt, eventId = pacersFinal.eventId))
        // An addon reusing its id for a game that starts after that final: not this final.
        val reused = SportsLiveEndedGames()
        reused.install(stored, endedAt + HOUR) { }
        assertNull(reused.verdict(item(startMs = endedAt + 30 * MINUTE), null, endedAt + HOUR, canEstimate = false))
        // ESPN shows the card's game in play: the remembered final goes for good.
        val games = SportsLiveEndedGames()
        games.install(stored, endedAt + 10 * MINUTE) { }
        assertNull(games.verdict(item(), live(pacersFinal), endedAt + 10 * MINUTE))
        assertNull(games.verdict(item(), null, endedAt + 11 * MINUTE, canEstimate = false))
        // Older than 6 h: forgotten.
        val late = SportsLiveEndedGames()
        late.install(stored, endedAt) { }
        assertNull(late.verdict(item(), null, endedAt + SportsLiveEndedGames.FORGET_AFTER_MS + MINUTE, canEstimate = false))
    }

    @Test
    fun `Live now's further end rules are one shared rule set the panel takes too (#354)`() {
        val games = SportsLiveEndedGames()
        // Soccer runs at most 3 h 15 live: this one started 4 h ago, no ESPN game, no recent links.
        val overdue = item(name = "Team A vs Team B", sport = "Soccer", id = "over", startMs = START - 4 * HOUR)
        // Past its estimate (4 h 05), before its longest run (4 h 30), and every lookup found no stream (#275).
        val noLinks = item(name = "New England Patriots at New York Jets", sport = "NFL", id = "nolinks", startMs = START - 4 * HOUR - 10 * MINUTE)
        val onAir = item(id = "on", startMs = START - HOUR)
        val alive = item(name = "Team C vs Team D", sport = "Soccer", id = "alive", startMs = START - 4 * HOUR)
        val live = listOf(overdue, noLinks, onAir, alive)
        val noStreams = setOf(noLinks.streamKey, onAir.streamKey)
        val more = games.moreEnds(live, emptyMap(), START, noStreams) { it.id == "alive" }
        assertEquals(setOf(overdue.streamKey, noLinks.streamKey), more.moved)
        assertEquals(START - 4 * HOUR + SportsLiveLiveDuration.maxLiveMs("Soccer"), more.ended.getValue(overdue.streamKey).endedAtMs)
        assertEquals(START - 4 * HOUR - 10 * MINUTE + SportsLiveGameLength.of(noLinks)!!, more.ended.getValue(noLinks.streamKey).endedAtMs)
        assertTrue(more.ended.values.all { it.signal == EndedSignal.ESTIMATE })
        // A game ESPN shows in play is never overdue.
        val playing = item(id = "p", startMs = START - 4 * HOUR)
        assertTrue(games.moreEnds(listOf(playing), mapOf(playing.streamKey to live(pacersFinal)), START, emptySet()) { false }.moved.isEmpty())

        // The split, with them: out of the live list, into the strip after its own cards.
        val split = games.split(live, emptyMap(), START) { false }.with(more)
        assertEquals(listOf(onAir, alive), split.live)
        assertEquals(listOf(overdue, noLinks), split.justEnded)
        assertEquals(setOf(overdue.streamKey, noLinks.streamKey), split.ended.keys)
    }

    @Test
    fun `F9 - a game ESPN strictly matches is never overdue, scheduled or postponed too`() {
        val games = SportsLiveEndedGames()
        val late = item(id = "late", startMs = START - 4 * HOUR)
        val scheduled = live(pacersFinal).copy(state = ScoreState.PRE)
        assertTrue(games.moreEnds(listOf(late), mapOf(late.streamKey to scheduled), START, emptySet()) { false }.moved.isEmpty())
        val postponed = live(pacersFinal).copy(state = ScoreState.OFF)
        assertTrue(games.moreEnds(listOf(late), mapOf(late.streamKey to postponed), START, emptySet()) { false }.moved.isEmpty())
        // No ESPN game: overdue as before.
        assertEquals(setOf(late.streamKey), games.moreEnds(listOf(late), emptyMap(), START, emptySet()) { false }.moved)
    }

    @Test
    fun `F8 - Up next and the dual picker leave out overdue games too, never one with links found lately`() {
        val games = SportsLiveEndedGames()
        val overdue = item(name = "Team A vs Team B", sport = "Soccer", id = "over", startMs = START - 4 * HOUR)
        val on = item(id = "on", startMs = START - HOUR)
        assertTrue(games.overSourceKeys(listOf(overdue, on), emptyMap(), START) { false }.isEmpty())
        assertEquals(setOf(overdue.streamKey), games.overSourceKeys(listOf(overdue, on), emptyMap(), START, hasRecentLinks = { false }) { false })
        assertTrue(games.overSourceKeys(listOf(overdue), emptyMap(), START, hasRecentLinks = { true }) { false }.isEmpty())
    }

    @Test
    fun `#375 - the games Home labels Probably ended are Live now's - estimates and overdue, never a final or a live game`() {
        val games = SportsLiveEndedGames()
        val estimated = item(name = "Leinster vs Munster", sport = "Rugby", id = "est", startMs = START - 5 * HOUR)
        val overdue = item(name = "Team A vs Team B", sport = "Soccer", id = "over", startMs = START - 4 * HOUR)
        val final = item(id = "fin", startMs = START - 3 * HOUR)
        val on = item(name = "Team C vs Team D", sport = "Soccer", id = "on", startMs = START - HOUR)
        val keys = games.probablyEndedKeys(
            listOf(estimated, overdue, final, on), mapOf(final.streamKey to pacersFinal), START,
            canEstimate = { it.id == "est" }, hasRecentLinks = { false }
        )
        assertEquals(setOf(estimated.streamKey, overdue.streamKey), keys)
    }

    @Test
    fun `L5 - postponed or cancelled is no live game, suspended may resume, channels never`() {
        val card = item()
        val postponed = live(pacersFinal).copy(state = ScoreState.OFF, clockText = "Postponed", mayResume = false)
        assertTrue(SportsLiveEndedGames.isPostponed(card, postponed))
        assertTrue(!SportsLiveEndedGames.isPostponed(card, postponed.copy(mayResume = true)))
        assertTrue(!SportsLiveEndedGames.isPostponed(card, live(pacersFinal)))
        assertTrue(!SportsLiveEndedGames.isPostponed(item(isChannel = true), postponed))
        assertEquals(setOf(card.streamKey), SportsLiveEndedGames.postponedKeys(listOf(card, item(id = "x", name = "Team A vs Team B")), mapOf(card.streamKey to postponed)))
    }

    @Test
    fun `#469 - Up next and the dual pickers leave out a postponed game, with every source`() {
        val card = item().copy(alternates = listOf(item(id = "alt")))
        val postponed = live(pacersFinal).copy(state = ScoreState.OFF, clockText = "Postponed", mayResume = false)
        assertEquals(setOf(card.streamKey, "a|alt"), SportsLiveEndedGames().overSourceKeys(listOf(card), mapOf(card.streamKey to postponed), START))
        // Suspended (may resume): still a live game.
        assertTrue(SportsLiveEndedGames().overSourceKeys(listOf(card), mapOf(card.streamKey to postponed.copy(mayResume = true)), START).isEmpty())
    }

    @Test
    fun `F4 - what live counts leave out - Just ended, dropped and finals in their grace`() {
        val games = SportsLiveEndedGames()
        val old = item(name = "Team A vs Team B", sport = "Soccer", id = "old", startMs = START - 12 * HOUR)
        val strip = item(name = "Team C vs Team D", sport = "Soccer", id = "strip", startMs = START - 4 * HOUR)
        val on = item(id = "on", startMs = START - HOUR)
        val all = listOf(old, strip, on)
        val split = games.split(all, emptyMap(), START) { false }.with(games.moreEnds(all, emptyMap(), START, emptySet()) { false })
        assertEquals(setOf(old.streamKey, strip.streamKey), split.overKeys(all))
        assertEquals(listOf(on), split.live)
    }

    @Test
    fun `#354 - an overdue card past its drop time is moved out but shown nowhere`() {
        val games = SportsLiveEndedGames()
        val old = item(name = "Team A vs Team B", sport = "Soccer", id = "old", startMs = START - 12 * HOUR)
        val more = games.moreEnds(listOf(old), emptyMap(), START, emptySet()) { false }
        assertEquals(setOf(old.streamKey), more.moved)
        assertTrue(more.ended.isEmpty())
        val split = games.split(listOf(old), emptyMap(), START) { false }.with(more)
        assertTrue(split.live.isEmpty() && split.justEnded.isEmpty())
    }

    @Test
    fun `a stored time is reused only for the same ESPN event and while younger than 6 h`() {
        val card = item()
        val endedAt = START + 2 * HOUR
        // Stored for another event under the same card key (an addon reusing its id the next day).
        val other = SportsLiveEndedGames()
        other.install(mapOf(card.streamKey to SportsLiveEndedGames.FinalTime(endedAt, eventId = "other")), endedAt) { }
        assertEquals(START + BASKETBALL, other.verdict(card, pacersFinal, START + 5 * HOUR)!!.endedAtMs)
        // The same event: the stored time stands.
        val same = SportsLiveEndedGames()
        same.install(mapOf(card.streamKey to SportsLiveEndedGames.FinalTime(endedAt, eventId = pacersFinal.eventId)), endedAt) { }
        assertEquals(endedAt, same.verdict(card, pacersFinal, endedAt + 5 * MINUTE)!!.endedAtMs)
        // Past 6 h it is not reused (re-dated from the estimate instead).
        val late = endedAt + SportsLiveEndedGames.FORGET_AFTER_MS + MINUTE
        assertEquals(START + BASKETBALL, same.verdict(card, pacersFinal, late)!!.endedAtMs)
    }

    @Test
    fun `the stored text round-trips keys with commas and the no-grace mark`() {
        val times = mapOf(
            "a|b,c" to SportsLiveEndedGames.FinalTime(START, graced = false, eventId = "401909985"),
            "k" to SportsLiveEndedGames.FinalTime(START + MINUTE, eventId = "7")
        )
        assertEquals(times, SportsLiveEndedGames.decodeTimes(SportsLiveEndedGames.encodeTimes(times)))
        assertTrue(SportsLiveEndedGames.decodeTimes(null).isEmpty())
    }

    @Test
    fun `a final in its grace stays in the main list but is marked final, never live`() {
        val games = SportsLiveEndedGames()
        val card = item()
        games.verdict(card, live(pacersFinal), START + 2 * HOUR)
        val endedAt = START + 2 * HOUR + MINUTE
        val split = games.split(listOf(card), mapOf(card.streamKey to pacersFinal), endedAt + 5 * MINUTE)
        assertEquals(listOf(card), split.live)
        assertEquals(EndedSignal.FINAL, split.finals[card.streamKey]?.signal)
        assertTrue(SportsLiveEndedGames.isEspnFinal(card, pacersFinal))
        assertTrue(!SportsLiveEndedGames.isEspnFinal(card, live(pacersFinal)))
        assertTrue(!SportsLiveEndedGames.isEspnFinal(card, null))
        // A final the delay still withholds (no score yet) is not labelled final early.
        val withheld = pacersFinal.copy(home = pacersFinal.home.copy(score = null), away = pacersFinal.away.copy(score = null))
        assertTrue(!SportsLiveEndedGames.isEspnFinal(card, withheld))
        // A channel naming the game keeps its LIVE pill, as it is never moved.
        val channel = item(name = "US: Sports 1 [Indiana Pacers at Philadelphia 76ers]", isChannel = true)
        assertTrue(!SportsLiveEndedGames.isEspnFinal(channel, pacersFinal))
        // A surface without the snapshot now (the player panel) still gets the final, so it never groups it as live.
        assertEquals(pacersFinal.eventId, split.scoresWithFinals(emptyMap())[card.streamKey]?.eventId)
    }

    @Test
    fun `the dual picker leaves out finals past the grace, with every source of a merged card`() {
        val games = SportsLiveEndedGames()
        val alternate = item(id = "alt").copy(addonId = "b")
        val card = item().copy(alternates = listOf(alternate))
        val now = START + BASKETBALL + 15 * MINUTE
        val keys = games.confirmedEndedSourceKeys(listOf(card), mapOf(card.streamKey to pacersFinal), now)
        assertTrue(card.hasEnded(keys))
    }

    @Test
    fun `Up next leaves out every game Live now shows over - a final in its grace and a Probably ended estimate too`() {
        val games = SportsLiveEndedGames()
        val now = START + BASKETBALL + 5 * MINUTE
        val alternate = item(id = "alt").copy(addonId = "b")
        val finalCard = item(id = "final").copy(alternates = listOf(alternate))
        val estimated = item(name = "Boston Celtics at New York Knicks", id = "estimated")
        val playing = item(name = "Los Angeles Lakers at Denver Nuggets", id = "playing", startMs = now - 30 * MINUTE)
        val channel = item(name = "TSN 4", id = "channel", isChannel = true, startMs = null)
        val items = listOf(finalCard, estimated, playing, channel)
        val scores = mapOf(finalCard.streamKey to pacersFinal)
        val over = games.overSourceKeys(items, scores, now)
        assertEquals(setOf(finalCard.streamKey, alternate.streamKey, estimated.streamKey), over)
        // Home's confirmed finals wait for the grace, and never take an estimate: the gap live.19 QA F2 found.
        assertTrue(games.confirmedEndedSourceKeys(items, scores, now).isEmpty())
        // Where Live now may not estimate a card, neither does Up next.
        assertEquals(setOf(finalCard.streamKey, alternate.streamKey), games.overSourceKeys(items, scores, now) { false })
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
        const val LATE = SportsLiveGameLength.LATE_START_MS
        /** Basketball's estimated length with the late-start allowance. */
        const val BASKETBALL = SportsLiveGameLength.BASKETBALL_MS + LATE
    }

    @Test
    fun `Home's live count and zap list keep a final through the grace, as Live Now's list does`() {
        val games = SportsLiveEndedGames()
        val card = item()
        // Kicked off 40 minutes before the end of the grace: in play by any estimate.
        val running = item(name = "Leeds vs Everton", sport = "Soccer", startMs = START + HOUR + 30 * MINUTE)
        val all = listOf(card, running)
        games.verdict(card, live(pacersFinal), START + HOUR)
        val end = START + 2 * HOUR
        val scores = mapOf(card.streamKey to pacersFinal)
        games.verdict(card, pacersFinal, end)
        // In the grace: Home's row drops the card (a final never reads "Live now"), but the count and zap list keep it,
        // as Live Now keeps it in its main list.
        val inGrace = end + 5 * MINUTE
        assertEquals(setOf(card.streamKey), games.finalKeys(all, scores, inGrace))
        val homeLive = sportsLiveHomeLiveGames(all, games.confirmedEndedKeys(all, scores, inGrace))
        assertEquals(games.split(all, scores, inGrace).live, homeLive)
        assertEquals(all, homeLive)
        // Past the grace: both leave it out at the same moment.
        val after = end + 10 * MINUTE
        val homeAfter = sportsLiveHomeLiveGames(all, games.confirmedEndedKeys(all, scores, after))
        assertEquals(games.split(all, scores, after).live, homeAfter)
        assertEquals(listOf(running), homeAfter)
    }
}
