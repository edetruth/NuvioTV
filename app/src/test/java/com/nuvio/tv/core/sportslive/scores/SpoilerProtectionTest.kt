package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.TestPreferencesStore
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sportslive.SportsLiveFullTimeToasts
import com.nuvio.tv.core.sportslive.SportsLivePlayedTime
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.MINUTE
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.START
import com.nuvio.tv.core.sportslive.alerts.AlertGame
import com.nuvio.tv.core.sportslive.alerts.SportsLiveAlert
import com.nuvio.tv.core.sportslive.alerts.SportsLiveAlertEngine
import com.nuvio.tv.core.sportslive.alerts.SportsLiveAlertSettings
import com.nuvio.tv.core.sportslive.alerts.SportsLiveWatching
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.SportsLivePreferencesDataStore
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.ui.screens.home.SportsLiveHomeRow
import com.nuvio.tv.ui.screens.sportslive.ResultSpoilerReveal
import com.nuvio.tv.ui.screens.sportslive.SportsLiveResultsUiState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpoilerProtectionTest {

    private val strings = ScoreChipStrings(
        score = "%1\$d–%2\$d", live = "%1\$s · %2\$s", final = "%1\$s %2\$s",
        finalNote = "Final", postponed = "Postponed", hidden = "Score hidden"
    )
    private val card = AlertFixtures.item()
    private val final = AlertFixtures.final(101, 99)
    private val live = AlertFixtures.live(60, 58)
    private val now = START + 3 * 60 * MINUTE
    private val saved = SpoilerProtection(games = SpoilerProtection.saved(emptyList(), final, now), nowMs = now)

    @Test
    fun `a protected game hides its score on every surface`() {
        // Unprotected, every surface shows it (the fixture matches the card).
        assertTrue(ScoreChips.of(card, final, ScoresMode.SHOW, revealed = false) is ScoreChip.Final)

        val protectedFinal = saved.stamp(final)
        val protectedLive = saved.stamp(live)
        assertTrue(protectedFinal.spoilerProtected && protectedLive.spoilerProtected)

        // Live Now, Upcoming and the in-player panel: the chip, even with a focus reveal.
        assertEquals(ScoreChip.Hidden, ScoreChips.of(card, protectedFinal, ScoresMode.SHOW, revealed = true))
        assertEquals(ScoreChip.Hidden, ScoreChips.of(card, protectedLive, ScoresMode.HIDE_UNTIL_FOCUSED, revealed = true))
        // The details pane and the situation line follow the chip.
        assertEquals(ScoreDetails.Hidden, ScoreDetailsBuilder.of(card, protectedFinal, ScoresMode.SHOW, revealed = true))
        assertNull(GameSituations.of(card, protectedLive, ScoresMode.SHOW, revealed = true))
        // Home's Live now row.
        val labels = SportsLiveHomeRow.scoreLabels(listOf(card), mapOf(card.streamKey to protectedFinal), ScoresMode.SHOW, strings, "● %1\$s")
        assertEquals(mapOf(card.streamKey to "Score hidden"), labels)
        // Results (and the Game page it opens: not revealed).
        val row = ResultRow(final, followed = false, note = FinalNote.FT)
        assertFalse(SportsLiveResultsUiState(mode = ScoresMode.SHOW, revealed = setOf(final.eventId), protection = saved).showsScore(row))
        assertTrue(SportsLiveResultsUiState(mode = ScoresMode.SHOW).showsScore(row))
        assertFalse(ResultSpoilerReveal.showsScore(ScoresMode.SHOW, emptySet(), final.eventId, spoilerProtected = true))
        // A Team page's last results.
        assertFalse(TeamPageSpoilers.showsResult(ScoresMode.SHOW, revealed = true, focused = true, spoilerProtected = saved.protects(final)))
        // The full-time toast of the game playing.
        assertNull(SportsLiveFullTimeToasts.of(card, protectedFinal, ScoresMode.SHOW, seenLive = true))
    }

    @Test
    fun `alerts are muted for protected games`() {
        fun alerts(protection: SpoilerProtection): List<SportsLiveAlert> {
            val engine = SportsLiveAlertEngine()
            val watching = SportsLiveWatching(playingIds = setOf("movie"), inPlayer = true, streamStartedAtMs = START - 60 * MINUTE)
            fun step(at: Long, snapshot: ScoreSnapshot) =
                engine.update(at, listOf(AlertGame(card, protection.stamp(snapshot), false, setOf(ScoreSide.HOME))), SportsLiveAlertSettings(), ScoresMode.SHOW, watching)
            step(START + 10 * MINUTE, AlertFixtures.live(10, 8))
            val score = step(START + 11 * MINUTE, AlertFixtures.live(12, 8))
            val fullTime = step(START + 130 * MINUTE, AlertFixtures.final(101, 99))
            return score + fullTime
        }
        // Unprotected: a score toast and a full-time toast.
        assertEquals(2, alerts(SpoilerProtection(nowMs = now)).size)
        assertEquals(emptyList<SportsLiveAlert>(), alerts(saved))
    }

    @Test
    fun `protection ends on reveal and after seven days`() {
        assertTrue(saved.protects(final))
        val revealed = SpoilerProtection(games = SpoilerProtection.ended(saved.games, final, now + MINUTE), nowMs = now + MINUTE)
        assertFalse(revealed.protects(final))
        assertFalse(revealed.isSaved(final.eventId))

        val day = 24 * 60 * MINUTE
        assertTrue(saved.copy(nowMs = now + 7 * day - 1).protects(final))
        assertFalse(saved.copy(nowMs = now + 7 * day).protects(final))
        // Long-ended entries are dropped from storage.
        assertTrue(SpoilerProtection.pruned(revealed.games, now + 9 * day).isEmpty())
    }

    @Test
    fun `hide my teams' results protects followed teams' finished games until watched or revealed`() {
        val followed = listOf(FollowedTeam(name = "Philadelphia 76ers", sport = "Basketball"))
        val auto = SpoilerProtection(myTeams = true, followed = followed, nowMs = now)
        assertTrue(auto.protects(final))
        // M2: results only. A followed team's live game keeps its score chip on Live now, the panel and the guide.
        assertFalse(auto.protects(live))
        assertFalse(auto.stamp(live).spoilerProtected)
        assertTrue(auto.stamp(final).spoilerProtected)
        // A game saved for later is still hidden while live.
        assertTrue(auto.copy(games = SpoilerProtection.saved(emptyList(), live, now)).protects(live))
        // Off by default, and never another team's game or one not started.
        assertFalse(auto.copy(myTeams = false).protects(final))
        assertFalse(auto.copy(followed = listOf(FollowedTeam(name = "Boston Celtics", sport = "Basketball"))).protects(final))
        assertFalse(auto.protects(AlertFixtures.pre()))
        // Watched live (or revealed): an ended entry keeps it unprotected.
        assertFalse(auto.copy(games = SpoilerProtection.ended(emptyList(), live, now)).protects(final))
        // Over seven days after its start.
        assertFalse(auto.copy(nowMs = START + 7 * 24 * 60 * MINUTE).protects(final))
    }

    @Test
    fun `a saved game's replays are found by their strict match or by their sides`() {
        val replay = AlertFixtures.item(name = "76ers vs Pacers", id = "replay").copy(kind = SportsLiveKind.REPLAY, startMs = START + 4 * 60 * MINUTE)
        val other = AlertFixtures.item(name = "Celtics vs Knicks", id = "other").copy(kind = SportsLiveKind.REPLAY)
        val strict = AlertFixtures.item(name = "Full game replay", id = "strict").copy(kind = SportsLiveKind.REPLAY, teams = null)
        assertEquals(
            setOf(replay.streamKey, strict.streamKey),
            saved.savedReplayKeys(listOf(replay, other, strict), mapOf(strict.streamKey to final))
        )
    }

    // The store: the ends that happen on their own.

    private fun TestScope.store(clock: () -> Long): Triple<SpoilerProtectionStore, SportsLivePreferencesDataStore, SportsLivePlayedTime> {
        val data = TestPreferencesStore()
        val factory = mockk<ProfileDataStoreFactory> { every { get(1, SportsLivePreferencesDataStore.FEATURE) } returns data }
        val profiles = mockk<ProfileManager> { every { activeProfileId } returns MutableStateFlow(1) }
        val prefs = SportsLivePreferencesDataStore(factory, profiles).also { it.nowMs = clock }
        val played = SportsLivePlayedTime { 0L }
        return Triple(SpoilerProtectionStore(prefs, played, clock, backgroundScope), prefs, played)
    }

    @Test
    fun `protection ends when the saved game's replay is played to its end`() = runTest {
        var clock = now
        val (store, prefs, played) = store { clock }
        runCurrent()
        store.saveForLater(final)
        assertTrue(prefs.spoilerProtection.first().protects(final))

        val replay = AlertFixtures.item(name = "Pacers @ 76ers", id = "replay-1").copy(kind = SportsLiveKind.REPLAY)
        store.onReplayStarted(listOf(replay), matched = final)
        // Stopping part way changes nothing; another content's end neither.
        played.onFinished("something-else")
        runCurrent()
        assertTrue(prefs.spoilerProtection.first().protects(final))

        clock += 150 * MINUTE
        played.onFinished(replay.id)
        runCurrent()
        val after = prefs.spoilerProtection.first()
        assertFalse(after.protects(final))
        assertTrue(after.saved.isEmpty())
    }

    @Test
    fun `Reveal result ends protection through the store`() = runTest {
        val (store, prefs, _) = store { now }
        store.saveForLater(final)
        assertEquals(listOf(final.eventId), prefs.spoilerProtection.first().saved.map { it.eventId })
        store.reveal(final)
        assertFalse(prefs.spoilerProtection.first().protects(final))
    }
}
