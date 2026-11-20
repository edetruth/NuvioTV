package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.TeamDirectory
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.ClaimedCatalog
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Every real duplicate seen on the TV so far, and the pairs that must stay apart, in one table (live.18): team
 * ids decide where the ESPN team directory knows the teams, the name rules (#153, #165, #174) elsewhere.
 */
class SportsLiveDuplicateCorpusTest {

    private val now = Instant.parse("2026-11-19T16:00:00Z").toEpochMilli()

    private val nba = TeamDirectory().apply {
        put(EspnLeagueMap.NBA, TeamDirectory.parse(EspnFixtures.json("espn_teams_nba_20261118.json"), EspnFixtures.moshi)!!, fetchedAt = 1L)
        put(
            EspnLeagueMap.COLLEGE_BASKETBALL,
            TeamDirectory.parse(EspnFixtures.json("espn_teams_cbb_20261118.json"), EspnFixtures.moshi)!!,
            fetchedAt = 1L
        )
    }

    /** One case: copies as (title, genres), and how many rows they must make. */
    private class Case(val name: String, val rows: Int, vararg val copies: Pair<String, List<String>>)

    private fun copy(title: String, vararg genres: String) = title to genres.toList()

    private val oneGame = listOf(
        Case("Spurs and Clippers, sides swapped, LA and Los Angeles", 1,
            copy("San Antonio Spurs vs Los Angeles Clippers", "Basketball"), copy("LA Clippers vs San Antonio Spurs", "NBA")),
        // Seen on the emulator (Seattle vs Salt Lake, MLS and untagged): ids from one copy's league, names decide.
        Case("one copy tagged, the other untagged", 1,
            copy("LA Clippers vs San Antonio Spurs", "NBA"), copy("LA Clippers vs San Antonio Spurs")),
        // The emulator sweep (live.18): an NCAAM copy and an unmarked College copy of the same two schools.
        Case("NCAAM and College", 1, copy("Duke vs Gonzaga", "NCAAM"), copy("LIVE: Duke Blue Devils vs Gonzaga Bulldogs", "COLLEGE")),
        Case("KK Crvena zvezda and FC Barcelona Basquet", 1,
            copy("FC Barcelona Basquet vs KK Crvena zvezda", "Basketball", "Euroleague"),
            copy("Barcelona vs Crvena Zvezda Beograd", "BASKETBALL")),
        Case("East Texas A and M under its old name", 1,
            copy("California Golden Bears vs Texas A&M–Commerce Lions", "Basketball"), copy("California vs East Texas A and M", "NCAAM")),
        Case("Qatar and Panama Sub-17 and U17", 1,
            copy("Qatar Sub-17 vs Panamá Sub-17", "Football"), copy("LIVE: Qatar U17 x Panamá U17"), copy("Qatar U17 vs Panama U17", "Soccer")),
        Case("Argentina U17", 1,
            copy("Argentina Sub-17 vs Australia Sub-17", "Football"), copy("Argentina U17 x Australia U17"),
            copy("Argentina U-17 vs Australia U-17", "Soccer")),
        Case("Egypt and Greece U17", 1, copy("Egypt U17 x Greece U17"), copy("Egypt Under-17 vs Greece Under 17", "Soccer")),
        Case("BAN-A and Hong Kong", 1, copy("Bangladesh A vs Hong Kong", "CRICKET"), copy("BAN-A vs HK")),
        Case("NC State", 1, copy("Murray State vs NC State", "Basketball"), copy("Murray State vs North Carolina State", "Basketball")),
        Case("Virtus Bologna", 1,
            copy("Valencia Basket vs Virtus Bologna", "Basketball"), copy("Valencia Basket vs Virtus Pallacanestro Bologna", "Basketball")),
        Case("Barcelona W", 1,
            copy("Barcelona W vs Servette Chênois W", "Football"), copy("Barcelona Women vs Servette Chenois Women", "Soccer"),
            copy("FC Barcelona W vs Servette FC Chênois W", "UWCL")),
        Case("Arsenal and OL Lyonnes, UWCL and plain", 1, copy("Arsenal W vs OL Lyonnes W", "UWCL"), copy("Arsenal vs OL Lyonnes", "FOOTBALL")),
        Case("Illawarra and Adelaide 36ers", 1,
            copy("Illawarra Hawks vs Adelaide", "Basketball"), copy("Illawarra Hawks vs Adelaide 36ers", "Basketball")),
        Case("Brisbane W", 1,
            copy("Brisbane W vs Hobart W", "Cricket"), copy("Brisbane Heat Women vs Hobart Hurricanes Women", "Cricket"))
    )

    private val apart = listOf(
        Case("Manchester United and City", 2, copy("Manchester United vs Liverpool", "Soccer"), copy("Manchester City vs Liverpool", "Soccer")),
        Case("Man Utd and Man City", 2, copy("Man Utd vs Chelsea", "Soccer"), copy("Man City vs Chelsea", "Soccer")),
        Case("men's and women's", 2, copy("Arsenal W vs OL Lyonnes W", "UWCL"), copy("Arsenal vs OL Lyonnes", "Men's Football")),
        Case("U17 and seniors", 2, copy("Qatar U17 vs Panama U17", "Soccer"), copy("Qatar vs Panamá", "Soccer")),
        Case("LA Clippers vs LA Lakers and the Clippers' other game", 2, copy("LA Clippers vs LA Lakers", "NBA"), copy("Clippers vs Warriors", "NBA")),
        Case("LA Clippers vs LA Lakers and the Lakers' other game", 2,
            copy("LA Clippers vs LA Lakers", "NBA"), copy("Los Angeles Lakers vs Phoenix Suns", "Basketball"))
    )

    private fun rows(case: Case): List<SportsLiveItem> {
        val items = case.copies.mapIndexed { index, (title, genres) -> event("addon$index", "$index", title, genres) }
        return SportsLiveItemBucketer.merge(items, items.map { it.addonId }, now, nba)
    }

    @Test
    fun `every duplicate seen so far makes one row with all its sources`() {
        for (case in oneGame) {
            val rows = rows(case)
            assertEquals(case.name, 1, rows.size)
            assertEquals(case.name, case.copies.size, rows.single().sourceCount)
        }
    }

    @Test
    fun `different games stay apart`() {
        for (case in apart) assertEquals(case.name, case.rows, rows(case).size)
    }

    @Test
    fun `no case leaves a suspected duplicate behind`() {
        for (case in oneGame + apart) {
            val suspects = SportsLiveDupes.scan(rows(case), now, nba)
            assertTrue("${case.name}: ${suspects.map { it.reason }}", suspects.none { it.why == "same team ids" })
        }
    }

    @Test
    fun `the detector names why look-alike rows did not merge`() {
        // One copy says basketball, the other soccer: alike names, kept apart by the sport rule.
        val basketball = event("a", "1", "Hapoel Holon vs Maccabi Haifa", listOf("Basketball"))
        val soccer = event("b", "2", "Hapoel Holon vs Maccabi Haifa", listOf("Soccer"))
        val rows = SportsLiveItemBucketer.bucketTab(listOf(basketball, soccer), SportsLiveTab.LIVE_NOW, now, listOf("a", "b"))
        assertEquals(2, rows.size)
        val suspect = SportsLiveDupes.scan(rows, now, nba).single()
        assertEquals("names alike", suspect.why)
        assertEquals("no team id, sport mismatch", suspect.reason)
        // LA Clippers and Los Angeles Clippers share one word of two: the ids say they are one team.
        assertEquals(0.5, SportsLiveDupes.overlap("LA Clippers", "Los Angeles Clippers"), 0.001)
    }

    private fun event(addonId: String, id: String, name: String, genres: List<String> = emptyList()): SportsLiveItem =
        SportsLiveItemBucketer.describe(
            MetaPreview(
                id = id, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
                posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
                releaseInfo = "LIVE", imdbRating = null, genres = genres
            ),
            claimed(addonId),
            now
        )

    private fun claimed(addonId: String): ClaimedCatalog {
        val catalog = CatalogDescriptor(type = ContentType.fromString("sport"), rawType = "sport", id = "events", name = "Events")
        val addon = Addon(
            id = addonId, name = "Sports $addonId", version = "1.0.0", description = null, logo = null,
            baseUrl = "https://example.invalid", catalogs = listOf(catalog), types = listOf(catalog.type), resources = emptyList()
        )
        return ClaimedCatalog(addon, catalog, "${addonId}_sport_events", SportsLiveKind.EVENTS, false)
    }
}
