package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.EndedGame
import com.nuvio.tv.core.sportslive.EndedSignal
import com.nuvio.tv.core.sportslive.GameStatus
import com.nuvio.tv.core.sportslive.GameStatusStrings
import com.nuvio.tv.core.sportslive.SportsLiveSportKind
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.ChipScore
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
    fun `the hold OK menu lists Watch both first, then Choose stream, Game stats and Follow`() {
        assertEquals(listOf(WATCH_WITH, CHOOSE_STREAM, GAME_STATS, FOLLOW, FOLLOW), panelMenuEntries(true, true, true, sides = 2))
        assertEquals(listOf(CHOOSE_STREAM, FOLLOW, FOLLOW), panelMenuEntries(false, true, false, sides = 2))
        assertEquals(WATCH_WITH, panelMenuEntries(true, false, false, sides = 0).first())
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
        assertEquals("LIVE", eventLine.clock)
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
