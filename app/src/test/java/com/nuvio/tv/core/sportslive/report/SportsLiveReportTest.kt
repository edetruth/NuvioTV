package com.nuvio.tv.core.sportslive.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** Report a problem (#172): what a report says, that no secret survives anywhere in it, and its QR summary. */
class SportsLiveReportTest {

    private val host = "private-addon.example.invalid"
    private val token = "s3cr3tT0ken123"
    private val addonName = "Secret Sports Addon"
    private val addonId = "org.example.secretaddon"
    private val email = "viewer@mail.example.invalid"
    private val userId = "5b0c7e2a-1111-4c3d-9e8f-0123456789ab"
    private val secrets = listOf(addonName, addonId, "https://$host/$token/manifest.json", host, email, userId)

    private fun input(
        game: SportsLiveReportInput.Game? = SportsLiveReportInput.Game(
            title = "Celtics vs 76ers",
            teams = "Celtics vs 76ers",
            sport = "basketball",
            league = "NBA",
            live = true,
            sources = 3,
            espn = "401585 in"
        ),
        nearMisses: List<String>? = listOf("[Live now] \"Celtics vs 76ers\" (NBA) ~ \"Boston v Phila\" (no tags): one side alike")
    ) = SportsLiveReportInput(
        number = 12,
        savedAtMs = 1_763_667_725_000L,
        zone = ZoneId.of("UTC"),
        trigger = "Hold Play/Pause",
        appVersion = "1.2.0-beta.10.live.20",
        appBuild = "12345 release",
        device = "onn 4K Pro · Android 12 (API 31)",
        screen = "player/{contentId}",
        focused = "Celtics vs 76ers",
        game = game,
        linkChecks = SportsLiveReportInput.LinkChecks(
            gameMark = "READY",
            gameChecking = true,
            gameFailed = mapOf("didnt start" to 2),
            gamePlayed = 1,
            ready = 4,
            noStream = 2,
            checking = 1
        ),
        scores = "show · delay 30 s · real-time off",
        playbackEvents = listOf(
            "[live] file=index.m3u8 pos=0 reqLen=-1 -> len=512 ms=80",
            "[live] file=$token pos=0 FAILED IOException: GET https://$host/$token/seg.ts?sig=$token failed ms=120"
        ),
        signInLog = listOf("1763667700000 attempt=$userId flow=qr type=exception exception=UnknownHostException: Unable to resolve host \"$host\" for $email"),
        unplaced = listOf("3x | $addonName Replay Channel 7", "1x | Some Show"),
        nearMisses = nearMisses
    )

    private fun assertNoSecret(text: String) {
        for (secret in listOf(host, token, "example.invalid", addonName, "secretaddon", "viewer@", "5b0c7e2a")) {
            assertFalse("$secret in\n$text", text.contains(secret, ignoreCase = true))
        }
    }

    @Test
    fun `the summary names the version, screen, focus, game, link checks and scores`() {
        val text = SportsLiveReport.text(input(), secrets)
        val summary = text.substringBefore(SportsLiveReport.DETAILS).lines().filter { it.isNotBlank() }
        assertEquals(
            listOf(
                "Nuvio Live report #12",
                "Saved: 2025-11-20 19:42:05 +00:00",
                "App: 1.2.0-beta.10.live.20 (12345 release)",
                "Screen: player/{contentId}",
                "Focused: Celtics vs 76ers",
                "Game: Celtics vs 76ers · NBA · basketball · live · 3 sources · ESPN 401585 in",
                "Links: game: ready, checking, 2 failed (didnt start), 1 played · all games: 4 ready, 2 no stream, 1 checking",
                "Scores: show · delay 30 s · real-time off",
                "Device: onn 4K Pro · Android 12 (API 31)",
                "From: Hold Play/Pause"
            ),
            summary
        )
    }

    @Test
    fun `no secret survives anywhere in a report`() {
        val text = SportsLiveReport.text(input(), secrets)
        assertNoSecret(text)
        assertTrue(text, text.contains("[Sign-in log]"))
        assertTrue(text, text.contains("UnknownHostException: Unable to resolve host"))
    }

    @Test
    fun `a playback line keeps a media extension and never the file name`() {
        val text = SportsLiveReport.text(input(), secrets)
        assertTrue(text, text.contains("[live] file=….m3u8 pos=0 reqLen=-1 -> len=512 ms=80"))
        assertTrue(text, text.contains("[live] file=… pos=0 FAILED IOException"))
        assertEquals("file=… x", SportsLiveReport.playbackLine("file=abc123 x"))
        assertEquals("file=….ts", SportsLiveReport.playbackLine("file=seg-7.TS?x=1"))
    }

    @Test
    fun `unplaced titles carry no addon and an addon name inside a title goes`() {
        val text = SportsLiveReport.text(input(), secrets)
        assertTrue(text, text.contains("3x | <private> Replay Channel 7"))
        assertTrue(text, text.contains("1x | Some Show"))
    }

    @Test
    fun `no game and a build without near misses say so`() {
        val text = SportsLiveReport.text(input(game = null, nearMisses = null), secrets)
        assertTrue(text, text.contains("Game: none"))
        assertTrue(text, text.contains("[Merge near misses]\nnot collected in this build"))
    }

    @Test
    fun `an empty section says none`() {
        val text = SportsLiveReport.text(input().copy(playbackEvents = emptyList()), secrets)
        assertTrue(text, text.contains("[Playback events, newest last]\nnone"))
    }

    @Test
    fun `a game line leaves out what it does not know`() {
        val game = SportsLiveReportInput.Game("Some Fight", null, null, null, live = false, sources = 1, espn = null)
        assertEquals("Some Fight · not live · 1 source · no ESPN match", SportsLiveReport.gameLine(game))
    }

    @Test
    fun `no score is ever written`() {
        val text = SportsLiveReport.text(input(), secrets)
        assertFalse(text, Regex("""\d+\s*-\s*\d+\s*(final|ft)""", RegexOption.IGNORE_CASE).containsMatchIn(text))
        assertFalse(text, text.contains("score:", ignoreCase = true))
    }

    @Test
    fun `a title carrying its result never puts the score in the report or its qr code`() {
        val game = SportsLiveReportInput.Game(
            title = "Arsenal 2-1 Chelsea",
            teams = "Arsenal 2 vs 1 Chelsea",
            sport = "soccer",
            league = "Premier League",
            live = false,
            sources = 1,
            espn = null
        )
        val nearMiss = "[Replays] \"Lakers 110 - 102 Celtics\" (NBA) ~ \"Lakers v Celtics\" (no tags): one side alike"
        val text = SportsLiveReport.text(
            input(game = game, nearMisses = listOf(nearMiss)).copy(focused = "Bayern 3:0 Koln", unplaced = listOf("2x | Final 4–3 OT")),
            secrets
        )
        for (score in listOf("2-1", "2 vs 1", "110 - 102", "3:0", "4–3")) {
            assertFalse(text, text.contains(score))
            assertFalse(score, SportsLiveReport.qrPayload(text).contains(score))
        }
        assertTrue(text, text.contains("Game: Arsenal <numbers> Chelsea · Arsenal <numbers> Chelsea · Premier League"))
        assertTrue(text, text.contains("Focused: Bayern <numbers> Koln"))
        assertTrue(text, text.contains("Saved: 2025-11-20 19:42:05 +00:00"))
    }

    // The voice note

    @Test
    fun `a voice note goes under the time and in full in the details, redacted`() {
        val text = SportsLiveReport.withNote(SportsLiveReport.text(input(), secrets), "the $addonName link froze at kick off", secrets)
        val lines = text.lines()
        assertEquals("Note: the <private> link froze at kick off", lines[2])
        assertTrue(text, text.contains("[Voice note]\nthe <private> link froze at kick off\n"))
        assertNoSecret(text)
    }

    @Test
    fun `a second voice note replaces the first`() {
        val once = SportsLiveReport.withNote(SportsLiveReport.text(input(), secrets), "first words", secrets)
        val twice = SportsLiveReport.withNote(once, "second words", secrets)
        assertFalse(twice, twice.contains("first words"))
        assertEquals(1, Regex("Note: ").findAll(twice).count())
        assertEquals(1, Regex("""\[Voice note]""").findAll(twice).count())
        assertTrue(twice, twice.contains("[Playback events, newest last]"))
    }

    @Test
    fun `a long note is cut in the summary and kept whole in the details`() {
        val long = "word ".repeat(80).trim()
        val text = SportsLiveReport.withNote(SportsLiveReport.text(input(), secrets), long, secrets)
        val noteLine = text.lines().first { it.startsWith("Note: ") }
        assertEquals("Note: ".length + SportsLiveReport.NOTE_MAX_CHARS, noteLine.length)
        assertTrue(noteLine.endsWith("…"))
        assertTrue(text, text.contains("\n$long\n"))
    }

    @Test
    fun `a blank note changes nothing`() {
        val text = SportsLiveReport.text(input(), secrets)
        assertEquals(text, SportsLiveReport.withNote(text, "   ", secrets))
    }

    // The QR code

    @Test
    fun `the qr code carries the summary only`() {
        val text = SportsLiveReport.text(input(), secrets)
        val qr = SportsLiveReport.qrPayload(text)
        assertTrue(qr, qr.startsWith("Nuvio Live report #12\nSaved: "))
        assertTrue(qr, qr.endsWith("From: Hold Play/Pause"))
        assertFalse(qr, qr.contains(SportsLiveReport.DETAILS))
        assertFalse(qr, qr.contains("[Playback events"))
        assertNoSecret(qr)
    }

    @Test
    fun `the qr code never passes its byte limit and cuts the last line with an ellipsis`() {
        val long = "é".repeat(400)
        val text = SportsLiveReport.withNote(SportsLiveReport.text(input(), secrets), long, secrets)
        val qr = SportsLiveReport.qrPayload(text)
        assertTrue("${qr.toByteArray().size}", qr.toByteArray(Charsets.UTF_8).size <= SportsLiveReport.QR_MAX_BYTES)
        assertTrue(qr, qr.contains("Note: é"))
        val small = SportsLiveReport.qrPayload(text, maxBytes = 40)
        assertTrue("${small.toByteArray().size}", small.toByteArray(Charsets.UTF_8).size <= 40)
        assertTrue(small, small.startsWith("Nuvio Live report #12\n"))
        assertTrue(small, small.endsWith("…"))
    }

    @Test
    fun `a cut never splits a character`() {
        val text = "Nuvio Live report #1\nNote: " + "😀".repeat(50) + "\n" + SportsLiveReport.DETAILS + "\n"
        for (max in 20..60) {
            val qr = SportsLiveReport.qrPayload(text, maxBytes = max)
            assertTrue("$max", qr.toByteArray(Charsets.UTF_8).size <= max)
            assertFalse("$max", qr.contains('�'))
            assertTrue("$max", qr.none { Character.isSurrogate(it) } || qr.zipWithNext().all { (a, b) -> !Character.isHighSurrogate(a) || Character.isLowSurrogate(b) })
            assertFalse("$max", Character.isHighSurrogate(qr.last()))
        }
    }

    @Test
    fun `score feed data errors are counted in the summary and listed in the details`() {
        val errors = listOf("scoreboard soccer/ger.1 2026-11-20 · 2x · 3 min ago · 1 entry skipped, first event 401884736 (NullPointerException)")
        val text = SportsLiveReport.text(input().copy(feedDataErrors = errors), secrets)
        assertTrue(text, text.contains("Scores: show · delay 30 s · real-time off · 1 feed data errors"))
        assertTrue(text, text.contains("[Score feed data errors, newest first]\n" + errors.single()))
        assertTrue(SportsLiveReport.text(input(), secrets).contains("[Score feed data errors, newest first]\nnone"))
    }
}
