package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.TestPreferencesStore
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sportslive.SportsLiveOpenGame
import com.nuvio.tv.core.sportslive.scores.MyLeague
import com.nuvio.tv.core.sportslive.scores.MySport
import com.nuvio.tv.core.sportslive.scores.MySports
import com.nuvio.tv.core.sportslive.scores.ScoreSettings
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
    fun `when I open a game defaults to Play best stream and is stored per profile`() = runTest {
        val store = SportsLivePreferencesDataStore(factory, profiles)
        assertEquals(SportsLiveOpenGame.PLAY_BEST, store.openGame.first())

        store.setOpenGame(SportsLiveOpenGame.LET_ME_CHOOSE)
        assertEquals(SportsLiveOpenGame.LET_ME_CHOOSE, store.openGame.first())
        assertEquals("LET_ME_CHOOSE", profile1Store.data.first()[stringPreferencesKey("sports_live_open_game")])

        activeProfile.value = 2
        assertEquals(SportsLiveOpenGame.PLAY_BEST, store.openGame.first())
        activeProfile.value = 1
        assertEquals(SportsLiveOpenGame.LET_ME_CHOOSE, store.openGame.first())
    }

    @Test
    fun `an unknown stored open-game value reads as Play best stream`() = runTest {
        profile1Store.updateData { mutablePreferencesOf(stringPreferencesKey("sports_live_open_game") to "SOMETHING_ELSE") }
        val store = SportsLivePreferencesDataStore(factory, profiles)
        assertEquals(SportsLiveOpenGame.PLAY_BEST, store.openGame.first())
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
    fun `dual keys hint shows below three shown`() {
        assertTrue(shouldShowDualKeysHint(0))
        assertTrue(shouldShowDualKeysHint(2))
        assertEquals(false, shouldShowDualKeysHint(3))
        assertEquals(false, shouldShowDualKeysHint(10))
    }

    @Test
    fun `dual keys hint is claimed on the first three entries per profile`() = runTest {
        val store = SportsLivePreferencesDataStore(factory, profiles)
        assertEquals(listOf(true, true, true, false, false), List(5) { store.claimDualKeysHint() })
        assertEquals(3, profile1Store.data.first()[intPreferencesKey("sports_live_dual_hint_shown")])
        activeProfile.value = 2
        assertTrue(store.claimDualKeysHint())
    }

    @Test
    fun `score settings default to Show with no delay and are per profile`() = runTest {
        val store = SportsLivePreferencesDataStore(factory, profiles)
        assertEquals(ScoreSettings(ScoresMode.SHOW, 0), store.scoreSettings.first())
        store.setScoresMode(ScoresMode.HIDE_UNTIL_FOCUSED)
        store.setScoreDelaySeconds(60)
        assertEquals(ScoreSettings(ScoresMode.HIDE_UNTIL_FOCUSED, 60), store.scoreSettings.first())
        store.setScoreDelaySeconds(45) // not a choice: Off
        assertEquals(0, store.scoreSettings.first().delaySeconds)
        activeProfile.value = 2
        assertEquals(ScoreSettings(), store.scoreSettings.first())
        store.setScoresMode(ScoresMode.OFF)
        assertEquals(ScoresMode.OFF, store.scoreSettings.first().mode)
        activeProfile.value = 1
        assertEquals(ScoresMode.HIDE_UNTIL_FOCUSED, store.scoreSettings.first().mode)
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
        assertEquals(listOf(MySport.BASKETBALL, MySport.FOOTBALL, MySport.HOCKEY, MySport.BASEBALL), saved.effectiveSports)
        assertEquals(setOf(MyLeague.NBA, MyLeague.COLLEGE_BASKETBALL), saved.leaguesOf(MySport.BASKETBALL))
        assertTrue("basketball/college-basketball" in profile1Store.data.first()[key].orEmpty())
        assertNull(profile1Store.data.first()[recentKey])
        activeProfile.value = 2
        assertEquals(MySports.DEFAULT, store.mySports.first())
        activeProfile.value = 1
        store.updateMySports { MySports.DEFAULT }
        assertNull(profile1Store.data.first()[key])
        // Tokens of a later version (an individual sport) are skipped, not an error.
        profile2Store.updateData { mutablePreferencesOf(key to setOf("tennis", "tennis/atp", "hockey")) }
        activeProfile.value = 2
        assertEquals(listOf(MySport.HOCKEY), store.mySports.first().effectiveSports)
    }
}
