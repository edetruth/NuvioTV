package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.ScoreCard
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoreTeam
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** live.27, TV reports #29-#36 (2026-11-23): "A vs B" listings that are no game in progress leave Live now for Other streams. */
class SportsLiveOtherStreamsTest {
    private val now = Instant.parse("2026-11-23T23:16:00Z").toEpochMilli()

    private fun live(name: String, addon: String = "a", channel: Boolean = false, kind: SportsLiveKind = SportsLiveKind.EVENTS) = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "tv", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = addon, addonName = addon, addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
        kind = kind, isLive = true, isChannel = channel, startMs = null, sport = null, teams = SportsLiveTeams.parse(name)
    )

    /** What the score repository records for [item] when ESPN has no game for it today. */
    private fun SportsLiveNoEspnGames.judge(item: SportsLiveItem, at: Long = now) {
        val card = ScoreCard.from(item)
        assertNotNull(item.meta.name, card)
        record(listOf(item.streamKey), card!!.teams, at)
    }

    // Tonight's list as the TV showed it (names as the sources wrote them), and the one real game in play.
    private val espnJudged = listOf(
        "Bayern München vs Hoffenheim", "Real Madrid vs Inter", "Real Madrid vs Celta, fudbal", "Newcastle vs Arsenal",
        "Arsenal vs Chelsea", "Aston Villa vs Sunderland", "MUN vs KÖL", "Fenerbahce vs Liverpool"
    )
    private val namedNonGames = listOf("Liverpool vs Manchester Utd: The Match", "Utah Jazz vs LA Lakers 2026-27", "Kosárlabda vs NBA")
    private val realGame = "BYU vs Washington"

    @Test
    fun `tonight's list keeps only the real game live, every other row goes to Other streams`() {
        val verdicts = SportsLiveNoEspnGames()
        val rows = (espnJudged + namedNonGames + realGame).mapIndexed { i, name -> live(name, addon = "s$i") }
        rows.filter { it.meta.name in espnJudged }.forEach { verdicts.judge(it) }
        val (games, others) = SportsLiveOtherStreams.split(rows, now, verdicts, SportsLiveMatchedEvents())
        assertEquals(listOf(realGame), games.map { it.meta.name })
        assertEquals(espnJudged + namedNonGames, others.map { it.meta.name })
    }

    @Test
    fun `with no ESPN verdict, only the rows whose titles say so move`() {
        // ESPN's feed failed, or the league is not covered: the score repository records nothing (fail open).
        val rows = (espnJudged + namedNonGames + realGame).mapIndexed { i, name -> live(name, addon = "s$i") }
        val (games, others) = SportsLiveOtherStreams.split(rows, now, SportsLiveNoEspnGames(), SportsLiveMatchedEvents())
        assertEquals(espnJudged + realGame, games.map { it.meta.name })
        assertEquals(namedNonGames, others.map { it.meta.name })
    }

    @Test
    fun `a season closing the title is a pass, a date or a competition's season is not`() {
        assertTrue(SportsLiveOtherStreams.isSeasonTitle("Utah Jazz vs LA Lakers 2026-27"))
        assertTrue(SportsLiveOtherStreams.isSeasonTitle("Arsenal vs Chelsea 2025/26"))
        assertTrue(SportsLiveOtherStreams.isSeasonTitle("Celtics vs Knicks 2026-2027"))
        assertFalse(SportsLiveOtherStreams.isSeasonTitle("Arsenal vs Chelsea 2026-11-23"))
        assertFalse(SportsLiveOtherStreams.isSeasonTitle("NBA 2026-27: Jazz vs Lakers"))
        assertFalse(SportsLiveOtherStreams.isSeasonTitle("Arsenal vs Chelsea 2026-28"))
        assertFalse(SportsLiveOtherStreams.isSeasonTitle("Arsenal vs Chelsea"))
    }

    @Test
    fun `a show after the colon is no game, a real game's subtitle is`() {
        assertTrue(SportsLiveOtherStreams.isShowSubtitle("Liverpool vs Manchester Utd: The Match"))
        assertTrue(SportsLiveOtherStreams.isShowSubtitle("Arsenal vs Chelsea: Extended Highlights"))
        assertTrue(SportsLiveOtherStreams.isShowSubtitle("Lakers vs Celtics: Full Match Replay"))
        assertFalse(SportsLiveOtherStreams.isShowSubtitle("Arsenal vs Chelsea: Premier League"))
        assertFalse(SportsLiveOtherStreams.isShowSubtitle("Real Madrid vs Barcelona: El Clásico"))
        assertFalse(SportsLiveOtherStreams.isShowSubtitle("Michigan vs Ohio State: The Game"))
        assertFalse(SportsLiveOtherStreams.isShowSubtitle("Lakers vs Celtics: NBA Cup Quarterfinal"))
        assertFalse(SportsLiveOtherStreams.isShowSubtitle("Arsenal vs Chelsea"))
    }

    @Test
    fun `a sport or league word as one side is no game, a club named after a sport is`() {
        assertTrue(SportsLiveOtherStreams.isNotGameTitle(live("Kosárlabda vs NBA")))
        assertTrue(SportsLiveOtherStreams.isNotGameTitle(live("Basketball vs NBA")))
        assertFalse(SportsLiveOtherStreams.isNotGameTitle(live("Sport Recife vs Náutico")))
        assertFalse(SportsLiveOtherStreams.isNotGameTitle(live("Real Madrid vs Celta, fudbal")))
        assertFalse(SportsLiveOtherStreams.isNotGameTitle(live("BYU vs Washington")))
    }

    @Test
    fun `ESPN's match keeps a row live whatever its title says, and a channel or a merged real copy never moves`() {
        val verdicts = SportsLiveNoEspnGames()
        val matched = SportsLiveMatchedEvents()
        // The Match, yet ESPN matched the row to Liverpool vs Manchester United in play: a title rule never overrules ESPN.
        val theMatch = live("Liverpool vs Manchester Utd: The Match")
        matched.record(listOf(theMatch.streamKey), event(), now)
        assertFalse(SportsLiveOtherStreams.isOtherStream(theMatch, now, verdicts, matched))
        // A channel Live now lists with its own live signal: never an Other stream.
        val channel = live("Sports 1 [Arsenal vs Chelsea]", channel = true)
        assertFalse(SportsLiveOtherStreams.isOtherStream(channel, now, verdicts, matched))
        // One copy ESPN judged, another copy of the row not: it stays.
        val judged = live("Arsenal vs Chelsea", addon = "a")
        val other = live("Arsenal vs Chelsea", addon = "b")
        verdicts.judge(judged)
        assertTrue(SportsLiveOtherStreams.isOtherStream(judged, now, verdicts, matched))
        assertFalse(SportsLiveOtherStreams.isOtherStream(judged.copy(alternates = listOf(other)), now, verdicts, matched))
        verdicts.judge(other)
        assertTrue(SportsLiveOtherStreams.isOtherStream(judged.copy(alternates = listOf(other)), now, verdicts, matched))
    }

    @Test
    fun `a verdict holds for its ESPN day and two hours past Eastern midnight, not after a retitle or once forgotten`() {
        val verdicts = SportsLiveNoEspnGames()
        val bayern = live("Bayern München vs Hoffenheim")
        verdicts.judge(bayern)
        val revision = verdicts.revision.value
        // Confirmed again: no rebuild.
        verdicts.judge(bayern, now + 60_000L)
        assertEquals(revision, verdicts.revision.value)
        // Judged at 18:17 Eastern on Monday: it holds to Monday's end, Eastern, then lapses (more than 2 h old by then).
        assertTrue(verdicts.holds(bayern, Instant.parse("2026-11-24T04:59:00Z").toEpochMilli()))
        assertFalse(verdicts.holds(bayern, Instant.parse("2026-11-24T05:01:00Z").toEpochMilli()))
        // Confirmed at 23:30 Eastern: it holds two hours into Tuesday, then lapses.
        val lateNight = SportsLiveNoEspnGames()
        lateNight.judge(bayern, Instant.parse("2026-11-24T04:30:00Z").toEpochMilli())
        assertTrue(lateNight.holds(bayern, Instant.parse("2026-11-24T06:29:00Z").toEpochMilli()))
        assertFalse(lateNight.holds(bayern, Instant.parse("2026-11-24T06:31:00Z").toEpochMilli()))
        // The same source retitled for another game is judged again.
        val retitled = bayern.copy(meta = bayern.meta.copy(name = "Bayern München vs Dortmund"), teams = SportsLiveTeams.parse("Bayern München vs Dortmund"))
        assertFalse(verdicts.holds(retitled, now + 60_000L))
        verdicts.forget(listOf(bayern.streamKey))
        assertFalse(verdicts.holds(bayern, now + 60_000L))
        assertTrue(verdicts.revision.value > revision)
    }

    @Test
    fun `Other streams is More live's last group and names itself last in its row`() {
        val streams = listOf(live("Arsenal vs Chelsea"), live("Newcastle vs Arsenal"))
        val other = listOf(live("ATP"))
        val groups = SportsLiveMoreLive.groups(emptyList(), other, untagged = { true }, streams = streams)
        assertEquals(listOf(SportsLiveMoreLive.OTHER, SportsLiveMoreLive.OTHER_STREAMS), groups.map { it.key })
        assertEquals(listOf(SportsLiveMoreLive.OTHER to 1, SportsLiveMoreLive.OTHER_STREAMS to 2), SportsLiveMoreLive.summary(groups))
        // Nothing else (no sport picked in My sports): Other streams alone.
        val alone = SportsLiveMoreLive.groups(emptyList(), emptyList(), streams = streams)
        assertEquals(listOf(SportsLiveMoreLive.OTHER_STREAMS to 2), SportsLiveMoreLive.summary(alone))
    }

    private fun event() = ScoreSnapshot(
        eventId = "liv-mun", league = EspnLeague("soccer", "eng.1"), startMs = now - 30 * 60_000L, state = ScoreState.IN,
        clockText = "30'", isFinal = false, home = team("Liverpool"), away = team("Manchester United")
    )

    private fun team(name: String) = ScoreTeam(
        id = name, abbreviation = null, displayName = name, shortDisplayName = name, name = name, location = name,
        logo = null, score = 0, linescores = emptyList()
    )
}
