package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.scores.DirectoryTeam
import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.TeamDirectory
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** live.18: copies of one game whose sides resolve to the same two ESPN teams merge, whatever the names. */
class SportsLiveTeamIdMergeTest {

    private val now = 1_800_000_000_000L

    private val nba = TeamDirectory().apply {
        put(EspnLeagueMap.NBA, TeamDirectory.parse(EspnFixtures.json("espn_teams_nba_20261118.json"), EspnFixtures.moshi)!!, fetchedAt = 1L)
    }

    private fun merge(vararg items: SportsLiveItem) = SportsLiveItemBucketer.merge(items.toList(), listOf("a", "b", "c"), now, nba)

    @Test
    fun `Los Angeles Clippers and LA Clippers with the sides swapped are one game`() {
        val plain = game("a", "San Antonio Spurs vs Los Angeles Clippers", "Basketball")
        val espn = game("b", "LA Clippers vs San Antonio Spurs", "NBA")
        val row = merge(plain, espn).single()
        // Every source, shown as the copy that names its league (the one ESPN matches).
        assertEquals(2, row.sourceCount)
        assertEquals("a", row.addonId)
        assertEquals(listOf("b"), row.alternates.map { it.addonId })
        assertEquals("NBA", row.sport)
        assertEquals("LA Clippers vs San Antonio Spurs", row.meta.name)
        assertEquals(espn.teams, row.teams)
    }

    @Test
    fun `a city written short or in full resolves to one team`() {
        assertEquals(nba.teamId(EspnLeagueMap.NBA, "LA Clippers"), nba.teamId(EspnLeagueMap.NBA, "Los Angeles Clippers"))
        assertEquals(nba.teamId(EspnLeagueMap.NBA, "Los Angeles Lakers"), nba.teamId(EspnLeagueMap.NBA, "LA Lakers"))
        assertNull(nba.teamId(EspnLeagueMap.NBA, "Los Angeles"))
        assertEquals(1, merge(game("a", "LA Clippers vs LA Lakers", "NBA"), game("b", "Los Angeles Clippers vs Los Angeles Lakers", "Basketball")).size)
    }

    @Test
    fun `LA Clippers vs LA Lakers stays apart from the Clippers' and the Lakers' other games`() {
        val derby = game("a", "LA Clippers vs LA Lakers", "NBA")
        val clippers = game("b", "Clippers vs Warriors", "Basketball")
        val lakers = game("c", "Lakers vs Suns", "Basketball")
        assertEquals(3, merge(derby, clippers, lakers).size)
    }

    @Test
    fun `a men's copy and a women's copy stay apart`() {
        val men = game("a", "LA Clippers vs San Antonio Spurs", "NBA")
        val women = game("b", "Los Angeles Clippers W vs San Antonio Spurs W", "Basketball")
        assertEquals(2, merge(men, women).size)
        val womensBasketball = game("c", "San Antonio Spurs vs Los Angeles Clippers", "Women's Basketball")
        assertEquals(2, merge(men, womensBasketball).size)
    }

    // ---- #215.3, live.23 nightly: a copy of a college football game filed under soccer ----

    private val collegeFootball = TeamDirectory().apply {
        put(
            EspnLeagueMap.COLLEGE_FOOTBALL,
            listOf(
                DirectoryTeam("228", "Clemson Tigers", "Clemson", "CLEM", "Clemson", "Tigers", "https://example.invalid/228.png"),
                DirectoryTeam("150", "Duke Blue Devils", "Duke", "DUKE", "Duke", "Blue Devils", "https://example.invalid/150.png"),
                DirectoryTeam("2649", "Toledo Rockets", "Toledo", "TOL", "Toledo", "Rockets", "https://example.invalid/2649.png"),
                DirectoryTeam("189", "Bowling Green Falcons", "Bowling Green", "BGSU", "Bowling Green", "Falcons", "https://example.invalid/189.png")
            ),
            fetchedAt = 1L
        )
    }

    private fun mergeFootball(vararg items: SportsLiveItem) =
        SportsLiveItemBucketer.merge(items.toList(), listOf("a", "b"), now, collegeFootball)

    @Test
    fun `a college football copy an addon files under soccer merges with the NCAAF copy`() {
        val ncaaf = game("a", "Duke vs Clemson", "NCAAF")
        val soccer = game("b", "Clemson Tigers vs Duke Blue Devils", "⚽ Soccer")
        val row = mergeFootball(soccer, ncaaf).single()
        assertEquals(2, row.sourceCount)
        // Shown as the copy that names its league, the one ESPN scores.
        assertEquals("NCAAF", row.sport)
        assertEquals("Duke vs Clemson", row.meta.name)
        assertNull(SportsLiveItemBucketer.splitReason(game("a", "Toledo vs Bowling Green", "NCAAF"),
            game("b", "Bowling Green Falcons vs Toledo Rockets", "Soccer"), now, collegeFootball))
    }

    @Test
    fun `only soccer and American football are read as one football word, never basketball`() {
        val ncaaf = game("a", "Duke vs Clemson", "NCAAF")
        assertEquals(2, mergeFootball(ncaaf, game("b", "Clemson Tigers vs Duke Blue Devils", "Basketball")).size)
        // Another pairing filed under soccer stays its own row.
        assertEquals(2, mergeFootball(ncaaf, game("b", "Toledo Rockets vs Duke Blue Devils", "Soccer")).size)
    }

    private fun game(addon: String, name: String, sport: String) = SportsLiveItem(
        meta = MetaPreview(
            id = "$addon-$name", type = ContentType.UNKNOWN, rawType = "tv", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = addon, addonName = addon, addonBaseUrl = "https://example.invalid", catalogKey = "$addon:tv:live",
        catalogName = "Events", kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false,
        startMs = null, sport = sport, startHasTimeOfDay = false,
        teams = SportsLiveTeams.parse(name)
    )
}
