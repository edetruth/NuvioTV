package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.Stream

/**
 * Which stream links work for each Sports & Live game (#65), shared by full screen, dual screen and
 * zap. In memory for this process only; nothing is saved and nothing here makes a request.
 *
 * A game is keyed by its content id (the card's [com.nuvio.tv.domain.model.SportsLiveItem.id], the
 * full player's contentId), a link by its stream url.
 * - A link that played for [GOOD_AFTER_MS] is good for that game for [GOOD_FOR_MS].
 * - A link that failed to start or hit a fatal error is quarantined for [QUARANTINE_MS].
 * - [order] puts good links first and quarantined ones last, so they play only when nothing else can.
 *
 * It also holds the stream the full player is playing for a game ([playing]), so dual screen can take
 * it over as is instead of looking it up again.
 */
class SportsLiveWorkingLinks(internal val nowMs: () -> Long = { System.currentTimeMillis() }) {
    companion object {
        const val GOOD_AFTER_MS = 30_000L
        const val GOOD_FOR_MS = 3L * 60 * 60 * 1000
        const val QUARANTINE_MS = 15L * 60 * 1000
        private const val MAX_GAMES = 200

        /** The process-wide memory the app uses. */
        val shared = SportsLiveWorkingLinks()
    }

    private class Game {
        /** url -> when it stops counting as good. */
        val good = HashMap<String, Long>()

        /** url -> when its quarantine ends. */
        val bad = HashMap<String, Long>()

        /** The stream last sent to the full player, matched by url when it starts playing. */
        var launched: Stream? = null

        /** The stream the full player plays now; null when it failed or another one is launched. */
        var playing: Stream? = null

        /** When [playing] showed its first frame. */
        var playingSinceMs = 0L
    }

    // Insertion order is age: the oldest game is dropped first.
    private val games = LinkedHashMap<String, Game>()

    private fun game(key: String): Game = games.getOrPut(key) { Game() }.also {
        while (games.size > MAX_GAMES) games.remove(games.keys.first())
    }

    /** [stream] is about to open in the full player for [gameKey]; the stream it played before is no longer playing. */
    fun noteLaunch(gameKey: String, stream: Stream) {
        synchronized(this) {
            game(gameKey).apply {
                launched = stream
                playing = null
            }
        }
    }

    /** The stream last launched for [gameKey], or null. */
    fun launched(gameKey: String): Stream? = synchronized(this) { games[gameKey]?.launched }

    /** The full player shows [stream]'s first frame for [gameKey]. */
    fun startedPlaying(gameKey: String, stream: Stream) {
        synchronized(this) {
            game(gameKey).apply {
                // The same link starting again (a seek, a new surface) keeps its play time.
                if (playing?.getStreamUrl() != stream.getStreamUrl()) playingSinceMs = nowMs()
                playing = stream
            }
        }
    }

    /**
     * The full player still plays for [gameKey]: once its link has played [GOOD_AFTER_MS] it is
     * marked good. True when it was.
     */
    fun notePlaying(gameKey: String): Boolean {
        val url = synchronized(this) {
            val game = games[gameKey] ?: return false
            val url = game.playing?.getStreamUrl() ?: return false
            if (nowMs() - game.playingSinceMs < GOOD_AFTER_MS) return false
            url
        }
        markGood(gameKey, url)
        return true
    }

    /** The stream the full player plays for [gameKey] now, or null. */
    fun playing(gameKey: String): Stream? = synchronized(this) { games[gameKey]?.playing }

    /** [url] played for [playedMs]: good for [gameKey] once that reaches [GOOD_AFTER_MS]. */
    fun played(gameKey: String, url: String?, playedMs: Long) {
        if (playedMs >= GOOD_AFTER_MS) markGood(gameKey, url)
    }

    /** [url] works for [gameKey]: good for [GOOD_FOR_MS] from now, and out of quarantine. */
    fun markGood(gameKey: String, url: String?) {
        if (url.isNullOrBlank()) return
        synchronized(this) {
            game(gameKey).apply {
                bad.remove(url)
                good[url] = nowMs() + GOOD_FOR_MS
            }
        }
    }

    /** [url] failed for [gameKey]: quarantined for [QUARANTINE_MS], no longer good, and no longer playing. */
    fun quarantine(gameKey: String, url: String?) {
        if (url.isNullOrBlank()) return
        synchronized(this) {
            game(gameKey).apply {
                good.remove(url)
                bad[url] = nowMs() + QUARANTINE_MS
                if (playing?.getStreamUrl() == url) playing = null
            }
        }
    }

    fun isGood(gameKey: String, url: String?): Boolean = isListed(gameKey, url) { it.good }

    fun isQuarantined(gameKey: String, url: String?): Boolean = isListed(gameKey, url) { it.bad }

    fun isQuarantined(gameKey: String, stream: Stream): Boolean = isQuarantined(gameKey, stream.getStreamUrl())

    private fun isListed(gameKey: String, url: String?, list: (Game) -> HashMap<String, Long>): Boolean {
        if (url == null) return false
        synchronized(this) {
            val entries = games[gameKey]?.let(list) ?: return false
            val until = entries[url] ?: return false
            if (nowMs() < until) return true
            entries.remove(url)
            return false
        }
    }

    /** [streams] with [gameKey]'s good links first and quarantined ones last; otherwise in their order. */
    fun order(gameKey: String, streams: List<Stream>): List<Stream> {
        if (streams.isEmpty()) return streams
        val (good, rest) = streams.partition { isGood(gameKey, it.getStreamUrl()) }
        val (bad, fresh) = rest.partition { isQuarantined(gameKey, it) }
        return good + fresh + bad
    }

    /** [streams] without [gameKey]'s quarantined links. */
    fun withoutQuarantined(gameKey: String, streams: List<Stream>): List<Stream> =
        streams.filterNot { isQuarantined(gameKey, it) }

    internal fun clear() {
        synchronized(this) { games.clear() }
    }
}
