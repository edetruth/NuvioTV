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
 *   is quarantined only when it fails twice within [STRIKE_WINDOW_MS]. A link that was only slow to
 *   start ([SportsLiveStreamHealth.Problem.NO_START]) is quarantined for [SLOW_START_QUARANTINE_MS]
 *   (#158): slow links often start fine a minute later.
 * - [order] puts good links first and quarantined ones last, so they play only when nothing else can.
 *
 * It also holds the stream the full player is playing for a game ([playing]), so dual screen can take
 * it over as is instead of looking it up again, and the links lookups found for each game ([noteLinks]),
 * so a next-game pick can skip a game whose every link failed ([allQuarantined]).
 */
class SportsLiveWorkingLinks(
    internal val nowMs: () -> Long = { System.currentTimeMillis() },
    /** The stream lists' marks (#156): a failure and a first frame are told to them too. */
    private val marks: SportsLiveLinkMarks? = null,
    /** "▶ Ready" (1b): a failure of a game's held link takes its mark away. */
    private val readiness: SportsLiveGameReadiness? = null
) {
    companion object {
        const val GOOD_AFTER_MS = 30_000L
        const val GOOD_FOR_MS = 3L * 60 * 60 * 1000
        const val QUARANTINE_MS = 15L * 60 * 1000
        const val SLOW_START_QUARANTINE_MS = 2L * 60 * 1000
        const val STRIKE_WINDOW_MS = 10L * 60 * 1000
        private const val MAX_GAMES = 200
        private const val MAX_KNOWN_LINKS = 200

        private val USER_INFO = Regex("^([A-Za-z][A-Za-z0-9+.-]*://)[^/?#@]*@")

        /** The process-wide memory the app uses. */
        val shared = SportsLiveWorkingLinks(marks = SportsLiveLinkMarks.shared, readiness = SportsLiveGameReadiness.shared)

        /** How long a link that failed with [problem] stays quarantined (#158); null: a plain failure. */
        fun quarantineMsFor(problem: SportsLiveStreamHealth.Problem?): Long =
            if (problem == SportsLiveStreamHealth.Problem.NO_START) SLOW_START_QUARANTINE_MS else QUARANTINE_MS

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

        /** The link that failed last with nothing started since ([failedNow]). */
        var failedUrl: String? = null

        /** A quarantined link a lookup had to fall back to, and until when nothing better is expected. */
        var fallback: Stream? = null
        var fallbackUntilMs = 0L

        /** Every link lookups found for the game, as link keys ([noteLinks]). */
        val known = LinkedHashSet<String>()
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
                failedUrl = null
            }
        }
        marks?.played(gameKey, stream.getStreamUrl())
    }

    /**
     * The full player for [gameKey] stopped (it left, or its link [failedUrl] failed): nothing plays
     * for it now.
     */
    fun stoppedPlaying(gameKey: String, failedUrl: String? = null) {
        synchronized(this) {
            if (failedUrl != null) {
                game(gameKey).apply {
                    playing = null
                    this.failedUrl = failedUrl
                }
            } else {
                games[gameKey]?.playing = null
            }
        }
    }

    /** True when [url] is the link that failed last for [gameKey] and nothing has started playing since (C1). */
    fun failedNow(gameKey: String, url: String?): Boolean = synchronized(this) {
        val game = games[gameKey] ?: return false
        val key = linkKey(url) ?: return false
        game.playing == null && linkKey(game.failedUrl) == key
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
     * [url] failed to start or hit a fatal error for [gameKey] ([problem], when known). A good link
     * takes a strike and stays good, unless it already failed within [STRIKE_WINDOW_MS]; any other
     * link is quarantined ([quarantineMsFor]). True when it was quarantined.
     */
    fun failed(gameKey: String, url: String?, problem: SportsLiveStreamHealth.Problem? = null): Boolean {
        val key = linkKey(url) ?: return false
        marks?.failed(gameKey, url, problem)
        readiness?.linkFailed(gameKey, url)
        synchronized(this) {
            val game = game(gameKey)
            if (key in game.good && key !in game.strikes) {
                game.strikes[key] = nowMs()
                return false
            }
        }
        quarantine(gameKey, url, quarantineMsFor(problem))
        return true
    }

    /**
     * [url] is quarantined for [gameKey] for [forMs] (a running longer quarantine is kept), no longer
     * good, and no longer playing.
     */
    fun quarantine(gameKey: String, url: String?, forMs: Long = QUARANTINE_MS) {
        val key = linkKey(url) ?: return
        synchronized(this) {
            game(gameKey).apply {
                good.remove(key)
                strikes.remove(key)
                bad[key] = maxOf(bad[key] ?: 0L, nowMs() + forMs)
                if (linkKey(playing?.getStreamUrl()) == key) playing = null
            }
        }
    }

    /**
     * A lookup for [gameKey] found nothing better than quarantined [stream]: it is served again,
     * with no new search, until its quarantine ends ([fallback]).
     */
    fun noteFallback(gameKey: String, stream: Stream) {
        synchronized(this) {
            game(gameKey).apply {
                fallback = stream
                fallbackUntilMs = linkKey(stream.getStreamUrl())?.let(bad::get) ?: (nowMs() + QUARANTINE_MS)
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

    /** Lookups found [streams] for [gameKey]: they join its known links (at most [MAX_KNOWN_LINKS]). */
    fun noteLinks(gameKey: String, streams: List<Stream>) {
        val keys = streams.mapNotNull { linkKey(it.getStreamUrl()) }
        if (keys.isEmpty()) return
        synchronized(this) {
            val known = game(gameKey).known
            keys.forEach { if (known.size < MAX_KNOWN_LINKS) known += it }
        }
    }

    /**
     * True when lookups found links for [gameKey] and every one of them is quarantined now: the game has no link
     * left to try until a quarantine ends (live.19 re-check: Up next picked a game whose only link had failed).
     */
    fun allQuarantined(gameKey: String): Boolean = synchronized(this) {
        val known = games[gameKey]?.known?.toList().orEmpty()
        known.isNotEmpty() && known.all { isQuarantined(gameKey, it) }
    }

    /** [streams] without [gameKey]'s quarantined links. */
    fun withoutQuarantined(gameKey: String, streams: List<Stream>): List<Stream> =
        streams.filterNot { isQuarantined(gameKey, it) }

    internal fun gameCount(): Int = synchronized(this) { games.size }

    internal fun clear() {
        synchronized(this) { games.clear() }
    }
}
