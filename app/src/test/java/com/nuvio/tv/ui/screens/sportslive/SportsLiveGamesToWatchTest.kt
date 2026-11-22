package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.BigGameRating
import com.nuvio.tv.core.sportslive.scores.BigGameReason
import com.nuvio.tv.core.sportslive.scores.BigGameReasonKind
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoreTeam
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Upcoming's Games to watch (#137): picks at the top of each day, the badge, and the board's big games. */
class SportsLiveGamesToWatchTest {

    private val zone = ZoneId.of("UTC")
    private val now = ZonedDateTime.of(2026, 11, 18, 13, 9, 0, 0, zone).toInstant().toEpochMilli()

    private fun at(day: Int, hour: Int): Long = ZonedDateTime.of(2026, 11, day, hour, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun item(name: String, startMs: Long, sport: String = "College Basketball") = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = listOf(sport)
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Events",
        kind = SportsLiveKind.EVENTS, isLive = false, isChannel = false,
        startMs = startMs, sport = sport, startHasTimeOfDay = true,
        teams = SportsLiveTeams.parse(name)
    )

    private fun rating(score: Double, a: Int, b: Int) = BigGameRating(score, listOf(BigGameReason(BigGameReasonKind.RANKS, a = a, b = b)))

    private fun rows(vararg games: Pair<SportsLiveItem, BigGameRating?>): List<ScoreboardRow> {
        val built = scoreboardRows(games.map { it.first }, SportsLiveTab.UPCOMING, emptyMap(), null, emptySet(), now)
        val ratings = games.associate { it.first.streamKey to it.second }
        return built.map { row -> row.copy(bigGame = ratings[row.item.streamKey]) }
    }

    @Test
    fun `each day opens with its biggest games, at most three, which stay in their slots too`() {
        val agenda = SportsLiveAgenda.of(
            rows(
                item("Kansas vs Houston", at(18, 23)) to rating(8.0, 8, 11),
                item("Florida vs Duke", at(18, 20)) to rating(9.0, 1, 2),
                item("Iowa vs Purdue", at(18, 19)) to null,
                item("UCLA vs Arizona", at(18, 21)) to rating(4.0, 14, 20),
                item("Gonzaga vs Baylor", at(18, 22)) to rating(5.0, 9, 13),
                item("Texas vs Utah", at(19, 19)) to rating(6.0, 4, 5)
            ),
            now,
            zone
        )
        val today = agenda.games.filter { it.day == 0 }
        val picks = today.filter { it.pick }
        assertEquals(listOf("Florida vs Duke", "Kansas vs Houston", "Gonzaga vs Baylor"), picks.map { it.row.item.meta.name })
        // The picks come first, in their own slot, then every game by time (the picks again among them).
        assertTrue(today.take(3).all { it.pick })
        assertEquals(5, today.count { !it.pick })
        val pickSlot = agenda.slots[picks.first().slot]
        assertTrue(pickSlot.picks)
        assertNull(pickSlot.startMs)
        assertEquals(3, pickSlot.count)
        // Tomorrow has its own.
        assertEquals(listOf("Texas vs Utah"), agenda.games.filter { it.day == 1 && it.pick }.map { it.row.item.meta.name })
        // The list positions still count each day header once.
        val tomorrowFirst = agenda.games.indexOfFirst { it.day == 1 }
        assertEquals(tomorrowFirst + 2, agenda.listIndexOf(tomorrowFirst))
    }

    @Test
    fun `a day without big games has no Games to watch`() {
        val agenda = SportsLiveAgenda.of(rows(item("Iowa vs Purdue", at(18, 19)) to null), now, zone)
        assertTrue(agenda.games.none { it.pick })
        assertTrue(agenda.slots.none { it.picks })
    }

    @Test
    fun `from the evening Today's picks are Tonight's best`() {
        val today = AgendaDay(AgendaDayKind.TODAY, null)
        assertFalse(agendaTonight(today, now, zone))
        assertTrue(agendaTonight(today, at(18, TONIGHT_FROM_HOUR), zone))
        assertFalse(agendaTonight(AgendaDay(AgendaDayKind.TOMORROW, null), at(18, 20), zone))
    }

    @Test
    fun `a big game in play gets no slot of its own, only followed teams then scored games lead (live 25)`() {
        val game = item("Florida vs Duke", at(18, 12))
        assertEquals(LINEUP_RANK_FOLLOWED, lineupRankOf(game.copy(followed = true), liveScored = true))
        assertEquals(LINEUP_RANK_SCORED, lineupRankOf(game, liveScored = true))
        assertEquals(LINEUP_RANK_REST, lineupRankOf(game, liveScored = false))
    }

    @Test
    fun `the board rates upcoming games it matched, and badges only the big ones`() {
        fun team(name: String, rank: Int?) = ScoreTeam(null, null, name, name, name, null, null, null, emptyList(), rank = rank)
        val big = item("Florida vs Duke", at(18, 20))
        val small = item("Iowa vs Purdue", at(18, 19))
        fun event(id: String, home: ScoreTeam, away: ScoreTeam) = ScoreSnapshot(
            eventId = id, league = EspnLeagueMap.COLLEGE_BASKETBALL, startMs = at(18, 20), state = ScoreState.PRE,
            clockText = null, isFinal = false, home = home, away = away
        )
        val scores = mapOf(
            big.streamKey to event("1", team("Florida", 1), team("Duke", 2)),
            small.streamKey to event("2", team("Iowa", null), team("Purdue", null))
        )
        val rated = SportsLiveBoard.withBigGames(rows(big to null, small to null), scores, emptyMap())
        assertEquals("Florida vs Duke", rated.single { it.bigGame != null }.item.meta.name)
        assertEquals("1", rated.single { it.bigGame != null }.bigGame?.eventId)
    }

    @Test
    fun `two cards of one game are one pick, and records read in each card's order`() {
        fun team(name: String, record: String) = ScoreTeam(null, null, name, name, name, null, null, null, emptyList(), record = record)
        val first = item("Houston Rockets vs Denver Nuggets", at(18, 20))
        val second = item("Denver Nuggets vs Houston Rockets", at(18, 20)).copy(addonId = "b")
        // Rockets at Nuggets: ESPN reads the away team first.
        val event = ScoreSnapshot(
            eventId = "9", league = EspnLeagueMap.NBA, startMs = at(18, 20), state = ScoreState.PRE, clockText = null, isFinal = false,
            home = team("Denver Nuggets", "8-5"), away = team("Houston Rockets", "12-4"), broadcasts = listOf("Prime Video"), headline = "NBA Cup - Group Play"
        )
        val rated = SportsLiveBoard.withBigGames(rows(first to null, second to null), mapOf(first.streamKey to event, second.streamKey to event), emptyMap())
        val lines = rated.map { row -> row.bigGame!!.reasons.first { it.kind == BigGameReasonKind.RECORDS }.text }
        assertEquals(listOf("12-4 vs 8-5", "8-5 vs 12-4"), lines)
        val agenda = SportsLiveAgenda.of(rated, now, zone)
        assertEquals(1, agenda.games.count { it.pick })
    }
}
