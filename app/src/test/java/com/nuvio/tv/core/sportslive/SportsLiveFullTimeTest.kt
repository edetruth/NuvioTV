package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.scores.ChipScore
import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Ended while you're watching" (#60): the full-time toast for the game playing. */
class SportsLiveFullTimeTest {

    @After
    fun tearDown() {
        SportsLiveFullTimeAnnouncements.clear()
    }

    private val pacersEvent = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA).first { it.eventId == "401909985" } // IND @ PHI

    // The card names the away team first: its score comes first.
    private val pacers = SportsLiveItem(
        meta = MetaPreview(
            id = "p", type = ContentType.UNKNOWN, rawType = "sport", name = "Indiana Pacers at Philadelphia 76ers", poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
        kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false,
        startMs = null, sport = "Basketball", teams = SportsLiveTeams.parse("Indiana Pacers at Philadelphia 76ers")
    )

    private val final = pacersEvent.copy(
        state = ScoreState.POST, isFinal = true, clockText = "Final/OT",
        home = pacersEvent.home.copy(score = 99), away = pacersEvent.away.copy(score = 101)
    )
    private val inPlay = final.copy(state = ScoreState.IN, isFinal = false, clockText = "Q4 1:00")

    private val strings = FullTimeToastStrings(title = "Full time", withScore = "Full time · %1\$s", score = "%1\$d–%2\$d")

    private fun text(mode: ScoresMode?) = SportsLiveFullTimeToasts.of(pacers, final, mode, seenLive = true)?.let { SportsLiveFullTimeToasts.text(it, strings) }

    @Test
    fun `the toast text follows the Scores setting`() {
        assertEquals("Full time · 101–99", text(ScoresMode.SHOW))
        assertEquals(ChipScore(101, 99), SportsLiveFullTimeToasts.of(pacers, final, ScoresMode.SHOW, seenLive = true)!!.score)
        // Hide until focused: no score.
        assertEquals("Full time", text(ScoresMode.HIDE_UNTIL_FOCUSED))
        // Off, or the setting not read yet: nothing.
        assertNull(text(ScoresMode.OFF))
        assertNull(text(null))
    }

    @Test
    fun `only a strict match ESPN shows final after it was seen in play here`() {
        assertTrue(SportsLiveFullTimeToasts.isLive(pacers, inPlay))
        assertFalse(SportsLiveFullTimeToasts.isLive(pacers, final))
        // Opened already over: it did not end while watching.
        assertNull(SportsLiveFullTimeToasts.of(pacers, final, ScoresMode.SHOW, seenLive = false))
        assertNull(SportsLiveFullTimeToasts.of(pacers, inPlay, ScoresMode.SHOW, seenLive = true))
        // The score still withheld by the delay is not a final yet.
        assertNull(SportsLiveFullTimeToasts.of(pacers, final.copy(home = final.home.copy(score = null)), ScoresMode.SHOW, seenLive = true))
        // Another game's event under the card.
        val other = pacers.copy(meta = pacers.meta.copy(name = "Boston Celtics at New York Knicks"), teams = SportsLiveTeams.parse("Boston Celtics at New York Knicks"))
        assertNull(SportsLiveFullTimeToasts.of(other, final, ScoresMode.SHOW, seenLive = true))
    }

    @Test
    fun `a game's full time is announced once, whichever toast claims it first`() {
        val eventId = SportsLiveFullTimeToasts.of(pacers, final, ScoresMode.SHOW, seenLive = true)!!.eventId
        // The followed-team alert (#42) got there first: the toast for the game playing stays quiet.
        assertTrue(SportsLiveFullTimeAnnouncements.claim(eventId))
        assertTrue(SportsLiveFullTimeAnnouncements.isAnnounced(eventId))
        assertFalse(SportsLiveFullTimeAnnouncements.claim(eventId))
        // Another game is its own.
        assertTrue(SportsLiveFullTimeAnnouncements.claim("other"))
        assertFalse(SportsLiveFullTimeAnnouncements.claim(""))
    }
}
