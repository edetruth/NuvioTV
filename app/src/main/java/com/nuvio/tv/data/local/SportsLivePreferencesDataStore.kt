package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sportslive.SportsLiveChannelPicks
import com.nuvio.tv.core.sportslive.SportsLiveChannelRef
import com.nuvio.tv.core.sportslive.SportsLiveHiddenTitles
import com.nuvio.tv.core.sportslive.SportsLiveAddSportPrompt
import com.nuvio.tv.core.sportslive.ReplayPosition
import com.nuvio.tv.core.sportslive.SportsLiveOpenGame
import com.nuvio.tv.core.sportslive.SportsLiveReplayResume
import com.nuvio.tv.core.sportslive.alerts.GameReminder
import com.nuvio.tv.core.sportslive.alerts.GameReminders
import com.nuvio.tv.core.sportslive.alerts.RemindersState
import com.nuvio.tv.core.sportslive.SportsLiveGameEndAction
import com.nuvio.tv.core.sportslive.alerts.SportsLiveAlertSettings
import com.nuvio.tv.core.sportslive.scores.CatchUp
import com.nuvio.tv.core.sportslive.scores.CatchUpMarks
import com.nuvio.tv.core.sportslive.scores.MySports
import com.nuvio.tv.core.sportslive.scores.ProtectedGame
import com.nuvio.tv.core.sportslive.scores.ScoreDelayChoices
import com.nuvio.tv.core.sportslive.scores.ScoreSettings
import com.nuvio.tv.core.sportslive.scores.ScoresChoice
import com.nuvio.tv.core.sportslive.scores.ScoreWatch
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.core.sportslive.scores.SpoilerProtection
import com.nuvio.tv.core.sportslive.scores.SportKind
import com.nuvio.tv.core.sync.SportsLiveSettingsSync
import com.nuvio.tv.domain.model.CatalogOption
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.SportsLiveOverride
import com.nuvio.tv.domain.model.SportsLiveTab
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class SportsLivePreferencesDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        const val FEATURE = "sports_live_settings"

        /**
         * "Hide my teams' results until I reveal" for a profile that never set it (results redesign Q1, M2 of the live.23
         * review): on, so a followed team's finished game never shows its result before the viewer chooses to see it. Its
         * own key: the older "Hide scores for my teams only" choice (live scores) never turns it off.
         */
        const val MY_TEAMS_RESULTS_HIDDEN_DEFAULT = true
    }

    private val gson = Gson()
    private val overridesType = object : TypeToken<Map<String, String>>() {}.type
    // catalogKey -> {"extra": name, "value": value}; plain maps, so release minification cannot rename fields.
    private val optionsType = object : TypeToken<Map<String, Map<String, String>>>() {}.type

    // [{"name": name, "sport": sport or null}]; plain maps, like the options.
    private val followedType = object : TypeToken<List<Map<String, String?>>>() {}.type

    /** Every change is stamped for the account sync (#141, [SportsLiveSettingsSync.stamp]). */
    private fun store(): DataStore<Preferences> = SyncStampedStore(factory.get(profileManager.activeProfileId.value, FEATURE)) { nowMs() }

    /** The clock reminders are read against (games started over 6 h ago are dropped); tests set it. */
    internal var nowMs: () -> Long = System::currentTimeMillis

    private val showSectionKey = booleanPreferencesKey("sports_live_show_section")
    private val showHomeRowKey = booleanPreferencesKey("sports_live_show_home_row")
    private val hoverPreviewKey = booleanPreferencesKey("sports_live_hover_preview")
    private val dataSaverKey = booleanPreferencesKey("sports_live_data_saver")
    // live.21 B2: the in-player guide, side panel or the new bottom table (beta, two squeezes); this TV only, not synced.
    private val guideStyleKey = stringPreferencesKey("sports_live_guide_style")
    private val pipEnabledKey = booleanPreferencesKey("sports_live_pip_enabled")
    private val hideUnplayableKey = booleanPreferencesKey("sports_live_hide_unplayable_links")
    private val overridesKey = stringPreferencesKey("sports_live_overrides")
    private val catalogOptionsKey = stringPreferencesKey("sports_live_catalog_options")
    private val followedTeamsKey = stringPreferencesKey("sports_live_followed_teams")
    private val hiddenTitlesKey = stringSetPreferencesKey("sports_live_hidden_titles")
    // "Add Cricket to My sports?" (More live): the sports declined, and "id:count" of cards left unanswered.
    private val addSportDeclinedKey = stringSetPreferencesKey("sports_live_add_sport_declined")
    private val addSportIgnoredKey = stringSetPreferencesKey("sports_live_add_sport_ignored")
    private val panelHintShownKey = intPreferencesKey("sports_live_panel_hint_shown")
    private val panelLastOpenKey = longPreferencesKey("sports_live_panel_last_open")
    private val dualLayoutKey = stringPreferencesKey("sports_live_dual_layout")
    private val dualHintShownKey = intPreferencesKey("sports_live_dual_hint_shown")
    private val newSportsHintSeenKey = booleanPreferencesKey("sports_live_new_sports_hint_seen")
    // Local only (not in SportsLiveSettingsSync): where each followed team's replay was left, by ESPN event id.
    private val replayPositionsKey = stringPreferencesKey("sports_live_replay_positions")
    // Channels' favorites and recent plays (phase 1): synced by name key only (SportsLiveSettingsSync, live.25 J).
    private val channelFavoritesKey = stringPreferencesKey("sports_live_channel_favorites")
    private val channelRecentKey = stringPreferencesKey("sports_live_channel_recent")
    // Phase 2: plays per channel in the last 30 days ({"key": "ms,ms"}), and the countries Channels lists (unset: yours).
    private val channelPlaysKey = stringPreferencesKey("sports_live_channel_plays")
    private val channelCountriesKey = stringSetPreferencesKey("sports_live_channel_countries")
    // First-run setup (#134 phase 7): set once the profile has had its chance at it (shown, or set up already).
    private val setupDoneKey = booleanPreferencesKey("sports_live_setup_done")
    private val scoresModeKey = stringPreferencesKey("sports_live_scores_mode")
    private val scoreDelayKey = intPreferencesKey("sports_live_score_delay_seconds")
    // live.20 Q18 (#85): "Real-time scores" (opt-in, no delay at all), and "Scores ahead? +15 s" per stream source
    // ({"addon id": seconds}; this TV's own, as each source's restream lag is what this TV receives).
    private val realTimeScoresKey = booleanPreferencesKey("sports_live_real_time_scores")
    private val scoreNudgesKey = stringPreferencesKey("sports_live_score_nudges")
    // Leagues seen on cards (scores S3), no longer used: cleared on the next My sports save.
    private val recentScoreLeaguesKey = stringPreferencesKey("sports_live_recent_score_leagues")
    private val mySportsKey = stringSetPreferencesKey("sports_live_my_sports")
    private val openGameKey = stringPreferencesKey("sports_live_open_game")
    // Retired with the View menu and Sort (V2, #132): removed by [dropRetiredKeys].
    private val retiredKeys = listOf(stringPreferencesKey("sports_live_layouts"), stringPreferencesKey("sports_live_live_now_sort"))
    private val remindersKey = stringPreferencesKey("sports_live_reminders")
    private val reminderOptOutsKey = stringPreferencesKey("sports_live_reminder_opt_outs")
    private val remindMyTeamsKey = booleanPreferencesKey("sports_live_remind_my_teams")
    private val scoreAlertsKey = booleanPreferencesKey("sports_live_score_alerts")
    private val fullTimeAlertsKey = booleanPreferencesKey("sports_live_full_time_alerts")
    private val closeGameAlertsKey = booleanPreferencesKey("sports_live_close_game_alerts")
    // Reminder-only teams (#154), stored as the follows are.
    private val remindedTeamsKey = stringPreferencesKey("sports_live_reminded_teams")
    // "When a game ends" (#161): a [SportsLiveGameEndAction] name.
    private val gameEndActionKey = stringPreferencesKey("sports_live_game_end_action")
    // Research Q19 / Q17: "OK on live" and "At tip-off" ([SportsLiveOkOnLive], [SportsLiveTipOff] names).
    private val okOnLiveKey = stringPreferencesKey("sports_live_ok_on_live")
    private val tipOffKey = stringPreferencesKey("sports_live_tip_off")
    // Spoiler protection (#151): games saved for later, and "Hide scores of my teams' games until I've watched".
    private val watchLaterKey = stringPreferencesKey(SportsLiveSettingsSync.WATCH_LATER)
    private val myTeamsSpoilersKey = booleanPreferencesKey("sports_live_my_teams_spoilers")
    private val myTeamsResultsHiddenKey = booleanPreferencesKey("sports_live_my_teams_results_hidden")

    // [{"keys": source keys joined by REMINDER_KEY_SEPARATOR, "title": ..., "home": ..., "away": ..., "start": "epoch ms"}]; plain maps, like the follows.
    private val remindersType = object : TypeToken<List<Map<String, String?>>>() {}.type
    private val catchUpRowKey = booleanPreferencesKey("sports_live_catch_up_row")
    private val catchUpLastOpenedKey = longPreferencesKey("sports_live_catch_up_last_opened")
    private val catchUpSinceKey = longPreferencesKey("sports_live_catch_up_since")
    // "eventId|ms" entries: when each replay was watched, and when each surfaced game started.
    private val catchUpSeenKey = stringSetPreferencesKey("sports_live_catch_up_seen")
    private val catchUpSurfacedKey = stringSetPreferencesKey("sports_live_catch_up_surfaced")
    private val catchUpPendingKey = stringSetPreferencesKey("sports_live_catch_up_pending")
    private val catchUpKnownKey = stringSetPreferencesKey("sports_live_catch_up_known")

    val showSection: Flow<Boolean> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { it[showSectionKey] ?: true }
    }

    val showHomeRow: Flow<Boolean> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { it[showHomeRowKey] ?: true }
    }

    /** Whether resting on a Live now or Channels card previews it, muted, in the details pane. */
    val hoverPreview: Flow<Boolean> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { it[hoverPreviewKey] ?: true }
    }

    /**
     * Data saver for limited/metered stream providers: when on, hover preview and the on-focus
     * ended-probe are skipped so a stream resolves only on an explicit play or zap-settle. Off by
     * default, so behaviour is unchanged for most users.
     */
    val dataSaver: Flow<Boolean> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { it[dataSaverKey] ?: false }
    }

    /** "In-player guide" (live.21 design B2; out of beta in live.23): the smooth table by default ([SportsLiveGuideStyle.DEFAULT]). */
    val guideStyle: Flow<com.nuvio.tv.core.sportslive.SportsLiveGuideStyle> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { com.nuvio.tv.core.sportslive.SportsLiveGuideStyle.fromStored(it[guideStyleKey]) }
    }

    suspend fun setGuideStyle(value: com.nuvio.tv.core.sportslive.SportsLiveGuideStyle) {
        store().edit { it[guideStyleKey] = value.name }
    }

    /**
     * Whether Home while Sports & Live plays may go picture-in-picture (where the device supports it);
     * off keeps the in-app mini player only. On by default.
     */
    val pipEnabled: Flow<Boolean> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { it[pipEnabledKey] ?: true }
    }

    /** Whether stream lists leave out links this TV can't play: DRM-protected with no licence (#166). On by default. */
    val hideUnplayable: Flow<Boolean> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { it[hideUnplayableKey] ?: true }
    }

    val overrides: Flow<Map<String, SportsLiveOverride>> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { decode(it[overridesKey]) }
    }

    /** Pinned catalog option per catalog key; a catalog with no entry loads everything (All). */
    val catalogOptions: Flow<Map<String, CatalogOption>> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { decodeOptions(it[catalogOptionsKey]) }
    }

    /** Followed teams of the active profile, in the order they were followed. */
    val followedTeams: Flow<List<FollowedTeam>> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { decodeFollowed(it[followedTeamsKey]) }
    }

    /** Titles hidden from Live Now (#134), by clean name ([SportsLiveHiddenTitles]); Settings → More → Hidden lists them. */
    val hiddenTitles: Flow<Set<String>> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { it[hiddenTitlesKey].orEmpty() }
    }

    /** What the profile said to "Add ‹sport› to My sports?" ([SportsLiveAddSportPrompt]). */
    val addSportMemory: Flow<SportsLiveAddSportPrompt.Memory> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            SportsLiveAddSportPrompt.Memory(
                declined = prefs[addSportDeclinedKey].orEmpty(),
                ignored = SportsLiveAddSportPrompt.decodeIgnored(prefs[addSportIgnoredKey])
            )
        }
    }

    /** Saves [change] of the active profile's "Add ‹sport› to My sports?" answers in one edit. */
    suspend fun updateAddSportMemory(change: (SportsLiveAddSportPrompt.Memory) -> SportsLiveAddSportPrompt.Memory) {
        store().edit { prefs ->
            val now = SportsLiveAddSportPrompt.Memory(
                declined = prefs[addSportDeclinedKey].orEmpty(),
                ignored = SportsLiveAddSportPrompt.decodeIgnored(prefs[addSportIgnoredKey])
            )
            val next = change(now)
            prefs[addSportDeclinedKey] = next.declined
            prefs[addSportIgnoredKey] = next.encodeIgnored()
        }
    }

    /** Hides [title] from Live Now for the active profile. */
    suspend fun hideTitle(title: String) {
        store().edit { it[hiddenTitlesKey] = SportsLiveHiddenTitles.add(it[hiddenTitlesKey].orEmpty(), title) }
    }

    /** Lists [title] on Live Now again. */
    suspend fun unhideTitle(title: String) {
        store().edit { it[hiddenTitlesKey] = SportsLiveHiddenTitles.remove(it[hiddenTitlesKey].orEmpty(), title) }
    }

    /** Scores: Show (default) / Hide until I reveal / Never show; and the score delay in seconds (Off = 0; 60 until set). */
    val scoreSettings: Flow<ScoreSettings> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            ScoreSettings(
                mode = ScoresMode.entries.firstOrNull { it.name == prefs[scoresModeKey] } ?: ScoresMode.SHOW,
                delaySeconds = ScoreDelayChoices.coerce(prefs[scoreDelayKey] ?: ScoreDelayChoices.DEFAULT_SECONDS),
                realTime = prefs[realTimeScoresKey] ?: false
            )
        }
    }

    /** My sports (#55): the sports and leagues picked; unset is every supported sport. */
    val mySports: Flow<MySports> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { MySports.decode(it[mySportsKey]) }
    }

    /**
     * "When I open a game" (#56) as the user chose it; null when never set (or unreadable), so the
     * default can follow the player's autoplay mode ([SportsLiveOpenGame.effective]).
     */
    val openGame: Flow<SportsLiveOpenGame?> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            SportsLiveOpenGame.entries.firstOrNull { it.name == prefs[openGameKey] }
        }
    }

    /** Removes the retired View and Sort choices (#132) from the active profile's settings; a no-op once gone. */
    suspend fun dropRetiredKeys() {
        val store = store()
        if (retiredKeys.none { it in store.data.first() }) return
        store.edit { prefs -> retiredKeys.forEach { prefs.remove(it) } }
    }

    /**
     * Spoiler protection (#151) of the active profile: the games saved for later (and recently ended ones),
     * "Hide scores of my teams' games until I've watched" (off by default) and the followed teams it reads.
     */
    val spoilerProtection: Flow<SpoilerProtection> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            SpoilerProtection(
                games = decodeProtected(prefs[watchLaterKey]),
                myTeams = prefs[myTeamsSpoilersKey] ?: false,
                myTeamsResults = prefs[myTeamsResultsHiddenKey] ?: MY_TEAMS_RESULTS_HIDDEN_DEFAULT,
                followed = decodeFollowed(prefs[followedTeamsKey]),
                nowMs = nowMs()
            )
        }
    }

    /** "Hide scores of my teams' games until I've watched" (#151); off by default. */
    val hideMyTeamsScores: Flow<Boolean> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { it[myTeamsSpoilersKey] ?: false }
    }

    suspend fun setHideMyTeamsScores(v: Boolean) {
        store().edit { it[myTeamsSpoilersKey] = v }
    }

    /** "Hide my teams' results until I reveal" (M2): followed teams' finished games; on until the profile sets it. */
    val hideMyTeamsResults: Flow<Boolean> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { it[myTeamsResultsHiddenKey] ?: MY_TEAMS_RESULTS_HIDDEN_DEFAULT }
    }

    suspend fun setHideMyTeamsResults(v: Boolean) {
        store().edit { it[myTeamsResultsHiddenKey] = v }
    }

    /** Saves [change] of the protected games in one edit. */
    suspend fun updateProtectedGames(change: (List<ProtectedGame>) -> List<ProtectedGame>) {
        store().edit { prefs ->
            val next = change(decodeProtected(prefs[watchLaterKey]))
            if (next.isEmpty()) prefs.remove(watchLaterKey) else prefs[watchLaterKey] = encodeProtected(next)
        }
    }

    /** Settings → Sports & Live → Alerts (#42, #44): each on by default. */
    val alertSettings: Flow<SportsLiveAlertSettings> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            SportsLiveAlertSettings(
                remindMyTeams = prefs[remindMyTeamsKey] ?: true,
                scoreAlerts = prefs[scoreAlertsKey] ?: true,
                fullTimeAlerts = prefs[fullTimeAlertsKey] ?: true,
                closeGameAlerts = prefs[closeGameAlertsKey] ?: true,
                tipOff = com.nuvio.tv.core.sportslive.SportsLiveTipOff.fromStored(prefs[tipOffKey])
            )
        }
    }

    suspend fun setRemindMyTeams(v: Boolean) {
        store().edit { it[remindMyTeamsKey] = v }
    }

    suspend fun setScoreAlerts(v: Boolean) {
        store().edit { it[scoreAlertsKey] = v }
    }

    suspend fun setFullTimeAlerts(v: Boolean) {
        store().edit { it[fullTimeAlertsKey] = v }
    }

    /** "Close-game alerts" (#137): crunch-time alerts for games in My sports. */
    suspend fun setCloseGameAlerts(v: Boolean) {
        store().edit { it[closeGameAlertsKey] = v }
    }

    /** "At tip-off" (research Q17): Remind me by default; Switch automatically is opt-in. */
    suspend fun setTipOff(value: com.nuvio.tv.core.sportslive.SportsLiveTipOff) {
        store().edit { it[tipOffKey] = value.name }
    }

    /** "OK on live" (research Q19): Show controls by default. */
    val okOnLive: Flow<com.nuvio.tv.core.sportslive.SportsLiveOkOnLive> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { com.nuvio.tv.core.sportslive.SportsLiveOkOnLive.fromStored(it[okOnLiveKey]) }
    }

    suspend fun setOkOnLive(value: com.nuvio.tv.core.sportslive.SportsLiveOkOnLive) {
        store().edit { it[okOnLiveKey] = value.name }
    }

    /** "When a game ends" (#161): Move to the next game by default. */
    val gameEndAction: Flow<SportsLiveGameEndAction> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { SportsLiveGameEndAction.fromStored(it[gameEndActionKey]) }
    }

    suspend fun setGameEndAction(action: SportsLiveGameEndAction) {
        store().edit { it[gameEndActionKey] = action.name }
    }

    /** The active profile's reminders (#44), opt-outs from automatic ones, and "Remind me for my teams". */
    val reminders: Flow<RemindersState> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            RemindersState(
                reminders = GameReminders.current(decodeReminders(prefs[remindersKey]), nowMs()),
                optOuts = GameReminders.current(decodeReminders(prefs[reminderOptOutsKey]), nowMs()),
                remindMyTeams = prefs[remindMyTeamsKey] ?: true,
                remindedTeams = decodeFollowed(prefs[remindedTeamsKey])
            )
        }
    }

    /** Saves [change] of the stored reminders and opt-outs in one edit; returns the state saved. */
    suspend fun updateReminders(change: (RemindersState) -> RemindersState): RemindersState {
        var saved = RemindersState()
        store().edit { prefs ->
            val current = RemindersState(
                reminders = decodeReminders(prefs[remindersKey]),
                optOuts = decodeReminders(prefs[reminderOptOutsKey]),
                remindMyTeams = prefs[remindMyTeamsKey] ?: true,
                remindedTeams = decodeFollowed(prefs[remindedTeamsKey])
            )
            saved = change(current)
            prefs[remindersKey] = encodeReminders(saved.reminders)
            prefs[reminderOptOutsKey] = encodeReminders(saved.optOuts)
            prefs[remindedTeamsKey] = encodeFollowed(saved.remindedTeams)
        }
        return saved
    }

    /** The Catch up row (#58): on by default. */
    val catchUpRow: Flow<Boolean> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { it[catchUpRowKey] ?: true }
    }

    suspend fun setCatchUpRow(v: Boolean) {
        store().edit { it[catchUpRowKey] = v }
    }

    /** The catch-up row's watched replays and surfaced games (#58), by ESPN event id. */
    val catchUpMarks: Flow<CatchUpMarks> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map(::catchUpMarksOf)
    }

    /** Where the profile left each replay (coordinator 2026-11-20), by ESPN event id. */
    val replayPositions: Flow<Map<String, ReplayPosition>> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { decodeReplayPositions(it[replayPositionsKey]) }
    }

    suspend fun updateReplayPositions(change: (Map<String, ReplayPosition>) -> Map<String, ReplayPosition>) {
        store().edit { prefs ->
            prefs[replayPositionsKey] = gson.toJson(SportsLiveReplayResume.encode(change(decodeReplayPositions(prefs[replayPositionsKey]))))
        }
    }

    private fun decodeReplayPositions(json: String?): Map<String, ReplayPosition> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            SportsLiveReplayResume.decode(gson.fromJson<Map<String, String>?>(json, overridesType).orEmpty())
        }.getOrDefault(emptyMap())
    }

    /** Channels' favorites and recent plays (phase 1) of the active profile (synced without their addon ids). */
    val channelPicks: Flow<SportsLiveChannelPicks> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map(::channelPicksOf)
    }

    /** Saves [change] of the active profile's channel picks in one edit. */
    suspend fun updateChannelPicks(change: (SportsLiveChannelPicks) -> SportsLiveChannelPicks) {
        store().edit { prefs ->
            val changed = change(channelPicksOf(prefs))
            prefs[channelFavoritesKey] = gson.toJson(encodeChannelRefs(changed.favorites))
            prefs[channelRecentKey] = gson.toJson(encodeChannelRefs(changed.recent))
            prefs[channelPlaysKey] = gson.toJson(changed.plays.mapValues { (_, times) -> times.joinToString(",") })
        }
    }

    private fun channelPicksOf(prefs: Preferences) = SportsLiveChannelPicks(
        favorites = decodeChannelRefs(prefs[channelFavoritesKey]),
        recent = decodeChannelRefs(prefs[channelRecentKey]),
        plays = decodeChannelPlays(prefs[channelPlaysKey])
    )

    private fun decodeChannelPlays(json: String?): Map<String, List<Long>> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            gson.fromJson<Map<String, String>?>(json, overridesType).orEmpty()
                .mapValues { (_, times) -> times.split(',').mapNotNull { it.trim().toLongOrNull() } }
                .filterValues { it.isNotEmpty() }
        }.getOrDefault(emptyMap())
    }

    /** The countries Channels lists (phase 2) for the active profile: null for the viewer's own; this TV only. */
    val channelCountries: Flow<Set<String>?> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { it[channelCountriesKey] }
    }

    /** Saves the countries Channels lists; null goes back to the viewer's own. */
    suspend fun setChannelCountries(countries: Set<String>?) {
        store().edit { prefs ->
            prefs.remove(channelCountriesKey)
            if (countries != null) prefs[channelCountriesKey] = countries
        }
    }

    // [{"addon": addon id, "id": channel id, "name": name key, "at": played ms when known}]; plain maps, like the follows.
    private fun encodeChannelRefs(refs: List<SportsLiveChannelRef>): List<Map<String, String>> = refs.map {
        val ref = mapOf("addon" to it.addonId, "id" to it.id, "name" to it.name)
        if (it.playedAt > 0L) ref + ("at" to it.playedAt.toString()) else ref
    }

    private fun decodeChannelRefs(json: String?): List<SportsLiveChannelRef> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            gson.fromJson<List<Map<String, String?>?>?>(json, followedType).orEmpty().mapNotNull { stored ->
                val addon = stored?.get("addon").orEmpty()
                val id = stored?.get("id").orEmpty()
                val name = stored?.get("name").orEmpty()
                val at = stored?.get("at")?.toLongOrNull() ?: 0L
                when {
                    addon.isNotBlank() && id.isNotBlank() -> SportsLiveChannelRef(addon, id, name, at)
                    // Synced from another TV: its name key alone.
                    name.isNotBlank() -> SportsLiveChannelRef("", "", name, at)
                    else -> null
                }
            }
        }.getOrDefault(emptyList())
    }

    /** The active profile's id: the catch-up row's visit and played replays belong to it. */
    val activeProfileId: StateFlow<Int> get() = profileManager.activeProfileId

    private fun catchUpMarksOf(prefs: Preferences) = CatchUpMarks(
        seen = decodeMarks(prefs[catchUpSeenKey]),
        surfaced = decodeMarks(prefs[catchUpSurfacedKey]),
        pending = decodeMarks(prefs[catchUpPendingKey]),
        known = decodeMarks(prefs[catchUpKnownKey])
    )

    /** Saves [change] of the catch-up marks in one edit, dropping marks too old to matter ([CatchUp.pruned]). */
    suspend fun updateCatchUpMarks(nowMs: Long, change: (CatchUpMarks) -> CatchUpMarks) {
        store().edit { prefs ->
            val next = CatchUp.pruned(change(catchUpMarksOf(prefs)), nowMs)
            prefs[catchUpSeenKey] = encodeMarks(next.seen)
            prefs[catchUpSurfacedKey] = encodeMarks(next.surfaced)
            prefs[catchUpPendingKey] = encodeMarks(next.pending)
            prefs[catchUpKnownKey] = encodeMarks(next.known)
        }
    }

    /**
     * Home or Sports & Live opened at [nowMs] (#58): returns the catch-up row's "since" ([CatchUp.since]:
     * the last visit before a gap, else the one kept) and stores both.
     */
    suspend fun openCatchUp(nowMs: Long): Long {
        var since = nowMs
        store().edit { prefs ->
            since = CatchUp.since(prefs[catchUpLastOpenedKey], prefs[catchUpSinceKey], nowMs)
            prefs[catchUpSinceKey] = since
            prefs[catchUpLastOpenedKey] = nowMs
        }
        return since
    }

    suspend fun setOpenGame(openGame: SportsLiveOpenGame) {
        store().edit { it[openGameKey] = openGame.name }
    }

    /** Saves [change] of the stored My sports in one edit; the default (nothing picked) clears the key. */
    suspend fun updateMySports(change: (MySports) -> MySports) {
        store().edit { prefs ->
            prefs.remove(recentScoreLeaguesKey)
            val sports = change(MySports.decode(prefs[mySportsKey]))
            if (sports.isDefault) prefs.remove(mySportsKey) else prefs[mySportsKey] = sports.encode()
        }
    }

    suspend fun setScoresMode(mode: ScoresMode) {
        store().edit { it[scoresModeKey] = mode.name }
    }

    /** Scores as Settings shows it (research item 5): the stored mode and "my teams" switch read as one choice. */
    val scoresChoice: Flow<ScoresChoice> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs -> scoresChoiceOf(prefs) }
    }

    /** Applies [change] to the Scores choice and stores it as the mode plus the "my teams" switch, in one edit. */
    suspend fun updateScoresChoice(change: (ScoresChoice) -> ScoresChoice) {
        store().edit { prefs ->
            val choice = change(scoresChoiceOf(prefs))
            prefs[scoresModeKey] = choice.storedMode.name
            prefs[myTeamsSpoilersKey] = choice.storedMyTeams
        }
    }

    private fun scoresChoiceOf(prefs: Preferences): ScoresChoice = ScoresChoice.of(
        mode = ScoresMode.entries.firstOrNull { it.name == prefs[scoresModeKey] } ?: ScoresMode.SHOW,
        myTeams = prefs[myTeamsSpoilersKey] ?: false
    )

    suspend fun setScoreDelaySeconds(seconds: Int) {
        store().edit { it[scoreDelayKey] = ScoreDelayChoices.coerce(seconds) }
    }

    /** "Real-time scores" (live.20 Q18): every score delay off, also while video plays. Off by default. */
    suspend fun setRealTimeScores(on: Boolean) {
        store().edit { it[realTimeScoresKey] = on }
    }

    private val nudgesType = object : TypeToken<Map<String, Int>>() {}.type

    /** Each stream source's "Scores ahead? +15 s" seconds (live.20 Q18, #85), by addon id. */
    val scoreNudges: Flow<Map<String, Int>> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs -> decodeNudges(prefs[scoreNudgesKey]) }
    }

    /** One "Scores ahead? +15 s" press for [sourceId] (an addon id); returns its new seconds (at most 3 min). */
    suspend fun nudgeScores(sourceId: String): Int {
        var seconds = 0
        store().edit { prefs ->
            val current = decodeNudges(prefs[scoreNudgesKey])
            seconds = ScoreWatch.nudged(current[sourceId] ?: 0)
            prefs[scoreNudgesKey] = gson.toJson(current + (sourceId to seconds))
        }
        return seconds
    }

    /** "Reset" of [sourceId]'s nudge. */
    suspend fun resetScoreNudge(sourceId: String) {
        store().edit { prefs ->
            val current = decodeNudges(prefs[scoreNudgesKey])
            if (sourceId in current) prefs[scoreNudgesKey] = gson.toJson(current - sourceId)
        }
    }

    private fun decodeNudges(json: String?): Map<String, Int> =
        json?.let { runCatching { gson.fromJson<Map<String, Number>>(it, nudgesType) }.getOrNull() }
            ?.mapValues { (_, value) -> ScoreWatch.nudgeCapped(value.toInt()) }
            ?.filterValues { it > 0 }
            .orEmpty()

    /** Adds [team] unless the same name and sport are already followed (ignoring case). */
    suspend fun follow(team: FollowedTeam) {
        val name = team.name.trim()
        if (name.isEmpty()) return
        val followed = team.copy(name = name, sport = team.sport?.trim()?.takeIf { it.isNotEmpty() })
        store().edit { prefs ->
            val current = decodeFollowed(prefs[followedTeamsKey])
            if (current.none { it.sameAs(followed) }) prefs[followedTeamsKey] = encodeFollowed(current + followed)
        }
    }

    /** Removes [team] (same name and sport, ignoring case). */
    suspend fun unfollow(team: FollowedTeam) {
        val removed = FollowedTeam(team.name.trim(), team.sport?.trim()?.takeIf { it.isNotEmpty() })
        store().edit { prefs ->
            prefs[followedTeamsKey] = encodeFollowed(decodeFollowed(prefs[followedTeamsKey]).filterNot { it.sameAs(removed) })
        }
    }

    /**
     * Called once per in-player panel opening (live.20): true when its keys hint shows this time (the profile's first
     * [PANEL_KEYS_HINT_OPENS] openings, or the first after [PANEL_KEYS_HINT_GAP_MS] without one), which is then counted.
     */
    suspend fun claimPanelKeysHint(nowMs: Long = System.currentTimeMillis()): Boolean {
        var show = false
        store().edit { prefs ->
            val shown = prefs[panelHintShownKey] ?: 0
            show = shouldShowPanelKeysHint(shown, prefs[panelLastOpenKey], nowMs)
            if (show) prefs[panelHintShownKey] = shown + 1
            prefs[panelLastOpenKey] = nowMs
        }
        return show
    }

    /**
     * Called once per dual screen entry (live.24): true when its keys hint shows this time ([show] of the times it has
     * shown: the first few entries), which is then counted.
     */
    suspend fun claimDualKeysHint(show: (shownCount: Int) -> Boolean): Boolean {
        var shows = false
        store().edit { prefs ->
            val shown = prefs[dualHintShownKey] ?: 0
            shows = show(shown)
            if (shows) prefs[dualHintShownKey] = shown + 1
        }
        return shows
    }

    /** The dual screen layout the profile chose last (#123): a [com.nuvio.tv.ui.screens.sportslive.dual.DualLayout] name; null: never chosen. */
    suspend fun dualLayout(): String? = store().data.first()[dualLayoutKey]

    suspend fun setDualLayout(name: String) {
        store().edit { prefs -> prefs[dualLayoutKey] = name }
    }

    /**
     * Called when the My sports picker shows: true the first time only, and only for a profile that
     * saved My sports before the individual sports existed ([shouldShowNewSportsHint]). Seen either way.
     */
    suspend fun claimNewSportsHint(): Boolean {
        var show = false
        store().edit { prefs ->
            if (prefs[newSportsHintSeenKey] == true) return@edit
            show = shouldShowNewSportsHint(prefs[mySportsKey])
            prefs[newSportsHintSeenKey] = true
        }
        return show
    }

    /**
     * Called when Sports & Live opens: true when first-run setup shows now ([firstRunSetup]). The profile is
     * marked done either way, so it is offered once; Settings → Setup runs it again.
     */
    suspend fun claimFirstRunSetup(): Boolean {
        val store = store()
        if (store.data.first()[setupDoneKey] == true) return false
        var show = false
        store.edit { prefs ->
            val decision = firstRunSetup(
                done = prefs[setupDoneKey] == true,
                changedMySports = !prefs[mySportsKey].isNullOrEmpty(),
                followsTeam = decodeFollowed(prefs[followedTeamsKey]).isNotEmpty()
            )
            show = decision == FirstRunSetup.SHOW
            if (decision != FirstRunSetup.NONE) prefs[setupDoneKey] = true
        }
        return show
    }

    suspend fun setShowSection(v: Boolean) {
        store().edit { it[showSectionKey] = v }
    }

    suspend fun setShowHomeRow(v: Boolean) {
        store().edit { it[showHomeRowKey] = v }
    }

    suspend fun setHoverPreview(v: Boolean) {
        store().edit { it[hoverPreviewKey] = v }
    }

    suspend fun setDataSaver(v: Boolean) {
        store().edit { it[dataSaverKey] = v }
    }

    suspend fun setPipEnabled(v: Boolean) {
        store().edit { it[pipEnabledKey] = v }
    }

    suspend fun setHideUnplayable(v: Boolean) {
        store().edit { it[hideUnplayableKey] = v }
    }

    suspend fun setOverride(catalogKey: String, override: SportsLiveOverride?) {
        store().edit { prefs ->
            val updated = decode(prefs[overridesKey]).toMutableMap()
            if (override == null) updated.remove(catalogKey) else updated[catalogKey] = override
            prefs[overridesKey] = gson.toJson(updated.mapValues { it.value.name })
        }
    }

    /** Pins [option] for [catalogKey]; null clears it (All). */
    suspend fun setCatalogOption(catalogKey: String, option: CatalogOption?) {
        store().edit { prefs ->
            val updated = decodeOptions(prefs[catalogOptionsKey]).toMutableMap()
            if (option == null) updated.remove(catalogKey) else updated[catalogKey] = option
            prefs[catalogOptionsKey] = gson.toJson(updated.mapValues { mapOf("extra" to it.value.extra, "value" to it.value.value) })
        }
    }

    private fun encodeMarks(marks: Map<String, Long>): Set<String> = marks.mapTo(HashSet()) { (id, ms) -> "$id|$ms" }

    private fun decodeMarks(stored: Set<String>?): Map<String, Long> =
        stored.orEmpty().mapNotNull { entry ->
            val id = entry.substringBeforeLast('|', "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val ms = entry.substringAfterLast('|').toLongOrNull() ?: return@mapNotNull null
            id to ms
        }.toMap()

    private fun FollowedTeam.sameAs(other: FollowedTeam): Boolean =
        name.equals(other.name, ignoreCase = true) && (sport ?: "").equals(other.sport ?: "", ignoreCase = true)

    private fun encodeFollowed(teams: List<FollowedTeam>): String =
        gson.toJson(
            teams.map {
                mapOf("name" to it.name, "sport" to it.sport, "espnId" to it.espnId, "abbreviation" to it.abbreviation, "league" to it.league, "logo" to it.logo)
            }
        )

    private fun decodeFollowed(json: String?): List<FollowedTeam> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            gson.fromJson<List<Map<String, String?>?>?>(json, followedType).orEmpty().mapNotNull { stored ->
                val name = stored?.get("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                FollowedTeam(
                    name = name,
                    sport = stored["sport"]?.takeIf { it.isNotBlank() },
                    espnId = stored["espnId"]?.takeIf { it.isNotBlank() },
                    abbreviation = stored["abbreviation"]?.takeIf { it.isNotBlank() },
                    league = stored["league"]?.takeIf { it.isNotBlank() },
                    logo = stored["logo"]?.takeIf { it.isNotBlank() }
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeReminders(entries: List<GameReminder>): String =
        gson.toJson(
            entries.map {
                mapOf(
                    "keys" to it.keys.joinToString(REMINDER_KEY_SEPARATOR),
                    "title" to it.title,
                    "home" to it.home,
                    "away" to it.away,
                    "start" to it.startMs.toString()
                )
            }
        )

    private fun decodeReminders(json: String?): List<GameReminder> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            gson.fromJson<List<Map<String, String?>?>?>(json, remindersType).orEmpty().mapNotNull { stored ->
                val start = stored?.get("start")?.toLongOrNull() ?: return@mapNotNull null
                GameReminder(
                    keys = stored["keys"].orEmpty().split(REMINDER_KEY_SEPARATOR).filter { it.isNotBlank() }.toSet(),
                    title = stored["title"].orEmpty(),
                    home = stored["home"]?.takeIf { it.isNotBlank() },
                    away = stored["away"]?.takeIf { it.isNotBlank() },
                    startMs = start
                )
            }
        }.getOrDefault(emptyList())
    }

    // [{"eventId", "home", "away", "start", "saved", "ended"}]: numbers as text, plain maps like the follows.
    private fun encodeProtected(games: List<ProtectedGame>): String =
        gson.toJson(
            games.map {
                mapOf(
                    "eventId" to it.eventId,
                    "home" to it.home,
                    "away" to it.away,
                    "start" to it.startMs?.toString(),
                    "saved" to it.savedAtMs.toString(),
                    "ended" to it.endedAtMs?.toString()
                )
            }
        )

    private fun decodeProtected(json: String?): List<ProtectedGame> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            gson.fromJson<List<Map<String, String?>?>?>(json, followedType).orEmpty().mapNotNull { stored ->
                val id = stored?.get("eventId")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                ProtectedGame(
                    eventId = id,
                    home = stored["home"]?.takeIf { it.isNotBlank() },
                    away = stored["away"]?.takeIf { it.isNotBlank() },
                    startMs = stored["start"]?.toLongOrNull(),
                    savedAtMs = stored["saved"]?.toLongOrNull() ?: return@mapNotNull null,
                    endedAtMs = stored["ended"]?.toLongOrNull()
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun decodeOptions(json: String?): Map<String, CatalogOption> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            gson.fromJson<Map<String, Map<String, String>?>?>(json, optionsType).orEmpty()
                .mapNotNull { (key, stored) ->
                    val extra = stored?.get("extra")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val value = stored["value"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    key to CatalogOption(extra, value)
                }
                .toMap()
        }.getOrDefault(emptyMap())
    }

    private fun decode(json: String?): Map<String, SportsLiveOverride> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            gson.fromJson<Map<String, String>?>(json, overridesType).orEmpty()
                .mapNotNull { (key, value) ->
                    SportsLiveOverride.entries.firstOrNull { it.name == value }?.let { key to it }
                }
                .toMap()
        }.getOrDefault(emptyMap())
    }
}

/**
 * Whether the My sports picker points out the individual sports: My sports was saved ([savedTokens]
 * set) with none of them, as a profile did before they existed. Unsaved My sports already has them.
 */
fun shouldShowNewSportsHint(savedTokens: Set<String>?): Boolean =
    !savedTokens.isNullOrEmpty() && MySports.decode(savedTokens).sports.keys.none { it.kind == SportKind.INDIVIDUAL }

/** What opening Sports & Live does about first-run setup (#134 phase 7). */
enum class FirstRunSetup {
    /** Show it (and mark the profile done). */
    SHOW,

    /** Mark the profile done without showing it: it is set up already. */
    MARK_DONE,

    /** Nothing: it was offered before. */
    NONE
}

/**
 * First-run setup for a profile: once [done], nothing; a profile that already [changedMySports] or
 * [followsTeam] is set up, so it is marked done silently; else it shows.
 */
fun firstRunSetup(done: Boolean, changedMySports: Boolean, followsTeam: Boolean): FirstRunSetup = when {
    done -> FirstRunSetup.NONE
    changedMySports || followsTeam -> FirstRunSetup.MARK_DONE
    else -> FirstRunSetup.SHOW
}

/** [delegate] with each change stamped for the account sync (#141): the times its merge compares. */
private class SyncStampedStore(
    private val delegate: DataStore<Preferences>,
    private val now: () -> Long
) : DataStore<Preferences> {
    override val data: Flow<Preferences> get() = delegate.data

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        delegate.updateData { before -> SportsLiveSettingsSync.stamp(before, transform(before), now()) }
}

/** Between a stored reminder's source keys (a unit separator: never in an addon or item id). */
private const val REMINDER_KEY_SEPARATOR = "\u001F"

/** In-player panel openings that show the keys hint (live.20). */
const val PANEL_KEYS_HINT_OPENS = 3

/**
 * A gap without opening the panel after which its keys hint shows again (research 8: help must be easy to recall; a
 * viewer who watches once a week, as NFL fans do, has forgotten the keys after a couple of weeks).
 */
const val PANEL_KEYS_HINT_GAP_MS = 14L * 24 * 60 * 60 * 1000

/**
 * Whether the in-player panel's keys hint shows, after it has shown [shownCount] times and the panel was last opened
 * at [lastOpenAtMs] (null: never recorded): the first few openings, then again after a long gap ([PANEL_KEYS_HINT_GAP_MS]).
 */
fun shouldShowPanelKeysHint(shownCount: Int, lastOpenAtMs: Long? = null, nowMs: Long = 0L): Boolean =
    shownCount < PANEL_KEYS_HINT_OPENS || (lastOpenAtMs != null && nowMs - lastOpenAtMs >= PANEL_KEYS_HINT_GAP_MS)
