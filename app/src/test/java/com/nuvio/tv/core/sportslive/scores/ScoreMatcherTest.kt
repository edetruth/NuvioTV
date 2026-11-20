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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
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
    fun `games outside the window are rejected unless both sides match strongly`() {
        assertEquals("401927170", match("NAM vs CMR", "2026-11-16T15:59:00Z", caf))
        assertEquals("401927170", match("NAM vs CMR", "2026-11-16T10:01:00Z", caf))
        // Past 3 h only the pair's one meeting with both sides STRONG (181); a day off is too far.
        assertEquals("401927170", match("NAM vs CMR", "2026-11-16T16:01:00Z", caf))
        assertNull(match("NAM vs CMR", "2026-11-17T13:00:00Z", caf))
        // One side matched by its city alone stays within 3 h.
        assertEquals("401873063", match("San Francisco 49ers vs Dallas", "2026-11-15T21:25:00Z", nfl))
        assertNull(match("San Francisco 49ers vs Dallas", "2026-11-16T01:30:00Z", nfl))
    }

    @Test
    fun `an addon clock 6 hours off still matches the pair's one game (181)`() {
        val game = caf.first { it.eventId == "401927170" }
        assertEquals("401927170", match("Namibia vs Cameroon", Instant.ofEpochMilli(game.startMs!! - 6 * hour).toString(), caf))
        assertEquals("401927170", match("Cameroon vs Namibia", Instant.ofEpochMilli(game.startMs!! + 6 * hour).toString(), caf))
    }

    @Test
    fun `back-to-back meetings on consecutive days are never paired across days (181)`() {
        val game = caf.first { it.eventId == "401927170" }
        val nextDay = game.copy(eventId = "next-day", startMs = game.startMs!! + 24 * hour)
        val events = caf + nextDay
        // 6 h after the first game: no game within 3 h, and the pair meets twice within the wide window.
        assertNull(match("Namibia vs Cameroon", Instant.ofEpochMilli(game.startMs!! + 6 * hour).toString(), events))
        assertNull(match("Namibia vs Cameroon", Instant.ofEpochMilli(nextDay.startMs!! - 6 * hour).toString(), events))
        // Within 3 h each day's own game still matches.
        assertEquals("next-day", match("Namibia vs Cameroon", Instant.ofEpochMilli(nextDay.startMs!!).toString(), events))
    }

    @Test
    fun `a women's or youth namesake hours off is never matched (181)`() {
        val game = caf.first { it.eventId == "401927170" }
        val off = Instant.ofEpochMilli(game.startMs!! + 6 * hour).toString()
        // The same clubs on a women's feed: no women's marker on either side of the card.
        val women = game.copy(eventId = "w", league = EspnLeagueMap.WOMEN_FEEDS.first())
        assertNull(match("Namibia vs Cameroon", off, listOf(women)))
        // A women's or youth slug never fits an unmarked card, near or far.
        assertNull(match("Namibia vs Cameroon", off, listOf(game.copy(eventId = "ws", leagueSlug = "fifa.wworldq.caf"))))
        assertNull(match("Namibia vs Cameroon", off, listOf(game.copy(eventId = "u", leagueSlug = "fifa.friendly_u21"))))
        // The card that names the youth sides does not take the senior game either.
        assertNull(match("Namibia U21 vs Cameroon U21", off, caf))
    }

    @Test
    fun `with no start the game must be today or yesterday`() {
        assertEquals("401927170", match("NAM vs CMR", null, caf, now = "2026-11-16T23:00:00Z"))
        assertEquals("401927170", match("NAM vs CMR", null, caf, now = "2026-11-17T08:00:00Z"))
        assertNull(match("NAM vs CMR", null, caf, now = "2026-11-18T08:00:00Z"))
        assertNull(match("NAM vs CMR", null, caf, now = "2026-11-15T08:00:00Z"))
    }

    @Test
    fun `with no start a bare date still prefers its own day in a series`() {
        val original = nba.first { it.eventId == "401909985" }
        val yesterday = original.copy(eventId = "yesterday", startMs = ms("2026-11-15T23:00:00Z"))
        val today = original.copy(eventId = "today", startMs = ms("2026-11-16T18:00:00Z"))
        val bareDate = ScoreCard("bare", TeamPair("76ers", "Pacers"), ms("2026-11-16T00:00:00Z"), hasTimeOfDay = false)
        assertEquals("today", matcher.match(bareDate, listOf(yesterday, today), ms("2026-11-20T00:00:00Z"))?.eventId)
    }

    // A card with no start (a channel's "[Pacers vs 76ers]") is matched on ESPN's US Eastern days, in
    // every device zone. Pacers/76ers stand in for an MLB series and an NHL home-and-home.
    private val zones = listOf("Europe/London", "Europe/Berlin", "Asia/Tokyo", "America/Los_Angeles", "UTC").map(ZoneId::of)
    private val base = nba.first { it.eventId == "401909985" }

    private fun meeting(id: String, start: String, state: ScoreState, swapped: Boolean = false) = base.copy(
        eventId = id,
        startMs = ms(start),
        state = state,
        isFinal = state == ScoreState.POST,
        home = if (swapped) base.away else base.home,
        away = if (swapped) base.home else base.away
    )

    private fun undated(zone: ZoneId, events: List<ScoreSnapshot>, now: String, matcher: ScoreMatcher = ScoreMatcher(zone = { zone })) =
        matcher.match(ScoreCard("channel", TeamPair("Pacers", "76ers"), null, hasTimeOfDay = false), events, ms(now))?.eventId

    @Test
    fun `with no start a series shows tonight's game, never yesterday's final, in every zone`() {
        // 19:10 Eastern on the 15th (final) and on the 16th: 00:10 UTC on the 16th and on the 17th.
        val yesterdayFinal = meeting("yesterday", "2026-11-16T00:10:00Z", ScoreState.POST)
        for (zone in zones) {
            // 16:00 Eastern on the 16th: 21:00 London, 22:00 Berlin, 06:00 Tokyo (17th), 13:00 Los Angeles.
            // Berlin's own day (the 16th) holds yesterday's Eastern game but not tonight's.
            val scheduled = meeting("today", "2026-11-17T00:10:00Z", ScoreState.PRE)
            assertEquals("$zone", "today", undated(zone, listOf(yesterdayFinal, scheduled), now = "2026-11-16T21:00:00Z"))
            // 20:00 Eastern: tonight's game is live.
            val live = meeting("today", "2026-11-17T00:10:00Z", ScoreState.IN)
            assertEquals("$zone", "today", undated(zone, listOf(yesterdayFinal, live), now = "2026-11-17T01:00:00Z"))
            // 23:00 Eastern: both are finals; tonight's is today's lone final, never yesterday's (I6).
            val tonightFinal = meeting("today", "2026-11-17T00:10:00Z", ScoreState.POST)
            assertEquals("$zone", "today", undated(zone, listOf(yesterdayFinal, tonightFinal), now = "2026-11-17T04:00:00Z"))
            // Two live meetings (a doubleheader) are ambiguous too.
            val other = meeting("other", "2026-11-16T17:05:00Z", ScoreState.IN)
            assertNull("$zone", undated(zone, listOf(other, live), now = "2026-11-17T01:00:00Z"))
            // A game still listed as scheduled on yesterday's Eastern day is not today's.
            val stale = meeting("stale", "2026-11-15T18:00:00Z", ScoreState.PRE)
            assertNull("$zone", undated(zone, listOf(yesterdayFinal, stale), now = "2026-11-16T21:00:00Z"))
        }
    }

    @Test
    fun `with no start an NHL home-and-home shows tonight's game in every zone`() {
        // Yesterday at one rink, tonight at the other: the sides swap.
        val yesterdayFinal = meeting("yesterday", "2026-11-16T00:00:00Z", ScoreState.POST)
        for (zone in zones) {
            val tonight = meeting("tonight", "2026-11-17T00:00:00Z", ScoreState.PRE, swapped = true)
            assertEquals("$zone", "tonight", undated(zone, listOf(yesterdayFinal, tonight), now = "2026-11-16T22:30:00Z"))
            val live = tonight.copy(state = ScoreState.IN)
            assertEquals("$zone", "tonight", undated(zone, listOf(yesterdayFinal, live), now = "2026-11-17T01:30:00Z"))
        }
    }

    @Test
    fun `with no start a lone meeting matches and is not cached over a later one`() {
        val yesterdayFinal = meeting("yesterday", "2026-11-16T00:10:00Z", ScoreState.POST)
        val tonight = meeting("today", "2026-11-17T00:10:00Z", ScoreState.PRE)
        for (zone in zones) {
            val matcher = ScoreMatcher(zone = { zone })
            // Only yesterday's meeting is in the feeds: it is the pair's only game.
            assertEquals("$zone", "yesterday", undated(zone, listOf(yesterdayFinal), "2026-11-16T21:00:00Z", matcher))
            // Tonight's arrives with the next poll: it replaces yesterday's, never held by a cache.
            assertEquals("$zone", "today", undated(zone, listOf(yesterdayFinal, tonight), "2026-11-16T21:00:00Z", matcher))
            // Two Eastern days back is out of reach.
            assertNull("$zone", undated(zone, listOf(yesterdayFinal), "2026-11-18T12:00:00Z", matcher))
        }
    }

    // I6, real feeds: Minnesota and Portland met on 2026-11-16 and again on 2026-11-17 (ET), both finals
    // by 23:00 ET; the addon's card had no start, so the two finals tied and the card kept its LIVE pill.
    private val minPor = EspnFixtures.events("espn_nba_min_por_20261116.json", EspnLeagueMap.NBA) +
        EspnFixtures.events("espn_nba_min_por_20261117.json", EspnLeagueMap.NBA)

    @Test
    fun `an undated back-to-back card takes tonight's final, not last night's (I6)`() {
        for (zone in zones) {
            val m = ScoreMatcher(zone = { zone })
            val card = ScoreCard("min-por", SportsLiveTeams.parse("Minnesota Timberwolves vs Portland Trail Blazers")!!, null, hasTimeOfDay = false)
            // 23:30 ET on the 17th.
            val event = m.match(card, minPor, ms("2026-11-18T04:30:00Z"))
            assertEquals("$zone", "401910003", event?.eventId)
            assertEquals(106, event?.home?.score)
            assertEquals(105, event?.away?.score)
            assertEquals(true, ScoreMatcher.firstIsHome("Minnesota Timberwolves", "Portland Trail Blazers", event!!))
        }
    }

    @Test
    fun `two cities alone still never match, even in a back-to-back (I6)`() {
        val card = ScoreCard("min-por-cities", TeamPair("Minnesota", "Portland"), ms("2026-11-18T01:00:00Z"), hasTimeOfDay = true)
        assertNull(ScoreMatcher(zone = { ZoneOffset.UTC }).match(card, minPor, ms("2026-11-18T04:30:00Z")))
    }

    @Test
    fun `Southern Methodist is SMU on the strict college match (I6)`() {
        val cbb = EspnFixtures.events("espn_cbb_smu_20261117.json", EspnLeagueMap.COLLEGE_BASKETBALL)
        val strict = ScoreMatcher(zone = { ZoneOffset.UTC }, minScore = ScoreMatcher.BOTH_STRONG)
        val card = ScoreCard("smu-oru", SportsLiveTeams.parse("Southern Methodist vs Oral Roberts")!!, null, hasTimeOfDay = false)
        val event = strict.match(card, cbb, ms("2026-11-18T04:30:00Z"))
        assertEquals("401918970", event?.eventId)
        assertEquals(true, ScoreMatcher.firstIsHome("Southern Methodist", "Oral Roberts", event!!))
        // "Southern" alone is no school.
        val loose = ScoreCard("s-oru", TeamPair("Southern", "Oral Roberts"), null, hasTimeOfDay = false)
        assertNull(strict.match(loose, cbb, ms("2026-11-18T04:30:00Z")))
    }

    @Test
    fun `a bare date is matched by day`() {
        val bareDate = ScoreCard("k", TeamPair("NAM", "CMR"), ms("2026-11-16T00:00:00Z"), hasTimeOfDay = false)
        assertEquals("401927170", matcher.match(bareDate, caf, ms("2026-11-30T00:00:00Z"))?.eventId)
        val dayAfter = bareDate.copy(key = "k2", startMs = ms("2026-11-18T00:00:00Z"))
        assertNull(matcher.match(dayAfter, caf, ms("2026-11-30T00:00:00Z")))
    }

    @Test
    fun `two different events tied for the best score give no match`() {
        val original = caf.first { it.eventId == "401927170" }
        val later = original.copy(eventId = "later", startMs = original.startMs!! + 2 * hour)
        val events = caf + later
        // A doubleheader-like pair: the closer start no longer decides.
        assertNull(match("NAM vs CMR", "2026-11-16T13:30:00Z", events))
        assertNull(match("NAM vs CMR", null, events, now = "2026-11-16T20:00:00Z"))
        val bareDate = ScoreCard("bare", TeamPair("NAM", "CMR"), ms("2026-11-16T00:00:00Z"), hasTimeOfDay = false)
        assertNull(ScoreMatcher(zone = { ZoneOffset.UTC }).match(bareDate, events, ms("2026-11-30T00:00:00Z")))
        // A better-scoring event still wins over a weaker one.
        val weaker = later.copy(home = later.home.copy(abbreviation = null, displayName = "North Namibia", shortDisplayName = null, name = null, location = null))
        assertEquals("401927170", match("NAM vs CMR", "2026-11-16T13:30:00Z", caf + weaker))
    }

    @Test
    fun `an ambiguous day does not fall back to the day before`() {
        val original = caf.first { it.eventId == "401927170" }
        val twin = original.copy(eventId = "twin", startMs = original.startMs!! + 2 * hour)
        val dayBefore = original.copy(eventId = "before", startMs = original.startMs - 24 * hour)
        val bareDate = ScoreCard("bare", TeamPair("NAM", "CMR"), ms("2026-11-16T00:00:00Z"), hasTimeOfDay = false)
        assertNull(ScoreMatcher(zone = { ZoneOffset.UTC }).match(bareDate, listOf(original, twin, dayBefore), ms("2026-11-30T00:00:00Z")))
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

    private val liveSoccer = EspnFixtures.events(EspnFixtures.SOCCER_ALL_LIVE_20261117, EspnLeagueMap.SOCCER_ALL)

    @Test
    fun `USA v Haiti, live on 2026-11-17, matches in the forms addons write it (#66)`() {
        val kickoff = "2026-11-18T00:00:00Z"
        val now = "2026-11-18T00:50:00Z"
        for (title in listOf("USA vs Haiti", "USA v Haiti", "United States vs Haiti", "USMNT vs Haiti", "Haiti @ USA", "US vs Haiti")) {
            assertEquals(title, "401929766", match(title, kickoff, liveSoccer, now))
        }
        // No start (a channel's "[USA vs Haiti]"): ESPN's Eastern day.
        assertEquals("401929766", match("USA vs Haiti", null, liveSoccer, now))
        // The women's and youth teams never take the men's game.
        for (title in listOf("USA (W) vs Haiti (W)", "USA Women vs Haiti Women", "USA U20 vs Haiti U20", "USWNT vs Haiti")) {
            assertNull(title, match(title, kickoff, liveSoccer, now))
        }
    }

    @Test
    fun `the other live national and club games of 2026-11-17 match their usual titles (#66)`() {
        val now = "2026-11-18T00:50:00Z"
        val vincent = liveSoccer.first { it.away.displayName == "St. Vincent and the Grenadines" }.eventId
        for (title in listOf("French Guiana vs St Vincent", "French Guiana vs Saint Vincent and the Grenadines", "French Guiana vs St. Vincent & the Grenadines")) {
            assertEquals(title, vincent, match(title, "2026-11-18T00:00:00Z", liveSoccer, now))
        }
        assertEquals(liveSoccer.first { it.home.displayName == "Uruguay" }.eventId, match("Uruguay vs Dominican Rep.", "2026-11-17T23:30:00Z", liveSoccer, now))
        assertEquals(liveSoccer.first { it.home.displayName == "Ecuador" }.eventId, match("Ecuador vs Bolivia", "2026-11-17T23:30:00Z", liveSoccer, now))
        val nacional = liveSoccer.first { it.away.displayName == "Once Caldas" }.eventId
        assertEquals(nacional, match("Atletico Nacional vs Once Caldas", "2026-11-17T23:30:00Z", liveSoccer, now))
        assertEquals(nacional, match("Atlético Nacional v Once Caldas", "2026-11-17T23:30:00Z", liveSoccer, now))
        // ESPN names no league for this game (no link, no sibling): a weak side ("Nacional") is not enough.
        assertNull(match("Nacional vs Once Caldas", "2026-11-17T23:30:00Z", liveSoccer, now))
    }

    @Test
    fun `national team aliases fold both ways and keep qualifiers (#66)`() {
        fun same(a: String, b: String) = assertEquals("$a = $b", SportsLiveTeams.canonicalise(b), SportsLiveTeams.canonicalise(a))
        same("USA", "United States"); same("USMNT", "United States"); same("UAE", "United Arab Emirates")
        same("Korea Republic", "South Korea"); same("Ivory Coast", "Côte d'Ivoire"); same("Türkiye", "Turkey")
        same("IR Iran", "Iran"); same("Czechia", "Czech Republic"); same("Bosnia-Herzegovina", "Bosnia and Herzegovina")
        same("Bosnia & Herzegovina", "Bosnia"); same("Cabo Verde", "Cape Verde"); same("Congo DR", "DR Congo")
        same("China PR", "China"); same("Holland", "Netherlands"); same("Republic of Ireland", "Ireland")
        same("Man Utd", "Manchester Utd"); same("Nott'm Forest", "Nottingham Forest")
        assertTrue(SportsLiveTeams.canonicalise("USA (W)") != SportsLiveTeams.canonicalise("United States"))
        assertTrue(SportsLiveTeams.canonicalise("USWNT") != SportsLiveTeams.canonicalise("United States"))
        assertTrue(SportsLiveTeams.canonicalise("Northern Ireland") != SportsLiveTeams.canonicalise("Ireland"))
        // And in a match: DR Congo is Congo DR either way round.
        val original = caf.first { it.eventId == "401927170" }
        val congo = original.copy(
            eventId = "cod",
            home = original.home.copy(abbreviation = "COD", displayName = "Congo DR", shortDisplayName = "Congo DR", name = "Congo DR", location = "Congo DR")
        )
        assertEquals("cod", match("DR Congo vs Cameroon", "2026-11-16T13:00:00Z", listOf(congo)))
        assertEquals("cod", match("Congo DR vs Cameroon", "2026-11-16T13:00:00Z", listOf(congo)))
    }


    /** An eng.1 game as soccer/all lists one with no league link, no sibling and no note. */
    private fun unknownLeague(event: ScoreSnapshot, id: String, startMs: Long? = event.startMs) =
        event.copy(eventId = id, league = EspnLeagueMap.SOCCER_ALL, leagueSlug = "all", leagueName = null, competitionId = null, startMs = startMs)

    @Test
    fun `an event of unknown league needs both sides strong (#66)`() {
        val game = eng1.first { it.eventId == "401879240" }
        val unknown = listOf(unknownLeague(game, "u1"))
        val start = java.time.Instant.ofEpochMilli(game.startMs!!).toString()
        val now = start
        // Both strong: matched.
        assertEquals("u1", match("${game.home.displayName} vs ${game.away.displayName}", start, unknown, now))
        // One side only weak (a suffix): enough on a known league, not here.
        val weakHome = game.home.displayName!!.substringAfter(' ')
        assertEquals("401879240", match("$weakHome vs ${game.away.displayName}", start, listOf(game), now))
        assertNull(match("$weakHome vs ${game.away.displayName}", start, unknown, now))
        // A women's card never takes it.
        assertNull(match("${game.home.displayName} (W) vs ${game.away.displayName} (W)", start, unknown, now))
    }

    @Test
    fun `a men's game and an unknown-league game of the same pair that day give no score (#66)`() {
        val game = eng1.first { it.eventId == "401879240" }
        val start = java.time.Instant.ofEpochMilli(game.startMs!!).toString()
        // The same clubs again five hours later, league unknown (a women's game ESPN does not label).
        val later = unknownLeague(game, "w1", game.startMs!! + 5 * 3_600_000L)
        val title = "${game.home.displayName} vs ${game.away.displayName}"
        assertEquals("401879240", match(title, start, listOf(game), start))
        assertNull(match(title, start, listOf(game, later), start))
        assertNull(match(title, java.time.Instant.ofEpochMilli(later.startMs!!).toString(), listOf(game, later), start))
    }

    // --- Hardening: women's slugs, youth forms, curly apostrophes, club names and punctuation (S1 follow-up + S1b) ---

    private val germany = soccerAll.first { it.eventId == "401861185" } // Germany 5-0 Netherlands, 19:45Z
    private val englandU21 = soccerAll.first { it.eventId == "401930175" } // England U21 v France U21, 19:30Z

    /** A timed card matched with the clock at 0, as the hardening cases were written. */
    private fun matchTimed(title: String, start: String, events: List<ScoreSnapshot>): String? =
        ScoreMatcher(zone = { ZoneOffset.UTC })
            .match(ScoreCard("k", SportsLiveTeams.parse(title)!!, ms(start), hasTimeOfDay = true), events, 0L)?.eventId

    @Test
    fun `a men's card never matches a game in a women's league`() {
        val women = listOf(
            "eng.w.1", "esp.w.1", "can.w.nsl", "usa.w.usl.1", "usa.nwsl", "uefa.wchampions", "fifa.wwc",
            "uefa.weuro", "uefa.wnations", "fifa.wworld.u17", "fifa.wworldq.uefa", "esp.copa_de_la_reina",
            "conmebol.america.femenina", "fifa.shebelieves"
        )
        for (slug in women) {
            assertNull(slug, matchTimed("Germany vs Netherlands", "2026-11-16T19:45:00Z", listOf(germany.copy(leagueSlug = slug))))
        }
    }

    @Test
    fun `men's world cup, qualifiers and Wales' league are not women's`() {
        for (slug in listOf("fifa.world", "fifa.worldq.uefa", "fifa.worldq.conmebol", "wal.1", "uefa.nations", "fifa.friendly")) {
            assertEquals(slug, "401861185", matchTimed("Germany vs Netherlands", "2026-11-16T19:45:00Z", listOf(germany.copy(leagueSlug = slug))))
        }
    }

    @Test
    fun `youth forms are one qualifier`() {
        for (title in listOf("England U-21 vs France U-21", "England Under 21 vs France Under-21", "England U21s vs France (U21)")) {
            assertEquals(title, "401930175", matchTimed(title, "2026-11-16T19:30:00Z", soccerAll))
        }
        // Senior and youth sides stay apart.
        assertNull(matchTimed("England Under-21 vs France", "2026-11-16T19:30:00Z", listOf(englandU21)))
        assertEquals(listOf("401861185"), listOf(matchTimed("Germany vs Netherlands", "2026-11-16T19:45:00Z", soccerAll)))
    }

    @Test
    fun `a curly apostrophe still marks a women's side`() {
        assertNull(matchTimed("Germany Women’s vs Netherlands Women’s", "2026-11-16T19:45:00Z", listOf(germany)))
        assertNull(matchTimed("Germany Women's vs Netherlands Women's", "2026-11-16T19:45:00Z", listOf(germany)))
    }

    private fun clubGame(home: String, away: String) = germany.copy(
        eventId = "club",
        leagueSlug = "ita.1",
        home = germany.home.copy(abbreviation = null, displayName = home, shortDisplayName = home, name = home, location = null),
        away = germany.away.copy(abbreviation = null, displayName = away, shortDisplayName = away, name = away, location = null)
    )

    @Test
    fun `club tags, club aliases and Inter versus Milan`() {
        val milan = clubGame("AC Milan", "Juventus")
        assertEquals("club", matchTimed("Milan vs Juventus", "2026-11-16T19:45:00Z", listOf(milan)))
        assertNull(matchTimed("Inter Milan vs Juventus", "2026-11-16T19:45:00Z", listOf(milan)))
        assertEquals("club", matchTimed("Inter Milan vs Juventus", "2026-11-16T19:45:00Z", listOf(clubGame("Internazionale", "Juventus"))))
        assertEquals("club", matchTimed("Man Utd vs PSG", "2026-11-16T19:45:00Z", listOf(clubGame("Manchester United", "Paris Saint-Germain"))))
        assertEquals("club", matchTimed("VfB Stuttgart vs Bayern", "2026-11-16T19:45:00Z", listOf(clubGame("Stuttgart", "Bayern Munich"))))
        // Ambiguous nicknames are not aliases: "Spurs" is not Tottenham.
        assertNull(matchTimed("Spurs vs Villa", "2026-11-16T19:45:00Z", listOf(clubGame("Tottenham Hotspur", "Aston Villa"))))
    }

    @Test
    fun `ampersand, punctuation and small raised letters are folded`() {
        val game = clubGame("Brighton & Hove Albion", "St. Louis City SC")
        assertEquals("club", matchTimed("Brighton and Hove Albion vs St Louis City", "2026-11-16T19:45:00Z", listOf(game)))
        assertEquals("club", matchTimed("Brighton & Hove Albion ᴴᴰ vs St. Louis City ᴸᴵⱽᴱ", "2026-11-16T19:45:00Z", listOf(game)))
    }

    @Test
    fun `UEFA Youth League games are under-19`() {
        val youth = clubGame("Real Madrid", "Bayern Munich").copy(leagueSlug = "uefa.youth")
        assertEquals("club", matchTimed("Real Madrid U19 vs Bayern Munich U19", "2026-11-16T19:45:00Z", listOf(youth)))
        assertNull(matchTimed("Real Madrid vs Bayern Munich", "2026-11-16T19:45:00Z", listOf(youth)))
    }

    @Test
    fun `reserve sides never take the senior score`() {
        val senior = clubGame("Real Madrid", "Barcelona")
        assertNull(matchTimed("Real Madrid B vs Barcelona B", "2026-11-16T19:45:00Z", listOf(senior)))
        assertNull(matchTimed("Real Madrid Castilla vs Barcelona Atletic", "2026-11-16T19:45:00Z", listOf(senior)))
        assertNull(matchTimed("Castilla vs Barcelona", "2026-11-16T19:45:00Z", listOf(senior)))
    }

    // --- live.20: an addon lists tomorrow's game live (user TV report, Nice / PSG) ---

    private fun ligue1(id: String, start: String, state: ScoreState = ScoreState.PRE) = ScoreSnapshot(
        eventId = id, league = EspnLeague("soccer", "fra.1"), startMs = ms(start), state = state, clockText = null,
        isFinal = state == ScoreState.POST,
        home = ScoreTeam(id = "nice", abbreviation = "NICE", displayName = "Nice", shortDisplayName = "Nice", name = "Nice",
            location = "Nice", logo = null, score = null, linescores = emptyList()),
        away = ScoreTeam(id = "psg", abbreviation = "PSG", displayName = "Paris Saint-Germain", shortDisplayName = "Paris SG",
            name = "Paris Saint-Germain", location = "Paris Saint-Germain", logo = null, score = null, linescores = emptyList())
    )

    private val nicePsgSaturday = ligue1("nice-psg", "2026-11-21T16:15:00Z")
    private val fridayLive = card("Nice vs PSG", "2026-11-20T15:30:00Z")
    private val friday = ms("2026-11-20T15:40:00Z")

    @Test
    fun `the next day's meeting is found for its start but never matched as the card's score`() {
        assertNull(matcher.match(fridayLive, listOf(nicePsgSaturday), friday))
        assertEquals("nice-psg", matcher.laterMeeting(fridayLive, listOf(nicePsgSaturday), friday)?.eventId)
        // A card with no start (a live catalog's) finds it too.
        assertEquals("nice-psg", matcher.laterMeeting(card("PSG vs Nice", null), listOf(nicePsgSaturday), friday)?.eventId)
    }

    @Test
    fun `no later meeting when ESPN has it in play, near, twice, or one side is not strong`() {
        assertNull(matcher.laterMeeting(fridayLive, listOf(ligue1("in", "2026-11-20T15:30:00Z", ScoreState.IN)), friday))
        assertNull(matcher.laterMeeting(fridayLive, listOf(ligue1("near", "2026-11-20T16:05:00Z")), friday))
        assertNull(matcher.laterMeeting(fridayLive, listOf(nicePsgSaturday, ligue1("cup", "2026-11-25T20:00:00Z")), friday))
        assertNull(matcher.laterMeeting(fridayLive, listOf(nicePsgSaturday.copy(timeValid = false)), friday))
        // "Paris" alone is the city, never strong.
        assertNull(matcher.laterMeeting(card("Nice vs Paris", "2026-11-20T15:30:00Z"), listOf(nicePsgSaturday), friday))
        // A women's game never takes a card naming no women's side.
        assertNull(matcher.laterMeeting(fridayLive, listOf(nicePsgSaturday.copy(league = EspnLeagueMap.WOMEN_FEEDS.first())), friday))
    }

    // ---- TV report #5 ----

    private fun espnTeam(id: String, abbr: String, display: String, short: String) =
        ScoreTeam(id = id, abbreviation = abbr, displayName = display, shortDisplayName = short, name = display, location = display,
            logo = null, score = 0, linescores = emptyList())

    private fun soccerGame(id: String, slug: String, competition: String, home: ScoreTeam, away: ScoreTeam, start: String) = ScoreSnapshot(
        eventId = id, league = EspnLeagueMap.SOCCER_ALL, leagueSlug = slug, leagueName = competition, startMs = ms(start),
        state = ScoreState.IN, clockText = "10'", isFinal = false, home = home, away = away
    )

    @Test
    fun `Troyes vs Le Havre matches ESPN's Le Havre AC and Bayern vs Koln its FC Cologne, never Leverkusen`() {
        val ligue1 = soccerGame("t", "fra.1", "French Ligue 1", espnTeam("1", "TRY", "Troyes", "Troyes"),
            espnTeam("2", "HAC", "Le Havre AC", "Le Havre AC"), "2026-11-20T19:45:00Z")
        val bundesliga = soccerGame("b", "ger.1", "German Bundesliga", espnTeam("132", "MUN", "Bayern Munich", "Bayern"),
            espnTeam("122", "KOE", "FC Cologne", "Cologne"), "2026-11-20T19:30:00Z")
        val events = listOf(ligue1, bundesliga)
        assertEquals("t", match("Troyes vs Le Havre", "2026-11-20T19:45:00Z", events, now = "2026-11-20T20:00:00Z"))
        assertEquals("t", match("TRY vs Le Havre", "2026-11-20T19:45:00Z", events, now = "2026-11-20T20:00:00Z"))
        for (title in listOf("Bayern München vs 1. FC Köln", "FC Bayern vs Koln", "Bayern Munich vs FC Koeln")) {
            assertEquals(title, "b", match(title, "2026-11-20T19:30:00Z", events, now = "2026-11-20T20:00:00Z"))
        }
        // Leverkusen is not Bayern: no score.
        assertNull(match("Bayer Leverkusen vs Köln", "2026-11-20T19:30:00Z", events, now = "2026-11-20T20:00:00Z"))
        assertNull(match("Bayer vs Köln", "2026-11-20T19:30:00Z", events, now = "2026-11-20T20:00:00Z"))
    }
    // ---- #204 ----

    @Test
    fun `New Zealand vs India never takes New Zealand U17 at Mali U17 by one side`() {
        val u17 = soccerGame("u17", "fifa.world.u17", "FIFA U-17 World Cup", espnTeam("1", "MLI", "Mali U17", "Mali U17"),
            espnTeam("2", "NZL", "New Zealand U17", "New Zealand U17"), "2026-11-20T13:30:00Z").copy(state = ScoreState.POST, isFinal = true)
        val unknownSlug = u17.copy(eventId = "all", leagueSlug = EspnLeagueMap.SOCCER_ALL.league)
        for (events in listOf(listOf(u17), listOf(unknownSlug))) {
            assertNull(match("New Zealand vs India", null, events, now = "2026-11-20T22:40:00Z"))
            assertNull(match("New Zealand vs India", "2026-11-18T22:00:00Z", events, now = "2026-11-20T22:40:00Z"))
            // Both sides, but the senior sides: still not the U17 game.
            assertNull(match("New Zealand vs Mali", "2026-11-20T13:30:00Z", events, now = "2026-11-20T22:40:00Z"))
        }
        // The U17 card itself does match.
        assertEquals("u17", match("New Zealand U17 vs Mali U17", "2026-11-20T13:30:00Z", listOf(u17), now = "2026-11-20T22:40:00Z"))
    }
}
