package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class SportsResultsTest {

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()
    private val day = 24 * 60 * 60 * 1000L
    private val now = ms("2026-11-17T12:00:00Z")
    private val nov15 = LocalDate.of(2026, 11, 15)

    private val nba = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA)
    private val soccerAll = EspnFixtures.events(EspnFixtures.SOCCER_ALL_20261116, EspnLeagueMap.SOCCER_ALL)

    private fun item(
        name: String,
        start: String?,
        timed: Boolean = true,
        sport: String? = "Basketball",
        kind: SportsLiveKind = SportsLiveKind.REPLAY,
        isChannel: Boolean = false,
        genres: List<String> = listOfNotNull(sport)
    ) = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = genres
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Replays",
        kind = kind, isLive = false, isChannel = isChannel, startMs = start?.let(::ms), sport = sport,
        startHasTimeOfDay = timed, teams = SportsLiveTeams.parse(name)
    )

    // --- Relevant leagues ---

    @Test
    fun `relevant leagues come from My sports, default soccer all once plus NBA NFL NHL MLB and the individual sports`() {
        val relevant = SportsResults.relevantLeagues(MySports.DEFAULT, emptyList())
        assertEquals(
            listOf(
                EspnLeagueMap.SOCCER_ALL, EspnLeagueMap.NBA, EspnLeagueMap.NFL, EspnLeagueMap.NHL, EspnLeagueMap.MLB,
                EspnLeagueMap.ATP, EspnLeagueMap.WTA, EspnLeagueMap.UFC, EspnLeagueMap.F1, EspnLeagueMap.PGA, EspnLeagueMap.LPGA
            ),
            relevant.leagues
        )
    }

    @Test
    fun `college is fetched only when picked, and soccer leagues never add a request`() {
        val picked = MySports.decode(setOf("basketball", "basketball/nba", "basketball/college-basketball", "soccer", "soccer/eng.1"))
        assertEquals(
            listOf(EspnLeagueMap.SOCCER_ALL, EspnLeagueMap.NBA, EspnLeagueMap.COLLEGE_BASKETBALL),
            SportsResults.relevantLeagues(picked, emptyList()).leagues
        )
        val hockeyOnly = MySports.decode(setOf("hockey"))
        assertEquals(listOf(EspnLeagueMap.NHL), SportsResults.relevantLeagues(hockeyOnly, emptyList()).leagues)
    }

    @Test
    fun `a followed team adds its sport and league`() {
        val hockeyOnly = MySports.decode(setOf("hockey"))
        val relevant = SportsResults.relevantLeagues(
            hockeyOnly,
            listOf(FollowedTeam("Duke", "College Basketball"), FollowedTeam("Germany", "Soccer"), FollowedTeam("Lakers"))
        )
        assertEquals(listOf(EspnLeagueMap.SOCCER_ALL, EspnLeagueMap.COLLEGE_BASKETBALL, EspnLeagueMap.NHL), relevant.leagues)
        assertEquals(listOf(MySport.BASKETBALL, MySport.HOCKEY, MySport.SOCCER), relevant.mySports.effectiveSports)
    }

    @Test
    fun `only finished games are listed, postponed and cancelled are not`() {
        val live = nba[0].copy(eventId = "live", state = ScoreState.IN, isFinal = false)
        val upcoming = nba[0].copy(eventId = "pre", state = ScoreState.PRE, isFinal = false)
        val postponed = nba[0].copy(eventId = "off", state = ScoreState.OFF, isFinal = false)
        val rows = SportsResults.rows(nba + live + upcoming + postponed + soccerAll, emptyList())
        val ids = rows.map { it.event.eventId }.toSet()
        assertEquals(nba.map { it.eventId }.toSet() + setOf("401861185", "401927170", "401930175"), ids)
        assertFalse("401934216" in ids) // Canceled
    }

    @Test
    fun `my teams' games come first with the star, then by league and start`() {
        val rows = SportsResults.rows(nba, listOf(FollowedTeam("Knicks", "Basketball")))
        assertEquals("401909990", rows.first().event.eventId)
        assertTrue(rows.first().followed)
        assertTrue(rows.drop(1).none { it.followed })
        assertEquals(listOf("401909985", "401909986", "401909987", "401909989"), rows.drop(1).map { it.event.eventId })
    }

    @Test
    fun `the star needs a strong name match with the same qualifier`() {
        val women = nba[0].copy(
            league = EspnLeague("soccer", "eng.w.1"), leagueSlug = "eng.w.1",
            home = nba[0].home.copy(displayName = "Arsenal", shortDisplayName = "Arsenal", name = "Arsenal", location = "Arsenal", abbreviation = "ARS")
        )
        assertFalse(SportsResults.follows(FollowedTeam("Arsenal", "Soccer"), women))
        assertTrue(SportsResults.follows(FollowedTeam("Arsenal Women", "Soccer"), women))
        // A city alone (no sport to scope it) is not a team.
        assertFalse(SportsResults.follows(FollowedTeam("Indiana"), nba.first { it.eventId == "401909985" }))
        assertTrue(SportsResults.follows(FollowedTeam("Pacers"), nba.first { it.eventId == "401909985" }))
    }

    @Test
    fun `a followed team never stars another sport's namesake`() {
        fun game(sport: String, league: String) = nba.first().copy(
            league = EspnLeague(sport, league), leagueSlug = league,
            home = nba.first().home.copy(displayName = "Rangers", shortDisplayName = "Rangers", name = "Rangers", location = null, abbreviation = null)
        )
        val soccer = FollowedTeam("Rangers", "Soccer")
        assertTrue(SportsResults.follows(soccer, game("soccer", "sco.1")))
        assertFalse(SportsResults.follows(soccer, game("hockey", "nhl")))
        assertFalse(SportsResults.follows(soccer, game("baseball", "mlb")))
        // "Football" may be American football on an addon: soccer and the NFL, nothing else.
        val football = FollowedTeam("Rangers", "Football")
        assertTrue(SportsResults.follows(football, game("football", "nfl")))
        assertFalse(SportsResults.follows(football, game("hockey", "nhl")))
        // No sport given: no sport check (as before).
        assertTrue(SportsResults.follows(FollowedTeam("Rangers"), game("hockey", "nhl")))
        // In the rows: a followed team of another sport stars no namesake.
        val rows = SportsResults.rows(nba, listOf(FollowedTeam("Rangers", "Hockey"), FollowedTeam("Suns", "Hockey")))
        assertTrue(rows.none { it.followed })
    }

    @Test
    fun `an unresolved soccer all slug takes the women's or youth qualifier from the competition name`() {
        val women = EspnFixtures.events(EspnFixtures.SOCCER_ALL_WOMEN_20261108, EspnLeagueMap.SOCCER_ALL)
        // NCAAW Soccer: no league link on any of its games, so the slug stays "all".
        val temple = women.first { it.eventId == "401929870" }
        assertEquals("all", temple.leagueSlug)
        assertFalse(SportsResults.follows(FollowedTeam("Temple", "Soccer"), temple))
        // A lone friendly whose slug is unknown: the name says women's or U-21.
        val germany = soccerAll.first { it.eventId == "401861185" }.copy(leagueSlug = "all", competitionId = null)
        assertTrue(SportsResults.follows(FollowedTeam("Germany", "Soccer"), germany.copy(leagueName = "Men's International Friendly")))
        assertFalse(SportsResults.follows(FollowedTeam("Germany", "Soccer"), germany.copy(leagueName = "Women's International Friendly")))
        assertFalse(SportsResults.follows(FollowedTeam("Germany", "Soccer"), germany.copy(leagueName = "Men's U-21 Friendly")))
        assertTrue(SportsResults.follows(FollowedTeam("Germany U21", "Soccer"), germany.copy(leagueName = "Men's U-21 Friendly")))
        assertEquals("w", ScoreMatcher.leagueQualifier("all", "NCAAW Soccer"))
        assertEquals("u21", ScoreMatcher.leagueQualifier("all", "EURO U-21 Qualifying"))
        assertEquals(null, ScoreMatcher.leagueQualifier("all", "UEFA Nations League"))
    }

    @Test
    fun `soccer all lists every competition's finals and a competition feed's copy wins`() {
        val nations = soccerAll.first { it.eventId == "401861185" }.copy(league = EspnLeague("soccer", "uefa.nations"))
        val rows = SportsResults.rows(soccerAll + nations, emptyList())
        assertTrue(rows.any { it.event.eventId == "401861185" })
        assertEquals(EspnLeague("soccer", "uefa.nations"), rows.first { it.event.eventId == "401861185" }.event.league)
        assertEquals(rows.size, rows.map { it.event.eventId }.distinct().size)
    }

    @Test
    fun `final notes read ESPN's short detail`() {
        fun note(detail: String?) = SportsResults.finalNote(nba[0].copy(clockText = detail))
        assertEquals(FinalNote.FT, note("Final"))
        assertEquals(FinalNote.FT, note("FT"))
        assertEquals(FinalNote.OT, note("Final/OT"))
        assertEquals(FinalNote.OT, note("Final/2OT"))
        assertEquals(FinalNote.SO, note("Final/SO"))
        assertEquals(FinalNote.AET, note("AET"))
        assertEquals(FinalNote.PENS, note("FT-Pens"))
        assertEquals(FinalNote.FT, note(null))
    }

    @Test
    fun `the feed's league name is carried on its events`() {
        assertEquals("NBA", nba.first().leagueName)
        // This trimmed soccer/all fixture has no competition note.
        assertNull(soccerAll.first().leagueName)
    }

    // --- Replay match ---

    @Test
    fun `a replay with both teams and the date opens its result`() {
        val replay = item("Indiana Pacers vs Philadelphia 76ers", "2026-11-15T18:30:00Z")
        val found = SportsResults.replaysByEvent(nba, nov15, listOf(replay), now)
        assertEquals(mapOf("401909985" to replay), found)
    }

    @Test
    fun `a bare-date replay matches on its day`() {
        val replay = item("New York Knicks vs Toronto Raptors", "2026-11-15T00:00:00Z", timed = false)
        assertEquals(setOf("401909990"), SportsResults.replaysByEvent(nba, nov15, listOf(replay), now).keys)
    }

    @Test
    fun `a bare-date replay of the next day never opens this day's game`() {
        // A back-to-back series: game 2 the day after must not open game 1.
        val nextDay = item("New York Knicks vs Toronto Raptors", "2026-11-16T00:00:00Z", timed = false)
        assertTrue(SportsResults.replaysByEvent(nba, nov15, listOf(nextDay), now).isEmpty())
    }

    @Test
    fun `no replay when one side, the date or the name does not match`() {
        val replays = listOf(
            item("Indiana Pacers vs Boston Celtics", "2026-11-15T18:00:00Z"), // one side only
            item("Indiana Pacers vs Philadelphia 76ers", "2026-11-12T18:00:00Z"), // another day
            item("Indiana Pacers vs Philadelphia 76ers", null), // no date
            item("New York Knicks vs Toronto", "2026-11-15T23:00:00Z"), // one side by city only (weak)
            item("Lakers", "2026-11-15T21:00:00Z"), // a team folder
            item("NBA TV [Lakers vs Suns]", "2026-11-15T21:00:00Z", isChannel = true, kind = SportsLiveKind.CHANNELS)
        )
        assertTrue(SportsResults.replaysByEvent(nba, nov15, replays, now).isEmpty())
    }

    // --- Individual sports (#55 part 2) ---

    private fun individual(file: String, league: EspnLeague, date: LocalDate) = EspnIndividualResults.toSnapshots(
        EspnFixtures.moshi.adapter(EspnScoreboardDto::class.java).fromJson(EspnFixtures.json(file))!!, league, date
    )

    @Test
    fun `an individual result never gets a replay, even one naming both players at the exact time`() {
        val nov16 = LocalDate.of(2026, 11, 16)
        val match = individual("espn_tennis_atp_20261116.json", EspnLeagueMap.ATP, nov16).single { it.eventId == "tennis:187512" }
        // Even if a match ever carried its players as sides, it is never matched.
        val named = match.copy(
            home = match.home.copy(displayName = "Daniil Medvedev", name = "Medvedev"),
            away = match.away.copy(displayName = "Flavio Cobolli", name = "Cobolli")
        )
        val replay = item("Daniil Medvedev vs Flavio Cobolli", "2026-11-16T19:55:00Z", sport = "Tennis")
        assertTrue(SportsResults.replaysByEvent(listOf(match, named), nov16, listOf(replay), now).isEmpty())
        // Team games of the same day still match as before.
        val knicks = item("New York Knicks vs Toronto Raptors", "2026-11-15T00:00:00Z", timed = false)
        assertEquals(setOf("401909990"), SportsResults.replaysByEvent(nba + match, nov15, listOf(knicks), now).keys)
    }

    @Test
    fun `a UFC card lists its main event first, and no individual result is a followed team's`() {
        val card = individual("espn_ufc_20261114.json", EspnLeagueMap.UFC, LocalDate.of(2026, 11, 14))
        val rows = SportsResults.rows(card, listOf(FollowedTeam("Ciryl Gane", "MMA"), FollowedTeam("Gane", null)))
        assertEquals("mma:401913646", rows.first().event.eventId)
        assertEquals((0 until card.size).toList(), rows.map { (it.event.individual as Fight).bout })
        assertTrue(rows.none { it.followed })
        val groups = SportsResults.groups(rows)
        assertEquals(listOf("UFC 334: Gane vs. Hokit"), groups.map { it.name })
    }
}
