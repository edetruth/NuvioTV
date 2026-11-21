package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.scores.ChipScore
import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.ScoreChip
import com.nuvio.tv.core.sportslive.scores.ScoreChipStrings
import com.nuvio.tv.core.sportslive.scores.ScoreChips
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameStatusTest {

    private val lakersEvent: ScoreSnapshot =
        EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA).first { it.eventId == "401909986" } // LAL @ PHX

    private fun item(
        name: String = "Lakers @ Suns",
        startMs: Long? = NOW - HOUR,
        isLive: Boolean = true,
        isChannel: Boolean = false,
        timed: Boolean = startMs != null
    ) = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
        kind = if (isChannel) SportsLiveKind.CHANNELS else SportsLiveKind.EVENTS, isLive = isLive, isChannel = isChannel,
        startMs = startMs, sport = "Basketball", startHasTimeOfDay = timed,
        teams = SportsLiveTeams.parse(name)
    )

    private fun event(state: ScoreState, startMs: Long = NOW - HOUR, clock: String? = null, final: Boolean = false) = lakersEvent.copy(
        state = state, startMs = startMs, timeValid = true, clockText = clock, isFinal = final,
        home = lakersEvent.home.copy(score = if (state == ScoreState.PRE) null else 101),
        away = lakersEvent.away.copy(score = if (state == ScoreState.PRE) null else 99)
    )

    // ---- GameStatus.of: each state ----

    @Test
    fun `ESPN in play is live whatever the addon says`() {
        val live = event(ScoreState.IN, clock = "Q3 4:12")
        assertEquals(GameStatus.Live(live), GameStatus.of(item(isLive = false), live, null, NOW))
        assertEquals(GameStatus.Live(live), GameStatus.of(item(isLive = true), live, null, NOW))
    }

    @Test
    fun `an addon live game ESPN still has as scheduled is starting soon, never live`() {
        // F5: the same game read LIVE in Cards and Starting soon in the Scoreboard.
        val pre = event(ScoreState.PRE, startMs = NOW + MINUTE)
        val status = GameStatus.of(item(isLive = true), pre, null, NOW)
        assertEquals(GameStatus.StartingSoon(NOW + MINUTE, 0 to 1), status)
    }

    @Test
    fun `a scheduled game beyond three hours is upcoming`() {
        val pre = event(ScoreState.PRE, startMs = NOW + 5 * HOUR)
        assertEquals(GameStatus.Upcoming(NOW + 5 * HOUR, hasTimeOfDay = true), GameStatus.of(item(isLive = false), pre, null, NOW))
    }

    @Test
    fun `a scheduled game whose start has passed is still starting soon, with no countdown`() {
        val pre = event(ScoreState.PRE, startMs = NOW - 10 * MINUTE)
        assertEquals(GameStatus.StartingSoon(NOW - 10 * MINUTE, null), GameStatus.of(item(), pre, null, NOW))
    }

    @Test
    fun `ESPN post is final`() {
        val post = event(ScoreState.POST, clock = "Final", final = true)
        assertEquals(GameStatus.Final(post), GameStatus.of(item(), post, null, NOW))
    }

    @Test
    fun `a just ended verdict wins over the live feed`() {
        val ended = EndedGame(EndedSignal.FINAL, NOW - MINUTE, event(ScoreState.POST, final = true))
        val status = GameStatus.of(item(), event(ScoreState.IN), ended, NOW)
        assertEquals(GameStatus.JustEnded(ended), status)
        assertTrue(GameStatus.JustEnded(EndedGame(EndedSignal.ESTIMATE, NOW)).estimate)
    }

    @Test
    fun `ESPN off is postponed with its text`() {
        val off = event(ScoreState.OFF, clock = "Postponed")
        assertEquals(GameStatus.Postponed("Postponed"), GameStatus.of(item(), off, null, NOW))
        assertEquals(GameStatus.Postponed(null), GameStatus.of(item(), event(ScoreState.OFF, clock = " "), null, NOW))
    }

    @Test
    fun `unmatched games follow their list and their clock`() {
        assertEquals(GameStatus.Live(null), GameStatus.of(item(isLive = true, startMs = NOW - HOUR), null, null, NOW))
        assertEquals(GameStatus.Live(null), GameStatus.of(item(isLive = true, startMs = null), null, null, NOW))
        // A later start says it has not begun, even when listed live.
        assertEquals(GameStatus.StartingSoon(NOW + HOUR, 1 to 0), GameStatus.of(item(isLive = true, startMs = NOW + HOUR), null, null, NOW))
        // Upcoming's games (not listed live) are never live without ESPN.
        assertEquals(GameStatus.StartingSoon(NOW - HOUR, null), GameStatus.of(item(isLive = false, startMs = NOW - HOUR), null, null, NOW))
        assertEquals(GameStatus.Upcoming(NOW, hasTimeOfDay = false), GameStatus.of(item(isLive = false, startMs = NOW, timed = false), null, null, NOW))
        assertEquals(GameStatus.Live(null), GameStatus.of(item(isLive = false), null, null, NOW, listedLive = true))
    }

    @Test
    fun `a date with no time never starts soon or counts down`() {
        val status = GameStatus.of(item(isLive = false, startMs = NOW + HOUR, timed = false), null, null, NOW)
        assertEquals(GameStatus.Upcoming(NOW + HOUR, hasTimeOfDay = false), status)
    }

    @Test
    fun `channels are channels unless ESPN has their bracketed game in play`() {
        assertEquals(GameStatus.Channel, GameStatus.of(item(name = "Sports Channel 1", isChannel = true), null, null, NOW))
        assertEquals(GameStatus.Channel, GameStatus.of(item(isChannel = true), event(ScoreState.POST, final = true), null, NOW))
    }

    @Test
    fun `a snapshot of another game is ignored`() {
        val other = event(ScoreState.IN).copy(home = lakersEvent.home.copy(displayName = "Somebody", shortDisplayName = "Somebody", abbreviation = "SB", name = "Somebody", location = "Nowhere"))
        val status = GameStatus.of(item(isLive = false, startMs = NOW + 5 * HOUR), other, null, NOW)
        assertEquals(GameStatus.Upcoming(NOW + 5 * HOUR, hasTimeOfDay = true), status)
    }

    @Test
    fun `starts in rounds up to the minute and ends at the start`() {
        assertEquals(2 to 21, GameStatus.startsIn(NOW + 2 * HOUR + 20 * MINUTE + 1, NOW))
        assertEquals(0 to 1, GameStatus.startsIn(NOW + 1, NOW))
        assertNull(GameStatus.startsIn(NOW, NOW))
    }

    // ---- GameStatusText ----

    private val chipStrings = ScoreChipStrings("%1\$d–%2\$d", "%1\$s · %2\$s", "%1\$s %2\$s", "Final", "Postponed", "Score hidden")
    private val strings = GameStatusStrings(
        liveClock = "● %1\$s", live = "LIVE", clockPeriod = "%1\$s · %2\$s", timeCountdown = "%1\$s · %2\$s",
        inHoursMinutes = "in %1\$d h %2\$d m", inHours = "in %1\$d h", inMinutes = "in %1\$d min",
        final = "Final", fullTime = "FT", probablyEnded = "Probably ended", postponed = "Postponed", chip = chipStrings
    )
    private val timeOf: (Long, Boolean) -> String = { _, timed -> if (timed) "3:30 PM" else "Sat 21 Nov" }

    private fun text(status: GameStatus, chip: ScoreChip? = null, sport: String? = null) =
        GameStatusText.of(status, chip, strings, timeOf, sport)

    @Test
    fun `live reads the clock with its period`() {
        assertEquals("● 9:51 · 2nd half", text(GameStatus.Live(null), ScoreChip.Live("9:51 - 2nd Half", ChipScore(78, 55))))
        assertEquals("● Q3 4:12", text(GameStatus.Live(null), ScoreChip.Live("Q3 4:12", null)))
        assertEquals("● 67'", text(GameStatus.Live(null), ScoreChip.Live("67'", null)))
        assertEquals("● Halftime", text(GameStatus.Live(null), ScoreChip.Live("Halftime", null)))
    }

    @Test
    fun `live with no clock or a hidden score`() {
        assertEquals("LIVE", text(GameStatus.Live(null)))
        assertEquals("LIVE", text(GameStatus.Live(null), ScoreChip.Live(null, ChipScore(1, 0))))
        assertEquals("Score hidden", text(GameStatus.Live(null), ScoreChip.Hidden))
    }

    @Test
    fun `starting soon reads the time and the countdown`() {
        assertEquals("3:30 PM · in 2 h 21 m", text(GameStatus.StartingSoon(NOW, 2 to 21)))
        assertEquals("3:30 PM · in 12 min", text(GameStatus.StartingSoon(NOW, 0 to 12)))
        assertEquals("3:30 PM · in 2 h", text(GameStatus.StartingSoon(NOW, 2 to 0)))
        assertEquals("3:30 PM", text(GameStatus.StartingSoon(NOW, null)))
    }

    @Test
    fun `upcoming reads its time or date`() {
        assertEquals("3:30 PM", text(GameStatus.Upcoming(NOW, hasTimeOfDay = true)))
        assertEquals("Sat 21 Nov", text(GameStatus.Upcoming(NOW, hasTimeOfDay = false)))
        assertEquals("", text(GameStatus.Upcoming(null, hasTimeOfDay = false)))
    }

    @Test
    fun `finals read FT for soccer and Final for the US sports`() {
        val nfl = event(ScoreState.POST, final = true).copy(league = EspnLeagueMap.NFL)
        val soccer = event(ScoreState.POST, final = true).copy(league = EspnLeague("soccer", "eng.1"))
        assertEquals("Final", text(GameStatus.Final(nfl)))
        assertEquals("FT", text(GameStatus.Final(soccer)))
        // ESPN's own note wins when it gave one.
        assertEquals("FT 2–1", text(GameStatus.Final(soccer), ScoreChip.Final("FT", ChipScore(2, 1))))
        assertEquals("Final · OT 101–99", text(GameStatus.Final(nfl), ScoreChip.Final("Final/OT", ChipScore(101, 99))))
        // No note: the sport's word.
        assertEquals("Final 24–17", text(GameStatus.Final(nfl), ScoreChip.Final(null, ChipScore(24, 17))))
        assertEquals("FT 2–1", text(GameStatus.Final(soccer), ScoreChip.Final(null, ChipScore(2, 1))))
        assertEquals("Score hidden", text(GameStatus.Final(nfl), ScoreChip.Hidden))
    }

    @Test
    fun `just ended reads final or probably ended`() {
        val soccerFinal = event(ScoreState.POST, final = true).copy(league = EspnLeague("soccer", "usa.1"))
        assertEquals("FT", text(GameStatus.JustEnded(EndedGame(EndedSignal.FINAL, NOW, soccerFinal))))
        assertEquals("Probably ended", text(GameStatus.JustEnded(EndedGame(EndedSignal.ESTIMATE, NOW))))
        assertEquals("Final", text(GameStatus.JustEnded(EndedGame(EndedSignal.FINAL, NOW, null))))
    }

    @Test
    fun `postponed and channels`() {
        assertEquals("Postponed", text(GameStatus.Postponed(null)))
        assertEquals("Suspended", text(GameStatus.Postponed("Suspended")))
        assertEquals("", text(GameStatus.Channel))
    }

    @Test
    fun `the final word per sport`() {
        assertEquals("FT", GameStatusText.finalWord("soccer", strings))
        assertEquals("FT", GameStatusText.finalWord("rugby", strings))
        assertEquals("Final", GameStatusText.finalWord("football", strings))
        assertEquals("Final", GameStatusText.finalWord("basketball", strings))
        assertEquals("Final", GameStatusText.finalWord("hockey", strings))
        assertEquals("Final", GameStatusText.finalWord(null, strings))
    }

    @Test
    fun `the status and the chip agree on a matched live game`() {
        // One source: a live chip only ever comes with a live status.
        val live = event(ScoreState.IN, clock = "Q4 2:00")
        val chip = ScoreChips.of(item(), live, ScoresMode.SHOW, revealed = false)
        assertTrue(chip is ScoreChip.Live)
        assertTrue(GameStatus.of(item(), live, null, NOW) is GameStatus.Live)
        assertEquals("basketball", GameStatusText.sportOf(GameStatus.Live(live)))
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
