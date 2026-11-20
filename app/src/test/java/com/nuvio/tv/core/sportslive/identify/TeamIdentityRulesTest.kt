package com.nuvio.tv.core.sportslive.identify

import com.nuvio.tv.core.sportslive.SportsLiveSportKind
import com.nuvio.tv.core.sportslive.identify.TeamLookupFixtures.SDB_BC_DUBAI
import com.nuvio.tv.core.sportslive.identify.TeamLookupFixtures.SDB_DUBAI_BASKETBALL
import com.nuvio.tv.core.sportslive.identify.TeamLookupFixtures.SDB_MACCABI_TEL_AVIV
import com.nuvio.tv.core.sportslive.identify.TeamLookupFixtures.SDB_MACCABI_TEL_AVIV_BC
import com.nuvio.tv.core.sportslive.identify.TeamLookupFixtures.SDB_RAPID_BUCURESTI
import com.nuvio.tv.core.sportslive.identify.TeamLookupFixtures.ESPN_MACCABI_TEL_AVIV
import com.nuvio.tv.core.sportslive.identify.TeamLookupFixtures.espn
import com.nuvio.tv.core.sportslive.identify.TeamLookupFixtures.sportsDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #168: a game is identified only when both sides share one sport and a league in it. */
class TeamIdentityRulesTest {

    private val maccabiBasketball = sportsDb(SDB_MACCABI_TEL_AVIV_BC)
    private val maccabiSoccer = espn(ESPN_MACCABI_TEL_AVIV)
    private val dubai = sportsDb(SDB_DUBAI_BASKETBALL)

    @Test
    fun `both sides agreeing on a sport and a league give the game's identity`() {
        val identity = TeamIdentityRules.agree(maccabiSoccer + maccabiBasketball, dubai)!!
        assertEquals("basketball", identity.sport)
        assertEquals("EuroLeague Basketball", identity.league)
        assertEquals(
            "https://r2.thesportsdb.com/images/media/team/badge/z0mk1l1789281457.png" to
                "https://r2.thesportsdb.com/images/media/team/badge/fgtnti1758215967.png",
            identity.badges
        )
        // Either order: the same league, badges in the asked order.
        val reversed = TeamIdentityRules.agree(dubai, maccabiBasketball)!!
        assertEquals("EuroLeague Basketball", reversed.league)
        assertEquals(identity.badges.second, reversed.badges.first)
    }

    @Test
    fun `several shared leagues pick the first side's first listed one`() {
        val a = listOf(TeamCandidate("A", emptyList(), "soccer", listOf("Cup", "League One"), null))
        val b = listOf(TeamCandidate("B", emptyList(), "soccer", listOf("League One", "Cup"), null))
        assertEquals("Cup", TeamIdentityRules.agree(a, b)!!.league)
        assertEquals("League One", TeamIdentityRules.agree(b, a)!!.league)
    }

    @Test
    fun `one side alone, two shared sports or no shared league give no identity`() {
        // One side unknown.
        assertNull(TeamIdentityRules.agree(dubai, emptyList()))
        assertNull(TeamIdentityRules.agree(emptyList(), dubai))
        // Soccer against basketball: nothing shared.
        assertNull(TeamIdentityRules.agree(maccabiSoccer, dubai))
        // Two shared sports: ambiguous.
        val both = maccabiSoccer + maccabiBasketball
        val otherClub = listOf(
            TeamCandidate("Club", emptyList(), "soccer", listOf("Israeli Premier League"), null),
            TeamCandidate("Club BC", emptyList(), "basketball", listOf("EuroLeague Basketball"), null)
        )
        assertNull(TeamIdentityRules.agree(both, otherClub))
        // One shared sport, no shared league: Dubai City and Maccabi's soccer club.
        assertNull(TeamIdentityRules.agree(maccabiSoccer, sportsDb(SDB_BC_DUBAI)))
    }

    @Test
    fun `sport names become ESPN-style keys and card words`() {
        assertEquals("soccer", TeamIdentityRules.sportKey("Soccer"))
        assertEquals("basketball", TeamIdentityRules.sportKey("Basketball"))
        assertEquals("football", TeamIdentityRules.sportKey("American Football"))
        assertEquals("hockey", TeamIdentityRules.sportKey("Ice Hockey"))
        assertEquals("volleyball", TeamIdentityRules.sportKey("Volleyball"))
        assertEquals("soccer", TeamIdentityRules.sportKey("soccer"))
        // Each card word reads back as its sport.
        assertEquals(SportsLiveSportKind.BASKETBALL, SportsLiveSportKind.of(TeamIdentityRules.sportWord("basketball")))
        assertEquals(SportsLiveSportKind.SOCCER, SportsLiveSportKind.of(TeamIdentityRules.sportWord("soccer")))
        assertEquals(SportsLiveSportKind.HOCKEY, SportsLiveSportKind.of(TeamIdentityRules.sportWord("hockey")))
        assertEquals(SportsLiveSportKind.FOOTBALL, SportsLiveSportKind.of(TeamIdentityRules.sportWord("football")))
        assertEquals(SportsLiveSportKind.BASEBALL, SportsLiveSportKind.of(TeamIdentityRules.sportWord("baseball")))
        assertEquals(SportsLiveSportKind.RUGBY, SportsLiveSportKind.of(TeamIdentityRules.sportWord("rugby")))
        assertEquals(SportsLiveSportKind.CRICKET, SportsLiveSportKind.of(TeamIdentityRules.sportWord("cricket")))
        assertEquals("Volleyball", TeamIdentityRules.sportWord("volleyball"))
    }

    @Test
    fun `name keys fold case, accents and punctuation`() {
        assertEquals("maccabi tel aviv", TeamIdentityRules.nameKey("Maccabi Tel-Aviv"))
        assertEquals("maccabi tel aviv", TeamIdentityRules.nameKey("  MACCABI   tel aviv "))
        assertEquals("rapid bucuresti", TeamIdentityRules.nameKey("Rapid București"))
        assertEquals("", TeamIdentityRules.nameKey(" - "))
    }

    @Test
    fun `a source's loose match does not answer to the name`() {
        val dubaiCity = sportsDb(SDB_BC_DUBAI).single()
        assertFalse(TeamIdentityRules.answersTo(dubaiCity, TeamIdentityRules.nameKey("BC Dubai")))
        // An alternate name answers, and so does a fuller name.
        assertTrue(TeamIdentityRules.answersTo(dubai.single(), TeamIdentityRules.nameKey("BC Dubai")))
        assertTrue(TeamIdentityRules.answersTo(maccabiSoccer.single(), TeamIdentityRules.nameKey("Maccabi Tel Aviv")))
        assertTrue(TeamIdentityRules.answersTo(maccabiBasketball.single(), TeamIdentityRules.nameKey("Maccabi Tel Aviv")))
        assertTrue(TeamIdentityRules.answersTo(sportsDb(SDB_RAPID_BUCURESTI).single(), TeamIdentityRules.nameKey("Rapid Bucuresti")))
    }

    @Test
    fun `a sport worth a hinted look comes from a name's own words or a soccer-only side`() {
        assertEquals("basketball", TeamIdentityRules.markedSport("BC Dubai"))
        assertEquals("basketball", TeamIdentityRules.markedSport("Dubai Basketball"))
        assertNull(TeamIdentityRules.markedSport("Maccabi Tel Aviv"))
        assertEquals("Dubai", TeamIdentityRules.withoutMarkers("BC Dubai"))
        assertEquals("basketball", TeamIdentityRules.hintSport("Maccabi Tel Aviv", maccabiSoccer, "BC Dubai", emptyList()))
        // Basketball alone against soccer alone: the soccer side may also have a basketball team.
        assertEquals("basketball", TeamIdentityRules.hintSport("Maccabi Tel Aviv", maccabiSoccer, "Dubai", dubai))
        // Soccer against soccer, or a sport with no hinted words: nothing to ask.
        assertNull(TeamIdentityRules.hintSport("Maccabi Tel Aviv", maccabiSoccer, "Dubai City", sportsDb(SDB_BC_DUBAI)))
        assertNull(TeamIdentityRules.hintSport("Rapid", sportsDb(SDB_RAPID_BUCURESTI), "Maccabi", sportsDb(SDB_MACCABI_TEL_AVIV)))
    }
}
