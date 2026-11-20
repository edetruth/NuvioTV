package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.displayName
import com.nuvio.tv.core.sportslive.EndedGame
import com.nuvio.tv.core.sportslive.EndedSignal
import com.nuvio.tv.core.sportslive.GameStatus
import com.nuvio.tv.core.sportslive.GameStatusStrings
import com.nuvio.tv.core.sportslive.SportsLiveReadyMark
import com.nuvio.tv.core.sportslive.SportsLiveSportKind
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.data.local.PANEL_KEYS_HINT_GAP_MS
import com.nuvio.tv.data.local.PANEL_KEYS_HINT_OPENS
import com.nuvio.tv.data.local.shouldShowPanelKeysHint
import com.nuvio.tv.core.sportslive.scores.ChipScore
import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.GameArt
import com.nuvio.tv.core.sportslive.scores.GameArtTeam
import com.nuvio.tv.core.sportslive.scores.ScoreChip
import com.nuvio.tv.core.sportslive.scores.ScoreChipStrings
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.ui.screens.sportslive.SportsLiveMoreEntry.CHOOSE_STREAM
import com.nuvio.tv.ui.screens.sportslive.SportsLiveMoreEntry.FOLLOW
import com.nuvio.tv.ui.screens.sportslive.SportsLiveMoreEntry.GAME_STATS
import com.nuvio.tv.ui.screens.sportslive.SportsLiveMoreEntry.REPORT
import com.nuvio.tv.ui.screens.sportslive.SportsLiveMoreEntry.WATCH_WITH
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #140 (the slim in-player panel) and #142 (CH+/CH- walk the panel's own list). */
class SportsLiveSlimPanelTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    // ---- #142: one ordered list for the panel and the zaps ----

    // Live: a, and f (a followed team, so first); starting soon: s2 then s1; later: u; a channel; a game just ended.
    private val a = game("a", now - 90 * minute)
    private val f = game("f", now - 30 * minute).copy(followed = true)
    private val s1 = game("s1", now + 60 * minute)
    private val s2 = game("s2", now + 30 * minute)
    private val u = game("u", now + 5 * 60 * minute)
    private val ch = game("ch", null).copy(isChannel = true, meta = game("ch", null).meta.copy(name = "Sports Channel HD"))
    private val e = game("e", now - 60 * minute)
    private val soccer1 = game("o1", now - 20 * minute, sport = "Soccer")
    private val soccer2 = game("o2", now + 20 * minute, sport = "Soccer")

    private val ended = mapOf(e.streamKey to EndedGame(EndedSignal.ESTIMATE, now))

    /** The panel's rows for [items] under [chip], as the player builds them. */
    private fun lineupOf(items: List<SportsLiveItem>, chip: String? = null): List<ZapPanelRow> =
        panelLineup(groupPanelRows(filterPanelItems(items, chip), emptyMap(), ScoresMode.OFF, emptySet(), now, ended))

    private val all = listOf(e, ch, u, s1, a, s2, f, soccer1, soccer2)

    /** The row the next (or previous) zap must land on from row [index]: the nearest zappable row that way, wrapping. */
    private fun expected(rows: List<ZapPanelRow>, index: Int, forward: Boolean): String {
        var at = index
        repeat(rows.size) {
            at = Math.floorMod(at + if (forward) 1 else -1, rows.size)
            if (rows[at].zappable) return rows[at].item.id
        }
        error("no target")
    }

    private fun sessionOn(item: SportsLiveItem, rows: List<ZapPanelRow>, chip: String? = null) =
        SportsLiveZapSession.of(all, item.streamKey, SportsLiveTab.LIVE_NOW, null, returnToHomeOnBack = false)!!
            .copy(panelChip = chip?.let { ZapPanelChip(it) })
            .withLineup(rows)

    @Test
    fun `the panel lists by V2 group with followed games first, and the zap order is that same list`() {
        val rows = lineupOf(all)
        assertEquals(all.size, rows.size)
        // Followed first in the live group; soonest first in starting soon; the channel; the game just ended last.
        assertEquals("f", rows.first().item.id)
        assertEquals(ScoreboardGroup.JUST_ENDED, rows.last().section)
        assertEquals("e", rows.last().item.id)
        assertEquals(rows.map { it.section }, rows.map { it.section }.sortedBy { it.ordinal })
        assertEquals(ScoreboardGroup.CHANNELS, rows.first { it.item == ch }.section)

        val session = sessionOn(a, rows)
        assertEquals(rows.map { it.item.streamKey }, session.zapOrder.map { session.items[it].streamKey })
        // The banner's position is the row's place in the panel; "of N" counts the games CH stops on (#145),
        // so the game just ended is not counted.
        assertEquals(rows.indexOfFirst { it.item == a } + 1 to rows.count { it.zappable }, session.positionLabelArgs)
    }

    @Test
    fun `the next zap from any game lands on the row directly below it and the previous on the row above, across groups`() {
        val rows = lineupOf(all)
        rows.forEachIndexed { index, row ->
            val session = sessionOn(row.item, rows)
            assertEquals("next from ${row.item.id}", expected(rows, index, forward = true), session.step(true, emptySet())!!.current.id)
            assertEquals("previous from ${row.item.id}", expected(rows, index, forward = false), session.step(false, emptySet())!!.current.id)
        }
    }

    @Test
    fun `CH minus and down go to the next game, the row below, and CH plus and up to the row above`() {
        assertEquals(true, zapDirection(android.view.KeyEvent.KEYCODE_CHANNEL_DOWN, partyActive = false))
        assertEquals(true, zapDirection(android.view.KeyEvent.KEYCODE_DPAD_DOWN, partyActive = false))
        assertEquals(false, zapDirection(android.view.KeyEvent.KEYCODE_CHANNEL_UP, partyActive = false))
        assertEquals(false, zapDirection(android.view.KeyEvent.KEYCODE_DPAD_UP, partyActive = false))
        // So CH- from a row lands on the row below it in the panel, CH+ on the row above.
        val rows = lineupOf(all)
        val index = rows.indexOfFirst { it.item == a }
        val session = sessionOn(a, rows)
        val chMinus = zapDirection(android.view.KeyEvent.KEYCODE_CHANNEL_DOWN, false)!!
        val chPlus = zapDirection(android.view.KeyEvent.KEYCODE_CHANNEL_UP, false)!!
        assertEquals(rows[index + 1].item.id, session.step(chMinus, emptySet())!!.current.id)
        assertEquals(rows[index - 1].item.id, session.step(chPlus, emptySet())!!.current.id)
    }

    @Test
    fun `with a chip selected the zaps walk only that chip's rows, in the panel's order`() {
        val soccer = sportKey("Soccer")
        val rows = lineupOf(all, soccer)
        assertEquals(listOf("o1", "o2"), rows.map { it.item.id })
        rows.forEachIndexed { index, row ->
            val session = sessionOn(row.item, rows, soccer)
            assertEquals(expected(rows, index, forward = true), session.step(true, emptySet())!!.current.id)
            assertEquals(expected(rows, index, forward = false), session.step(false, emptySet())!!.current.id)
        }
        // The game playing is not under the chip: the chip's first game, or its last going back.
        val outside = sessionOn(a, rows, soccer)
        assertEquals("a", outside.current.id)
        assertEquals("o1", outside.step(true, emptySet())!!.current.id)
        assertEquals("o2", outside.step(false, emptySet())!!.current.id)
    }

    @Test
    fun `finished games stay listed in the panel but the zaps pass over them`() {
        val rows = lineupOf(all)
        val final = rows.single { it.item == e }
        assertFalse(final.zappable)
        // From the row above the final, next skips it (wrapping to the first row).
        val above = rows[rows.indexOf(final) - 1].item
        assertEquals(rows.first().item.id, sessionOn(above, rows).step(true, emptySet())!!.current.id)
        // Watching the final itself: next is the row below (wrapping), previous the row above.
        val onFinal = sessionOn(e, rows)
        assertEquals(rows.first().item.id, onFinal.step(true, emptySet())!!.current.id)
        assertEquals(above.id, onFinal.step(false, emptySet())!!.current.id)
        // A walk all the way round never stops on it.
        var at = sessionOn(f, rows)
        val seen = mutableListOf<String>()
        repeat(rows.size) {
            at = at.step(true, emptySet())!!
            seen += at.current.id
        }
        assertFalse("e" in seen)
    }

    @Test
    fun `an order change gives the panel and the zaps the same new order at once`() {
        val before = lineupOf(all)
        val session = sessionOn(a, before)
        assertEquals(expected(before, before.indexOfFirst { it.item == a }, true), session.step(true, emptySet())!!.current.id)

        // s2 goes live: it moves to the live group in the panel, and the next zap follows.
        val live = all.map { if (it == s2) it.copy(startMs = now - 5 * minute) else it }
        val after = lineupOf(live)
        assertEquals(ScoreboardGroup.LIVE, after.first { it.item.id == "s2" }.section)
        val moved = session.withLineup(after)
        assertEquals(after.map { it.item.streamKey }, moved.zapOrder.map { moved.items[it].streamKey })
        after.forEachIndexed { index, row ->
            assertEquals(expected(after, index, true), sessionOn(row.item, after).step(true, emptySet())!!.current.id)
        }
    }

    // ---- BUG6/Q10: the zap order is frozen once taken ----

    @Test
    fun `once taken the zap order is kept - a moved game keeps its place, a new one comes last, a gone one is passed over`() {
        val before = lineupOf(all)
        val keys = before.map { it.item.streamKey }
        val session = sessionOn(a, before)

        // s2 goes live (it would move up), u leaves the list and a new live game n appears.
        val n = game("n", now - 10 * minute)
        val after = lineupOf(all.map { if (it == s2) it.copy(startMs = now - 5 * minute) else it }.filterNot { it == u } + n)
        val frozen = session.withFrozenLineup(after)

        assertEquals(keys + n.streamKey, frozen.zapOrder.map { frozen.items[it].streamKey })
        assertEquals("a", frozen.current.id)
        assertTrue(u.streamKey in frozen.skipped)
        // Walking forward from a visits every game in the frozen order, never the gone one, and the new one last.
        var at = frozen
        val seen = mutableListOf<String>()
        repeat(frozen.items.size) {
            at = at.step(true, emptySet())!!
            seen += at.current.id
        }
        assertFalse("u" in seen)
        val zappable = (keys + n.streamKey).filter { key -> key != u.streamKey && key !in frozen.skipped }
        assertEquals(zappable.last(), (frozen.items.first { it.id == "n" }).streamKey)
        // The banner names exactly where the next ▲ and ▼ go.
        val banner = frozen.banner(switching = false)
        assertEquals(frozen.step(true, emptySet())!!.current.displayName, banner.next)
        assertEquals(frozen.step(false, emptySet())!!.current.displayName, banner.previous)
    }

    @Test
    fun `a session with no order yet takes the panel's as it is`() {
        val rows = lineupOf(all)
        val session = SportsLiveZapSession.of(all, a.streamKey, SportsLiveTab.LIVE_NOW, null, returnToHomeOnBack = false)!!
        assertEquals(session.withLineup(rows), session.withFrozenLineup(rows))
    }

    @Test
    fun `the zap order is taken afresh when the panel opens`() {
        val before = lineupOf(all)
        SportsLiveZapSessions.start(sessionOn(a, before))
        var rows = before
        val controller = PlayerZapController(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            resolve = { null },
            onNavigate = { _, _ -> },
            onNoStream = {},
            lineup = { rows }
        )
        // The followed game is no longer followed: the fresh list orders differently.
        rows = lineupOf(all.map { if (it == f) it.copy(followed = false) else it })
        assertEquals(before.map { it.item.streamKey }, controller.ordered(SportsLiveZapSessions.current()!!).lineup)

        controller.thaw(a.id)
        assertEquals(rows.map { it.item.streamKey }, controller.ordered(SportsLiveZapSessions.current()!!).lineup)
        SportsLiveZapSessions.clear()
    }

    @Test
    fun `while the panel is open the order shown is kept and the zaps keep it too`() {
        val (shown, order) = freezePanelLineup(lineupOf(all), null)
        val shownKeys = shown.map { it.item.streamKey }
        // A fresh order (the followed game is no longer followed) is held back while the panel is open (#164).
        val fresh = panelLineup(groupPanelRows(all.map { if (it == f) it.copy(followed = false) else it }, emptyMap(), ScoresMode.OFF, emptySet(), now, ended))
        val (frozen, _) = freezePanelLineup(fresh, order)
        assertEquals(shownKeys, frozen.map { it.item.streamKey })
        val session = sessionOn(a, frozen)
        assertEquals(shownKeys, session.zapOrder.map { session.items[it].streamKey })
    }

    @Test
    fun `a session from another tab keeps its own order`() {
        val channels = SportsLiveZapSession.of(listOf(ch, a), ch.streamKey, SportsLiveTab.CHANNELS, null, false)!!
        assertEquals(channels, channels.withLineup(lineupOf(all)))
    }

    // ---- #140: the slim panel ----

    @Test
    fun `the hold OK menu lists Watch both first, then Choose stream, Game stats, Follow and Report a problem`() {
        assertEquals(listOf(WATCH_WITH, CHOOSE_STREAM, GAME_STATS, FOLLOW, FOLLOW, REPORT), panelMenuEntries(true, true, true, sides = 2))
        assertEquals(listOf(CHOOSE_STREAM, FOLLOW, FOLLOW, REPORT), panelMenuEntries(false, true, false, sides = 2))
        assertEquals(WATCH_WITH, panelMenuEntries(true, false, false, sides = 0).first())
        assertEquals(listOf(REPORT), panelMenuEntries(false, false, false, sides = 0))
    }

    @Test
    fun `the panel has no Watch with another game button and shows the hold hint`() {
        val overlay = listOf(
            File("src/main/java/com/nuvio/tv/ui/screens/player/SportsLiveZapOverlay.kt"),
            File("app/src/main/java/com/nuvio/tv/ui/screens/player/SportsLiveZapOverlay.kt")
        ).first { it.exists() }.readText()
        assertFalse(overlay.contains("sports_live_dual_watch_with"))
        assertFalse(overlay.contains("SportsLiveDualPickerDialog"))
        assertTrue(overlay.contains("sports_live_panel_footer_hint"))
        assertTrue(overlay.contains("sports_live_watch_both"))
    }

    @Test
    fun `a filter option selected or under the cursor is never a blank pill, and each state looks different`() {
        val rest = sportsLiveFilterLook(selected = false, underCursor = false, gold = false)
        val cursor = sportsLiveFilterLook(selected = false, underCursor = true, gold = false)
        val selected = sportsLiveFilterLook(selected = true, underCursor = false, gold = false)
        val both = sportsLiveFilterLook(selected = true, underCursor = true, gold = false)
        listOf(rest, cursor, selected, both).forEach { look ->
            assertNotEquals(look.fill, look.text)
            assertNotEquals(SportsLiveTokens.Colors.FocusWhite, look.fill)
            assertTrue(look.text.alpha > 0f)
        }
        assertEquals(4, setOf(rest, cursor, selected, both).size)
        assertEquals(SportsLiveTokens.Colors.FollowedGold, sportsLiveFilterLook(true, true, gold = true).text)
    }

    @Test
    fun `the filter has no catch-all option that only repeats All`() {
        val generic = game("g", now - minute, sport = "Sports")
        assertEquals(listOf(null, sportKey("Basketball")), panelChips(listOf(a, generic), selected = null))
    }

    @Test
    fun `a panel row is one compact line - tag, logos, short names and score, then the clock`() {
        assertEquals(22.dp, SportsLiveTokens.Size.PanelLine)
        assertTrue(SportsLiveTokens.Size.PanelWidth in 270.dp..290.dp)

        val art = GameArt(GameArtTeam("Orlando Magic", "ORL", null), GameArtTeam("Philadelphia 76ers", "PHI", null), "NBA", null, false)
        val live = ScoreboardRow(
            item = game("m", now - 90 * minute), group = ScoreboardGroup.LIVE,
            chip = ScoreChip.Live("Q4 11:22", ChipScore(97, 92)), art = art,
            firstFollowed = false, secondFollowed = false, probablyEnded = false,
            status = GameStatus.Live(null), twoSided = true
        )
        val line = panelLineOf(live, strings, kickoff = null)
        assertEquals(PanelLine(PanelLineKind.GAME, "ORL", "PHI", ScoreboardScores.Shown(97, 92, leader = 0), clock = "11:22", period = "Q4", live = true), line)
        assertEquals(1, line.lines)

        val soon = live.copy(group = ScoreboardGroup.STARTING_SOON, chip = null, status = GameStatus.StartingSoon(now + 60 * minute, 1 to 0), startsIn = 1 to 0)
        assertEquals(PanelLine(PanelLineKind.GAME, "ORL", "PHI", clock = "8:30 PM"), panelLineOf(soon, strings, kickoff = "8:30 PM"))

        val event = live.copy(item = game("t", now).copy(meta = game("t", now).meta.copy(name = "ATP 250 - Stockholm Open")), chip = null, art = null, twoSided = false)
        val eventLine = panelLineOf(event, strings, kickoff = null)
        assertEquals(PanelLineKind.EVENT, eventLine.kind)
        // live.20 F5: no "LIVE" word on every row (the LIVE header says it), only the red dot.
        assertEquals(null, eventLine.clock)
        assertTrue(eventLine.live)

        val channel = live.copy(item = ch, group = ScoreboardGroup.CHANNELS, chip = null, art = null, twoSided = false, status = GameStatus.Channel)
        val channelLine = panelLineOf(channel, strings, kickoff = null)
        assertEquals(PanelLineKind.CHANNEL, channelLine.kind)
        assertFalse(channelLine.live)
        assertEquals(1, channelLine.lines)
    }

    @Test
    fun `every panel row leads with its compact sport or league tag, a two-team row too`() {
        // #167: "Ak Bars Kazan vs CSKA" is a known hockey game; its line says so.
        val art = GameArt(GameArtTeam("Ak Bars Kazan", "", null), GameArtTeam("CSKA Moscow", "", null), null, null, false)
        val hockey = ScoreboardRow(
            item = game("k", now - minute, sport = "Hockey"), group = ScoreboardGroup.LIVE, chip = null, art = art,
            firstFollowed = false, secondFollowed = false, probablyEnded = false,
            status = GameStatus.Live(null), twoSided = true
        )
        assertEquals("HOCKEY", rowTagOf(hockey, sportLabel = null))
        val line = panelLineOf(hockey, strings, kickoff = null, tag = rowTagOf(hockey, sportLabel = null))
        assertEquals(PanelLineKind.GAME, line.kind)
        assertEquals("HOCKEY", line.tag)

        // ESPN's league comes first.
        assertEquals("NBA", rowTagOf(hockey.copy(art = art.copy(league = "NBA")), sportLabel = "Hockey"))
        // A non-sport addon tag says nothing: the sport the title tells, else no tag.
        val other = hockey.copy(item = game("o", now - minute, sport = "OTHER"), sportTag = SportsLiveSportKind.HOCKEY)
        assertEquals("HOCKEY", rowTagOf(other, sportLabel = "Hockey"))
        assertEquals("", rowTagOf(other.copy(sportTag = null), sportLabel = null))
        // A catch-all addon sport ("Other Sports") is no tag ("OS"): the cricket the title tells is.
        val catchAll = hockey.copy(item = game("c", now - minute, sport = "Other Sports"), sportTag = SportsLiveSportKind.CRICKET)
        assertEquals("CRICKET", rowTagOf(catchAll, sportLabel = "Cricket"))
        // The same as a league on an unmatched game's art.
        val catchAllArt = catchAll.copy(art = art.copy(league = "Other Sports"))
        assertEquals("CRICKET", rowTagOf(catchAllArt, sportLabel = "Cricket"))

        // Score hidden: the eye shows between the names, and no "Score hidden" words push them out.
        val hidden = hockey.copy(chip = ScoreChip.Hidden)
        val hiddenLine = panelLineOf(hidden, strings, kickoff = null)
        assertEquals(ScoreboardScores.Hidden, hiddenLine.scores)
        assertEquals(null, hiddenLine.clock)

        // A single-line event carries it the same way; a channel never has one.
        val event = hockey.copy(art = null, twoSided = false)
        assertEquals("HOCKEY", panelLineOf(event, strings, kickoff = null, tag = "HOCKEY").tag)
        val channel = hockey.copy(item = ch, group = ScoreboardGroup.CHANNELS, art = null, twoSided = false, status = GameStatus.Channel)
        assertEquals("", panelLineOf(channel, strings, kickoff = null, tag = "TV").tag)
    }

    @Test
    fun `an unmatched side is shortened for the panel line - abbreviation, else the place without its nickname`() {
        val places = setOf("Stetson", "Texas")
        fun short(name: String, abbreviation: String? = null) = panelSideName(name, abbreviation) { it in places }
        // The team directory's abbreviation first, as on matched rows.
        assertEquals("SUU", short("Southern Utah Thunderbirds", abbreviation = "SUU"))
        // Two words of place or more left: the nickname goes.
        assertEquals("Southern Utah", short("Southern Utah Thunderbirds"))
        // One word left: only when it is a known place.
        assertEquals("Stetson", short("Stetson Hatters"))
        assertEquals("Real Madrid", short("Real Madrid"))
        assertEquals("Arsenal", short("Arsenal"))

        // On the line: only unmatched sides are shortened; a matched side keeps its abbreviation.
        val art = GameArt(GameArtTeam("Southern Utah Thunderbirds", "Southern Utah Thunderbirds", null), GameArtTeam("Texas Longhorns", "TEX", null), null, null, false)
        val row = ScoreboardRow(
            item = game("u", now - minute), group = ScoreboardGroup.LIVE, chip = null, art = art,
            firstFollowed = false, secondFollowed = false, probablyEnded = false, status = GameStatus.Live(null), twoSided = true
        )
        val line = panelLineOf(row, strings, kickoff = null) { panelSideName(it, null) { place -> place in places } }
        assertEquals("Southern Utah", line.first)
        assertEquals("TEX", line.second)
    }

    // ---- live.20 F5 / #179: the panel's tag column, names and readiness ----

    @Test
    fun `a live row with no clock shows the dot alone, and a real clock stays`() {
        val art = GameArt(GameArtTeam("Slovenia", "Slovenia", null), GameArtTeam("Indonesia", "Indonesia", null), null, null, false)
        val row = ScoreboardRow(
            item = game("s", now - minute, sport = "Tennis"), group = ScoreboardGroup.LIVE, chip = null, art = art,
            firstFollowed = false, secondFollowed = false, probablyEnded = false, status = GameStatus.Live(null), twoSided = true
        )
        val line = panelLineOf(row, strings, kickoff = null)
        assertEquals(null, line.clock)
        assertTrue(line.live)
        val clocked = row.copy(chip = ScoreChip.Live("Q4 11:22", ChipScore(1, 0)))
        assertEquals("11:22", panelLineOf(clocked, strings, kickoff = null).clock)
    }

    @Test
    fun `the two names share the line by need - a short one keeps its width, the long one takes the rest`() {
        // Both fit: each its own.
        assertEquals(40 to 60, panelSideWidths(40, 60, 120))
        // The first fits in half: the second takes the rest.
        assertEquals(30 to 70, panelSideWidths(30, 200, 100))
        // The second fits in half: the first takes the rest.
        assertEquals(70 to 30, panelSideWidths(200, 30, 100))
        // Both long: half each.
        assertEquals(50 to 50, panelSideWidths(200, 300, 100))
        assertEquals(0 to 0, panelSideWidths(10, 10, -5))
    }

    @Test
    fun `the score is never cut - the names give way first, then the score goes whole (user TV report)`() {
        // "38–37" is 40 px, gaps 5, a name keeps at least 26 px.
        // Room for all: each name its own width, the score whole.
        assertEquals(SidesFit(60, 70, middle = true), panelSidesFit(60, 70, middle = 40, gap = 5, minName = 26, available = 200))
        // Tight: the names share what the score leaves (they ellipsize), the score stays whole.
        assertEquals(SidesFit(30, 30, middle = true), panelSidesFit(100, 100, middle = 40, gap = 5, minName = 26, available = 110))
        assertEquals(SidesFit(26, 26, middle = true), panelSidesFit(100, 100, middle = 40, gap = 5, minName = 26, available = 102))
        // Too tight for both names at their least beside the score: the score goes, whole, and the names stay.
        val dropped = panelSidesFit(100, 100, middle = 40, gap = 5, minName = 26, available = 90)
        assertFalse(dropped.middle)
        assertEquals(40 to 40, dropped.first to dropped.second)
        // Short names need less than the least: they only need their own width.
        assertTrue(panelSidesFit(12, 12, middle = 40, gap = 5, minName = 26, available = 80).middle)
        // A three-digit score is measured like any other middle.
        assertTrue(panelSidesFit(30, 30, middle = 60, gap = 5, minName = 26, available = 130).middle)
        assertFalse(panelSidesFit(30, 30, middle = 60, gap = 5, minName = 26, available = 100).middle)
    }

    @Test
    fun `a player's side steps down to last names, a team's to its short code`() {
        assertEquals(listOf("Neal Skupski / Joe Salisbury", "Skupski / Salisbury"), panelSideChoices("Neal Skupski / Joe Salisbury", individual = true))
        assertEquals(listOf("Novak Djokovic", "Djokovic"), panelSideChoices("Novak Djokovic", individual = true))
        assertEquals(listOf("Arevalo & Pavic", "Arevalo / Pavic"), panelSideChoices("Arevalo & Pavic", individual = true))
        assertEquals(listOf("Slovenia"), panelSideChoices("Slovenia", individual = true))
        assertEquals(listOf("Real Madrid", "REA"), panelSideChoices("Real Madrid", individual = false))
        assertTrue(SportsLiveSportKind.TENNIS in PANEL_INDIVIDUAL_SPORTS)
        assertFalse(SportsLiveSportKind.SOCCER in PANEL_INDIVIDUAL_SPORTS)

        // A player's full name is never cut as a team's nickname.
        assertEquals("Neal Skupski / Joe Salisbury", panelSideName("Neal Skupski / Joe Salisbury", null, individual = true) { true })
    }

    @Test
    fun `a team with no ESPN code gets a sensible three-letter code, never an ellipsis (BUG-C)`() {
        assertEquals("AND", panelShortCode("Andorra"))
        assertEquals("AND", panelShortCode("FC Andorra"))
        assertEquals("LEG", panelShortCode("Leganés"))
        assertEquals("BAY", panelShortCode("Bayern"))
        assertEquals("KÖL", panelShortCode("Köln"))
        assertEquals("KÖL", panelShortCode("1. FC Köln"))
        assertEquals("NYK", panelShortCode("New York Knicks"))
        // From the place, as ESPN's codes are (TV QA BUG-C: "Utah Utes" is UTA, not UUT); Real Madrid's RMA comes from ESPN.
        assertEquals("REA", panelShortCode("Real Madrid"))
        // Only affixes: kept rather than left empty.
        assertEquals("FC", panelShortCode("FC"))
        assertEquals("", panelShortCode("76"))
        // A side that is already a code steps nowhere.
        assertEquals(listOf("ORL"), panelSideChoices("ORL", individual = false))
        assertEquals(listOf("Leganes", "LEG"), panelSideChoices("Leganes", individual = false))
        // ESPN's real code, when the app knows the team, before a derived one.
        assertEquals(listOf("Bayern Munich", "BAY"), panelSideChoices("Bayern Munich", individual = false, code = "BAY"))
    }

    @Test
    fun `a row's chips give way before the names (BUG-C)`() {
        // 266 px row, status 50, Ready 60, names floor 190.
        // Short names: all fit, the chip shows.
        assertTrue(panelChipsShown(total = 266, status = 50, chips = 60, wantMain = 150, floor = 190))
        // Long names: the chip would leave them 156 px, under the floor: the chip goes.
        assertFalse(panelChipsShown(total = 266, status = 50, chips = 60, wantMain = 300, floor = 190))
        // A wide row keeps the chip while the names keep the floor.
        assertTrue(panelChipsShown(total = 400, status = 50, chips = 60, wantMain = 300, floor = 190))
        // No chips: nothing to drop.
        assertTrue(panelChipsShown(total = 100, status = 50, chips = 0, wantMain = 300, floor = 190))
    }

    @Test
    fun `the TV QA rows keep readable codes - never U or Pogon S cut short (BUG-C)`() {
        // The names the TV showed cut ("U… 6–7 DAY", "Pogoń S… vs Śląsk Wr…", "EAG Ro…", "AS… Dij…").
        assertEquals("UTA", panelShortCode("Utah Utes"))
        assertEquals("DAY", panelShortCode("Dayton Flyers"))
        assertEquals("POG", panelShortCode("Pogoń Szczecin"))
        assertEquals("ŚLĄ", panelShortCode("Śląsk Wrocław"))
        assertEquals("EAG", panelShortCode("EA Guingamp"))
        assertEquals("ROD", panelShortCode("Rodez AF"))
        assertEquals("NAN", panelShortCode("AS Nancy Lorraine"))
        assertEquals("DIJ", panelShortCode("Dijon FCO"))
        assertEquals("HAV", panelShortCode("Le Havre"))
        // ESPN's code when known, else the derived one: every team side has a short form.
        assertEquals(listOf("Utah Utes", "UTAH"), panelSideChoices("Utah Utes", individual = false, code = "UTAH"))
        assertEquals(listOf("Dayton Flyers", "DAY"), panelSideChoices("Dayton Flyers", individual = false))
        assertEquals(listOf("Pogoń Szczecin", "POG"), panelSideChoices("Pogoń Szczecin", individual = false))
        assertEquals(listOf("Śląsk Wrocław", "ŚLĄ"), panelSideChoices("Śląsk Wrocław", individual = false))
        // A matched side is ESPN's code already: it never steps down to a made-up one ("UTAH" stays, not "UTA").
        assertEquals(listOf("UTAH"), panelSideChoices("UTAH", individual = false))
        assertEquals(listOf("DAY"), panelSideChoices("DAY", individual = false, code = "XYZ"))
        assertTrue(panelIsCode("UTAH"))
        assertFalse(panelIsCode("Utah"))
        assertFalse(panelIsCode("EA G"))
    }

    @Test
    fun `a game line steps the wider name down to its code before anything is cut (BUG-C)`() {
        // Widths at the TV's density, px: "Utah Utes" 140, "UTAH" 74, "DAY" 52, "6–7" 46, gaps 10, logos 2 x (36 + 10).
        val utah = listOf(140, 74)
        val dayton = listOf(52)
        // The room the TV row had for the names (about 176 px): UTAH 6–7 DAY, whole, the logos left out.
        val tight = panelNamesFit(utah, dayton, middle = 46, logos = 92, gap = 10, available = 196)
        assertEquals(PanelNamesFit(1, 0, 74, 52, middle = true, logos = false), tight)
        // With room for the logos too, they stay.
        assertEquals(PanelNamesFit(1, 0, 74, 52, middle = true, logos = true), panelNamesFit(utah, dayton, 46, 92, 10, 300))
        // A wide row keeps the full names and the logos.
        assertEquals(PanelNamesFit(0, 0, 140, 52, middle = true, logos = true), panelNamesFit(utah, dayton, 46, 92, 10, 400))
        // "Pogoń Szczecin vs Śląsk Wrocław", no score: the wider side goes to its code first, the other keeps its name.
        val pogon = listOf(190, 62)
        val slask = listOf(180, 60)
        assertEquals(PanelNamesFit(1, 0, 62, 180, middle = true, logos = false), panelNamesFit(pogon, slask, 28, 0, 10, 300))
        // Tighter: both codes, "vs" kept.
        assertEquals(PanelNamesFit(1, 1, 62, 60, middle = true, logos = false), panelNamesFit(pogon, slask, 28, 0, 10, 200))
        // Too tight for both codes beside the score: the score goes whole; the codes stay whole.
        assertEquals(PanelNamesFit(1, 1, 62, 60, middle = false, logos = false), panelNamesFit(pogon, slask, 28, 0, 10, 140))
        // Narrower than two codes: only then are they cut.
        val cut = panelNamesFit(pogon, slask, 28, 0, 10, 100)
        assertEquals(1 to 1, cut.first to cut.second)
        assertTrue(cut.firstWidth < 62 && cut.secondWidth <= 60)
        assertFalse(cut.middle)
    }

    @Test
    fun `the status gives way to the codes - its period first, a long word for a short one (BUG-C)`() {
        // Row 492 px, codes and score need 324: the status ("15:47 1st", 132) keeps all it wants.
        assertEquals(132, panelStatusWidth(room = 492, wantStatus = 132, leastMain = 324, gap = 10))
        // "Probably ended" (200) on a row whose codes need 290: it gets 192 and steps down to its short form.
        assertEquals(192, panelStatusWidth(room = 492, wantStatus = 200, leastMain = 290, gap = 10))
        assertEquals(1, panelFirstFit(listOf(200, 84), 192))
        assertEquals(0, panelFirstFit(listOf(132, 96), 132))
        // Nothing fits: the shortest form, to be cut.
        assertEquals(1, panelFirstFit(listOf(132, 96), 40))
        // No status: no room taken.
        assertEquals(0, panelStatusWidth(room = 492, wantStatus = 0, leastMain = 290, gap = 10))
        // Periods short beside the clock: "15:47 1H", never "15:47 1st half".
        assertEquals("1H", panelPeriodShort("1st half"))
        assertEquals("2H", panelPeriodShort("2nd Half"))
        assertEquals("Q2", panelPeriodShort("2nd quarter"))
        assertEquals("3rd", panelPeriodShort("3rd period"))
        assertEquals("OT", panelPeriodShort("Overtime"))
        assertEquals("1st", panelPeriodShort("1st"))
        assertEquals("Q4", panelPeriodShort("Q4"))
    }

    @Test
    fun `the tag column is as wide as the widest tag shown, never wider than FOOTBALL's`() {
        // "NCAAM" 80 px, "SOCCER" 98, "TENNIS" 90: the column is SOCCER's, not FOOTBALL's 124.
        assertEquals(98, panelTagColumnWidth(listOf(80, 98, 90), max = 124))
        // A tag wider than the column's most is held to it (it fits by the tag vocabulary's own rule).
        assertEquals(124, panelTagColumnWidth(listOf(130), max = 124))
        // No row with a tag: no column.
        assertEquals(0, panelTagColumnWidth(emptyList(), max = 124))
    }

    @Test
    fun `men's college basketball reads its halves 1H and 2H, other games keep their period`() {
        assertTrue(panelPlaysHalves(EspnLeagueMap.COLLEGE_BASKETBALL))
        // The women's game and the pros play quarters: ESPN's "1st" stays.
        assertFalse(panelPlaysHalves(EspnLeagueMap.COLLEGE_BASKETBALL_WOMEN))
        assertFalse(panelPlaysHalves(EspnLeague("basketball", "nba")))
        assertFalse(panelPlaysHalves(null))
        // "Utah 6–7 Dayton, 15:47 1st" on the TV: "15:47 1H".
        assertEquals("1H", panelPeriodShort("1st", halves = true))
        assertEquals("2H", panelPeriodShort("2nd", halves = true))
        assertEquals("OT", panelPeriodShort("OT", halves = true))
        assertEquals("1st", panelPeriodShort("1st", halves = false))
        assertEquals("1H", panelPeriodShort("1st half", halves = true))
    }

    @Test
    fun `the footer says Ready when the focused row had no room for the chip`() {
        assertEquals("FC Andorra vs Leganés · ▶ Ready", panelFooterText("FC Andorra vs Leganés", true, "▶ Ready", " · "))
        assertEquals("FC Andorra vs Leganés", panelFooterText("FC Andorra vs Leganés", false, "▶ Ready", " · "))
    }

    @Test
    fun `panel names and titles lose the addon's leading emoji, marks and bracketed prefix`() {
        assertEquals("Portugal Free Practice", panelCleanText("\uD83D\uDCFA Portugal Free Practice"))
        assertEquals("Moto2 | Round 22", panelCleanText("\u200B\uFE0FMoto2 | Round 22"))
        assertEquals("Lakers", panelCleanText("[NBA 11/19] Lakers"))
        assertEquals("Lakers", panelCleanText("\uD83C\uDFC0 [NBA 11/19] Lakers"))
        assertEquals("76ers", panelCleanText("76ers"))
        assertEquals("(W) Final", panelCleanText("(W) Final"))
        // Nothing left: as it was.
        assertEquals("[TBD]", panelCleanText("[TBD]"))

        val event = ScoreboardRow(
            item = game("p", now - minute, sport = "Motorsport").let { it.copy(meta = it.meta.copy(name = "\uD83D\uDCFA Portugal Free Practice")) },
            group = ScoreboardGroup.LIVE, chip = null, art = null,
            firstFollowed = false, secondFollowed = false, probablyEnded = false, status = GameStatus.Live(null), twoSided = false
        )
        assertEquals("Portugal Free Practice", panelLineOf(event, strings, kickoff = null).title)
    }

    @Test
    fun `Ready always shows on a panel row, No stream yet only under focus, the row dimmed meanwhile`() {
        assertEquals(SportsLiveReadyMark.READY, panelReadyMarkShown(SportsLiveReadyMark.READY, focused = false))
        assertEquals(SportsLiveReadyMark.READY, panelReadyMarkShown(SportsLiveReadyMark.READY, focused = true))
        assertEquals(null, panelReadyMarkShown(SportsLiveReadyMark.NO_STREAM, focused = false))
        assertEquals(SportsLiveReadyMark.NO_STREAM, panelReadyMarkShown(SportsLiveReadyMark.NO_STREAM, focused = true))
        assertEquals(null, panelReadyMarkShown(null, focused = true))
        assertTrue(panelNoStreamDimmed(SportsLiveReadyMark.NO_STREAM, focused = false))
        assertFalse(panelNoStreamDimmed(SportsLiveReadyMark.NO_STREAM, focused = true))
        assertFalse(panelNoStreamDimmed(SportsLiveReadyMark.READY, focused = false))
    }

    @Test
    fun `a running check shows its faint mark only where no readiness mark shows`() {
        assertTrue(panelCheckingShown(null, focused = false, checking = true))
        assertFalse(panelCheckingShown(null, focused = false, checking = false))
        // Ready keeps its pill while it is verified again.
        assertFalse(panelCheckingShown(SportsLiveReadyMark.READY, focused = false, checking = true))
        // No stream yet: the words under focus, the faint mark on the dimmed row otherwise.
        assertFalse(panelCheckingShown(SportsLiveReadyMark.NO_STREAM, focused = true, checking = true))
        assertTrue(panelCheckingShown(SportsLiveReadyMark.NO_STREAM, focused = false, checking = true))
    }

    @Test
    fun `rows of one title each say what tells them apart, rows with their own title say nothing`() {
        fun motogp(id: String, name: String = "MotoGP", description: String? = null, catalog: String = "Live", addon: String = "Sports") =
            game(id, now - minute, sport = "Motorsport").let {
                it.copy(meta = it.meta.copy(name = name, description = description), catalogName = catalog, addonName = addon, teams = null)
            }
        val feed: (Int) -> String = { "Feed $it" }
        val title: (SportsLiveItem) -> String = { it.meta.name.substringBefore(" (").trim() }

        // The description's first line tells them apart.
        val main = motogp("m1", description = "Main feed\nRound 22")
        val onboard = motogp("m2", description = "Onboard\nRound 22")
        val other = motogp("x", name = "Moto3")
        assertEquals(
            mapOf(main.streamKey to "Main feed", onboard.streamKey to "Onboard"),
            panelTitleSuffixes(listOf(main, onboard, other), feed, title)
        )
        // Nothing but the addon differs: its name.
        val a = motogp("a1", addon = "Alpha Sports")
        val b = motogp("b1", addon = "Beta TV")
        assertEquals(mapOf(a.streamKey to "Alpha Sports", b.streamKey to "Beta TV"), panelTitleSuffixes(listOf(a, b), feed, title))
        // Never a web address; nothing tells them apart: numbered feeds.
        val c = motogp("c1", addon = "https://example.invalid/manifest.json")
        val d = motogp("d1", addon = "example.invalid.tv")
        val e = motogp("e1", addon = "streams.example.com")
        val numbered = panelTitleSuffixes(listOf(c, d, e), feed, title)
        assertEquals(listOf("Feed 1", "Feed 2", "Feed 3"), listOf(c, d, e).map { numbered[it.streamKey] })
        // A long part is cut at a word, at most the suffix's longest.
        val long1 = motogp("l1", description = "Onboard camera feed for the Portugal Grand Prix")
        val long2 = motogp("l2", description = "Pit lane")
        val cut = panelTitleSuffixes(listOf(long1, long2), feed, title)
        assertTrue(cut.values.all { it.length <= PANEL_SUFFIX_MAX_CHARS && !it.endsWith(" ") })
        assertEquals("Onboard camera feed", cut[long1.streamKey])
        assertEquals("Pit lane", cut[long2.streamKey])

        // On the line: an event carries its suffix; a game never.
        val row = ScoreboardRow(
            item = main, group = ScoreboardGroup.LIVE, chip = null, art = null,
            firstFollowed = false, secondFollowed = false, probablyEnded = false, status = GameStatus.Live(null), twoSided = false
        )
        assertEquals("Onboard", panelLineOf(row, strings, kickoff = null, suffix = "Onboard").suffix)
    }

    @Test
    fun `the footer names the focused row in full - both sides, else the whole title and its suffix`() {
        val art = GameArt(GameArtTeam("Orlando Magic", "ORL", null), GameArtTeam("Philadelphia 76ers", "PHI", null), "NBA", null, false)
        val gameRow = ScoreboardRow(
            item = game("m", now - minute), group = ScoreboardGroup.LIVE, chip = ScoreChip.Live("Q4 11:22", ChipScore(97, 92)),
            art = art, firstFollowed = false, secondFollowed = false, probablyEnded = false, status = GameStatus.Live(null), twoSided = true
        )
        assertEquals("Orlando Magic vs Philadelphia 76ers", panelFullName(gameRow, "vs"))
        val event = gameRow.copy(
            item = game("t", now).let { it.copy(meta = it.meta.copy(name = "\uD83D\uDCFA MotoGP Portugal Free Practice"), teams = null) },
            chip = null, art = null, twoSided = false
        )
        assertEquals("MotoGP Portugal Free Practice · Onboard", panelFullName(event, "vs", suffix = "Onboard"))
        assertEquals("MotoGP Portugal Free Practice", panelFullName(event, "vs"))
    }

    @Test
    fun `the keys hint shows for the first 3 panel openings only`() {
        assertEquals(3, PANEL_KEYS_HINT_OPENS)
        assertEquals(listOf(true, true, true, false, false), (0..4).map { shouldShowPanelKeysHint(it) })
    }

    @Test
    fun `the keys hint comes back after a long gap without opening the panel`() {
        val day = 24 * 60 * 60 * 1000L
        val now = 100 * day
        assertFalse(shouldShowPanelKeysHint(5, lastOpenAtMs = now - day, nowMs = now))
        assertTrue(shouldShowPanelKeysHint(5, lastOpenAtMs = now - PANEL_KEYS_HINT_GAP_MS, nowMs = now))
        assertFalse(shouldShowPanelKeysHint(5, lastOpenAtMs = null, nowMs = now))
    }

    @Test
    fun `the panel has no video preview any more`() {
        val overlay = listOf(
            File("src/main/java/com/nuvio/tv/ui/screens/player/SportsLiveZapOverlay.kt"),
            File("app/src/main/java/com/nuvio/tv/ui/screens/player/SportsLiveZapOverlay.kt")
        ).first { it.exists() }.readText()
        assertFalse(overlay.contains("SportsLivePreviewPlayer"))
        assertFalse(overlay.contains("SportsLivePreviewVideo"))
        assertTrue(overlay.contains("panelFullName"))
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
