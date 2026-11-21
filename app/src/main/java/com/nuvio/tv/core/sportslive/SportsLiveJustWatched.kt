package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.SportsLiveItem

/**
 * The game watched before the one playing (J7, user 2026-11-21: "I watched Man City, switched to Liverpool; to go back
 * I have to open the table and scroll all the way down"). The player's panel and guide show it as one "Just watched"
 * line under Now playing, so ▼ OK goes back; OK plays its last working link at once (the zap resolver serves a held
 * "▶ Ready" or known-good link before any search).
 *
 * The games played this app session, newest first ([played]); copies of one game ([SportsLivePlayingGame.isSameGame])
 * count once. Thread-safe.
 */
class SportsLiveJustWatched(private val keep: Int = KEEP) {
    private val played = ArrayList<SportsLiveItem>()

    /** [item] started playing: it becomes the newest. */
    @Synchronized
    fun played(item: SportsLiveItem) {
        played.removeAll { SportsLivePlayingGame.isSameGame(it, item) }
        played.add(0, item)
        while (played.size > keep) played.removeAt(played.size - 1)
    }

    /**
     * The newest game played before [playing] that is still listed in [available] (live, not over: the caller passes
     * the panel's live games), as listed there; null when there is none. Never [playing] or a copy of it.
     */
    @Synchronized
    fun justWatched(playing: SportsLiveItem?, available: List<SportsLiveItem>): SportsLiveItem? {
        for (earlier in played) {
            if (playing != null && SportsLivePlayingGame.isSameGame(earlier, playing)) continue
            val listed = available.firstOrNull { it.streamKey == earlier.streamKey }
                ?: available.firstOrNull { SportsLivePlayingGame.isSameGame(it, earlier) }
            // Only the newest one before the game playing: an older one is no "just watched".
            return listed?.takeIf { playing == null || !SportsLivePlayingGame.isSameGame(it, playing) }
        }
        return null
    }

    @Synchronized
    fun clear() = played.clear()

    companion object {
        private const val KEEP = 8

        /** The app's one record. */
        val shared = SportsLiveJustWatched()
    }
}
