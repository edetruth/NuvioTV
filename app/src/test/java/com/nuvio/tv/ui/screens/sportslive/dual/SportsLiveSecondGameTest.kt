package com.nuvio.tv.ui.screens.sportslive.dual

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import org.junit.Assert.assertFalse
import com.nuvio.tv.ui.screens.sportslive.offersWatchWith
import com.nuvio.tv.ui.screens.sportslive.watchWithFor
import com.nuvio.tv.ui.screens.sportslive.watchWithSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** "Watch with another game" straight into dual screen (#57): the slot-2 pick and its dual session. */
@OptIn(ExperimentalCoroutinesApi::class)
class SportsLiveSecondGameTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @After
    fun tearDown() {
        SportsLiveDualSessions.clear()
    }

    private val nba = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA)
    private val pacersEvent = nba.first { it.eventId == "401909985" } // IND @ PHI
    private val lakersEvent = nba.first { it.eventId == "401909986" } // LAL @ PHX

    private fun game(name: String, startMs: Long?, followed: Boolean = false, id: String = name) = SportsLiveItem(
        meta = MetaPreview(
            id = id, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
        kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false,
        startMs = startMs, sport = "Basketball", startHasTimeOfDay = startMs != null,
        teams = SportsLiveTeams.parse(name), followed = followed
    )

    private fun live(event: ScoreSnapshot, home: Int, away: Int, clock: String) = event.copy(
        state = ScoreState.IN, isFinal = false, clockText = clock,
        home = event.home.copy(score = home), away = event.away.copy(score = away)
    )

    private fun final(event: ScoreSnapshot, home: Int, away: Int) = event.copy(
        state = ScoreState.POST, isFinal = true, clockText = "Final",
        home = event.home.copy(score = home), away = event.away.copy(score = away)
    )

    private val first = game("Boston Celtics at New York Knicks", NOW)
    private val earlier = game("Game Earlier", NOW - HOUR)
    private val later = game("Game Later", NOW + 10 * MINUTE)
    private val pacers = game("Indiana Pacers at Philadelphia 76ers", NOW - 2 * HOUR)
    private val lakers = game("Los Angeles Lakers at Phoenix Suns", NOW - 3 * HOUR)

    private fun pick(
        items: List<SportsLiveItem>,
        scores: Map<String, ScoreSnapshot> = emptyMap(),
        finished: Set<String> = emptySet()
    ) = SportsLiveSecondGame.pick(first, items, scores, finished)

    // Slot-2 pick order

    @Test
    fun `a followed team's live game comes first, the next one by start time`() {
        val followedEarly = game("Followed Early", NOW - HOUR, followed = true)
        val followedLate = game("Followed Late", NOW + 5 * MINUTE, followed = true)
        // A close, late game is more exciting, but the followed team wins.
        val scores = mapOf(pacers.streamKey to live(pacersEvent, 100, 99, "1:00 - 4th"))
        assertEquals(followedLate, pick(listOf(first, pacers, followedEarly, followedLate), scores))
        // None starting after the first game: wraps round to the earliest.
        assertEquals(followedEarly, pick(listOf(first, pacers, followedEarly), scores))
    }

    @Test
    fun `with no followed game the most exciting live game, on the delayed snapshot`() {
        val scores = mapOf(
            pacers.streamKey to live(pacersEvent, 100, 99, "1:00 - 4th"),
            lakers.streamKey to live(lakersEvent, 30, 10, "5:00 - 2nd")
        )
        assertEquals(pacers, pick(listOf(first, later, lakers, pacers), scores))
        // Another game's snapshot under a card rates nothing: start time decides.
        assertEquals(later, pick(listOf(first, later, pacers), mapOf(pacers.streamKey to live(lakersEvent, 100, 99, "1:00 - 4th"))))
    }

    @Test
    fun `under Hide until focused or Off the pairing never follows how close a game is (sorting model)`() {
        val scores = mapOf(
            pacers.streamKey to live(pacersEvent, 100, 99, "1:00 - 4th"),
            lakers.streamKey to live(lakersEvent, 30, 10, "5:00 - 2nd")
        )
        val items = listOf(first, later, lakers, pacers)
        val byStart = pick(items)
        for (mode in listOf(ScoresMode.HIDE_UNTIL_FOCUSED, ScoresMode.OFF, null)) {
            assertEquals("$mode", byStart, SportsLiveSecondGame.pick(first, items, scores, emptySet(), mode = mode))
        }
    }

    @Test
    fun `else the next live game by start time, wrapping to the earliest`() {
        assertEquals(later, pick(listOf(earlier, first, later)))
        assertEquals(earlier, pick(listOf(earlier, first)))
        // Unknown starts go last.
        val noStart = game("No Start", null)
        assertEquals(earlier, pick(listOf(noStart, earlier, first)))
        assertEquals(noStart, pick(listOf(noStart, first)))
        assertNull(pick(listOf(first)))
    }

    // No finished games

    @Test
    fun `finished games, channels and the first game's own sources are never picked`() {
        val finals = mapOf(pacers.streamKey to final(pacersEvent, 101, 99))
        // An ESPN final in its postgame grace, though it is the only rated or followed game.
        assertNull(pick(listOf(first, pacers), finals))
        val postponed = mapOf(pacers.streamKey to pacersEvent.copy(state = ScoreState.OFF, isFinal = false))
        assertNull(pick(listOf(first, pacers), postponed))
        // In Just ended (or ended by the probe), for any of its sources.
        assertNull(pick(listOf(first, later), finished = setOf(later.streamKey)))
        val merged = later.copy(alternates = listOf(earlier.copy(addonId = "b")))
        assertNull(pick(listOf(first, merged), finished = setOf(earlier.copy(addonId = "b").streamKey)))
        // A channel, or the same game from another addon.
        assertNull(pick(listOf(first, later.copy(isChannel = true))))
        assertNull(pick(listOf(first, later.copy(alternates = listOf(first)))))
        // The live ones beside them are still picked.
        assertEquals(later, pick(listOf(first, pacers, later), finals))
    }

    // The dual session, and no extra resolves

    @Test
    fun `the session puts the game in slot 1 and the pick in slot 2, with the hint, over Live now`() {
        val session = sportsLiveWatchWithSession(
            first = first,
            items = listOf(first, later),
            zapItems = listOf(earlier, first, later),
            filter = "Basketball",
            scores = emptyMap(),
            finishedKeys = setOf("x")
        )!!
        assertEquals(first, session.left)
        assertEquals(later, session.right)
        assertEquals(SportsLiveTab.LIVE_NOW, session.tab)
        assertTrue(session.pairedHint)
        assertEquals(setOf("x"), session.finishedKeys)
        assertNull(session.backTo)
        assertNull(sportsLiveWatchWithSession(first, listOf(first), listOf(first), null, emptyMap(), emptySet()))
    }

    @Test
    fun `dual screen resolves only the two games, the handed-over one not at all`() = runTest(mainDispatcherRule.dispatcher) {
        val resolved = mutableListOf<String>()
        val session = sportsLiveWatchWithSession(first, listOf(first, later, earlier), listOf(first, later, earlier), null, emptyMap(), emptySet())!!
        SportsLiveDualViewModel(resolve = { resolved += it.id; null }, endedKeys = { emptySet() }, session = session)
        runCurrent()
        assertEquals(listOf(first.id, later.id), resolved)

        // Slot 1's preview stream plays as is: only slot 2 is looked up.
        resolved.clear()
        SportsLiveDualViewModel(
            resolve = { resolved += it.id; null },
            endedKeys = { emptySet() },
            session = session.copy(leftStream = dualStream("preview"))
        )
        runCurrent()
        assertEquals(listOf(later.id), resolved)
    }

    @Test
    fun `the hint names the paired game for three seconds`() = runTest(mainDispatcherRule.dispatcher) {
        val session = sportsLiveWatchWithSession(first, listOf(first, later), listOf(first, later), null, emptyMap(), emptySet())!!
        val viewModel = SportsLiveDualViewModel(resolve = { CompletableDeferred<Stream?>().await() }, endedKeys = { emptySet() }, session = session)
        runCurrent()
        assertEquals(later.meta.name, viewModel.pairHint.value)
        advanceTimeBy(SportsLiveDualViewModel.PAIR_HINT_MS + 1)
        assertNull(viewModel.pairHint.value)
        // A pick from elsewhere shows no hint.
        val picked = SportsLiveDualViewModel(resolve = { null }, endedKeys = { emptySet() }, session = session.copy(pairedHint = false))
        assertNull(picked.pairHint.value)
    }

    @Test
    fun `the other pane's picker leaves out finished games, and a pick resolves one game`() =
        runTest(mainDispatcherRule.dispatcher) {
            val resolved = mutableListOf<String>()
            val ended = game("Ended", NOW - HOUR)
            val probeEnded = game("Probe Ended", NOW - HOUR)
            val session = sportsLiveWatchWithSession(
                first, listOf(first, later, earlier, ended, probeEnded), listOf(first, later, earlier, ended, probeEnded),
                null, emptyMap(), finishedKeys = setOf(ended.streamKey)
            )!!
            val viewModel = SportsLiveDualViewModel(
                resolve = { resolved += it.id; null },
                endedKeys = { setOf(probeEnded.streamKey) },
                session = session
            )
            runCurrent()
            assertEquals(Side.LEFT, viewModel.state.value!!.highlighted)
            viewModel.openPairPicker(Side.RIGHT)
            // Neither pane's game, nor a finished one.
            assertEquals(listOf(earlier), viewModel.pairPicker.value)
            resolved.clear()
            viewModel.pickPair(earlier)
            runCurrent()
            assertNull(viewModel.pairPicker.value)
            assertEquals(earlier, viewModel.state.value!!.right.item)
            assertEquals(first, viewModel.state.value!!.left.item)
            assertEquals(listOf(earlier.id), resolved)
        }

    // Channels: no auto pair, the picker

    @Test
    fun `Watch with another game always opens the picker, for a channel and for a live game (F13)`() {
        val channel = later.copy(isChannel = true, kind = SportsLiveKind.CHANNELS, teams = null)
        val channels = com.nuvio.tv.ui.screens.sportslive.SportsLiveUiState(
            tabs = mapOf(SportsLiveTab.CHANNELS to listOf(channel, earlier), SportsLiveTab.LIVE_NOW to listOf(first, later)),
            selectedTab = SportsLiveTab.CHANNELS,
            visibleItems = listOf(channel, earlier)
        )
        assertTrue(channels.offersWatchWith(channel, null))
        val pick = channels.watchWithFor(channel, emptyMap())!!
        assertEquals(channel, pick.chosen)
        assertEquals(listOf(earlier), pick.candidates)
        assertEquals(SportsLiveTab.CHANNELS, pick.session(earlier).tab)

        // A live game from the More… menu: the picker too, never straight into dual screen.
        val liveNow = channels.copy(selectedTab = SportsLiveTab.LIVE_NOW, visibleItems = listOf(first, later))
        val gamePick = liveNow.watchWithFor(first, emptyMap())!!
        assertEquals(first, gamePick.chosen)
        assertEquals(listOf(later), gamePick.candidates)
        assertEquals(later, gamePick.session(later).right)
        // Nothing to go beside it: no picker.
        assertNull(liveNow.copy(visibleItems = listOf(first)).watchWithFor(first, emptyMap()))
        // Upcoming and Replays offer nothing.
        assertFalse(channels.copy(selectedTab = SportsLiveTab.UPCOMING).offersWatchWith(channel, null))
    }

    // Watch later (#151, live.20): "Watch with another game" never pairs a saved game

    @Test
    fun `the pick never pairs a game saved with Watch later, even a followed or the next one`() {
        val followedLater = later.copy(followed = true)
        val saved: (SportsLiveItem) -> Boolean = { it.id == followedLater.id }
        assertEquals(earlier, SportsLiveSecondGame.pick(first, listOf(first, followedLater, earlier), emptyMap(), emptySet(), saved))
        // Only saved games left: nothing to pair with.
        assertNull(SportsLiveSecondGame.pick(first, listOf(first, followedLater), emptyMap(), emptySet(), saved))
    }

    @Test
    fun `the Game page's session skips a saved game, through the screen state`() {
        val state = com.nuvio.tv.ui.screens.sportslive.SportsLiveUiState(
            tabs = mapOf(SportsLiveTab.LIVE_NOW to listOf(first, later, earlier)),
            selectedTab = SportsLiveTab.LIVE_NOW,
            visibleItems = listOf(first, later, earlier)
        )
        assertEquals(later, state.watchWithSession(first, emptyMap())!!.right)
        assertEquals(earlier, state.watchWithSession(first, emptyMap(), savedForLater = { it.id == later.id })!!.right)
        assertNull(state.watchWithSession(first, emptyMap(), savedForLater = { it.id != first.id }))
    }

    private companion object {
        const val NOW = 1_763_240_000_000L
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
