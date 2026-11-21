package com.nuvio.tv.ui.screens.sportslive.dual

import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.ScoreChipStrings
import com.nuvio.tv.core.sportslive.scores.ScoreDetailsStrings
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #124: the score strip under each dual pane, per sport and game status, under the score settings. */
class DualScoreStripTest {

    private val chipStrings = ScoreChipStrings(
        score = "%1\$d–%2\$d", live = "%1\$s · %2\$s", final = "%1\$s %2\$s",
        finalNote = "Final", postponed = "Postponed", hidden = "Score hidden"
    )
    private val detailsStrings = ScoreDetailsStrings(
        scoreline = "%1\$s %2\$d–%3\$d %4\$s", sides = "%1\$s – %2\$s", overtime = "OT", overtimeN = "%1\$dOT",
        extraTime = "ET%1\$d", shootout = "SO", emptyPeriod = "–", scorer = "%1\$s %2\$s", penalty = "%1\$s (pen)",
        ownGoal = "%1\$s (OG)", separator = ", "
    )

    private val nba = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA)
    // IND @ PHI: home Philadelphia 76ers 131, away Indiana Pacers 124 (Final).
    private val pacers = nba.first { it.eventId == "401909985" }
    private val eng1 = EspnFixtures.events(EspnFixtures.ENG1_20261108, EspnFixtures.ENG1)
    // Crystal Palace (home) 1–3 Liverpool (away), FT.
    private val palace = eng1.first { it.eventId == "401879243" }
    private val nfl = EspnFixtures.events(EspnFixtures.NFL_LIVE_SITUATION_SAMPLE, EspnLeagueMap.NFL)
    // SEA @ LV, 3rd quarter.
    private val seahawks = nfl.first { it.eventId == "401873101" }

    private fun item(name: String, sport: String = "Basketball") = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
        kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false,
        startMs = null, sport = sport, teams = SportsLiveTeams.parse(name)
    )

    private fun live(event: ScoreSnapshot, clock: String?, home: Int?, away: Int?) = event.copy(
        state = ScoreState.IN, isFinal = false, clockText = clock,
        home = event.home.copy(score = home), away = event.away.copy(score = away)
    )

    private fun strip(item: SportsLiveItem, event: ScoreSnapshot?, mode: ScoresMode? = ScoresMode.SHOW, revealed: Boolean = false) =
        DualScoreStrip.of(item, event, mode, revealed, codeOf = { null })

    private fun texts(strip: DualScoreStrip?) = strip?.let { it.scoreline(detailsStrings) to it.status(chipStrings) }

    @Test
    fun `live basketball shows both teams, the score in the title's order and the clock with the live dot`() {
        val strip = strip(item("Indiana Pacers at Philadelphia 76ers"), live(pacers, "4:12 - 4th", home = 88, away = 91))
        assertEquals("IND 91–88 PHI" to "4:12 - 4th", texts(strip))
        assertTrue(strip!!.live)
        assertEquals(pacers.away.logo, strip.first.logo)
        assertEquals(pacers.home.logo, strip.second.logo)
        // The other way round, the other order.
        assertEquals("PHI 88–91 IND" to "4:12 - 4th", texts(strip(item("76ers vs Pacers"), live(pacers, "4:12 - 4th", home = 88, away = 91))))
    }

    @Test
    fun `live soccer shows the minute`() {
        val home = palace.home.abbreviation
        val away = palace.away.abbreviation
        assertEquals("$home 2–1 $away" to "78'", texts(strip(item("Crystal Palace vs Liverpool", "Soccer"), live(palace, "78'", home = 2, away = 1))))
    }

    @Test
    fun `live football shows the quarter clock`() {
        val event = live(seahawks, "8:41 - 3rd", home = 17, away = 20)
        val text = texts(strip(item("Seahawks at Raiders", "American Football"), event))
        assertEquals("${seahawks.away.abbreviation} 20–17 ${seahawks.home.abbreviation}" to "8:41 - 3rd", text)
    }

    @Test
    fun `a live game before its first score shows the sides and the clock`() {
        assertEquals("PHI – IND" to "12:00 - 1st", texts(strip(item("76ers vs Pacers"), live(pacers, "12:00 - 1st", null, null))))
    }

    @Test
    fun `a final shows the final score and ESPN's note, with no live dot`() {
        val strip = strip(item("76ers vs Pacers"), pacers)
        assertEquals("PHI 131–124 IND" to "Final", texts(strip))
        assertFalse(strip!!.live)
        assertEquals("PHI 131–124 IND" to "Final/OT", texts(strip(item("76ers vs Pacers"), pacers.copy(clockText = "Final/OT"))))
        val home = palace.home.abbreviation
        val away = palace.away.abbreviation
        assertEquals("$home 1–3 $away" to "FT", texts(strip(item("Crystal Palace vs Liverpool", "Soccer"), palace)))
    }

    @Test
    fun `a postponed game shows its status and never a score`() {
        val postponed = pacers.copy(state = ScoreState.OFF, isFinal = false, clockText = "Postponed")
        assertEquals("PHI – IND" to "Postponed", texts(strip(item("76ers vs Pacers"), postponed)))
        assertEquals("PHI – IND" to "Postponed", texts(strip(item("76ers vs Pacers"), postponed.copy(clockText = null))))
    }

    @Test
    fun `hide until focused shows Score hidden until the pane is revealed`() {
        val event = live(pacers, "4:12 - 4th", home = 88, away = 91)
        val hidden = strip(item("76ers vs Pacers"), event, ScoresMode.HIDE_UNTIL_FOCUSED, revealed = false)
        assertEquals("PHI – IND" to "Score hidden", texts(hidden))
        assertFalse(hidden!!.live)
        assertEquals("PHI 88–91 IND" to "4:12 - 4th", texts(strip(item("76ers vs Pacers"), event, ScoresMode.HIDE_UNTIL_FOCUSED, revealed = true)))
    }

    @Test
    fun `no score when Scores is Off or not read yet, unmatched, or not started: the teams alone (live 24)`() {
        val event = live(pacers, "4:12 - 4th", home = 88, away = 91)
        listOf(
            strip(item("76ers vs Pacers"), event, ScoresMode.OFF),
            strip(item("76ers vs Pacers"), event, mode = null),
            strip(item("76ers vs Pacers"), null),
            // Only one side named on the event: never a score against the wrong team.
            strip(item("Celtics vs 76ers"), event),
            strip(item("76ers vs Pacers"), pacers.copy(state = ScoreState.PRE, isFinal = false))
        ).forEach { strip ->
            assertNotNull(strip)
            assertNull(strip!!.chip)
            assertNull(strip.score)
            assertNull(strip.status(chipStrings))
            assertFalse(strip.live)
        }
        // Before the start: ESPN's codes and logos, no score ("PHI – IND").
        val before = strip(item("76ers vs Pacers"), pacers.copy(state = ScoreState.PRE, isFinal = false))!!
        assertEquals("PHI – IND", before.scoreline(detailsStrings))
        assertEquals(pacers.home.logo, before.first.logo)
    }

    @Test
    fun `a ranked college team keeps its rank, and a title that names no two teams gets no strip`() {
        val college = live(pacers, "4:12 - 2nd", home = 40, away = 38).copy(
            league = EspnLeagueMap.COLLEGE_BASKETBALL, home = pacers.home.copy(rank = 12, score = 40)
        )
        val strip = strip(item("76ers vs Pacers"), college)!!
        assertEquals(12, strip.first.rank)
        assertNull(strip.second.rank)
        assertNull(strip(item("ATP 250 - Stockholm Open", "Tennis"), null))
    }
}
