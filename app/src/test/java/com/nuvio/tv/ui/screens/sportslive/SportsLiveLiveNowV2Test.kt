package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.EndedGame
import com.nuvio.tv.core.sportslive.EndedSignal
import com.nuvio.tv.core.sportslive.GameStatusStrings
import com.nuvio.tv.core.sportslive.SportsLiveEndedGames
import com.nuvio.tv.core.sportslive.SportsLiveProgress
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.alerts.GameReminders
import com.nuvio.tv.core.sportslive.scores.EspnFixtures
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
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.ui.screens.sportslive.SportsLiveMoreEntry.CHOOSE_STREAM
import com.nuvio.tv.ui.screens.sportslive.SportsLiveMoreEntry.DETAILS
import com.nuvio.tv.ui.screens.sportslive.SportsLiveMoreEntry.FOLLOW
import com.nuvio.tv.ui.screens.sportslive.SportsLiveMoreEntry.GAME_STATS
import com.nuvio.tv.ui.screens.sportslive.SportsLiveMoreEntry.REMIND
import com.nuvio.tv.ui.screens.sportslive.SportsLiveMoreEntry.WATCH_WITH
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** V2 Live Now (#132): Scoreboard + Stage, its groups, row cells, More… menu, stage and list behaviour. */
class SportsLiveLiveNowV2Test {

    private val nba = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA)
    private val pacersEvent = nba.first { it.eventId == "401909985" } // IND @ PHI, final 131–124
    private val lakersEvent = nba.first { it.eventId == "401909986" } // LAL @ PHX

    private fun item(
        name: String,
        startMs: Long?,
        isLive: Boolean = true,
        isChannel: Boolean = false,
        sport: String? = "Basketball"
    ) = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
        kind = if (isChannel) SportsLiveKind.CHANNELS else SportsLiveKind.EVENTS, isLive = isLive, isChannel = isChannel,
        startMs = startMs, sport = sport, startHasTimeOfDay = startMs != null,
        teams = SportsLiveTeams.parse(name)
    )

    private fun inPlay(event: ScoreSnapshot, home: Int, away: Int, clock: String) = event.copy(
        state = ScoreState.IN, isFinal = false, clockText = clock,
        home = event.home.copy(score = home), away = event.away.copy(score = away)
    )

    private val pacers = item("Pacers @ 76ers", NOW - HOUR)
    private val soon = item("Celtics @ Knicks", NOW + 12 * MINUTE, isLive = false)
    private val channel = item("UK: Sports Channel 1 HD", null, isChannel = true, sport = null)
    private val lakers = item("Lakers @ Suns", NOW - 3 * HOUR)
    private val scores = mapOf(pacers.streamKey to inPlay(pacersEvent, 80, 88, "4:12 - 4th"), lakers.streamKey to lakersEvent)
    private val ended = mapOf(lakers.streamKey to EndedGame(EndedSignal.FINAL, NOW - 5 * MINUTE, lakersEvent))

    private fun rows(mode: ScoresMode? = ScoresMode.SHOW, revealed: Set<String> = emptySet()) =
        scoreboardRows(listOf(channel, lakers, soon, pacers), SportsLiveTab.LIVE_NOW, scores, mode, revealed, NOW, ended)

    // ---- Groups ----

    @Test
    fun `channels get their own group, never mixed with games, and just ended comes last`() {
        val rows = rows()
        assertEquals(listOf(pacers, soon, channel, lakers), rows.map { it.item })
        assertEquals(
            listOf(ScoreboardGroup.LIVE, ScoreboardGroup.STARTING_SOON, ScoreboardGroup.CHANNELS, ScoreboardGroup.JUST_ENDED),
            rows.map { it.group }
        )
        // A channel is a channel whatever its bracketed game does.
        assertEquals(ScoreboardGroup.CHANNELS, scoreboardGroupOf(com.nuvio.tv.core.sportslive.GameStatus.Live(null), channel))
    }

    @Test
    fun `just ended rows are dimmed until focused`() {
        val justEnded = rows().first { it.item == lakers }
        assertTrue(justEnded.dimmed(focused = false))
        assertFalse(justEnded.dimmed(focused = true))
        assertFalse(rows().first { it.item == pacers }.dimmed(focused = false))
    }

    @Test
    fun `an ESPN final leaves Live Now 30 minutes after it ended`() {
        val games = SportsLiveEndedGames()
        val endedAt = games.verdict(lakers, lakersEvent, NOW)!!.endedAtMs
        val drops = EndedGame(EndedSignal.FINAL, endedAt).dropsAtMs
        assertEquals(endedAt + 30 * MINUTE, drops)
        val beforeDrop = games.split(listOf(lakers), mapOf(lakers.streamKey to lakersEvent), drops - 1)
        assertEquals(listOf(lakers), beforeDrop.justEnded)
        val afterDrop = games.split(listOf(lakers), mapOf(lakers.streamKey to lakersEvent), drops)
        assertTrue(afterDrop.live.isEmpty() && afterDrop.justEnded.isEmpty())
    }

    // ---- Progress ----

    @Test
    fun `progress is the share of regulation played, per sport`() {
        // NBA: 48 minutes in four quarters; 4:12 left in the 4th is about 91 %.
        val share = SportsLiveProgress.share(pacers, ScoreChips.matchedEvent(pacers, scores[pacers.streamKey]), NOW)!!
        assertEquals(0.9125f, share, 0.001f)
        assertEquals(share, rows().first { it.item == pacers }.progress!!, 0.0001f)
        // Soccer by its minute: 67' of 90.
        val soccer = inPlay(pacersEvent.copy(league = EspnLeagueMap.SOCCER_ALL), 1, 1, "67'")
        val soccerItem = item("Arsenal vs Chelsea", NOW - HOUR, sport = "Soccer")
        assertEquals(67f / 90f, SportsLiveProgress.share(soccerItem, soccer, NOW)!!, 0.01f)
        // College basketball plays halves, though ESPN's short clock says "1st": 3:25 left of 20 is about 41 %.
        val college = inPlay(pacersEvent.copy(league = com.nuvio.tv.core.sportslive.scores.EspnLeague("basketball", "mens-college-basketball")), 30, 28, "3:25 - 1st")
        assertEquals((1f - 205f / 1200f) / 2f, SportsLiveProgress.share(pacers, college, NOW)!!, 0.001f)
        // Unmatched: the time since its start against its sport's length (#60 table).
        val unmatched = item("Hawks @ Spurs", NOW - 90 * MINUTE)
        assertEquals(90f / 180f, SportsLiveProgress.share(unmatched, null, NOW)!!, 0.001f)
        // Unknown: no start, a long-session or unknown sport, or not started yet. No bar.
        assertNull(SportsLiveProgress.share(item("Hawks @ Spurs", null), null, NOW))
        assertNull(SportsLiveProgress.share(item("Djokovic vs Alcaraz", NOW - HOUR, sport = "Tennis"), null, NOW))
        assertNull(SportsLiveProgress.share(item("Mystery match", NOW - HOUR, sport = null), null, NOW))
        assertNull(SportsLiveProgress.share(item("Hawks @ Spurs", NOW + HOUR), null, NOW))
        // Only live rows draw it.
        assertNull(rows().first { it.item == soon }.progress)
        assertNull(rows().first { it.item == lakers }.progress)
    }

    // ---- Row cells and spoilers ----

    private val chipStrings = ScoreChipStrings("%1\$d–%2\$d", "%1\$s · %2\$s", "%1\$s %2\$s", "Final", "Postponed", "Score hidden")
    private val strings = GameStatusStrings(
        liveClock = "● %1\$s", live = "LIVE", clockPeriod = "%1\$s · %2\$s", timeCountdown = "%1\$s · %2\$s",
        inHoursMinutes = "in %1\$d h %2\$d m", inHours = "in %1\$d h", inMinutes = "in %1\$d min",
        final = "Final", fullTime = "FT", probablyEnded = "Probably ended", postponed = "Postponed", chip = chipStrings
    )

    @Test
    fun `a live row stacks its scores with the leader marked and splits clock from period`() {
        val row = rows().first { it.item == pacers }
        assertEquals(ScoreboardScores.Shown(first = 88, second = 80, leader = 0), scoreboardScoresOf(row.chip))
        assertEquals(ScoreboardStatusLines("4:12", "4th", live = true), scoreboardStatusLines(row, strings, kickoff = null))
        assertEquals("4:12" to "Q4", splitClock("Q4 4:12"))
        assertEquals("67'" to null, splitClock("67'"))
        assertEquals("9:51" to "2nd half", splitClock("9:51 - 2nd Half"))
    }

    @Test
    fun `starting soon shows the kickoff over its countdown, red inside the last hour`() {
        val row = rows().first { it.item == soon }
        assertEquals(ScoreboardScores.None, scoreboardScoresOf(row.chip))
        assertEquals(ScoreboardStatusLines("8:30 PM", "in 12 min", urgent = true), scoreboardStatusLines(row, strings, kickoff = "8:30 PM"))
        val later = scoreboardRows(listOf(item("Bulls @ Heat", NOW + 141 * MINUTE, isLive = false)), SportsLiveTab.LIVE_NOW, emptyMap(), ScoresMode.SHOW, emptySet(), NOW).single()
        assertEquals(ScoreboardStatusLines("10:30 PM", "in 2 h 21 m", urgent = false), scoreboardStatusLines(later, strings, kickoff = "10:30 PM"))
    }

    @Test
    fun `spoilers stay as they were - hidden until focused, nothing under Off`() {
        val hidden = rows(mode = ScoresMode.HIDE_UNTIL_FOCUSED)
        hidden.filter { it.item == pacers || it.item == lakers }.forEach { row ->
            assertEquals(ScoreboardScores.Hidden, scoreboardScoresOf(row.chip))
            // No clock, no dot: "Score hidden", whatever the game's state.
            assertEquals(ScoreboardStatusLines("Score hidden"), scoreboardStatusLines(row, strings, kickoff = null))
        }
        // Revealed (focused): the score.
        val revealed = rows(mode = ScoresMode.HIDE_UNTIL_FOCUSED, revealed = setOf(pacers.streamKey)).first { it.item == pacers }
        assertTrue(scoreboardScoresOf(revealed.chip) is ScoreboardScores.Shown)
        // Off: no scores; a live unmatched game reads LIVE with the dot.
        val off = rows(mode = ScoresMode.OFF).first { it.item == pacers }
        assertEquals(ScoreboardScores.None, scoreboardScoresOf(off.chip))
        assertEquals(ScoreboardStatusLines("LIVE", live = true), scoreboardStatusLines(off, strings, kickoff = null))
        // An estimated end: "Probably ended", never a score.
        val estimate = scoreboardRows(
            listOf(pacers), SportsLiveTab.LIVE_NOW, emptyMap(), ScoresMode.SHOW, emptySet(), NOW,
            mapOf(pacers.streamKey to EndedGame(EndedSignal.ESTIMATE, NOW - MINUTE))
        ).single()
        assertEquals(ScoreboardScores.None, scoreboardScoresOf(estimate.chip))
        assertEquals(ScoreboardStatusLines("Probably ended"), scoreboardStatusLines(estimate, strings, kickoff = null))
    }

    @Test
    fun `a channel row has no status column`() {
        val row = rows().first { it.item == channel }
        assertEquals(ScoreboardStatusLines(null), scoreboardStatusLines(row, strings, kickoff = null))
        assertEquals(ScoreboardScores.None, scoreboardScoresOf(row.chip))
    }

    @Test
    fun `tags and league labels drop addon decoration and never spell out a catalog name`() {
        assertEquals("Live Events", com.nuvio.tv.core.sportslive.SportsLiveDisplayText.label("⚡ Live Events (tv)"))
        assertNull(com.nuvio.tv.core.sportslive.SportsLiveDisplayText.label("⚡ (x)"))
        assertEquals("TENNIS", scoreboardTag("⚡ Tennis"))
        // A sport that is only the catalog's name: whole when short, else no tag (never initials like "LE").
        assertEquals("", scoreboardRowTag(null, "⚡ Live Events (tv)", "⚡ Live Events (tv)"))
        assertEquals("EVENTS", scoreboardRowTag(null, "⚡ Events", "⚡ Events"))
        assertEquals("NBA", scoreboardRowTag("NBA", "Basketball", "Live"))
        assertEquals("BASKETBALL", scoreboardRowTag(null, "Basketball", "Live"))
        // The stage names no catalog as the league; a real league is cleaned.
        assertNull(sportsLiveLeagueLabel("⚡ Live Events (tv)", "⚡ Live Events (tv)"))
        assertEquals("Premier League", sportsLiveLeagueLabel("Premier League (EN)", "Live"))
        // A filter option reads clean; its value stays the raw sport it filters by.
        val option = sportsLiveFilterOptions(listOf("⚡ Events (tv)"), "★ My teams", "All").last()
        assertEquals("Events", option.label)
        assertEquals("⚡ Events (tv)", option.value)
    }

    // ---- More… ----

    private fun entriesFor(item: SportsLiveItem, state: SportsLiveUiState, snapshot: ScoreSnapshot?): List<SportsLiveMoreEntry> =
        sportsLiveMoreEntries(
            watchWith = state.offersWatchWith(item, snapshot),
            chooseStream = sportsLiveAutoPlayEligible(item),
            strictMatch = ScoreChips.matchedEvent(item, snapshot) != null,
            sides = item.teams?.let { 2 } ?: 0,
            remindable = GameReminders.canRemind(item, NOW)
        )

    @Test
    fun `More lists what fits each kind of item`() {
        val liveNow = SportsLiveUiState(selectedTab = SportsLiveTab.LIVE_NOW, tabs = mapOf(SportsLiveTab.LIVE_NOW to listOf(pacers, channel)))
        // A matched live game: everything but Remind me.
        assertEquals(
            listOf(WATCH_WITH, CHOOSE_STREAM, GAME_STATS, DETAILS, FOLLOW, FOLLOW),
            entriesFor(pacers, liveNow, scores[pacers.streamKey])
        )
        // An unmatched live game: no Game stats.
        val unmatched = item("Hawks @ Spurs", NOW - HOUR)
        assertEquals(listOf(WATCH_WITH, CHOOSE_STREAM, DETAILS, FOLLOW, FOLLOW), entriesFor(unmatched, liveNow, null))
        // A channel: no Follow (no sides), no Game stats.
        assertEquals(listOf(WATCH_WITH, CHOOSE_STREAM, DETAILS), entriesFor(channel, liveNow, null))
        // An upcoming game on Upcoming: Details, Follow and Remind me.
        val upcoming = SportsLiveUiState(selectedTab = SportsLiveTab.UPCOMING)
        val game = item("Bulls @ Heat", NOW + 5 * HOUR, isLive = false)
        assertEquals(listOf(DETAILS, FOLLOW, FOLLOW, REMIND), entriesFor(game, upcoming, null))
        // Details is always there: the raw source text lives only there.
        assertTrue(sportsLiveMoreEntries(false, false, false, 0, false) == listOf(DETAILS))
    }

    // ---- Stage ----

    @Test
    fun `the stage keeps one size and at most two buttons for every row`() {
        assertEquals(360, SportsLiveStageLayout.WIDTH_DP)
        assertEquals(203, SportsLiveStageLayout.MEDIA_HEIGHT_DP)
        // Under the room left beside the list at 1080p (540 dp less the safe area, the header and its gap).
        assertTrue(SportsLiveStageLayout.HEIGHT_DP <= 540 - 27 - 27 - 40 - 8 - 8)
        val buttons = rows().map { SportsLiveStageLayout.buttons(it) } + listOf(SportsLiveStageLayout.buttons(null))
        buttons.forEach { assertEquals(listOf(SportsLiveStageButton.WATCH, SportsLiveStageButton.MORE), it) }
    }

    // ---- List ----

    @Test
    fun `on return Live Now opens at the top with the LIVE group showing (F3)`() {
        val keys = listOf("live1", "live2", "soon", "ch1", "ch2", "ch3", "ended")
        // A card near the top keeps focus.
        assertEquals("soon", sportsLiveReturnFocusKey(keys, "soon", rowsOnScreen = 4))
        // One further down hands focus to the first row: the list opens at its top.
        assertEquals("live1", sportsLiveReturnFocusKey(keys, "ch3", rowsOnScreen = 4))
        // A card gone or none: the first row.
        assertEquals("live1", sportsLiveReturnFocusKey(keys, "gone", rowsOnScreen = 4))
        assertEquals("live1", sportsLiveReturnFocusKey(keys, null, rowsOnScreen = 4))
        assertNull(sportsLiveReturnFocusKey(emptyList(), "x", rowsOnScreen = 4))
    }

    @Test
    fun `focus keeps about two rows from the list edges`() {
        // A 400 px list keeping 100 px: a row at 350 px (50 tall) scrolls by 100, one at 20 px back by 80.
        assertEquals(100f, sportsLiveKeepFromEdge(offset = 350f, size = 50f, containerSize = 400f, margin = 100f), 0f)
        assertEquals(-80f, sportsLiveKeepFromEdge(offset = 20f, size = 50f, containerSize = 400f, margin = 100f), 0f)
        assertEquals(0f, sportsLiveKeepFromEdge(offset = 150f, size = 50f, containerSize = 400f, margin = 100f), 0f)
        // A row taller than the band aligns to the top margin.
        assertEquals(-50f, sportsLiveKeepFromEdge(offset = 50f, size = 300f, containerSize = 400f, margin = 100f), 0f)
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val HOUR = 3_600_000L
        const val MINUTE = 60_000L
    }
}
