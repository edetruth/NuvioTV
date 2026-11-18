package com.nuvio.tv.core.sportslive.scores

import kotlinx.coroutines.test.runTest
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate

class EspnFeedHealthTest {

    /** 3 PM US Eastern on 2026-11-15: ESPN's today is [day]. */
    private var nowMs = Instant.parse("2026-11-15T20:00:00Z").toEpochMilli()
    private val health = EspnFeedHealth { nowMs }
    private val day = LocalDate.of(2026, 11, 15)
    private val nba = EspnFeedKey.scoreboard(EspnLeagueMap.NBA, day)
    private val nfl = EspnFeedKey.scoreboard(EspnLeagueMap.NFL, day)

    private fun feed(key: EspnFeedKey) = health.feeds.value.getValue(key)

    // --- Tracker state ---

    @Test
    fun `a failure run counts up, keeps when it began, and a success clears it`() {
        health.recordSuccess(nba)
        val okAt = nowMs
        nowMs += 30_000
        health.recordFailure(nba, FeedFailureKind.HTTP, 503)
        val firstFailure = nowMs
        nowMs += 30_000
        health.recordFailure(nba, FeedFailureKind.TIMEOUT)
        with(feed(nba)) {
            assertEquals(2, consecutiveFailures)
            assertEquals(firstFailure, failingSince)
            assertEquals(nowMs, lastFailureAt)
            assertEquals(FeedFailureKind.TIMEOUT, lastFailure)
            assertNull(lastHttpCode)
            assertEquals(okAt, lastSuccessAt)
            assertTrue(failing)
        }
        nowMs += 30_000
        health.recordSuccess(nba)
        with(feed(nba)) {
            assertEquals(0, consecutiveFailures)
            assertNull(failingSince)
            assertEquals(nowMs, lastSuccessAt)
            assertEquals(FeedFailureKind.TIMEOUT, lastFailure)
            assertFalse(failing)
        }
    }

    @Test
    fun `an HTTP failure keeps its status code`() {
        health.recordFailure(nba, FeedFailureKind.HTTP, 503)
        assertEquals(503, feed(nba).lastHttpCode)
    }

    @Test
    fun `feeds are keyed by league and day, individual sports and summaries in their own families`() {
        assertEquals(EspnFeedFamily.INDIVIDUAL, EspnFeedKey.scoreboard(EspnLeagueMap.ATP, day).family)
        assertEquals(EspnFeedFamily.SCOREBOARD, nba.family)
        assertEquals(EspnFeedFamily.SUMMARY, EspnFeedKey.SUMMARY.family)
        health.recordFailure(nba, FeedFailureKind.NETWORK)
        health.recordSuccess(EspnFeedKey.scoreboard(EspnLeagueMap.NBA, day.minusDays(1)))
        assertTrue(feed(nba).failing)
        assertEquals(2, health.feeds.value.size)
    }

    @Test
    fun `exceptions are classified`() {
        assertEquals(FeedFailureKind.TIMEOUT, FeedFailureKind.of(SocketTimeoutException()))
        assertEquals(FeedFailureKind.NETWORK, FeedFailureKind.of(UnknownHostException()))
        assertEquals(FeedFailureKind.FORMAT, FeedFailureKind.of(com.squareup.moshi.JsonEncodingException("bad")))
        assertEquals(FeedFailureKind.FORMAT, FeedFailureKind.of(com.squareup.moshi.JsonDataException("bad")))
    }

    @Test
    fun `scoreboards in use are live ones asked for within 20 minutes, never a past day`() {
        health.recordFailure(nba, FeedFailureKind.NETWORK)
        nowMs += 21 * 60_000L
        health.recordSuccess(nfl)
        health.recordFailure(EspnFeedKey.scoreboard(EspnLeagueMap.NFL, day.minusDays(1)), FeedFailureKind.NETWORK)
        health.recordFailure(EspnFeedKey.scoreboard(EspnLeagueMap.NFL, day.minusDays(5)), FeedFailureKind.NETWORK)
        health.recordFailure(EspnFeedKey.SUMMARY, FeedFailureKind.NETWORK)
        assertEquals(
            setOf(ScoreFeed(EspnLeagueMap.NFL, day), ScoreFeed(EspnLeagueMap.NFL, day.minusDays(1))),
            health.scoreboardsInUse(nowMs).toSet()
        )
    }

    // --- Status ---

    private fun status(mode: ScoresMode = ScoresMode.SHOW) = ScoresStatus.of(mode, health.feeds.value.values, nowMs)

    @Test
    fun `status for each state`() {
        assertEquals(ScoresStatus.NotChecked, status())
        health.recordSuccess(nba)
        health.recordSuccess(nfl)
        assertEquals(ScoresStatus.Working, status())

        nowMs += 60_000
        val nbaSince = nowMs
        health.recordFailure(nba, FeedFailureKind.FORMAT)
        val some = status()
        assertTrue(some is ScoresStatus.SomeFailing)
        assertEquals(nbaSince, (some as ScoresStatus.SomeFailing).sinceMs)
        assertEquals(listOf(nba), some.failing.map { it.key })

        nowMs += 60_000
        val nflSince = nowMs
        health.recordFailure(nfl, FeedFailureKind.NETWORK)
        val none = status()
        assertTrue(none is ScoresStatus.NotResponding)
        // Not responding since every feed was failing: the last to start.
        assertEquals(nflSince, (none as ScoresStatus.NotResponding).sinceMs)
        assertEquals(2, none.failing.size)

        assertEquals(ScoresStatus.Off, status(ScoresMode.OFF))
    }

    @Test
    fun `a failing feed no longer in use leaves the status`() {
        health.recordFailure(nba, FeedFailureKind.NETWORK)
        nowMs += 21 * 60_000L
        assertEquals(ScoresStatus.Working, status())
    }

    @Test
    fun `a past day never drives the status`() {
        health.recordSuccess(EspnFeedKey.scoreboard(EspnLeagueMap.NBA, day.minusDays(5)))
        assertEquals(ScoresStatus.NotChecked, status())
        health.recordFailure(nba, FeedFailureKind.NETWORK)
        assertTrue(status() is ScoresStatus.NotResponding)
    }

    @Test
    fun `an offline Results day served from the HTTP cache is not recorded, so it never shows Working`() = runTest {
        val dir = Files.createTempDirectory("espn-health-cache").toFile()
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(EspnFixtures.json(EspnFixtures.NBA_20261115)).addHeader("Cache-Control", "max-age=60")
        )
        server.start()
        try {
            val client = OkHttpClient.Builder().cache(Cache(dir, 1024L * 1024)).build()
            val past = LocalDate.of(2026, 11, 1)
            val key = EspnFeedKey.scoreboard(EspnLeagueMap.NBA, past)
            val provider = EspnScoreProvider(
                client, EspnFixtures.moshi, server.url("/").toString(), today = { LocalDate.of(2026, 11, 16) }, health = health
            )
            assertFalse(provider.scoreboard(EspnLeagueMap.NBA, past).failed)
            val fetchedAt = feed(key).lastAttemptAt
            server.shutdown()
            nowMs += 60_000
            // Offline: the stored past day still reads, but proves nothing about ESPN.
            assertFalse(provider.scoreboard(EspnLeagueMap.NBA, past).failed)
            assertEquals(fetchedAt, feed(key).lastAttemptAt)
            assertEquals(ScoresStatus.NotChecked, status())
        } finally {
            runCatching { server.shutdown() }
            dir.deleteRecursively()
        }
    }

    // --- One-time toast ---

    @Test
    fun `the outage toast shows once, after every feed in use has failed for 10 minutes`() {
        val notice = ScoresOutageNotice(health)
        health.recordFailure(nba, FeedFailureKind.NETWORK)
        nowMs += 9 * 60_000L
        health.recordFailure(nba, FeedFailureKind.NETWORK)
        assertFalse(notice.check(nowMs, ScoresMode.SHOW, inUse = true))
        nowMs += 60_000L
        health.recordFailure(nba, FeedFailureKind.NETWORK)
        // Not while no surface is in use, nor while Scores is Off.
        assertFalse(notice.check(nowMs, ScoresMode.SHOW, inUse = false))
        assertFalse(notice.check(nowMs, ScoresMode.OFF, inUse = true))
        assertTrue(notice.check(nowMs, ScoresMode.SHOW, inUse = true))
        assertFalse(notice.check(nowMs + 60_000L, ScoresMode.SHOW, inUse = true))
        assertTrue(notice.shown)
    }

    @Test
    fun `no outage toast while one feed still works`() {
        val notice = ScoresOutageNotice(health)
        health.recordFailure(nba, FeedFailureKind.NETWORK)
        nowMs += 11 * 60_000L
        health.recordFailure(nba, FeedFailureKind.NETWORK)
        health.recordSuccess(nfl)
        assertFalse(notice.check(nowMs, ScoresMode.SHOW, inUse = true))
    }

    // --- Format change ---

    private val adapter = EspnFixtures.moshi.adapter(EspnScoreboardDto::class.java)
    private fun dto(json: String) = adapter.fromJson(json)!!
    private val tbd = EspnFixtures.json(EspnFixtures.NFL_TBD_PLAYOFFS_SAMPLE)

    @Test
    fun `scheduled TBD playoff games without competitors are not a format change`() {
        assertFalse(EspnFeedHealth.formatChanged(dto(tbd)))
    }

    @Test
    fun `a game in progress or final without competitors is a format change`() {
        assertTrue(EspnFeedHealth.formatChanged(dto(tbd.replaceFirst("\"state\": \"pre\"", "\"state\": \"post\""))))
        assertTrue(EspnFeedHealth.formatChanged(dto(tbd.replaceFirst("\"state\": \"pre\"", "\"state\": \"in\""))))
    }

    @Test
    fun `every event malformed is a format change, a day without events is not`() {
        assertTrue(EspnFeedHealth.formatChanged(dto("""{"events":[{"id":"1"},{"id":"2","competitions":[]}]}""")))
        assertFalse(EspnFeedHealth.formatChanged(dto("""{"events":[]}""")))
    }

    private fun provider(code: Int, body: String) = EspnScoreProvider(
        OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fake")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }).build(),
        EspnFixtures.moshi,
        "https://example.invalid/apis/site/v2/sports/",
        today = { LocalDate.of(2026, 11, 16) },
        health = health
    )

    @Test
    fun `finals that lost their competitors are recorded as a format change, not an empty day`() = runTest {
        val json = EspnFixtures.json(EspnFixtures.NBA_20261115).replace("\"competitors\"", "\"competitorsRenamed\"")
        val result = provider(200, json).scoreboard(EspnLeagueMap.NBA, day)
        assertTrue(result.failed)
        with(feed(nba)) {
            assertEquals(FeedFailureKind.FORMAT, lastFailure)
            assertEquals(1, consecutiveFailures)
        }
    }

    @Test
    fun `a day without events is an empty day`() = runTest {
        val result = provider(200, """{"events":[]}""").scoreboard(EspnLeagueMap.NBA, day)
        assertFalse(result.failed)
        assertFalse(feed(nba).failing)
        assertEquals(nowMs, feed(nba).lastSuccessAt)
    }

    @Test
    fun `an unreadable body is a format change and an HTTP error keeps its code`() = runTest {
        assertTrue(provider(200, "<html>").scoreboard(EspnLeagueMap.NBA, day).failed)
        assertEquals(FeedFailureKind.FORMAT, feed(nba).lastFailure)
        assertTrue(provider(502, "").scoreboard(EspnLeagueMap.NFL, day).failed)
        assertEquals(FeedFailureKind.HTTP, feed(nfl).lastFailure)
        assertEquals(502, feed(nfl).lastHttpCode)
    }

    @Test
    fun `a summary failure is recorded on the summary feed`() = runTest {
        assertNull(provider(500, "").summary(EspnLeagueMap.NBA, "1"))
        assertEquals(FeedFailureKind.HTTP, feed(EspnFeedKey.SUMMARY).lastFailure)
    }
}
