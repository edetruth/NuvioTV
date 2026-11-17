package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nuvio.tv.core.sportslive.SportsLiveSourceReliability
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private val Context.sportsLiveReliabilityDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "sports_live_source_reliability",
    corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { androidx.datastore.preferences.core.emptyPreferences() }
)

/**
 * Keeps [SportsLiveSourceReliability]'s book on this device (#40): one compact JSON value, capped at
 * [SportsLiveSourceReliability.MAX_ENTRIES] sources. Nothing here leaves the device.
 *
 * It is device-wide, not per profile: how well a provider's streams start and hold is a property
 * of this device's network and the provider, the same whoever watches, so every profile shares (and
 * adds to) the evidence. Writes are conflated, so a burst of outcomes costs one write.
 */
@Singleton
class SportsLiveSourceReliabilityStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val key = stringPreferencesKey("records")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = Channel<Map<String, SportsLiveSourceReliability.Record>>(Channel.CONFLATED)

    /** Loads the stored book into [SportsLiveSourceReliability] and saves its changes from then on. */
    fun start() {
        scope.launch {
            val stored = SportsLiveSourceReliability.decode(context.sportsLiveReliabilityDataStore.data.first()[key])
            SportsLiveSourceReliability.install(stored) { records -> pending.trySend(records) }
            pending.receiveAsFlow().collect { records ->
                context.sportsLiveReliabilityDataStore.edit { it[key] = SportsLiveSourceReliability.encode(records) }
            }
        }
    }
}
