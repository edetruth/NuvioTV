package com.nuvio.tv.ui.screens.sportslive

import android.view.KeyEvent
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #77 and N7: ±3 h jumps, the "+N later" hints and the now tag. */
class SportsLiveTimelineJumpTest {

    private fun item(name: String) = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
        kind = SportsLiveKind.EVENTS, isLive = false, isChannel = false, startMs = null, sport = "Basketball",
        teams = SportsLiveTeams.parse(name)
    )

    private fun block(name: String, start: Long, end: Long = start + 2 * HOUR, lane: Int = 0) = TimelineBlock(
        row = ScoreboardRow(item(name), ScoreboardGroup.MORE_GAMES, null, null, false, false, false),
        kind = TimelineBlockKind.UPCOMING,
        startMs = start,
        endMs = end,
        lane = lane
    )

    private fun league(id: String, vararg blocks: TimelineBlock) =
        TimelineLeague(id, id.uppercase(), followed = false, lanes = (blocks.maxOfOrNull { it.lane } ?: 0) + 1, blocks = blocks.toList())

    // NBA: games at now, +1 h, +3 h and +7 h. NHL: one at +3 h 30 min. Untimed: a channel.
    private val nbaNow = block("A @ B", NOW)
    private val nbaSoon = block("C @ D", NOW + HOUR)
    private val nbaLater = block("E @ F", NOW + 3 * HOUR)
    private val nbaLate = block("G @ H", NOW + 7 * HOUR)
    private val nhl = block("I @ J", NOW + 3 * HOUR + 30 * MIN)
    private val channel = ScoreboardRow(item("Sports Channel"), ScoreboardGroup.MORE_GAMES, null, null, false, false, false)
    private val model = TimelineModel(
        leagues = listOf(league("nba", nbaNow, nbaSoon, nbaLater, nbaLate), league("nhl", nhl)),
        untimed = listOf(channel),
        startMs = NOW - HOUR,
        endMs = NOW + 10 * HOUR,
        viewStartMs = NOW - HOUR,
        viewMs = 6 * HOUR,
        ticks = emptyList(),
        nowMs = NOW
    )

    @Test
    fun `a jump lands on the game nearest three hours on, in the same row first`() {
        assertEquals(nbaLater.key, SportsLiveTimeline.jumpTarget(model, nbaNow.key, forward = true))
        // From +1 h (aiming at +4 h): the NBA game an hour off ties the NHL game half an hour off plus its
        // row's cost; the tie goes to the upper row.
        assertEquals(nbaLater.key, SportsLiveTimeline.jumpTarget(model, nbaSoon.key, forward = true))
        assertEquals(nbaLate.key, SportsLiveTimeline.jumpTarget(model, nbaLater.key, forward = true))
        assertEquals(nbaNow.key, SportsLiveTimeline.jumpTarget(model, nbaLater.key, forward = false))
        // Nothing later than the last game: the window still moves, focus stays.
        assertNull(SportsLiveTimeline.jumpTarget(model, nbaLate.key, forward = true))
        // From the "No time" row, the jump counts from now.
        assertEquals(nbaLater.key, SportsLiveTimeline.jumpTarget(model, channel.item.timelineKey, forward = true))
    }

    @Test
    fun `the window jumps three hours, kept on the ruler`() {
        val hourPx = 100f
        assertEquals(450f, SportsLiveTimeline.jumpScroll(150f, forward = true, hourPx = hourPx, minScroll = 0f, maxScroll = 1000f), 0f)
        assertEquals(0f, SportsLiveTimeline.jumpScroll(150f, forward = false, hourPx = hourPx, minScroll = 0f, maxScroll = 1000f), 0f)
        assertEquals(1000f, SportsLiveTimeline.jumpScroll(900f, forward = true, hourPx = hourPx, minScroll = 0f, maxScroll = 1000f), 0f)
    }

    @Test
    fun `fast-forward and rewind jump on their first press, a held arrow jumps once`() {
        val down = KeyEvent.ACTION_DOWN
        val up = KeyEvent.ACTION_UP
        assertEquals(TimelineJumpKey.FORWARD, timelineJumpKey(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, down, 0, held = false))
        assertEquals(TimelineJumpKey.BACK, timelineJumpKey(KeyEvent.KEYCODE_MEDIA_REWIND, down, 0, held = false))
        assertEquals(TimelineJumpKey.CONSUME, timelineJumpKey(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, down, 3, held = false))
        assertEquals(TimelineJumpKey.CONSUME, timelineJumpKey(KeyEvent.KEYCODE_MEDIA_REWIND, up, 0, held = false))
        // A short arrow press moves a block as before.
        assertNull(timelineJumpKey(KeyEvent.KEYCODE_DPAD_RIGHT, down, 0, held = false))
        assertNull(timelineJumpKey(KeyEvent.KEYCODE_DPAD_RIGHT, up, 0, held = false))
        // Held: the first repeat jumps, the rest are swallowed until the release.
        assertEquals(TimelineJumpKey.FORWARD, timelineJumpKey(KeyEvent.KEYCODE_DPAD_RIGHT, down, 1, held = false))
        assertEquals(TimelineJumpKey.BACK, timelineJumpKey(KeyEvent.KEYCODE_DPAD_LEFT, down, 1, held = false))
        assertEquals(TimelineJumpKey.CONSUME, timelineJumpKey(KeyEvent.KEYCODE_DPAD_RIGHT, down, 5, held = true))
        assertEquals(TimelineJumpKey.RELEASE, timelineJumpKey(KeyEvent.KEYCODE_DPAD_RIGHT, up, 0, held = true))
        assertNull(timelineJumpKey(KeyEvent.KEYCODE_DPAD_UP, down, 1, held = false))
    }

    @Test
    fun `a row with nothing in the window hints where its games are`() {
        val nba = model.leagues[0]
        val nhlRow = model.leagues[1]
        // Opening window (now − 1 h … now + 5 h): both rows have a game in view.
        assertNull(SportsLiveTimeline.rowHint(nba, NOW - HOUR, NOW + 5 * HOUR))
        // The first two hours only: the NHL game is later.
        assertEquals(TimelineRowHint(later = true, count = 1, atMs = nhl.startMs), SportsLiveTimeline.rowHint(nhlRow, NOW - HOUR, NOW + HOUR))
        // Between the NBA games at +3 h (ends +5 h) and +7 h: one later, at +7 h.
        assertEquals(TimelineRowHint(later = true, count = 1, atMs = nbaLate.startMs), SportsLiveTimeline.rowHint(nba, NOW + 5 * HOUR, NOW + 6 * HOUR))
        // Past every game: how many ended earlier, the latest start.
        assertEquals(TimelineRowHint(later = false, count = 4, atMs = nbaLate.startMs), SportsLiveTimeline.rowHint(nba, NOW + 10 * HOUR, NOW + 12 * HOUR))
        // A game straddling the window's edge counts as in view.
        assertNull(SportsLiveTimeline.rowHint(nba, NOW + HOUR + 90 * MIN, NOW + 2 * HOUR + 50 * MIN))
    }

    @Test
    fun `the now tag stays whole in the ruler and hides the hour label it covers`() {
        // Upcoming opens with now at the window's left edge: the tag is pulled in, not clipped.
        assertEquals(0f, SportsLiveTimeline.nowTagLeft(nowX = 500f, scroll = 500f, shiftPx = 24f, tagWidth = 80f, viewport = 1000f), 0f)
        assertEquals(376f, SportsLiveTimeline.nowTagLeft(nowX = 900f, scroll = 500f, shiftPx = 24f, tagWidth = 80f, viewport = 1000f), 0f)
        assertEquals(920f, SportsLiveTimeline.nowTagLeft(nowX = 1495f, scroll = 500f, shiftPx = 24f, tagWidth = 80f, viewport = 1000f), 0f)
        // Off the window: left where it is (out of view).
        assertEquals(-124f, SportsLiveTimeline.nowTagLeft(nowX = 400f, scroll = 500f, shiftPx = 24f, tagWidth = 80f, viewport = 1000f), 0f)
        // "2 AM" at 6 px, 36 px wide; the tag from 20 px: covered. "3 AM" an hour on: shown.
        assertTrue(SportsLiveTimeline.spansOverlap(6f, 36f, 20f, 80f, gap = 4f))
        assertFalse(SportsLiveTimeline.spansOverlap(206f, 36f, 20f, 80f, gap = 4f))
        assertTrue(SportsLiveTimeline.spansOverlap(102f, 36f, 20f, 80f, gap = 4f))
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val MIN = 60_000L
        const val HOUR = 60 * MIN
    }
}
