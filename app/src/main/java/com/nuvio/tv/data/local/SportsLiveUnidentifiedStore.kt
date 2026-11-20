package com.nuvio.tv.data.local

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nuvio.tv.core.LogRedact
import com.nuvio.tv.core.sportslive.SportsLiveUnidentified
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

private val Context.sportsLiveUnidentifiedDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "sports_live_unidentified",
    corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { androidx.datastore.preferences.core.emptyPreferences() }
)

/**
 * Keeps [SportsLiveUnidentified.shared] (live.20: titles the app could not place, with their addon's display name and
 * how many loads listed them) across restarts, for Report a problem (#172) and the nightly check (#192). Titles only,
 * never a link; bounded to [SportsLiveUnidentified.MAX_ENTRIES]; nothing here leaves the device. Writes are conflated.
 */
@Singleton
class SportsLiveUnidentifiedStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private companion object {
        const val TAG = "SportsLiveUnidentified"
    }

    private val key = stringPreferencesKey("titles")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = Channel<List<SportsLiveUnidentified.Entry>>(Channel.CONFLATED)

    /** Restores the stored titles into [SportsLiveUnidentified.shared] and saves its changes from then on. */
    fun start() {
        scope.launch {
            // A failed read or write only loses the list; it must never crash the app.
            val stored = runCatching {
                SportsLiveUnidentified.decode(context.sportsLiveUnidentifiedDataStore.data.first()[key])
            }.getOrElse {
                Log.w(TAG, "Could not read unidentified titles", LogRedact.error(it))
                emptyList()
            }
            SportsLiveUnidentified.shared.install(stored) { entries -> pending.trySend(entries) }
            pending.receiveAsFlow().collect { entries ->
                runCatching {
                    context.sportsLiveUnidentifiedDataStore.edit { it[key] = SportsLiveUnidentified.encode(entries) }
                }.onFailure { Log.w(TAG, "Could not save unidentified titles", LogRedact.error(it)) }
            }
        }
    }
}
