package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.Stream

/**
 * Which stream links work for each Sports & Live game (#65), shared by full screen, dual screen and
 * zap. In memory for this process only; nothing is saved and nothing here makes a request.
 *
 * A game is keyed by its content id (the card's [com.nuvio.tv.domain.model.SportsLiveItem.id], the
 * full player's contentId), a link by its url without user info ([linkKey]), so the addon's url and
 * the url the player plays (credentials moved into headers) are the same link.
 * - A link with [GOOD_AFTER_MS] of actual playback progress is good for that game for [GOOD_FOR_MS].
 * - A link that fails ([failed]) is quarantined for [QUARANTINE_MS], unless it is good: a good link
 *   is quarantined only when it fails twice within [STRIKE_WINDOW_MS].
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
        const val STRIKE_WINDOW_MS = 10L * 60 * 1000
        private const val MAX_GAMES = 200

        private val USER_INFO = Regex("^([A-Za-z][A-Za-z0-9+.-]*://)[^/?#@]*@")

        /** The process-wide memory the app uses. */
        val shared = SportsLiveWorkingLinks()

        /** [url] as a link key: without user info, as the player plays it; null when blank. */
        internal fun linkKey(url: String?): String? = url?.takeIf { it.isNotBlank() }?.replaceFirst(USER_INFO, "$1")
    }

    private class Game {
        /** Link -> when it stops counting as good. */
        val good = HashMap<String, Long>()

        /** Link -> when its quarantine ends. */
        val bad = HashMap<String, Long>()

        /** Good link -> when it last failed (its first strike). */
        val strikes = HashMap<String, Long>()

        /** The stream last sent to the full player, matched by url when it starts playing. */
        var launched: Stream? = null

        /** The stream the full player plays now; null once it failed, stopped, or another one launched. */
        var playing: Stream? = null

        /** Playback progress of [playing], in ms. */
        var playedMs = 0L

        /** A quarantined link a lookup had to fall back to, and until when nothing better is expected. */
        var fallback: Stream? = null
        var fallbackUntilMs = 0L
    }

    // In access order: the game used least recently is dropped first.
    private val games = LinkedHashMap<String, Game>(16, 0.75f, true)

    /** [key]'s game for a write, with its expired entries pruned. */
    private fun game(key: String): Game {
        val now = nowMs()
        val game = games.getOrPut(key) { Game() }
        game.good.values.removeAll { it <= now }
        game.bad.values.removeAll { it <= now }
        game.strikes.values.removeAll { now - it >= STRIKE_WINDOW_MS }
        if (game.fallback != null && game.fallbackUntilMs <= now) game.fallback = null
        while (games.size > MAX_GAMES) games.remove(games.keys.first())
        return game
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
                if (linkKey(playing?.getStreamUrl()) != linkKey(stream.getStreamUrl())) playedMs = 0L
                playing = stream
            }
        }
    }

    /** The full player for [gameKey] stopped (it left, or its link failed): nothing plays for it now. */
    fun stoppedPlaying(gameKey: String) {
        synchronized(this) { games[gameKey]?.playing = null }
    }

    /** The stream the full player plays for [gameKey] now, or null. */
    fun playing(gameKey: String): Stream? = synchronized(this) { games[gameKey]?.playing }

    /**
     * The full player's position for [gameKey] advanced over [deltaMs]: once its link has
     * [GOOD_AFTER_MS] of progress it is marked good. True when it was.
     */
    fun progressed(gameKey: String, deltaMs: Long): Boolean {
        val url = synchronized(this) {
            val game = games[gameKey] ?: return false
            val url = game.playing?.getStreamUrl() ?: return false
            game.playedMs += deltaMs
            if (game.playedMs < GOOD_AFTER_MS) return false
            url
        }
        markGood(gameKey, url)
        return true
    }

    /** [url] played for [playedMs] (progress only): good for [gameKey] once that reaches [GOOD_AFTER_MS]. */
    fun played(gameKey: String, url: String?, playedMs: Long) {
        if (playedMs >= GOOD_AFTER_MS) markGood(gameKey, url)
    }

    /** [url] works for [gameKey]: good for [GOOD_FOR_MS] from now, and out of quarantine. */
    fun markGood(gameKey: String, url: String?) {
        val key = linkKey(url) ?: return
        synchronized(this) {
            game(gameKey).apply {
                bad.remove(key)
                strikes.remove(key)
                good[key] = nowMs() + GOOD_FOR_MS
            }
        }
    }

    /**
     * [url] failed to start or hit a fatal error for [gameKey]. A good link takes a strike and stays
     * good, unless it already failed within [STRIKE_WINDOW_MS]; any other link is quarantined. True
     * when it was quarantined.
     */
    fun failed(gameKey: String, url: String?): Boolean {
        val key = linkKey(url) ?: return false
        synchronized(this) {
            val game = game(gameKey)
            if (key in game.good && key !in game.strikes) {
                game.strikes[key] = nowMs()
                return false
            }
        }
        quarantine(gameKey, url)
        return true
    }

    /** [url] is quarantined for [gameKey] for [QUARANTINE_MS], no longer good, and no longer playing. */
    fun quarantine(gameKey: String, url: String?) {
        val key = linkKey(url) ?: return
        synchronized(this) {
            game(gameKey).apply {
                good.remove(key)
                strikes.remove(key)
                bad[key] = nowMs() + QUARANTINE_MS
                if (linkKey(playing?.getStreamUrl()) == key) playing = null
            }
        }
    }

    /**
     * A lookup for [gameKey] found nothing better than quarantined [stream]: it is served again,
     * with no new search, until that quarantine window ends ([fallback]).
     */
    fun noteFallback(gameKey: String, stream: Stream) {
        synchronized(this) {
            game(gameKey).apply {
                fallback = stream
                fallbackUntilMs = nowMs() + QUARANTINE_MS
            }
        }
    }

    /** The stream [noteFallback] kept for [gameKey] while its window lasts, or null. */
    fun fallback(gameKey: String): Stream? = synchronized(this) {
        games[gameKey]?.takeIf { it.fallbackUntilMs > nowMs() }?.fallback
    }

    fun isGood(gameKey: String, url: String?): Boolean = isListed(gameKey, url) { it.good }

    fun isQuarantined(gameKey: String, url: String?): Boolean = isListed(gameKey, url) { it.bad }

    fun isQuarantined(gameKey: String, stream: Stream): Boolean = isQuarantined(gameKey, stream.getStreamUrl())

    private fun isListed(gameKey: String, url: String?, list: (Game) -> HashMap<String, Long>): Boolean {
        val key = linkKey(url) ?: return false
        synchronized(this) {
            val until = games[gameKey]?.let(list)?.get(key) ?: return false
            return nowMs() < until
        }
    }

    /** [streams] with [gameKey]'s good links first and quarantined ones last; otherwise in their order. */
    fun order(gameKey: String, streams: List<Stream>): List<Stream> {
        if (streams.isEmpty()) return streams
        val (good, rest) = streams.partition { isGood(gameKey, it.getStreamUrl()) }
        val (bad, fresh) = rest.partition { isQuarantined(gameKey, it) }
        return good + fresh + bad
    }

    /** The stream playing, else last launched, for [gameKey] when its link is known good; else null. */
    fun goodStream(gameKey: String): Stream? = synchronized(this) {
        val game = games[gameKey] ?: return null
        listOfNotNull(game.playing, game.launched).firstOrNull { isGood(gameKey, it.getStreamUrl()) }
    }

    /** [streams] without [gameKey]'s quarantined links. */
    fun withoutQuarantined(gameKey: String, streams: List<Stream>): List<Stream> =
        streams.filterNot { isQuarantined(gameKey, it) }

    internal fun gameCount(): Int = synchronized(this) { games.size }

    internal fun clear() {
        synchronized(this) { games.clear() }
    }
}
