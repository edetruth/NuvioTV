package com.nuvio.tv.ui.screens.sportslive.dual

import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.ChipScore
import com.nuvio.tv.core.sportslive.scores.GameArt
import com.nuvio.tv.core.sportslive.scores.GameArtTeam
import com.nuvio.tv.core.sportslive.scores.ScoreChip
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.ui.screens.sportslive.ScoreboardGroup
import com.nuvio.tv.ui.screens.sportslive.ScoreboardRow
import com.nuvio.tv.ui.screens.sportslive.YourTeams
import com.nuvio.tv.ui.screens.sportslive.YourTeamsEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * live.26 item 4: Live Now opens on your team's live game, and Hold OK on another game offers
 * "Watch with the ‹team› game" (one press into dual screen), under the picker's rules.
 */
class SportsLiveTeamPairTest {

    private val focused = item("focused")
    private val lakers = row("lakers", firstFollowed = true)
    private val celtics = row("celtics", secondFollowed = true)
    private val other = item("other")

    /** The picker's candidates for [chosen] over [pool], as the Hold OK menu builds them. */
    private fun candidates(chosen: SportsLiveItem, pool: List<SportsLiveItem>, ended: Set<String> = emptySet()) =
        SportsLiveDualPick(chosen, pool, filter = null, tab = SportsLiveTab.LIVE_NOW, endedKeys = ended).candidates

    @Test
    fun `Hold OK on another game pairs it with the first followed team's live game`() {
        val strip = listOf(YourTeamsEntry.Live(lakers), YourTeamsEntry.Live(celtics))
        val pool = listOf(focused, other, celtics.item, lakers.item)
        assertEquals(lakers, SportsLiveSecondGame.teamPair(strip, candidates(focused, pool)))
    }

    @Test
    fun `no entry on the followed game itself when it is the only one live`() {
        val strip = listOf(YourTeamsEntry.Live(lakers))
        assertNull(SportsLiveSecondGame.teamPair(strip, candidates(lakers.item, listOf(lakers.item, other))))
    }

    @Test
    fun `on one followed game the entry offers the other followed game`() {
        val strip = listOf(YourTeamsEntry.Live(lakers), YourTeamsEntry.Live(celtics))
        val pool = listOf(lakers.item, celtics.item, other)
        assertEquals(celtics, SportsLiveSecondGame.teamPair(strip, candidates(lakers.item, pool)))
    }

    @Test
    fun `no entry when no followed team is live`() {
        assertNull(SportsLiveSecondGame.teamPair(emptyList(), candidates(focused, listOf(focused, other))))
    }

    @Test
    fun `a followed game the picker would not offer is never paired`() {
        val strip = listOf(YourTeamsEntry.Live(lakers), YourTeamsEntry.Live(celtics))
        val pool = listOf(focused, lakers.item, celtics.item)
        // Ended (Just ended, an ESPN final): the picker leaves it out, so the entry skips to the next followed game.
        assertEquals(celtics, SportsLiveSecondGame.teamPair(strip, candidates(focused, pool, ended = setOf(lakers.item.streamKey))))
        // Not in the picker's list at all (another tab's list): no entry.
        assertNull(SportsLiveSecondGame.teamPair(strip, candidates(focused, listOf(focused, other))))
    }

    @Test
    fun `a game saved with Watch later is never paired, as the auto pair never picks it`() {
        val strip = listOf(YourTeamsEntry.Live(lakers), YourTeamsEntry.Live(celtics))
        val pool = listOf(focused, lakers.item, celtics.item)
        val saved = { item: SportsLiveItem -> item.streamKey == lakers.item.streamKey }
        assertEquals(celtics, SportsLiveSecondGame.teamPair(strip, candidates(focused, pool), saved))
        assertNull(SportsLiveSecondGame.teamPair(listOf(YourTeamsEntry.Live(lakers)), candidates(focused, pool), saved))
    }

    @Test
    fun `the entry names the followed team, never the listing's title or a score`() {
        // The listing reads "Lakers 98-95 Celtics": the entry is built from the drawn sides only.
        val scored = lakers.copy(chip = ScoreChip.Live("Q4 2:00", ChipScore(98, 95)))
        assertEquals("Lakers", SportsLiveSecondGame.teamPairName(scored, ::vs))
        assertEquals("Celtics", SportsLiveSecondGame.teamPairName(celtics, ::vs))
    }

    @Test
    fun `both sides followed or none flagged, the entry names the match`() {
        assertEquals("Lakers vs Celtics", SportsLiveSecondGame.teamPairName(row("both", firstFollowed = true, secondFollowed = true), ::vs))
        assertEquals("Lakers vs Celtics", SportsLiveSecondGame.teamPairName(row("none"), ::vs))
        assertNull(SportsLiveSecondGame.teamPairName(row("no-art").copy(art = null), ::vs))
    }

    @Test
    fun `Live Now opens on the strip only when its first card is a followed team's live game`() {
        assertTrue(YourTeams.opensOnStrip(listOf(YourTeamsEntry.Live(lakers))))
        assertFalse(YourTeams.opensOnStrip(emptyList()))
        // A game later today first (nothing of yours live): Live Now opens on its list's first row.
        val later = YourTeamsEntry.LaterToday(row("later"), startMs = 0L)
        assertFalse(YourTeams.opensOnStrip(listOf(later)))
    }

    private fun vs(first: String, second: String) = "$first vs $second"

    private fun row(id: String, firstFollowed: Boolean = false, secondFollowed: Boolean = false) = ScoreboardRow(
        item = item(id, followed = firstFollowed || secondFollowed),
        group = ScoreboardGroup.LIVE,
        chip = null,
        art = GameArt(
            first = GameArtTeam(name = "Los Angeles Lakers", short = "LAL", logo = null, shortName = "Lakers"),
            second = GameArtTeam(name = "Boston Celtics", short = "BOS", logo = null, shortName = "Celtics"),
            league = "NBA",
            startMs = null,
            hasTimeOfDay = false
        ),
        firstFollowed = firstFollowed,
        secondFollowed = secondFollowed,
        probablyEnded = false
    )

    private fun item(id: String, followed: Boolean = false) = SportsLiveItem(
        meta = MetaPreview(
            id = id, type = ContentType.UNKNOWN, rawType = "tv", name = "Lakers 98-95 Celtics", poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null, releaseInfo = null,
            imdbRating = null, genres = emptyList()
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
        kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false, startMs = null, sport = "NBA", startHasTimeOfDay = false,
        teams = SportsLiveTeams.parse("Lakers vs Celtics"), followed = followed
    )
}
