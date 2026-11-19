package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.GameStatus
import com.nuvio.tv.core.sportslive.GameStatusStrings
import com.nuvio.tv.core.sportslive.SportsLiveDisplayText
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.ChipScore
import com.nuvio.tv.core.sportslive.scores.GameArt
import com.nuvio.tv.core.sportslive.scores.GameArtTeam
import com.nuvio.tv.core.sportslive.scores.ScoreChip
import com.nuvio.tv.core.sportslive.scores.ScoreChipStrings
import com.nuvio.tv.core.sportslive.scores.TeamLogoBook
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.ui.screens.sportslive.dual.dualPickerSections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Small fixes found on the emulator: the dual picker's order, Replays' rows and stage, Live Now's stage art; All sports' place. */
class SportsLiveEmulatorFixesTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    @Test
    fun `the dual picker lists live games first and channels last, each in the order given`() {
        val g1 = item("g1", "Lakers vs Celtics")
        val c1 = item("c1", "Sports Channel 1").copy(isChannel = true)
        val g2 = item("g2", "Bulls vs Knicks")
        val c2 = item("c2", "Sports Channel 2").copy(isChannel = true)
        val (games, channels) = dualPickerSections(listOf(c1, g1, c2, g2))
        assertEquals(listOf("g1", "g2"), games.map { it.id })
        assertEquals(listOf("c1", "c2"), channels.map { it.id })
    }

    @Test
    fun `a competition after a team's name comes off it and becomes the league`() {
        assertEquals("Real Madrid" to "Euroleague", SportsLiveDisplayText.competitionSuffix("Real Madrid Euroleague"))
        assertEquals("Lakers" to "NBA", SportsLiveDisplayText.competitionSuffix("Lakers (NBA)"))
        assertEquals("Arsenal" to "Premier League", SportsLiveDisplayText.competitionSuffix("Arsenal - Premier League"))
        assertNull(SportsLiveDisplayText.competitionSuffix("Real Madrid"))
        assertNull(SportsLiveDisplayText.competitionSuffix("Euroleague"))

        val replay = item("r", "Real Madrid Euroleague vs Barcelona Euroleague").copy(sport = "Basketball", kind = SportsLiveKind.REPLAY)
        val art = GameArt.ofReplay(replay, replay.meta.name, TeamLogoBook())!!
        assertEquals("Real Madrid", art.first.name)
        assertEquals("Barcelona", art.second.name)
        assertEquals("Euroleague", art.league)
        // The row's tag reads the competition, whole.
        assertEquals("EUROLEAGUE", scoreboardRowTag(art.league, replay.sport, replay.catalogName))

        // No competition written: the card's sport, as before.
        val plain = item("p", "Lakers vs Celtics").copy(sport = "Basketball", kind = SportsLiveKind.REPLAY)
        assertEquals("Basketball", GameArt.ofReplay(plain, plain.meta.name, TeamLogoBook())!!.league)
    }

    @Test
    fun `a live game's stage art shows its score and clock, never its start time`() {
        val art = GameArt(GameArtTeam("Orlando Magic", "ORL", null), GameArtTeam("Philadelphia 76ers", "PHI", null), "NBA", now - 90 * minute, true)
        val live = ScoreboardRow(
            item = item("m", "Orlando Magic vs Philadelphia 76ers"), group = ScoreboardGroup.LIVE,
            chip = ScoreChip.Live("Q4 11:22", ChipScore(97, 92)), art = art,
            firstFollowed = false, secondFollowed = false, probablyEnded = false,
            status = GameStatus.Live(null), twoSided = true
        )
        val status = stageArtStatus(live, strings, kickoff = "7:00 PM")
        assertEquals(ScoreboardScores.Shown(97, 92, leader = 0), status.score)
        assertEquals("11:22 · Q4", status.status)
        assertTrue(status.live)
        assertFalse(status.status!!.contains("7:00 PM"))

        // Live with no score feed: LIVE, still not the start time.
        val unmatched = stageArtStatus(live.copy(chip = null), strings, kickoff = "7:00 PM")
        assertEquals("LIVE", unmatched.status)
        assertEquals(ScoreboardScores.None, unmatched.score)

        // A game yet to start keeps its kickoff.
        val soon = live.copy(group = ScoreboardGroup.STARTING_SOON, chip = null, status = GameStatus.StartingSoon(now + 60 * minute, null))
        val upcoming = stageArtStatus(soon, strings, kickoff = "8:30 PM")
        assertEquals("8:30 PM", upcoming.status)
        assertFalse(upcoming.live)
    }

    @Test
    fun `All sports follows My teams and My sports in the filter, and Live now shows the filter only to offer it`() {
        val myTeams = SportsLiveUiState.MY_TEAMS_FILTER
        val allSports = SportsLiveUiState.ALL_SPORTS_FILTER
        assertEquals(listOf(myTeams, allSports, "Soccer"), withAllSports(listOf(myTeams, "Soccer")))
        assertEquals(listOf(allSports, "Soccer"), withAllSports(listOf("Soccer", allSports)))
        val options = sportsLiveFilterOptions(withAllSports(listOf(myTeams, "Soccer")), "My teams", "My sports") { it }
        assertEquals(listOf(myTeams, null, allSports, "Soccer"), options.map { it.value })

        assertTrue(sportsLiveHeaderFilterShown(SportsLiveTab.LIVE_NOW, 1, allSports = true))
        assertFalse(sportsLiveHeaderFilterShown(SportsLiveTab.LIVE_NOW, 5))
        assertTrue(sportsLiveHeaderFilterShown(SportsLiveTab.UPCOMING, 1, allSports = true))
        assertFalse(sportsLiveHeaderFilterShown(SportsLiveTab.CHANNELS, 1))
        // Every game is listed under All sports, on any tab.
        assertTrue(item("x", "India vs Australia").copy(sport = "Cricket").matchesZapFilter(allSports, SportsLiveTab.LIVE_NOW))
    }

    private val strings = GameStatusStrings(
        liveClock = "● %1\$s", live = "LIVE", clockPeriod = "%1\$s · %2\$s", timeCountdown = "%1\$s · %2\$s",
        inHoursMinutes = "in %1\$d h %2\$d m", inHours = "in %1\$d h", inMinutes = "in %1\$d min",
        final = "Final", fullTime = "FT", probablyEnded = "Probably ended", postponed = "Postponed",
        chip = ScoreChipStrings("%1\$d–%2\$d", "%1\$s · %2\$s", "%1\$s %2\$s", "Final", "Postponed", "Score hidden")
    )

    private fun item(id: String, name: String) = SportsLiveItem(
        meta = MetaPreview(
            id = id, type = ContentType.UNKNOWN, rawType = "tv", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = "addon", addonName = "Sports", addonBaseUrl = "https://example.invalid", catalogKey = "addon:tv:live",
        catalogName = "Replays", kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false,
        startMs = null, sport = "Basketball", startHasTimeOfDay = false,
        teams = SportsLiveTeams.parse(name)
    )
}
