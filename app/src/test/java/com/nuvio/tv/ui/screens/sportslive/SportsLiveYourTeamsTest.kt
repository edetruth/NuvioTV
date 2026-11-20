package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.CatchUp
import com.nuvio.tv.core.sportslive.scores.CatchUpItem
import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.GameArt
import com.nuvio.tv.core.sportslive.scores.GameArtTeam
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/** Live Now's Your teams strip (#155): its order, the 48 h cutoff, the stage following it and the first focus. */
class SportsLiveYourTeamsTest {

    private val zone = ZoneOffset.UTC
    private val now = ms("2026-11-19T15:00:00Z")
    private val hour = 60 * 60_000L

    private val liveFollowed = row("live-f", followed = true, group = ScoreboardGroup.LIVE)
    private val liveOther = row("live-o", followed = false, group = ScoreboardGroup.LIVE)
    private val soonFollowed = row("soon-f", followed = true, group = ScoreboardGroup.STARTING_SOON, startMs = now + hour)

    private val tonight = row("tonight", followed = true, group = ScoreboardGroup.LATER, startMs = ms("2026-11-19T23:30:00Z"))
    private val evening = row("evening", followed = true, group = ScoreboardGroup.LATER, startMs = ms("2026-11-19T19:00:00Z"))
    private val tomorrow = row("tomorrow", followed = true, group = ScoreboardGroup.LATER, startMs = ms("2026-11-20T01:00:00Z"))
    private val started = row("started", followed = true, group = ScoreboardGroup.LATER, startMs = now - hour)
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
    fun `the strip lists live now, then later today soonest first, then replays newest first`() {
        val older = replay("older", hoursAgo = 30.0)
        val newer = replay("newer", hoursAgo = 5.0)
        val strip = YourTeams.strip(
            liveRows = listOf(liveFollowed, liveOther, soonFollowed),
            upcomingRows = listOf(tonight, tomorrow, evening, started, notFollowed),
            catchUp = listOf(older, newer),
            nowMs = now,
            zone = zone
        )
        assertEquals(
            listOf("live|a|live-f", "today|a|evening", "today|a|tonight", "replay|newer", "replay|older"),
            strip.map { it.key }
        )
    }

    @Test
    fun `a game live now is not listed again as later today`() {
        val alsoUpcoming = liveFollowed.copy(group = ScoreboardGroup.LATER)
        val strip = YourTeams.strip(listOf(liveFollowed), listOf(alsoUpcoming), emptyList(), now, zone)
        assertEquals(listOf("live|a|live-f"), strip.map { it.key })
    }

    @Test
    fun `catch-ups older than about 48 h leave the strip`() {
        val fresh = replay("fresh", hoursAgo = 47.0)
        val stale = replay("stale", hoursAgo = 49.0)
        assertTrue(YourTeams.fresh(fresh, now))
        assertTrue(!YourTeams.fresh(stale, now))
        val strip = YourTeams.strip(emptyList(), emptyList(), listOf(stale, fresh), now, zone)
        assertEquals(listOf("replay|fresh"), strip.map { it.key })
    }

    @Test
    fun `an empty strip when nothing qualifies`() {
        assertTrue(YourTeams.strip(listOf(liveOther), listOf(tomorrow, notFollowed), listOf(replay("stale", 60.0)), now, zone).isEmpty())
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
    fun `first focus never lands on a catch-up replay`() {
        val replays = YourTeams.strip(emptyList(), emptyList(), listOf(replay("r", 5.0)), now, zone)
        assertEquals(LiveNowEntry.LIST, YourTeams.entry(resumeShown = false, strip = replays, listHasRows = true))
        assertEquals(LiveNowEntry.EMPTY, YourTeams.entry(resumeShown = false, strip = replays, listHasRows = false))
    }

    @Test
    fun `first focus is the first live game when no followed team is live`() {
        val later = YourTeams.strip(emptyList(), listOf(evening), listOf(replay("r", 5.0)), now, zone)
        assertEquals(LiveNowEntry.LIST, YourTeams.entry(resumeShown = false, strip = later, listHasRows = true))
        // Nothing live at all: the game later today.
        assertEquals(LiveNowEntry.STRIP, YourTeams.entry(resumeShown = false, strip = later, listHasRows = false))
        assertEquals(LiveNowEntry.LIST, YourTeams.entry(resumeShown = false, strip = emptyList(), listHasRows = true))
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

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()
}
