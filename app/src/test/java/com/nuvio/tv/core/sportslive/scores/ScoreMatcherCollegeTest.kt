package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.core.sportslive.SportsLiveTeams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/**
 * N9: college cards that add "University", "College" or write "State" differently from ESPN. The ESPN
 * names below were checked against ESPN's men's college basketball teams list (2026-11-18).
 */
class ScoreMatcherCollegeTest {

    private val template = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA).first()
    private val matcher = ScoreMatcher(zone = { ZoneOffset.UTC })

    private fun team(abbr: String, display: String, short: String, name: String, location: String) =
        template.home.copy(abbreviation = abbr, displayName = display, shortDisplayName = short, name = name, location = location)

    private val georgetown = team("GTWN", "Georgetown Hoyas", "Georgetown", "Hoyas", "Georgetown")
    private val navy = team("NAVY", "Navy Midshipmen", "Navy", "Midshipmen", "Navy")
    private val michiganState = team("MSU", "Michigan State Spartans", "Michigan St", "Spartans", "Michigan State")
    private val ohio = team("OHIO", "Ohio Bobcats", "Ohio", "Bobcats", "Ohio")
    private val miamiFl = team("MIA", "Miami Hurricanes", "Miami", "Hurricanes", "Miami")
    private val bostonCollege = team("BC", "Boston College Eagles", "Boston College", "Eagles", "Boston College")

    private fun game(id: String, home: ScoreTeam, away: ScoreTeam) =
        template.copy(eventId = id, leagueSlug = "mens-college-basketball", home = home, away = away)

    private fun match(title: String, vararg events: ScoreSnapshot): String? {
        val start = template.startMs!!
        return matcher.match(ScoreCard("$title@$start", SportsLiveTeams.parse(title)!!, start, hasTimeOfDay = true), events.toList(), start)?.eventId
    }

    @Test
    fun `a trailing University or a leading University of is dropped`() {
        val game = game("gtwn", georgetown, navy)
        assertEquals("gtwn", match("Georgetown University vs Navy", game))
        assertEquals("gtwn", match("Navy vs Georgetown University", game))
        assertEquals(true, ScoreMatcher.firstIsHome("Georgetown University", "Navy", game))
        assertEquals("ohio", match("Ohio University vs Navy", game("ohio", ohio, navy)))
        assertEquals("msu", match("University of Michigan State vs Navy", game("msu", michiganState, navy)))
    }

    @Test
    fun `State and St are one name, and State is never dropped`() {
        assertEquals("msu", match("Michigan State University vs Georgetown", game("msu", michiganState, georgetown)))
        assertEquals("msu", match("Michigan St. vs Georgetown", game("msu", michiganState, georgetown)))
        // "Ohio State" is not Ohio.
        assertNull(match("Ohio State vs Navy", game("ohio", ohio, navy)))
        assertNull(match("Ohio State University vs Navy", game("ohio", ohio, navy)))
    }

    @Test
    fun `a school whose bare name is another school keeps its University`() {
        // Miami University is Miami (OH), never the Hurricanes.
        assertNull(match("Miami University vs Navy", game("mia", miamiFl, navy)))
        // A name that ends in College on ESPN too still matches as written.
        assertEquals("bc", match("Boston College vs Navy", game("bc", bostonCollege, navy)))
    }

    @Test
    fun `college alternates`() {
        assertEquals(setOf("georgetown"), ScoreMatcher.collegeAlternates("georgetown university"))
        assertEquals(setOf("michigan"), ScoreMatcher.collegeAlternates("university of michigan"))
        assertEquals(setOf("michigan state", "michigan st"), ScoreMatcher.collegeAlternates("michigan state university"))
        assertEquals(setOf("kansas state"), ScoreMatcher.collegeAlternates("kansas st"))
        assertEquals(setOf("dartmouth"), ScoreMatcher.collegeAlternates("dartmouth college"))
        assertTrue(ScoreMatcher.collegeAlternates("miami university").isEmpty())
        assertTrue(ScoreMatcher.collegeAlternates("college").isEmpty())
        assertTrue(ScoreMatcher.collegeAlternates("navy").isEmpty())
    }

    // Item 4: ESPN's "Central Michigan Chippewas at Pittsburgh Panthers"; its short name is "C Michigan".
    private val pitt = team("PITT", "Pittsburgh Panthers", "Pittsburgh", "Panthers", "Pittsburgh")
    private val cmu = team("CMU", "Central Michigan Chippewas", "C Michigan", "Chippewas", "Central Michigan")

    private fun college(id: String, league: EspnLeague, offsetMs: Long = 0L) =
        template.copy(eventId = id, league = league, leagueSlug = league.league, home = pitt, away = cmu, startMs = template.startMs!! + offsetMs)

    @Test
    fun `bare school names match a college game both sides strong`() {
        val strict = ScoreMatcher(zone = { ZoneOffset.UTC }, minScore = ScoreMatcher.BOTH_STRONG)
        val start = template.startMs!!
        val card = ScoreCard("pitt-cmu", SportsLiveTeams.parse("Pittsburgh vs Central Michigan")!!, start, hasTimeOfDay = true)
        assertEquals("m", strict.match(card, listOf(college("m", EspnLeagueMap.COLLEGE_BASKETBALL)), start)?.eventId)
        assertEquals(true, ScoreMatcher.firstIsHome("Pittsburgh", "Central Michigan", college("m", EspnLeagueMap.COLLEGE_BASKETBALL)))
        // On a pro feed a place alone stays weak.
        val pro = college("p", EspnLeagueMap.NBA)
        assertNull(strict.match(card.copy(key = "pro"), listOf(pro), start))
    }

    @Test
    fun `the men's and the women's game of two schools at one time give no score`() {
        val start = template.startMs!!
        val card = ScoreCard("pitt-cmu-both", SportsLiveTeams.parse("Pittsburgh vs Central Michigan")!!, start, hasTimeOfDay = true)
        val men = college("m", EspnLeagueMap.COLLEGE_BASKETBALL)
        val women = college("w", EspnLeagueMap.COLLEGE_BASKETBALL_WOMEN)
        assertNull(matcher.match(card, listOf(men, women), start))
        // The scoreboard with the pairing at the card's time decides.
        val earlier = college("w2", EspnLeagueMap.COLLEGE_BASKETBALL_WOMEN, offsetMs = -2 * 60 * 60_000L)
        assertEquals("m", matcher.match(card.copy(key = "pitt-cmu-apart"), listOf(men, earlier), start)?.eventId)
    }
}
