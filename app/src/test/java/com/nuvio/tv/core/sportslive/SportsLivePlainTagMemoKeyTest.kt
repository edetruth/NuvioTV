package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.TeamDirectory
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rows keep their text's sport tag per item and team directory revision (release review, TV performance:
 * rememberTextPlainTag in SportsLiveScoreboardView). That memo is right only if every answer [SportsLiveSportKind.plainTagOf]
 * can change with moves the revision too, and the same item and revision always give the same answer.
 */
class SportsLivePlainTagMemoKeyTest {

    private fun item(name: String, sport: String) = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
        kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false, startMs = null, sport = sport,
        startHasTimeOfDay = false, teams = SportsLiveTeams.parse(name)
    )

    @Test
    fun `the NFL list loading moves the revision the row tag memo is keyed on, and the tag with it`() {
        val directory = TeamDirectory()
        val bare = item("East Bengal FC vs Sporting Delhi", "Football")
        val before = directory.revision.value
        assertNull(SportsLiveSportKind.plainTagOf(bare, directory))
        assertNull(SportsLiveSportKind.plainTagOf(bare, directory))
        directory.put(EspnLeagueMap.NFL, emptyList(), 0L)
        assertNotEquals(before, directory.revision.value)
        assertEquals(SportsLiveSportKind.SOCCER, SportsLiveSportKind.plainTagOf(bare, directory))
    }

    @Test
    fun `a plain sport word answers the same whatever the directory holds`() {
        val hockey = item("Devils vs Rangers", "Ice Hockey")
        assertEquals(SportsLiveSportKind.HOCKEY, SportsLiveSportKind.plainTagOf(hockey, TeamDirectory()))
        assertEquals(
            SportsLiveSportKind.HOCKEY,
            SportsLiveSportKind.plainTagOf(hockey, TeamDirectory().apply { put(EspnLeagueMap.NFL, emptyList(), 0L) })
        )
    }
}
