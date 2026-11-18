package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sportslive.LiveNowSort
import com.nuvio.tv.core.sportslive.SportsLiveLayout
import com.nuvio.tv.core.sportslive.SportsLiveOpenGame
import com.nuvio.tv.core.sportslive.alerts.GameReminder
import com.nuvio.tv.core.sportslive.alerts.RemindersState
import com.nuvio.tv.core.sportslive.alerts.SportsLiveAlertSettings
import com.nuvio.tv.core.sportslive.scores.MySports
import com.nuvio.tv.core.sportslive.scores.ScoreDelayChoices
import com.nuvio.tv.core.sportslive.scores.ScoreSettings
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.core.sportslive.scores.SportKind
import com.nuvio.tv.domain.model.CatalogOption
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.SportsLiveOverride
import com.nuvio.tv.domain.model.SportsLiveTab
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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
    }

    private val gson = Gson()
    private val overridesType = object : TypeToken<Map<String, String>>() {}.type
    // catalogKey -> {"extra": name, "value": value}; plain maps, so release minification cannot rename fields.
    private val optionsType = object : TypeToken<Map<String, Map<String, String>>>() {}.type

    // [{"name": name, "sport": sport or null}]; plain maps, like the options.
    private val followedType = object : TypeToken<List<Map<String, String?>>>() {}.type

    private fun store() = factory.get(profileManager.activeProfileId.value, FEATURE)

    private val showSectionKey = booleanPreferencesKey("sports_live_show_section")
    private val showHomeRowKey = booleanPreferencesKey("sports_live_show_home_row")
    private val hoverPreviewKey = booleanPreferencesKey("sports_live_hover_preview")
    private val dataSaverKey = booleanPreferencesKey("sports_live_data_saver")
    private val pipEnabledKey = booleanPreferencesKey("sports_live_pip_enabled")
    private val overridesKey = stringPreferencesKey("sports_live_overrides")
    private val catalogOptionsKey = stringPreferencesKey("sports_live_catalog_options")
    private val followedTeamsKey = stringPreferencesKey("sports_live_followed_teams")
    private val dualHintShownKey = intPreferencesKey("sports_live_dual_hint_shown")
    private val newSportsHintSeenKey = booleanPreferencesKey("sports_live_new_sports_hint_seen")
    private val scoresModeKey = stringPreferencesKey("sports_live_scores_mode")
    private val scoreDelayKey = intPreferencesKey("sports_live_score_delay_seconds")
    // Leagues seen on cards (scores S3), no longer used: cleared on the next My sports save.
    private val recentScoreLeaguesKey = stringPreferencesKey("sports_live_recent_score_leagues")
    private val mySportsKey = stringSetPreferencesKey("sports_live_my_sports")
    private val openGameKey = stringPreferencesKey("sports_live_open_game")
    private val layoutsKey = stringPreferencesKey("sports_live_layouts")
    private val liveNowSortKey = stringPreferencesKey("sports_live_live_now_sort")
    private val remindersKey = stringPreferencesKey("sports_live_reminders")
    private val reminderOptOutsKey = stringPreferencesKey("sports_live_reminder_opt_outs")
    private val remindMyTeamsKey = booleanPreferencesKey("sports_live_remind_my_teams")
    private val scoreAlertsKey = booleanPreferencesKey("sports_live_score_alerts")
    private val fullTimeAlertsKey = booleanPreferencesKey("sports_live_full_time_alerts")

    // [{"keys": source keys joined by REMINDER_KEY_SEPARATOR, "title": ..., "home": ..., "away": ..., "start": "epoch ms"}]; plain maps, like the follows.
    private val remindersType = object : TypeToken<List<Map<String, String?>>>() {}.type

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

    /**
     * Whether Home while Sports & Live plays may go picture-in-picture (where the device supports it);
     * off keeps the in-app mini player only. On by default.
     */
    val pipEnabled: Flow<Boolean> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { it[pipEnabledKey] ?: true }
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

    /** Scores: Show (default) / Hide until focused / Off; and the score delay in seconds (Off = 0). */
    val scoreSettings: Flow<ScoreSettings> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            ScoreSettings(
                mode = ScoresMode.entries.firstOrNull { it.name == prefs[scoresModeKey] } ?: ScoresMode.SHOW,
                delaySeconds = ScoreDelayChoices.coerce(prefs[scoreDelayKey])
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

    /** Cards or Scoreboard (#23), per tab; a tab with no entry shows Cards. */
    val layouts: Flow<Map<SportsLiveTab, SportsLiveLayout>> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { SportsLiveLayout.decode(it[layoutsKey]) }
    }

    suspend fun setLayout(tab: SportsLiveTab, layout: SportsLiveLayout) {
        store().edit { prefs ->
            prefs[layoutsKey] = SportsLiveLayout.encode(SportsLiveLayout.decode(prefs[layoutsKey]) + (tab to layout))
        }
    }

    /** Live now's sort (#43): Start time (default) or Most exciting. */
    val liveNowSort: Flow<LiveNowSort> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { LiveNowSort.decode(it[liveNowSortKey]) }
    }

    suspend fun setLiveNowSort(sort: LiveNowSort) {
        store().edit { it[liveNowSortKey] = sort.name }
    }

    /** Settings → Sports & Live → Alerts (#42, #44): each on by default. */
    val alertSettings: Flow<SportsLiveAlertSettings> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            SportsLiveAlertSettings(
                remindMyTeams = prefs[remindMyTeamsKey] ?: true,
                scoreAlerts = prefs[scoreAlertsKey] ?: true,
                fullTimeAlerts = prefs[fullTimeAlertsKey] ?: true
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

    /** The active profile's reminders (#44), opt-outs from automatic ones, and "Remind me for my teams". */
    val reminders: Flow<RemindersState> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { prefs ->
            RemindersState(
                reminders = decodeReminders(prefs[remindersKey]),
                optOuts = decodeReminders(prefs[reminderOptOutsKey]),
                remindMyTeams = prefs[remindMyTeamsKey] ?: true
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
                remindMyTeams = prefs[remindMyTeamsKey] ?: true
            )
            saved = change(current)
            prefs[remindersKey] = encodeReminders(saved.reminders)
            prefs[reminderOptOutsKey] = encodeReminders(saved.optOuts)
        }
        return saved
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

    suspend fun setScoreDelaySeconds(seconds: Int) {
        store().edit { it[scoreDelayKey] = ScoreDelayChoices.coerce(seconds) }
    }

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
     * Called once per dual screen entry: true when its keys hint shows this time (the profile's first
     * [DUAL_KEYS_HINT_ENTRIES] entries), which is then counted.
     */
    suspend fun claimDualKeysHint(): Boolean {
        var show = false
        store().edit { prefs ->
            val shown = prefs[dualHintShownKey] ?: 0
            show = shouldShowDualKeysHint(shown)
            if (show) prefs[dualHintShownKey] = shown + 1
        }
        return show
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

    private fun FollowedTeam.sameAs(other: FollowedTeam): Boolean =
        name.equals(other.name, ignoreCase = true) && (sport ?: "").equals(other.sport ?: "", ignoreCase = true)

    private fun encodeFollowed(teams: List<FollowedTeam>): String =
        gson.toJson(
            teams.map {
                mapOf("name" to it.name, "sport" to it.sport, "espnId" to it.espnId, "abbreviation" to it.abbreviation, "league" to it.league)
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
                    league = stored["league"]?.takeIf { it.isNotBlank() }
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

/** Between a stored reminder's source keys (a unit separator: never in an addon or item id). */
private const val REMINDER_KEY_SEPARATOR = "\u001F"

/** Dual screen entries that show the keys hint. */
const val DUAL_KEYS_HINT_ENTRIES = 3

/** Whether the dual screen's keys hint shows, after it has shown [shownCount] times. */
fun shouldShowDualKeysHint(shownCount: Int): Boolean = shownCount < DUAL_KEYS_HINT_ENTRIES
