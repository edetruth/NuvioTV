package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.SportsLiveSportKind
import com.nuvio.tv.core.sportslive.scores.GameArt
import com.nuvio.tv.core.sportslive.scores.GameArtTeam
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
    fun `a live game's facts name its start, its sources and Ready`() {
        val start = NOW - 63 * MINUTE
        val game = item("g", startMs = start, timed = true, alternates = 4)
        val facts = sportsLiveStageFacts(row(game, art = ART), ready = true, nowMs = NOW)
        assertEquals(StageFacts(ready = true, startedAtMs = start, sources = 5), facts)
        assertFalse(facts.isEmpty)
    }

    @Test
    fun `no start for a date without a time, a game not started or a channel, and one source says nothing`() {
        assertNull(sportsLiveStageFacts(row(item("d", startMs = NOW - HOUR, timed = false)), ready = false, nowMs = NOW).startedAtMs)
        assertNull(sportsLiveStageFacts(row(item("s", startMs = NOW + HOUR, timed = true), group = ScoreboardGroup.STARTING_SOON), false, NOW).startedAtMs)
        val channel = item("c", channel = true, startMs = NOW - HOUR, timed = true)
        assertNull(sportsLiveStageFacts(row(channel, group = ScoreboardGroup.CHANNELS), false, NOW).startedAtMs)
        val alone = sportsLiveStageFacts(row(item("one")), ready = false, nowMs = NOW)
        assertNull(alone.sources)
        assertTrue(alone.isEmpty)
    }

    @Test
    fun `ESPN's start counts when the card has no time of day`() {
        val espnStart = NOW - 30 * MINUTE
        val facts = sportsLiveStageFacts(row(item("g"), art = ART.copy(startMs = espnStart, hasTimeOfDay = true)), false, NOW)
        assertEquals(espnStart, facts.startedAtMs)
    }

    // ---- The row's one badge ----

    @Test
    fun `Ready takes the row's one badge, then excitement, then close game`() {
        assertEquals(RowBadge.READY, rowBadgeOf(ready = true, excitement = true, closeGame = true))
        assertEquals(RowBadge.EXCITEMENT, rowBadgeOf(ready = false, excitement = true, closeGame = true))
        assertEquals(RowBadge.CLOSE_GAME, rowBadgeOf(ready = false, excitement = false, closeGame = true))
        assertEquals(RowBadge.NONE, rowBadgeOf(ready = false, excitement = false, closeGame = false))
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
        val ART = GameArt(
            first = GameArtTeam("Arsenal", "ARS", null),
            second = GameArtTeam("Chelsea", "CHE", null),
            league = "Premier League",
            startMs = null,
            hasTimeOfDay = false
        )
    }
}
