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
    fun `relevant leagues are followed teams' leagues plus leagues seen in the last 7 days`() {
        val recent = mapOf(
            "hockey/nhl" to now - 2 * day,
            "soccer/eng.1" to now - 6 * day,
            "baseball/mlb" to now - 8 * day // expired
        )
        val relevant = SportsResults.relevantLeagues(listOf(FollowedTeam("Lakers", "Basketball")), recent, now)
        assertEquals(listOf(EspnLeagueMap.NHL, EspnLeague("soccer", "eng.1"), EspnLeagueMap.NBA), relevant.leagues)
        assertTrue(relevant.followedOnly.isEmpty())
    }

    @Test
    fun `nothing followed and nothing seen lately is no relevant league`() {
        assertTrue(SportsResults.relevantLeagues(emptyList(), mapOf("hockey/nhl" to now - 8 * day), now).isEmpty)
        // A followed team without a sport routes nowhere by itself (its games' leagues are recorded as seen).
        assertTrue(SportsResults.relevantLeagues(listOf(FollowedTeam("Lakers")), emptyMap(), now).isEmpty)
    }

    @Test
    fun `a followed soccer team brings soccer all, listed for followed games only`() {
        val relevant = SportsResults.relevantLeagues(listOf(FollowedTeam("Germany", "Soccer")), emptyMap(), now)
        assertEquals(listOf(EspnLeagueMap.SOCCER_ALL), relevant.leagues)
        assertEquals(setOf(EspnLeagueMap.SOCCER_ALL), relevant.followedOnly)
    }

    @Test
    fun `recording seen leagues stamps new ones, expires old ones and never records soccer all`() {
        val current = mapOf("hockey/nhl" to now - 8 * day, "basketball/nba" to now - day)
        val next = SportsResults.recordSeen(current, setOf(EspnLeagueMap.NFL, EspnLeagueMap.SOCCER_ALL), now)!!
        assertEquals(mapOf("basketball/nba" to now - day, "football/nfl" to now), next)
        // Seen again within the re-stamp interval: nothing to save.
        assertNull(SportsResults.recordSeen(next, setOf(EspnLeagueMap.NFL), now + 60_000))
        // After it: re-stamped.
        assertEquals(now + SportsResults.RESTAMP_MS, SportsResults.recordSeen(next, setOf(EspnLeagueMap.NFL), now + SportsResults.RESTAMP_MS)!!["football/nfl"])
    }

    @Test
    fun `a full set keeps its leagues and new ones only fill expired slots, in path order`() {
        val full = (1..SportsResults.MAX_RECENT).associate { "soccer/l%02d".format(it) to now - it * 60_000L }
        // Full: a new league on screen changes nothing, so nothing is written.
        assertNull(SportsResults.recordSeen(full, setOf(EspnLeagueMap.NBA, EspnLeagueMap.NHL), now))
        // One slot expires: the first new league by path takes it.
        val oneExpired = full + ("soccer/l01" to now - 8 * day)
        val next = SportsResults.recordSeen(oneExpired, setOf(EspnLeagueMap.NHL, EspnLeagueMap.NBA), now)!!
        assertEquals(SportsResults.MAX_RECENT, next.size)
        assertEquals(now, next["basketball/nba"])
        assertTrue("hockey/nhl" !in next && "soccer/l01" !in next)
    }

    @Test
    fun `leagues of cards come from their routing, channels excluded`() {
        val leagues = SportsResults.leaguesOf(
            listOf(
                item("Lakers vs Celtics", null, kind = SportsLiveKind.EVENTS),
                item("Arsenal vs Chelsea", null, sport = "Soccer", genres = listOf("Premier League"), kind = SportsLiveKind.EVENTS),
                item("ESPN [Rangers vs Bruins]", null, sport = "Hockey", kind = SportsLiveKind.CHANNELS, isChannel = true)
            )
        )
        assertEquals(setOf(EspnLeagueMap.NBA, EspnLeague("soccer", "eng.1")), leagues)
    }

    // --- Rows ---

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
    fun `a followed team of another sport does not star a namesake`() {
        val rows = SportsResults.rows(nba, listOf(FollowedTeam("Rangers", "Hockey"), FollowedTeam("Suns", "Hockey")))
        assertTrue(rows.none { it.followed })
    }

    @Test
    fun `soccer all lists only followed teams' games and a competition feed's copy wins`() {
        val nations = soccerAll.first { it.eventId == "401861185" }.copy(league = EspnLeague("soccer", "uefa.nations"))
        val rows = SportsResults.rows(
            soccerAll + nations,
            listOf(FollowedTeam("Germany", "Soccer")),
            followedOnly = setOf(EspnLeagueMap.SOCCER_ALL)
        )
        assertEquals(listOf("401861185"), rows.map { it.event.eventId })
        assertEquals(EspnLeague("soccer", "uefa.nations"), rows.single().event.league)
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
}
