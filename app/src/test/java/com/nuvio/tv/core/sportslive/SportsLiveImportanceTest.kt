package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.MyLeague
import com.nuvio.tv.core.sportslive.scores.MySport
import com.nuvio.tv.core.sportslive.scores.MySports
import com.nuvio.tv.core.sportslive.scores.TeamDirectory
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** J2 (live.23): Live now ranked by importance, the same on every refresh. */
class SportsLiveImportanceTest {
    private val directory = TeamDirectory()

    // The user's My sports (report #6): NBA + college basketball, NFL + college football, six soccer competitions, tennis whole.
    private val mine = MySports(
        mapOf(
            MySport.BASKETBALL to setOf(MyLeague.NBA, MyLeague.COLLEGE_BASKETBALL),
            MySport.FOOTBALL to setOf(MyLeague.NFL, MyLeague.COLLEGE_FOOTBALL),
            MySport.SOCCER to setOf(MyLeague.CHAMPIONS_LEAGUE, MyLeague.PREMIER_LEAGUE, MyLeague.LA_LIGA, MyLeague.SERIE_A),
            MySport.TENNIS to emptySet()
        )
    )

    @Test
    fun `followed first, then My sports leagues by tier, then other leagues, then unplaced`() {
        val laLiga = game("Alaves vs Deportivo", "La Liga", start = 1)
        val nfl = game("Chiefs vs Bills", "NFL", start = 1)
        val nba = game("Spurs vs Pelicans", "NBA", start = 1)
        val epl = game("Brighton vs Leeds", "Premier League", start = 3)
        val ucl = game("Inter vs Porto", "Champions League", start = 9)
        val bundesliga = game("Frankfurt vs HSV", "Bundesliga", start = 1)
        val unplaced = game("Khor Fakkan vs Al-Jazira", "Soccer", start = 0)
        val followed = game("Liverpool vs Chelsea", "Premier League", start = 5).copy(followed = true)
        val sorted = SportsLiveImportance.sort(listOf(unplaced, bundesliga, laLiga, nfl, nba, epl, ucl, followed), mine, directory = directory)
        assertEquals(listOf(followed, ucl, epl, nba, nfl, laLiga, bundesliga, unplaced), sorted)
    }

    @Test
    fun `within a tier, soonest start first, then name`() {
        val late = game("Arsenal vs Spurs", "Premier League", start = 30)
        val early = game("Wolves vs Fulham", "Premier League", start = 10)
        val sameB = game("Brentford vs Everton", "Premier League", start = 20)
        val sameA = game("Aston Villa vs Burnley", "Premier League", start = 20)
        assertEquals(listOf(early, sameA, sameB, late), SportsLiveImportance.sort(listOf(late, sameB, early, sameA), mine, directory = directory))
    }

    @Test
    fun `the order is the same whatever order the load lists them in`() {
        val items = listOf(
            game("Spurs vs Pelicans", "NBA", start = 1), game("Duke vs Kansas", "NCAAM", start = 2),
            game("Brighton vs Leeds", "Premier League", start = 2), game("Inter vs Porto", "Champions League", start = 2),
            game("Khor Fakkan vs Al-Jazira", "Soccer", start = 2), game("Celtics vs Knicks", "NBA", start = 1)
        )
        val once = SportsLiveImportance.sort(items, mine, directory = directory)
        assertEquals(once, SportsLiveImportance.sort(items.reversed(), mine, directory = directory))
        assertEquals(once, SportsLiveImportance.sort(items.shuffled(java.util.Random(7)), mine, directory = directory))
    }

    @Test
    fun `a close or late game moves up within its tier only, and only when it is bumped`() {
        val first = game("Arsenal vs Spurs", "Premier League", start = 1)
        val close = game("Wolves vs Fulham", "Premier League", start = 5)
        val ucl = game("Inter vs Porto", "Champions League", start = 9)
        val items = listOf(first, close, ucl)
        // Scores hidden or off: the caller passes no bumps, so no score can move a row.
        assertEquals(listOf(ucl, first, close), SportsLiveImportance.sort(items, mine, directory = directory))
        // Scores shown: the close game leads its tier, never above a higher tier.
        assertEquals(listOf(ucl, close, first), SportsLiveImportance.sort(items, mine, bumped = setOf(close.streamKey), directory = directory))
    }

    @Test
    fun `a followed game with no score still leads every scored game (sorting model)`() {
        val followed = game("Liverpool vs Chelsea", "Premier League", start = 9).copy(followed = true)
        val ucl = game("Inter vs Porto", "Champions League", start = 1)
        val epl = game("Brighton vs Leeds", "Premier League", start = 1)
        val scored = setOf(ucl.streamKey, epl.streamKey)
        assertEquals(listOf(followed, ucl, epl), SportsLiveImportance.sort(listOf(ucl, epl, followed), mine, directory = directory, scored = scored))
    }

    @Test
    fun `a game with a score leads the scoreless ones of its own tier only (sorting model)`() {
        val ucl = game("Inter vs Porto", "Champions League", start = 9)
        val eplEarly = game("Wolves vs Fulham", "Premier League", start = 1)
        val eplScored = game("Arsenal vs Spurs", "Premier League", start = 5)
        val college = game("Duke vs Kansas", "NCAAM", start = 0)
        val items = listOf(college, eplEarly, eplScored, ucl)
        // An unscored higher tier still beats a scored lower one; inside the tier the scored game goes first.
        val scored = setOf(eplScored.streamKey, college.streamKey)
        assertEquals(listOf(ucl, eplScored, eplEarly, college), SportsLiveImportance.sort(items, mine, directory = directory, scored = scored))
        // No score set (Off, or a caller that does not rank by it): nothing moves.
        assertEquals(listOf(ucl, eplEarly, eplScored, college), SportsLiveImportance.sort(items, mine, directory = directory))
    }

    @Test
    fun `nothing picked ranks every placed league as My sports`() {
        val bundesliga = game("Frankfurt vs HSV", "Bundesliga", start = 1)
        val nba = game("Spurs vs Pelicans", "NBA", start = 5)
        val unplaced = game("Khor Fakkan vs Al-Jazira", "Soccer", start = 0)
        assertEquals(listOf(nba, bundesliga, unplaced), SportsLiveImportance.sort(listOf(unplaced, bundesliga, nba), MySports.DEFAULT, directory = directory))
    }

    @Test
    fun `tiers follow the table`() {
        assertEquals(0, SportsLiveImportance.tierOf(com.nuvio.tv.core.sportslive.scores.EspnLeague("soccer", "uefa.champions")))
        assertEquals(2, SportsLiveImportance.tierOf(EspnLeagueMap.NBA))
        assertEquals(3, SportsLiveImportance.tierOf(EspnLeagueMap.NFL))
        assertEquals(SportsLiveImportance.TIER_OTHER, SportsLiveImportance.tierOf(EspnLeagueMap.EUROCUP))
        assertEquals(SportsLiveImportance.TIER_NONE, SportsLiveImportance.tierOf(null))
    }

    private fun game(name: String, sport: String?, start: Long) = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "tv", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = "addon", addonName = "Sports", addonBaseUrl = "https://example.invalid", catalogKey = "addon:tv:live",
        catalogName = "Events", kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false,
        startMs = start * 60_000L, sport = sport, startHasTimeOfDay = true,
        teams = SportsLiveTeams.parse(name)
    )
}
