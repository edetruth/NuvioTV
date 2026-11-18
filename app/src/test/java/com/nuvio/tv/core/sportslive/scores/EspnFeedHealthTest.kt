package com.nuvio.tv.core.sportslive.scores

import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.LocalDate

class EspnFeedHealthTest {

    private var nowMs = 1_000_000L
    private val health = EspnFeedHealth { nowMs }
    private val nba = EspnFeedKey.scoreboard(EspnLeagueMap.NBA)
    private val nfl = EspnFeedKey.scoreboard(EspnLeagueMap.NFL)
    private val day = LocalDate.of(2026, 11, 15)

    private fun feed(key: EspnFeedKey) = health.feeds.value.getValue(key)

    // --- Tracker state ---

    @Test
    fun `a failure run counts up, keeps when it began, and a success clears it`() {
        health.recordSuccess(nba, day)
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
            assertEquals(day, lastDate)
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
    fun `individual sports and summaries are their own families`() {
        assertEquals(EspnFeedFamily.INDIVIDUAL, EspnFeedKey.scoreboard(EspnLeagueMap.ATP).family)
        assertEquals(EspnFeedFamily.SCOREBOARD, nba.family)
        assertEquals(EspnFeedFamily.SUMMARY, EspnFeedKey.SUMMARY.family)
    }

    @Test
    fun `exceptions are classified`() {
        assertEquals(FeedFailureKind.TIMEOUT, FeedFailureKind.of(SocketTimeoutException()))
        assertEquals(FeedFailureKind.NETWORK, FeedFailureKind.of(UnknownHostException()))
        assertEquals(FeedFailureKind.FORMAT, FeedFailureKind.of(com.squareup.moshi.JsonEncodingException("bad")))
        assertEquals(FeedFailureKind.FORMAT, FeedFailureKind.of(com.squareup.moshi.JsonDataException("bad")))
    }

    @Test
    fun `scoreboards in use are those asked for within 20 minutes, at their last day`() {
        health.recordFailure(nba, FeedFailureKind.NETWORK, date = day)
        nowMs += 21 * 60_000L
        health.recordSuccess(nfl, day.plusDays(1))
        health.recordFailure(EspnFeedKey.SUMMARY, FeedFailureKind.NETWORK)
        assertEquals(listOf(ScoreFeed(EspnLeagueMap.NFL, day.plusDays(1))), health.scoreboardsInUse(nowMs))
    }

    // --- Status ---

    private fun status(mode: ScoresMode = ScoresMode.SHOW) = ScoresStatus.of(mode, health.feeds.value.values, nowMs)

    @Test
    fun `status for each state`() {
        assertEquals(ScoresStatus.NotChecked, status())
        health.recordSuccess(nba, day)
        health.recordSuccess(nfl, day)
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
        health.recordSuccess(nfl, day)
        assertFalse(notice.check(nowMs, ScoresMode.SHOW, inUse = true))
    }

    // --- Format change ---

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
    fun `a scoreboard whose events all lost their competitors is a format change, not an empty day`() = runTest {
        val json = EspnFixtures.json(EspnFixtures.NBA_20261115).replace("\"competitors\"", "\"competitorsRenamed\"")
        val result = provider(200, json).scoreboard(EspnLeagueMap.NBA, day)
        assertTrue(result.failed)
        with(feed(nba)) {
            assertEquals(FeedFailureKind.FORMAT, lastFailure)
            assertEquals(1, consecutiveFailures)
            assertEquals(day, lastDate)
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
