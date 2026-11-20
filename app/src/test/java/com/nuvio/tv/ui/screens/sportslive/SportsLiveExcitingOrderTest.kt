package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.ExcitementHistory
import com.nuvio.tv.core.sportslive.scores.WhyKind
import com.nuvio.tv.core.sportslive.scores.WhyTag
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

/** The excitement badge (#43, V2): rated from the card's own game, drawn on live rows only, never a re-order. */
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

    @Test
    fun `a badge comes only from the card's own game`() {
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
    fun `the badge sits on live rows only and keeps the row order`() {
        val pacers = item("Indiana Pacers at Philadelphia 76ers", startMs = NOW - 3_600_000L)
        val lakers = item("Los Angeles Lakers at Phoenix Suns", startMs = NOW - 1_800_000L)
        val scores = mapOf(
            pacers.streamKey to live(pacersEvent, 60, 40, "5:00 - 2nd"),
            // Not crunch time (#137 would move it into Live Now's big-game slot): the badge alone never re-orders.
            lakers.streamKey to live(lakersEvent, 100, 92, "1:00 - 4th")
        )
        val badge = WhyTag(WhyKind.CLOSE_LATE, 1)
        val rows = scoreboardRows(
            listOf(pacers, lakers), SportsLiveTab.LIVE_NOW, scores, ScoresMode.SHOW, emptySet(), NOW,
            excitement = mapOf(lakers.streamKey to badge, "nobody" to WhyTag(WhyKind.OVERTIME))
        )
        assertEquals(listOf(pacers, lakers), rows.map { it.item })
        assertNull(rows[0].excitement)
        assertEquals(badge, rows[1].excitement)
        // An upcoming row never carries one.
        val soon = item("Boston Celtics at Dallas Mavericks", startMs = NOW + 3_600_000L).copy(isLive = false)
        val upcoming = scoreboardRows(listOf(soon), SportsLiveTab.UPCOMING, emptyMap(), ScoresMode.SHOW, emptySet(), NOW, excitement = mapOf(soon.streamKey to badge))
        assertNull(upcoming.single().excitement)
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val HOUR = 3_600_000L
    }
}
