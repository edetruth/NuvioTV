package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.SportsLiveSportKind
import com.nuvio.tv.core.sportslive.scores.GameArt
import com.nuvio.tv.core.sportslive.scores.GameArtTeam
import com.nuvio.tv.core.sportslive.scores.PeriodLabel
import com.nuvio.tv.core.sportslive.scores.ScoreDetails
import com.nuvio.tv.core.sportslive.scores.ScoreDetailsSide
import com.nuvio.tv.core.sportslive.scores.ScoreDetailsStrings
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** live.20: the stage's row (F23), its art (F7, item 5), its facts line, the rows' marks and badges, and the board's builds (F21). */
@OptIn(ExperimentalCoroutinesApi::class)
class SportsLiveStageInfoTest {

    // ---- Which row the stage shows (F23) ----

    @Test
    fun `the stage shows the focused row while it is listed`() {
        assertEquals("b", sportsLiveStageKey(listOf("a", "b", "c"), focusedKey = "b", miniKey = "c"))
    }

    @Test
    fun `with no focused row the mini player's game is shown, else the first row`() {
        // After stopping the mini player with focus on the tab: its game, never an unrelated first item.
        assertEquals("c", sportsLiveStageKey(listOf("a", "b", "c"), focusedKey = null, miniKey = "c"))
        assertEquals("c", sportsLiveStageKey(listOf("a", "b", "c"), focusedKey = "gone", miniKey = "c"))
        assertEquals("a", sportsLiveStageKey(listOf("a", "b", "c"), focusedKey = null, miniKey = "elsewhere"))
        assertEquals("a", sportsLiveStageKey(listOf("a", "b", "c"), focusedKey = null, miniKey = null))
        assertNull(sportsLiveStageKey(emptyList(), focusedKey = "a", miniKey = "a"))
    }

    // ---- The media box (F7, item 5) ----

    @Test
    fun `a two-sided game is drawn, any other game or event gets the drawn event card, never addon art`() {
        assertEquals(StageArtKind.MATCHUP, stageArtKind(row(item("g", poster = "https://example.invalid/p.png"), art = ART)))
        // A tournament feed with addon art (its name and a letter circle painted in): the drawn event card.
        assertEquals(StageArtKind.EVENT, stageArtKind(row(item("atp", poster = "https://example.invalid/p.png"))))
        assertEquals(StageArtKind.EVENT, stageArtKind(row(item("race"))))
        // Two parsed sides that are not a real match stay a single-line event.
        assertEquals(StageArtKind.EVENT, stageArtKind(row(item("g2"), art = ART, twoSided = false)))
    }

    @Test
    fun `a channel keeps its own art, and one with none shows its name, never an empty box`() {
        val withLogo = item("tsn", channel = true, logo = "https://example.invalid/l.png")
        assertEquals(StageArtKind.CHANNEL_ART, stageArtKind(row(withLogo, group = ScoreboardGroup.CHANNELS)))
        assertEquals(StageArtKind.CHANNEL_NAME, stageArtKind(row(item("bare", channel = true), group = ScoreboardGroup.CHANNELS)))
    }

    // ---- Marks (item 4) ----

    @Test
    fun `a tennis feed with no players carries the tournament trophy, other events their sport`() {
        assertEquals(SportsLiveEventMark.TROPHY, eventMarkOf(SportsLiveSportKind.TENNIS))
        assertEquals(SportsLiveEventMark.TROPHY, eventMarkOf(null))
        assertEquals(SportsLiveEventMark.MOTORSPORT, eventMarkOf(SportsLiveSportKind.MOTORSPORT))
        assertEquals(SportsLiveEventMark.SOCCER, eventMarkOf(SportsLiveSportKind.SOCCER))
        assertEquals(SportsLiveEventMark.FIGHT, eventMarkOf(SportsLiveSportKind.BOXING))
        // Every sport has a mark.
        SportsLiveSportKind.entries.forEach { eventMarkOf(it) }
    }

    // ---- Facts line ----

    @Test
    fun `a live game's facts name Stream ready and its start, never how many sources list it`() {
        val start = NOW - 63 * MINUTE
        val game = item("g", startMs = start, timed = true, alternates = 4)
        val facts = sportsLiveStageFacts(row(game, art = ART), ready = true, nowMs = NOW)
        assertEquals(StageFacts(ready = true, startedAtMs = start), facts)
        assertFalse(facts.isEmpty)
    }

    @Test
    fun `no start for a date without a time, a game not started or a channel, and nothing else says nothing`() {
        assertNull(sportsLiveStageFacts(row(item("d", startMs = NOW - HOUR, timed = false)), ready = false, nowMs = NOW).startedAtMs)
        assertNull(sportsLiveStageFacts(row(item("s", startMs = NOW + HOUR, timed = true), group = ScoreboardGroup.STARTING_SOON), false, NOW).startedAtMs)
        val channel = item("c", channel = true, startMs = NOW - HOUR, timed = true)
        assertNull(sportsLiveStageFacts(row(channel, group = ScoreboardGroup.CHANNELS), false, NOW).startedAtMs)
        val alone = sportsLiveStageFacts(row(item("one", alternates = 4)), ready = false, nowMs = NOW)
        assertTrue(alone.isEmpty)
    }

    @Test
    fun `ESPN's start counts when the card has no time of day`() {
        val espnStart = NOW - 30 * MINUTE
        val facts = sportsLiveStageFacts(row(item("g"), art = ART.copy(startMs = espnStart, hasTimeOfDay = true)), false, NOW)
        assertEquals(espnStart, facts.startedAtMs)
    }

    // ---- Compact linescore ----

    @Test
    fun `the stage's linescore is the last three periods on one line`() {
        val shown = ScoreDetails.Shown(
            first = ScoreDetailsSide("BOS", 91, listOf(22, 25, 31, 13), emptyList()),
            second = ScoreDetailsSide("NYK", 88, listOf(20, 30, 25, null), emptyList()),
            status = null, live = true,
            periods = listOf(PeriodLabel.Number(1), PeriodLabel.Number(2), PeriodLabel.Number(3), PeriodLabel.Number(4)),
            soccer = false
        )
        assertEquals("2nd 25–30 · 3rd 31–25 · 4th 13–", stageLinescoreText(shown, DETAIL_STRINGS))
        val overtime = shown.copy(
            first = shown.first.copy(linescores = listOf(22, 25, 7)),
            second = shown.second.copy(linescores = listOf(20, 30, 5)),
            periods = listOf(PeriodLabel.Number(1), PeriodLabel.Number(2), PeriodLabel.Overtime(1))
        )
        assertEquals("1st 22–20 · 2nd 25–30 · OT 7–5", stageLinescoreText(overtime, DETAIL_STRINGS))
        assertNull(stageLinescoreText(shown.copy(periods = emptyList()), DETAIL_STRINGS))
    }

    // ---- The row's one badge ----

    @Test
    fun `Ready takes the row's one badge, then excitement, then close game`() {
        assertEquals(RowBadge.READY, rowBadgeOf(ready = true, excitement = true, closeGame = true))
        assertEquals(RowBadge.EXCITEMENT, rowBadgeOf(ready = false, excitement = true, closeGame = true))
        assertEquals(RowBadge.CLOSE_GAME, rowBadgeOf(ready = false, excitement = false, closeGame = true))
        assertEquals(RowBadge.NONE, rowBadgeOf(ready = false, excitement = false, closeGame = false))
    }

    @Test
    fun `a live row with nothing playable says No stream yet, unless a verified link is held`() {
        assertEquals(RowBadge.NO_STREAM, rowBadgeOf(ready = false, excitement = true, closeGame = true, noStream = true))
        assertEquals(RowBadge.READY, rowBadgeOf(ready = true, excitement = false, closeGame = false, noStream = true))
    }

    @Test
    fun `the mini player's game says Watching in the badge place, never over its status (BUG-D)`() {
        assertEquals(RowBadge.WATCHING, rowBadgeOf(ready = true, excitement = false, closeGame = false, noStream = true, watching = true))
        // The close-game and excitement badges outrank Watching: the mini player already says what is watching.
        assertEquals(RowBadge.EXCITEMENT, rowBadgeOf(ready = true, excitement = true, closeGame = true, watching = true))
        assertEquals(RowBadge.CLOSE_GAME, rowBadgeOf(ready = true, excitement = false, closeGame = true, watching = true))
        assertEquals(RowBadge.READY, rowBadgeOf(ready = true, excitement = false, closeGame = false, watching = false))
    }

    // ---- Board builds (F21) ----

    @Test
    fun `an update of the same view lets the running build finish, and the latest waiting one follows`() = runTest {
        val built = flow {
            emit("live" to 1)
            delay(10)
            emit("live" to 2)
            delay(10)
            emit("live" to 3)
        }.mapFinishing({ running, next -> running.first != next.first }) { value ->
            delay(100)
            value
        }.toList()
        // The first board lands after one build (mapLatest would have waited for the inputs to settle); 2 is skipped.
        assertEquals(listOf("live" to 1, "live" to 3), built)
    }

    @Test
    fun `another view cancels the running build at once`() = runTest {
        val results = flow {
            emit("live" to 1)
            delay(10)
            emit("channels" to 1)
        }.mapFinishing({ running, next -> running.first != next.first }) { value ->
            delay(100)
            value
        }.toList()
        assertEquals(listOf("channels" to 1), results)
    }

    @Test
    fun `a view opened again shows its last result at once, then the fresh one`() = runTest {
        val held = mapOf("upcoming" to ("upcoming" to 0))
        val results = flow {
            emit("live" to 1)
            delay(200)
            emit("upcoming" to 1)
        }.mapFinishing({ running, next -> running.first != next.first }, instant = { held[it.first] }) { value ->
            delay(100)
            value
        }.toList()
        assertEquals(listOf("live" to 1, "upcoming" to 0, "upcoming" to 1), results)
    }

    @Test
    fun `after the first value a burst comes out once and a trickle once per window`() = runTest {
        val out = flow {
            emit(1)
            delay(10)
            emit(2)
            delay(10)
            emit(3)
            delay(1_000)
            repeat(5) {
                emit(10 + it)
                delay(120)
            }
        }.firstThenCoalesce(300).toList()
        assertEquals(listOf(1, 3, 12, 14), out)
    }

    @Test
    fun `values far apart are each built`() = runTest {
        val results = flow {
            emit(1)
            delay(500)
            emit(2)
        }.mapFinishing({ _, _ -> false }) { value ->
            delay(100)
            value * 10
        }.toList()
        assertEquals(listOf(10, 20), results)
    }

    private fun row(
        item: SportsLiveItem,
        group: ScoreboardGroup = ScoreboardGroup.LIVE,
        art: GameArt? = null,
        twoSided: Boolean = art != null
    ) = ScoreboardRow(
        item = item, group = group, chip = null, art = art,
        firstFollowed = false, secondFollowed = false, probablyEnded = false, twoSided = twoSided
    )

    private fun item(
        id: String,
        channel: Boolean = false,
        poster: String? = null,
        logo: String? = null,
        startMs: Long? = null,
        timed: Boolean = false,
        alternates: Int = 0
    ): SportsLiveItem {
        val base = SportsLiveItem(
            meta = MetaPreview(
                id = id, type = ContentType.UNKNOWN, rawType = "tv", name = id, poster = poster,
                posterShape = PosterShape.LANDSCAPE, background = null, logo = logo,
                description = null, releaseInfo = null, imdbRating = null, genres = emptyList()
            ),
            addonId = "x", addonName = "x", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
            kind = if (channel) SportsLiveKind.CHANNELS else SportsLiveKind.EVENTS, isLive = true, isChannel = channel,
            startMs = startMs, sport = null, startHasTimeOfDay = timed
        )
        return base.copy(alternates = (1..alternates).map { base.copy(addonId = "alt$it") })
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        val DETAIL_STRINGS = ScoreDetailsStrings(
            scoreline = "%1\$s %2\$d–%3\$d %4\$s",
            sides = "%1\$s – %2\$s",
            overtime = "OT",
            overtimeN = "%1\$dOT",
            extraTime = "ET%1\$d",
            shootout = "SO",
            emptyPeriod = "–",
            scorer = "%1\$s %2\$s",
            penalty = "%1\$s (pen)",
            ownGoal = "%1\$s (OG)",
            separator = ", "
        )
        val ART = GameArt(
            first = GameArtTeam("Arsenal", "ARS", null),
            second = GameArtTeam("Chelsea", "CHE", null),
            league = "Premier League",
            startMs = null,
            hasTimeOfDay = false
        )
    }
}
