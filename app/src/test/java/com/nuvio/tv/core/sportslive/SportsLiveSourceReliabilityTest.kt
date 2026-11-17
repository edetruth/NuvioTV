package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.SportsLiveSourceReliability.HALF_LIFE_MS
import com.nuvio.tv.core.sportslive.SportsLiveSourceReliability.NEUTRAL
import com.nuvio.tv.core.sportslive.SportsLiveSourceReliability.Outcome
import com.nuvio.tv.core.sportslive.SportsLiveSourceReliability.Record
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsLiveSourceReliabilityTest {

    private val reliability = SportsLiveSourceReliability
    private val t0 = 1_000_000_000_000L

    @After
    fun tearDown() = reliability.reset()

    private fun recordOf(vararg outcomes: Outcome, at: Long = t0, ttffMs: Long? = null): Record =
        outcomes.fold(null as Record?) { r, o -> reliability.update(r, o, at, ttffMs) }!!

    // --- scoring ---

    @Test
    fun `an unknown source gets the neutral prior`() {
        assertEquals(NEUTRAL, reliability.score(null, t0), 0.0)
        assertEquals(NEUTRAL, reliability.scoreOf("never-seen"), 0.0)
        assertEquals(NEUTRAL, reliability.scoreOf(null), 0.0)
    }

    @Test
    fun `one success lifts a source above neutral and one failure drops it below, without burying it`() {
        val up = reliability.score(recordOf(Outcome.SUCCESS), t0)
        val down = reliability.score(recordOf(Outcome.FAILURE), t0)

        assertTrue(up > NEUTRAL)
        assertTrue(down < NEUTRAL)
        assertTrue("one failure is not a death sentence", down > 0.1)
    }

    @Test
    fun `evidence decays with a three-day half-life, so a bad source recovers toward neutral`() {
        val failed = recordOf(Outcome.FAILURE, Outcome.FAILURE, Outcome.FAILURE)

        val now = reliability.score(failed, t0)
        val later = reliability.score(failed, t0 + HALF_LIFE_MS)
        val muchLater = reliability.score(failed, t0 + 10 * HALF_LIFE_MS)

        assertEquals(0.5, reliability.decayFactor(HALF_LIFE_MS), 1e-9)
        assertTrue(now < later)
        assertTrue(later < muchLater)
        assertEquals(NEUTRAL, muchLater, 0.01)
    }

    @Test
    fun `recent outcomes outweigh old ones`() {
        // Failed a week ago, then worked today.
        val recovered = reliability.update(recordOf(Outcome.FAILURE, Outcome.FAILURE), Outcome.SUCCESS, t0 + 7 * 24 * 3_600_000L)

        assertTrue(reliability.score(recovered, t0 + 7 * 24 * 3_600_000L) > NEUTRAL)
    }

    @Test
    fun `a stall costs less than a failure`() {
        val stalled = reliability.score(recordOf(Outcome.SUCCESS, Outcome.STALL), t0)
        val failed = reliability.score(recordOf(Outcome.SUCCESS, Outcome.FAILURE), t0)
        val clean = reliability.score(recordOf(Outcome.SUCCESS), t0)

        assertTrue(stalled < clean)
        assertTrue(failed < stalled)
    }

    @Test
    fun `slow starts cost a small penalty, fast ones none`() {
        val fast = reliability.score(recordOf(Outcome.SUCCESS, ttffMs = 1_500), t0)
        val slow = reliability.score(recordOf(Outcome.SUCCESS, ttffMs = 20_000), t0)
        val unknown = reliability.score(recordOf(Outcome.SUCCESS), t0)

        assertEquals(unknown, fast, 1e-9)
        assertTrue(slow < fast)
        assertTrue("the slow penalty is small", fast - slow <= 0.1 + 1e-9)
        // A slow but working source still beats one that fails.
        assertTrue(slow > reliability.score(recordOf(Outcome.FAILURE), t0))
    }

    @Test
    fun `time to first frame is a running average`() {
        val first = reliability.update(null, Outcome.SUCCESS, t0, ttffMs = 10_000)
        val second = reliability.update(first, Outcome.SUCCESS, t0, ttffMs = 0)

        assertEquals(10_000.0, first.ttffMs, 0.0)
        assertTrue(second.ttffMs in 1.0..9_999.0)
    }

    // --- ranking ---

    @Test
    fun `with no evidence the original order (the primary first) is kept`() {
        val ranked = reliability.rank(listOf("p", "a", "b"), scoreOf = { NEUTRAL })

        assertEquals(listOf("p", "a", "b"), ranked)
    }

    @Test
    fun `the most reliable source goes first`() {
        val scores = mapOf("p" to 0.2, "a" to 0.5, "b" to 0.9)

        assertEquals(listOf("b", "a", "p"), reliability.rank(listOf("p", "a", "b"), scoreOf = { scores.getValue(it) }))
    }

    @Test
    fun `the primary keeps first place unless another source is clearly better`() {
        val close = mapOf("p" to 0.5, "a" to 0.55)
        val clear = mapOf("p" to 0.5, "a" to 0.75)

        assertEquals(listOf("p", "a"), reliability.rank(listOf("p", "a"), scoreOf = { close.getValue(it) }))
        assertEquals(listOf("a", "p"), reliability.rank(listOf("p", "a"), scoreOf = { clear.getValue(it) }))
    }

    @Test
    fun `sources the user's filters rule out are never promoted ahead of allowed ones`() {
        val scores = mapOf("p" to 0.1, "a" to 0.9, "b" to 0.95, "c" to 0.4)
        val allowed = setOf("p", "c")

        val ranked = reliability.rank(listOf("p", "a", "b", "c"), scoreOf = { scores.getValue(it) }, allowed = { it in allowed })

        // Allowed ones ranked among themselves, then the rest in their original order.
        assertEquals(listOf("c", "p", "a", "b"), ranked)
    }

    @Test
    fun `a recorded failure moves a source down and a success moves it up`() {
        reliability.nowMs = { t0 }
        val sources = listOf("p", "a", "b")
        fun ranked() = reliability.rank(sources, scoreOf = { reliability.scoreOf(it) })

        reliability.record("p", Outcome.FAILURE)
        assertEquals(listOf("a", "b", "p"), ranked())

        reliability.record("b", Outcome.SUCCESS)
        assertEquals(listOf("b", "a", "p"), ranked())
    }

    @Test
    fun `clearing forgets everything`() {
        reliability.nowMs = { t0 }
        var saved: Map<String, Record>? = null
        reliability.install(emptyMap()) { saved = it }
        reliability.record("p", Outcome.FAILURE)

        reliability.clear()

        assertEquals(NEUTRAL, reliability.scoreOf("p"), 0.0)
        assertEquals(emptyMap<String, Record>(), saved)
    }

    // --- storage ---

    @Test
    fun `the book is capped, dropping the least recently used sources`() {
        val records = (1..10).associate { "s$it" to Record(successes = 1.0, atMs = t0 + it) }

        val capped = reliability.capped(records, max = 3)

        assertEquals(setOf("s8", "s9", "s10"), capped.keys)
    }

    @Test
    fun `recording past the cap evicts the oldest source`() {
        var now = t0
        reliability.nowMs = { now }
        repeat(SportsLiveSourceReliability.MAX_ENTRIES + 1) {
            now++
            reliability.record("s$it", Outcome.SUCCESS)
        }

        assertEquals(SportsLiveSourceReliability.MAX_ENTRIES, reliability.snapshot().size)
        assertTrue("s0" !in reliability.snapshot())
        assertTrue("s${SportsLiveSourceReliability.MAX_ENTRIES}" in reliability.snapshot())
    }

    @Test
    fun `records survive a persistence round trip`() {
        val records = mapOf(
            "addon.one|catalog:live" to recordOf(Outcome.SUCCESS, Outcome.STALL, ttffMs = 2_400),
            "addon.two|catalog:tv" to recordOf(Outcome.FAILURE)
        )

        val decoded = reliability.decode(reliability.encode(records))

        assertEquals(records, decoded)
    }

    @Test
    fun `an empty or corrupt store starts over`() {
        assertEquals(emptyMap<String, Record>(), reliability.decode(null))
        assertEquals(emptyMap<String, Record>(), reliability.decode(""))
        assertEquals(emptyMap<String, Record>(), reliability.decode("{not json"))
        assertEquals(emptyMap<String, Record>(), reliability.decode("""{"k":[1.0]}"""))
    }

    @Test
    fun `install loads the stored book and saves each change`() {
        reliability.nowMs = { t0 }
        val stored = mapOf("p" to recordOf(Outcome.FAILURE))
        val saves = mutableListOf<Map<String, Record>>()

        reliability.install(stored) { saves += it }
        reliability.record("a", Outcome.SUCCESS)

        assertTrue(reliability.scoreOf("p") < NEUTRAL)
        assertEquals(setOf("p", "a"), saves.single().keys)
    }

    @Test
    fun `blank keys are ignored`() {
        reliability.record(null, Outcome.FAILURE)
        reliability.record("", Outcome.FAILURE)

        assertTrue(reliability.snapshot().isEmpty())
    }
}
