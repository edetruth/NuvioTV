package com.nuvio.tv.ui.screens.sportslive

import android.view.KeyEvent
import androidx.lifecycle.SavedStateHandle
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.debrid.DirectDebridResolver
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sportslive.SportsLiveFullTimeAnnouncements
import com.nuvio.tv.core.sportslive.SportsLiveLinkVerdicts
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.LiveScoresRepository
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.data.local.shouldShowPanelKeysHint
import com.nuvio.tv.core.sportslive.scores.SpoilerProtection
import com.nuvio.tv.core.sportslive.scores.ProtectedGame
import com.nuvio.tv.core.sportslive.SportsLiveTriedGames
import com.nuvio.tv.core.sportslive.SportsLiveWorkingLinks
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.repository.SportsLiveRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import com.nuvio.tv.core.sportslive.GameEndReason
import com.nuvio.tv.core.sportslive.GameEndStrings
import com.nuvio.tv.core.sportslive.SportsLiveEndCountdown
import com.nuvio.tv.core.sportslive.SportsLiveGameEnd
import com.nuvio.tv.core.sportslive.SportsLiveGameWatch
import com.nuvio.tv.core.sportslive.SportsLiveGameReadiness
import com.nuvio.tv.core.sportslive.SportsLiveStillWatching

@OptIn(ExperimentalCoroutinesApi::class)
class SportsLiveZapViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Before
    fun setUp() {
        SportsLiveExtraSources.reset()
        SportsLiveZapSessions.clear()
    }

    @After
    fun tearDown() {
        SportsLiveExtraSources.reset()
        SportsLiveZapSessions.clear()
        SportsLiveFullTimeAnnouncements.clear()
    }

    // "Ended while you're watching" (#60)

    private val pacersEvent = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA).first { it.eventId == "401909985" }
    private val inPlay = pacersEvent.copy(
        state = ScoreState.IN, isFinal = false, clockText = "Q4 1:00",
        home = pacersEvent.home.copy(score = 99), away = pacersEvent.away.copy(score = 101)
    )
    private val final = inPlay.copy(state = ScoreState.POST, isFinal = true, clockText = "Final")

    private fun game(name: String) = item(name, "one").copy(
        meta = item(name, "one").meta.copy(name = name),
        teams = SportsLiveTeams.parse(name)
    )

    private fun fullTimeViewModel(
        scores: MutableStateFlow<Map<String, ScoreSnapshot>>,
        mode: ScoresMode,
        playing: SportsLiveItem,
        others: List<SportsLiveItem>,
        started: Boolean = true,
        watch: SportsLiveGameWatch = SportsLiveGameWatch(),
        protection: SpoilerProtection = SpoilerProtection(),
        links: SportsLiveWorkingLinks = SportsLiveWorkingLinks.shared,
        claimHint: suspend () -> Boolean = { false },
        readiness: SportsLiveGameReadiness = SportsLiveGameReadiness.shared,
        stillWatching: SportsLiveStillWatching = SportsLiveStillWatching()
    ): SportsLiveZapViewModel {
        SportsLiveZapSessions.start(
            SportsLiveZapSession.of(listOf(playing) + others, playing.streamKey, SportsLiveTab.LIVE_NOW, null, returnToHomeOnBack = false)!!
        )
        val resolver = mockk<SportsLiveZapResolver>()
        coEvery { resolver.resolve(any()) } returns STREAM
        val debrid = mockk<DirectDebridResolver>()
        coEvery { debrid.shouldResolveToPlayableStream(any()) } returns false
        val profiles = mockk<ProfileManager>()
        every { profiles.activeProfileId } returns MutableStateFlow(1)
        val repo = mockk<SportsLiveRepository>(relaxed = true)
        every { repo.endedKeys } returns MutableStateFlow(emptySet())
        val liveScores = mockk<LiveScoresRepository>(relaxed = true)
        every { liveScores.scores } returns scores
        every { liveScores.mode } returns flowOf(mode)
        every { liveScores.protection } returns MutableStateFlow(protection)
        every { liveScores.unmatched } returns MutableStateFlow(emptySet())
        return SportsLiveZapViewModel(
            SavedStateHandle(mapOf("contentId" to playing.id)), resolver, debrid, profiles, repo, liveScores,
            mainDispatcherRule.dispatcher, gameWatch = watch, workingLinks = links, claimPanelKeysHint = claimHint,
            readiness = readiness, stillWatching = stillWatching
        ).also { it.onPlayerStarted(started) }
    }

    @Test
    fun `the panel's keys hint shows for the profile's first 3 openings, then never`() = runTest(mainDispatcherRule.dispatcher) {
        var shown = 0
        val pacers = game("Indiana Pacers at Philadelphia 76ers")
        val viewModel = fullTimeViewModel(
            MutableStateFlow(emptyMap()), ScoresMode.SHOW, pacers, emptyList(),
            claimHint = { shouldShowPanelKeysHint(shown).also { if (it) shown++ } }
        )
        val seen = (1..5).map {
            viewModel.openPanel()
            runCurrent()
            viewModel.panelKeysHint.value.also { viewModel.closePanel() }
        }
        assertEquals(listOf(true, true, true, false, false), seen)
    }

    private val END_STRINGS = GameEndStrings("Game over", "Final · %1\$s %2\$d–%3\$d %4\$s")

    @Test
    fun `the game playing ending shows the full-time toast once on screen, the score per the Scores setting`() = runTest(mainDispatcherRule.dispatcher) {
        val pacers = game("Indiana Pacers at Philadelphia 76ers")
        val scores = MutableStateFlow(mapOf(pacers.streamKey to inPlay))
        val viewModel = fullTimeViewModel(scores, ScoresMode.SHOW, pacers, emptyList())
        runCurrent()
        assertNull(viewModel.endCard.value)
        scores.value = mapOf(pacers.streamKey to final)
        runCurrent()
        val card = viewModel.endCard.value!!
        assertEquals(GameEndReason.FINAL, card.reason)
        assertEquals("Final · Indiana Pacers 101–99 Philadelphia 76ers", SportsLiveGameEnd.title(card.item, card.snapshot, card.mode, END_STRINGS))
        // Not on screen yet (the controls are up): not claimed.
        assertFalse(SportsLiveFullTimeAnnouncements.isAnnounced(final.eventId))
        viewModel.onEndCardVisible(true)
        assertTrue(SportsLiveFullTimeAnnouncements.isAnnounced(final.eventId))
        // Nothing else live and the stream plays on: the card leaves by itself.
        advanceTimeBy(FULL_TIME_TOAST_MS + 1)
        assertNull(viewModel.endCard.value)

        // Hide until focused: "Full time" with no score.
        SportsLiveFullTimeAnnouncements.clear()
        val hiddenScores = MutableStateFlow(mapOf(pacers.streamKey to inPlay))
        val hidden = fullTimeViewModel(hiddenScores, ScoresMode.HIDE_UNTIL_FOCUSED, pacers, emptyList())
        runCurrent()
        hiddenScores.value = mapOf(pacers.streamKey to final)
        runCurrent()
        val hiddenCard = hidden.endCard.value!!
        assertEquals("Game over", SportsLiveGameEnd.title(hiddenCard.item, hiddenCard.snapshot, hiddenCard.mode, END_STRINGS))
        hidden.dismissEndCard()

        // Off: nothing.
        SportsLiveFullTimeAnnouncements.clear()
        val offScores = MutableStateFlow(mapOf(pacers.streamKey to inPlay))
        val off = fullTimeViewModel(offScores, ScoresMode.OFF, pacers, emptyList())
        runCurrent()
        offScores.value = mapOf(pacers.streamKey to final)
        runCurrent()
        assertNull(off.endCard.value)
    }

    @Test
    fun `the toast claims its game only when shown, and goes if the alerts announced it meanwhile`() = runTest(mainDispatcherRule.dispatcher) {
        val pacers = game("Indiana Pacers at Philadelphia 76ers")
        val scores = MutableStateFlow(mapOf(pacers.streamKey to inPlay))
        val viewModel = fullTimeViewModel(scores, ScoresMode.SHOW, pacers, emptyList())
        runCurrent()
        scores.value = mapOf(pacers.streamKey to final)
        runCurrent()
        assertTrue(viewModel.endCard.value != null)
        // The followed-team toast (#42) shows first, while this one waits behind the controls.
        assertTrue(SportsLiveFullTimeAnnouncements.claim(final.eventId))
        viewModel.onEndCardVisible(true)
        assertNull(viewModel.endCard.value)

        // Already announced before it ended here: never raised.
        SportsLiveFullTimeAnnouncements.clear()
        assertTrue(SportsLiveFullTimeAnnouncements.claim(final.eventId))
        val laterScores = MutableStateFlow(mapOf(pacers.streamKey to inPlay))
        val later = fullTimeViewModel(laterScores, ScoresMode.SHOW, pacers, emptyList())
        runCurrent()
        laterScores.value = mapOf(pacers.streamKey to final)
        runCurrent()
        assertNull(later.endCard.value)

        // Never shown (controls up the whole time): dropped, unclaimed.
        SportsLiveFullTimeAnnouncements.clear()
        val waitingScores = MutableStateFlow(mapOf(pacers.streamKey to inPlay))
        val waiting = fullTimeViewModel(waitingScores, ScoresMode.SHOW, pacers, emptyList())
        runCurrent()
        waitingScores.value = mapOf(pacers.streamKey to final)
        runCurrent()
        advanceTimeBy(FULL_TIME_PENDING_MS + 1)
        assertNull(waiting.endCard.value)
        assertFalse(SportsLiveFullTimeAnnouncements.isAnnounced(final.eventId))

        // A game opened already over did not end while watching.
        val already = fullTimeViewModel(MutableStateFlow(mapOf(pacers.streamKey to final)), ScoresMode.SHOW, pacers, emptyList())
        runCurrent()
        assertNull(already.endCard.value)
    }

    @Test
    fun `Up next never picks a game Live now shows Probably ended`() = runTest(mainDispatcherRule.dispatcher) {
        // live.19 QA F2: no ESPN feed covers it and its start plus its sport's length is long past (an estimate).
        val rugby = game("Leinster vs Munster").let {
            it.copy(
                sport = "Rugby", meta = it.meta.copy(genres = listOf("Rugby")),
                startMs = System.currentTimeMillis() - 6 * 60 * 60_000L, startHasTimeOfDay = true
            )
        }
        val pacers = game("Indiana Pacers at Philadelphia 76ers")
        val hawks = game("Atlanta Hawks at San Antonio Spurs")
        val watch = SportsLiveGameWatch()
        val viewModel = fullTimeViewModel(MutableStateFlow(emptyMap()), ScoresMode.SHOW, pacers, listOf(rugby, hawks), watch = watch)
        runCurrent()
        watch.reportEnd(pacers.id, GameEndReason.STREAM_ENDED)
        runCurrent()
        assertEquals(hawks, viewModel.endCard.value!!.next)
    }

    @Test
    fun `Up next and Next live game never pick a game saved with Watch later`() = runTest(mainDispatcherRule.dispatcher) {
        // #184: playing it would show it live. Hawks @ Spurs was saved for later (no ESPN match here: by its sides).
        val pacers = game("Indiana Pacers at Philadelphia 76ers")
        val hawks = game("Atlanta Hawks at San Antonio Spurs")
        val celtics = game("Boston Celtics at New York Knicks")
        val saved = ProtectedGame("401", home = "San Antonio Spurs", away = "Atlanta Hawks", startMs = null, savedAtMs = 0L)
        val watch = SportsLiveGameWatch()
        val viewModel = fullTimeViewModel(
            MutableStateFlow(emptyMap()), ScoresMode.SHOW, pacers, listOf(hawks, celtics), watch = watch,
            protection = SpoilerProtection(games = listOf(saved), nowMs = 0L)
        )
        val events = mutableListOf<SportsLiveZapEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.collect { events += it } }
        runCurrent()
        watch.reportEnd(pacers.id, GameEndReason.STREAM_ENDED)
        runCurrent()
        assertEquals(celtics, viewModel.endCard.value!!.next)

        // The error page's Next live game skips it too.
        viewModel.dismissEndCard()
        try {
            assertTrue(viewModel.nextLiveGameAfterFailure())
            advanceUntilIdle()
            assertEquals(celtics, (events.single() as SportsLiveZapEvent.Navigate).target.item)
        } finally {
            SportsLiveTriedGames.shared.clear()
        }
    }

    @Test
    fun `Up next skips a game whose every known link failed this session`() = runTest(mainDispatcherRule.dispatcher) {
        // live.19 re-check: Up next picked a game whose only link had already failed.
        val pacers = game("Indiana Pacers at Philadelphia 76ers")
        val hawks = game("Atlanta Hawks at San Antonio Spurs")
        val celtics = game("Boston Celtics at New York Knicks")
        val links = SportsLiveWorkingLinks()
        val dead = STREAM.copy(url = "https://example.invalid/hawks.m3u8")
        links.noteLinks(hawks.id, listOf(dead))
        links.failed(hawks.id, dead.url)
        val watch = SportsLiveGameWatch()
        val viewModel = fullTimeViewModel(MutableStateFlow(emptyMap()), ScoresMode.SHOW, pacers, listOf(hawks, celtics), watch = watch, links = links)
        runCurrent()
        watch.reportEnd(pacers.id, GameEndReason.STREAM_ENDED)
        runCurrent()
        assertEquals(celtics, viewModel.endCard.value!!.next)
    }

    @Test
    fun `a stream stopping at the game's end shows the card with the countdown, and any key cancels it`() = runTest(mainDispatcherRule.dispatcher) {
        val pacers = game("Indiana Pacers at Philadelphia 76ers")
        val hawks = game("Atlanta Hawks at San Antonio Spurs").copy(followed = true)
        val watch = SportsLiveGameWatch()
        val viewModel = fullTimeViewModel(
            MutableStateFlow(emptyMap()), ScoresMode.SHOW, pacers, listOf(hawks), watch = watch, readiness = readyFor(hawks)
        )
        val events = mutableListOf<SportsLiveZapEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.collect { events += it } }
        runCurrent()
        watch.reportEnd(pacers.id, GameEndReason.STREAM_ENDED)
        runCurrent()
        val card = viewModel.endCard.value!!
        assertTrue(card.streamStopped)
        assertEquals(hawks, card.next)
        viewModel.onEndCardVisible(true)
        assertTrue(viewModel.endCard.value!!.countdownEndsAtMs != null)
        // Any key: the countdown stops, the card waits for OK.
        assertTrue(viewModel.cancelEndCountdown())
        advanceTimeBy(30_000L)
        runCurrent()
        assertTrue(events.isEmpty())
        assertTrue(viewModel.endCard.value != null)
        // Back now closes it (Stay).
        assertTrue(viewModel.endCardOnBack())
        assertNull(viewModel.endCard.value)
    }

    @Test
    fun `the countdown running out moves to Up next`() = runTest(mainDispatcherRule.dispatcher) {
        val pacers = game("Indiana Pacers at Philadelphia 76ers")
        val hawks = game("Atlanta Hawks at San Antonio Spurs").copy(followed = true)
        val watch = SportsLiveGameWatch()
        val guard = SportsLiveStillWatching()
        val viewModel = fullTimeViewModel(
            MutableStateFlow(emptyMap()), ScoresMode.SHOW, pacers, listOf(hawks), watch = watch,
            readiness = readyFor(hawks), stillWatching = guard
        )
        val events = mutableListOf<SportsLiveZapEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.collect { events += it } }
        runCurrent()
        watch.reportEnd(pacers.id, GameEndReason.STREAM_ENDED)
        runCurrent()
        viewModel.onEndCardVisible(true)
        advanceTimeBy(SportsLiveEndCountdown.DURATION_MS + 1)
        advanceUntilIdle()
        assertNull(viewModel.endCard.value)
        assertEquals(hawks, (events.single() as SportsLiveZapEvent.Navigate).target.item)
        // The move counted toward "Are you still watching?".
        repeat(SportsLiveStillWatching.MAX_AUTO_MOVES - 1) { guard.onAutoMove() }
        assertFalse(guard.mayAutoMove())
    }

    /** "▶ Ready" for [game]: a verified link held now. */
    private fun readyFor(game: SportsLiveItem) = SportsLiveGameReadiness().also { it.ready(game.id, STREAM) }

    @Test
    fun `Up next that is not one of my games, or not Ready, waits for OK with no countdown`() = runTest(mainDispatcherRule.dispatcher) {
        // Research 6: the card moves by itself only into my games showing "▶ Ready".
        val pacers = game("Indiana Pacers at Philadelphia 76ers")
        val hawks = game("Atlanta Hawks at San Antonio Spurs")
        val watch = SportsLiveGameWatch()
        val notMine = fullTimeViewModel(MutableStateFlow(emptyMap()), ScoresMode.SHOW, pacers, listOf(hawks), watch = watch, readiness = readyFor(hawks))
        runCurrent()
        watch.reportEnd(pacers.id, GameEndReason.STREAM_ENDED)
        runCurrent()
        notMine.onEndCardVisible(true)
        assertEquals(hawks, notMine.endCard.value!!.next)
        assertFalse(notMine.endCard.value!!.countdown)
        assertNull(notMine.endCard.value!!.countdownEndsAtMs)

        SportsLiveZapSessions.clear()
        val followed = hawks.copy(followed = true)
        val watch2 = SportsLiveGameWatch()
        val notReady = fullTimeViewModel(MutableStateFlow(emptyMap()), ScoresMode.SHOW, pacers, listOf(followed), watch = watch2)
        runCurrent()
        watch2.reportEnd(pacers.id, GameEndReason.STREAM_ENDED)
        runCurrent()
        notReady.onEndCardVisible(true)
        assertEquals(followed, notReady.endCard.value!!.next)
        assertNull(notReady.endCard.value!!.countdownEndsAtMs)
    }

    @Test
    fun `with no key press for hours the card asks Are you still watching instead of counting down`() = runTest(mainDispatcherRule.dispatcher) {
        val pacers = game("Indiana Pacers at Philadelphia 76ers")
        val hawks = game("Atlanta Hawks at San Antonio Spurs").copy(followed = true)
        val watch = SportsLiveGameWatch()
        var clock = 0L
        val guard = SportsLiveStillWatching(nowMs = { clock })
        clock = SportsLiveStillWatching.IDLE_LIMIT_MS
        val viewModel = fullTimeViewModel(
            MutableStateFlow(emptyMap()), ScoresMode.SHOW, pacers, listOf(hawks), watch = watch,
            readiness = readyFor(hawks), stillWatching = guard
        )
        runCurrent()
        watch.reportEnd(pacers.id, GameEndReason.STREAM_ENDED)
        runCurrent()
        viewModel.onEndCardVisible(true)
        val card = viewModel.endCard.value!!
        assertTrue(card.stillWatching)
        assertNull(card.countdownEndsAtMs)
        // A key press: the guard starts afresh, the next game end counts down again.
        guard.onInteraction()
        assertTrue(guard.mayAutoMove())
    }

    @Test
    fun `Both from an alert pairs the game playing, on its link, with the alert's game`() = runTest(mainDispatcherRule.dispatcher) {
        val pacers = game("Indiana Pacers at Philadelphia 76ers")
        val hawks = game("Atlanta Hawks at San Antonio Spurs")
        val elsewhere = game("Boston Celtics at New York Knicks")
        val viewModel = fullTimeViewModel(MutableStateFlow(emptyMap()), ScoresMode.SHOW, pacers, listOf(hawks))
        val session = viewModel.bothFromAlert(hawks)!!
        assertEquals(pacers, session.left)
        assertEquals(hawks, session.right)
        assertEquals(pacers, session.backTo)
        // A game the list leaves out is added to it.
        val added = viewModel.bothFromAlert(elsewhere)!!
        assertEquals(elsewhere, added.right)
        assertTrue(added.items.any { it.streamKey == elsewhere.streamKey })
        // Never the game playing beside itself.
        assertNull(viewModel.bothFromAlert(pacers))
    }

    @Test
    fun `a Game page opened over a playing game resumes it once on return`() = runTest(mainDispatcherRule.dispatcher) {
        val pacers = game("Indiana Pacers at Philadelphia 76ers")
        val viewModel = fullTimeViewModel(MutableStateFlow(emptyMap()), ScoresMode.SHOW, pacers, emptyList())

        assertFalse(viewModel.consumeResumeOnReturn())
        viewModel.onGameOpened(wasPlaying = true)
        assertTrue(viewModel.consumeResumeOnReturn())
        // Once only: a later return (or another screen) does not resume it again.
        assertFalse(viewModel.consumeResumeOnReturn())
        // Paused when the page opened: it stays paused.
        viewModel.onGameOpened(wasPlaying = false)
        assertFalse(viewModel.consumeResumeOnReturn())
    }

    @Test
    fun `nothing is scored or raised while the player is stopped`() = runTest(mainDispatcherRule.dispatcher) {
        val pacers = game("Indiana Pacers at Philadelphia 76ers")
        val scores = MutableStateFlow(mapOf(pacers.streamKey to inPlay))
        val viewModel = fullTimeViewModel(scores, ScoresMode.SHOW, pacers, emptyList())
        runCurrent()
        // Home pressed: the game ends behind it.
        viewModel.onPlayerStarted(false)
        scores.value = mapOf(pacers.streamKey to final)
        runCurrent()
        assertNull(viewModel.endCard.value)
        // Back to the player: the toast is raised then.
        viewModel.onPlayerStarted(true)
        runCurrent()
        assertTrue(viewModel.endCard.value != null)

        // Never started: never seen live, so nothing.
        SportsLiveFullTimeAnnouncements.clear()
        val stoppedScores = MutableStateFlow(mapOf(pacers.streamKey to inPlay))
        val stopped = fullTimeViewModel(stoppedScores, ScoresMode.SHOW, pacers, emptyList(), started = false)
        runCurrent()
        stoppedScores.value = mapOf(pacers.streamKey to final)
        runCurrent()
        assertNull(stopped.endCard.value)
    }

    @Test
    fun `Next live game works only while the toast shows, and skips finished and dead-checked games`() = runTest(mainDispatcherRule.dispatcher) {
        val pacers = game("Indiana Pacers at Philadelphia 76ers")
        val lakers = game("Los Angeles Lakers at Phoenix Suns")
        val dead = game("Boston Celtics at New York Knicks")
        val hawks = game("Atlanta Hawks at San Antonio Spurs")
        val lakersFinal = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA).first { it.eventId == "401909986" }
            .let { it.copy(state = ScoreState.POST, isFinal = true, home = it.home.copy(score = 90), away = it.away.copy(score = 88)) }
        SportsLiveLinkVerdicts.shared.markDead(dead.streamKey, SportsLiveLinkVerdicts.Dead(0L, 0L))
        try {
            val scores = MutableStateFlow(mapOf(pacers.streamKey to inPlay, lakers.streamKey to lakersFinal))
            val viewModel = fullTimeViewModel(scores, ScoresMode.SHOW, pacers, listOf(lakers, dead, hawks))
            val events = mutableListOf<SportsLiveZapEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.collect { events += it } }
            // No toast: Next does nothing.
            assertFalse(viewModel.nextLiveGame())
            runCurrent()
            scores.value = mapOf(pacers.streamKey to final, lakers.streamKey to lakersFinal)
            runCurrent()
            // Raised but hidden (the controls are up): Right is left to the player.
            assertFalse(viewModel.nextLiveGame())
            viewModel.onEndCardVisible(true)
            viewModel.onEndCardVisible(false)
            assertFalse(viewModel.nextLiveGame())
            // On screen: Right moves on, past the final and the dead-checked game.
            viewModel.onEndCardVisible(true)
            assertTrue(viewModel.nextLiveGame())
            advanceUntilIdle()
            assertNull(viewModel.endCard.value)
            assertEquals(hawks, (events.single() as SportsLiveZapEvent.Navigate).target.item)
        } finally {
            SportsLiveLinkVerdicts.shared.clear(dead.streamKey)
        }
    }

    @Test
    fun `a zap to a merged game drops this player's extras and registers the target's`() = runTest(mainDispatcherRule.dispatcher) {
        SportsLiveExtraSources.register(PLAYING.id, PLAYING)
        SportsLiveZapSessions.start(
            SportsLiveZapSession.of(listOf(PLAYING, NEXT), PLAYING.streamKey, SportsLiveTab.LIVE_NOW, null, returnToHomeOnBack = false)!!
        )
        val resolver = mockk<SportsLiveZapResolver>()
        coEvery { resolver.resolve(any()) } returns STREAM
        val debrid = mockk<DirectDebridResolver>()
        coEvery { debrid.shouldResolveToPlayableStream(any()) } returns false
        val profiles = mockk<ProfileManager>()
        every { profiles.activeProfileId } returns MutableStateFlow(1)
        val repo = mockk<SportsLiveRepository>(relaxed = true)
        every { repo.endedKeys } returns MutableStateFlow(emptySet())
        val viewModel = SportsLiveZapViewModel(SavedStateHandle(mapOf("contentId" to PLAYING.id)), resolver, debrid, profiles, repo, mockk(relaxed = true))
        val events = mutableListOf<SportsLiveZapEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.collect { events += it } }

        assertTrue(viewModel.onKey(KeyEvent.KEYCODE_DPAD_DOWN, partyActive = false))
        advanceUntilIdle()

        assertEquals(NEXT, (events.single() as SportsLiveZapEvent.Navigate).target.item)
        assertFalse(SportsLiveExtraSources.isRegistered(PLAYING.id))
        assertTrue(SportsLiveExtraSources.isRegistered(NEXT.id))
    }

    private companion object {
        fun item(id: String, addon: String) = SportsLiveItem(
            meta = MetaPreview(
                id = id,
                type = ContentType.UNKNOWN,
                rawType = "sport",
                name = "$id vs Rivals",
                poster = null,
                posterShape = PosterShape.LANDSCAPE,
                background = null,
                logo = null,
                description = null,
                releaseInfo = "LIVE",
                imdbRating = null,
                genres = emptyList()
            ),
            addonId = addon,
            addonName = addon,
            addonBaseUrl = "https://$addon.example",
            catalogKey = "${addon}_sport_live",
            catalogName = "Live",
            kind = SportsLiveKind.EVENTS,
            isLive = true,
            isChannel = false,
            startMs = null,
            sport = null
        )

        val PLAYING = item("playing", "one").copy(alternates = listOf(item("playing-alt", "two")))
        val NEXT = item("next", "one").copy(alternates = listOf(item("next-alt", "two")))

        val STREAM = Stream(
            name = "One 720p",
            title = null,
            description = null,
            url = "https://cdn.example/one.m3u8",
            ytId = null,
            infoHash = null,
            fileIdx = null,
            externalUrl = null,
            behaviorHints = null,
            addonName = "one",
            addonLogo = null
        )
    }
}
