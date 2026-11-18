package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.EndedGame
import com.nuvio.tv.core.sportslive.EndedSignal
import com.nuvio.tv.core.sportslive.SportsLiveGameLength
import com.nuvio.tv.core.sportslive.SportsLiveLayout
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.ScoreChip
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsLiveTimelineTest {

    private val utc: ZoneId = ZoneOffset.UTC
    private val nba = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA)
    private val pacersEvent = nba.first { it.eventId == "401909985" } // IND @ PHI, final 131–124

    private fun item(
        name: String,
        startMs: Long?,
        sport: String = "Basketball",
        timed: Boolean = startMs != null,
        isLive: Boolean = true,
        channel: Boolean = false
    ) = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
        kind = if (channel) SportsLiveKind.CHANNELS else SportsLiveKind.EVENTS, isLive = isLive, isChannel = channel,
        startMs = startMs, sport = sport, startHasTimeOfDay = timed,
        teams = if (channel) null else SportsLiveTeams.parse(name)
    )

    private fun row(
        item: SportsLiveItem,
        group: ScoreboardGroup,
        chip: ScoreChip? = null,
        probablyEnded: Boolean = false
    ) = ScoreboardRow(item, group, chip, art = null, firstFollowed = false, secondFollowed = false, probablyEnded = probablyEnded)

    private fun block(start: Long, end: Long, name: String = "A @ B $start") =
        TimelineBlock(row(item(name, start), ScoreboardGroup.STARTING_SOON), TimelineBlockKind.UPCOMING, start, end, lane = 0)

    private fun live(event: ScoreSnapshot) = event.copy(
        state = ScoreState.IN, isFinal = false, clockText = "Q3 4:12",
        home = event.home.copy(score = 80), away = event.away.copy(score = 88)
    )

    // --- placement and lanes ---

    @Test
    fun `overlapping games stack into lanes, a game starting as another ends reuses its lane`() {
        val a = block(NOW, NOW + 150 * MIN, "A @ B")
        val b = block(NOW + HOUR, NOW + HOUR + 150 * MIN, "C @ D")
        val c = block(NOW + 150 * MIN, NOW + 5 * HOUR, "E @ F")
        val d = block(NOW + 210 * MIN, NOW + 4 * HOUR, "G @ H")
        val stacked = SportsLiveTimeline.stack(listOf(d, c, b, a))
        assertEquals(listOf("A @ B", "C @ D", "E @ F", "G @ H"), stacked.map { it.row.item.meta.name })
        assertEquals(listOf(0, 1, 0, 1), stacked.map { it.lane })
    }

    @Test
    fun `blocks sit by start, live ones run to now at least, finals end where they ended`() {
        val liveLong = item("Pacers @ 76ers", NOW - 5 * HOUR)
        val liveNew = item("Celtics @ Knicks", NOW - 10 * MIN)
        val soon = item("Bulls @ Heat", NOW + HOUR + 44 * MIN, isLive = false)
        val final = item("Lakers @ Suns", NOW - 4 * HOUR)
        val estimate = item("Nets @ Magic", NOW - 6 * HOUR)
        val length = SportsLiveTimeline.lengthOf(liveLong)
        assertEquals(SportsLiveGameLength.of(liveLong)!! - SportsLiveGameLength.LATE_START_MS, length)
        val model = SportsLiveTimeline.model(
            rows = listOf(
                row(liveLong, ScoreboardGroup.LIVE),
                row(liveNew, ScoreboardGroup.LIVE),
                row(soon, ScoreboardGroup.STARTING_SOON),
                row(final, ScoreboardGroup.FINAL, ScoreChip.Final("Final", com.nuvio.tv.core.sportslive.scores.ChipScore(1, 2))),
                row(estimate, ScoreboardGroup.FINAL, probablyEnded = true)
            ),
            tab = SportsLiveTab.LIVE_NOW,
            nowMs = NOW,
            zone = utc,
            ended = mapOf(
                final.streamKey to EndedGame(EndedSignal.FINAL, NOW - 30 * MIN),
                estimate.streamKey to EndedGame(EndedSignal.ESTIMATE, NOW - 6 * HOUR + length)
            )
        )
        val blocks = model.blocks
        fun of(item: SportsLiveItem) = blocks.getValue(item.timelineKey)
        // A game past its estimate is still live: its block runs to now.
        assertEquals(NOW, of(liveLong).endMs)
        assertEquals(TimelineBlockKind.LIVE, of(liveLong).kind)
        assertEquals(NOW - 10 * MIN + length, of(liveNew).endMs)
        assertEquals(TimelineBlockKind.UPCOMING, of(soon).kind)
        assertEquals(NOW + HOUR + 44 * MIN, of(soon).startMs)
        assertEquals(1 to 44, of(soon).startsIn)
        assertEquals(TimelineBlockKind.FINAL, of(final).kind)
        assertEquals(NOW - 30 * MIN, of(final).endMs)
        assertEquals(NOW - 6 * HOUR + length, of(estimate).endMs)
        // One league row ("Basketball"), live first in time order, stacked.
        assertEquals(1, model.leagues.size)
        assertTrue(model.leagues.single().lanes >= 2)
        // No countdown on a live or finished block.
        assertNull(of(liveLong).startsIn)
        assertNull(of(final).startsIn)
    }

    @Test
    fun `a sport with no length estimate still gets a block, with the unknown-sport length`() {
        val tennis = item("Sinner vs Alcaraz", NOW + HOUR, sport = "Tennis")
        assertNull(SportsLiveGameLength.of(tennis))
        assertEquals(SportsLiveGameLength.UNKNOWN_MS, SportsLiveTimeline.lengthOf(tennis))
    }

    @Test
    fun `league rows - followed first, then rows with a live game, then by next start`() {
        val nfl = item("Raiders @ Seahawks", NOW + 2 * HOUR, sport = "NFL")
        val epl = item("Arsenal vs Chelsea", NOW - HOUR, sport = "Premier League")
        val nhl = item("Bruins @ Rangers", NOW + HOUR, sport = "NHL")
        val followedMls = row(item("Galaxy vs LAFC", NOW + 6 * HOUR, sport = "MLS"), ScoreboardGroup.MORE_GAMES).copy(firstFollowed = true)
        val model = SportsLiveTimeline.model(
            rows = listOf(
                row(nfl, ScoreboardGroup.STARTING_SOON),
                row(epl, ScoreboardGroup.LIVE),
                row(nhl, ScoreboardGroup.STARTING_SOON),
                followedMls
            ),
            tab = SportsLiveTab.LIVE_NOW, nowMs = NOW, zone = utc
        )
        assertEquals(listOf("MLS", "Premier League", "NHL", "NFL"), model.leagues.map { it.name })
        assertTrue(model.leagues.first().followed)
    }

    // --- no time ---

    @Test
    fun `channels and date-only games are kept off the ruler, in the no-time row, with no countdown`() {
        val dateOnly = item("Raiders @ Seahawks", NOW + 30 * HOUR, timed = false, isLive = false)
        val channel = item("Sky Sports Main Event", null, channel = true)
        val timed = item("Bulls @ Heat", NOW + 2 * HOUR, isLive = false)
        val rows = listOf(row(dateOnly, ScoreboardGroup.MORE_GAMES), row(channel, ScoreboardGroup.MORE_GAMES), row(timed, ScoreboardGroup.STARTING_SOON))
        val model = SportsLiveTimeline.model(rows, SportsLiveTab.UPCOMING, NOW, utc)
        assertEquals(listOf(dateOnly, channel), model.untimed.map { it.item })
        assertEquals(setOf(timed.timelineKey), model.blocks.keys)
        assertEquals(2 to 0, model.blocks.getValue(timed.timelineKey).startsIn)
        // The rows the remote walks: the league, then "No time".
        assertEquals(listOf(listOf(timed.timelineKey), listOf(dateOnly.timelineKey, channel.timelineKey)), model.navRows)
        // Scoreboard rows built from the same items never count down a date-only start either.
        val board = scoreboardRows(listOf(dateOnly, timed), SportsLiveTab.UPCOMING, emptyMap(), ScoresMode.SHOW, emptySet(), NOW)
        assertNull(board.first { it.item == dateOnly }.startsIn)
    }

    // --- window math ---

    @Test
    fun `Live now opens on now - 1 h to now + 5 h, Upcoming on the next 12 h, the ruler on local hours`() {
        val live = SportsLiveTimeline.model(emptyList(), SportsLiveTab.LIVE_NOW, NOW_1416, utc)
        assertEquals(NOW_1416 - HOUR, live.viewStartMs)
        assertEquals(6 * HOUR, live.viewMs)
        assertEquals(at("2026-11-17T13:00:00Z"), live.startMs)
        assertEquals(at("2026-11-17T20:00:00Z"), live.endMs)
        val upcoming = SportsLiveTimeline.model(emptyList(), SportsLiveTab.UPCOMING, NOW_1416, utc)
        assertEquals(NOW_1416, upcoming.viewStartMs)
        assertEquals(12 * HOUR, upcoming.viewMs)
        assertEquals(at("2026-11-17T14:00:00Z"), upcoming.startMs)
        assertEquals(at("2026-11-18T03:00:00Z"), upcoming.endMs)
    }

    @Test
    fun `the ruler widens to every block, so later games are reached by scrolling`() {
        val later = item("Bulls @ Heat", at("2026-11-18T09:30:00Z"), isLive = false)
        val earlier = item("Pacers @ 76ers", at("2026-11-17T08:10:00Z"))
        val model = SportsLiveTimeline.model(
            listOf(row(later, ScoreboardGroup.MORE_GAMES), row(earlier, ScoreboardGroup.LIVE)),
            SportsLiveTab.UPCOMING, NOW_1416, utc
        )
        assertEquals(at("2026-11-17T08:00:00Z"), model.startMs)
        assertEquals(SportsLiveTimeline.ceilHour(at("2026-11-18T09:30:00Z") + SportsLiveTimeline.lengthOf(later), utc), model.endMs)
        assertEquals(NOW_1416, model.viewStartMs)
    }

    @Test
    fun `across midnight the ruler carries on into the next day and marks midnight`() {
        val late = at("2026-11-17T23:30:00Z")
        val model = SportsLiveTimeline.model(emptyList(), SportsLiveTab.LIVE_NOW, late, utc)
        assertEquals(at("2026-11-17T22:00:00Z"), model.startMs)
        assertEquals(at("2026-11-18T05:00:00Z"), model.endMs)
        assertEquals(8, model.ticks.size)
        assertEquals(listOf(at("2026-11-18T00:00:00Z")), model.ticks.filter { it.midnight }.map { it.ms })
        assertTrue(model.ticks.zipWithNext().all { (a, b) -> b.ms - a.ms == HOUR })
    }

    @Test
    fun `DST fall-back repeats an hour and spring-forward skips one, positions stay an hour apart`() {
        val ny = ZoneId.of("America/New_York")
        // 1 November 2026: 2:00 EDT → 1:00 EST.
        val fall = SportsLiveTimeline.ticks(at("2026-11-01T04:00:00Z"), at("2026-11-01T09:00:00Z"), ny)
        assertEquals(listOf(0, 1, 1, 2, 3, 4), fall.map { Instant.ofEpochMilli(it.ms).atZone(ny).hour })
        assertTrue(fall.zipWithNext().all { (a, b) -> b.ms - a.ms == HOUR })
        assertTrue(fall.first().midnight)
        // 8 March 2026: 2:00 EST → 3:00 EDT.
        val spring = SportsLiveTimeline.ticks(at("2026-03-08T05:00:00Z"), at("2026-03-08T08:00:00Z"), ny)
        assertEquals(listOf(0, 1, 3, 4), spring.map { Instant.ofEpochMilli(it.ms).atZone(ny).hour })
        assertTrue(spring.zipWithNext().all { (a, b) -> b.ms - a.ms == HOUR })
        // A game across the change is placed by instants: 3 real hours, whatever the clock says.
        val game = item("Bruins @ Rangers", at("2026-11-01T05:00:00Z"), sport = "NHL")
        val model = SportsLiveTimeline.model(listOf(row(game, ScoreboardGroup.LIVE)), SportsLiveTab.LIVE_NOW, at("2026-11-01T06:30:00Z"), ny)
        val block = model.blocks.getValue(game.timelineKey)
        assertEquals(SportsLiveTimeline.lengthOf(game), block.endMs - block.startMs)
    }

    @Test
    fun `ticks are local hours in a half-hour zone`() {
        val kolkata = ZoneId.of("Asia/Kolkata")
        // 10:45 UTC is 16:15 IST: the hour is 16:00 IST, 10:30 UTC.
        assertEquals(at("2026-11-17T10:30:00Z"), SportsLiveTimeline.floorHour(at("2026-11-17T10:45:00Z"), kolkata))
        assertEquals(at("2026-11-17T11:30:00Z"), SportsLiveTimeline.ceilHour(at("2026-11-17T10:45:00Z"), kolkata))
        assertEquals(at("2026-11-17T10:30:00Z"), SportsLiveTimeline.ceilHour(at("2026-11-17T10:30:00Z"), kolkata))
    }

    @Test
    fun `a day whose 00-00 DST skips still gets its midnight mark, at 01-00`() {
        val santiago = ZoneId.of("America/Santiago")
        // 6 September 2026: 00:00 → 01:00 in Chile (04:00 UTC).
        val ticks = SportsLiveTimeline.ticks(at("2026-09-06T01:00:00Z"), at("2026-09-06T07:00:00Z"), santiago)
        val firstOfDay = ticks.first { Instant.ofEpochMilli(it.ms).atZone(santiago).dayOfMonth == 6 }
        assertEquals(1, Instant.ofEpochMilli(firstOfDay.ms).atZone(santiago).hour)
        assertEquals(listOf(firstOfDay), ticks.filter { it.midnight })
    }

    @Test
    fun `the ruler is capped at two weeks, a game further out is listed under No time`() {
        val far = item("Bulls @ Heat", NOW + 20 * 24 * HOUR, isLive = false)
        val near = item("Celtics @ Knicks", NOW + HOUR, isLive = false)
        val model = SportsLiveTimeline.model(
            listOf(row(far, ScoreboardGroup.MORE_GAMES), row(near, ScoreboardGroup.STARTING_SOON)),
            SportsLiveTab.UPCOMING, NOW, utc
        )
        assertEquals(listOf(far), model.untimed.map { it.item })
        assertTrue(model.endMs - model.startMs <= (SportsLiveTimeline.MAX_TICKS - 1) * HOUR)
        assertTrue(model.ticks.size <= SportsLiveTimeline.MAX_TICKS)
        assertEquals(model.ticks.last().ms, model.endMs)
    }

    @Test
    fun `an 8 h live game is drawn inside the window and revealed with now on screen`() {
        val marathon = item("Pacers @ 76ers", NOW - 8 * HOUR)
        val model = SportsLiveTimeline.model(listOf(row(marathon, ScoreboardGroup.LIVE)), SportsLiveTab.LIVE_NOW, NOW, utc)
        val block = model.blocks.getValue(marathon.timelineKey)
        assertEquals(NOW, block.endMs)
        // The screen's geometry: 1600 px for the opening 6 h window, measured from its start.
        val viewport = 1600f
        val pxPerMs = viewport / model.viewMs
        fun x(ms: Long) = (ms - model.viewStartMs) * pxPerMs
        val left = x(block.startMs)
        val right = x(block.endMs)
        val minScroll = x(model.startMs)
        val maxScroll = x(model.endMs) - viewport
        // Opening window (scroll 0): the drawn part is inside the window, never the 8 h width.
        val (from, to) = SportsLiveTimeline.visibleSpan(left, right, 0f, viewport)
        assertTrue(from >= 0f && to <= viewport && to > from)
        // Only 1 h of it shows: focusing it scrolls so its end (now) sits at three quarters of the window.
        val target = SportsLiveTimeline.revealTarget(left, right, 0f, viewport, pxPerMs * HOUR, minScroll, maxScroll)!!
        val (shownFrom, shownTo) = SportsLiveTimeline.visibleSpan(left, right, target, viewport)
        assertTrue(shownFrom >= target && shownTo <= target + viewport)
        assertTrue(shownTo - shownFrom >= viewport * 0.25f)
        assertTrue(x(NOW) in target..(target + viewport))
        // Once shown, focusing it again does not move the window.
        assertNull(SportsLiveTimeline.revealTarget(left, right, target, viewport, pxPerMs * HOUR, minScroll, maxScroll))
    }

    @Test
    fun `a game right of the window comes in by its start, and the edge scroll keeps the focused block on screen`() {
        val viewport = 1200f
        val hour = 200f
        // A 2 h game from 1500 to 1900, the window at 0: its start comes in half an hour from the edge.
        assertEquals(1400f, SportsLiveTimeline.revealTarget(1500f, 1900f, 0f, viewport, hour, -5000f, 5000f))
        // ← on the first block (at 100..500): half a window back, but its first quarter window stays in.
        assertEquals(-600f, SportsLiveTimeline.edgeScroll(TimelineMove.LEFT, 100f, 500f, 0f, viewport))
        val back = SportsLiveTimeline.edgeScroll(TimelineMove.LEFT, 100f, 500f, 0f, viewport)
        assertTrue(SportsLiveTimeline.visibleSpan(100f, 500f, back, viewport).let { (f, t) -> t - f >= viewport * 0.25f })
        // → on the last block (at 900..1100): on by at most half a window, its last part kept.
        val on = SportsLiveTimeline.edgeScroll(TimelineMove.RIGHT, 900f, 1100f, 0f, viewport)
        assertEquals(600f, on)
        assertTrue(SportsLiveTimeline.visibleSpan(900f, 1100f, on, viewport).let { (f, t) -> t > f && f >= on })
    }

    @Test
    fun `league rows are keyed by a stable id whatever their case`() {
        val a = item("Arsenal vs Chelsea", NOW, sport = "Premier League")
        val b = item("Spurs vs Fulham", NOW + 3 * HOUR, sport = "premier league")
        val model = SportsLiveTimeline.model(listOf(row(a, ScoreboardGroup.LIVE), row(b, ScoreboardGroup.MORE_GAMES)), SportsLiveTab.LIVE_NOW, NOW, utc)
        assertEquals(listOf("premier league"), model.leagues.map { it.id })
        assertEquals("Premier League", model.leagues.single().name)
    }

    // --- spoilers ---

    @Test
    fun `spoilers follow the Scoreboard rows, and a live game is never hidden`() {
        val pacers = item("Pacers @ 76ers", NOW - HOUR)
        val scores = mapOf(pacers.streamKey to live(pacersEvent))
        fun block(mode: ScoresMode?, revealed: Set<String> = emptySet()): TimelineBlock {
            val rows = scoreboardRows(listOf(pacers), SportsLiveTab.LIVE_NOW, scores, mode, revealed, NOW)
            return SportsLiveTimeline.model(rows, SportsLiveTab.LIVE_NOW, NOW, utc).blocks.getValue(pacers.timelineKey)
        }
        val shown = block(ScoresMode.SHOW)
        assertEquals(TimelineBlockKind.LIVE, shown.kind)
        assertTrue(shown.row.chip is ScoreChip.Live)
        // Hide until focused: the block is still there and still live; only its score is hidden.
        val hidden = block(ScoresMode.HIDE_UNTIL_FOCUSED)
        assertEquals(TimelineBlockKind.LIVE, hidden.kind)
        assertEquals(ScoreChip.Hidden, hidden.row.chip)
        assertTrue(block(ScoresMode.HIDE_UNTIL_FOCUSED, setOf(pacers.streamKey)).row.chip is ScoreChip.Live)
        // Off: no score at all, the game still on the ruler as live (its addon says so).
        val off = block(ScoresMode.OFF)
        assertEquals(TimelineBlockKind.LIVE, off.kind)
        assertNull(off.row.chip)
    }

    @Test
    fun `a final shows its chip under Show and Score hidden until focused`() {
        val pacers = item("Pacers @ 76ers", NOW - 3 * HOUR)
        val scores = mapOf(pacers.streamKey to pacersEvent)
        val ended = mapOf(pacers.streamKey to EndedGame(EndedSignal.FINAL, NOW - 20 * MIN, pacersEvent))
        fun block(mode: ScoresMode): TimelineBlock {
            val rows = scoreboardRows(listOf(pacers), SportsLiveTab.LIVE_NOW, scores, mode, emptySet(), NOW, ended)
            return SportsLiveTimeline.model(rows, SportsLiveTab.LIVE_NOW, NOW, utc, ended).blocks.getValue(pacers.timelineKey)
        }
        val show = block(ScoresMode.SHOW)
        assertEquals(TimelineBlockKind.FINAL, show.kind)
        assertTrue(show.row.chip is ScoreChip.Final)
        assertEquals(NOW - 20 * MIN, show.endMs)
        val hidden = block(ScoresMode.HIDE_UNTIL_FOCUSED)
        assertEquals(ScoreChip.Hidden, hidden.row.chip)
    }

    // --- layout preference ---

    @Test
    fun `the layout switch offers Timeline and remembers it per tab`() {
        assertTrue(SportsLiveLayout.TIMELINE in SportsLiveLayout.offeredFor(SportsLiveTab.LIVE_NOW))
        assertTrue(SportsLiveLayout.TIMELINE in SportsLiveLayout.offeredFor(SportsLiveTab.UPCOMING))
        val choices = mapOf(SportsLiveTab.LIVE_NOW to SportsLiveLayout.TIMELINE, SportsLiveTab.UPCOMING to SportsLiveLayout.SCOREBOARD)
        val stored = SportsLiveLayout.decode(SportsLiveLayout.encode(choices))
        assertEquals(SportsLiveLayout.TIMELINE, SportsLiveLayout.of(stored, SportsLiveTab.LIVE_NOW))
        assertEquals(SportsLiveLayout.SCOREBOARD, SportsLiveLayout.of(stored, SportsLiveTab.UPCOMING))
        assertEquals(SportsLiveLayout.CARDS, SportsLiveLayout.of(mapOf(SportsLiveTab.CHANNELS to SportsLiveLayout.TIMELINE), SportsLiveTab.CHANNELS))
    }

    // --- remote ---

    private fun navModel(): Triple<TimelineModel, Map<String, SportsLiveItem>, SportsLiveItem> {
        val a1 = item("Arsenal vs Chelsea", NOW - 2 * HOUR, sport = "Premier League")
        val a2 = item("Spurs vs Fulham", NOW + HOUR, sport = "Premier League")
        val a3 = item("Leeds vs Burnley", NOW + 4 * HOUR, sport = "Premier League")
        val b1 = item("Raiders @ Seahawks", NOW - HOUR, sport = "NFL")
        val b2 = item("Bears @ Lions", NOW + 3 * HOUR, sport = "NFL")
        val channel = item("Sky Sports", null, channel = true)
        val model = SportsLiveTimeline.model(
            listOf(
                row(a1, ScoreboardGroup.LIVE), row(a2, ScoreboardGroup.STARTING_SOON), row(a3, ScoreboardGroup.MORE_GAMES),
                row(b1, ScoreboardGroup.LIVE), row(b2, ScoreboardGroup.MORE_GAMES), row(channel, ScoreboardGroup.MORE_GAMES)
            ),
            SportsLiveTab.LIVE_NOW, NOW, utc
        )
        val byName = listOf(a1, a2, a3, b1, b2).associateBy { it.meta.name }
        return Triple(model, byName, channel)
    }

    @Test
    fun `left and right walk a row in time, up and down change row to the game nearest in time`() {
        val (model, games, channel) = navModel()
        fun key(name: String) = games.getValue(name).timelineKey
        // Both rows have a live game: the one whose games start earliest (EPL at −2 h) comes first.
        assertEquals(listOf("Premier League", "NFL"), model.leagues.map { it.name })
        assertEquals(key("Spurs vs Fulham"), SportsLiveTimeline.moveTarget(model, key("Arsenal vs Chelsea"), TimelineMove.RIGHT))
        assertEquals(key("Arsenal vs Chelsea"), SportsLiveTimeline.moveTarget(model, key("Spurs vs Fulham"), TimelineMove.LEFT))
        assertNull(SportsLiveTimeline.moveTarget(model, key("Arsenal vs Chelsea"), TimelineMove.LEFT))
        assertNull(SportsLiveTimeline.moveTarget(model, key("Leeds vs Burnley"), TimelineMove.RIGHT))
        // Down from Spurs (+1 h): Raiders (−1 h, still running at +1 h) beats Bears (+3 h).
        assertEquals(key("Raiders @ Seahawks"), SportsLiveTimeline.moveTarget(model, key("Spurs vs Fulham"), TimelineMove.DOWN))
        // Down from Leeds (+4 h): Bears (+3 h, running) is nearest.
        assertEquals(key("Bears @ Lions"), SportsLiveTimeline.moveTarget(model, key("Leeds vs Burnley"), TimelineMove.DOWN))
        // Up from the first row leaves the Timeline (null); down from NFL goes to the "No time" row.
        assertNull(SportsLiveTimeline.moveTarget(model, key("Arsenal vs Chelsea"), TimelineMove.UP))
        assertEquals(channel.timelineKey, SportsLiveTimeline.moveTarget(model, key("Bears @ Lions"), TimelineMove.DOWN))
        assertNull(SportsLiveTimeline.moveTarget(model, channel.timelineKey, TimelineMove.DOWN))
        // Up from "No time": the game running now in the last league row.
        assertEquals(key("Raiders @ Seahawks"), SportsLiveTimeline.moveTarget(model, channel.timelineKey, TimelineMove.UP))
    }

    @Test
    fun `focus enters on the focused game, else the first live one, else the next to start`() {
        val (model, games, _) = navModel()
        assertEquals(games.getValue("Arsenal vs Chelsea").timelineKey, SportsLiveTimeline.entryKey(model, null))
        assertEquals(games.getValue("Bears @ Lions").timelineKey, SportsLiveTimeline.entryKey(model, games.getValue("Bears @ Lions").timelineKey))
        assertEquals(games.getValue("Arsenal vs Chelsea").timelineKey, SportsLiveTimeline.entryKey(model, "gone|key"))
        val soon = item("Bulls @ Heat", NOW + 2 * HOUR, isLive = false)
        val sooner = item("Celtics @ Knicks", NOW + HOUR, isLive = false)
        val upcoming = SportsLiveTimeline.model(
            listOf(row(soon, ScoreboardGroup.STARTING_SOON), row(sooner, ScoreboardGroup.STARTING_SOON)),
            SportsLiveTab.UPCOMING, NOW, utc
        )
        assertEquals(sooner.timelineKey, SportsLiveTimeline.entryKey(upcoming, null))
        assertNull(SportsLiveTimeline.entryKey(SportsLiveTimeline.model(emptyList(), SportsLiveTab.UPCOMING, NOW, utc), null))
    }

    private companion object {
        const val MIN = 60_000L
        const val HOUR = 3_600_000L
        val NOW = at("2026-11-17T18:00:00Z")
        val NOW_1416 = at("2026-11-17T14:16:00Z")

        fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()
    }
}
