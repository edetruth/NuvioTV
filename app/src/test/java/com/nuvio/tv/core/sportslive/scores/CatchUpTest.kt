package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class CatchUpTest {

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()
    private val hour = 60 * 60_000L
    private val nov15 = LocalDate.of(2026, 11, 15)

    // Pacers at 76ers (18:00Z) and Knicks at Raptors (23:00Z), both final, on ESPN's 2026-11-15.
    private val nba = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA)
    private val pacersId = "401909985"
    private val knicksId = "401909990"
    private val pacersStart = ms("2026-11-15T18:00:00Z")
    private val day = CatchUpDay(nov15, nba, nba.filter { it.state == ScoreState.POST && it.isFinal })

    private val pacers = FollowedTeam("Indiana Pacers", "Basketball")
    private val knicks = FollowedTeam("New York Knicks", "Basketball")
    private val pacersReplay = replay("Indiana Pacers vs Philadelphia 76ers", "2026-11-15T18:00:00Z")
    private val knicksReplay = replay("New York Knicks vs Toronto Raptors", "2026-11-15T23:00:00Z")
    private val now = ms("2026-11-16T12:00:00Z")
    private val longAgo = now - CatchUp.WINDOW_MS

    private fun replay(
        name: String,
        start: String?,
        timed: Boolean = true,
        isChannel: Boolean = false,
        addonId: String = "a",
        type: ContentType = ContentType.UNKNOWN
    ) = SportsLiveItem(
        meta = MetaPreview(
            id = "$addonId:$name", type = type, rawType = if (type == ContentType.UNKNOWN) "sport" else type.toApiString(), name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = listOf("Basketball")
        ),
        addonId = addonId, addonName = addonId, addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Replays",
        kind = if (isChannel) SportsLiveKind.CHANNELS else SportsLiveKind.REPLAY, isLive = false, isChannel = isChannel,
        startMs = start?.let(::ms), sport = "Basketball", startHasTimeOfDay = timed, teams = SportsLiveTeams.parse(name)
    )

    private fun select(
        followed: List<FollowedTeam> = listOf(pacers),
        replays: List<SportsLiveItem> = listOf(pacersReplay, knicksReplay),
        since: Long = longAgo,
        marks: CatchUpMarks = CatchUpMarks(),
        nowMs: Long = now,
        days: List<CatchUpDay> = listOf(day)
    ) = CatchUp.select(days, replays, followed, since, marks, nowMs).items.map { it.key }

    // --- Only followed teams ---

    @Test
    fun `only followed teams' finals are listed`() {
        assertEquals(listOf(pacersId), select())
        assertEquals(listOf(knicksId, pacersId), select(followed = listOf(pacers, knicks)))
        assertTrue(select(followed = emptyList()).isEmpty())
        // A namesake in another sport is not the team.
        assertTrue(select(followed = listOf(FollowedTeam("Indiana Pacers", "Hockey"))).isEmpty())
    }

    // --- Only strict replay matches ---

    @Test
    fun `a followed final with no strict replay match is never listed`() {
        assertTrue(select(replays = listOf(knicksReplay)).isEmpty())
        val loose = listOf(
            replay("Indiana Pacers vs Boston Celtics", "2026-11-15T18:00:00Z"), // one side only
            replay("Indiana Pacers vs Philadelphia 76ers", "2026-11-12T18:00:00Z"), // another day
            replay("Indiana Pacers vs Philadelphia 76ers", "2026-11-16T00:00:00Z", timed = false), // the day after
            replay("Indiana Pacers vs Philadelphia 76ers", null), // no date
            replay("Indiana Pacers", "2026-11-15T18:00:00Z"), // a team folder
            replay("NBA TV [Indiana Pacers vs Philadelphia 76ers]", "2026-11-15T18:00:00Z", isChannel = true)
        )
        assertTrue(select(replays = loose).isEmpty())
    }

    @Test
    fun `the item carries the strict replay match`() {
        val items = CatchUp.select(listOf(day), listOf(knicksReplay, pacersReplay), listOf(pacers), longAgo, CatchUpMarks(), now).items
        assertEquals(pacersReplay, items.single().replay)
    }

    @Test
    fun `each source's strict match is kept, one per source, and a series folder never counts`() {
        val other = replay("Indiana Pacers vs Philadelphia 76ers", "2026-11-15T18:00:00Z", addonId = "b")
        val sameSource = replay("Indiana Pacers vs Philadelphia 76ers", "2026-11-15T18:05:00Z")
        val items = CatchUp.select(listOf(day), listOf(pacersReplay, sameSource, other), listOf(pacers), longAgo, CatchUpMarks(), now).items
        assertEquals(listOf(pacersReplay, other), items.single().replays)

        val series = replay("Indiana Pacers vs Philadelphia 76ers", "2026-11-15T18:00:00Z", type = ContentType.SERIES)
        assertTrue(select(replays = listOf(series)).isEmpty())
    }

    // --- Pending: a replay that arrives late ---

    @Test
    fun `a followed final with no replay yet is pending, and listed once a replay arrives, however far since moved`() {
        val waiting = CatchUp.select(listOf(day), listOf(knicksReplay), listOf(pacers), longAgo, CatchUpMarks(), now)
        assertTrue(waiting.items.isEmpty())
        assertEquals(mapOf(pacersId to pacersStart), waiting.pending)

        // Next visit, long after the game: since alone would drop it; pending keeps it.
        val laterSince = pacersStart + 20 * hour
        assertTrue(select(since = laterSince).isEmpty())
        assertEquals(listOf(pacersId), select(since = laterSince, marks = CatchUpMarks(pending = waiting.pending)))
    }

    @Test
    fun `a pending game expires 4 days after its start`() {
        val marks = CatchUpMarks(pending = mapOf(pacersId to pacersStart))
        val later = pacersStart + CatchUp.WINDOW_MS + hour
        assertTrue(select(since = later, marks = marks, nowMs = later).isEmpty())
        val muchLater = later + 6 * hour
        assertTrue(CatchUp.pruned(marks, muchLater).pending.isEmpty())
        assertEquals(marks, CatchUp.pruned(marks, now))
    }

    @Test
    fun `remembering moves a listed game out of pending and keeps at most the newest pending`() {
        val listed = CatchUp.select(listOf(day), listOf(pacersReplay), listOf(pacers), longAgo, CatchUpMarks(), now).items
        val marks = CatchUp.remembered(CatchUpMarks(pending = mapOf(pacersId to pacersStart)), listed, emptyMap())
        assertTrue(marks.pending.isEmpty())
        assertEquals(mapOf(pacersId to pacersStart), marks.surfaced)

        val many = (0 until CatchUp.MAX_PENDING + 5).associate { "e$it" to it.toLong() }
        val kept = CatchUp.remembered(CatchUpMarks(), emptyList(), many).pending
        assertEquals(CatchUp.MAX_PENDING, kept.size)
        assertTrue("the oldest go first", "e0" !in kept && "e${CatchUp.MAX_PENDING + 4}" in kept)
    }

    @Test
    fun `games with no time or not final are never listed`() {
        val untimed = day.copy(finals = day.finals.map { if (it.eventId == pacersId) it.copy(timeValid = false) else it })
        assertTrue(select(days = listOf(untimed)).isEmpty())
        val live = day.copy(finals = day.finals.map { if (it.eventId == pacersId) it.copy(state = ScoreState.IN, isFinal = false) else it })
        assertTrue(select(days = listOf(live)).isEmpty())
    }

    // --- The time window ---

    @Test
    fun `a final that ended before the last visit is not listed, one still being played then is`() {
        // An NBA game is taken to run 3 h plus the 20 min late-start allowance (the #60 table).
        assertEquals(pacersStart + 200 * 60_000L, CatchUp.estimatedEndMs(day.finals.first { it.eventId == pacersId }, pacersStart))
        // Last here 4 h after tip-off: the game was over.
        assertTrue(select(since = pacersStart + 4 * hour).isEmpty())
        // Last here 3 h after tip-off: it may still have been played, so it ended since.
        assertEquals(listOf(pacersId), select(since = pacersStart + 3 * hour))
        assertEquals(listOf(pacersId), select(since = pacersStart - hour))
    }

    @Test
    fun `nothing older than 4 days, whatever the last visit`() {
        val later = pacersStart + CatchUp.WINDOW_MS + hour
        assertEquals(listOf(knicksId), select(followed = listOf(pacers, knicks), since = 0L, nowMs = later))
    }

    @Test
    fun `a game saved for later is listed past the window, until its protection ends`() {
        val later = pacersStart + CatchUp.WINDOW_MS + 24 * hour
        val items = CatchUp.select(listOf(day), listOf(pacersReplay), listOf(pacers), later, CatchUpMarks(), later, savedForLater = setOf(pacersId)).items
        assertEquals(listOf(pacersId), items.map { it.key })
        // Watched: gone, saved or not.
        val watched = CatchUpMarks(seen = mapOf(pacersId to later))
        assertTrue(CatchUp.select(listOf(day), listOf(pacersReplay), listOf(pacers), later, watched, later, savedForLater = setOf(pacersId)).items.isEmpty())
    }

    @Test
    fun `a game listed before stays after the last visit moves on`() {
        val marks = CatchUpMarks(surfaced = mapOf(pacersId to pacersStart))
        assertEquals(listOf(pacersId), select(since = now, marks = marks))
    }

    @Test
    fun `since is the last visit before a gap, else the one kept, and 4 days back when never opened`() {
        assertEquals(now - CatchUp.WINDOW_MS, CatchUp.since(null, null, now))
        val lastOpened = now - 5 * hour
        assertEquals(lastOpened, CatchUp.since(lastOpened, now - 30 * hour, now))
        // Opened again within the gap: the same visit, its since kept.
        assertEquals(now - 30 * hour, CatchUp.since(now - 10 * 60_000L, now - 30 * hour, now))
    }

    @Test
    fun `the days loaded go back to since, never past 4 days, and to games saved for later`() {
        val easternDate = CatchUp::easternDate
        assertEquals(0..1, CatchUp.daysAgo(pacersStart, CatchUpMarks(), now, easternDate))
        assertEquals(0..4, CatchUp.daysAgo(0L, CatchUpMarks(), now, easternDate))
        // A game saved for later 6 days back is loaded; one saved longer ago than Watch later lasts is not.
        assertEquals(0..6, CatchUp.daysAgo(now, CatchUpMarks(), now, easternDate, savedStarts = listOf(ms("2026-11-10T18:00:00Z"))))
        assertEquals(0..6, CatchUp.daysAgo(now, CatchUpMarks(), now, easternDate, savedStarts = listOf(ms("2026-11-01T18:00:00Z"))))
        assertEquals(0..0, CatchUp.daysAgo(now, CatchUpMarks(), now, easternDate))
        assertEquals(0..1, CatchUp.daysAgo(now, CatchUpMarks(surfaced = mapOf(pacersId to pacersStart)), now, easternDate))
    }

    // --- Removal ---

    @Test
    fun `a watched replay leaves the row`() {
        val marks = CatchUpMarks(seen = mapOf(pacersId to now), surfaced = mapOf(pacersId to pacersStart))
        assertTrue(select(marks = marks).isEmpty())
    }

    @Test
    fun `marks older than the window are dropped, watched and known ones after Watch later's length`() {
        val oldWatched = now - ReplayLifetime.WATCH_LATER_MS - 24 * hour
        val marks = CatchUpMarks(
            seen = mapOf("old" to oldWatched, "kept" to now - CatchUp.WINDOW_MS - 6 * hour, pacersId to now),
            surfaced = mapOf("old" to now - CatchUp.WINDOW_MS - 6 * hour, pacersId to pacersStart),
            known = mapOf("old" to oldWatched, pacersId to now)
        )
        assertEquals(
            CatchUpMarks(
                seen = mapOf("kept" to now - CatchUp.WINDOW_MS - 6 * hour, pacersId to now),
                surfaced = mapOf(pacersId to pacersStart),
                known = mapOf(pacersId to now)
            ),
            CatchUp.pruned(marks, now)
        )
    }

    @Test
    fun `followed teams' feeds are their sports' feeds only`() {
        assertEquals(listOf(EspnLeagueMap.NBA), CatchUp.feeds(listOf(pacers, knicks)))
        assertEquals(listOf(EspnLeagueMap.NBA, EspnLeagueMap.SOCCER_ALL), CatchUp.feeds(listOf(pacers, FollowedTeam("Arsenal", "Soccer"))))
        assertTrue(CatchUp.feeds(listOf(FollowedTeam("Arsenal"))).isEmpty())
    }
}
