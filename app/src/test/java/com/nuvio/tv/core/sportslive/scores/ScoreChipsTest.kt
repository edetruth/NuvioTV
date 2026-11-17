package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScoreChipsTest {

    private val strings = ScoreChipStrings(
        score = "%1\$d–%2\$d",
        live = "%1\$s · %2\$s",
        final = "%1\$s %2\$s",
        finalNote = "Final",
        postponed = "Postponed",
        hidden = "Score hidden"
    )

    private val nba = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA)
    // IND @ PHI: home Philadelphia 76ers 131, away Indiana Pacers 124 (Final).
    private val pacers = nba.first { it.eventId == "401909985" }
    private val eng1 = EspnFixtures.events(EspnFixtures.ENG1_20261108, EspnFixtures.ENG1)
    // Crystal Palace (home) 1–3 Liverpool (away), FT.
    private val palace = eng1.first { it.eventId == "401879243" }

    private fun item(name: String, isChannel: Boolean = false) = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
        kind = if (isChannel) SportsLiveKind.CHANNELS else SportsLiveKind.EVENTS, isLive = true, isChannel = isChannel,
        startMs = null, sport = "Basketball", teams = if (isChannel) null else SportsLiveTeams.parse(name)
    )

    private fun live(event: ScoreSnapshot, clock: String?, home: Int?, away: Int?) = event.copy(
        state = ScoreState.IN, isFinal = false, clockText = clock,
        home = event.home.copy(score = home), away = event.away.copy(score = away)
    )

    private fun chipText(item: SportsLiveItem, event: ScoreSnapshot?, mode: ScoresMode = ScoresMode.SHOW, revealed: Boolean = false) =
        ScoreChips.of(item, event, mode, revealed)?.let { ScoreChips.text(it, strings) }

    @Test
    fun `live basketball shows the clock and the score in the title's team order`() {
        // "Pacers at 76ers": the title names the away side first, so its score comes first.
        assertEquals("Q3 4:12 · 80–88", chipText(item("Indiana Pacers at Philadelphia 76ers"), live(pacers, "Q3 4:12", home = 88, away = 80)))
        assertEquals("Q3 4:12 · 88–80", chipText(item("76ers vs Pacers"), live(pacers, "Q3 4:12", home = 88, away = 80)))
        assertEquals(ScoreChip.Live("Q3 4:12", ChipScore(80, 88)), ScoreChips.of(item("Pacers @ 76ers"), live(pacers, "Q3 4:12", 88, 80), ScoresMode.SHOW, false))
    }

    @Test
    fun `live soccer shows the minute and the score`() {
        assertEquals("67' · 2–1", chipText(item("Crystal Palace vs Liverpool"), live(palace, "67'", home = 2, away = 1)))
        assertEquals("67' · 1–2", chipText(item("Liverpool vs Crystal Palace"), live(palace, "67'", home = 2, away = 1)))
    }

    @Test
    fun `a live game without scores yet shows only its clock, and nothing without either`() {
        assertEquals("1st 12:00", chipText(item("76ers vs Pacers"), live(pacers, "1st 12:00", null, null)))
        assertNull(chipText(item("76ers vs Pacers"), live(pacers, null, null, null)))
    }

    @Test
    fun `finals show ESPN's note and the score`() {
        assertEquals("FT 1–3", chipText(item("Crystal Palace vs Liverpool"), palace))
        assertEquals("Final 131–124", chipText(item("76ers vs Pacers"), pacers))
        assertEquals("Final/OT 124–131", chipText(item("Pacers at 76ers"), pacers.copy(clockText = "Final/OT")))
        assertEquals("Final 131–124", chipText(item("76ers vs Pacers"), pacers.copy(clockText = null)))
    }

    @Test
    fun `postponed and cancelled games show their status and never a score`() {
        val postponed = pacers.copy(state = ScoreState.OFF, isFinal = false, clockText = "Postponed")
        assertEquals(ScoreChip.Status("Postponed"), ScoreChips.of(item("76ers vs Pacers"), postponed, ScoresMode.SHOW, false))
        assertEquals("Canceled", chipText(item("76ers vs Pacers"), postponed.copy(clockText = "Canceled")))
        assertEquals("Postponed", chipText(item("76ers vs Pacers"), postponed.copy(clockText = null)))
        // Not a score, so it is no spoiler either.
        assertEquals("Postponed", chipText(item("76ers vs Pacers"), postponed, ScoresMode.HIDE_UNTIL_FOCUSED))
    }

    @Test
    fun `upcoming and unknown games get no chip`() {
        assertNull(chipText(item("76ers vs Pacers"), pacers.copy(state = ScoreState.PRE, isFinal = false, clockText = "7:00 PM")))
        assertNull(chipText(item("76ers vs Pacers"), pacers.copy(state = ScoreState.UNKNOWN)))
        // A finished state the feed does not call completed: no score.
        assertNull(chipText(item("76ers vs Pacers"), pacers.copy(isFinal = false)))
    }

    @Test
    fun `hide until focused shows Score hidden until the card is revealed`() {
        val game = live(pacers, "Q3 4:12", 88, 80)
        assertEquals(ScoreChip.Hidden(live = true), ScoreChips.of(item("76ers vs Pacers"), game, ScoresMode.HIDE_UNTIL_FOCUSED, false))
        assertEquals("Score hidden", chipText(item("76ers vs Pacers"), pacers, ScoresMode.HIDE_UNTIL_FOCUSED))
        assertEquals(ScoreChip.Hidden(live = false), ScoreChips.of(item("76ers vs Pacers"), pacers, ScoresMode.HIDE_UNTIL_FOCUSED, false))
        assertEquals("Q3 4:12 · 88–80", chipText(item("76ers vs Pacers"), game, ScoresMode.HIDE_UNTIL_FOCUSED, revealed = true))
    }

    @Test
    fun `Scores Off or an unknown setting shows no chip`() {
        assertNull(chipText(item("76ers vs Pacers"), pacers, ScoresMode.OFF, revealed = true))
        assertNull(ScoreChips.of(item("76ers vs Pacers"), pacers, null, true))
    }

    @Test
    fun `an unmatched card gets no chip`() {
        assertNull(chipText(item("76ers vs Pacers"), null))
        // A channel names no game unless its brackets do.
        assertNull(chipText(item("US| ESPN HD", isChannel = true), pacers))
    }

    @Test
    fun `a bracketed channel game is ordered by its brackets`() {
        assertEquals("Final 124–131", chipText(item("US| NBA 01 [Pacers vs 76ers]", isChannel = true), pacers))
    }

    @Test
    fun `sides that do not sit on the event one way round give no chip`() {
        // A women's card on a men's event: the qualifier does not agree, so neither way fits.
        assertNull(chipText(item("76ers (W) vs Pacers (W)"), pacers))
        // One side matches nothing.
        assertNull(chipText(item("76ers vs Celtics"), pacers))
        // Both sides name the same team: both orientations tie.
        assertNull(ScoreMatcher.firstIsHome("76ers", "76ers", pacers))
        assertEquals(true, ScoreMatcher.firstIsHome("Philadelphia 76ers", "Indiana Pacers", pacers))
        assertEquals(false, ScoreMatcher.firstIsHome("IND", "PHI", pacers))
    }

    @Test
    fun `a youth card never takes a senior event's score`() {
        assertNull(chipText(item("Crystal Palace U21 vs Liverpool U21"), palace))
    }
}
