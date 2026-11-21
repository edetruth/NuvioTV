package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.core.sportslive.SportsLiveEspnStarts
import com.nuvio.tv.core.sportslive.SportsLiveSportKind
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.coroutines.EmptyCoroutineContext

/**
 * #206: on the TV at 22:50Z on 2026-11-20 Live now listed as LIVE, with no score, "Bayern Munich vs Köln" (BUNDES),
 * "FC Andorra vs Leganés" (FOOTBALL), "Troyes vs Le Havre" and "Athletic Bilbao vs Espanyol", all finals on ESPN's
 * soccer/all, while other addons' copies were matched and under Just ended. The names match ESPN's and each other's;
 * the copies were never matched because no feed was routed for them ("BUNDES" and no tag route nowhere), and a copy
 * the addon flags live stays LIVE until ESPN says otherwise.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnroutedSoccerCopiesTest {

    private class Provider(private val events: List<ScoreSnapshot>, private val day: LocalDate) : ScoreProvider {
        val calls = mutableListOf<ScoreFeed>()
        override suspend fun scoreboard(league: EspnLeague, date: LocalDate): ScoreboardResult {
            calls += ScoreFeed(league, date)
            return ScoreboardResult(if (league == EspnLeagueMap.SOCCER_ALL && date == day) events else emptyList())
        }
        override suspend fun recentScoreboard(league: EspnLeague, date: LocalDate): ScoreboardResult? = null
        override suspend fun scheduleScoreboard(league: EspnLeague, date: LocalDate): ScoreboardResult = scoreboard(league, date)
        override suspend fun teamList(league: EspnLeague): List<DirectoryTeam>? = null
    }

    private val soccerAll = EspnFixtures.events(EspnFixtures.SOCCER_ALL_20261120, EspnLeagueMap.SOCCER_ALL)
    private val provider = Provider(soccerAll, LocalDate.of(2026, 11, 20))
    private val base = Instant.parse("2026-11-20T22:50:00Z").toEpochMilli()

    private fun item(name: String, sport: String?, addon: String = "b", startMs: Long? = null) = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = addon, addonName = addon, addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
        kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false, startMs = startMs, sport = sport,
        startHasTimeOfDay = startMs != null, teams = SportsLiveTeams.parse(name)
    )

    private fun team(id: String, name: String) = DirectoryTeam(id, name, name, null, name, name, "https://example.invalid/i/$id.png")

    // The loaded team lists that tell an untagged card's sport by its two teams (release review).
    private fun soccerLists() = TeamLogoBook().apply {
        directory.put(EspnLeague("soccer", "fra.1"), listOf(team("1", "Troyes"), team("2", "Le Havre")), base)
        directory.put(EspnLeague("soccer", "esp.1"), listOf(team("3", "Athletic Bilbao"), team("4", "Espanyol")), base)
    }

    private fun TestScope.repository(logos: TeamLogoBook = soccerLists()) = LiveScoresRepository(
        provider = provider,
        settings = MutableStateFlow(ScoreSettings()),
        mySports = MutableStateFlow(MySports.DEFAULT),
        scope = backgroundScope,
        now = { base + testScheduler.currentTime },
        zone = { ZoneOffset.UTC },
        jitter = { 0L },
        logoFile = null,
        prefetch = {},
        io = EmptyCoroutineContext,
        health = EspnFeedHealth { base + testScheduler.currentTime },
        notifyOutage = {},
        logos = logos,
        followedLeagues = flowOf(emptyList()),
        watching = WatchingNow(),
        espnStarts = SportsLiveEspnStarts()
    ).also { repo -> backgroundScope.launch { repo.scores.collect {} } }

    private val matched = item("Bayern vs Köln", "Soccer", addon = "a")
    private val bundes = item("Bayern Munich vs Köln", "BUNDES")
    private val andorra = item("FC Andorra vs Leganés", "FOOTBALL")
    private val troyes = item("Troyes vs Le Havre", null, startMs = Instant.parse("2026-11-20T19:45:00Z").toEpochMilli())
    private val athletic = item("Athletic Bilbao vs Espanyol", null, startMs = Instant.parse("2026-11-20T20:00:00Z").toEpochMilli())

    @Test
    fun `BUNDES routes to the Bundesliga and a bare FOOTBALL to soccer`() {
        assertEquals(listOf(EspnLeague("soccer", "ger.1")), EspnLeagueMap.leaguesFor(bundes))
        assertEquals(SportsLiveSportKind.SOCCER, SportsLiveSportKind.tagOf(bundes))
        assertEquals(listOf(EspnLeagueMap.SOCCER_ALL), EspnLeagueMap.leaguesFor(andorra))
        assertEquals(SportsLiveSportKind.SOCCER, SportsLiveSportKind.plainTagOf(andorra, TeamDirectory().apply { put(EspnLeagueMap.NFL, emptyList(), base) }))
    }

    @Test
    fun `a bare Football is American football only when the card or both teams say so`() {
        val nfl = TeamDirectory().apply {
            put(EspnLeagueMap.NFL, TeamDirectory.parse(EspnFixtures.json("espn_teams_nfl_20261118.json"), EspnFixtures.moshi)!!, base)
        }
        assertEquals(SportsLiveSportKind.FOOTBALL, SportsLiveSportKind.plainTagOf(item("Chicago Bears vs Green Bay Packers", "Football"), nfl))
        assertEquals(SportsLiveSportKind.SOCCER, SportsLiveSportKind.plainTagOf(item("FC Andorra vs Leganés", "Football"), nfl))
        // Before the NFL list is loaded nothing can tell: "Football" stays as written, never SOCCER first (release review).
        assertNull(SportsLiveSportKind.plainTagOf(item("Chicago Bears vs Green Bay Packers", "Football"), TeamDirectory()))
        assertNull(SportsLiveSportKind.plainTagOf(andorra, TeamDirectory()))
        // A card that says soccer elsewhere needs no list.
        assertEquals(
            SportsLiveSportKind.SOCCER,
            SportsLiveSportKind.plainTagOf(item("FC Andorra vs Leganés", "Football").copy(catalogName = "Soccer"), TeamDirectory())
        )
    }

    @Test
    fun `every copy of the four finals gets ESPN's final, the untagged ones on the feed other cards already poll`() = runTest {
        val repo = repository()
        repo.track(LiveScoresRepository.SCREEN_SECTION, listOf(matched, bundes, andorra, troyes, athletic))
        runCurrent()
        val scores = repo.scores.value
        val final = { card: SportsLiveItem -> scores[card.streamKey]?.state }
        assertEquals(ScoreState.POST, final(matched))
        assertEquals(ScoreState.POST, final(andorra))
        assertEquals(ScoreState.POST, final(troyes))
        assertEquals("401884736", scores[matched.streamKey]?.eventId)
        assertEquals("401876395", scores[troyes.streamKey]?.eventId)
        assertEquals("401882805", scores[athletic.streamKey]?.eventId)
        // BUNDES polls the Bundesliga feed, empty here, then soccer/all.
        assertEquals("401884736", scores[bundes.streamKey]?.eventId)
        // No feed was asked for the untagged cards: their own leagues (Ligue 1, LALIGA) never, only the routed cards' feeds.
        assertTrue(provider.calls.none { it.league.league == "fra.1" || it.league.league == "esp.1" })
    }

    @Test
    fun `an untagged card alone asks for no feed and gets no score`() = runTest {
        val repo = repository()
        repo.track(LiveScoresRepository.SCREEN_SECTION, listOf(troyes, athletic))
        runCurrent()
        assertTrue(provider.calls.isEmpty())
        assertNull(repo.scores.value[troyes.streamKey])
    }
    @Test
    fun `an untagged card with no start time gets no score from another card's feed`() = runTest {
        val repo = repository()
        val undated = item("Troyes vs Le Havre", null, addon = "c")
        repo.track(LiveScoresRepository.SCREEN_SECTION, listOf(matched, undated))
        runCurrent()
        assertEquals(ScoreState.POST, repo.scores.value[matched.streamKey]?.state)
        assertNull(repo.scores.value[undated.streamKey])
    }

    @Test
    fun `an untagged card whose teams no loaded list holds gets no score`() = runTest {
        val repo = repository(logos = TeamLogoBook())
        repo.track(LiveScoresRepository.SCREEN_SECTION, listOf(matched, troyes))
        runCurrent()
        assertEquals(ScoreState.POST, repo.scores.value[matched.streamKey]?.state)
        assertNull(repo.scores.value[troyes.streamKey])
    }

    @Test
    fun `an untagged card whose teams are another sport's never takes the soccer game`() = runTest {
        val basketball = TeamLogoBook().apply {
            directory.put(EspnLeague("basketball", "example"), listOf(team("5", "Troyes"), team("6", "Le Havre")), base)
        }
        val repo = repository(logos = basketball)
        repo.track(LiveScoresRepository.SCREEN_SECTION, listOf(matched, troyes))
        runCurrent()
        assertNull(repo.scores.value[troyes.streamKey])
    }

    @Test
    fun `an untagged card starting hours from the game gets no score`() = runTest {
        val repo = repository()
        val later = item("Troyes vs Le Havre", null, addon = "c", startMs = Instant.parse("2026-11-21T02:00:00Z").toEpochMilli())
        repo.track(LiveScoresRepository.SCREEN_SECTION, listOf(matched, later))
        runCurrent()
        assertNull(repo.scores.value[later.streamKey])
    }
}
