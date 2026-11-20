package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.TestPreferencesStore
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sportslive.SportsLiveOpenGame
import com.nuvio.tv.core.sportslive.alerts.GameReminder
import com.nuvio.tv.core.sportslive.alerts.RemindersState
import com.nuvio.tv.core.sportslive.alerts.SportsLiveAlertSettings
import com.nuvio.tv.core.sportslive.scores.CatchUp
import com.nuvio.tv.core.sportslive.scores.CatchUpMarks
import com.nuvio.tv.core.sportslive.scores.MyLeague
import com.nuvio.tv.core.sportslive.scores.MySport
import com.nuvio.tv.core.sportslive.scores.MySports
import com.nuvio.tv.core.sportslive.scores.ScoreDelayChoices
import com.nuvio.tv.core.sportslive.scores.ScoreSettings
import com.nuvio.tv.core.sportslive.scores.ScoresChoice
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.domain.model.CatalogOption
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.SportsLiveOverride
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsLivePreferencesDataStoreTest {
    private val profile1Store = TestPreferencesStore()
    private val profile2Store = TestPreferencesStore()
    private val activeProfile = MutableStateFlow(1)
    private val factory = mockk<ProfileDataStoreFactory> {
        every { get(1, SportsLivePreferencesDataStore.FEATURE) } returns profile1Store
        every { get(2, SportsLivePreferencesDataStore.FEATURE) } returns profile2Store
    }
    private val profiles = mockk<ProfileManager> {
        every { activeProfileId } returns this@SportsLivePreferencesDataStoreTest.activeProfile
    }

    @Test
    fun `defaults are on with no overrides`() = runTest {
        val store = SportsLivePreferencesDataStore(factory, profiles)
        assertTrue(store.showSection.first())
        assertTrue(store.showHomeRow.first())
        assertTrue(store.hoverPreview.first())
        assertEquals(emptyMap<String, SportsLiveOverride>(), store.overrides.first())
    }

    @Test
    fun `first-run setup shows once for a new profile, per profile`() = runTest {
        val store = SportsLivePreferencesDataStore(factory, profiles)
        assertTrue(store.claimFirstRunSetup())
        assertEquals(true, profile1Store.data.first()[booleanPreferencesKey("sports_live_setup_done")])
        assertFalse(store.claimFirstRunSetup())

        activeProfile.value = 2
        assertTrue(store.claimFirstRunSetup())
    }

    @Test
    fun `a profile that changed My sports is marked done without setup showing`() = runTest {
        val store = SportsLivePreferencesDataStore(factory, profiles)
        store.updateMySports { MySports(mapOf(MySport.SOCCER to emptySet())) }
        assertFalse(store.claimFirstRunSetup())
        assertEquals(true, profile1Store.data.first()[booleanPreferencesKey("sports_live_setup_done")])
        assertFalse(store.claimFirstRunSetup())
    }

    @Test
    fun `a profile that follows a team is marked done without setup showing`() = runTest {
        val store = SportsLivePreferencesDataStore(factory, profiles)
        store.follow(FollowedTeam("Boston Celtics", "Basketball"))
        assertFalse(store.claimFirstRunSetup())
        assertEquals(true, profile1Store.data.first()[booleanPreferencesKey("sports_live_setup_done")])
    }

    @Test
    fun `a profile already done never sees setup again, whatever it changes`() = runTest {
        profile1Store.updateData { mutablePreferencesOf(booleanPreferencesKey("sports_live_setup_done") to true) }
        val store = SportsLivePreferencesDataStore(factory, profiles)
        assertFalse(store.claimFirstRunSetup())
        store.unfollow(FollowedTeam("Nobody", null))
        assertFalse(store.claimFirstRunSetup())
    }

    @Test
    fun `the first-run decision`() {
        assertEquals(FirstRunSetup.SHOW, firstRunSetup(done = false, changedMySports = false, followsTeam = false))
        assertEquals(FirstRunSetup.MARK_DONE, firstRunSetup(done = false, changedMySports = true, followsTeam = false))
        assertEquals(FirstRunSetup.MARK_DONE, firstRunSetup(done = false, changedMySports = false, followsTeam = true))
        assertEquals(FirstRunSetup.NONE, firstRunSetup(done = true, changedMySports = false, followsTeam = false))
        assertEquals(FirstRunSetup.NONE, firstRunSetup(done = true, changedMySports = true, followsTeam = true))
    }

    @Test
    fun `when I open a game is unset by default and is stored per profile`() = runTest {
        val store = SportsLivePreferencesDataStore(factory, profiles)
        assertNull(store.openGame.first())

        store.setOpenGame(SportsLiveOpenGame.LET_ME_CHOOSE)
        assertEquals(SportsLiveOpenGame.LET_ME_CHOOSE, store.openGame.first())
        assertEquals("LET_ME_CHOOSE", profile1Store.data.first()[stringPreferencesKey("sports_live_open_game")])

        activeProfile.value = 2
        assertNull(store.openGame.first())
        activeProfile.value = 1
        assertEquals(SportsLiveOpenGame.LET_ME_CHOOSE, store.openGame.first())
    }

    @Test
    fun `an unknown stored open-game value reads as unset`() = runTest {
        profile1Store.updateData { mutablePreferencesOf(stringPreferencesKey("sports_live_open_game") to "SOMETHING_ELSE") }
        val store = SportsLivePreferencesDataStore(factory, profiles)
        assertNull(store.openGame.first())
    }

    @Test
    fun `the catch-up row is on by default, per profile`() = runTest {
        val store = SportsLivePreferencesDataStore(factory, profiles)
        assertTrue(store.catchUpRow.first())
        store.setCatchUpRow(false)
        assertEquals(false, store.catchUpRow.first())
        activeProfile.value = 2
        assertTrue(store.catchUpRow.first())
    }

    @Test
    fun `catch-up marks round trip and old ones are dropped`() = runTest {
        val store = SportsLivePreferencesDataStore(factory, profiles)
        val now = 10 * CatchUp.WINDOW_MS
        store.updateCatchUpMarks(now) {
            CatchUpMarks(seen = mapOf("e:1" to now, "old" to 1L), surfaced = mapOf("e|2" to now - 1_000L))
        }
        assertEquals(CatchUpMarks(mapOf("e:1" to now), mapOf("e|2" to now - 1_000L)), store.catchUpMarks.first())
    }

    @Test
    fun `opening keeps since within a visit and moves it to the last visit after a gap`() = runTest {
        val store = SportsLivePreferencesDataStore(factory, profiles)
        val now = 10 * CatchUp.WINDOW_MS
        assertEquals(now - CatchUp.WINDOW_MS, store.openCatchUp(now))
        assertEquals(now - CatchUp.WINDOW_MS, store.openCatchUp(now + 60_000L))
        val later = now + 60_000L + CatchUp.VISIT_GAP_MS
        assertEquals(now + 60_000L, store.openCatchUp(later))
    }

    @Test
    fun `hover preview round trips under its own key`() = runTest {
        val store = SportsLivePreferencesDataStore(factory, profiles)
        store.setHoverPreview(false)
        assertEquals(false, store.hoverPreview.first())
        assertEquals(false, profile1Store.data.first()[booleanPreferencesKey("sports_live_hover_preview")])
        store.setHoverPreview(true)
        assertTrue(store.hoverPreview.first())
    }

    @Test
    fun `setOverride round trips and null clears`() = runTest {
        val store = SportsLivePreferencesDataStore(factory, profiles)
        store.setOverride("a_sport_x", SportsLiveOverride.EXCLUDE)
        assertEquals(mapOf("a_sport_x" to SportsLiveOverride.EXCLUDE), store.overrides.first())
        store.setOverride("a_sport_x", null)
        assertEquals(emptyMap<String, SportsLiveOverride>(), store.overrides.first())
    }

    @Test
    fun `switching profile reads the other store`() = runTest {
        val store = SportsLivePreferencesDataStore(factory, profiles)
        store.setOverride("a_sport_x", SportsLiveOverride.EXCLUDE)
        activeProfile.value = 2
        assertEquals(emptyMap<String, SportsLiveOverride>(), store.overrides.first())
    }

    @Test
    fun `followed teams round trip in order, skip duplicates, and unfollow by name and sport`() = runTest {
        val store = SportsLivePreferencesDataStore(factory, profiles)
        assertEquals(emptyList<FollowedTeam>(), store.followedTeams.first())

        store.follow(FollowedTeam("Celtics", "Basketball"))
        store.follow(FollowedTeam("Arsenal", null))
        store.follow(FollowedTeam(" celtics ", "BASKETBALL"))
        store.follow(FollowedTeam("Celtics", null))
        store.follow(FollowedTeam("  ", "Soccer"))
        assertEquals(
            listOf(FollowedTeam("Celtics", "Basketball"), FollowedTeam("Arsenal", null), FollowedTeam("Celtics", null)),
            store.followedTeams.first()
        )

        store.unfollow(FollowedTeam("CELTICS", "basketball"))
        assertEquals(listOf(FollowedTeam("Arsenal", null), FollowedTeam("Celtics", null)), store.followedTeams.first())
    }

    @Test
    fun `a team followed from team search keeps its logo and unfollows by name and sport`() = runTest {
        val store = SportsLivePreferencesDataStore(factory, profiles)
        val uconn = FollowedTeam(
            "UConn Huskies", "College Basketball", espnId = "41", abbreviation = "CONN",
            league = "basketball/mens-college-basketball", logo = "https://a.espncdn.com/i/teamlogos/ncaa/500/41.png"
        )
        store.follow(uconn)
        assertEquals(listOf(uconn), store.followedTeams.first())

        store.unfollow(uconn)
        assertEquals(emptyList<FollowedTeam>(), store.followedTeams.first())
    }

    @Test
    fun `a team followed from ESPN keeps its id, abbreviation and league, old name-only follows still read`() = runTest {
        profile1Store.updateData {
            mutablePreferencesOf(stringPreferencesKey("sports_live_followed_teams") to """[{"name":"Arsenal","sport":"Soccer"}]""")
        }
        val store = SportsLivePreferencesDataStore(factory, profiles)
        val celtics = FollowedTeam("Boston Celtics", "Basketball", espnId = "2", abbreviation = "BOS", league = "basketball/nba")
        store.follow(celtics)
        assertEquals(listOf(FollowedTeam("Arsenal", "Soccer"), celtics), store.followedTeams.first())

        store.unfollow(celtics)
        assertEquals(listOf(FollowedTeam("Arsenal", "Soccer")), store.followedTeams.first())
    }

    @Test
    fun `followed teams are stored per profile as plain maps`() = runTest {
        val store = SportsLivePreferencesDataStore(factory, profiles)
        store.follow(FollowedTeam("Celtics", "Basketball"))
        val json = profile1Store.data.first()[stringPreferencesKey("sports_live_followed_teams")]
        assertEquals("""[{"name":"Celtics","sport":"Basketball"}]""", json)

        activeProfile.value = 2
        assertEquals(emptyList<FollowedTeam>(), store.followedTeams.first())
        store.follow(FollowedTeam("Arsenal", null))
        assertEquals(listOf(FollowedTeam("Arsenal", null)), store.followedTeams.first())

        activeProfile.value = 1
        assertEquals(listOf(FollowedTeam("Celtics", "Basketball")), store.followedTeams.first())
    }

    @Test
    fun `malformed followed teams json yields no teams`() = runTest {
        profile1Store.updateData {
            mutablePreferencesOf(stringPreferencesKey("sports_live_followed_teams") to "{oops")
        }
        val store = SportsLivePreferencesDataStore(factory, profiles)
        assertEquals(emptyList<FollowedTeam>(), store.followedTeams.first())
    }

    @Test
    fun `malformed overrides json yields empty map`() = runTest {
        profile1Store.updateData {
            mutablePreferencesOf(stringPreferencesKey("sports_live_overrides") to "not json")
        }
        val store = SportsLivePreferencesDataStore(factory, profiles)
        assertEquals(emptyMap<String, SportsLiveOverride>(), store.overrides.first())
    }

    @Test
    fun `catalog options default to none, round trip, and null clears`() = runTest {
        val store = SportsLivePreferencesDataStore(factory, profiles)
        assertEquals(emptyMap<String, CatalogOption>(), store.catalogOptions.first())

        val us = CatalogOption("genre", "United States")
        store.setCatalogOption("cnc_tv_live", us)
        store.setCatalogOption("cnc_tv_events", CatalogOption("genre", "Football"))
        assertEquals(
            mapOf("cnc_tv_live" to us, "cnc_tv_events" to CatalogOption("genre", "Football")),
            store.catalogOptions.first()
        )

        store.setCatalogOption("cnc_tv_events", null)
        assertEquals(mapOf("cnc_tv_live" to us), store.catalogOptions.first())
    }

    @Test
    fun `catalog options are stored as an extra and value map per catalog key`() = runTest {
        val store = SportsLivePreferencesDataStore(factory, profiles)
        store.setCatalogOption("cnc_tv_live", CatalogOption("genre", "United States"))

        val json = profile1Store.data.first()[stringPreferencesKey("sports_live_catalog_options")]
        assertEquals("""{"cnc_tv_live":{"extra":"genre","value":"United States"}}""", json)
    }

    @Test
    fun `catalog options follow the active profile`() = runTest {
        val store = SportsLivePreferencesDataStore(factory, profiles)
        store.setCatalogOption("cnc_tv_live", CatalogOption("genre", "United States"))

        activeProfile.value = 2
        assertEquals(emptyMap<String, CatalogOption>(), store.catalogOptions.first())
        store.setCatalogOption("cnc_tv_live", CatalogOption("genre", "Canada"))

        activeProfile.value = 1
        assertEquals(mapOf("cnc_tv_live" to CatalogOption("genre", "United States")), store.catalogOptions.first())
        activeProfile.value = 2
        assertEquals(mapOf("cnc_tv_live" to CatalogOption("genre", "Canada")), store.catalogOptions.first())
    }

    @Test
    fun `malformed or incomplete catalog options are dropped`() = runTest {
        profile1Store.updateData {
            mutablePreferencesOf(stringPreferencesKey("sports_live_catalog_options") to "not json")
        }
        val store = SportsLivePreferencesDataStore(factory, profiles)
        assertEquals(emptyMap<String, CatalogOption>(), store.catalogOptions.first())

        profile1Store.updateData {
            mutablePreferencesOf(
                stringPreferencesKey("sports_live_catalog_options") to
                    """{"a":{"extra":"genre"},"b":{"value":"X"},"c":{"extra":"genre","value":"Canada"}}"""
            )
        }
        assertEquals(mapOf("c" to CatalogOption("genre", "Canada")), store.catalogOptions.first())
    }

    @Test
    fun `the new sports hint is for My sports saved without any individual sport`() {
        assertEquals(false, shouldShowNewSportsHint(null))
        assertEquals(false, shouldShowNewSportsHint(emptySet()))
        assertTrue(shouldShowNewSportsHint(setOf("basketball", "soccer")))
        assertEquals(false, shouldShowNewSportsHint(setOf("basketball", "tennis")))
        // A later version's sport alone is not a pick of today's individual ones either.
        assertTrue(shouldShowNewSportsHint(setOf("hockey", "curling")))
    }

    @Test
    fun `the new sports hint is claimed once per profile`() = runTest {
        val store = SportsLivePreferencesDataStore(factory, profiles)
        store.updateMySports { MySports.decode(setOf("basketball")) }
        assertEquals(listOf(true, false), List(2) { store.claimNewSportsHint() })
        assertEquals(true, profile1Store.data.first()[booleanPreferencesKey("sports_live_new_sports_hint_seen")])
        // A profile on the default (individual sports included) never gets it, and it is seen.
        activeProfile.value = 2
        assertEquals(false, store.claimNewSportsHint())
        store.updateMySports { MySports.decode(setOf("basketball")) }
        assertEquals(false, store.claimNewSportsHint())
    }

    @Test
    fun `score settings default to Show with a 60 s delay and are per profile`() = runTest {
        val store = SportsLivePreferencesDataStore(factory, profiles)
        assertEquals(ScoreSettings(ScoresMode.SHOW, 60), store.scoreSettings.first())
        store.setScoresMode(ScoresMode.HIDE_UNTIL_FOCUSED)
        store.setScoreDelaySeconds(60)
        assertEquals(ScoreSettings(ScoresMode.HIDE_UNTIL_FOCUSED, 60), store.scoreSettings.first())
        store.setScoreDelaySeconds(45) // not a choice: Off
        assertEquals(0, store.scoreSettings.first().delaySeconds)
        activeProfile.value = 2
        assertEquals(ScoreSettings(delaySeconds = ScoreDelayChoices.DEFAULT_SECONDS), store.scoreSettings.first())
        // A stored Off stays Off: only a profile that never set it gets the default.
        store.setScoreDelaySeconds(0)
        assertEquals(0, store.scoreSettings.first().delaySeconds)
        store.setScoresMode(ScoresMode.OFF)
        assertEquals(ScoresMode.OFF, store.scoreSettings.first().mode)
        activeProfile.value = 1
        assertEquals(ScoresMode.HIDE_UNTIL_FOCUSED, store.scoreSettings.first().mode)
    }

    @Test
    fun `Real-time scores and each source's nudge are kept per profile`() = runTest {
        val store = SportsLivePreferencesDataStore(factory, profiles)
        assertFalse(store.scoreSettings.first().realTime)
        store.setRealTimeScores(true)
        assertTrue(store.scoreSettings.first().realTime)
        assertEquals(0L, store.scoreSettings.first().effectiveDelayMs)

        assertEquals(emptyMap<String, Int>(), store.scoreNudges.first())
        assertEquals(15, store.nudgeScores("addon-a"))
        assertEquals(30, store.nudgeScores("addon-a"))
        assertEquals(15, store.nudgeScores("addon-b"))
        assertEquals(mapOf("addon-a" to 30, "addon-b" to 15), store.scoreNudges.first())
        repeat(20) { store.nudgeScores("addon-a") }
        assertEquals(180, store.scoreNudges.first().getValue("addon-a"))
        store.resetScoreNudge("addon-a")
        assertEquals(mapOf("addon-b" to 15), store.scoreNudges.first())

        activeProfile.value = 2
        assertFalse(store.scoreSettings.first().realTime)
        assertEquals(emptyMap<String, Int>(), store.scoreNudges.first())
    }

    @Test
    fun `the scores choice reads the stored mode and my teams switch and writes both`() = runTest {
        val store = SportsLivePreferencesDataStore(factory, profiles)
        assertEquals(ScoresChoice(), store.scoresChoice.first())
        // A profile from before live.20: Show plus "Hide scores of my teams' games" reads as my teams only.
        store.setScoresMode(ScoresMode.SHOW)
        store.setHideMyTeamsScores(true)
        assertEquals(ScoresChoice(ScoresMode.HIDE_UNTIL_FOCUSED, myTeamsOnly = true), store.scoresChoice.first())
        assertEquals(ScoresMode.SHOW, store.scoreSettings.first().mode)
        assertTrue(store.spoilerProtection.first().myTeams)
        // Widening to every game stores Hide until I reveal and turns the my teams protection off.
        store.updateScoresChoice { it.withMyTeamsOnly(false) }
        assertEquals(ScoresChoice(ScoresMode.HIDE_UNTIL_FOCUSED), store.scoresChoice.first())
        assertEquals(ScoresMode.HIDE_UNTIL_FOCUSED, store.scoreSettings.first().mode)
        assertFalse(store.hideMyTeamsScores.first())
        store.updateScoresChoice { it.withMode(ScoresMode.OFF) }
        assertEquals(ScoresMode.OFF, store.scoreSettings.first().mode)
        assertFalse(store.spoilerProtection.first().myTeams)
    }

    @Test
    fun `my sports default to every sport, round trip per profile, and the default clears the key`() = runTest {
        val store = SportsLivePreferencesDataStore(factory, profiles)
        val key = stringSetPreferencesKey("sports_live_my_sports")
        assertEquals(MySports.DEFAULT, store.mySports.first())
        // The unused recent-leagues entry of scores S3 is cleared by the first save.
        val recentKey = stringPreferencesKey("sports_live_recent_score_leagues")
        profile1Store.updateData { mutablePreferencesOf(recentKey to "{\"basketball/nba\":1}") }
        store.updateMySports { it.toggleSport(MySport.SOCCER).toggleLeague(MyLeague.COLLEGE_BASKETBALL) }
        val saved = store.mySports.first()
        assertEquals(MySport.entries - MySport.SOCCER, saved.effectiveSports)
        assertEquals(setOf(MyLeague.NBA, MyLeague.COLLEGE_BASKETBALL), saved.leaguesOf(MySport.BASKETBALL))
        assertTrue("basketball/college-basketball" in profile1Store.data.first()[key].orEmpty())
        assertNull(profile1Store.data.first()[recentKey])
        activeProfile.value = 2
        assertEquals(MySports.DEFAULT, store.mySports.first())
        activeProfile.value = 1
        store.updateMySports { MySports.DEFAULT }
        assertNull(profile1Store.data.first()[key])
        // Tokens of a later version (an unknown sport) are skipped, not an error.
        profile2Store.updateData { mutablePreferencesOf(key to setOf("cricket", "cricket/ipl", "hockey")) }
        activeProfile.value = 2
        assertEquals(listOf(MySport.HOCKEY), store.mySports.first().effectiveSports)
    }

    @Test
    fun `reminders and opt-outs round trip per profile`() = runTest {
        val store = SportsLivePreferencesDataStore(factory, profiles)
        assertEquals(RemindersState(), store.reminders.first())
        val reminder = GameReminder(setOf("a|1", "b|2"), "Pacers @ 76ers", home = "76ers", away = "Pacers", startMs = 1_800_000_000_000L)
        val optOut = GameReminder(setOf("c|3"), "Bulls vs Bucks", home = null, away = null, startMs = 1_800_000_100_000L)

        val saved = store.updateReminders { it.copy(reminders = listOf(reminder), optOuts = listOf(optOut)) }
        assertEquals(listOf(reminder), saved.reminders)
        assertEquals(RemindersState(listOf(reminder), listOf(optOut)), store.reminders.first())

        activeProfile.value = 2
        assertEquals(RemindersState(), store.reminders.first())
        activeProfile.value = 1
        assertEquals(listOf(reminder), store.reminders.first().reminders)
    }

    @Test
    fun `unreadable stored reminders read as none`() = runTest {
        profile1Store.updateData { mutablePreferencesOf(stringPreferencesKey("sports_live_reminders") to "not json") }
        val store = SportsLivePreferencesDataStore(factory, profiles)
        assertEquals(emptyList<GameReminder>(), store.reminders.first().reminders)
    }

    @Test
    fun `alert switches are on by default, per profile, and Remind me for my teams reaches the reminders`() = runTest {
        val store = SportsLivePreferencesDataStore(factory, profiles)
        assertEquals(SportsLiveAlertSettings(), store.alertSettings.first())
        store.setRemindMyTeams(false)
        store.setScoreAlerts(false)
        store.setFullTimeAlerts(false)
        assertEquals(SportsLiveAlertSettings(remindMyTeams = false, scoreAlerts = false, fullTimeAlerts = false), store.alertSettings.first())
        assertEquals(false, store.reminders.first().remindMyTeams)

        activeProfile.value = 2
        assertEquals(SportsLiveAlertSettings(), store.alertSettings.first())
    }

    @Test
    fun `stored reminders of games started over six hours ago are dropped when read`() = runTest {
        val store = SportsLivePreferencesDataStore(factory, profiles)
        val start = 1_800_000_000_000L
        val reminder = GameReminder(setOf("a|1"), "Pacers @ 76ers", home = "76ers", away = "Pacers", startMs = start)
        store.updateReminders { it.copy(reminders = listOf(reminder), optOuts = listOf(reminder)) }
        store.nowMs = { start + 5 * 60 * 60_000L }
        assertEquals(listOf(reminder), store.reminders.first().reminders)
        store.nowMs = { start + 7 * 60 * 60_000L }
        assertEquals(RemindersState(), store.reminders.first())
    }
}
