package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.SportsLiveLineup
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.MySports
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.core.sportslive.scores.TeamDirectory
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.ui.screens.sportslive.dual.dualPickerCandidates
import com.nuvio.tv.ui.screens.sportslive.dual.dualPickerGames
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * live.25 (#225): one shared order for every live list. Live Now, the player's panel and its table guide, CH± / ▲▼ and the
 * dual picker are built here from the same games, each the way its code builds it, and must list them alike; no Scores
 * setting may let a score, a clock or a close game move a row.
 */
class SportsLiveLineupTest {

    private val directory = TeamDirectory()
    private val nba = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA)
    private val pacersEvent = nba.first { it.eventId == "401909985" } // IND @ PHI
    private val lakersEvent = nba.first { it.eventId == "401909986" } // LAL @ PHX
    private val celticsEvent = nba.first { it.eventId == "401909989" } // DAL @ BOS

    private val now = lakersEvent.startMs!! + HOUR

    // Start order (earliest first) is college, hornets, pacers, epl, lakers, ucl, celtics: a different order from the shared one.
    private val college = game("Duke vs Kansas", "NCAAM", now - 200 * MINUTE)
    private val hornets = game("Charlotte Hornets at LA Clippers", "NBA", now - 150 * MINUTE)
    private val pacers = game("Indiana Pacers at Philadelphia 76ers", "NBA", now - 120 * MINUTE)
    private val epl = game("Arsenal vs Chelsea", "Premier League", now - 100 * MINUTE)
    private val lakers = game("Los Angeles Lakers at Phoenix Suns", "NBA", now - 60 * MINUTE)
    private val ucl = game("Inter vs Porto", "Champions League", now - 30 * MINUTE)
    private val celtics = game("Dallas Mavericks at Boston Celtics", "NBA", now - 10 * MINUTE).copy(followed = true)

    /** As the load lists them: start order, nothing ranked. */
    private val games = listOf(college, hornets, pacers, epl, lakers, ucl, celtics)

    /** A dull moment: no game is close. */
    private val calm = mapOf(
        pacers.streamKey to live(pacersEvent, "5:00 - 2nd", 60, 40),
        lakers.streamKey to live(lakersEvent, "6:00 - 2nd", 50, 30),
        celtics.streamKey to live(celticsEvent, "8:00 - 1st", 10, 30)
    )

    /** The same games later: the Lakers game is one point apart with a minute left (crunch time), Pacers is a blowout. */
    private val crunch = mapOf(
        pacers.streamKey to live(pacersEvent, "2:00 - 4th", 120, 80),
        lakers.streamKey to live(lakersEvent, "1:00 - 4th", 100, 99),
        celtics.streamKey to live(celticsEvent, "1:00 - 4th", 99, 98)
    )

    /** The order each surface lists, built as its code builds it. */
    private data class Surfaces(val liveNow: List<String>, val panel: List<String>, val guide: List<String>, val zap: List<String>, val picker: List<String>)

    private fun surfaces(scores: Map<String, ScoreSnapshot>, mode: ScoresMode): Surfaces {
        // Live Now (SportsLiveViewModel): the shared order, then the board splits it by status, keeping it.
        val liveNowItems = SportsLiveLineup.order(games, MySports.DEFAULT, scores, mode, directory)
        val liveNow = scoreboardRows(liveNowItems, SportsLiveTab.LIVE_NOW, scores, mode, emptySet(), now, keepOrder = true).map { it.item }
        // The player's panel (SportsLiveZapViewModel's lineup): the same order, grouped keeping it, seeded from Live Now.
        val panelOrder = SportsLiveLineup.order(games, MySports.DEFAULT, scores, mode, directory)
        val rows = panelLineup(groupPanelRows(panelOrder, scores, mode, emptySet(), now, keepOrder = true), seed = liveNowItems.map { it.streamKey })
        // The table guide draws the panel's rows (PanelGuide): Now playing (the first game here) pinned, then its lines.
        val drawn = SportsLiveZapPanelState(rows = rows, currentKey = rows.first().item.streamKey).guide
        val guide = listOfNotNull(drawn.nowPlaying?.item) + drawn.entries.filterIsInstance<PanelGuideEntry.Game>().map { it.row.item }
        // CH± / ▼ from the first game: the session started on Live Now, its order frozen from the panel's at the first zap.
        var session = SportsLiveZapSession.of(liveNowItems, liveNow.first().streamKey, SportsLiveTab.LIVE_NOW, null, returnToHomeOnBack = false)!!
            .copy(seed = liveNowItems.map { it.streamKey })
            .withFrozenLineup(rows)
        val zap = mutableListOf(session.current)
        repeat(games.size - 1) {
            session = session.step(forward = true, endedKeys = emptySet())!!
            zap += session.current
        }
        // The dual picker (SportsLiveDualViewModel): its pool in the shared order, then the candidates filter.
        val pool = runBlocking { dualPickerGames(flowOf(games), flowOf(MySports.DEFAULT), flowOf(scores), flowOf(mode)).first() }
        val picker = dualPickerCandidates(pool, onScreen = emptySet(), over = emptySet())
        fun List<SportsLiveItem>.ids() = map { it.id }
        return Surfaces(liveNow.ids(), rows.map { it.item }.ids(), guide.ids(), zap.ids(), picker.ids())
    }

    private fun assertAllAlike(expected: List<String>, surfaces: Surfaces) {
        assertEquals("Live Now", expected, surfaces.liveNow)
        assertEquals("panel", expected, surfaces.panel)
        assertEquals("table guide", expected, surfaces.guide)
        assertEquals("zap", expected, surfaces.zap)
        assertEquals("dual picker", expected, surfaces.picker)
    }

    @Test
    fun `Live Now, the panel, the table guide, CH and the dual picker list the same games in the same order`() {
        // Followed first; then league priority (Champions League, Premier League, NBA); inside the NBA the games ESPN has
        // before the one it has none of, each by start; the unplaced college game last.
        val expected = listOf(celtics, ucl, epl, pacers, lakers, hornets, college).map { it.id }
        assertAllAlike(expected, surfaces(calm, ScoresMode.SHOW))
        assertAllAlike(expected, surfaces(calm, ScoresMode.HIDE_UNTIL_FOCUSED))
        // Off: nothing is matched, so start order inside the tier; still alike everywhere.
        assertAllAlike(listOf(celtics, ucl, epl, hornets, pacers, lakers, college).map { it.id }, surfaces(calm, ScoresMode.OFF))
    }

    @Test
    fun `a close game never moves up, and Show and Hide list alike whatever the scores say`() {
        for (mode in ScoresMode.entries) {
            // The Lakers game going to crunch time (and Pacers to a blowout) changes no surface's order.
            assertEquals(mode.name, surfaces(calm, mode), surfaces(crunch, mode))
        }
        assertEquals(surfaces(crunch, ScoresMode.SHOW), surfaces(crunch, ScoresMode.HIDE_UNTIL_FOCUSED))
        // Off reads no score at all: the same as with no feed.
        assertEquals(surfaces(crunch, ScoresMode.OFF), surfaces(emptyMap(), ScoresMode.OFF))
        assertEquals(emptySet<String>(), SportsLiveLineup.matchedKeys(games, crunch, ScoresMode.OFF))
        assertEquals(emptySet<String>(), SportsLiveLineup.matchedKeys(games, crunch, null))
    }

    @Test
    fun `zap up and down follow Live Now's visible order, calm order and status groups included`() {
        // Live Now as shown: its calm order kept lakers above pacers (the list was ranked before pacers' match landed),
        // and the epl game has ended since (Just ended, at the end).
        val shown = listOf(celtics, ucl, lakers, pacers, epl, hornets)
        val ended = mapOf(epl.streamKey to com.nuvio.tv.core.sportslive.EndedGame(com.nuvio.tv.core.sportslive.EndedSignal.ESTIMATE, now - MINUTE))
        val visible = scoreboardRows(shown, SportsLiveTab.LIVE_NOW, calm, ScoresMode.SHOW, emptySet(), now, ended = ended, keepOrder = true)
            .map { it.item.id }
        assertEquals(listOf(celtics, ucl, lakers, pacers, hornets, epl).map { it.id }, visible)
        // The panel takes the fresh shared order (college has started listing since: behind "N new" on Live Now) but lists
        // the seeded games as shown; the new one follows in its group.
        val fresh = SportsLiveLineup.order(shown + college, MySports.DEFAULT, calm, ScoresMode.SHOW, directory)
        val rows = panelLineup(
            groupPanelRows(fresh, calm, ScoresMode.SHOW, emptySet(), now, ended = ended, keepOrder = true),
            seed = shown.map { it.streamKey }
        )
        assertEquals(listOf(celtics, ucl, lakers, pacers, hornets, college, epl).map { it.id }, rows.map { it.item.id })
        val session = SportsLiveZapSession.of(shown, lakers.streamKey, SportsLiveTab.LIVE_NOW, null, returnToHomeOnBack = false)!!
            .copy(seed = shown.map { it.streamKey })
            .withFrozenLineup(rows)
        // ▼ / CH− is the row below on Live Now, ▲ / CH+ the row above.
        assertEquals(pacers.id, session.step(forward = true, endedKeys = emptySet())!!.current.id)
        assertEquals(ucl.id, session.step(forward = false, endedKeys = emptySet())!!.current.id)
        // Past hornets the new game, then round past the ended one (CH± never stops on it) to the top.
        val afterHornets = session.moveTo(hornets.streamKey)!!.step(forward = true, endedKeys = emptySet())!!
        assertEquals(college.id, afterHornets.current.id)
        assertEquals(celtics.id, afterHornets.step(forward = true, endedKeys = emptySet())!!.current.id)
    }

    @Test
    fun `without a seed (Home, an alert, a Game page) the panel and CH take the shared order fresh`() {
        val fresh = SportsLiveLineup.order(games, MySports.DEFAULT, calm, ScoresMode.SHOW, directory)
        val rows = panelLineup(groupPanelRows(fresh, calm, ScoresMode.SHOW, emptySet(), now, keepOrder = true))
        assertEquals(fresh.map { it.id }, rows.map { it.item.id })
    }

    private fun live(event: ScoreSnapshot, clock: String, home: Int, away: Int) = event.copy(
        state = ScoreState.IN, isFinal = false, clockText = clock,
        home = event.home.copy(score = home), away = event.away.copy(score = away)
    )

    private fun game(name: String, sport: String, startMs: Long) = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "tv", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = "LIVE", imdbRating = null, genres = emptyList()
        ),
        addonId = "addon", addonName = "Sports", addonBaseUrl = "https://example.invalid", catalogKey = "addon:tv:live",
        catalogName = "Live", kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false,
        startMs = startMs, sport = sport, startHasTimeOfDay = true,
        teams = SportsLiveTeams.parse(name)
    )

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
