package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.DirectoryTeam
import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.GameArt
import com.nuvio.tv.core.sportslive.scores.GameArtTeam
import com.nuvio.tv.core.sportslive.scores.TeamDirectory
import com.nuvio.tv.core.sportslive.scores.TeamLogoBook
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/** J4 (user TV report 2026-11-21): a Your teams strip card never cuts a name ("Da… Lo…"). */
class SportsLiveYourTeamsNamesTest {

    private val nba = TeamDirectory().apply {
        put(
            EspnLeagueMap.NBA,
            listOf(
                DirectoryTeam("6", "Dallas Mavericks", "Mavericks", "DAL", "Dallas", "Mavericks", "https://a.espncdn.com/i/teamlogos/nba/500/dal.png"),
                DirectoryTeam("12", "LA Clippers", "Clippers", "LAC", "LA", "Clippers", "https://a.espncdn.com/i/teamlogos/nba/500/lac.png")
            ),
            fetchedAt = 1L
        )
    }

    private fun fromLists(name: String): String? =
        TeamDirectory.cityVariants(name).firstNotNullOfOrNull { nba.abbreviationAnywhere(it) }

    @Test
    fun `an unmatched card takes the abbreviation our team lists give, its city written either way`() {
        val mavericks = GameArtTeam(name = "Dallas Mavericks", short = "Dallas Mavericks", logo = null)
        val clippers = GameArtTeam(name = "Los Angeles Clippers", short = "Los Angeles Clippers", logo = null)
        assertEquals("DAL", yourTeamsShortName(mavericks, ::fromLists))
        assertEquals("LAC", yourTeamsShortName(clippers, ::fromLists))
    }

    @Test
    fun `ESPN's code is kept, and a team no list knows gets a code made from its name`() {
        assertEquals("BOS", yourTeamsShortName(GameArtTeam(name = "Boston Celtics", short = "BOS", logo = null)) { null })
        val code = yourTeamsShortName(GameArtTeam(name = "Khor Fakkan Club", short = "Khor Fakkan Club", logo = null)) { null }
        assertTrue(code, code.length <= 3)
        // A long short name (ESPN's "Mavericks") is no code: the lists' one, else a made one.
        assertEquals("DAL", yourTeamsShortName(GameArtTeam(name = "Dallas Mavericks", short = "Mavericks", logo = null), ::fromLists))
    }

    @Test
    fun `a reminder card for tonight's game with no ESPN match shows both logos and the codes (J4)`() {
        // The TV case: Mavericks vs Clippers at 7 PM, followed, bell on the card; no score feed has matched it yet.
        val nbaList = TeamDirectory().apply {
            put(EspnLeagueMap.NBA, TeamDirectory.parse(EspnFixtures.json("espn_teams_nba_20261118.json"), EspnFixtures.moshi)!!, 0L)
        }
        val book = TeamLogoBook(active = true, directory = nbaList)
        val now = Instant.parse("2026-11-21T18:00:00Z").toEpochMilli()
        val start = Instant.parse("2026-11-21T23:00:00Z").toEpochMilli()
        val name = "Dallas Mavericks vs Los Angeles Clippers"
        val item = SportsLiveItem(
            meta = MetaPreview(
                id = "mavs-lac", type = ContentType.UNKNOWN, rawType = "tv", name = name, poster = null,
                posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null, releaseInfo = null,
                imdbRating = null, genres = emptyList()
            ),
            addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Upcoming",
            kind = SportsLiveKind.EVENTS, isLive = false, isChannel = false, startMs = start, sport = "NBA",
            startHasTimeOfDay = true, teams = SportsLiveTeams.parse(name), followed = true
        )
        val row = ScoreboardRow(
            item = item,
            group = ScoreboardGroup.LATER,
            chip = null,
            art = GameArt.of(item, snapshot = null, known = book),
            firstFollowed = true,
            secondFollowed = false,
            probablyEnded = false
        )
        val entry = YourTeams.strip(emptyList(), listOf(row), emptyList(), now, ZoneOffset.UTC).single() as YourTeamsEntry.LaterToday
        val art = entry.row.art!!
        assertTrue(art.hasLogos)
        // The card's own codes, without any other list's help.
        assertEquals(listOf("DAL", "LAC"), listOf(yourTeamsShortName(art.first) { null }, yourTeamsShortName(art.second) { null }))
    }
}
