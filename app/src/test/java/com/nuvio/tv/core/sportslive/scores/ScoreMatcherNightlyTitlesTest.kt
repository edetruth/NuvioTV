package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.core.sportslive.SportsLiveTeams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/**
 * Nightly accuracy run 2026-11-23_2144 (#192): two addon titles of one finished NBA game (ESPN 401910040, Brooklyn Nets at
 * Minnesota Timberwolves, final) matched only one side STRONG, so the strict match that moves a final to Just ended failed
 * and the card stayed LIVE. Fixture: that scoreboard trimmed to the game.
 */
class ScoreMatcherNightlyTitlesTest {

    private val events = EspnFixtures.events("espn_nba_min_bkn_20261123.json", EspnLeagueMap.NBA)
    private val now = Instant.parse("2026-11-24T03:44:29.335Z").toEpochMilli()
    private val start = Instant.parse("2026-11-24T00:30:00Z").toEpochMilli()

    private fun card(title: String) = ScoreCard(title, SportsLiveTeams.parse(title)!!, start, hasTimeOfDay = true)

    private fun match(title: String) = ScoreMatcher(zone = { ZoneOffset.UTC }).match(card(title), events, now)?.eventId

    private fun strict(title: String) =
        ScoreMatcher(zone = { ZoneOffset.UTC }, minScore = ScoreMatcher.BOTH_STRONG).match(card(title), events, now)?.eventId

    @Test
    fun `nightly 2026-11-23 a Sport and League heading before the first team is no part of its name`() {
        val title = "LIVE: Košarka: NBA: Minnesota Timberwolves vs Brooklyn Nets"
        assertEquals("401910040", match(title))
        assertEquals("401910040", strict(title))
        val teams = SportsLiveTeams.parse(title)!!
        assertEquals(true, ScoreMatcher.firstIsHome(teams.home, teams.away, events.single()))
    }

    @Test
    fun `nightly 2026-11-23 one stray inner word in a team name still matches it strongly`() {
        val title = "LIVE: Brooklyn Nets vs Minnesota Lynx Timberwolves"
        assertEquals("401910040", match(title))
        assertEquals("401910040", strict(title))
        assertEquals(false, ScoreMatcher.firstIsHome("Brooklyn Nets", "Minnesota Lynx Timberwolves", events.single()))
    }

    @Test
    fun `two teams sharing a word still never match`() {
        // The WNBA's Minnesota Lynx share only the city with the Timberwolves.
        assertNull(match("Brooklyn Nets vs Minnesota Lynx"))
        assertNull(match("LIVE: Košarka: NBA: Minnesota Lynx vs Brooklyn Nets"))
        // A heading with a bare city is still the city alone: never STRONG.
        assertNull(strict("Košarka: NBA: Minnesota vs Brooklyn Nets"))
        val lynx = ScoreTeam(
            id = "8", abbreviation = "MIN", displayName = "Minnesota Lynx", shortDisplayName = "Lynx", name = "Lynx",
            location = "Minnesota", logo = null, score = null, linescores = emptyList()
        )
        assertFalse(ScoreMatcher.isStrongSide("Minnesota Lynx Timberwolves", lynx, "wnba"))
        assertTrue(ScoreMatcher.isStrongSide("Minnesota Lynx", lynx, "wnba"))
    }

    @Test
    fun `a reserve or youth side told apart by its first or last word never loses it`() {
        val realMadrid = ScoreTeam(
            id = "86", abbreviation = "RMA", displayName = "Real Madrid", shortDisplayName = "Real Madrid", name = "Real Madrid",
            location = null, logo = null, score = null, linescores = emptyList()
        )
        val bayern = ScoreTeam(
            id = "132", abbreviation = "BAY", displayName = "Bayern Munich", shortDisplayName = "Bayern", name = "Bayern Munich",
            location = null, logo = null, score = null, linescores = emptyList()
        )
        assertFalse(ScoreMatcher.isStrongSide("Real Madrid Castilla", realMadrid, "esp.1"))
        assertFalse(ScoreMatcher.isStrongSide("Bayern Munich II", bayern, "ger.1"))
        assertFalse(ScoreMatcher.isStrongSide("Jong Bayern Munich", bayern, "ger.1"))
    }

    @Test
    fun `heading and inner word helpers`() {
        assertEquals("Minnesota Timberwolves", ScoreMatcher.withoutHeading("Kosarka: NBA: Minnesota Timberwolves"))
        assertEquals("Minnesota Timberwolves", ScoreMatcher.withoutHeading("Minnesota Timberwolves"))
        // A clock is no heading, and nothing after the last one keeps the name.
        assertEquals("19:30 Lakers", ScoreMatcher.withoutHeading("19:30 Lakers"))
        assertEquals("Lakers: ", ScoreMatcher.withoutHeading("Lakers: "))
        assertEquals(setOf("minnesota timberwolves"), ScoreMatcher.innerWordDropped("minnesota lynx timberwolves"))
        assertEquals(setOf("new red bulls", "new york bulls"), ScoreMatcher.innerWordDropped("new york red bulls"))
        assertTrue(ScoreMatcher.innerWordDropped("minnesota timberwolves").isEmpty())
    }
}
