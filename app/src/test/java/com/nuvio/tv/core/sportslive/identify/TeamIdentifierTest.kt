package com.nuvio.tv.core.sportslive.identify

import com.nuvio.tv.core.sportslive.identify.TeamLookupFixtures.game
import com.nuvio.tv.core.sportslive.identify.TeamLookupFixtures.moshi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.ZoneOffset

/** #168: unknown games are looked up in the background, both sides, one request at a time. */
@OptIn(ExperimentalCoroutinesApi::class)
class TeamIdentifierTest {

    private val espn = TeamLookupFixtures.espnSource()
    private val sportsDb = TeamLookupFixtures.sportsDbSource()
    private val book = TeamIdentityBook()

    private fun TestScope.identifier(store: TeamIdentityStore? = null) = TeamIdentifier(
        espn = espn,
        sportsDb = sportsDb,
        store = store,
        book = book,
        now = { testScheduler.currentTime },
        dispatcher = StandardTestDispatcher(testScheduler),
        zone = { ZoneOffset.UTC }
    )

    @Test
    fun `Maccabi Tel Aviv vs BC Dubai is EuroLeague basketball`() = runTest {
        val identifier = identifier()
        identifier.offer(listOf(game("Maccabi Tel Aviv vs BC Dubai")))
        advanceUntilIdle()
        val identity = book.identityOf("Maccabi Tel Aviv", "BC Dubai")!!
        assertEquals("basketball", identity.sport)
        assertEquals("EuroLeague Basketball", identity.league)
        assertEquals(
            "https://r2.thesportsdb.com/images/media/team/badge/z0mk1l1789281457.png" to
                "https://r2.thesportsdb.com/images/media/team/badge/fgtnti1758215967.png",
            identity.badges
        )
        assertEquals(1, book.revision.value)
        // ESPN first; TheSportsDB for the name ESPN has no team for, then the basketball looks.
        assertEquals(listOf("Maccabi Tel Aviv", "BC Dubai"), espn.asked)
        assertEquals(listOf("BC Dubai", "Maccabi Tel Aviv BC", "Dubai BC", "Dubai Basketball"), sportsDb.asked)
        // Spaced two seconds apart: six requests.
        assertTrue(testScheduler.currentTime >= 5 * TeamLookupRateLimiter.SPACING_MS)
    }

    @Test
    fun `Maccabi Tel Aviv vs Dubai Basketball agrees after one hinted look`() = runTest {
        val identifier = identifier()
        // Its title names basketball, so the list never offers it; asked directly here.
        identifier.offerGame("Maccabi Tel Aviv", "Dubai Basketball")
        identifier.wakeWorker()
        advanceUntilIdle()
        assertEquals("EuroLeague Basketball", book.identityOf("Dubai Basketball", "Maccabi Tel Aviv")!!.league)
        assertEquals(listOf("Dubai Basketball", "Maccabi Tel Aviv BC"), sportsDb.asked)
    }

    @Test
    fun `names already known are not asked again`() = runTest {
        val identifier = identifier()
        identifier.offer(listOf(game("Maccabi Tel Aviv vs BC Dubai")))
        advanceUntilIdle()
        val asked = espn.asked.size + sportsDb.asked.size
        // Another game of a known team, and the same game written the other way round.
        identifier.offer(listOf(game("BC Dubai vs Maccabi Tel Aviv"), game("Maccabi Tel Aviv vs Rapid Bucuresti")))
        advanceUntilIdle()
        assertEquals(listOf("Maccabi Tel Aviv", "BC Dubai", "Rapid Bucuresti"), espn.asked)
        assertEquals(asked + 1, espn.asked.size + sportsDb.asked.size)
        // Soccer both, no shared league: unknown.
        assertNull(book.identityOf("Maccabi Tel Aviv", "Rapid Bucuresti"))
    }

    @Test
    fun `a failed request is not cached as unknown`() = runTest {
        val identifier = identifier()
        sportsDb.failNext = 2
        identifier.offer(listOf(game("Maccabi Tel Aviv vs BC Dubai")))
        advanceUntilIdle()
        // BC Dubai's TheSportsDB look failed twice: the game is not answered and waits for the next offer.
        assertFalse(book.has("Maccabi Tel Aviv", "BC Dubai"))
        assertEquals(listOf("BC Dubai", "BC Dubai"), sportsDb.asked)
        identifier.offer(listOf(game("Maccabi Tel Aviv vs BC Dubai")))
        advanceUntilIdle()
        assertEquals("EuroLeague Basketball", book.identityOf("Maccabi Tel Aviv", "BC Dubai")!!.league)
        // ESPN's own answer (no team) was not asked again.
        assertEquals(listOf("Maccabi Tel Aviv", "BC Dubai"), espn.asked)
    }

    @Test
    fun `a too many requests answer stops the lookups until the pause is over`() = runTest {
        val identifier = identifier()
        espn.rateLimitNext = true
        identifier.offer(listOf(game("Maccabi Tel Aviv vs BC Dubai")))
        advanceUntilIdle()
        assertEquals(1, espn.asked.size)
        // Offers during the pause ask nothing.
        identifier.offer(listOf(game("Maccabi Tel Aviv vs BC Dubai")))
        advanceUntilIdle()
        assertEquals(1, espn.asked.size)
        testScheduler.advanceTimeBy(TeamLookupRateLimiter.PAUSE_MS)
        identifier.offer(listOf(game("Maccabi Tel Aviv vs BC Dubai")))
        advanceUntilIdle()
        assertEquals("basketball", book.identityOf("Maccabi Tel Aviv", "BC Dubai")!!.sport)
    }

    @Test
    fun `only unknown two-team games that are live or start today are offered`() = runTest {
        val identifier = identifier()
        val day = 24 * 60 * 60_000L
        identifier.offer(
            listOf(
                game("Maccabi Tel Aviv vs BC Dubai", sport = "Basketball"),
                game("Maccabi Tel Aviv vs BC Dubai", isChannel = true),
                game("Maccabi Tel Aviv vs BC Dubai", isLive = false, startMs = 2 * day),
                game("Maccabi Tel Aviv vs BC Dubai", isLive = false, startMs = null),
                game("Maccabi Tel Aviv", isLive = true)
            )
        )
        advanceUntilIdle()
        assertTrue(espn.asked.isEmpty() && sportsDb.asked.isEmpty())
        // Later today.
        identifier.offer(listOf(game("Maccabi Tel Aviv vs BC Dubai", isLive = false, startMs = day - 1)))
        advanceUntilIdle()
        assertEquals("basketball", book.identityOf("Maccabi Tel Aviv", "BC Dubai")!!.sport)
    }

    @Test
    fun `what was learned is kept on the device`() = runTest {
        val dir = Files.createTempDirectory("team_identifier").toFile()
        try {
            val store = TeamIdentityStore(File(dir, "team_identity.json"), moshi)
            identifier(store).offer(listOf(game("Maccabi Tel Aviv vs BC Dubai")))
            advanceUntilIdle()
            val asked = espn.asked.size + sportsDb.asked.size

            // A new start: answered from the file alone.
            val freshBook = TeamIdentityBook()
            val again = TeamIdentifier(
                espn = espn, sportsDb = sportsDb, store = store, book = freshBook,
                now = { testScheduler.currentTime }, dispatcher = StandardTestDispatcher(testScheduler), zone = { ZoneOffset.UTC }
            )
            again.offer(listOf(game("Maccabi Tel Aviv vs BC Dubai")))
            advanceUntilIdle()
            assertEquals("EuroLeague Basketball", freshBook.identityOf("Maccabi Tel Aviv", "BC Dubai")!!.league)
            assertEquals(asked, espn.asked.size + sportsDb.asked.size)
        } finally {
            dir.deleteRecursively()
        }
    }
}
