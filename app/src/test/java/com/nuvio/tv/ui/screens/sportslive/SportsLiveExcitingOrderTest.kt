package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.LiveNowSort
import com.nuvio.tv.core.sportslive.SportsLiveLayout
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.ExcitementHistory
import com.nuvio.tv.core.sportslive.scores.ExcitementRating
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsLiveExcitingOrderTest {

    private val nba = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA)
    private val pacersEvent = nba.first { it.eventId == "401909985" } // IND @ PHI
    private val lakersEvent = nba.first { it.eventId == "401909986" } // LAL @ PHX

    private fun item(name: String, followed: Boolean = false, startMs: Long? = null) = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
        kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false,
        startMs = startMs, sport = "Basketball", startHasTimeOfDay = startMs != null,
        teams = SportsLiveTeams.parse(name), followed = followed
    )

    private fun live(event: ScoreSnapshot, home: Int, away: Int, clock: String) = event.copy(
        state = ScoreState.IN, isFinal = false, clockText = clock,
        home = event.home.copy(score = home), away = event.away.copy(score = away)
    )

    private fun rating(score: Double) = ExcitementRating(score, null)

    @Test
    fun `rated games go most exciting first, followed teams stay pinned first`() {
        val a = item("A")
        val b = item("B")
        val followed = item("F", followed = true)
        val ratings = mapOf(a to rating(1.0), b to rating(5.0))
        assertEquals(listOf(followed, b, a), excitingOrder(listOf(a, b, followed)) { ratings[it] })
        // Followed games are ranked among themselves too.
        val followedHot = item("G", followed = true)
        val withHot = ratings + (followedHot to rating(9.0))
        assertEquals(listOf(followedHot, followed, b, a), excitingOrder(listOf(a, b, followed, followedHot)) { withHot[it] })
    }

    @Test
    fun `unmatched or unscored games rank after scored ones and keep their order`() {
        val first = item("U1")
        val rated = item("R")
        val second = item("U2")
        val zero = item("Z")
        val ratings = mapOf(rated to rating(2.0), zero to rating(0.0))
        assertEquals(listOf(rated, zero, first, second), excitingOrder(listOf(first, rated, second, zero)) { ratings[it] })
        // Equal ratings keep start order.
        val tieA = item("TA")
        val tieB = item("TB")
        assertEquals(listOf(tieA, tieB), excitingOrder(listOf(tieA, tieB)) { rating(1.0) })
    }

    @Test
    fun `a rank or tag comes only from the card's own game`() {
        val pacers = item("Indiana Pacers at Philadelphia 76ers")
        val history = ExcitementHistory()
        val own = excitementOf(pacers, live(pacersEvent, 100, 99, "1:00 - 4th"), history, NOW)
        assertNotNull(own)
        assertNotNull(own!!.tag)
        // Another game's snapshot under this card: no rating, so no tag.
        assertNull(excitementOf(pacers, live(lakersEvent, 100, 99, "1:00 - 4th"), history, NOW))
        assertNull(excitementOf(pacers, null, history, NOW))
        // Hide until focused leaves recent changes out, so a goal never lifts a card on its own.
        history.observe(listOf(live(pacersEvent, 60, 40, "5:00 - 2nd")), NOW)
        val after = live(pacersEvent, 60, 43, "5:00 - 2nd")
        history.observe(listOf(after), NOW + 1_000)
        val shown = excitementOf(pacers, after, history, NOW + 1_000)!!
        val hidden = excitementOf(pacers, after, history, NOW + 1_000, recentChanges = false)!!
        assertTrue(shown.score > hidden.score)
        assertNull(hidden.tag)
        // A card naming no game (a channel name) is never rated.
        assertNull(excitementOf(item("Sky Sports Main Event"), live(pacersEvent, 100, 99, "1:00 - 4th"), history, NOW))
    }

    @Test
    fun `under focus the shown order holds, new cards follow`() {
        val a = item("A")
        val b = item("B")
        val c = item("C")
        assertEquals(listOf(b, a, c), stabilizeOrder(listOf(c, a, b), listOf(b.streamKey, a.streamKey)))
        assertEquals(listOf(c, a, b), stabilizeOrder(listOf(c, a, b), null))
    }

    @Test
    fun `scores Off hides the sort and falls back to start time, as does the Timeline`() {
        val exciting = LiveNowSort.MOST_EXCITING
        assertFalse(LiveNowSort.offered(ScoresMode.OFF, SportsLiveLayout.CARDS))
        assertEquals(LiveNowSort.START_TIME, LiveNowSort.effective(exciting, ScoresMode.OFF, SportsLiveLayout.CARDS))
        // Not read yet: not offered.
        assertEquals(LiveNowSort.START_TIME, LiveNowSort.effective(exciting, null, SportsLiveLayout.CARDS))
        assertFalse(LiveNowSort.offered(ScoresMode.SHOW, SportsLiveLayout.TIMELINE))
        assertEquals(LiveNowSort.START_TIME, LiveNowSort.effective(exciting, ScoresMode.SHOW, SportsLiveLayout.TIMELINE))
        for (layout in listOf(SportsLiveLayout.CARDS, SportsLiveLayout.SCOREBOARD)) {
            for (mode in listOf(ScoresMode.SHOW, ScoresMode.HIDE_UNTIL_FOCUSED)) {
                assertTrue(LiveNowSort.offered(mode, layout))
                assertEquals(exciting, LiveNowSort.effective(exciting, mode, layout))
            }
        }
        assertEquals(LiveNowSort.START_TIME, LiveNowSort.decode(null))
        assertEquals(LiveNowSort.START_TIME, LiveNowSort.decode("NOPE"))
        assertEquals(exciting, LiveNowSort.decode(exciting.name))
    }

    @Test
    fun `why tags show only with scores shown`() {
        assertTrue(LiveNowSort.showsWhyTags(ScoresMode.SHOW))
        assertFalse(LiveNowSort.showsWhyTags(ScoresMode.HIDE_UNTIL_FOCUSED))
        assertFalse(LiveNowSort.showsWhyTags(ScoresMode.OFF))
        assertFalse(LiveNowSort.showsWhyTags(null))
    }

    @Test
    fun `the Scoreboard's LIVE group follows Live now's order`() {
        val pacers = item("Indiana Pacers at Philadelphia 76ers", startMs = NOW - 2 * HOUR)
        val lakers = item("Los Angeles Lakers at Phoenix Suns", startMs = NOW - HOUR)
        val scores = mapOf(
            pacers.streamKey to live(pacersEvent, 60, 40, "5:00 - 2nd"),
            lakers.streamKey to live(lakersEvent, 100, 99, "1:00 - 4th")
        )
        fun rows(rank: Map<String, Int>) =
            scoreboardRows(listOf(pacers, lakers), SportsLiveTab.LIVE_NOW, scores, ScoresMode.SHOW, emptySet(), NOW, liveRank = rank).map { it.item }
        val usual = rows(emptyMap())
        assertEquals(usual.reversed(), rows(mapOf(usual[1].streamKey to 0, usual[0].streamKey to 1)))
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val HOUR = 3_600_000L
    }
}
