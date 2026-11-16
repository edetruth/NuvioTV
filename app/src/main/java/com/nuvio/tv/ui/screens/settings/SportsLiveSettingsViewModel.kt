package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.sportslive.SportsLiveCatalogOptions
import com.nuvio.tv.core.sportslive.SportsLiveCatalogPolicy
import com.nuvio.tv.data.local.SportsLivePreferencesDataStore
import com.nuvio.tv.domain.model.CatalogOption
import com.nuvio.tv.domain.model.CatalogStats
import com.nuvio.tv.domain.model.SportsLiveClaim
import com.nuvio.tv.domain.model.SportsLiveOverride
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.SportsLiveRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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
    val override: SportsLiveOverride?,
    /** How this session's last load recognised the catalog's items; null unless it is claimed and was loaded. */
    val stats: CatalogStats? = null,
    /** Name of the extra the user can pin a value of; null when the catalog offers none. */
    val filterExtra: String? = null,
    /** The values the addon offers for [filterExtra], exactly as it gives them. */
    val filterOptions: List<String> = emptyList(),
    /** The pinned value; null for All, including a pin the addon no longer offers. */
    val filterValue: String? = null
)

data class SportsLiveSettingsUiState(
    val showSection: Boolean = true,
    val showHomeRow: Boolean = true,
    val catalogs: List<SportsLiveCatalogSetting> = emptyList()
)

@HiltViewModel
class SportsLiveSettingsViewModel @Inject constructor(
    private val prefs: SportsLivePreferencesDataStore,
    addonRepository: AddonRepository,
    private val sportsLiveRepository: SportsLiveRepository
) : ViewModel() {

    private val claimedStats = combine(
        sportsLiveRepository.claimedCatalogKeys,
        sportsLiveRepository.catalogStats
    ) { keys, stats -> stats.filterKeys { it in keys } }

    val uiState: StateFlow<SportsLiveSettingsUiState> = combine(
        addonRepository.getInstalledAddons(),
        combine(prefs.overrides, prefs.catalogOptions, ::Pair),
        prefs.showSection,
        prefs.showHomeRow,
        claimedStats
    ) { addons, (overrides, options), showSection, showHomeRow, stats ->
        val catalogs = addons.enabledAddons().flatMap { addon ->
            addon.catalogs.filter(SportsLiveCatalogPolicy::isClaimable).map { catalog ->
                val key = SportsLiveCatalogPolicy.keyFor(addon, catalog)
                val extra = SportsLiveCatalogOptions.eligibleExtra(catalog)
                SportsLiveCatalogSetting(
                    key = key,
                    addonName = addon.displayName,
                    catalogName = catalog.name,
                    apiType = catalog.apiType,
                    autoClaim = SportsLiveCatalogPolicy.classify(addon, catalog, emptyMap()),
                    override = overrides[key],
                    stats = stats[key],
                    filterExtra = extra?.name,
                    filterOptions = extra?.options.orEmpty(),
                    filterValue = SportsLiveCatalogOptions.effectiveOption(catalog, options[key])?.value
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

    /**
     * Pins [value] of [extra] for the catalog [key] (null for All), then reloads so it applies now.
     * Channels catalogs whose pin did not change are carried over rather than refetched.
     */
    fun setCatalogFilter(key: String, extra: String, value: String?) {
        viewModelScope.launch {
            prefs.setCatalogOption(key, value?.let { CatalogOption(extra, it) })
            try {
                sportsLiveRepository.load(force = false, includeChannels = false)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The section loads with the new pin next time it opens.
            }
        }
    }
}
