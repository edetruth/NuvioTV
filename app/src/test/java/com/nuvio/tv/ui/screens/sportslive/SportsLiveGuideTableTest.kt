package com.nuvio.tv.ui.screens.sportslive

import android.view.KeyEvent
import com.nuvio.tv.TestPreferencesStore
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sportslive.GameStatus
import com.nuvio.tv.core.sportslive.GameStatusStrings
import com.nuvio.tv.core.sportslive.SportsLiveGuideStyle
import com.nuvio.tv.core.sportslive.SportsLiveReadyMark
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.ChipScore
import com.nuvio.tv.core.sportslive.scores.GameArt
import com.nuvio.tv.core.sportslive.scores.GameArtTeam
import com.nuvio.tv.core.sportslive.scores.ScoreChip
import com.nuvio.tv.core.sportslive.scores.ScoreChipStrings
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.SportsLivePreferencesDataStore
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** live.21, design B2: the in-player guide's rows, marks, keys, selection, idle close, squeeze and its setting. */
@OptIn(ExperimentalCoroutinesApi::class)
class SportsLiveGuideTableTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    private val art = GameArt(
        GameArtTeam("Orlando Magic", "ORL", null, shortName = "Magic"),
        GameArtTeam("Philadelphia 76ers", "PHI", null, shortName = "76ers"),
        "NBA", null, false
    )

    private val live = ScoreboardRow(
        item = game("m", now - 90 * minute), group = ScoreboardGroup.LIVE,
        chip = ScoreChip.Live("Q4 2:31", ChipScore(97, 92)), art = art,
        firstFollowed = false, secondFollowed = false, probablyEnded = false,
        status = GameStatus.Live(null), twoSided = true
    )

    // ---- The row model ----

    @Test
    fun `a game row has its tag, both teams longest name first, and score and time in one column`() {
        val row = guideRowOf(live, strings, kickoff = null, tag = "NBA")
        assertEquals(GuideRowKind.GAME, row.kind)
        assertEquals("NBA", row.tag)
        assertEquals(listOf("Orlando Magic", "Magic", "ORL"), row.firstNames)
        assertEquals(listOf("Philadelphia 76ers", "76ers", "PHI"), row.secondNames)
        assertEquals("97–92", row.score)
        assertEquals("Q4 2:31", row.time)
        assertTrue(row.live)
        assertEquals(emptyList<GuideMark>(), row.marks)
        assertFalse(row.dimmedAtRest)
        assertNull(row.title)
    }

    @Test
    fun `the time puts the short period before the clock, and other rows keep their status words`() {
        val half = live.copy(chip = ScoreChip.Live("9:51 - 2nd Half", ChipScore(54, 61)))
        assertEquals("2H 9:51", guideRowOf(half, strings, null, "NCAAM").time)
        val soccer = live.copy(chip = ScoreChip.Live("67'", ChipScore(0, 2)))
        assertEquals("67'", guideRowOf(soccer, strings, null, "LIGUE 1").time)
        assertEquals("0–2", guideRowOf(soccer, strings, null, "LIGUE 1").score)

        val soon = live.copy(group = ScoreboardGroup.STARTING_SOON, chip = null, status = GameStatus.StartingSoon(now + 60 * minute, 1 to 0), startsIn = 1 to 0)
        val soonRow = guideRowOf(soon, strings, kickoff = "8:30 PM", tag = "NBA")
        assertEquals("8:30 PM · in 1 h", soonRow.time)
        assertNull(soonRow.score)
        assertFalse(soonRow.live)

        val final = live.copy(group = ScoreboardGroup.JUST_ENDED, chip = ScoreChip.Final(note = "FT", score = ChipScore(2, 1)))
        val finalRow = guideRowOf(final, strings, null, "EPL")
        assertEquals("2–1", finalRow.score)
        assertEquals("FT", finalRow.time)
        // Just ended rows are dimmed until selected, as in the panel.
        assertTrue(finalRow.dimmedAtRest)
    }

    @Test
    fun `an event or a channel is one title with its suffix, never two teams`() {
        val event = live.copy(
            item = game("t", now).let { it.copy(meta = it.meta.copy(name = "ATP 250 - Stockholm Open")) },
            chip = null, art = null, twoSided = false
        )
        val row = guideRowOf(event, strings, null, "TENNIS", suffix = "Court 2")
        assertEquals(GuideRowKind.TITLE, row.kind)
        assertEquals(panelTitleOf(event.item), row.title)
        assertEquals("Court 2", row.suffix)
        assertEquals(emptyList<String>(), row.firstNames)
    }

    @Test
    fun `an unmatched side keeps its full name first, then ESPN's code or a derived one, never a cut name`() {
        val side = GameArtTeam("Maryland Eastern Shore", "Maryland Eastern Shore", null)
        assertEquals(listOf("Maryland Eastern Shore", "MES"), guideNameChoices(side, individual = false))
        assertEquals(listOf("Maryland Eastern Shore", "UMES"), guideNameChoices(side, individual = false) { "UMES" })
        // A player steps down to the last name.
        val player = GameArtTeam("Novak Djokovic", "Novak Djokovic", null)
        assertEquals(listOf("Novak Djokovic", "Djokovic"), guideNameChoices(player, individual = true))
    }

    @Test
    fun `the stopwatch shows only beside a score that shows`() {
        val close = live.copy(closeGame = true)
        assertEquals(listOf(GuideMark.CLOSE_LATE), guideRowOf(close, strings, null, "NBA").marks)
        // Hide until focused: no number, "Score hidden", and no hint of how close it is.
        val hidden = guideRowOf(close.copy(chip = ScoreChip.Hidden), strings, null, "NBA")
        assertEquals(emptyList<GuideMark>(), hidden.marks)
        assertNull(hidden.score)
        assertTrue(hidden.scoreHidden)
        assertEquals("Score hidden", hidden.time)
    }

    @Test
    fun `the star marks my team and the Ready dot shows only for a ready game of mine, never on Now playing`() {
        val mine = live.copy(firstFollowed = true)
        assertEquals(listOf(GuideMark.MY_TEAM), guideRowOf(mine, strings, null, "NBA").marks)
        assertEquals(
            listOf(GuideMark.MY_TEAM, GuideMark.READY),
            guideRowOf(mine, strings, null, "NBA", readyMark = SportsLiveReadyMark.READY).marks
        )
        assertEquals(listOf(GuideMark.MY_TEAM), guideRowOf(mine, strings, null, "NBA", readyMark = SportsLiveReadyMark.READY, nowPlaying = true).marks)
        // No stream found: no mark, the row dimmed at rest instead.
        val none = guideRowOf(live, strings, null, "NBA", readyMark = SportsLiveReadyMark.NO_STREAM)
        assertEquals(emptyList<GuideMark>(), none.marks)
        assertTrue(none.dimmedAtRest)
        // All three, in drawing order.
        assertEquals(
            listOf(GuideMark.CLOSE_LATE, GuideMark.MY_TEAM, GuideMark.READY),
            guideMarks(closeLate = true, myTeam = true, ready = SportsLiveReadyMark.READY, nowPlaying = false)
        )
    }

    @Test
    fun `Now playing draws the star beside the followed team's line, not always the first`() {
        // #212: the second team is followed, so the star sits on the second line; the stopwatch stays on the first.
        val second = guideRowOf(live.copy(secondFollowed = true, closeGame = true), strings, null, "NBA", nowPlaying = true)
        assertEquals(listOf(GuideMark.CLOSE_LATE) to listOf(GuideMark.MY_TEAM), guideNowPlayingMarks(second))
        val first = guideRowOf(live.copy(firstFollowed = true), strings, null, "NBA", nowPlaying = true)
        assertEquals(listOf(GuideMark.MY_TEAM) to emptyList<GuideMark>(), guideNowPlayingMarks(first))
        val both = guideRowOf(live.copy(firstFollowed = true, secondFollowed = true), strings, null, "NBA", nowPlaying = true)
        assertEquals(listOf(GuideMark.MY_TEAM) to listOf(GuideMark.MY_TEAM), guideNowPlayingMarks(both))
        // Only the card says a followed team plays (no side known): the first line, as before.
        val cardOnly = guideRowOf(live.copy(item = live.item.copy(followed = true)), strings, null, "NBA", nowPlaying = true)
        assertEquals(listOf(GuideMark.MY_TEAM) to emptyList<GuideMark>(), guideNowPlayingMarks(cardOnly))
        assertEquals(emptyList<GuideMark>() to emptyList<GuideMark>(), guideNowPlayingMarks(guideRowOf(live, strings, null, "NBA")))
    }

    @Test
    fun `the first openings' legend names Hold OK More first, then the three marks, and is gone after`() {
        assertEquals(
            listOf(GuideLegend.HOLD_OK, GuideLegend.CLOSE_LATE, GuideLegend.MY_TEAM, GuideLegend.READY),
            guideLegend(shown = true)
        )
        assertEquals(emptyList<GuideLegend>(), guideLegend(shown = false))
    }

    @Test
    fun `a row keeps ESPN's reading while its match is briefly missing, then the fresh one, and a new match wins at once`() {
        val memory = GuideRowMemory(holdMs = 120_000L)
        val espnArt = GameArt(
            GameArtTeam("Air Force Falcons", "AF", null, shortName = "Air Force"),
            GameArtTeam("UTEP Miners", "UTEP", null, shortName = "UTEP"), "NCAAM", null, false
        )
        val cardArt = GameArt(GameArtTeam("Air Force", "Air Force", null), GameArtTeam("UTEP", "UTEP", null), null, null, false)
        val espn = guideRowOf(
            live.copy(group = ScoreboardGroup.STARTING_SOON, chip = null, art = espnArt, status = GameStatus.StartingSoon(now + 7 * minute, 0 to 7), startsIn = 0 to 7),
            strings, kickoff = "7:00 PM", tag = "NCAAM"
        )
        val card = guideRowOf(live.copy(chip = null, art = cardArt), strings, kickoff = null, tag = "NCAAM", readyMark = SportsLiveReadyMark.READY)
        assertEquals(espn, memory.stable(espn, matched = true, nowMs = 0L))
        // The match missing a poll: still ESPN's names and status, with the fresh row's Ready.
        val held = memory.stable(card, matched = false, nowMs = 30_000L)
        assertEquals(espn.firstNames, held.firstNames)
        assertEquals("7:00 PM · in 7 min", held.time)
        assertEquals(listOf(GuideMark.READY), held.marks)
        // Gone for longer than the hold: the card's own reading.
        assertEquals(card, memory.stable(card, matched = false, nowMs = 200_000L))
        // A row never matched is drawn as it is.
        assertEquals(card, GuideRowMemory().stable(card, matched = false, nowMs = 0L))
        // A new match replaces the held one at once.
        val later = espn.copy(time = "6:58 PM · in 5 min")
        assertEquals(later, memory.stable(later, matched = true, nowMs = 210_000L))
    }

    // ---- Selection ----

    private fun row(id: String, group: ZapPanelGroup = ZapPanelGroup.LIVE_NOW) = ZapPanelRow(game(id, now), group, chip = null, art = null)

    @Test
    fun `the selection opens on the game down would zap to, past games over, wrapping`() {
        val lineup = listOf(row("a"), row("b"), row("c", ZapPanelGroup.JUST_ENDED), row("d"))
        val key = { id: String -> lineup.first { it.item.id == id }.item.streamKey }
        fun opening(current: String?) = panelGuide(lineup, current?.let(key)).let { guideOpeningKey(guideLines(it), lineup, current?.let(key)) }
        assertEquals(key("b"), opening("a"))
        // c ended: the zap passes it.
        assertEquals(key("d"), opening("b"))
        assertEquals(key("a"), opening("d"))
        // The game playing unknown: the first line.
        assertEquals(key("a"), opening(null))
        // No headers in the table's lines.
        assertTrue(guideLines(panelGuide(lineup, key("a"))).none { it is PanelGuideEntry.Header })
    }

    @Test
    fun `sport chips that do not fit whole are left for the arrow, never cut mid-word`() {
        // #213: "Basketb" was cut at the edge. Four chips of 100 px, 10 px gaps, 20 px arrows, 340 px of room.
        val widths = listOf(100, 100, 100, 100)
        // The first picked: two chips and the arrow after them (240 px); a third would need 350.
        assertEquals(0..1, guideChipsFit(widths, 0, 340, arrow = 20, gap = 10, hiddenBefore = false, hiddenAfter = false))
        // The last picked: the run moves on so it shows whole, with an arrow before.
        assertEquals(2..3, guideChipsFit(widths, 3, 340, arrow = 20, gap = 10, hiddenBefore = false, hiddenAfter = false))
        // All fit: no arrow needed.
        assertEquals(0..3, guideChipsFit(widths, 1, 430, arrow = 20, gap = 10, hiddenBefore = false, hiddenAfter = false))
        // Even the picked one alone is too wide: it alone (cut with an ellipsis).
        assertEquals(1..1, guideChipsFit(widths, 1, 60, arrow = 20, gap = 10, hiddenBefore = false, hiddenAfter = false))
        assertTrue(guideChipsFit(emptyList(), 0, 100, arrow = 20, gap = 10, hiddenBefore = false, hiddenAfter = false).isEmpty())
    }

    @Test
    fun `the Live games table lists no finished game, so a chip's count equals its rows`() {
        // #213: two live games and two finals under Basketball: the chip said 2, the table listed 4.
        val lineup = listOf(row("a"), row("b"), row("c", ZapPanelGroup.JUST_ENDED), row("d", ZapPanelGroup.JUST_ENDED))
        val lines = guideLines(panelGuide(lineup, currentKey = null))
        assertEquals(listOf("a", "b"), lines.map { (it as PanelGuideEntry.Game).row.item.id })
        assertEquals(lineup.count { it.zappable }, lines.size)
        // A game that ended while the guide is open, still placed under Live, is not listed either.
        val endedWhileOpen = row("e", ZapPanelGroup.JUST_ENDED).copy(placed = ScoreboardGroup.LIVE)
        assertEquals(2, guideLines(panelGuide(lineup + endedWhileOpen, currentKey = null)).size)
    }

    @Test
    fun `Now playing is selectable above the table, OK on it only closes the guide and the table window ignores it`() {
        val lineup = listOf(row("a"), row("b"), row("c"))
        val lines = guideLines(panelGuide(lineup, lineup[0].item.streamKey))
        val keys = guideSelectableKeys(nowPlaying = true, lines = lines)
        assertEquals(listOf(GUIDE_NOW_PLAYING_KEY) + lines.map { it.key }, keys)
        // Up from the table's first row reaches Now playing, and no further.
        assertEquals(0, guideMove(1, -1, keys.size))
        assertEquals(0, guideMove(0, -1, keys.size))
        assertEquals(-1, guideTableIndex(0, nowPlaying = true))
        assertEquals(0, guideTableIndex(1, nowPlaying = true))
        assertEquals(1, guideTableIndex(1, nowPlaying = false))
        // Now playing selected: the table keeps its window.
        assertEquals(0, guideWindowStart(0, guideTableIndex(0, nowPlaying = true), lines.size))
        // Without a known game playing, only the table.
        assertEquals(lines.map { it.key }, guideSelectableKeys(nowPlaying = false, lines = lines))

        assertEquals(GuideOkAction.CLOSE, guideOkAction(GUIDE_NOW_PLAYING_KEY, null))
        assertEquals(GuideOkAction.PLAY, guideOkAction(lines[0].key, lines[0]))
        assertEquals(GuideOkAction.SHOW_ALL_SPORTS, guideOkAction(HIDDEN_BY_MY_SPORTS_KEY, PanelGuideEntry.HiddenByMySports(3)))
        assertEquals(GuideOkAction.NONE, guideOkAction(null, null))
    }

    @Test
    fun `moves stop at the ends, the selection follows its key across updates, and the window scrolls as little as it can`() {
        assertEquals(0, guideMove(0, -1, 7))
        assertEquals(6, guideMove(5, 5, 7))
        assertEquals(-1, guideMove(0, 1, 0))

        assertEquals(2, guideReselect(listOf("a", "b", "c"), "c", 0))
        // Gone: the row now where it was.
        assertEquals(1, guideReselect(listOf("a", "c"), "b", 1))
        assertEquals(1, guideReselect(listOf("a", "c"), "b", 5))
        assertEquals(-1, guideReselect(emptyList(), "b", 1))

        assertEquals(0, guideWindowStart(0, 4, 12))
        assertEquals(1, guideWindowStart(0, 5, 12))
        assertEquals(3, guideWindowStart(5, 3, 12))
        assertEquals(7, guideWindowStart(9, 11, 12))
        // A short list never scrolls.
        assertEquals(0, guideWindowStart(3, 2, 4))
    }

    @Test
    fun `left and right step through the sport chips without wrapping, and the picked chip always shows`() {
        val chips = listOf(SportsLiveUiState.MY_TEAMS_FILTER, null, SportsLiveUiState.ALL_SPORTS_FILTER, "Basketball", "Soccer")
        assertEquals(2, guideChipStep(chips, null, 1))
        assertEquals(0, guideChipStep(chips, null, -1))
        assertNull(guideChipStep(chips, SportsLiveUiState.MY_TEAMS_FILTER, -1))
        assertNull(guideChipStep(chips, "Soccer", 1))
        assertEquals(0 until 3, guideChipWindow(3, 2))
        assertEquals(1 until 5, guideChipWindow(6, 3))
        assertEquals(2 until 6, guideChipWindow(6, 5))
    }

    @Test
    fun `the star is only on My teams, My sports reads bare, and the other chips carry their counts`() {
        assertEquals("My sports", guideChipText(null, "My sports", 12, scoped = true))
        assertEquals("All 25", guideChipText(null, "All", 25, scoped = false))
        assertEquals("★ My teams 2", guideChipText(SportsLiveUiState.MY_TEAMS_FILTER, "My teams", 2, scoped = true))
        assertEquals("All sports 25", guideChipText(SportsLiveUiState.ALL_SPORTS_FILTER, "All sports", 25, scoped = true))
        assertEquals("Soccer", guideChipText("Soccer", "Soccer", null, scoped = true))
    }

    // ---- Keys ----

    private val keys = SportsLiveGuideKeys(longPressMs = 500L, repeatGateMs = 100L)
    private fun key(code: Int, action: Int = KeyEvent.ACTION_DOWN, repeat: Int = 0, atMs: Long = 0L) = keys.onKey(code, action, repeat, atMs)

    @Test
    fun `OK watches on its release, a held OK opens the actions once, and a release the guide never saw does nothing`() {
        // The release of the OK that opened the guide.
        assertEquals(SportsLiveGuideKey.CONSUMED, key(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_UP, atMs = 10L))
        assertEquals(SportsLiveGuideKey.CONSUMED, key(KeyEvent.KEYCODE_DPAD_CENTER, atMs = 100L))
        assertEquals(SportsLiveGuideKey.WATCH, key(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_UP, atMs = 200L))
        // Held: the repeats open the actions once; the release then does nothing.
        assertEquals(SportsLiveGuideKey.CONSUMED, key(KeyEvent.KEYCODE_ENTER, atMs = 1_000L))
        assertEquals(SportsLiveGuideKey.MORE, key(KeyEvent.KEYCODE_ENTER, repeat = 1, atMs = 1_500L))
        assertEquals(SportsLiveGuideKey.CONSUMED, key(KeyEvent.KEYCODE_ENTER, repeat = 2, atMs = 1_550L))
        assertEquals(SportsLiveGuideKey.CONSUMED, key(KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_UP, atMs = 1_600L))
        // Held past the long press with no repeats: the actions on release.
        assertEquals(SportsLiveGuideKey.CONSUMED, key(KeyEvent.KEYCODE_DPAD_CENTER, atMs = 2_000L))
        assertEquals(SportsLiveGuideKey.MORE, key(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_UP, atMs = 2_600L))
        assertEquals(SportsLiveGuideKey.MORE, key(KeyEvent.KEYCODE_MENU))
    }

    @Test
    fun `up and down move at a steady pace, CH pages, left and right change the sport once per press, Back passes`() {
        assertEquals(SportsLiveGuideKey.DOWN, key(KeyEvent.KEYCODE_DPAD_DOWN, atMs = 0L))
        assertEquals(SportsLiveGuideKey.CONSUMED, key(KeyEvent.KEYCODE_DPAD_DOWN, repeat = 1, atMs = 50L))
        assertEquals(SportsLiveGuideKey.DOWN, key(KeyEvent.KEYCODE_DPAD_DOWN, repeat = 2, atMs = 120L))
        assertEquals(SportsLiveGuideKey.CONSUMED, key(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_UP, atMs = 130L))
        assertEquals(SportsLiveGuideKey.UP, key(KeyEvent.KEYCODE_DPAD_UP, atMs = 500L))
        assertEquals(SportsLiveGuideKey.PAGE_DOWN, key(KeyEvent.KEYCODE_CHANNEL_DOWN, atMs = 1_000L))
        assertEquals(SportsLiveGuideKey.PAGE_UP, key(KeyEvent.KEYCODE_CHANNEL_UP, atMs = 2_000L))
        assertEquals(SportsLiveGuideKey.PREVIOUS_SPORT, key(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(SportsLiveGuideKey.CONSUMED, key(KeyEvent.KEYCODE_DPAD_LEFT, repeat = 1))
        assertEquals(SportsLiveGuideKey.CONSUMED, key(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.ACTION_UP))
        assertEquals(SportsLiveGuideKey.NEXT_SPORT, key(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(SportsLiveGuideKey.PASS, key(KeyEvent.KEYCODE_BACK))
        assertEquals(SportsLiveGuideKey.PASS, key(KeyEvent.KEYCODE_INFO))
    }

    @Test
    fun `with the guide closed and no menu offered OK opens it on its press, the rest of that press is taken, and other keys are left alone`() {
        val ok = SportsLivePlayerOkKey()
        val center = KeyEvent.KEYCODE_DPAD_CENTER
        fun key(code: Int, action: Int, repeat: Int, down: Long, guide: Boolean) = ok.onKey(code, action, repeat, down, guide = guide, menu = false)
        // adb's OK: down and up within milliseconds, one down time.
        assertEquals(SportsLivePlayerOk.OPEN_GUIDE, key(center, KeyEvent.ACTION_DOWN, 0, 100L, guide = true))
        assertEquals(SportsLivePlayerOk.CONSUMED, key(center, KeyEvent.ACTION_DOWN, 1, 100L, guide = false))
        assertEquals(SportsLivePlayerOk.CONSUMED, key(center, KeyEvent.ACTION_UP, 0, 100L, guide = false))
        // Its release was the last of it: a later release is not taken.
        assertEquals(SportsLivePlayerOk.NONE, key(center, KeyEvent.ACTION_UP, 0, 100L, guide = false))
        // A new press in the open guide is the guide's: never taken here, its release neither.
        assertEquals(SportsLivePlayerOk.NONE, key(center, KeyEvent.ACTION_DOWN, 0, 500L, guide = false))
        assertEquals(SportsLivePlayerOk.NONE, key(center, KeyEvent.ACTION_UP, 0, 500L, guide = false))
        // Enter opens it too.
        assertEquals(SportsLivePlayerOk.OPEN_GUIDE, key(KeyEvent.KEYCODE_ENTER, KeyEvent.ACTION_DOWN, 0, 900L, guide = true))
        // Zapping keys are never OK's.
        assertEquals(SportsLivePlayerOk.NONE, key(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_DOWN, 0, 1_000L, guide = true))
        assertEquals(SportsLivePlayerOk.NONE, key(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.ACTION_DOWN, 0, 1_000L, guide = true))
    }

    @Test
    fun `with the player menu offered a short OK acts on its release and a held one opens the menu, never both`() {
        val ok = SportsLivePlayerOkKey()
        val center = KeyEvent.KEYCODE_DPAD_CENTER
        fun key(action: Int, repeat: Int, down: Long, guide: Boolean = true) = ok.onKey(center, action, repeat, down, guide = guide, menu = true)
        // #218: short, with the guide on: it opens on the release.
        assertEquals(SportsLivePlayerOk.CONSUMED, key(KeyEvent.ACTION_DOWN, 0, 100L))
        assertEquals(SportsLivePlayerOk.OPEN_GUIDE, key(KeyEvent.ACTION_UP, 0, 100L))
        // Short, with the side panel style: the player's own OK on the release.
        assertEquals(SportsLivePlayerOk.CONSUMED, key(KeyEvent.ACTION_DOWN, 0, 200L, guide = false))
        assertEquals(SportsLivePlayerOk.SHORT, key(KeyEvent.ACTION_UP, 0, 200L, guide = false))
        // Held: the snapshot at the first repeat, once; the menu on the release; nothing else.
        assertEquals(SportsLivePlayerOk.CONSUMED, key(KeyEvent.ACTION_DOWN, 0, 300L))
        assertEquals(SportsLivePlayerOk.HOLD, key(KeyEvent.ACTION_DOWN, 1, 300L))
        assertEquals(SportsLivePlayerOk.CONSUMED, key(KeyEvent.ACTION_DOWN, 2, 300L))
        assertEquals(SportsLivePlayerOk.MENU, key(KeyEvent.ACTION_UP, 0, 300L))
        // A repeat or release of another press is not taken.
        assertEquals(SportsLivePlayerOk.NONE, key(KeyEvent.ACTION_DOWN, 1, 400L))
        assertEquals(SportsLivePlayerOk.NONE, key(KeyEvent.ACTION_UP, 0, 400L))
        // Neither offered: the player's, as always.
        assertEquals(SportsLivePlayerOk.NONE, ok.onKey(center, KeyEvent.ACTION_DOWN, 0, 500L, guide = false, menu = false))
    }

    // ---- Idle close ----

    @Test
    fun `the guide closes after 20 s with no key, a key restarts the wait, and stop cancels it`() = runTest {
        var closed = 0
        val idle = SportsLiveGuideIdle(this, GUIDE_IDLE_CLOSE_MS) { closed++ }
        idle.kick()
        advanceTimeBy(GUIDE_IDLE_CLOSE_MS - 1_000L)
        runCurrent()
        assertEquals(0, closed)
        idle.kick()
        advanceTimeBy(GUIDE_IDLE_CLOSE_MS - 1_000L)
        runCurrent()
        assertEquals(0, closed)
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(1, closed)
        assertFalse(idle.running)

        idle.kick()
        assertTrue(idle.running)
        idle.stop()
        advanceTimeBy(GUIDE_IDLE_CLOSE_MS * 2)
        runCurrent()
        assertEquals(1, closed)
        assertFalse(idle.running)
    }

    // ---- The squeeze ----

    @Test
    fun `the video window sits top right inside the safe area at 40 percent, and the squeeze lands on it`() {
        val window = guideVideoWindow(1920f, 1080f)
        assertEquals(1056f, window.left, 0.01f)
        assertEquals(54f, window.top, 0.01f)
        assertEquals(768f, window.width, 0.01f)
        assertEquals(432f, window.height, 0.01f)

        // Scaled about the top-right corner, then moved: the full frame's corners land on the window's.
        val end = guideSqueezeTransform(1f, 1920f, 1080f)
        assertEquals(window.left, 1920f + (0f - 1920f) * end.scale + end.translationX, 0.01f)
        assertEquals(window.right, 1920f + end.translationX, 0.01f)
        assertEquals(window.top, 0f * end.scale + end.translationY, 0.01f)
        assertEquals(window.bottom, 1080f * end.scale + end.translationY, 0.01f)
        val start = guideSqueezeTransform(0f, 1920f, 1080f)
        assertEquals(1f, start.scale, 0f)
        assertEquals(0f, start.translationX, 0f)
        assertEquals(0f, start.translationY, 0f)
        assertEquals(guideSqueezeTransform(1f, 1920f, 1080f), guideSqueezeTransform(3f, 1920f, 1080f))
        // One build compares both on the TV: the setting picks the squeeze; the side panel has none.
        assertNull(guideSqueezeModeOf(SportsLiveGuideStyle.PANEL))
        assertEquals(GuideSqueezeMode.SCALE, guideSqueezeModeOf(SportsLiveGuideStyle.TABLE_SMOOTH))
        assertEquals(GuideSqueezeMode.JUMP, guideSqueezeModeOf(SportsLiveGuideStyle.TABLE_JUMP))
        assertEquals(GUIDE_SQUEEZE_MS, guideFadeMs(GuideSqueezeMode.SCALE))
        assertEquals(GUIDE_FADE_MS, guideFadeMs(GuideSqueezeMode.JUMP))
    }

    // ---- The setting ----

    @Test
    fun `the guide setting is the smooth table by default and its choice is stored per profile`() = runTest {
        assertEquals(SportsLiveGuideStyle.TABLE_SMOOTH, SportsLiveGuideStyle.DEFAULT)
        assertTrue(SportsLiveGuideStyle.DEFAULT.table)
        assertTrue(SportsLiveGuideStyle.TABLE_SMOOTH.table && SportsLiveGuideStyle.TABLE_JUMP.table)
        assertEquals(SportsLiveGuideStyle.TABLE_SMOOTH, SportsLiveGuideStyle.fromStored("SOMETHING_ELSE"))
        assertEquals(SportsLiveGuideStyle.PANEL, SportsLiveGuideStyle.fromStored("PANEL"))
        // A three-way picker in Settings, not an On/Off switch.
        assertFalse(com.nuvio.tv.ui.screens.settings.SportsLiveSetting.GUIDE_TABLE.isSwitch)
        val store1 = TestPreferencesStore()
        val store2 = TestPreferencesStore()
        val active = MutableStateFlow(1)
        val factory = mockk<ProfileDataStoreFactory> {
            every { get(1, SportsLivePreferencesDataStore.FEATURE) } returns store1
            every { get(2, SportsLivePreferencesDataStore.FEATURE) } returns store2
        }
        val profiles = mockk<ProfileManager> { every { activeProfileId } returns active }
        val prefs = SportsLivePreferencesDataStore(factory, profiles)
        assertEquals(SportsLiveGuideStyle.TABLE_SMOOTH, prefs.guideStyle.first())
        prefs.setGuideStyle(SportsLiveGuideStyle.TABLE_JUMP)
        assertEquals(SportsLiveGuideStyle.TABLE_JUMP, prefs.guideStyle.first())
        active.value = 2
        assertEquals(SportsLiveGuideStyle.TABLE_SMOOTH, prefs.guideStyle.first())
    }

    private val strings = GameStatusStrings(
        liveClock = "● %1\$s", live = "LIVE", clockPeriod = "%1\$s · %2\$s", timeCountdown = "%1\$s · %2\$s",
        inHoursMinutes = "in %1\$d h %2\$d m", inHours = "in %1\$d h", inMinutes = "in %1\$d min",
        final = "Final", fullTime = "FT", probablyEnded = "Probably ended", postponed = "Postponed",
        chip = ScoreChipStrings("%1\$d–%2\$d", "%1\$s · %2\$s", "%1\$s %2\$s", "Final", "Postponed", "Score hidden")
    )

    private fun game(id: String, startMs: Long?, sport: String = "Basketball") = SportsLiveItem(
        meta = MetaPreview(
            id = id, type = ContentType.UNKNOWN, rawType = "tv", name = "Team $id vs Other $id", poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = "addon", addonName = "Sports", addonBaseUrl = "https://example.invalid", catalogKey = "addon:tv:live",
        catalogName = "Live", kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false,
        startMs = startMs, sport = sport, startHasTimeOfDay = startMs != null,
        teams = SportsLiveTeams.parse("Team $id vs Other $id")
    )
}
