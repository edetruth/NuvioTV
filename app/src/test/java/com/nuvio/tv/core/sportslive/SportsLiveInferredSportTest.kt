package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.identify.GameIdentity
import com.nuvio.tv.core.sportslive.identify.TeamIdentityBook
import com.nuvio.tv.core.sportslive.scores.DirectoryTeam
import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.TeamDirectory
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * live.25 (#221): a card's inferred sport holds until a team list its inference read, or its teams' identity, changes;
 * lists it never read, other games' identities and learned logos leave it as it is.
 */
class SportsLiveInferredSportTest {

    private var nextId = 0

    private fun team(display: String, nickname: String, location: String, abbr: String) =
        (++nextId).toString().let { id -> DirectoryTeam(id, display, nickname, abbr, location, nickname, "https://example.invalid/$id.png") }

    private val nhl = listOf(
        team("New Jersey Devils", "Devils", "New Jersey", "NJ"),
        team("Montreal Canadiens", "Canadiens", "Montreal", "MTL"),
        team("New York Rangers", "Rangers", "New York", "NYR")
    )

    /** Every inference league loaded: [lists]' with their teams, the rest with a placeholder. */
    private fun directory(vararg lists: Pair<EspnLeague, List<DirectoryTeam>>): TeamDirectory {
        val dir = TeamDirectory()
        val given = lists.toMap()
        (SportsLiveSportInference.leagues + EspnLeagueMap.COLLEGE_BASKETBALL + EspnLeagueMap.COLLEGE_BASKETBALL_WOMEN + EspnLeagueMap.COLLEGE_FOOTBALL).forEach { league ->
            dir.put(league, given[league] ?: listOf(team("Placeholder ${league.league}", "Placeholder", "Nowhere", "PLC")), 0L)
        }
        return dir
    }

    private fun card(name: String, sport: String? = null) = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
        kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false, startMs = null, sport = sport,
        startHasTimeOfDay = true, teams = SportsLiveTeams.parse(name)
    )

    @Test
    fun `a list the card did not read and another game's identity keep its inferred sport`() {
        val dir = directory(EspnLeagueMap.NHL to nhl)
        val book = TeamIdentityBook()
        val inferred = SportsLiveInferredSport.infer(card("Devils v Canadiens"), dir, book)
        assertEquals("NHL", inferred.item.sport)

        // A second-tier soccer list (never one a card with no sport is inferred into) is replaced, and another game is identified.
        dir.put(EspnLeague("soccer", "eng.2"), listOf(team("Burnley", "Burnley", "Burnley", "BUR")), 1L)
        book.put("Maccabi Tel Aviv", "BC Dubai", GameIdentity("basketball", "EuroLeague Basketball", null to null))
        assertTrue(inferred.holds())
    }

    @Test
    fun `a list the card read changing infers it again, with the directory's new answer`() {
        val dir = directory(EspnLeagueMap.NHL to nhl)
        val book = TeamIdentityBook()
        val item = card("Devils v Canadiens")
        val inferred = SportsLiveInferredSport.infer(item, dir, book)
        assertEquals("NHL", inferred.item.sport)

        // The NHL list no longer names the Canadiens: the card must not keep NHL.
        dir.put(EspnLeagueMap.NHL, nhl.filter { it.nickname != "Canadiens" }, 1L)
        assertFalse(inferred.holds())
        assertNull(SportsLiveInferredSport.infer(item, dir, book).item.sport)
    }

    @Test
    fun `a card routed to its league reads only that league's list`() {
        val dir = directory(EspnLeagueMap.NHL to nhl)
        val inferred = SportsLiveInferredSport.infer(card("Devils v Canadiens", sport = "NHL"), dir, TeamIdentityBook())
        assertEquals("NHL", inferred.item.sport)
        dir.put(EspnLeagueMap.NBA, listOf(team("Boston Celtics", "Celtics", "Boston", "BOS")), 1L)
        assertTrue(inferred.holds())
        dir.put(EspnLeagueMap.NHL, nhl, 2L)
        assertFalse(inferred.holds())
    }

    @Test
    fun `its own teams' identity learned infers it again`() {
        val dir = directory()
        val book = TeamIdentityBook()
        val item = card("Maccabi Tel Aviv vs BC Dubai")
        val inferred = SportsLiveInferredSport.infer(item, dir, book)
        assertNull(inferred.item.sport)

        book.put("Maccabi Tel Aviv", "BC Dubai", GameIdentity("basketball", "EuroLeague Basketball", null to null))
        assertFalse(inferred.holds())
        val again = SportsLiveInferredSport.infer(item, dir, book)
        assertEquals("Basketball", again.item.sport)
        assertTrue(again.holds())
    }

    @Test
    fun `a list dropped to stay bounded counts as changed for a card that read it`() {
        val dir = TeamDirectory(maxLeagues = 1, kept = emptyList())
        dir.put(EspnLeagueMap.NHL, nhl, 0L)
        val (_, read) = dir.reading { dir.fetchedAt(EspnLeagueMap.NHL) }
        assertTrue(dir.unchangedSince(read))
        dir.put(EspnLeagueMap.NBA, listOf(team("Boston Celtics", "Celtics", "Boston", "BOS")), 1L)
        assertNull(dir.fetchedAt(EspnLeagueMap.NHL))
        assertFalse(dir.unchangedSince(read))
    }
}
