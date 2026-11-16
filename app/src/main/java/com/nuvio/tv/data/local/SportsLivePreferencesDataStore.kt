package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.CatalogOption
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.SportsLiveOverride
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
    private val overridesKey = stringPreferencesKey("sports_live_overrides")
    private val catalogOptionsKey = stringPreferencesKey("sports_live_catalog_options")
    private val followedTeamsKey = stringPreferencesKey("sports_live_followed_teams")

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

    /** Adds [team] unless the same name and sport are already followed (ignoring case). */
    suspend fun follow(team: FollowedTeam) {
        val name = team.name.trim()
        if (name.isEmpty()) return
        val followed = FollowedTeam(name, team.sport?.trim()?.takeIf { it.isNotEmpty() })
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

    suspend fun setShowSection(v: Boolean) {
        store().edit { it[showSectionKey] = v }
    }

    suspend fun setShowHomeRow(v: Boolean) {
        store().edit { it[showHomeRowKey] = v }
    }

    suspend fun setHoverPreview(v: Boolean) {
        store().edit { it[hoverPreviewKey] = v }
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
        gson.toJson(teams.map { mapOf("name" to it.name, "sport" to it.sport) })

    private fun decodeFollowed(json: String?): List<FollowedTeam> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            gson.fromJson<List<Map<String, String?>?>?>(json, followedType).orEmpty().mapNotNull { stored ->
                val name = stored?.get("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                FollowedTeam(name, stored["sport"]?.takeIf { it.isNotBlank() })
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
