package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.SportsLiveDisplayText
import com.nuvio.tv.core.sportslive.SportsLiveTags
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.TeamPair
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures
import com.nuvio.tv.core.sportslive.scores.GameDay
import com.nuvio.tv.domain.model.SportsLiveKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale

/**
 * #210: a replays addon titles games "Athletic Club vs Espanyol Spain La Liga Football download free". The tail came
 * with the second team (no logos, a generic SOCCER tag); and a replay under Today read "Yesterday" on its stage.
 */
class SportsLiveReplayTitleTailsTest {
    private val athletic = "Athletic Club vs Espanyol Spain La Liga Football download free"
    private val bayern = "Bayern Munchen vs Koln Bundesliga Football download free"

    private fun replay(name: String, sport: String? = "Football", startMs: Long? = AlertFixtures.START, timed: Boolean = startMs != null) =
        AlertFixtures.item(name = name, id = name, sport = sport, startMs = startMs)
            .copy(kind = SportsLiveKind.REPLAY, startHasTimeOfDay = timed)

    private fun tagOf(row: ScoreboardRow): String =
        SportsLiveTags.short(scoreboardRowTag(row.art?.league, row.item.sport, row.item.catalogName), null)

    @Test
    fun `an addon's marketing tail comes off every title`() {
        assertEquals("Athletic Club vs Espanyol Spain La Liga Football", SportsLiveDisplayText.name(athletic))
        assertEquals("Bayern Munchen vs Koln Bundesliga Football", SportsLiveDisplayText.name(bayern))
        val epl = SportsLiveDisplayText.of("Arsenal vs Chelsea English Premier League Full Match 720p")
        assertEquals("Arsenal vs Chelsea English Premier League", epl.name)
        assertEquals("HD", epl.quality)
        assertEquals("Lakers vs Celtics NBA", SportsLiveDisplayText.name("Lakers vs Celtics NBA Replay HD"))
        assertEquals("Juventus vs Inter Italy Serie A", SportsLiveDisplayText.name("Juventus vs Inter Italy Serie A (Highlights)"))
        assertEquals("FHD", SportsLiveDisplayText.of("PSG vs Lyon France Ligue 1 1080p").quality)
    }

    @Test
    fun `a show's own words stay when the title names no game`() {
        assertEquals("NBA Highlights", SportsLiveDisplayText.name("NBA Highlights"))
        assertEquals("Match of the Day Replay", SportsLiveDisplayText.name("Match of the Day Replay"))
        assertEquals("Free Agency Special", SportsLiveDisplayText.name("Free Agency Special"))
        // "Download" never names anything; still nothing is left empty.
        assertEquals("Download", SportsLiveDisplayText.name("Download"))
    }

    @Test
    fun `the two sides are the teams alone, the country, league and sport word off`() {
        assertEquals(TeamPair("Athletic Club", "Espanyol"), SportsLiveTeams.parse(athletic))
        assertEquals(TeamPair("Bayern Munchen", "Koln"), SportsLiveTeams.parse(bayern))
        assertEquals(TeamPair("Club América", "Cruz Azul"), SportsLiveTeams.parse("Club América vs Cruz Azul Mexico Liga MX Soccer Highlights"))
        assertEquals(TeamPair("Arsenal", "Chelsea"), SportsLiveTeams.parse("Arsenal vs Chelsea English Premier League Full Match 720p"))
        assertEquals(TeamPair("Lakers", "Celtics"), SportsLiveTeams.parse("Lakers vs Celtics NBA 1080p"))
        assertEquals(TeamPair("Real Sociedad", "Inter Miami"), SportsLiveTeams.parse("Real Sociedad vs Inter Miami Full Match Replay"))
        assertEquals("Chelsea", SportsLiveDisplayText.teamSide("Chelsea Football"))
        // #216: a trailing Cricket is the sport, never part of the team.
        assertEquals(TeamPair("NEW", "India"), SportsLiveTeams.parse("NEW vs India Cricket"))
        // A club named after its sport keeps it.
        assertEquals("Dubai Basketball", SportsLiveDisplayText.teamSide("Dubai Basketball"))
        assertEquals("Cricket", SportsLiveDisplayText.teamSide("Cricket"))
    }

    @Test
    fun `a club's own words are never taken for a tail`() {
        for (team in listOf("Real Sociedad", "Inter Miami", "Club América", "Athletic Club", "Real Madrid", "Free State Stars", "Football")) {
            assertEquals(team, SportsLiveDisplayText.teamSide(team))
        }
        // A competition alone is no team: it stays as written.
        assertEquals("La Liga", SportsLiveDisplayText.teamSide("La Liga"))
        assertEquals("English Premier League", SportsLiveDisplayText.teamSide("English Premier League"))
        assertNull(SportsLiveDisplayText.competitionSuffix("La Liga"))
    }

    @Test
    fun `a replay's league is the one its title names, and its tag reads it`() {
        val (spain, germany, england) = replayRows(
            listOf(replay(athletic), replay(bayern), replay("Arsenal vs Chelsea English Premier League Full Match"))
        )
        val art = spain.art!!
        assertEquals("Athletic Club", art.first.name)
        assertEquals("Espanyol", art.second.name)
        assertEquals("La Liga", art.league)
        assertEquals("LA LIGA", tagOf(spain))
        assertEquals("Koln", germany.art!!.second.name)
        assertEquals("BUNDES", tagOf(germany))
        assertEquals("Chelsea", england.art!!.second.name)
        assertEquals("EPL", tagOf(england))
        // No league written: the card's sport, as before.
        assertEquals("Football", replayRows(listOf(replay("Arsenal vs Chelsea Full Match"))).single().art!!.league)
    }

    @Test
    fun `a replay listed under Today names Today on its stage`() {
        val eastern = ZoneId.of("America/New_York")
        val now = ZonedDateTime.of(2026, 11, 20, 10, 0, 0, 0, eastern).toInstant().toEpochMilli()
        // The addon's bare date "2026-11-20" sent as midnight UTC: 7 PM on the 19th in New York.
        val midnight = LocalDate.of(2026, 11, 20).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val item = replay(athletic, startMs = midnight, timed = true)
        val agenda = SportsLiveAgenda.ofReplays(replayRows(listOf(item)) { _, _ -> null }, now, eastern)
        assertEquals(listOf(AgendaDayKind.TODAY), agenda.days.map { it.kind })
        val listed = agenda.dayOf(agenda.items.single())
        assertEquals(LocalDate.of(2026, 11, 20), listed)
        assertEquals(AgendaDayKind.TODAY, replayWhenDay(listed!!, now, eastern))
        // The stage's own fallback reads the same day, and the bare date shows no "7:00 PM".
        assertEquals(AgendaDayKind.TODAY, replayWhenDay(GameDay.of(item, null, eastern)!!, now, eastern))
        assertNull(replayTimeText(item, eastern, Locale.US))
        // A real time of day still shows.
        assertEquals("8:00 AM", replayTimeText(replay(athletic, startMs = now - 2 * 3_600_000L), eastern, Locale.US))
    }
}
