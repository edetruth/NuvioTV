package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.EndedGame
import com.nuvio.tv.core.sportslive.EndedSignal
import com.nuvio.tv.core.sportslive.GameStatus
import com.nuvio.tv.core.sportslive.GameStatusStrings
import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.ScoreChip
import com.nuvio.tv.core.sportslive.scores.ScoreChipStrings
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoreTeam
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** live.26 settled screens: once Live now is shown, its board's rows stay where they were; statuses change in place. */
class SportsLiveSettledRowsTest {

    @Test
    fun `the list settles one second after its first frame, never before it was shown`() {
        assertFalse(SportsLiveSettledRows.settles(shownSinceMs = null, nowMs = 10_000))
        assertFalse(SportsLiveSettledRows.settles(shownSinceMs = 5_000, nowMs = 5_999))
        assertTrue(SportsLiveSettledRows.settles(shownSinceMs = 5_000, nowMs = 6_000))
    }

    @Test
    fun `a score landing late never moves a row to another group`() {
        val (a, b, c) = listOf("a", "b", "c").map(::item)
        val shown = listOf(row(a), row(b), row(c))
        // b turns out not started yet (ESPN), c is over: as built they would leave the Live rows.
        val built = listOf(
            row(a),
            row(c, ScoreboardGroup.JUST_ENDED, status = GameStatus.Final(final("c"))),
            row(b, ScoreboardGroup.STARTING_SOON, status = GameStatus.StartingSoon(NOW + HOUR, 1 to 0))
        ).sortedBy { it.group.ordinal }
        val settled = SportsLiveSettledRows.settle(shown, built)
        assertEquals(listOf("a", "b", "c"), settled.map { it.item.id })
        assertTrue(settled.all { it.group == ScoreboardGroup.LIVE })
        // Their fresh status is what they show, in place.
        assertTrue(settled[1].status is GameStatus.StartingSoon)
        assertTrue(settled[2].status is GameStatus.Final)
    }

    @Test
    fun `a game that ends in its place reads ended there, and the Live header stops counting it`() {
        val (a, b) = listOf("a", "b").map(::item)
        val shown = listOf(row(a), row(b))
        val built = listOf(row(b), row(a, ScoreboardGroup.JUST_ENDED, status = GameStatus.Final(final("a"))))
        val settled = SportsLiveSettledRows.settle(shown, built)
        assertEquals(listOf("a", "b"), settled.map { it.item.id })
        assertTrue(settled[0].endedInPlace)
        assertFalse(settled[1].endedInPlace)
        // As built, in the Just ended strip, it is no in-place end.
        assertFalse(built[1].endedInPlace)
        assertEquals(mapOf(ScoreboardGroup.LIVE to 1), sportsLiveGroupCounts(settled))
    }

    @Test
    fun `F7 - a row settled under LIVE that ESPN has as not started is not counted as live`() {
        val (a, b) = listOf("a", "b").map(::item)
        val shown = listOf(row(a), row(b))
        val built = listOf(row(a), row(b, ScoreboardGroup.STARTING_SOON, status = GameStatus.StartingSoon(NOW + HOUR, 1 to 0)))
        val settled = SportsLiveSettledRows.settle(shown, built)
        assertTrue(settled.all { it.group == ScoreboardGroup.LIVE })
        assertEquals(mapOf(ScoreboardGroup.LIVE to 1), sportsLiveGroupCounts(settled))
    }

    @Test
    fun `an ended in place row says its final word, and under Hide its score stays hidden`() {
        val ended = row(item("a"), ScoreboardGroup.LIVE, status = GameStatus.JustEnded(EndedGame(EndedSignal.FINAL, NOW, final("a"))))
        assertEquals(ScoreboardStatusLines("Final"), scoreboardStatusLines(ended, STRINGS, kickoff = "8:00 PM"))
        val hidden = ended.copy(chip = ScoreChip.Hidden)
        assertEquals(ScoreboardStatusLines("Final", "Score hidden"), scoreboardStatusLines(hidden, STRINGS, kickoff = "8:00 PM"))
        // The same game in the Just ended strip reads as before.
        val strip = hidden.copy(group = ScoreboardGroup.JUST_ENDED)
        assertEquals(ScoreboardStatusLines("Score hidden"), scoreboardStatusLines(strip, STRINGS, kickoff = "8:00 PM"))
    }

    @Test
    fun `a row keeps the height it was shown with when a late match makes it two sided`() {
        val a = item("a")
        val shown = listOf(row(a, twoSided = false))
        val settled = SportsLiveSettledRows.settle(shown, listOf(row(a, twoSided = true)))
        assertFalse(settled.single().twoSided)
    }

    @Test
    fun `dropped rows go and a row the list did not show joins the end of its own group`() {
        val (a, b, c, d, e) = listOf("a", "b", "c", "d", "e").map(::item)
        val shown = listOf(row(a), row(b), row(c, ScoreboardGroup.JUST_ENDED))
        val built = listOf(row(d), row(a), row(c, ScoreboardGroup.JUST_ENDED), row(e, ScoreboardGroup.JUST_ENDED))
        val settled = SportsLiveSettledRows.settle(shown, built)
        // b is gone; d goes after the last Live row, e after the last Just ended row: nothing above them moves.
        assertEquals(listOf("a", "d", "c", "e"), settled.map { it.item.id })
    }

    @Test
    fun `a new row of a group the list has none of sits where its group belongs`() {
        val (a, b) = listOf("a", "b").map(::item)
        val shown = listOf(row(a, ScoreboardGroup.JUST_ENDED))
        val settled = SportsLiveSettledRows.settle(shown, listOf(row(a, ScoreboardGroup.JUST_ENDED), row(b)))
        assertEquals(listOf("b", "a"), settled.map { it.item.id })
    }

    @Test
    fun `nothing shown, an emptied list or nothing of it left lays out as built`() {
        val (a, b) = listOf("a", "b").map(::item)
        val built = listOf(row(b), row(a, ScoreboardGroup.JUST_ENDED))
        assertSame(built, SportsLiveSettledRows.settle(emptyList(), built))
        assertTrue(SportsLiveSettledRows.settle(listOf(row(a)), emptyList()).isEmpty())
        val other = listOf(row(item("z")))
        assertSame(built, SportsLiveSettledRows.settle(other, built))
    }

    @Test
    fun `a board settles only on the same tab and filter, never on More live or another view`() {
        val (a, b) = listOf("a", "b").map(::item)
        val shown = board(listOf(row(a), row(b)))
        val built = board(listOf(row(b), row(a, ScoreboardGroup.JUST_ENDED, status = GameStatus.Final(final("a")))))
        val settled = built.settledOn(shown)
        assertEquals(listOf("a", "b"), settled.rows!!.map { it.item.id })
        // The items the list focuses follow the rows.
        assertEquals(listOf("a", "b"), settled.items.map { it.id })
        assertSame(built, built.settledOn(null))
        assertSame(built, built.settledOn(shown.copy(filter = "Soccer")))
        assertSame(built, built.settledOn(shown.copy(tab = SportsLiveTab.UPCOMING)))
        val moreLive = built.copy(moreLive = true)
        assertSame(moreLive, moreLive.settledOn(shown))
    }

    @Test
    fun `a board after N new or a follow never settles on a board from before it`() {
        // Gemini review #7: the thawed build skipped (conflated), the next settled build put the old order back.
        val (a, b) = listOf("a", "b").map(::item)
        val beforeThaw = board(listOf(row(a), row(b)))
        val afterThaw = board(listOf(row(b), row(a))).copy(settleEpoch = 1)
        assertSame(afterThaw, afterThaw.settledOn(beforeThaw))
        assertEquals(listOf("b", "a"), afterThaw.copy(rows = listOf(row(a), row(b))).settledOn(afterThaw).rows!!.map { it.item.id })
    }

    @Test
    fun `OK on a game ESPN says ended in place opens its Game page, every other card as before`() {
        val event = final("a")
        val inPlace = row(item("a"), ScoreboardGroup.LIVE, status = GameStatus.Final(event))
        assertEquals(SportsLiveCardOk.OpenGame(event), sportsLiveSettledOk(inPlace, ended = null))
        // A final in the strip under its grace, a live game and no row: the usual rule.
        assertEquals(SportsLiveCardOk.Play, sportsLiveSettledOk(inPlace.copy(group = ScoreboardGroup.JUST_ENDED), ended = null))
        assertEquals(SportsLiveCardOk.Play, sportsLiveSettledOk(row(item("b")), ended = null))
        val verdict = EndedGame(EndedSignal.FINAL, NOW, event)
        assertEquals(SportsLiveCardOk.OpenGame(event), sportsLiveSettledOk(null, verdict))
        // An estimate in place may still play (its stream may work).
        val estimate = EndedGame(EndedSignal.ESTIMATE, NOW)
        val probably = row(item("c"), ScoreboardGroup.LIVE, status = GameStatus.JustEnded(estimate))
        assertTrue(probably.endedInPlace)
        assertEquals(SportsLiveCardOk.Play, sportsLiveSettledOk(probably, estimate))
    }

    private fun board(rows: List<ScoreboardRow>) = SportsLiveBoard(
        tab = SportsLiveTab.LIVE_NOW, filter = null, items = rows.map { it.item }, rows = rows, arts = emptyMap()
    )

    private fun row(
        item: SportsLiveItem,
        group: ScoreboardGroup = ScoreboardGroup.LIVE,
        status: GameStatus = GameStatus.Live(null),
        twoSided: Boolean = true
    ) = ScoreboardRow(
        item = item, group = group, chip = null, art = null,
        firstFollowed = false, secondFollowed = false, probablyEnded = false, status = status, twoSided = twoSided
    )

    private fun final(id: String) = ScoreSnapshot(
        eventId = id, league = EspnLeague("basketball", "nba"), startMs = NOW - 3 * HOUR, state = ScoreState.POST,
        clockText = "Final", isFinal = true, home = team("Home $id"), away = team("Away $id")
    )

    private fun team(name: String) = ScoreTeam(
        id = null, abbreviation = null, displayName = name, shortDisplayName = name, name = name, location = null,
        logo = null, score = 100, linescores = emptyList()
    )

    private fun item(id: String) = SportsLiveItem(
        meta = MetaPreview(
            id = id, type = ContentType.UNKNOWN, rawType = "tv", name = "Team $id vs Other $id", poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = "addon", addonName = "Sports", addonBaseUrl = "https://example.invalid", catalogKey = "addon:tv:live",
        catalogName = "Events", kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false,
        startMs = null, sport = "Basketball"
    )

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val HOUR = 60 * 60_000L
        val STRINGS = GameStatusStrings(
            liveClock = "● %1\$s", live = "LIVE", clockPeriod = "%1\$s · %2\$s", timeCountdown = "%1\$s · %2\$s",
            inHoursMinutes = "in %1\$d h %2\$d m", inHours = "in %1\$d h", inMinutes = "in %1\$d min",
            final = "Final", fullTime = "FT", probablyEnded = "Probably ended", postponed = "Postponed",
            chip = ScoreChipStrings("%1\$d–%2\$d", "%1\$s · %2\$s", "%1\$s %2\$s", "Final", "Postponed", "Score hidden")
        )
    }
}
