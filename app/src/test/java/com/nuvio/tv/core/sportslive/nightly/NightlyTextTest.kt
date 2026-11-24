package com.nuvio.tv.core.sportslive.nightly

import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoreTeam
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #192: the nightly run's verdicts, redaction and text (no network). */
class NightlyTextTest {
    private val now = Instant.parse("2026-11-21T08:00:00Z").toEpochMilli()
    private val minute = 60_000L

    private fun team(name: String) = ScoreTeam(
        id = name, abbreviation = null, displayName = name, shortDisplayName = null, name = null, location = null,
        logo = null, score = null, linescores = emptyList()
    )

    private fun event(state: ScoreState, startMs: Long?, timeValid: Boolean = true) = ScoreSnapshot(
        eventId = "401", league = EspnLeague("soccer", "fra.1"), startMs = startMs, timeValid = timeValid, state = state,
        clockText = null, isFinal = state == ScoreState.POST, home = team("Nice"), away = team("Paris Saint-Germain")
    )

    @Test
    fun `the app-level line keeps the shape the tripwire reads`() {
        val line = NightlyText.appLiveLine(NightlyText.AppLive(games = 1, channels = 1, otherStreams = 150, corrected = 39, espnKnows = 1, unscored = 0, raw = 343, addons = 5))
        assertTrue(line.startsWith("App-level live: 1 games shown LIVE, ESPN knows 1, unscored 0 ("))
        assertTrue(line.contains("Other streams 150") && line.contains("raw 343 listed live from 5 addons"))
    }

    @Test
    fun `a live listing reads ESPN's state`() {
        assertEquals(Verdict.IN_PLAY, NightlyText.liveVerdict(event(ScoreState.IN, now - 30 * minute), null, now))
        assertEquals(Verdict.FINAL, NightlyText.liveVerdict(event(ScoreState.POST, now - 180 * minute), null, now))
        assertEquals(Verdict.OFF, NightlyText.liveVerdict(event(ScoreState.OFF, now), null, now))
        assertEquals(Verdict.STARTS_SOON, NightlyText.liveVerdict(event(ScoreState.PRE, now + 20 * minute), null, now))
        assertEquals(Verdict.SCHEDULED_LATER, NightlyText.liveVerdict(event(ScoreState.PRE, now + 8 * 60 * minute), null, now))
        assertEquals(Verdict.NOT_FOUND, NightlyText.liveVerdict(event(ScoreState.UNKNOWN, now), null, now))
    }

    @Test
    fun `no match but a later meeting is scheduled later, none is not found`() {
        assertEquals(Verdict.SCHEDULED_LATER, NightlyText.liveVerdict(null, event(ScoreState.PRE, now + 24 * 60 * minute), now))
        assertEquals(Verdict.NOT_FOUND, NightlyText.liveVerdict(null, null, now))
    }

    @Test
    fun `an upcoming listing ESPN has in play or over disagrees`() {
        assertEquals(Verdict.STARTED_EARLY, NightlyText.upcomingVerdict(event(ScoreState.IN, now)))
        assertEquals(Verdict.ENDED_EARLY, NightlyText.upcomingVerdict(event(ScoreState.POST, now)))
        assertEquals(Verdict.UPCOMING_OK, NightlyText.upcomingVerdict(event(ScoreState.PRE, now + 60 * minute)))
        assertEquals(Verdict.NOT_FOUND, NightlyText.upcomingVerdict(null))
        assertTrue(Verdict.STARTED_EARLY.disagreement)
        assertFalse(Verdict.UPCOMING_OK.disagreement)
    }

    @Test
    fun `the app corrects a later start it can date and a final on a strict match`() {
        val later = event(ScoreState.PRE, now + 8 * 60 * minute)
        assertTrue(NightlyText.appCatches(Verdict.SCHEDULED_LATER, later, null, strictSame = false, nowMs = now))
        // ESPN has no kick-off time yet: the app keeps no start, so it does not correct the listing.
        val tbd = event(ScoreState.PRE, now + 8 * 60 * minute, timeValid = false)
        assertFalse(NightlyText.appCatches(Verdict.SCHEDULED_LATER, tbd, null, strictSame = false, nowMs = now))
        assertTrue(NightlyText.appCatches(Verdict.SCHEDULED_LATER, null, later, strictSame = false, nowMs = now))
        assertTrue(NightlyText.appCatches(Verdict.FINAL, event(ScoreState.POST, now), null, strictSame = true, nowMs = now))
        assertFalse(NightlyText.appCatches(Verdict.FINAL, event(ScoreState.POST, now), null, strictSame = false, nowMs = now))
        assertFalse(NightlyText.appCatches(Verdict.OFF, event(ScoreState.OFF, now), null, strictSame = true, nowMs = now))
    }

    @Test
    fun `an addon's label is stable and never shows its link`() {
        val url = "https://addon.example.invalid/abcdefghijklmnop/manifest.json"
        val label = NightlyText.label(url)
        assertEquals(label, NightlyText.label(" $url "))
        assertTrue(label.matches(Regex("addon-[0-9a-f]{6}")))
        assertFalse(label == NightlyText.label("https://other.example.invalid/manifest.json"))
    }

    @Test
    fun `redaction replaces names, hosts and any link`() {
        val secrets = mapOf("Example Sports" to "addon-111111", "addon.example.invalid" to "addon-111111")
        assertEquals(
            "Nice vs PSG | addon-111111 | <link> | addon-111111",
            NightlyText.redact("Nice vs PSG | example sports | https://cdn.example.invalid/x.m3u8?t=1 | addon.example.invalid", secrets)
        )
        // Too short to be a secret: never replaced (it would garble every title).
        assertEquals("Live", NightlyText.redact("Live", mapOf("Liv" to "addon-222222")))
        assertEquals("", NightlyText.redact(null, secrets))
    }

    @Test
    fun `csv quotes commas, quotes and edge spaces`() {
        assertEquals("a,\"b, c\",\"say \"\"hi\"\"\",,\" x\"", NightlyText.csv(listOf("a", "b, c", "say \"hi\"", null, " x")))
        assertEquals("one two", NightlyText.csv(listOf("one\ntwo")))
    }

    @Test
    fun `kotlin literals and test names are safe to paste`() {
        assertEquals("\"a \\\"b\\\" \\$5\\n\"", NightlyText.kotlinString("a \"b\" \$5\n"))
        assertEquals("null", NightlyText.kotlinString(null))
        assertEquals("nightly 2026-11-21 Nice vs PSG scheduled-later", NightlyText.testName("nightly 2026-11-21: Nice vs. PSG [scheduled-later]"))
    }

    @Test
    fun `percent rounds and shows a dash with nothing verified`() {
        assertEquals("67%", NightlyText.percent(2, 3))
        assertEquals("-", NightlyText.percent(0, 0))
    }

    @Test
    fun `unscored live games are counted by league, most first, with hashed addon labels only`() {
        fun row(group: String, label: String, verdict: Verdict) = NightlyText.LiveRow(group, label, NightlyText.isUnscored(verdict))
        val rows = listOf(
            row("soccer/all", "addon-aaaaaa", Verdict.NOT_FOUND),
            row("soccer/all", "addon-aaaaaa", Verdict.NOT_FOUND),
            row("soccer/all", "addon-bbbbbb", Verdict.NOT_FOUND),
            row("soccer/all", "addon-bbbbbb", Verdict.IN_PLAY),
            row("can't place (no sport)", "addon-cccccc", Verdict.UNPLACED),
            row("basketball/nba", "addon-dddddd", Verdict.IN_PLAY),
            row("basketball/nba", "addon-dddddd", Verdict.FINAL)
        )
        assertEquals(
            listOf(
                "| league or reason | unscored | listed live | addons (unscored) |",
                "|---|---|---|---|",
                "| soccer/all | 3 | 4 | addon-aaaaaa ×2, addon-bbbbbb ×1 |",
                "| can't place (no sport) | 1 | 1 | addon-cccccc ×1 |"
            ),
            NightlyText.unscoredByLeague(rows)
        )
        // Every live row scored: no table.
        assertTrue(NightlyText.unscoredByLeague(rows.filter { it.group == "basketball/nba" }).isEmpty())
        assertTrue(NightlyText.isUnscored(Verdict.NO_FEED))
        assertTrue(NightlyText.isUnscored(Verdict.NO_TEAMS))
        assertFalse(NightlyText.isUnscored(Verdict.SCHEDULED_LATER))
    }

    @Test
    fun `certainty counts verified shown games and groups the rest by reason`() {
        fun g(name: String, v: Verdict) = NightlyText.ShownGame(name, "addon-aaaaaa", v)
        val shown = listOf(g("A vs B", Verdict.IN_PLAY), g("C vs D", Verdict.STARTS_SOON), g("E", Verdict.UNPLACED), g("F", Verdict.NO_TEAMS),
            g("G vs H", Verdict.NO_FEED), g("I vs J", Verdict.NOT_FOUND), g("K vs L", Verdict.SCHEDULED_LATER), g("M vs N", Verdict.FINAL)) +
            (1..12).map { g("X$it vs Y$it", Verdict.NOT_FOUND) }
        val c = NightlyText.certainty(NightlyText.AppLive(20, 0, 0, 0, 0, 0, 20, 1, shown = shown, endedShown = 3, endedVerified = 3))
        assertEquals(20, c.shown)
        assertEquals(2, c.verified)
        assertEquals(1, c.count(NightlyText.Reason.NO_SPORT))
        assertEquals(13, c.count(NightlyText.Reason.NOT_FOUND))
        assertEquals(1, c.count(NightlyText.Reason.FUTURE_START))
        assertEquals(1, c.count(NightlyText.Reason.ESPN_DISAGREES))
        assertEquals("Certainty: 10% of shown LIVE verified (2 of 20); ended: 100% (3 of 3 Just ended rows on a feed final; estimates not measured here).", NightlyText.certaintyLine(c))
        val section = NightlyText.certaintySection(c)
        assertTrue(section.contains("- not found on ESPN: 13"))
        assertTrue(section.contains("  - and 3 more"))
        assertEquals(10, section.count { it.startsWith("  - \"") && it.contains("vs Y") || it.startsWith("  - \"I vs J") })
        assertEquals("2026-11-24_0859,20,2,10%,3,3,100%,1,1,1,13,1,1", NightlyText.certaintyCsvRow("2026-11-24_0859", c))
        assertEquals("-", NightlyText.certainty(NightlyText.AppLive(0, 0, 0, 0, 0, 0, 0, 0)).let { NightlyText.percent(it.verified, it.shown) })
    }
}
