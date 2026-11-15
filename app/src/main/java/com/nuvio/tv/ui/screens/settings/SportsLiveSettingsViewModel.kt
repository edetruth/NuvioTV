package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.sportslive.SportsLiveCatalogPolicy
import com.nuvio.tv.data.local.SportsLivePreferencesDataStore
import com.nuvio.tv.domain.model.SportsLiveClaim
import com.nuvio.tv.domain.model.SportsLiveOverride
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.AddonRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One addon catalog as the Sports & Live settings list shows it. */
data class SportsLiveCatalogSetting(
    val key: String,
    val addonName: String,
    val catalogName: String,
    val apiType: String,
    /** What automatic classification would do, ignoring any override. */
    val autoClaim: SportsLiveClaim?,
    val override: SportsLiveOverride?
)

data class SportsLiveSettingsUiState(
    val showSection: Boolean = true,
    val showHomeRow: Boolean = true,
    val catalogs: List<SportsLiveCatalogSetting> = emptyList()
)

@HiltViewModel
class SportsLiveSettingsViewModel @Inject constructor(
    private val prefs: SportsLivePreferencesDataStore,
    addonRepository: AddonRepository
) : ViewModel() {

    val uiState: StateFlow<SportsLiveSettingsUiState> = combine(
        addonRepository.getInstalledAddons(),
        prefs.overrides,
        prefs.showSection,
        prefs.showHomeRow
    ) { addons, overrides, showSection, showHomeRow ->
        val catalogs = addons.enabledAddons().flatMap { addon ->
            addon.catalogs.map { catalog ->
                val key = SportsLiveCatalogPolicy.keyFor(addon, catalog)
                SportsLiveCatalogSetting(
                    key = key,
                    addonName = addon.displayName,
                    catalogName = catalog.name,
                    apiType = catalog.apiType,
                    autoClaim = SportsLiveCatalogPolicy.classify(addon, catalog, emptyMap()),
                    override = overrides[key]
                )
            }
        }
        SportsLiveSettingsUiState(showSection, showHomeRow, catalogs)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SportsLiveSettingsUiState())

    fun setShowSection(show: Boolean) {
        viewModelScope.launch { prefs.setShowSection(show) }
    }

    fun setShowHomeRow(show: Boolean) {
        viewModelScope.launch { prefs.setShowHomeRow(show) }
    }

    /** Auto, then INCLUDE, then EXCLUDE, then back to Auto. */
    fun cycleOverride(key: String) {
        viewModelScope.launch {
            val next = when (prefs.overrides.first()[key]) {
                null -> SportsLiveOverride.INCLUDE
                SportsLiveOverride.INCLUDE -> SportsLiveOverride.EXCLUDE
                SportsLiveOverride.EXCLUDE -> null
            }
            prefs.setOverride(key, next)
        }
    }
}
