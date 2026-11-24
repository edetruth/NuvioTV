package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.FinalNote
import com.nuvio.tv.core.sportslive.scores.GameSection
import com.nuvio.tv.core.sportslive.scores.GameSituation
import com.nuvio.tv.core.sportslive.scores.GameSituations
import com.nuvio.tv.core.sportslive.scores.GameSummaryFixtures
import com.nuvio.tv.core.sportslive.scores.ResultRow
import com.nuvio.tv.core.sportslive.scores.ScoreChipStrings
import com.nuvio.tv.core.sportslive.scores.ScoreChips
import com.nuvio.tv.core.sportslive.scores.ScoreSide
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.core.sportslive.scores.SpoilerProtection
import com.nuvio.tv.core.sportslive.scores.TeamPageSpoilers
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.ui.screens.home.SportsLiveHomeRow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #117 spoiler audit: for every Scores mode × revealed × spoiler-safe × protected, every surface that should hide a
 * game's result shows no score, record or stat of it, and no text of a hidden chip or label holds its score. The
 * rule audited is the trust promise, not any one surface's code: hidden unless Show (and not spoiler-safe) or
 * revealed, and always hidden while the game is protected.
 */
class SpoilerAuditTest {

    private val modes = ScoresMode.entries
    private val bools = listOf(false, true)

    // IND @ PHI: home Philadelphia 76ers 131, away Indiana Pacers 124 (Final).
    private val pacers = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA).first { it.eventId == "401909985" }
    private val now = pacers.startMs!! + 4 * 60 * 60_000L
    private val live = pacers.copy(
        state = ScoreState.IN, isFinal = false, clockText = "Q3 4:12", home = pacers.home.copy(score = 88), away = pacers.away.copy(score = 80),
        situation = GameSituation(possession = ScoreSide.HOME, outs = 1)
    )
    private val summary = GameSummaryFixtures.summary(GameSummaryFixtures.SOCCER, EspnFixtures.ENG1, "401879243")

    private val strings = ScoreChipStrings(
        score = "%1\$d–%2\$d",
        live = "%1\$s · %2\$s",
        final = "%1\$s %2\$s",
        finalNote = "Final",
        postponed = "Postponed",
        hidden = "Score hidden"
    )

    private val card = SportsLiveItem(
        meta = MetaPreview(
            id = "76ers vs Pacers", type = ContentType.UNKNOWN, rawType = "sport", name = "76ers vs Pacers", poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
        kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false,
        startMs = null, sport = "Basketball", teams = SportsLiveTeams.parse("76ers vs Pacers")
    )

    /** The trust promise: what must stay hidden, before any surface's own extra rule. */
    private fun mustHide(mode: ScoresMode, revealed: Boolean, safe: Boolean, protected: Boolean): Boolean =
        protected || (!revealed && (mode != ScoresMode.SHOW || safe))

    private fun assertNoScore(label: String, text: String?, vararg scores: Int) {
        for (score in scores) assertFalse("$label leaks $score in \"$text\"", text.orEmpty().contains(score.toString()))
    }

    @Test
    fun `no surface shows a hidden game's score, record or stat, in any mode`() {
        for (mode in modes) for (revealed in bools) for (safe in bools) for (protected in bools) {
            if (!mustHide(mode, revealed, safe, protected)) continue
            val label = "$mode revealed=$revealed safe=$safe protected=$protected"

            // Game page: no score, records or stats; at most the spoiler-neutral Preview.
            val game = SportsLiveGameUiState(
                event = pacers, summary = summary, mode = mode, revealed = revealed, spoilerSafe = safe, spoilerProtected = protected
            )
            assertFalse("Game $label", game.showsScore)
            assertTrue("Game $label", game.sections.all { it == GameSection.PREVIEW })

            // Player card, as the Game and Team pages open it (a protected game: spoiler-safe, never revealed): no
            // unfocused last game; no focus reveal where Reveal is needed.
            val player = SportsLivePlayerCardUiState(mode = mode, revealed = revealed && !protected, spoilerSafe = safe || protected)
            assertFalse("Player card $label", player.showsGame(focused = false))
            if (mode != ScoresMode.HIDE_UNTIL_FOCUSED || safe || protected) assertFalse("Player card focused $label", player.showsGame(focused = true))

            // Team page: opened from a spoiler-safe or protected Game page it is spoiler-safe too (no result, no record).
            if (!revealed && (safe || protected)) {
                assertFalse("Team safe $label", TeamPageSpoilers.showsResult(mode, revealed, focused = true, spoilerProtected = protected, spoilerSafe = true))
                assertFalse("Team safe record $label", TeamPageSpoilers.showsRecord(mode, revealed, spoilerSafe = true))
            }
            // Its hidden rows are otherwise the protected ones and the mode's.
            if (protected || (!revealed && mode != ScoresMode.SHOW)) {
                assertFalse("Team $label", TeamPageSpoilers.showsResult(mode, revealed, focused = false, spoilerProtected = protected))
                if (protected || mode == ScoresMode.OFF) {
                    assertFalse("Team focused $label", TeamPageSpoilers.showsResult(mode, revealed, focused = true, spoilerProtected = protected))
                }
            }

            // Results and the cards (no spoiler-safe entry either).
            if (protected || (!revealed && mode != ScoresMode.SHOW)) {
                val saved = SpoilerProtection(SpoilerProtection.saved(emptyList(), pacers, now), nowMs = now)
                val results = SportsLiveResultsUiState(
                    mode = mode, revealed = if (revealed) setOf(pacers.eventId) else emptySet(),
                    protection = if (protected) saved else SpoilerProtection()
                )
                val row = ResultRow(pacers, followed = false, note = FinalNote.FT)
                assertFalse("Results $label", results.showsScore(row))
                assertNotNull("Results $label", results.hiddenReason(row))

                for (snapshot in listOf<ScoreSnapshot>(live, pacers)) {
                    val stamped = snapshot.copy(spoilerProtected = protected)
                    val chip = ScoreChips.of(card, stamped, mode, revealed)
                    assertNoScore("Chip $label ${snapshot.state}", chip?.let { ScoreChips.text(it, strings) }, 88, 80, 131, 124)
                    assertNull("Situation $label ${snapshot.state}", GameSituations.of(card, stamped, mode, revealed))
                    // Home: no focus reveal, so its cards read their chip as not revealed.
                    val homeChip = ScoreChips.of(card, stamped, mode, revealed = false)
                    assertNoScore("Home card $label ${snapshot.state}", homeChip?.let { ScoreChips.text(it, strings) }, 88, 80, 131, 124)
                    val labels = SportsLiveHomeRow.scoreLabels(listOf(card), mapOf(card.streamKey to stamped), mode, strings, "● %1\$s")
                    assertNoScore("Home hero $label ${snapshot.state}", labels.values.joinToString(), 88, 80, 131, 124)
                }
            }
        }
    }
}
