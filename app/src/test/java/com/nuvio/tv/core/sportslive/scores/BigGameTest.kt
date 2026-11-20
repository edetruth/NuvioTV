package com.nuvio.tv.core.sportslive.scores

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The big-game score per sport (#137), rank display, Games to watch and the reason lines. */
class BigGameTest {

    private fun team(
        name: String,
        id: String? = null,
        rank: Int? = null,
        record: String? = null,
        form: String? = null
    ) = ScoreTeam(id, null, name, name, name, null, null, null, emptyList(), record = record, rank = rank, form = form)

    private fun game(
        league: EspnLeague,
        away: ScoreTeam,
        home: ScoreTeam,
        broadcasts: List<String> = emptyList(),
        headline: String? = null,
        seasonType: Int? = 2,
        spread: Double? = null,
        individual: IndividualResult? = null
    ) = ScoreSnapshot(
        eventId = "${away.displayName}@${home.displayName}",
        league = league,
        startMs = 0L,
        state = ScoreState.PRE,
        clockText = null,
        isFinal = false,
        home = home,
        away = away,
        broadcasts = broadcasts,
        headline = headline,
        seasonType = seasonType,
        spread = spread,
        individual = individual
    )

    private val strings = BigGameStrings(
        versus = "%1\$s vs %2\$s",
        topFour = "Top-4 clash",
        relegation = "Relegation battle",
        derby = "Derby",
        playoffs = "Playoffs",
        separator = " · ",
        ordinal = BigGameText::englishOrdinal
    )

    private fun line(rating: BigGameRating) = BigGameText.line(rating.reasons, strings)

    private val cbb = EspnLeagueMap.COLLEGE_BASKETBALL

    // ---- College ----

    @Test
    fun `college - two ranked teams make a big game, the higher the ranks the bigger`() {
        val top = BigGame.rate(game(cbb, team("Florida", rank = 1), team("Houston", rank = 8)))
        val lower = BigGame.rate(game(cbb, team("Kansas", rank = 18), team("Texas", rank = 24)))
        assertTrue(top.isBig)
        assertTrue(top.score > lower.score)
        assertEquals("#1 vs #8", line(top))
    }

    @Test
    fun `college - the reason line adds national TV and the event's name`() {
        val rating = BigGame.rate(
            game(cbb, team("Kansas", rank = 11), team("Houston", rank = 8), broadcasts = listOf("ESPN"), headline = "Players Era 8 - Championship")
        )
        assertEquals("#8 vs #11 · ESPN · Players Era 8 - Championship", line(rating))
    }

    @Test
    fun `college - one ranked team is named, unranked games are not big`() {
        val one = BigGame.rate(game(cbb, team("Southern Utah"), team("Texas", rank = 6)))
        assertEquals("#6 Texas", line(one))
        assertFalse(one.isBig)
        // ESPN's 99 is "unranked" and never reaches a team ([EspnScoreProvider.rankOf]).
        assertNull(EspnScoreProvider.rankOf(EspnCuratedRankDto(99)))
        assertEquals(0.0, BigGame.rate(game(cbb, team("A"), team("B"))).score, 0.0)
    }

    // ---- Pro ----

    @Test
    fun `pro - two winning records and two top places are big, records read away first`() {
        val nba = EspnLeagueMap.NBA
        val standings = mapOf("1" to TeamStanding(1, 15, "East"), "2" to TeamStanding(3, 15, "East"))
        val rating = BigGame.rate(game(nba, team("Cavaliers", "2", record = "12-3"), team("Grizzlies", "1", record = "11-4")), standings)
        assertTrue(rating.isBig)
        assertEquals("1st vs 3rd · 12–3 vs 11–4", line(rating))
    }

    @Test
    fun `pro - a losing record or too few games is no big game, the postseason is`() {
        val nfl = EspnLeagueMap.NFL
        assertFalse(BigGame.rate(game(nfl, team("A", record = "8-7"), team("B", record = "11-4"))).isBig)
        assertFalse(BigGame.rate(game(nfl, team("A", record = "2-0"), team("B", record = "3-0"))).isBig)
        val playoffs = BigGame.rate(game(nfl, team("A", record = "8-7"), team("B", record = "9-6"), seasonType = 3, broadcasts = listOf("CBS")))
        assertTrue(playoffs.isBig)
        assertEquals("Playoffs · CBS", line(playoffs))
    }

    @Test
    fun `pro - a regional channel adds nothing, a close spread adds a little`() {
        val nba = EspnLeagueMap.NBA
        val base = BigGame.rate(game(nba, team("A"), team("B"))).score
        assertEquals(base, BigGame.rate(game(nba, team("A"), team("B"), broadcasts = listOf("DAZN MEM", "KUNP 16"))).score, 0.0)
        assertTrue(BigGame.rate(game(nba, team("A"), team("B"), spread = -2.5)).score > base)
        assertEquals(base, BigGame.rate(game(nba, team("A"), team("B"), spread = -6.5)).score, 0.0)
    }

    @Test
    fun `a followed team is boosted, never made big by that alone`() {
        val nba = EspnLeagueMap.NBA
        val plain = BigGame.rate(game(nba, team("A"), team("B")))
        val followed = BigGame.rate(game(nba, team("A"), team("B")), followed = true)
        assertTrue(followed.score > plain.score)
        assertFalse(followed.isBig)
    }

    // ---- Soccer ----

    private val epl = EspnLeague("soccer", "eng.1")

    @Test
    fun `soccer - a title clash, a top-four race and a relegation six-pointer`() {
        fun rated(home: Int, away: Int) = BigGame.rate(
            game(epl, team("Away", "a"), team("Home", "h")),
            mapOf("h" to TeamStanding(home, 20, null), "a" to TeamStanding(away, 20, null))
        )
        assertEquals("1st vs 2nd", line(rated(1, 2)))
        assertTrue(rated(1, 2).isBig)
        assertEquals("Top-4 clash", line(rated(3, 5)))
        assertEquals("Relegation battle", line(rated(17, 19)))
        assertEquals("", line(rated(9, 12)))
    }

    @Test
    fun `soccer - a curated derby is big, form adds a little`() {
        val derby = BigGame.rate(game(epl, team("Tottenham Hotspur"), team("Arsenal")), emptyMap())
        assertEquals("Derby", line(derby))
        assertTrue(BigGame.isDerby(game(epl, team("Everton"), team("Liverpool"))))
        assertFalse(BigGame.isDerby(game(epl, team("Everton"), team("Arsenal"))))
        val inForm = BigGame.rate(game(epl, team("A", form = "WWWWW"), team("B", form = "WWWDW")))
        val outOfForm = BigGame.rate(game(epl, team("A", form = "LLLLL"), team("B", form = "LLDLL")))
        assertTrue(inForm.score > outOfForm.score)
    }

    // ---- Tennis ----

    private fun tennis(first: Int?, second: Int?, round: String?) = TennisMatch(
        round,
        listOf(TennisPlayer("Jannik Sinner", false, emptyList(), rank = first), TennisPlayer("Carlos Alcaraz", false, emptyList(), rank = second)),
        TennisEnding.COMPLETED,
        live = true
    )

    @Test
    fun `tennis - both rankings and the round, a final weighing most`() {
        val atp = EspnLeagueMap.ATP
        val semi = BigGame.rate(game(atp, team("x"), team("y"), individual = tennis(2, 1, "Semifinal")))
        assertTrue(semi.isBig)
        assertEquals("#1 vs #2 · Semifinal", line(semi))
        val final = BigGame.rate(game(atp, team("x"), team("y"), individual = tennis(2, 1, "Final")))
        val early = BigGame.rate(game(atp, team("x"), team("y"), individual = tennis(2, 1, "Round 1")))
        assertTrue(final.score > semi.score && semi.score > early.score)
        assertFalse(BigGame.rate(game(atp, team("x"), team("y"), individual = tennis(140, 212, "Round 1"))).isBig)
        assertEquals(1, BigGame.roundPoints("Quarterfinal"))
        assertEquals(0, BigGame.roundPoints("Group Stage"))
    }

    // ---- Rank display ----

    @Test
    fun `ranks show for ranked college teams anywhere, tennis off the rows, pro never`() {
        assertEquals(3, RankDisplay.rank(cbb, 3))
        assertEquals(25, RankDisplay.rank(EspnLeagueMap.COLLEGE_FOOTBALL, 25))
        assertNull(RankDisplay.rank(cbb, 26))
        assertNull(RankDisplay.rank(cbb, null))
        assertNull(RankDisplay.rank(EspnLeagueMap.NBA, 3))
        assertEquals(140, RankDisplay.rank(EspnLeagueMap.ATP, 140))
        assertTrue(RankDisplay.onRows(cbb))
        assertFalse(RankDisplay.onRows(EspnLeagueMap.ATP))
        assertFalse(RankDisplay.onRows(EspnLeagueMap.NFL))
        assertEquals("#3", RankDisplay.label(3))
    }

    @Test
    fun `a drawn card carries a ranked college team's rank, a pro team's never`() {
        val event = game(cbb, team("Arizona", rank = 12), team("UConn", rank = 3))
        val art = GameArt.ofEvent(event)
        assertEquals(12, art.first.rank)
        assertEquals(3, art.second.rank)
        assertTrue(art.ranksOnRows)
        val pro = GameArt.ofEvent(game(EspnLeagueMap.NBA, team("A", rank = 3), team("B")))
        assertNull(pro.first.rank)
        assertFalse(pro.ranksOnRows)
    }

    // ---- Games to watch ----

    @Test
    fun `games to watch - at most three, biggest first, only big games with a reason`() {
        val games = listOf(
            game(cbb, team("A", rank = 20), team("B", rank = 22)),
            game(cbb, team("C", rank = 1), team("D", rank = 2)),
            game(cbb, team("E"), team("F")),
            game(cbb, team("G", rank = 5), team("H", rank = 9)),
            game(cbb, team("I", rank = 10), team("J", rank = 12))
        )
        val picks = GamesToWatch.pick(games) { BigGame.rate(it) }
        assertEquals(GamesToWatch.MAX, picks.size)
        assertEquals(listOf("C@D", "G@H", "I@J"), picks.map { "${it.first.away.displayName}@${it.first.home.displayName}" })
        assertEquals(listOf("#1 vs #2", "#5 vs #9", "#10 vs #12"), picks.map { line(it.second) })
        assertTrue(GamesToWatch.pick(listOf(games[2])) { BigGame.rate(it) }.isEmpty())
    }

    @Test
    fun `games to watch - a followed team's game needs to be big too`() {
        val plain = game(EspnLeagueMap.NBA, team("A"), team("B"))
        assertTrue(GamesToWatch.pick(listOf(plain)) { BigGame.rate(it, followed = true) }.isEmpty())
    }

    @Test
    fun `ordinals and long event names read well`() {
        assertEquals(listOf("1st", "2nd", "3rd", "4th", "11th", "12th", "13th", "21st", "22nd"), listOf(1, 2, 3, 4, 11, 12, 13, 21, 22).map(BigGameText::englishOrdinal))
        assertEquals("Players Era 8", BigGame.eventName(" Players Era 8 "))
        assertNull(BigGame.eventName(" "))
        assertEquals("ESPN", BigGame.nationalNetwork(listOf("ESPN+", "ESPN")))
        assertNull(BigGame.nationalNetwork(listOf("SECN+", "BlazerVision")))
    }
}
