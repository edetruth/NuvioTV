package com.nuvio.tv.core.sportslive.alerts

import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.MINUTE
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.START
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.final
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.item
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.live
import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.ScoreChips
import com.nuvio.tv.core.sportslive.scores.ScoreSide
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.domain.model.FollowedTeam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsLiveAlertContentTest {

    // "Pacers @ 76ers": the card names the away side (Pacers) first.
    private val card = item(followed = true)

    @Test
    fun `full time under Show has the score in the card's order and ESPN's note`() {
        val content = SportsLiveAlertContent.of(SportsLiveAlert.FullTime(card, final(home = 101, away = 99), namesOnly = false))
        content as AlertContent.FullTime
        assertEquals(99, content.first.score)
        assertEquals(101, content.second.score)
        assertEquals("IND", content.first.short)
        assertEquals("PHI", content.second.short)
        assertEquals("Final/OT", content.note)
        // The card draws the game grammar's words (live.24), never ESPN's raw "Final/OT".
        assertEquals(false, content.fullTimeSport)
        assertEquals("Final · OT", ScoreChips.finalWords(content.note, "Final", fullTimeSport = content.fullTimeSport))
    }

    @Test
    fun `full time under Hide until focused has names only, no score and no OT`() {
        val content = SportsLiveAlertContent.of(SportsLiveAlert.FullTime(card, final(home = 101, away = 99), namesOnly = true))
        content as AlertContent.FullTimeNamesOnly
        assertEquals("Indiana Pacers", content.first.name)
        assertEquals("Philadelphia 76ers", content.second.name)
        assertNull(content.first.score)
        assertNull(content.second.score)
    }

    @Test
    fun `under Off there is no full-time alert at all`() {
        val engine = SportsLiveAlertEngine()
        val game = { s: ScoreSnapshot -> listOf(AlertGame(card, s, false, setOf(ScoreSide.HOME))) }
        engine.update(START, game(live(1, 1)), SportsLiveAlertSettings(), ScoresMode.OFF, SportsLiveWatching())
        val alerts = engine.update(START + MINUTE, game(final(2, 1)), SportsLiveAlertSettings(), ScoresMode.OFF, SportsLiveWatching())
        assertTrue(alerts.isEmpty())
    }

    @Test
    fun `a score toast names the side that scored, in the card's order`() {
        val content = SportsLiveAlertContent.of(SportsLiveAlert.ScoreChange(card, live(home = 10, away = 11), ScoreSide.AWAY))
        content as AlertContent.Score
        assertEquals("Indiana Pacers", content.scorer?.name)
        assertEquals(11, content.first.score)
        assertEquals(10, content.second.score)
        assertEquals("Q2 5:00", content.clock)
    }

    @Test
    fun `the kickoff prompt carries both sides and the followed star`() {
        val content = SportsLiveAlertContent.of(SportsLiveAlert.Kickoff(card, null)) as AlertContent.Kickoff
        assertEquals("Pacers", content.first.name)
        assertEquals("76ers", content.second.name)
        assertTrue(content.followed)
    }

    @Test
    fun `followed sides come from ESPN data, strictly`() {
        val celtics = FollowedTeam("Boston Celtics", "Basketball")
        val pacers = FollowedTeam("Indiana Pacers", "Basketball", espnId = AlertFixtures.pacersFinal.away.id, league = "basketball/nba")
        assertEquals(setOf(ScoreSide.AWAY), SportsLiveAlerts.followedSides(listOf(celtics, pacers), live(1, 1)))
        assertEquals(emptySet<ScoreSide>(), SportsLiveAlerts.followedSides(listOf(celtics), live(1, 1)))
    }

    // Tracking scope (#42): followed teams' live games in the followed teams' leagues only.

    @Test
    fun `followed leagues come from the stored ESPN league, else the sport word`() {
        val leagues = SportsLiveAlertScope.followedLeagues(
            listOf(
                FollowedTeam("Arsenal", espnId = "359", league = "soccer/eng.1"),
                FollowedTeam("Celtics", "Basketball"),
                FollowedTeam("Mystery")
            )
        )
        assertEquals(setOf(EspnLeague("soccer", "eng.1"), EspnLeagueMap.NBA), leagues)
    }

    @Test
    fun `only followed games in followed leagues, live or kicking off soon, are tracked`() {
        val followed = listOf(FollowedTeam("Pacers", "Basketball"))
        val now = START - 10 * MINUTE
        val kickingOff = item(followed = true)
        val later = item(name = "Pacers @ Knicks", followed = true, startMs = START + 3 * 60 * MINUTE)
        val notFollowed = item(name = "Bulls @ Bucks", startMs = START)
        val otherLeague = item(name = "Pacers vs Colts", followed = true, sport = "American Football")
        val live = item(name = "Pacers @ Heat", followed = true, isLive = true, startMs = START - 60 * MINUTE)
        val tracked = SportsLiveAlertScope.cardsToTrack(listOf(kickingOff, later, notFollowed, otherLeague, live), followed, now)
        assertEquals(listOf(live, kickingOff), tracked)
    }

    @Test
    fun `nothing is tracked without a followed team that has a league`() {
        assertEquals(emptyList<Any>(), SportsLiveAlertScope.cardsToTrack(listOf(item(followed = true)), emptyList(), START))
        assertEquals(emptyList<Any>(), SportsLiveAlertScope.cardsToTrack(listOf(item(followed = true)), listOf(FollowedTeam("Pacers")), START))
    }

    @Test
    fun `tracking is capped`() {
        val followed = listOf(FollowedTeam("Pacers", "Basketball"))
        val cards = (0 until 12).map { item(name = "Pacers @ Team$it", id = "g$it", followed = true, isLive = true) }
        assertEquals(SportsLiveAlertScope.MAX_CARDS, SportsLiveAlertScope.cardsToTrack(cards, followed, START).size)
    }

    @Test
    fun `a followed game ESPN has ended is no longer tracked`() {
        val followed = listOf(FollowedTeam("Pacers", "Basketball"))
        val live = item(followed = true, isLive = true)
        assertEquals(listOf(live), SportsLiveAlertScope.cardsToTrack(listOf(live), followed, START))
        assertEquals(emptyList<Any>(), SportsLiveAlertScope.cardsToTrack(listOf(live), followed, START, ended = setOf(SportsLiveAlertEngine.gameKey(live))))
    }

    @Test
    fun `a due reminder asks for a load only when this session has none`() {
        val reminder = GameReminders.of(item())!!
        assertTrue(SportsLiveAlertScope.needsLoad(listOf(reminder), hasLoad = false, nowMs = START - 30_000))
        assertEquals(false, SportsLiveAlertScope.needsLoad(listOf(reminder), hasLoad = true, nowMs = START))
        assertEquals(false, SportsLiveAlertScope.needsLoad(listOf(reminder), hasLoad = false, nowMs = START - 10 * MINUTE))
        assertEquals(false, SportsLiveAlertScope.needsLoad(listOf(reminder), hasLoad = false, nowMs = START + 30 * MINUTE))
        assertEquals(false, SportsLiveAlertScope.needsLoad(emptyList(), hasLoad = false, nowMs = START))
    }
}
