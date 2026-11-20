package com.nuvio.tv.data.local

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nuvio.tv.core.sportslive.SportsLiveLinkSpeed
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

private val Context.sportsLiveLinkSpeedDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "sports_live_link_speed",
    corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { androidx.datastore.preferences.core.emptyPreferences() }
)

/**
 * Keeps [SportsLiveLinkSpeed]'s book on this device (#147), as [SportsLiveSourceReliabilityStore] keeps the
 * reliability one: one compact JSON value of game ids, addon names and timings (no url), device-wide, its writes
 * conflated. Nothing here leaves the device.
 */
@Singleton
class SportsLiveLinkSpeedStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private companion object {
        const val TAG = "SportsLiveLinkSpeed"
    }

    private val key = stringPreferencesKey("records")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = Channel<Map<String, SportsLiveLinkSpeed.Record>>(Channel.CONFLATED)

    /** Loads the stored book into [SportsLiveLinkSpeed.shared] and saves its changes from then on. */
    fun start() {
        scope.launch {
            val stored = runCatching {
                SportsLiveLinkSpeed.decode(context.sportsLiveLinkSpeedDataStore.data.first()[key])
            }.getOrElse {
                Log.w(TAG, "Could not read link speeds", it)
                emptyMap()
            }
            SportsLiveLinkSpeed.shared.install(stored) { records -> pending.trySend(records) }
            pending.receiveAsFlow().collect { records ->
                runCatching {
                    context.sportsLiveLinkSpeedDataStore.edit { it[key] = SportsLiveLinkSpeed.encode(records) }
                }.onFailure { Log.w(TAG, "Could not save link speeds", it) }
            }
        }
    }
}
