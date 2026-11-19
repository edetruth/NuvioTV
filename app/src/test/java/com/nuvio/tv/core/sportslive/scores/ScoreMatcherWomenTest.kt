package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/** Women's feeds (#163): NCAAW games match plain cards, and a bare College card is told apart by its start. */
class ScoreMatcherWomenTest {
    private val matcher = ScoreMatcher(zone = { ZoneOffset.UTC })

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun team(id: String, abbreviation: String, displayName: String, short: String, name: String, location: String) =
        ScoreTeam(id, abbreviation, displayName, short, name, location, "https://example.invalid/$id.png", score = 4, linescores = emptyList())

    private val purdue = team("2509", "PUR", "Purdue Boilermakers", "Purdue", "Boilermakers", "Purdue")
    private val floridaState = team("52", "FSU", "Florida State Seminoles", "Florida St", "Seminoles", "Florida State")

    private fun game(id: String, league: EspnLeague, start: String) = ScoreSnapshot(
        eventId = id, league = league, startMs = ms(start), state = ScoreState.IN, clockText = "Q1",
        isFinal = false, home = floridaState, away = purdue
    )

    private val women = game("women", EspnLeagueMap.COLLEGE_BASKETBALL_WOMEN, "2026-11-19T19:00:00Z")
    private val men = game("men", EspnLeagueMap.COLLEGE_BASKETBALL, "2026-11-19T17:00:00Z")

    private fun match(title: String, start: String, events: List<ScoreSnapshot>) =
        matcher.match(ScoreCard("$title@$start", SportsLiveTeams.parse(title)!!, ms(start), hasTimeOfDay = true), events, ms(start))?.eventId

    @Test
    fun `a women's feed game matches a card with or without a women's marker`() {
        assertEquals("women", match("Purdue at Florida State", "2026-11-19T19:00:00Z", listOf(women)))
        assertEquals("women", match("Purdue (W) at Florida State (W)", "2026-11-19T19:00:00Z", listOf(women)))
        // Sides placed one way round, so the score shows.
        assertEquals(false, ScoreMatcher.firstIsHome("Purdue", "Florida State", women))
    }

    @Test
    fun `a bare college card picks NCAAW by start time when men's and women's games share the teams`() {
        assertEquals("women", match("Purdue at Florida State", "2026-11-19T19:00:00Z", listOf(men, women)))
        assertEquals("men", match("Purdue at Florida State", "2026-11-19T17:05:00Z", listOf(men, women)))
        // Halfway between: no score beats a wrong one.
        assertNull(match("Purdue at Florida State", "2026-11-19T18:00:00Z", listOf(men, women)))
    }

    @Test
    fun `men's cards are unchanged`() {
        // A women's side never takes the men's game; two men's games of one pair stay ambiguous.
        assertNull(match("Purdue (W) at Florida State (W)", "2026-11-19T17:00:00Z", listOf(men)))
        assertEquals("men", match("Purdue at Florida State", "2026-11-19T17:00:00Z", listOf(men)))
        val second = men.copy(eventId = "men2", startMs = ms("2026-11-19T19:00:00Z"))
        assertNull(match("Purdue at Florida State", "2026-11-19T19:00:00Z", listOf(men, second)))
    }

    @Test
    fun `a College card on both basketball feeds gets the school logos`() {
        val title = "Purdue at Florida State"
        val card = SportsLiveItem(
            meta = MetaPreview(
                id = "1", type = ContentType.UNKNOWN, rawType = "sport", name = title, poster = null,
                posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
                releaseInfo = null, imdbRating = null, genres = listOf("COLLEGE")
            ),
            addonId = "a", addonName = "A", addonBaseUrl = "https://example.invalid", catalogKey = "k",
            catalogName = "Live", kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false, startMs = null,
            sport = "COLLEGE", teams = SportsLiveTeams.parse(title)
        )
        fun listed(team: ScoreTeam) = DirectoryTeam(team.id!!, team.displayName!!, team.shortDisplayName, team.abbreviation, team.location, team.name, team.logo!!)
        val schools = listOf(listed(purdue), listed(floridaState))
        val dir = TeamDirectory()
        dir.put(EspnLeagueMap.COLLEGE_BASKETBALL, schools, 0L)
        dir.put(EspnLeagueMap.COLLEGE_BASKETBALL_WOMEN, schools, 0L)
        dir.put(EspnLeagueMap.COLLEGE_FOOTBALL, emptyList(), 0L)
        val (first, second) = dir.forCard(card, "Purdue", "Florida State")!!
        assertEquals("https://example.invalid/2509.png", first.logo)
        assertEquals("FSU", second.abbreviation)
    }
}
