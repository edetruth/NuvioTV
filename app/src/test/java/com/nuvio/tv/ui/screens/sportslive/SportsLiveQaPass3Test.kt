package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.EndedGame
import com.nuvio.tv.core.sportslive.EndedSignal
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.alerts.GameReminders
import com.nuvio.tv.core.sportslive.alerts.RemindersState
import com.nuvio.tv.core.sportslive.alerts.SportsLiveAlerts
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoreTeam
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** Emulator QA pass 3 (2026-11-18): the bugs it found, and the user's follow-up picks. */
class SportsLiveQaPass3Test {

    private fun item(
        name: String,
        startMs: Long?,
        sport: String = "Basketball",
        isLive: Boolean = false,
        channel: Boolean = false
    ) = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
        kind = if (channel) SportsLiveKind.CHANNELS else SportsLiveKind.EVENTS, isLive = isLive, isChannel = channel,
        startMs = startMs, sport = sport, startHasTimeOfDay = startMs != null,
        teams = if (channel) null else SportsLiveTeams.parse(name)
    )

    private fun row(item: SportsLiveItem) =
        ScoreboardRow(item, ScoreboardGroup.STARTING_SOON, chip = null, art = null, firstFollowed = false, secondFollowed = false, probablyEnded = false)

    // --- Bug 5: Down into the Timeline on Upcoming ---

    @Test
    fun `Down enters the Timeline on the remembered game only while its row is on screen`() {
        val soon = item("Lakers @ Celtics", NOW + HOUR)
        val later = item("Bulls @ Knicks", NOW + 3 * HOUR)
        val soccer = item("Arsenal vs Chelsea", NOW + 5 * HOUR, sport = "Soccer")
        val model = SportsLiveTimeline.model(listOf(row(soon), row(later), row(soccer)), SportsLiveTab.UPCOMING, NOW, ZoneOffset.UTC)
        // Basketball starts first: its row leads, Soccer is the second row.
        assertEquals(2, model.navRows.size)
        val remembered = soccer.timelineKey

        // The remembered game's row is on screen: it is where focus enters.
        assertEquals(remembered, SportsLiveTimeline.onScreenEntryKey(model, remembered, visibleRows = listOf(0, 1)))
        // Scrolled away (only the first row is laid out): the first row on screen's next game, never a block
        // with no node to take focus.
        assertEquals(soon.timelineKey, SportsLiveTimeline.onScreenEntryKey(model, remembered, visibleRows = listOf(0)))
        // Only the second row on screen, nothing remembered: that row's game.
        assertEquals(remembered, SportsLiveTimeline.onScreenEntryKey(model, null, visibleRows = listOf(1)))
        // Not laid out yet: the remembered game, as before.
        assertEquals(remembered, SportsLiveTimeline.onScreenEntryKey(model, remembered, visibleRows = emptyList()))
    }

    // --- Design 11: the now tag ---

    @Test
    fun `the now tag leaves the ruler whole once the now line is scrolled away, never a sliver`() {
        // The now line 10 px left of the window: the tag (80 px) goes wholly out of view.
        val left = SportsLiveTimeline.nowTagLeft(nowX = 490f, scroll = 500f, shiftPx = 24f, tagWidth = 80f, viewport = 1000f)
        assertTrue("tag right edge at ${left + 80f}", left + 80f <= 0f)
        val right = SportsLiveTimeline.nowTagLeft(nowX = 1510f, scroll = 500f, shiftPx = 24f, tagWidth = 80f, viewport = 1000f)
        assertTrue("tag left edge at $right", right >= 1000f)
        // In the window: whole and inside the ruler.
        assertEquals(0f, SportsLiveTimeline.nowTagLeft(nowX = 505f, scroll = 500f, shiftPx = 24f, tagWidth = 80f, viewport = 1000f), 0f)
    }

    // --- Bug 8: one live definition for Results and Live now ---

    @Test
    fun `Results counts the live games Live now shows, never ESPN's alone`() {
        val game = item("Pacers @ 76ers", NOW - HOUR, isLive = true)
        val channel = item("Sports 1", null, isLive = true, channel = true)
        val ended = item("Bucks @ Bulls", NOW - 3 * HOUR, isLive = true)
        val state = SportsLiveUiState(
            tabs = mapOf(SportsLiveTab.LIVE_NOW to listOf(game, channel, ended)),
            justEnded = mapOf(ended.streamKey to EndedGame(EndedSignal.FINAL, NOW - 20 * MIN))
        )
        assertEquals(1, state.liveGameCount)
        assertEquals(0, SportsLiveUiState(tabs = mapOf(SportsLiveTab.LIVE_NOW to listOf(channel))).liveGameCount)
        assertEquals(0, SportsLiveUiState().liveGameCount)
    }

    // --- Remind me on a Team page tile with no card ---

    private fun team(name: String, short: String) =
        ScoreTeam(id = short, abbreviation = short, displayName = name, shortDisplayName = short, name = short, location = null, logo = null, score = null, linescores = emptyList())

    private val nextGame = ScoreSnapshot(
        eventId = "e1",
        league = EspnLeagueMap.NBA,
        startMs = NOW + 2 * HOUR,
        state = ScoreState.PRE,
        clockText = null,
        isFinal = false,
        home = team("Boston Celtics", "Celtics"),
        away = team("Los Angeles Lakers", "Lakers")
    )

    @Test
    fun `a Team page tile reminds a game no card plays yet, and the reminder finds its card at kickoff`() {
        assertEquals(UpcomingAction.REMIND, upcomingAction(nextGame, card = null, nowMs = NOW))
        // Untimed, started or live games are not reminded.
        assertEquals(UpcomingAction.GAME_PAGE, upcomingAction(nextGame.copy(timeValid = false), null, NOW))
        assertEquals(UpcomingAction.GAME_PAGE, upcomingAction(nextGame.copy(startMs = NOW - MIN), null, NOW))
        assertEquals(UpcomingAction.GAME_PAGE, upcomingAction(nextGame.copy(state = ScoreState.IN), null, NOW))

        val on = GameReminders.toggle(RemindersState(), nextGame, NOW)
        assertTrue(on.isReminded(nextGame))
        // An addon lists the game later, under its own names: the reminder is its (the bell, the kickoff prompt).
        val card = item("Lakers @ Celtics", NOW + 2 * HOUR)
        assertTrue(on.isReminded(card))
        assertFalse(on.isReminded(item("Lakers @ Celtics", NOW + 26 * HOUR)))

        val off = GameReminders.toggle(on, nextGame, NOW)
        assertFalse(off.isReminded(nextGame))
        assertFalse(off.isReminded(card))
    }

    @Test
    fun `a bell press shows the app's toast card, and a newer press replaces it`() {
        val alerts = SportsLiveAlerts(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        val card = item("Lakers @ Celtics", NOW + HOUR)
        alerts.noticeReminder(card, on = true)
        val first = alerts.reminderNotice.value!!
        assertTrue(first.on)
        assertEquals("Lakers @ Celtics", first.title)
        assertEquals(card, first.item)

        alerts.noticeReminder(nextGame, on = false)
        val second = alerts.reminderNotice.value!!
        assertFalse(second.on)
        assertEquals("Lakers", second.away)
        assertEquals("Celtics", second.home)
        // The first one's time running out leaves the newer one up.
        alerts.closeReminderNotice(first)
        assertEquals(second, alerts.reminderNotice.value)
        alerts.closeReminderNotice(second)
        assertNull(alerts.reminderNotice.value)
    }

    // --- Design 10 and Results rows ---

    @Test
    fun `scoreboard league tags shorten long names instead of cutting them`() {
        assertEquals("NBA", scoreboardTag("NBA"))
        assertEquals("BASKETBALL", scoreboardTag("Basketball"))
        assertEquals("BSA", scoreboardTag("Brazil Serie A"))
        assertEquals("AF", scoreboardTag("American Football"))
        assertEquals("CHAMPIONSH", scoreboardTag("Championships"))
    }

    @Test
    fun `a Results row is about 70 px at 1080p`() {
        // 1080p at density 320: two pixels a dp.
        val px = ResultRowMetrics.HEIGHT_DP * 2
        assertTrue("row is $px px", px in 64..80)
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val MIN = 60_000L
        const val HOUR = 60 * MIN
    }
}
