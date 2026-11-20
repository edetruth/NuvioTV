package com.nuvio.tv.core.sportslive.identify

import com.nuvio.tv.core.sportslive.SportsLiveMySportsScope
import com.nuvio.tv.core.sportslive.SportsLiveNations
import com.nuvio.tv.core.sportslive.SportsLiveSportKind
import com.nuvio.tv.core.sportslive.identify.TeamLookupFixtures.game
import com.nuvio.tv.core.sportslive.identify.TeamLookupFixtures.moshi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/**
 * #204: on the TV at 22:40Z on 2026-11-20 Live now tagged "New Zealand vs India" SOCCER with flags, while ESPN had
 * the cricket Test live (series from 2026-11-18) and soccer only "New Zealand U17 at Mali U17" (FT 13:30Z). ESPN's
 * team search answers "India" with its soccer team alone (limit 5), so the two sides agreed on soccer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NationalPairIdentityTest {

    // ESPN's search of 2026-11-20, trimmed: New Zealand in rugby, cricket and soccer; India in soccer alone.
    private val espnNewZealand = """{"results": [{"type": "team", "contents": [
        {"type": "team", "displayName": "New Zealand", "subtitle": "Rugby", "sport": "rugby", "image": {"default": "https://example.invalid/nz-rugby.png"}},
        {"type": "team", "displayName": "New Zealand", "subtitle": "Cricket", "sport": "cricket", "image": {"default": "https://example.invalid/nz-cricket.png"}},
        {"type": "team", "displayName": "New Zealand", "subtitle": "Men's soccer team", "sport": "soccer", "image": {"default": "https://example.invalid/nzl.png"}}]}]}"""
    private val espnIndia = """{"results": [{"type": "team", "contents": [
        {"type": "team", "displayName": "Indiana Pacers", "subtitle": "NBA", "sport": "basketball"},
        {"type": "team", "displayName": "India", "subtitle": "Men's soccer team", "sport": "soccer", "image": {"default": "https://example.invalid/ind.png"}}]}]}"""

    // ESPN's cricket scorepanel at 22:40Z, trimmed: the Test in progress, a finished T10 game, a later women's game.
    private val scorepanel = """{"scores": [
        {"leagues": [{"name": "India tour of New Zealand 2026/27"}], "events": [{"name": "New Zealand v India", "date": "2026-11-18T22:00Z",
          "competitions": [{"status": {"type": {"state": "in"}}, "competitors": [{"team": {"displayName": "New Zealand", "abbreviation": "NZ"}}, {"team": {"displayName": "India", "abbreviation": "IND"}}]}]}]},
        {"leagues": [{"name": "Abu Dhabi T10"}], "events": [{"name": "Yas Lions v UAE Bulls", "date": "2026-11-20T15:45Z",
          "competitions": [{"status": {"type": {"state": "post"}}, "competitors": [{"team": {"displayName": "Yas Lions"}}, {"team": {"displayName": "UAE Bulls"}}]}]}]},
        {"leagues": [{"name": "England tour of Australia 2026/27"}], "events": [{"name": "Australia v England", "date": "2026-11-21T08:15Z",
          "competitions": [{"status": {"type": {"state": "pre"}}, "competitors": [{"team": {"displayName": "Australia"}}, {"team": {"displayName": "England"}}]}]}]}
    ]}"""

    private val start = Instant.parse("2026-11-20T22:40:00Z").toEpochMilli()

    private class FakeCricket(private val body: String?) : CricketLiveSource {
        var asked = 0
        override suspend fun livePairs(): CricketLiveResult {
            asked++
            return body?.let { EspnCricketLive.parse(it, moshi) }?.let { CricketLiveResult.Found(it) } ?: CricketLiveResult.Failed()
        }
    }

    private val espn = TeamLookupFixtures.FakeSource(
        mapOf("New Zealand" to espnNewZealand, "India" to espnIndia), TeamLookupFixtures::espn, fallback = null
    )
    private val sportsDb = TeamLookupFixtures.sportsDbSource()
    private val book = TeamIdentityBook()

    private fun TestScope.identifier(cricket: CricketLiveSource?) = TeamIdentifier(
        espn = espn,
        sportsDb = sportsDb,
        store = null,
        book = book,
        now = { start + testScheduler.currentTime },
        dispatcher = StandardTestDispatcher(testScheduler),
        zone = { ZoneOffset.UTC },
        cricket = cricket
    )

    @Test
    fun `ESPN's search answers alone make New Zealand vs India soccer, the root cause`() {
        val agreed = TeamIdentityRules.agree(TeamLookupFixtures.espn(espnNewZealand), TeamLookupFixtures.espn(espnIndia))
        assertEquals("soccer", agreed!!.sport)
    }

    @Test
    fun `New Zealand vs India live at 22-40Z is cricket from ESPN's scorepanel and never searched as teams`() = runTest {
        identifier(FakeCricket(scorepanel)).offer(listOf(game("New Zealand vs India")))
        advanceUntilIdle()
        assertTrue(espn.asked.isEmpty() && sportsDb.asked.isEmpty())
        assertEquals("cricket", book.identityOf("New Zealand", "India")!!.sport)
        val identified = SportsLiveTeamIdentity.identified(game("New Zealand vs India"), book)
        assertEquals("Cricket", identified.sport)
        assertEquals(SportsLiveSportKind.CRICKET, SportsLiveMySportsScope.kindOf(identified))
        // No flags from a cricket list: the soccer team's badges are not the cricket side's.
        assertNull(SportsLiveTeamIdentity.logosOf("New Zealand", "India", book))
    }

    @Test
    fun `a national pair the scorepanel does not list live stays untagged and the list is asked again only after ten minutes`() = runTest {
        val cricket = FakeCricket(scorepanel)
        val identifier = identifier(cricket)
        // Australia v England is listed for 2026-11-21 08:15Z (pre), UAE vs Bangladesh not at all.
        identifier.offer(listOf(game("Australia vs England"), game("UAE vs Bangladesh")))
        advanceUntilIdle()
        assertNull(book.identityOf("Australia", "England"))
        assertNull(book.identityOf("UAE", "Bangladesh"))
        assertTrue(espn.asked.isEmpty() && sportsDb.asked.isEmpty())
        assertEquals(1, cricket.asked)
        // Offered again on the next rebuild: the list is reused.
        identifier.offer(listOf(game("Australia vs England")))
        advanceUntilIdle()
        assertEquals(1, cricket.asked)
        advanceTimeBy(TeamIdentifier.CRICKET_REFRESH_MS)
        identifier.offer(listOf(game("Australia vs England")))
        advanceUntilIdle()
        assertEquals(2, cricket.asked)
    }

    @Test
    fun `a national pair not live, without a cricket source or with a failed list stays untagged`() = runTest {
        identifier(null).offer(listOf(game("New Zealand vs India")))
        advanceUntilIdle()
        assertFalse(book.has("New Zealand", "India"))
        val failing = FakeCricket(null)
        val identifier = identifier(failing)
        identifier.offer(listOf(game("New Zealand vs India", isLive = false, startMs = start + 60_000L)))
        advanceUntilIdle()
        assertEquals(0, failing.asked)
        identifier.offer(listOf(game("New Zealand vs India")))
        advanceUntilIdle()
        assertEquals(1, failing.asked)
        assertFalse(book.has("New Zealand", "India"))
        assertTrue(espn.asked.isEmpty() && sportsDb.asked.isEmpty())
    }

    @Test
    fun `a soccer identity already in the book never tags an untagged national pair`() {
        val soccer = GameIdentity("soccer", "Men's soccer team", "https://example.invalid/nzl.png" to "https://example.invalid/ind.png")
        val stale = TeamIdentityBook().apply {
            put("New Zealand", "India", soccer)
            put("UAE", "Bangladesh", soccer)
            put("India Women", "New Zealand Women", soccer)
        }
        for (title in listOf("New Zealand vs India", "UAE vs Bangladesh", "India Women vs New Zealand Women")) {
            val item = game(title)
            assertSame(title, item, SportsLiveTeamIdentity.identified(item, stale))
        }
        // A card its addon tags soccer keeps its soccer tag.
        assertEquals("Soccer", SportsLiveTeamIdentity.identified(game("New Zealand vs India", sport = "Soccer"), stale).sport)
    }

    @Test
    fun `the scorepanel keeps only games in progress`() {
        val pairs = EspnCricketLive.parse(scorepanel, moshi)!!
        assertEquals(setOf(TeamIdentityBook.pairKey(SportsLiveNations.key("New Zealand"), SportsLiveNations.key("India"))), pairs)
        assertNull(EspnCricketLive.parse("not json", moshi))
    }

    @Test
    fun `nations with or without a women's or youth note are told from clubs`() {
        for (name in listOf("New Zealand", "India", "NZ", "UAE", "Bangladesh", "England", "West Indies", "India Women", "Mali U17", "New Zealand (W)")) {
            assertTrue(name, SportsLiveNations.isNation(name))
        }
        for (name in listOf("Mumbai Indians", "Auckland", "Bayern Munich", "Sydney Sixers", "India A")) {
            assertFalse(name, SportsLiveNations.isNation(name))
        }
        assertTrue(SportsLiveNations.isNationalPair("New Zealand", "India"))
        assertFalse(SportsLiveNations.isNationalPair("New Zealand", "Mumbai Indians"))
    }
}
