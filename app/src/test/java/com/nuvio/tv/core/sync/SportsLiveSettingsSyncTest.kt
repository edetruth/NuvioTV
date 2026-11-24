package com.nuvio.tv.core.sync

import androidx.datastore.preferences.core.edit
import com.nuvio.tv.TestPreferencesStore
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sportslive.SportsLiveChannelFavorites
import com.nuvio.tv.core.sportslive.SportsLiveChannelRef
import com.nuvio.tv.core.sportslive.SportsLiveGameEndAction
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures
import com.nuvio.tv.core.sportslive.alerts.GameReminder
import com.nuvio.tv.core.sportslive.scores.MySport
import com.nuvio.tv.core.sportslive.scores.MySports
import com.nuvio.tv.core.sportslive.scores.ScoreDelayChoices
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.core.sportslive.scores.SpoilerProtection
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.SportsLivePreferencesDataStore
import com.nuvio.tv.domain.model.CatalogOption
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveOverride
import com.nuvio.tv.domain.model.UserProfile
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.jan.supabase.serializer.KotlinXSerializer
import io.ktor.http.Headers
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SportsLiveSettingsSyncTest {
    private val dispatcher = StandardTestDispatcher()
    private val services = mutableListOf<ProfileSettingsSyncService>()
    private val start = AlertFixtures.START
    private val celtics = FollowedTeam("Boston Celtics", "Basketball", espnId = "2", league = "basketball/nba")
    private val lakers = FollowedTeam("Los Angeles Lakers", "Basketball", espnId = "13", league = "basketball/nba")

    @Before
    fun setUp() {
        mockkStatic(Dispatchers::class)
        every { Dispatchers.IO } returns dispatcher
    }

    @After
    fun tearDown() {
        services.forEach { service ->
            val scope = ProfileSettingsSyncService::class.java.getDeclaredField("scope")
                .apply { isAccessible = true }.get(service) as CoroutineScope
            scope.cancel()
        }
        unmockkStatic(Dispatchers::class)
    }

    /** One TV's Sports & Live settings (profile 1), on its own clock. */
    private class Tv(var clock: Long) {
        val data = TestPreferencesStore()
        val prefs = SportsLivePreferencesDataStore(
            mockk<ProfileDataStoreFactory> { every { get(1, SportsLivePreferencesDataStore.FEATURE) } returns data },
            mockk<ProfileManager> { every { activeProfileId } returns MutableStateFlow(1) }
        ).also { it.nowMs = { clock } }

        fun export(): JsonObject = SportsLiveSettingsSync.export(data.value, clock)

        /** Merges [other]'s upload, as a pull does. */
        suspend fun pull(other: Tv) {
            val upload = other.export()
            data.edit { SportsLiveSettingsSync.merge(it, upload, clock) }
        }
    }

    @Test
    fun `sports settings make a round trip through the account`() = runTest(dispatcher) {
        val harness = harness()
        val prefs = harness.sports
        // The upload drops reminders by the real clock: this test runs on it.
        val now = System.currentTimeMillis()
        prefs.nowMs = { now }
        prefs.follow(celtics)
        prefs.updateMySports { MySports.DEFAULT.toggleSport(MySport.SOCCER) }
        prefs.setScoresMode(ScoresMode.HIDE_UNTIL_FOCUSED)
        prefs.setScoreDelaySeconds(ScoreDelayChoices.SECONDS.last())
        prefs.setScoreAlertLevel(com.nuvio.tv.core.sportslive.alerts.ScoreAlertLevel.EVERY_SCORE)
        prefs.setCatchUpRow(false)
        prefs.setHideMyTeamsScores(true)
        prefs.hideTitle("Morning Talk Show")
        prefs.updateReminders { it.copy(reminders = listOf(GameReminder(setOf("addon|item"), "Celtics at Lakers", "Los Angeles Lakers", "Boston Celtics", now + 60 * AlertFixtures.MINUTE))) }
        prefs.updateProtectedGames { SpoilerProtection.saved(it, AlertFixtures.final(101, 99), now) }
        val before = snapshot(prefs)

        harness.service.pushCurrentProfileToRemote().getOrThrow()
        // App data cleared (or a new TV): nothing local.
        harness.factory.get(1, SportsLivePreferencesDataStore.FEATURE).edit { it.clear() }
        assertEquals(emptyList<FollowedTeam>(), prefs.followedTeams.first())

        assertTrue(harness.service.pullCurrentProfileFromRemote().getOrThrow())

        val after = snapshot(prefs)
        assertEquals(before.copy(reminderKeys = emptySet()), after)
        assertEquals(listOf(celtics), after.followed)
        assertTrue(after.hideMyTeams)
    }

    @Test
    fun `an empty store exports nothing, as a cloud copy without Sports and Live reads`() {
        val exported = SportsLiveSettingsSync.export(androidx.datastore.preferences.core.emptyPreferences(), 1_000L)
        assertEquals(SportsLiveSettingsSync.canonical(kotlinx.serialization.json.JsonObject(emptyMap())), SportsLiveSettingsSync.canonical(exported))
    }

    @Test
    fun `no addon data is synced`() = runTest(dispatcher) {
        val harness = harness()
        val prefs = harness.sports
        val now = System.currentTimeMillis()
        prefs.nowMs = { now }
        prefs.setOverride("secret-addon-id|live_catalog", SportsLiveOverride.INCLUDE)
        prefs.setCatalogOption("secret-addon-id|live_catalog", CatalogOption("genre", "Hidden Value"))
        prefs.updateReminders { it.copy(reminders = listOf(GameReminder(setOf("secret-addon-id|item-42"), "Game", "A Team", "B Team", now + 60 * AlertFixtures.MINUTE))) }
        prefs.follow(celtics)

        harness.service.pushCurrentProfileToRemote().getOrThrow()

        val uploaded = harness.remote.getValue(1).getValue("features").jsonObject
            .getValue(SportsLivePreferencesDataStore.FEATURE).toString()
        listOf("secret-addon-id", "item-42", "live_catalog", "Hidden Value", "sports_live_overrides", "sports_live_catalog_options",
            "sports_live_dual_hint_shown", "http").forEach { banned ->
            assertFalse("uploaded $banned", banned in uploaded)
        }
        // What is allowed did go up.
        assertTrue("Boston Celtics" in uploaded)
        assertTrue("A Team" in uploaded)
    }

    @Test
    fun `a later change wins per setting, whichever TV made it`() = runTest {
        val a = Tv(start)
        val b = Tv(start)
        a.prefs.setScoresMode(ScoresMode.HIDE_UNTIL_FOCUSED)
        b.clock = start + 10
        b.prefs.setScoresMode(ScoresMode.OFF)
        a.clock = start + 20
        a.prefs.setScoreDelaySeconds(30)

        a.pull(b)
        // B's Scores is newer; A's delay is newer and B never set it.
        assertEquals(ScoresMode.OFF, a.prefs.scoreSettings.first().mode)
        assertEquals(30, a.prefs.scoreSettings.first().delaySeconds)

        // An older copy never undoes a newer local change.
        a.clock = start + 30
        a.prefs.setScoresMode(ScoresMode.SHOW)
        val stale = Tv(start + 15).also { it.prefs.setScoresMode(ScoresMode.OFF) }
        a.pull(stale)
        assertEquals(ScoresMode.SHOW, a.prefs.scoreSettings.first().mode)

        // Both TVs end up the same.
        b.pull(a)
        a.pull(b)
        assertEquals(SportsLiveSettingsSync.canonical(a.export()), SportsLiveSettingsSync.canonical(b.export()))
    }

    @Test
    fun `followed teams merge as a union and a removal is kept`() = runTest {
        val a = Tv(start)
        val b = Tv(start)
        a.prefs.follow(celtics)
        b.clock = start + 5
        b.prefs.follow(lakers)

        a.pull(b)
        b.pull(a)
        assertEquals(listOf(celtics, lakers), a.prefs.followedTeams.first())
        assertEquals(setOf(celtics, lakers), b.prefs.followedTeams.first().toSet())

        // Unfollowed on B: gone on A too, and an old copy that still has it does not bring it back.
        val oldCopy = Tv(start).also { it.pull(a) }
        b.clock = start + 50
        b.prefs.unfollow(celtics)
        a.clock = start + 60
        a.pull(b)
        assertEquals(listOf(lakers), a.prefs.followedTeams.first())
        a.pull(oldCopy)
        assertEquals(listOf(lakers), a.prefs.followedTeams.first())

        // Following it again later wins over the removal.
        a.clock = start + 70
        a.prefs.follow(celtics)
        b.pull(a)
        assertEquals(setOf(celtics, lakers), b.prefs.followedTeams.first().toSet())
    }

    @Test
    fun `close-game alerts, game end and reminder-only teams sync, this TV's own switches do not`() = runTest {
        val a = Tv(start)
        a.prefs.setCloseGameAlerts(false)
        a.prefs.setGameEndAction(SportsLiveGameEndAction.STAY)
        a.prefs.updateReminders { it.copy(remindedTeams = listOf(lakers)) }
        a.prefs.setHideUnplayable(false)
        a.prefs.setDualLayout("SIDE_BY_SIDE")
        val b = Tv(start + 5)
        b.pull(a)
        assertFalse(b.prefs.alertSettings.first().closeGameAlerts)
        assertEquals(SportsLiveGameEndAction.STAY, b.prefs.gameEndAction.first())
        assertEquals(listOf(lakers), b.prefs.reminders.first().remindedTeams)
        // What this TV can play and its dual screen layout stay on it.
        assertTrue(b.prefs.hideUnplayable.first())
        assertEquals(null, b.prefs.dualLayout())
        val uploaded = a.export().toString()
        assertFalse("sports_live_hide_unplayable_links" in uploaded)
        assertFalse("sports_live_dual_layout" in uploaded)
    }

    @Test
    fun `reminders merge by game and keep this TV's own source keys`() = runTest {
        val a = Tv(start)
        val b = Tv(start)
        val game = GameReminder(setOf("addon|1"), "Celtics at Lakers", "Los Angeles Lakers", "Boston Celtics", start + 3_600_000L)
        a.prefs.updateReminders { it.copy(reminders = listOf(game)) }
        b.clock = start + 5
        b.prefs.updateReminders { it.copy(reminders = listOf(game.copy(keys = emptySet()))) }
        a.pull(b)
        assertEquals(listOf(game), a.prefs.reminders.first().reminders)
        // A reminder whose game started over six hours ago is not uploaded.
        assertTrue("Celtics at Lakers" in a.export().toString())
        a.clock = start + 8 * 3_600_000L
        assertFalse("Celtics at Lakers" in a.export().toString())
    }

    // live.25 J: Channels' favorites and recent plays.

    private fun channel(id: String, name: String, addon: String) = SportsLiveItem(
        meta = MetaPreview(
            id = id, type = ContentType.UNKNOWN, rawType = "tv", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = addon, addonName = addon, addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Channels",
        kind = SportsLiveKind.CHANNELS, isLive = true, isChannel = true, startMs = null, sport = null
    )

    private val one = SportsLiveChannelFavorites.nameKey("US: Sports One")
    private val two = SportsLiveChannelFavorites.nameKey("US: Sports Two")
    private val three = SportsLiveChannelFavorites.nameKey("US: Sports Three")

    private suspend fun Tv.favorite(item: SportsLiveItem) = prefs.updateChannelPicks { SportsLiveChannelFavorites.toggle(it, item, favorite = false) }
    private suspend fun Tv.unfavorite(item: SportsLiveItem) = prefs.updateChannelPicks { SportsLiveChannelFavorites.toggle(it, item, favorite = true) }
    private suspend fun Tv.play(item: SportsLiveItem) = prefs.updateChannelPicks { SportsLiveChannelFavorites.played(it, item, clock) }
    private suspend fun Tv.favorites(): List<SportsLiveChannelRef> = prefs.channelPicks.first().favorites
    private suspend fun Tv.recent(): List<SportsLiveChannelRef> = prefs.channelPicks.first().recent

    @Test
    fun `channel picks go up by name key alone, never an addon id, channel id or link`() = runTest {
        val a = Tv(start)
        a.favorite(channel("https://example.invalid/live/42.m3u8", "US: Sports One HD", addon = "secret-addon-id"))
        a.play(channel("item-77", "US: Sports Two", addon = "secret-addon-id"))
        // A pick whose name key would carry a link never goes up.
        a.prefs.updateChannelPicks { it.copy(favorites = it.favorites + SportsLiveChannelRef("secret-addon-id", "item-78", "https://example.invalid/x")) }

        val uploaded = a.export().toString()
        listOf("://", "secret-addon-id", "example.invalid", "42.m3u8", "item-77", "item-78").forEach { banned ->
            assertFalse("uploaded $banned", banned in uploaded)
        }
        assertTrue(one in uploaded)
        assertTrue(two in uploaded)
    }

    @Test
    fun `channel favorites merge as a union, a removal is kept and each TV keeps its own addon`() = runTest {
        val a = Tv(start)
        val b = Tv(start)
        val oneOnA = channel("ch-1", "US: Sports One", addon = "addon.one")
        a.favorite(oneOnA)
        b.clock = start + 5
        b.favorite(channel("ch-2", "US: Sports Two HD", addon = "addon.two"))

        a.pull(b)
        b.pull(a)
        assertEquals(listOf(one, two), a.favorites().map { it.name })
        assertEquals(setOf(one, two), b.favorites().map { it.name }.toSet())
        // A's own pick keeps its addon and id; the one from B has none here.
        assertEquals(SportsLiveChannelFavorites.refOf(oneOnA), a.favorites().first())
        assertEquals("", a.favorites().last().addonId)

        // Removed on B (where it came synced): gone on A too, and an old copy that still has it does not bring it back.
        val oldCopy = Tv(start).also { it.pull(a) }
        b.clock = start + 50
        b.unfavorite(channel("ch-9", "US: Sports One", addon = "addon.two"))
        a.clock = start + 60
        a.pull(b)
        assertEquals(listOf(two), a.favorites().map { it.name })
        a.pull(oldCopy)
        assertEquals(listOf(two), a.favorites().map { it.name })

        // Added again later: wins over the removal. A's save leaves the synced pick's time as it was.
        a.clock = start + 70
        a.favorite(oneOnA)
        b.pull(a)
        assertEquals(setOf(one, two), b.favorites().map { it.name }.toSet())
        val synced = a.export().getValue("lists").jsonObject.getValue("sports_live_channel_favorites").jsonObject
        assertEquals(start + 5, synced.getValue(two).jsonObject.getValue("at").jsonPrimitive.long)
    }

    @Test
    fun `recent channels merge by last play, once per channel, at most as many as a TV keeps`() = runTest {
        val a = Tv(start + 1)
        val b = Tv(start + 2)
        a.play(channel("ch-1", "US: Sports One", addon = "addon.one"))
        b.play(channel("ch-2", "US: Sports Two", addon = "addon.two"))
        a.clock = start + 3
        a.play(channel("ch-3", "US: Sports Three", addon = "addon.one"))
        b.clock = start + 4
        b.play(channel("ch-9", "US: Sports One", addon = "addon.two"))

        a.pull(b)
        assertEquals(listOf(one, three, two), a.recent().map { it.name })
        // B's later play wins, and A keeps its own copy of the channel.
        assertEquals(start + 4, a.recent().first().playedAt)
        assertEquals("ch-1", a.recent().first().id)

        val names = (1..12).map { SportsLiveChannelFavorites.nameKey("US: Channel $it") }
        (1..12).forEach { i ->
            b.clock = start + 10 + i
            b.play(channel("c$i", "US: Channel $i", addon = "addon.two"))
        }
        a.pull(b)
        assertEquals(SportsLiveChannelFavorites.RECENT_KEPT, a.recent().size)
        assertEquals(names.reversed().take(SportsLiveChannelFavorites.RECENT_KEPT), a.recent().map { it.name })

        // Both TVs end up the same.
        b.pull(a)
        a.pull(b)
        assertEquals(SportsLiveSettingsSync.canonical(a.export()), SportsLiveSettingsSync.canonical(b.export()))
    }

    @Test
    fun `a new TV gets the channel picks and finds them in its own addons`() = runTest {
        val a = Tv(start)
        val oneOnA = channel("ch-1", "US: Sports One", addon = "addon.one")
        a.favorite(oneOnA)
        a.clock = start + 5
        a.play(oneOnA)

        val fresh = Tv(start + 10)
        fresh.pull(a)
        val there = channel("other-id", "US | Sports One FHD", addon = "addon.two")
        assertEquals(listOf(there), SportsLiveChannelFavorites.resolve(fresh.favorites(), listOf(there)))
        assertEquals(listOf(there), SportsLiveChannelFavorites.resolve(fresh.recent(), listOf(there)))

        // Back on A: unchanged, its own addon and id kept.
        val before = a.prefs.channelPicks.first()
        a.pull(fresh)
        assertEquals(before.favorites, a.favorites())
        assertEquals(before.recent, a.recent())
    }

    @Test
    fun `channel picks sync per profile`() = runTest(dispatcher) {
        val harness = harness()
        val prefs = harness.sports
        prefs.updateChannelPicks { it.copy(favorites = listOf(SportsLiveChannelRef("addon.one", "ch-1", one))) }
        harness.service.pushCurrentProfileToRemote().getOrThrow()
        harness.activeProfileId.value = 2
        prefs.updateChannelPicks { it.copy(favorites = listOf(SportsLiveChannelRef("addon.one", "ch-2", two))) }
        harness.service.pushCurrentProfileToRemote().getOrThrow()

        val first = harness.remote.getValue(1).toString()
        val second = harness.remote.getValue(2).toString()
        assertTrue(one in first)
        assertFalse(two in first)
        assertTrue(two in second)
        assertFalse(one in second)

        // Profile 1 on a cleared TV gets its own picks only.
        harness.activeProfileId.value = 1
        harness.factory.get(1, SportsLivePreferencesDataStore.FEATURE).edit { it.clear() }
        assertTrue(harness.service.pullCurrentProfileFromRemote().getOrThrow())
        assertEquals(listOf(one), prefs.channelPicks.first().favorites.map { it.name })
    }

    @Test
    fun `a malformed upload changes nothing`() = runTest {
        val a = Tv(start)
        a.prefs.follow(celtics)
        val before = a.data.value
        a.data.edit { SportsLiveSettingsSync.merge(it, buildJsonObject { put("fields", "nonsense"); put("lists", JsonArray(emptyList())) }, start) }
        assertEquals(before.asMap(), a.data.value.asMap())
    }

    private data class Snapshot(
        val followed: List<FollowedTeam>,
        val mySports: MySports,
        val mode: ScoresMode,
        val delay: Int,
        val scoreAlertLevel: com.nuvio.tv.core.sportslive.alerts.ScoreAlertLevel,
        val catchUpRow: Boolean,
        val hideMyTeams: Boolean,
        val hidden: Set<String>,
        val reminderTitles: List<String>,
        val reminderKeys: Set<String>,
        val saved: List<String>
    )

    private suspend fun snapshot(prefs: SportsLivePreferencesDataStore): Snapshot {
        val reminders = prefs.reminders.first().reminders
        return Snapshot(
            followed = prefs.followedTeams.first(),
            mySports = prefs.mySports.first(),
            mode = prefs.scoreSettings.first().mode,
            delay = prefs.scoreSettings.first().delaySeconds,
            scoreAlertLevel = prefs.alertSettings.first().scoreAlertLevel,
            catchUpRow = prefs.catchUpRow.first(),
            hideMyTeams = prefs.hideMyTeamsScores.first(),
            hidden = prefs.hiddenTitles.first(),
            reminderTitles = reminders.map { it.title },
            reminderKeys = reminders.flatMap { it.keys }.toSet(),
            saved = prefs.spoilerProtection.first().saved.map { it.eventId }
        )
    }

    private class Harness(
        val service: ProfileSettingsSyncService,
        val sports: SportsLivePreferencesDataStore,
        val factory: ProfileDataStoreFactory,
        val remote: MutableMap<Int, JsonObject>,
        val activeProfileId: MutableStateFlow<Int>
    )

    private fun harness(): Harness {
        val stores = mutableMapOf<Pair<Int, String>, TestPreferencesStore>()
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } answers {
            stores.getOrPut(firstArg<Int>() to secondArg<String>()) { TestPreferencesStore() }
        }
        every { factory.corruptedFileNames } returns mutableSetOf()
        val activeProfileId = MutableStateFlow(1)
        val profiles = MutableStateFlow(listOf(UserProfile(1, "Primary", "#000000")))
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns activeProfileId
        every { profileManager.profiles } returns profiles
        every { profileManager.activeProfile } answers { profiles.value.first() }
        val authManager = mockk<AuthManager>(relaxed = true)
        every { authManager.isAuthenticated } returns false
        val identity = mockk<SyncClientIdentity>()
        every { identity.currentClientId() } returns "nuvio-tv-sports-sync-test"
        val remote = mutableMapOf<Int, JsonObject>()
        val postgrest = mockk<Postgrest>()
        every { postgrest.serializer } returns KotlinXSerializer()
        coEvery { postgrest.rpc(any(), any<JsonObject>()) } answers {
            val params = secondArg<JsonObject>()
            val profile = params.getValue("p_profile_id").jsonPrimitive.int
            val response = when (firstArg<String>()) {
                "sync_push_profile_settings_blob" -> {
                    remote[profile] = params.getValue("p_settings_json").jsonObject
                    ""
                }
                "sync_pull_profile_settings_blob" -> {
                    val blob = remote[profile]
                    JsonArray(if (blob == null) emptyList() else listOf(buildJsonObject {
                        put("profile_id", profile)
                        put("settings_json", blob)
                    })).toString()
                }
                else -> error("Unexpected RPC")
            }
            PostgrestResult(response, Headers.Empty, postgrest)
        }
        val service = ProfileSettingsSyncService(
            authManager, postgrest, profileManager, factory, identity,
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true)
        )
        services += service
        return Harness(service, SportsLivePreferencesDataStore(factory, profileManager), factory, remote, activeProfileId)
    }
}
