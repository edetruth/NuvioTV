package com.nuvio.tv.data.local

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nuvio.tv.core.sportslive.SportsLiveEndedGames
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

private val Context.sportsLiveEndedGamesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "sports_live_ended_games",
    corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { androidx.datastore.preferences.core.emptyPreferences() }
)

/**
 * Keeps when each Live now final was first seen ([SportsLiveEndedGames.shared]) across restarts (C3),
 * so a restart never gives a long-over game a fresh grace. Device-wide: a game ended for every
 * profile. Small and bounded (entries are pruned 6 h after the game ended); writes are conflated.
 */
@Singleton
class SportsLiveEndedGamesStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private companion object {
        const val TAG = "SportsLiveEndedGames"
    }

    private val key = stringPreferencesKey("final_times")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = Channel<Map<String, SportsLiveEndedGames.FinalTime>>(Channel.CONFLATED)

    /** Restores the stored times into [SportsLiveEndedGames.shared] and saves its changes from then on. */
    fun start() {
        scope.launch {
            // A failed read or write only loses the times; it must never crash the app.
            val stored = runCatching {
                SportsLiveEndedGames.decodeTimes(context.sportsLiveEndedGamesDataStore.data.first()[key])
            }.getOrElse {
                Log.w(TAG, "Could not read ended games", it)
                emptyMap()
            }
            SportsLiveEndedGames.shared.install(stored, System.currentTimeMillis()) { times -> pending.trySend(times) }
            pending.receiveAsFlow().collect { times ->
                runCatching {
                    context.sportsLiveEndedGamesDataStore.edit { it[key] = SportsLiveEndedGames.encodeTimes(times) }
                }.onFailure { Log.w(TAG, "Could not save ended games", it) }
            }
        }
    }
}
