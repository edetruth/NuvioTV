package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.CatchUp
import com.nuvio.tv.core.sportslive.scores.CatchUpItem
import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.GameArt
import com.nuvio.tv.core.sportslive.scores.GameArtTeam
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.core.sportslive.SportsLiveReadyMark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/** Live Now's Your teams strip (#155, live.20 1c): its order, the replay cap, the heading, the stage and the first focus. */
class SportsLiveYourTeamsTest {

    private val zone = ZoneOffset.UTC
    private val now = ms("2026-11-19T15:00:00Z")
    private val hour = 60 * 60_000L

    private val liveFollowed = row("live-f", followed = true, group = ScoreboardGroup.LIVE)
    private val liveOther = row("live-o", followed = false, group = ScoreboardGroup.LIVE)
    private val soonFollowed = row("soon-f", followed = true, group = ScoreboardGroup.STARTING_SOON, startMs = now + hour)
    private val soon = row("soon", followed = true, group = ScoreboardGroup.STARTING_SOON, startMs = now + 30 * 60_000L)

    private val tonight = row("tonight", followed = true, group = ScoreboardGroup.LATER, startMs = ms("2026-11-19T23:30:00Z"))
    private val evening = row("evening", followed = true, group = ScoreboardGroup.LATER, startMs = ms("2026-11-19T19:00:00Z"))
    private val tomorrow = row("tomorrow", followed = true, group = ScoreboardGroup.LATER, startMs = ms("2026-11-20T01:00:00Z"))
    private val started = row("started", followed = true, group = ScoreboardGroup.LATER, startMs = now - hour)
    private val longStarted = row("long-started", followed = true, group = ScoreboardGroup.LATER, startMs = now - 4 * hour)
    private val notFollowed = row("other", followed = false, group = ScoreboardGroup.LATER, startMs = ms("2026-11-19T20:00:00Z"))

    private val nba = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA)
    private val game = nba.first { it.eventId == "401909985" }

    /** A catch-up whose estimated end was [hoursAgo] hours before [now]. */
    private fun replay(id: String, hoursAgo: Double): CatchUpItem {
        val lengthMs = CatchUp.estimatedEndMs(game, 0L)
        val start = now - (hoursAgo * hour).toLong() - lengthMs
        val event = game.copy(eventId = id, startMs = start)
        return CatchUpItem(event, listOf(item("replay-$id", kind = SportsLiveKind.REPLAY, startMs = start)))
    }

    @Test
    fun `the strip lists live now, then starting within the hour, then replays newest first, then the rest of today`() {
        val older = replay("older", hoursAgo = 30.0)
        val newer = replay("newer", hoursAgo = 5.0)
        val strip = YourTeams.strip(
            liveRows = listOf(liveFollowed, liveOther, soonFollowed),
            upcomingRows = listOf(tonight, tomorrow, evening, started, notFollowed, soon),
            catchUp = listOf(older, newer),
            nowMs = now,
            zone = zone
        )
        assertEquals(
            listOf("live|a|live-f", "today|a|started", "today|a|soon", "replay|newer", "replay|older", "today|a|evening", "today|a|tonight"),
            strip.map { it.key }
        )
    }

    @Test
    fun `a game exactly an hour away is starting soon, one just over is later today`() {
        val atHour = row("at-hour", followed = true, group = ScoreboardGroup.STARTING_SOON, startMs = now + hour)
        val overHour = row("over-hour", followed = true, group = ScoreboardGroup.LATER, startMs = now + hour + 60_000L)
        val strip = YourTeams.strip(emptyList(), listOf(overHour, atHour), listOf(replay("r", 5.0)), now, zone)
        assertEquals(listOf("today|a|at-hour", "replay|r", "today|a|over-hour"), strip.map { it.key })
    }

    @Test
    fun `at most 3 replays, the newest`() {
        val replays = listOf(1.0, 9.0, 3.0, 20.0, 6.0).mapIndexed { index, hours -> replay("r$index", hoursAgo = hours) }
        val strip = YourTeams.strip(emptyList(), emptyList(), replays, now, zone)
        assertEquals(listOf("replay|r0", "replay|r2", "replay|r4"), strip.map { it.key })
    }

    /** A replay of [replay] [id] whose home side is the team [teamId]. */
    private fun teamReplay(id: String, hoursAgo: Double, teamId: String): CatchUpItem {
        val base = replay(id, hoursAgo)
        return base.copy(event = base.event.copy(home = base.event.home.copy(id = teamId), away = base.event.away.copy(id = "opp-$id")))
    }

    private fun follows(teamId: String) = FollowedTeam(name = "Team $teamId", sport = "Basketball", espnId = teamId, league = game.league.path)

    @Test
    fun `one replay per followed team, the newest`() {
        val replays = listOf(teamReplay("a-old", 20.0, "A"), teamReplay("a-new", 2.0, "A"), teamReplay("b", 9.0, "B"))
        val strip = YourTeams.strip(emptyList(), emptyList(), replays, now, zone, followed = listOf(follows("A"), follows("B")))
        assertEquals(listOf("replay|a-new", "replay|b"), strip.map { it.key })
    }

    @Test
    fun `a kept replay stays beside its team's newest one and comes before the others for the cap`() {
        val replays = listOf(
            teamReplay("a-old", 30.0, "A"), teamReplay("a-new", 2.0, "A"),
            teamReplay("b", 3.0, "B"), teamReplay("c", 4.0, "C")
        )
        val followed = listOf(follows("A"), follows("B"), follows("C"))
        val strip = YourTeams.strip(emptyList(), emptyList(), replays, now, zone, followed = followed, kept = setOf("a-old"))
        assertEquals(listOf("replay|a-new", "replay|b", "replay|a-old"), strip.map { it.key })
    }

    @Test
    fun `with more cards than fit, the next one shows in part`() {
        assertEquals(4f, yourTeamsCardsAcross(4), 0f)
        assertTrue(yourTeamsCardsAcross(5) in 3.01f..3.99f)
    }

    @Test
    fun `the heading counts live games, replays and games later today`() {
        val strip = YourTeams.strip(listOf(liveFollowed), listOf(soon, evening, tonight), listOf(replay("a", 2.0), replay("b", 4.0)), now, zone)
        assertEquals(YourTeamsSummary(live = 1, replays = 2, later = 3), YourTeams.summary(strip))
        assertEquals(YourTeamsSummary(0, 0, 0), YourTeams.summary(emptyList()))
    }

    @Test
    fun `F5 - the heading does not count a followed game still waiting for ESPN as live`() {
        val waiting = liveFollowed.copy(awaitingScore = true)
        val strip = YourTeams.strip(listOf(waiting), emptyList(), emptyList(), now, zone)
        assertEquals(1, strip.size)
        assertEquals(0, YourTeams.summary(strip).live)
        assertEquals(1, YourTeams.summary(YourTeams.strip(listOf(liveFollowed), emptyList(), emptyList(), now, zone)).live)
    }

    @Test
    fun `L4 - the heading does not count a followed game postponed in its live place, a suspended one it does`() {
        val postponed = liveFollowed.copy(status = com.nuvio.tv.core.sportslive.GameStatus.Postponed("Postponed", mayResume = false))
        assertEquals(0, YourTeams.summary(YourTeams.strip(listOf(postponed), emptyList(), emptyList(), now, zone)).live)
        val suspended = liveFollowed.copy(status = com.nuvio.tv.core.sportslive.GameStatus.Postponed("Suspended", mayResume = true))
        assertEquals(1, YourTeams.summary(YourTeams.strip(listOf(suspended), emptyList(), emptyList(), now, zone)).live)
    }

    @Test
    fun `a game live now is not listed again as later today`() {
        val alsoUpcoming = liveFollowed.copy(group = ScoreboardGroup.LATER)
        val strip = YourTeams.strip(listOf(liveFollowed), listOf(alsoUpcoming), emptyList(), now, zone)
        assertEquals(listOf("live|a|live-f"), strip.map { it.key })
    }

    @Test
    fun `an empty strip when nothing qualifies`() {
        assertTrue(YourTeams.strip(listOf(liveOther), listOf(tomorrow, notFollowed, longStarted), emptyList(), now, zone).isEmpty())
    }

    @Test
    fun `after tip-off a game no addon lists live yet stays in the strip as Started, and OK plays it (Q17)`() {
        val strip = YourTeams.strip(emptyList(), listOf(evening, started), emptyList(), now, zone)
        val entry = strip.first() as YourTeamsEntry.LaterToday
        assertEquals("today|a|started", entry.key)
        assertTrue(entry.started)
        assertTrue(YourTeams.okPlays(entry, null))
        assertFalse((strip[1] as YourTeamsEntry.LaterToday).started)
        // Three hours on it leaves: by then it is live (in the list) or not happening.
        assertTrue(YourTeams.strip(emptyList(), listOf(longStarted), emptyList(), now, zone).isEmpty())
    }

    @Test
    fun `the stage follows the focused strip card`() {
        val catchUp = replay("r", hoursAgo = 20.0)
        val strip = YourTeams.strip(listOf(liveFollowed), listOf(evening), listOf(catchUp), now, zone)
        // A live card selects its own Live now row.
        assertEquals(YourTeamsStage.ListRow(liveFollowed.item.streamKey), YourTeams.stageFor(strip, "live|a|live-f"))
        // A later-today card shows Upcoming's stage (start time, Remind me, More…).
        assertEquals(YourTeamsStage.Upcoming(evening), YourTeams.stageFor(strip, "today|a|evening"))
        // A replay card shows Watch replay.
        assertEquals(YourTeamsStage.Replay(catchUp), YourTeams.stageFor(strip, "replay|r"))
        // Focus in the list (no strip card), or a card that has left the strip: the list's selection.
        assertNull(YourTeams.stageFor(strip, null))
        assertNull(YourTeams.stageFor(strip, "replay|gone"))
    }

    @Test
    fun `the replay stage draws ESPN's teams and start, never the replay's own title`() {
        val catchUp = replay("r", hoursAgo = 20.0)
        val row = YourTeams.replayStageRow(catchUp)
        assertEquals(GameArt.ofEvent(catchUp.event), row.art)
        assertEquals(catchUp.event.startMs, row.item.startMs)
        assertNull(row.chip)
        assertTrue(row.twoSided)
    }

    @Test
    fun `first focus lands on a followed team's live game in the strip`() {
        val strip = YourTeams.strip(listOf(liveFollowed), listOf(evening), listOf(replay("r", 5.0)), now, zone)
        assertEquals(LiveNowEntry.STRIP, YourTeams.entry(resumeShown = false, strip = strip, listHasRows = true))
    }

    @Test
    fun `with nothing live, first focus lands on the strip's first card, a replay included`() {
        val replays = YourTeams.strip(emptyList(), listOf(evening), listOf(replay("r", 5.0)), now, zone)
        assertTrue(replays.first() is YourTeamsEntry.Replay)
        assertEquals(LiveNowEntry.STRIP, YourTeams.entry(resumeShown = false, strip = replays, listHasRows = false))
        assertEquals(LiveNowEntry.EMPTY, YourTeams.entry(resumeShown = false, strip = emptyList(), listHasRows = false))
    }

    @Test
    fun `other teams' live games in the list never take first focus from the strip`() {
        val later = YourTeams.strip(emptyList(), listOf(evening), listOf(replay("r", 5.0)), now, zone)
        assertEquals(LiveNowEntry.STRIP, YourTeams.entry(resumeShown = false, strip = later, listHasRows = true))
        // No strip: the list.
        assertEquals(LiveNowEntry.LIST, YourTeams.entry(resumeShown = false, strip = emptyList(), listHasRows = true))
    }

    @Test
    fun `OK on a game not started is Watch now once Ready, else Remind me`() {
        val strip = YourTeams.strip(listOf(liveFollowed), listOf(evening), listOf(replay("r", 5.0)), now, zone)
        val later = strip.first { it is YourTeamsEntry.LaterToday }
        assertTrue(YourTeams.okPlays(later, SportsLiveReadyMark.READY))
        assertFalse(YourTeams.okPlays(later, null))
        assertFalse(YourTeams.okPlays(later, SportsLiveReadyMark.NO_STREAM))
        // Live games and replays always play.
        assertTrue(strip.filter { it !is YourTeamsEntry.LaterToday }.all { YourTeams.okPlays(it, null) })
    }

    @Test
    fun `the day's games read today before 5 pm and tonight from 5 pm`() {
        assertFalse(YourTeams.isEvening(ms("2026-11-19T16:59:00Z"), zone))
        assertTrue(YourTeams.isEvening(ms("2026-11-19T17:00:00Z"), zone))
        assertTrue(YourTeams.isEvening(ms("2026-11-19T23:30:00Z"), zone))
    }

    @Test
    fun `the Resume row keeps first focus while it is offered`() {
        val strip = YourTeams.strip(listOf(liveFollowed), emptyList(), emptyList(), now, zone)
        assertEquals(LiveNowEntry.RESUME, YourTeams.entry(resumeShown = true, strip = strip, listHasRows = true))
    }

    private fun row(id: String, followed: Boolean, group: ScoreboardGroup, startMs: Long? = null): ScoreboardRow {
        val item = item(id, followed = followed, startMs = startMs, isLive = group == ScoreboardGroup.LIVE)
        return ScoreboardRow(
            item = item,
            group = group,
            chip = null,
            art = GameArt(
                first = GameArtTeam(name = "Boston Celtics", short = "BOS", logo = null),
                second = GameArtTeam(name = "New York Knicks", short = "NYK", logo = null),
                league = "NBA",
                startMs = startMs,
                hasTimeOfDay = startMs != null
            ),
            firstFollowed = followed,
            secondFollowed = false,
            probablyEnded = false
        )
    }

    @Test
    fun `More live's games are tracked for scores while its view is open, and only then`() {
        // live.23 review M2: More live's games are outside Live now's My sports list, so none ever got a score or a final.
        val mine = item("mine", isLive = true)
        val other = item("other", isLive = true)
        val tonight = item("tonight", followed = true, startMs = ms("2026-11-19T23:30:00Z"))
        val closed = SportsLiveUiState(
            selectedTab = com.nuvio.tv.domain.model.SportsLiveTab.LIVE_NOW,
            tabs = mapOf(com.nuvio.tv.domain.model.SportsLiveTab.LIVE_NOW to listOf(mine)),
            visibleItems = listOf(mine)
        )
        assertEquals(listOf(mine, tonight), sectionTrackedCards(closed, listOf(tonight)))
        val open = closed.copy(moreLiveOpen = true, visibleItems = listOf(other))
        assertEquals(listOf(mine, other, tonight), sectionTrackedCards(open, listOf(tonight)))
        // Upcoming tracks what it shows; Channels nothing.
        val upcoming = closed.copy(selectedTab = com.nuvio.tv.domain.model.SportsLiveTab.UPCOMING, visibleItems = listOf(tonight))
        assertEquals(listOf(tonight), sectionTrackedCards(upcoming, emptyList()))
        assertEquals(emptyList<SportsLiveItem>(), sectionTrackedCards(closed.copy(selectedTab = com.nuvio.tv.domain.model.SportsLiveTab.CHANNELS), listOf(tonight)))
    }

    @Test
    fun `Live now tracks only the followed games the strip may list later today, never a week of them`() {
        // live.23 RC, TV: every followed Upcoming game was tracked, each day its own feed, and they took the live games'.
        val upcoming = listOf(
            item("evening", followed = true, startMs = ms("2026-11-19T19:00:00Z")),
            item("started", followed = true, startMs = now - hour),
            item("long-started", followed = true, startMs = now - 4 * hour),
            item("tomorrow", followed = true, startMs = ms("2026-11-20T01:00:00Z")),
            item("next-week", followed = true, startMs = ms("2026-11-26T19:00:00Z")),
            item("other", followed = false, startMs = ms("2026-11-19T20:00:00Z")),
            item("date-today", followed = true, startMs = ms("2026-11-19T00:00:00Z")).copy(startHasTimeOfDay = false),
            item("date-tomorrow", followed = true, startMs = ms("2026-11-20T00:00:00Z")).copy(startHasTimeOfDay = false),
            item("no-start", followed = true)
        )
        assertEquals(
            listOf("evening", "started", "date-today"),
            YourTeams.laterTodayTracked(upcoming, now, zone).map { it.meta.id }
        )
    }

    private fun item(
        id: String,
        followed: Boolean = false,
        startMs: Long? = null,
        isLive: Boolean = false,
        kind: SportsLiveKind = SportsLiveKind.EVENTS
    ) = SportsLiveItem(
        meta = MetaPreview(
            id = id, type = ContentType.UNKNOWN, rawType = "tv", name = "Celtics vs Knicks", poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null, releaseInfo = null,
            imdbRating = null, genres = emptyList()
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
        kind = kind, isLive = isLive, isChannel = false, startMs = startMs, sport = "NBA", startHasTimeOfDay = startMs != null,
        teams = SportsLiveTeams.parse("Celtics vs Knicks"), followed = followed
    )

    @Test
    fun `a strip arriving after Live now's rows settled is held back, never inserted above them`() {
        // live.26 TV: "Your teams 1 today" landed 15 s after opening and pushed every row down.
        val hold = YourTeamsStripHold()
        val strip = listOf("tonight")
        assertEquals(emptyList<String>(), hold.shown(emptyList<String>(), listShown = true, epoch = 0, nowMs = 0L))
        assertEquals("held", emptyList<String>(), hold.shown(strip, listShown = true, epoch = 0, nowMs = 15_000L))
        assertEquals("still held", emptyList<String>(), hold.shown(strip, listShown = true, epoch = 0, nowMs = 30_000L))
        // A fresh layout ("N new", a follow, My sports) shows it.
        assertEquals(strip, hold.shown(strip, listShown = true, epoch = 1, nowMs = 31_000L))
    }

    @Test
    fun `a strip in the landing window or already showing keeps following its games`() {
        val hold = YourTeamsStripHold()
        assertEquals(emptyList<String>(), hold.shown(emptyList<String>(), listShown = true, epoch = 0, nowMs = 0L))
        // Within the landing window, as the list's first scores and logos land.
        assertEquals(listOf("live"), hold.shown(listOf("live"), listShown = true, epoch = 0, nowMs = 500L))
        // Already showing: a game more is a card more inside the strip, which moves no row.
        assertEquals(listOf("live", "tonight"), hold.shown(listOf("live", "tonight"), listShown = true, epoch = 0, nowMs = 15_000L))
        // Before any row shows (loading rows, another tab), nothing is held.
        val loading = YourTeamsStripHold()
        assertEquals(listOf("tonight"), loading.shown(listOf("tonight"), listShown = false, epoch = 0, nowMs = 15_000L))
        // Back on Live now after another tab: a new landing window.
        val other = YourTeamsStripHold()
        other.shown(emptyList<String>(), listShown = true, epoch = 0, nowMs = 0L)
        other.shown(emptyList<String>(), listShown = false, epoch = 0, nowMs = 20_000L)
        other.shown(emptyList<String>(), listShown = true, epoch = 0, nowMs = 21_000L)
        assertEquals(listOf("tonight"), other.shown(listOf("tonight"), listShown = true, epoch = 0, nowMs = 21_200L))
    }

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()
}
