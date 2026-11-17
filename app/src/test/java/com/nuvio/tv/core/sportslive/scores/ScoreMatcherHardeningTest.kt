package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.core.sportslive.SportsLiveTeams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/** Women's slugs, youth forms, curly apostrophes, club names and punctuation (S1 follow-up + S1b). */
class ScoreMatcherHardeningTest {

    private val soccerAll = EspnFixtures.events(EspnFixtures.SOCCER_ALL_20261116, EspnLeagueMap.SOCCER_ALL)
    private val germany = soccerAll.first { it.eventId == "401861185" } // Germany 5-0 Netherlands, 19:45Z
    private val englandU21 = soccerAll.first { it.eventId == "401930175" } // England U21 v France U21, 19:30Z

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun match(title: String, start: String, events: List<ScoreSnapshot>): String? =
        ScoreMatcher(zone = { ZoneOffset.UTC })
            .match(ScoreCard("k", SportsLiveTeams.parse(title)!!, ms(start), hasTimeOfDay = true), events, 0L)?.eventId

    @Test
    fun `a men's card never matches a game in a women's league`() {
        val women = listOf(
            "eng.w.1", "esp.w.1", "can.w.nsl", "usa.w.usl.1", "usa.nwsl", "uefa.wchampions", "fifa.wwc",
            "uefa.weuro", "uefa.wnations", "fifa.wworld.u17", "fifa.wworldq.uefa", "esp.copa_de_la_reina"
        )
        for (slug in women) {
            assertNull(slug, match("Germany vs Netherlands", "2026-11-16T19:45:00Z", listOf(germany.copy(leagueSlug = slug))))
        }
    }

    @Test
    fun `men's world cup, qualifiers and Wales' league are not women's`() {
        for (slug in listOf("fifa.world", "fifa.worldq.uefa", "fifa.worldq.conmebol", "wal.1", "uefa.nations", "fifa.friendly")) {
            assertEquals(slug, "401861185", match("Germany vs Netherlands", "2026-11-16T19:45:00Z", listOf(germany.copy(leagueSlug = slug))))
        }
    }

    @Test
    fun `youth forms are one qualifier`() {
        for (title in listOf("England U-21 vs France U-21", "England Under 21 vs France Under-21", "England U21s vs France (U21)")) {
            assertEquals(title, "401930175", match(title, "2026-11-16T19:30:00Z", soccerAll))
        }
        // Senior and youth sides stay apart.
        assertNull(match("England Under-21 vs France", "2026-11-16T19:30:00Z", listOf(englandU21)))
        assertEquals(listOf("401861185"), listOf(match("Germany vs Netherlands", "2026-11-16T19:45:00Z", soccerAll)))
    }

    @Test
    fun `a curly apostrophe still marks a women's side`() {
        assertNull(match("Germany Women’s vs Netherlands Women’s", "2026-11-16T19:45:00Z", listOf(germany)))
        assertNull(match("Germany Women's vs Netherlands Women's", "2026-11-16T19:45:00Z", listOf(germany)))
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
        assertEquals("club", match("Milan vs Juventus", "2026-11-16T19:45:00Z", listOf(milan)))
        assertNull(match("Inter Milan vs Juventus", "2026-11-16T19:45:00Z", listOf(milan)))
        assertEquals("club", match("Inter Milan vs Juventus", "2026-11-16T19:45:00Z", listOf(clubGame("Internazionale", "Juventus"))))
        assertEquals("club", match("Man Utd vs PSG", "2026-11-16T19:45:00Z", listOf(clubGame("Manchester United", "Paris Saint-Germain"))))
        assertEquals("club", match("VfB Stuttgart vs Bayern", "2026-11-16T19:45:00Z", listOf(clubGame("Stuttgart", "Bayern Munich"))))
        // Ambiguous nicknames are not aliases: "Spurs" is not Tottenham.
        assertNull(match("Spurs vs Villa", "2026-11-16T19:45:00Z", listOf(clubGame("Tottenham Hotspur", "Aston Villa"))))
    }

    @Test
    fun `ampersand, punctuation and small raised letters are folded`() {
        val game = clubGame("Brighton & Hove Albion", "St. Louis City SC")
        assertEquals("club", match("Brighton and Hove Albion vs St Louis City", "2026-11-16T19:45:00Z", listOf(game)))
        assertEquals("club", match("Brighton & Hove Albion ᴴᴰ vs St. Louis City ᴸᴵⱽᴱ", "2026-11-16T19:45:00Z", listOf(game)))
    }
}
