package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoTest {

    @Test
    fun `a text is computed once while it stays in the cache, null answers included`() {
        val calls = mutableListOf<String>()
        val memo = Memo<String, String?>(maxEntries = 10) { calls += it; if (it == "none") null else it.uppercase() }

        assertEquals("ARSENAL", memo("arsenal"))
        assertEquals("ARSENAL", memo("arsenal"))
        assertNull(memo("none"))
        assertNull(memo("none"))

        assertEquals(listOf("arsenal", "none"), calls)
    }

    @Test
    fun `past its size the least recently used text is dropped and computed again`() {
        val calls = mutableListOf<String>()
        val memo = Memo(maxEntries = 2) { text: String -> calls += text; text.length }

        memo("a")
        memo("bb")
        memo("a") // a is now the most recent
        memo("ccc") // drops bb
        memo("a")
        memo("bb")

        assertEquals(listOf("a", "bb", "ccc", "bb"), calls)
    }

    @Test
    fun `memoised folding answers exactly as the uncached functions`() {
        val names = listOf("Arsenal FC (W)", "St. John's", "1. FC Köln", "AC Milan", "Paris Saint-Germain", "England U21", "")
        names.forEach { name ->
            assertEquals(SportsLiveTeams.normaliseUncached(name), SportsLiveTeams.normalise(name))
            assertEquals(SportsLiveTeams.normaliseUncached(name), SportsLiveTeams.normalise(name))
        }
        listOf("American Football", "Football", "NBA", "Ligue 1", "Live Events", "Fútbol", "").forEach { text ->
            assertEquals(SportsLiveSportKind.ofUncached(text), SportsLiveSportKind.of(text))
        }
        val cards = listOf(
            Triple("Basketball", listOf("Arizona vs Connecticut"), listOf("Live Sports")),
            Triple(null, listOf("NCAA Football - Ohio State vs Michigan"), listOf("College")),
            Triple("Soccer", listOf("Arsenal vs Chelsea", "Premier League"), emptyList()),
            Triple("Football", listOf("Packers vs Bears"), listOf("Live Events")),
            Triple(null, listOf("WNBA - Liberty vs Aces"), emptyList())
        )
        cards.forEach { (sport, hints, routing) ->
            repeat(2) {
                assertEquals(
                    EspnLeagueMap.leaguesForUncached(sport, hints, routing),
                    EspnLeagueMap.leaguesFor(sport, hints, routing)
                )
                assertEquals(
                    EspnLeagueMap.collegeFallbackForUncached(sport, hints, routing),
                    EspnLeagueMap.collegeFallbackFor(sport, hints, routing)
                )
            }
        }
    }
}
