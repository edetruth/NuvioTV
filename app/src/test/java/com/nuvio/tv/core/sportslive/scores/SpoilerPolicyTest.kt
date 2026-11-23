package com.nuvio.tv.core.sportslive.scores

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #117: the one spoiler rule, per surface, for every Scores mode × revealed × spoiler-safe × protected (and, for the
 * chips, game state). The expected table is today's per-surface rules (pinned at their call sites by
 * SpoilerCallSitesTest); a new [SpoilerSurface] fails to compile here until its row of the table is written.
 */
class SpoilerPolicyTest {

    private val bools = listOf(false, true)

    private data class Expected(val mayLoad: Boolean, val score: Boolean, val record: Boolean, val stats: Boolean, val onFocus: Boolean)

    private fun expected(
        surface: SpoilerSurface,
        mode: ScoresMode,
        revealed: Boolean,
        safe: Boolean,
        protected: Boolean,
        state: ScoreState?
    ): Expected {
        val show = mode == ScoresMode.SHOW
        val hide = mode == ScoresMode.HIDE_UNTIL_FOCUSED
        val off = mode == ScoresMode.OFF
        return when (surface) {
            SpoilerSurface.GAME -> {
                val score = !protected && ((show && !safe) || revealed)
                Expected(mayLoad = !off || revealed, score = score, record = score, stats = score, onFocus = false)
            }
            SpoilerSurface.PLAYER_CARD -> {
                val score = !protected && ((show && !safe) || revealed)
                Expected(!off || revealed, score, record = !off || revealed, stats = score, onFocus = hide && !safe && !protected && !revealed)
            }
            SpoilerSurface.TEAM -> {
                val score = !protected && ((show && !safe) || revealed)
                Expected(!off || revealed, score, record = !off || revealed, stats = score, onFocus = hide && !safe && !protected && !revealed)
            }
            SpoilerSurface.RESULTS -> {
                val score = !protected && !off && ((show && !safe) || revealed)
                Expected(!off, score, score, score, onFocus = false)
            }
            SpoilerSurface.SCORE_CHIP -> {
                val spoils = state == null || state == ScoreState.PRE || state == ScoreState.IN || state == ScoreState.POST
                val score = !off && (!spoils || (!protected && ((show && !safe) || revealed)))
                Expected(!off, score, score, score, onFocus = false)
            }
            SpoilerSurface.HOME_HERO -> {
                val score = !protected && show && !safe
                Expected(!off, score, score, score, onFocus = false)
            }
        }
    }

    @Test
    fun `every surface follows its rule in every combination`() {
        val states = listOf<ScoreState?>(null) + ScoreState.entries
        for (surface in SpoilerSurface.entries) for (mode in ScoresMode.entries) for (revealed in bools) for (safe in bools)
            for (protected in bools) for (state in states) {
                // Only the chips are asked with a game state.
                if (surface != SpoilerSurface.SCORE_CHIP && state != null) continue
                val v = SpoilerPolicy.visibility(mode, revealed, SpoilerContext(surface, safe, state, protected))
                val want = expected(surface, mode, revealed, safe, protected, state)
                val label = "$surface $mode revealed=$revealed safe=$safe protected=$protected $state"
                assertEquals(label, want, Expected(v.mayLoad, v.showsScore, v.showsRecord, v.showsStats, v.revealsOnFocus))
                assertEquals(label, want.score || (want.onFocus), v.showsRow(focused = true))
                assertEquals(label, want.score, v.showsRow(focused = false))
            }
    }

    @Test
    fun `nothing shows or loads before the Scores setting is read`() {
        for (surface in SpoilerSurface.entries) for (revealed in bools) {
            assertEquals("$surface $revealed", SpoilerVisibility.NOTHING, SpoilerPolicy.visibility(null, revealed, SpoilerContext(surface)))
        }
    }

    @Test
    fun `the surfaces that differ today stay different`() {
        val offRevealed = { surface: SpoilerSurface -> SpoilerPolicy.visibility(ScoresMode.OFF, true, SpoilerContext(surface)).showsScore }
        // Never show + Reveal: the pages show it; a Results row, a chip and Home never do.
        assertEquals(true, offRevealed(SpoilerSurface.GAME))
        assertEquals(true, offRevealed(SpoilerSurface.TEAM))
        assertEquals(true, offRevealed(SpoilerSurface.PLAYER_CARD))
        assertEquals(false, offRevealed(SpoilerSurface.RESULTS))
        assertEquals(false, offRevealed(SpoilerSurface.SCORE_CHIP))
        assertEquals(false, offRevealed(SpoilerSurface.HOME_HERO))
        // Hide until I reveal: the Team page's record and the Player card's season stats show; the Game page's hide.
        val hide = { surface: SpoilerSurface -> SpoilerPolicy.visibility(ScoresMode.HIDE_UNTIL_FOCUSED, false, SpoilerContext(surface)).showsRecord }
        assertEquals(true, hide(SpoilerSurface.TEAM))
        assertEquals(true, hide(SpoilerSurface.PLAYER_CARD))
        assertEquals(false, hide(SpoilerSurface.GAME))
        // A postponed game's chip shows its status under Hide, protected or not.
        assertEquals(
            true,
            SpoilerPolicy.visibility(ScoresMode.HIDE_UNTIL_FOCUSED, false, SpoilerContext(SpoilerSurface.SCORE_CHIP, eventState = ScoreState.OFF, spoilerProtected = true)).showsScore
        )
    }
}
