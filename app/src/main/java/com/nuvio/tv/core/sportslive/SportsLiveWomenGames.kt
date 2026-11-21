package com.nuvio.tv.core.sportslive

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Source keys ([com.nuvio.tv.domain.model.SportsLiveItem.streamKey]) of games an addon filed under a men's league that
 * ESPN shows are its women's league game (live.23: "Liverpool vs Chelsea · Premier League" was the WSL game). Filled by
 * the score repository when a card matches its women's counterpart; read when lists mark followed games, so a followed
 * men's club never makes its women's game "my team". Bounded, in memory, thread-safe.
 */
class SportsLiveWomenGames {

    private val keys = LinkedHashSet<String>()
    private val revisionFlow = MutableStateFlow(0L)

    /** Bumped when a game becomes a women's game: lists mark followed games again ([SportsLiveSharedBuckets.matchChanges]). */
    val revision: StateFlow<Long> = revisionFlow.asStateFlow()

    @Synchronized
    fun record(sourceKeys: Collection<String>) {
        var changed = false
        for (key in sourceKeys) {
            if (key in keys) continue
            if (keys.size >= MAX_KEPT) keys.remove(keys.first())
            keys += key
            changed = true
        }
        if (changed) revisionFlow.value += 1
    }

    @Synchronized
    fun has(key: String): Boolean = key in keys

    @Synchronized
    internal fun clear() = keys.clear()

    companion object {
        private const val MAX_KEPT = 2_000

        /** What every list uses. */
        val shared = SportsLiveWomenGames()
    }
}
