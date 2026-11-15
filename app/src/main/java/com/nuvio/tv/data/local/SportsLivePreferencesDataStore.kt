package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.core.profile.ProfileManager
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

    private fun store() = factory.get(profileManager.activeProfileId.value, FEATURE)

    private val showSectionKey = booleanPreferencesKey("sports_live_show_section")
    private val showHomeRowKey = booleanPreferencesKey("sports_live_show_home_row")
    private val overridesKey = stringPreferencesKey("sports_live_overrides")

    val showSection: Flow<Boolean> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { it[showSectionKey] ?: true }
    }

    val showHomeRow: Flow<Boolean> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { it[showHomeRowKey] ?: true }
    }

    val overrides: Flow<Map<String, SportsLiveOverride>> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { decode(it[overridesKey]) }
    }

    suspend fun setShowSection(v: Boolean) {
        store().edit { it[showSectionKey] = v }
    }

    suspend fun setShowHomeRow(v: Boolean) {
        store().edit { it[showHomeRowKey] = v }
    }

    suspend fun setOverride(catalogKey: String, override: SportsLiveOverride?) {
        store().edit { prefs ->
            val updated = decode(prefs[overridesKey]).toMutableMap()
            if (override == null) updated.remove(catalogKey) else updated[catalogKey] = override
            prefs[overridesKey] = gson.toJson(updated.mapValues { it.value.name })
        }
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
