package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.core.sportslive.SportsLiveEndedGames
import com.nuvio.tv.core.sportslive.SportsLivePillTime
import com.nuvio.tv.core.sportslive.SportsLiveScoreWait
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

/** #375: Home's Live now row reads the live truth as Live Now does: waiting for ESPN, Probably ended, the hero's eyebrow. */
class SportsLiveHomeLiveTruthTest {

    private val nowMs = Instant.parse("2026-11-15T20:00:00Z").toEpochMilli()
    private val hour = 3_600_000L

    private fun item(id: String, name: String, sport: String, startMs: Long?) = SportsLiveItem(
        meta = MetaPreview(
            id = id, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = listOf(sport)
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
        kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false,
        startMs = startMs, sport = sport, startHasTimeOfDay = startMs != null, teams = SportsLiveTeams.parse(name)
    )

    // An NBA game (covered by ESPN), a rugby game (no feed covers it), one over long ago (no feed, past its length).
    private val covered = item("nba", "Indiana Pacers at Philadelphia 76ers", "Basketball", nowMs - hour)
    private val uncovered = item("rugby", "Leinster vs Munster", "Rugby", nowMs - hour)
    private val over = item("over", "Saracens vs Harlequins", "Rugby", nowMs - 5 * hour)
    private val all = listOf(covered, uncovered, over)

    private fun truth(mode: ScoresMode?, wait: SportsLiveScoreWait = SportsLiveScoreWait(), at: Long = nowMs, scores: Map<String, com.nuvio.tv.core.sportslive.scores.ScoreSnapshot> = emptyMap()) =
        homeLiveTruth(all, all, scores, mode, emptySet(), at, wait, SportsLiveEndedGames()) { false }

    @Test
    fun `a covered game waits for ESPN on Home, an uncovered one is live as before, one over long ago reads Probably ended`() {
        val truth = truth(ScoresMode.SHOW)
        assertEquals(setOf(covered.streamKey), truth.awaiting)
        assertEquals(setOf(over.streamKey), truth.probablyEnded)
        assertEquals(nowMs + SportsLiveScoreWait.MAX_WAIT_MS, truth.deadline)
    }

    @Test
    fun `Scores Off or not read yet - nothing waits, and the wait ends with ESPN's answer or its deadline`() {
        assertTrue(truth(ScoresMode.OFF).awaiting.isEmpty())
        assertTrue(truth(null).awaiting.isEmpty())
        val wait = SportsLiveScoreWait()
        truth(ScoresMode.SHOW, wait)
        assertTrue(truth(ScoresMode.SHOW, wait, at = nowMs + SportsLiveScoreWait.MAX_WAIT_MS).awaiting.isEmpty())
        val answered = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA).first { it.eventId == "401909985" }
            .copy(state = ScoreState.IN, isFinal = false)
        val fresh = truth(ScoresMode.SHOW, scores = mapOf(covered.streamKey to answered))
        assertTrue(fresh.awaiting.isEmpty())
        assertNull(fresh.deadline)
    }

    @Test
    fun `L5 - a postponed game's hero eyebrow reads ESPN's word, never Live now`() {
        val off = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA).first { it.eventId == "401909985" }
            .copy(state = ScoreState.OFF, isFinal = false, clockText = "Canceled", mayResume = false)
        val starts = SportsLiveHomeRow.starts(
            listOf(covered), mapOf(covered.streamKey to off), "Starts %1\$s", "Tomorrow %1\$s", nowMs, ZoneOffset.UTC, Locale.US,
            postponedWord = "Postponed"
        )
        assertEquals(mapOf(covered.streamKey to "Canceled"), starts)
    }

    @Test
    fun `the hero's eyebrow follows the card - its start while it waits, Probably ended, Checking score with no start`() {
        val time = (SportsLivePillTime.of(covered.startMs!!, true, nowMs, ZoneOffset.UTC, Locale.US) as SportsLivePillTime.Today).time
        val untimed = item("untimed", "Boston Celtics at New York Knicks", "Basketball", null)
        val starts = SportsLiveHomeRow.starts(
            listOf(covered, uncovered, over, untimed), emptyMap(), "Starts %1\$s", "Tomorrow %1\$s", nowMs, ZoneOffset.UTC, Locale.US,
            awaiting = setOf(covered.streamKey, untimed.streamKey), checkingWord = "Checking score…",
            probablyEnded = setOf(over.streamKey), probablyEndedWord = "Probably ended"
        )
        assertEquals(
            mapOf(covered.streamKey to time, untimed.streamKey to "Checking score…", over.streamKey to "Probably ended"),
            starts
        )
        // The hero reads it in place of "Live now".
        val words = SportsLiveHomeRow.Words(liveNow = "Live now", yourTeam = "★ Your team", streams = { "$it streams" })
        val eyebrow = SportsLiveHomeRow.build(listOf(covered), "Live now", emptyMap(), words, emptyMap(), starts).items.single().genres.single()
        assertTrue(eyebrow, eyebrow.endsWith(time))
    }
}
