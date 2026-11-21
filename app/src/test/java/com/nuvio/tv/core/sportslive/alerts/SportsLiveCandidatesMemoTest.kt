package com.nuvio.tv.core.sportslive.alerts

import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.repository.SportsLiveLoad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** #207: the my-games keeper's every-30-s pass sorts the load again only when its answer could change. */
class SportsLiveCandidatesMemoTest {

    private var stamp = 1L
    private var builds = 0
    private val memo = SportsLiveCandidatesMemo(
        stamp = { stamp },
        build = { load, _, _ ->
            builds++
            load?.items.orEmpty().toList()
        }
    )

    private fun load() = SportsLiveLoad(items = emptyList(), failures = emptyList(), loadedAtMs = 0L, claimedKeys = emptySet())

    @Test
    fun `the same load, catalogs and follows are sorted once`() {
        val load = load()
        val first = memo.of(load, emptyList(), emptyList())
        val second = memo.of(load, emptyList(), emptyList())
        assertEquals(1, builds)
        assertSame(first, second)
    }

    @Test
    fun `a new load, a follow or a team directory change sorts again`() {
        val load = load()
        memo.of(load, emptyList(), emptyList())
        // An equal load that is a new one (a refresh): sorted again.
        memo.of(load(), emptyList(), emptyList())
        assertEquals(2, builds)
        val again = load()
        memo.of(again, emptyList(), emptyList())
        memo.of(again, emptyList(), listOf(FollowedTeam(name = "Pacers")))
        assertEquals(4, builds)
        stamp = 2L
        memo.of(again, emptyList(), listOf(FollowedTeam(name = "Pacers")))
        assertEquals(5, builds)
    }

    @Test
    fun `no load gives no games and is kept too`() {
        assertEquals(emptyList<SportsLiveItem>(), memo.of(null, emptyList(), emptyList()))
        memo.of(null, emptyList(), emptyList())
        assertEquals(1, builds)
    }
}
