package com.nuvio.tv.core.sportslive

/**
 * Source keys ([com.nuvio.tv.domain.model.SportsLiveItem.streamKey]) of games an addon filed under a men's league that
 * ESPN shows are its women's league game (live.23: "Liverpool vs Chelsea · Premier League" was the WSL game). Filled by
 * the score repository when a card matches its women's counterpart; read when lists mark followed games, so a followed
 * men's club never makes its women's game "my team". Bounded, in memory, thread-safe.
 */
class SportsLiveWomenGames {

    private val keys = LinkedHashSet<String>()

    @Synchronized
    fun record(sourceKeys: Collection<String>) {
        for (key in sourceKeys) {
            if (key in keys) continue
            if (keys.size >= MAX_KEPT) keys.remove(keys.first())
            keys += key
        }
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
