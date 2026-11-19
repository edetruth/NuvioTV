package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.SportsLiveRecoveryLadder.Step
import com.nuvio.tv.core.sportslive.SportsLiveStreamHealth.Problem
import com.nuvio.tv.domain.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #138: the recovery ladder, its budget, and the links a recovery may switch to. */
class SportsLiveRecoveryLadderTest {

    private var now = 1_000_000L
    private val budget = SportsLiveRecoveryBudget(nowMs = { now })
    private val links = SportsLiveWorkingLinks(nowMs = { now })

    private fun ladder() = SportsLiveRecoveryLadder(budget, nowMs = { now })

    @Test
    fun `a link is retried as is once, then the next link is tried`() {
        val ladder = ladder()
        assertEquals(Step.RETRY_SAME, ladder.decide(GAME, A, Problem.FROZEN))
        assertEquals(Step.NEXT_LINK, ladder.decide(GAME, A, Problem.FROZEN))
        // The next link gets its own retry.
        assertEquals(Step.RETRY_SAME, ladder.decide(GAME, B, Problem.BUFFERING_TOO_LONG))
    }

    @Test
    fun `an error burst skips the retry of the same link`() {
        assertEquals(Step.NEXT_LINK, ladder().decide(GAME, A, Problem.ERROR_BURST))
    }

    @Test
    fun `a link that never started or whose host is unknown goes straight to the next link`() {
        val ladder = ladder()
        assertEquals(Step.NEXT_LINK, ladder.decide(GAME, A, Problem.NO_START))
        assertEquals(Step.NEXT_LINK, ladder.decide(GAME, B, Problem.DEAD_LINK))
    }

    @Test
    fun `the ladder order is retry, next link, then the choices once the budget is spent`() {
        val ladder = ladder()
        val steps = listOf(
            ladder.decide(GAME, A, Problem.FROZEN),
            ladder.decide(GAME, A, Problem.FROZEN),
            ladder.decide(GAME, B, Problem.NO_START),
            ladder.decide(GAME, B, Problem.FROZEN)
        )
        assertEquals(listOf(Step.RETRY_SAME, Step.NEXT_LINK, Step.NEXT_LINK, Step.CHOICES), steps)
    }

    @Test
    fun `links that never started fail over past the budget in the full player (153)`() {
        val ladder = ladder()
        repeat(9) {
            val link = "https://example.invalid/$it.m3u8"
            val problem = if (it % 2 == 0) Problem.NO_START else Problem.DEAD_LINK
            assertEquals(Step.NEXT_LINK, ladder.decide(GAME, link, problem, started = false))
        }
        // None of them spent the game's budget: a stream that was playing still gets its full ladder.
        assertEquals(Step.RETRY_SAME, ladder.decide(GAME, A, Problem.FROZEN))
    }

    @Test
    fun `a playing link's problems still spend the budget when started is false (153)`() {
        val ladder = ladder()
        repeat(SportsLiveStreamThresholds.MAX_RECOVERIES) { ladder.decide(GAME, "https://example.invalid/$it.m3u8", Problem.ERROR_BURST, started = false) }
        assertEquals(Step.CHOICES, ladder.decide(GAME, B, Problem.ERROR_BURST, started = false))
    }

    @Test
    fun `a start-up timeout spends one of the game's recoveries`() {
        val ladder = ladder()
        repeat(SportsLiveStreamThresholds.MAX_RECOVERIES) { ladder.decide(GAME, "https://example.invalid/$it.m3u8", Problem.NO_START) }
        assertEquals(Step.CHOICES, ladder.decide(GAME, B, Problem.NO_START))
    }

    @Test
    fun `a link without a url goes to the next link`() {
        assertEquals(Step.NEXT_LINK, ladder().decide(GAME, null, Problem.ERROR))
    }

    @Test
    fun `at most three recoveries per game in ten minutes, then the choices`() {
        val ladder = ladder()
        assertEquals(Step.RETRY_SAME, ladder.decide(GAME, A, Problem.FROZEN))
        assertEquals(Step.NEXT_LINK, ladder.decide(GAME, A, Problem.FROZEN))
        assertEquals(Step.RETRY_SAME, ladder.decide(GAME, B, Problem.FROZEN))
        assertEquals(Step.CHOICES, ladder.decide(GAME, B, Problem.FROZEN))
        assertEquals(Step.CHOICES, ladder.decide(GAME, C, Problem.UNEXPECTED_END))

        // The window moves on: recoveries come back, and a link may be retried as is again.
        now += SportsLiveRecoveryBudget.WINDOW_MS
        assertEquals(Step.RETRY_SAME, ladder.decide(GAME, A, Problem.FROZEN))
    }

    @Test
    fun `the budget is per game, shared by every player of that game`() {
        val full = ladder()
        val mini = ladder()
        repeat(SportsLiveRecoveryBudget.MAX_RECOVERIES) { full.decide(GAME, A, Problem.FROZEN) }
        assertEquals(Step.CHOICES, mini.decide(GAME, B, Problem.FROZEN))
        assertEquals(Step.RETRY_SAME, mini.decide(OTHER_GAME, B, Problem.FROZEN))
    }

    @Test
    fun `dual panes recover independently`() {
        val left = ladder()
        val right = ladder()
        // The left pane's game freezes over and over; the right pane's game is untouched by it.
        assertEquals(Step.RETRY_SAME, left.decide(GAME, A, Problem.FROZEN))
        assertEquals(Step.NEXT_LINK, left.decide(GAME, A, Problem.FROZEN))
        assertEquals(Step.RETRY_SAME, left.decide(GAME, B, Problem.FROZEN))
        assertEquals(Step.CHOICES, left.decide(GAME, B, Problem.FROZEN))

        assertEquals(Step.RETRY_SAME, right.decide(OTHER_GAME, A, Problem.FROZEN))
        assertEquals(Step.NEXT_LINK, right.decide(OTHER_GAME, A, Problem.FROZEN))
    }

    @Test
    fun `the budget forgets spent recoveries after the window`() {
        assertTrue(budget.take(GAME))
        now += 1
        assertTrue(budget.take(GAME))
        assertTrue(budget.take(GAME))
        assertFalse(budget.take(GAME))
        now += SportsLiveRecoveryBudget.WINDOW_MS - 1
        assertTrue(budget.take(GAME))
        assertFalse(budget.take(GAME))
    }

    @Test
    fun `candidates put good links first, then the rest, then other sources`() {
        links.markGood(GAME, C)
        val order = SportsLiveRecoveryCandidates.order(
            GAME,
            primary = listOf(stream(A), stream(B), stream(C)),
            siblings = listOf(stream(D), stream(A)),
            tried = emptySet(),
            workingLinks = links,
            coolingAddonNames = emptySet()
        )
        assertEquals(listOf(C, A, B, D), order.map { it.url })
    }

    @Test
    fun `candidates skip tried, quarantined, cooling and notice entries`() {
        links.quarantine(GAME, B)
        val cooldown = SportsLiveSourceCooldown(nowMs = { now })
        cooldown.note("https://cool.invalid", "Cool", minutes = 5)
        val order = SportsLiveRecoveryCandidates.order(
            GAME,
            primary = listOf(
                stream(A),
                stream(B),
                stream(C, addon = "Cool"),
                stream("https://example.invalid/notice.m3u8", name = "Rate limit reached, wait 7 min"),
                stream(D)
            ),
            siblings = listOf(stream(E), Stream(
                name = "no url", title = null, description = null, url = null, ytId = null, infoHash = null,
                fileIdx = null, externalUrl = null, behaviorHints = null, addonName = "addon", addonLogo = null
            )),
            tried = setOf(SportsLiveWorkingLinks.linkKey(A)!!),
            workingLinks = links,
            coolingAddonNames = cooldown.coolingNames()
        )
        assertEquals(listOf(D, E), order.map { it.url })

        // Once the cool-down is over, its source is a candidate again.
        now += 5 * 60_000L
        assertTrue(cooldown.coolingNames().isEmpty())
    }

    @Test
    fun `a link that freezes counts as failed under the working-links rules`() {
        // Not known good: quarantined at its first failure.
        assertTrue(links.failed(GAME, A))
        assertTrue(links.isQuarantined(GAME, A))
        // Known good: a first failure is a strike, a second within the strike window quarantines it.
        links.markGood(GAME, B)
        assertFalse(links.failed(GAME, B))
        assertFalse(links.isQuarantined(GAME, B))
        now += 60_000
        assertTrue(links.failed(GAME, B))
        assertTrue(links.isQuarantined(GAME, B))
        // Quarantined links are no longer candidates.
        assertEquals(
            listOf(C),
            SportsLiveRecoveryCandidates.order(GAME, listOf(stream(A), stream(B), stream(C)), emptyList(), emptySet(), links, emptySet())
                .map { it.url }
        )
    }

    private companion object {
        const val GAME = "game"
        const val OTHER_GAME = "other"
        const val A = "https://example.invalid/a.m3u8"
        const val B = "https://example.invalid/b.m3u8"
        const val C = "https://example.invalid/c.m3u8"
        const val D = "https://example.invalid/d.m3u8"
        const val E = "https://example.invalid/e.m3u8"

        fun stream(url: String, addon: String = "addon", name: String = "HD") = Stream(
            name = name,
            title = null,
            description = null,
            url = url,
            ytId = null,
            infoHash = null,
            fileIdx = null,
            externalUrl = null,
            behaviorHints = null,
            addonName = addon,
            addonLogo = null
        )
    }
}
