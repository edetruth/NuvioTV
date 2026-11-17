package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.TeamPair
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class ScoreMatcherTest {

    private val nba = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA)
    private val nfl = EspnFixtures.events(EspnFixtures.NFL_20261115, EspnLeagueMap.NFL)
    private val eng1 = EspnFixtures.events(EspnFixtures.ENG1_20261108, EspnFixtures.ENG1)
    private val caf = EspnFixtures.events(EspnFixtures.CAF_QUAL_20261116, EspnFixtures.CAF_QUAL)
    private val soccerAll = EspnFixtures.events(EspnFixtures.SOCCER_ALL_20261116, EspnLeagueMap.SOCCER_ALL)

    private val matcher = ScoreMatcher(zone = { ZoneOffset.UTC })

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()
    private val hour = 60 * 60 * 1000L

    private fun card(title: String, start: String?, key: String = "$title@$start") =
        ScoreCard(key, SportsLiveTeams.parse(title)!!, start?.let(::ms), hasTimeOfDay = start != null)

    private fun match(title: String, start: String?, events: List<ScoreSnapshot>, now: String = "2026-11-16T20:00:00Z") =
        matcher.match(card(title, start), events, ms(now))?.eventId

    @Test
    fun `abbreviation-only titles match`() {
        assertEquals("401927170", match("NAM vs CMR", "2026-11-16T13:00:00Z", caf))
        assertEquals("401927135", match("SUD v SEN", "2026-11-16T13:00:00Z", caf))
        assertEquals("401909985", match("IND @ PHI", "2026-11-15T18:00:00Z", nba))
        assertEquals("401873061", match("LAR @ ARI", "2026-11-15T21:05:00Z", nfl))
    }

    @Test
    fun `full, short and partial names match`() {
        assertEquals("401927170", match("Namibia vs Cameroon", "2026-11-16T13:00:00Z", caf))
        assertEquals("401927173", match("Zimbabwe vs Equatorial Guinea", "2026-11-16T14:00:00Z", caf))
        assertEquals("401909985", match("Indiana Pacers at Philadelphia 76ers", "2026-11-15T18:00:00Z", nba))
        assertEquals("401909987", match("Los Angeles Clippers vs Charlotte Hornets", "2026-11-15T21:30:00Z", nba))
        assertEquals("401909990", match("Knicks vs Raptors", "2026-11-15T23:00:00Z", nba))
        assertEquals("401873062", match("Seattle Seahawks vs Las Vegas Raiders", "2026-11-15T21:05:00Z", nfl))
        assertEquals("401873063", match("San Francisco 49ers vs Dallas", "2026-11-15T21:25:00Z", nfl))
        assertEquals("401879240", match("Man Utd vs Aston Villa", "2026-11-08T16:30:00Z", eng1))
        assertEquals("401879240", match("Manchester United FC vs Villa", "2026-11-08T16:30:00Z", eng1))
        assertEquals("401879243", match("Crystal Palace vs Liverpool FC", "2026-11-08T14:00:00Z", eng1))
    }

    @Test
    fun `matching ignores the order of the sides`() {
        assertEquals("401927170", match("Cameroon vs Namibia", "2026-11-16T13:00:00Z", caf))
        assertEquals("401927170", match("CMR vs NAM", "2026-11-16T13:00:00Z", caf))
        assertEquals("401909985", match("76ers @ Pacers", "2026-11-15T18:00:00Z", nba))
    }

    @Test
    fun `a match on one side only is rejected`() {
        assertNull(match("Namibia vs Senegal", "2026-11-16T13:00:00Z", caf))
        assertNull(match("NAM vs Ghana", "2026-11-16T13:00:00Z", caf))
        assertNull(match("Celtics vs Knicks", "2026-11-15T23:00:00Z", nba))
        assertNull(match("Arsenal vs Liverpool", "2026-11-08T14:00:00Z", eng1))
        // ESPN's "IND" is Indiana: an India card does not borrow it.
        assertNull(match("India vs Philadelphia 76ers", "2026-11-15T18:00:00Z", nba))
    }

    @Test
    fun `a city alone is weak, so two cities never match`() {
        assertNull(match("San Francisco vs Dallas", "2026-11-15T21:25:00Z", nfl))
        assertNull(match("Los Angeles vs Arizona", "2026-11-15T21:05:00Z", nfl))
        assertNull(match("Indiana vs Philadelphia", "2026-11-15T18:00:00Z", nba))
    }

    @Test
    fun `women's and U21 games stay distinct`() {
        assertNull(match("Namibia (W) vs Cameroon (W)", "2026-11-16T13:00:00Z", caf))
        assertNull(match("NAM (W) vs CMR (W)", "2026-11-16T13:00:00Z", caf))
        assertNull(match("Namibia (U21) vs Cameroon (U21)", "2026-11-16T13:00:00Z", caf))
        assertNull(match("Namibia U21 vs Cameroon U21", "2026-11-16T13:00:00Z", caf))
        assertNull(match("Liverpool (W) vs Crystal Palace (W)", "2026-11-08T14:00:00Z", eng1))
    }

    @Test
    fun `soccer all keeps senior and U21 games apart`() {
        // Real soccer/all day: England U21 v France U21 (shortDisplayName "England", abbreviation "ENG").
        assertNull(match("England vs France", "2026-11-16T19:30:00Z", soccerAll))
        assertNull(match("ENG vs FRA", "2026-11-16T19:30:00Z", soccerAll))
        assertEquals("401930175", match("England U21 vs France U21", "2026-11-16T19:30:00Z", soccerAll))
        assertEquals("401930175", match("ENG (U21) vs FRA (U21)", "2026-11-16T19:30:00Z", soccerAll))
        assertEquals("401861185", match("Germany vs Netherlands", "2026-11-16T19:45:00Z", soccerAll))
        assertNull(match("Germany U21 vs Netherlands U21", "2026-11-16T19:45:00Z", soccerAll))
    }

    @Test
    fun `a women's league slug needs a women's card`() {
        val original = caf.first { it.eventId == "401927170" }
        val womens = original.copy(eventId = "w", leagueSlug = "caf.w.nations")
        assertNull(match("Namibia vs Cameroon", "2026-11-16T13:00:00Z", listOf(womens)))
        assertEquals("w", match("Namibia (W) vs Cameroon (W)", "2026-11-16T13:00:00Z", listOf(womens)))
        assertEquals("w", match("Namibia Women vs Cameroon Women", "2026-11-16T13:00:00Z", listOf(womens)))
        assertNull(match("Namibia vs Cameroon", "2026-11-16T13:00:00Z", listOf(original.copy(eventId = "x", leagueSlug = "uefa.wchampions"))))
        assertNull(match("Namibia vs Cameroon", "2026-11-16T13:00:00Z", listOf(original.copy(eventId = "y", leagueSlug = "usa.nwsl"))))
        assertEquals("z", match("Namibia vs Cameroon", "2026-11-16T13:00:00Z", listOf(original.copy(eventId = "z", leagueSlug = "fifa.world"))))
    }

    @Test
    fun `DR Congo matches Congo DR`() {
        val original = caf.first { it.eventId == "401927170" }
        val congo = original.copy(
            eventId = "cod",
            home = original.home.copy(abbreviation = "COD", displayName = "Congo DR", shortDisplayName = "Congo DR", name = "Congo DR", location = "Congo DR")
        )
        assertEquals("cod", match("DR Congo vs Cameroon", "2026-11-16T13:00:00Z", listOf(congo)))
        assertEquals("cod", match("Congo DR vs Cameroon", "2026-11-16T13:00:00Z", listOf(congo)))
    }

    @Test
    fun `games outside the window are rejected`() {
        assertEquals("401927170", match("NAM vs CMR", "2026-11-16T15:59:00Z", caf))
        assertEquals("401927170", match("NAM vs CMR", "2026-11-16T10:01:00Z", caf))
        assertNull(match("NAM vs CMR", "2026-11-16T16:01:00Z", caf))
        assertNull(match("NAM vs CMR", "2026-11-16T09:59:00Z", caf))
        assertNull(match("NAM vs CMR", "2026-11-17T13:00:00Z", caf))
    }

    @Test
    fun `with no start the game must be today or yesterday`() {
        assertEquals("401927170", match("NAM vs CMR", null, caf, now = "2026-11-16T23:00:00Z"))
        assertEquals("401927170", match("NAM vs CMR", null, caf, now = "2026-11-17T08:00:00Z"))
        assertNull(match("NAM vs CMR", null, caf, now = "2026-11-18T08:00:00Z"))
        assertNull(match("NAM vs CMR", null, caf, now = "2026-11-15T08:00:00Z"))
    }

    @Test
    fun `with no start today's game beats yesterday's in a series`() {
        // Same opponents on consecutive days (a baseball-style series).
        val original = nba.first { it.eventId == "401909985" }
        val yesterday = original.copy(eventId = "yesterday", startMs = ms("2026-11-15T23:00:00Z"))
        val today = original.copy(eventId = "today", startMs = ms("2026-11-16T18:00:00Z"))
        // Just after midnight yesterday's final is closer to now, but today's game is the card's.
        assertEquals("today", match("Pacers @ 76ers", null, listOf(yesterday, today), now = "2026-11-16T01:00:00Z"))
        assertEquals("yesterday", match("Pacers @ 76ers", null, listOf(yesterday), now = "2026-11-16T01:00:00Z"))
        val bareDate = ScoreCard("bare", TeamPair("76ers", "Pacers"), ms("2026-11-16T00:00:00Z"), hasTimeOfDay = false)
        assertEquals("today", matcher.match(bareDate, listOf(yesterday, today), ms("2026-11-20T00:00:00Z"))?.eventId)
    }

    @Test
    fun `a bare date is matched by day`() {
        val bareDate = ScoreCard("k", TeamPair("NAM", "CMR"), ms("2026-11-16T00:00:00Z"), hasTimeOfDay = false)
        assertEquals("401927170", matcher.match(bareDate, caf, ms("2026-11-30T00:00:00Z"))?.eventId)
        val dayAfter = bareDate.copy(key = "k2", startMs = ms("2026-11-18T00:00:00Z"))
        assertNull(matcher.match(dayAfter, caf, ms("2026-11-30T00:00:00Z")))
    }

    @Test
    fun `a tie goes to the closest start`() {
        val original = caf.first { it.eventId == "401927170" }
        val later = original.copy(eventId = "later", startMs = original.startMs!! + 2 * hour)
        val events = caf + later
        assertEquals("401927170", match("NAM vs CMR", "2026-11-16T13:30:00Z", events))
        assertEquals("later", ScoreMatcher(zone = { ZoneOffset.UTC })
            .match(card("NAM vs CMR", "2026-11-16T14:30:00Z"), events, ms("2026-11-16T20:00:00Z"))?.eventId)
    }

    @Test
    fun `suffix on both sides is not enough, and equality beats suffix`() {
        val original = caf.first { it.eventId == "401927170" }
        val northNamibia = original.home.copy(abbreviation = null, displayName = "North Namibia", shortDisplayName = null, name = null, location = null)
        // Both sides only match "Namibia"/"Cameroon" by the suffix rule: 2 + 2 is below 5.
        val suffixOnly = original.copy(
            eventId = "suffix",
            home = northNamibia,
            away = original.away.copy(abbreviation = null, displayName = "West Cameroon", shortDisplayName = null, name = null, location = null)
        )
        assertNull(match("Namibia vs Cameroon", "2026-11-16T13:00:00Z", listOf(suffixOnly)))
        // One exact side plus one suffix side is enough, but the all-exact event wins.
        val oneExact = original.copy(eventId = "one-exact", home = northNamibia)
        assertEquals("one-exact", match("Namibia vs Cameroon", "2026-11-16T13:00:00Z", listOf(oneExact)))
        val fresh = ScoreMatcher(zone = { ZoneOffset.UTC }) // the matcher above has cached "one-exact"
        assertEquals("401927170", fresh.match(card("Namibia vs Cameroon", "2026-11-16T13:00:00Z"), listOf(oneExact) + caf, 0L)?.eventId)
    }

    @Test
    fun `diacritics are ignored`() {
        val original = caf.first { it.eventId == "401927170" }
        val ivoryCoast = original.copy(
            eventId = "civ",
            home = original.home.copy(abbreviation = "CIV", displayName = "Côte d'Ivoire", shortDisplayName = "Côte d'Ivoire", name = "Côte d'Ivoire", location = "Côte d'Ivoire")
        )
        assertEquals("civ", match("Ivory Coast vs Cameroon", "2026-11-16T13:00:00Z", listOf(ivoryCoast)))
        assertEquals("civ", match("Cote d'Ivoire vs Cameroon", "2026-11-16T13:00:00Z", listOf(ivoryCoast)))
    }

    @Test
    fun `channels never match unless their name brackets a game`() {
        assertNull(ScoreCard.from(item("Sky Sports Main Event", isChannel = true)))
        // A channel's teams field is not trusted: only parseBracketed counts.
        assertNull(ScoreCard.from(item("Namibia vs Cameroon HD", isChannel = true, teams = TeamPair("Namibia", "Cameroon"))))
        val bracketed = ScoreCard.from(item("US| PEACOCK PPV 004 [NAM vs CMR (2026-11-16 13:00)]", isChannel = true))
        assertNotNull(bracketed)
        assertEquals("401927170", matcher.match(bracketed!!.copy(startMs = ms("2026-11-16T13:00:00Z"), hasTimeOfDay = true), caf, 0L)?.eventId)

        val game = ScoreCard.from(item("Namibia vs Cameroon", isChannel = false))!!
        assertEquals(TeamPair("Namibia", "Cameroon"), game.teams)
        assertEquals("a|Namibia vs Cameroon", game.key)
    }

    @Test
    fun `a match is cached per card per day`() {
        val first = matcher.match(card("NAM vs CMR", "2026-11-16T13:00:00Z", key = "card"), caf, 0L)
        assertEquals("401927170", first?.eventId)
        // A later poll with the same event under unrecognisable names still finds it by id, with fresh data.
        val renamed = first!!.copy(
            home = first.home.copy(abbreviation = "X", displayName = "X", shortDisplayName = "X", name = "X", location = "X", score = 1)
        )
        assertEquals(renamed, matcher.match(card("NAM vs CMR", "2026-11-16T13:00:00Z", key = "card"), listOf(renamed), 0L))
        // Another card does not share the cache, and another day re-matches.
        assertNull(matcher.match(card("NAM vs CMR", "2026-11-16T13:00:00Z", key = "other"), listOf(renamed), 0L))
        assertNull(matcher.match(card("NAM vs CMR", "2026-11-17T13:00:00Z", key = "card"), listOf(renamed), 0L))
    }

    private fun item(name: String, isChannel: Boolean, teams: TeamPair? = null) = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = "a", addonName = "A", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
        kind = SportsLiveKind.EVENTS, isLive = true, isChannel = isChannel, startMs = null, sport = null,
        teams = teams ?: SportsLiveTeams.parse(name)
    )
}
