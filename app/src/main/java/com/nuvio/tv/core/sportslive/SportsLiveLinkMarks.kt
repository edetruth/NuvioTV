package com.nuvio.tv.core.sportslive

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Why a link didn't work for a game, as the stream lists say it (#156, #166). */
enum class SportsLiveLinkFailure(
    /** Whether the text carries how long ago ("Didn't start · 3 min ago"); a dead or protected link does not. */
    val showsAge: Boolean
) {
    DIDNT_START(showsAge = true),
    STOPPED(showsAge = true),
    DEAD(showsAge = false),
    PROTECTED(showsAge = false),
    UNSUPPORTED(showsAge = true),
    REFUSED(showsAge = true),
    EXPIRED(showsAge = true);

    companion object {
        /** The failure a [cause] reads as; null when the cause says nothing the start or stop does not. */
        fun of(cause: SportsLiveLinkCause): SportsLiveLinkFailure? = when (cause) {
            SportsLiveLinkCause.DRM -> PROTECTED
            SportsLiveLinkCause.HEADERS -> REFUSED
            SportsLiveLinkCause.CODEC -> UNSUPPORTED
            SportsLiveLinkCause.EXPIRED_OR_GEO -> EXPIRED
            SportsLiveLinkCause.DEAD_HOST -> DEAD
            SportsLiveLinkCause.OTHER -> null
        }
    }
}

/** What a stream list shows on a link of a game (#156). */
sealed interface SportsLiveLinkMark {
    /** It failed for this game [atMs]: dimmed, amber ⚠ and [reason], in the trailing "Didn't work" group. */
    data class Failed(val reason: SportsLiveLinkFailure, val atMs: Long) : SportsLiveLinkMark {
        /** Whole minutes since it failed, at least 0. */
        fun minutesAgo(nowMs: Long): Int = ((nowMs - atMs).coerceAtLeast(0L) / 60_000L).toInt()
    }

    /** It played for this game this session: a subtle "✓ Played". */
    data object Played : SportsLiveLinkMark
}

/**
 * What each Sports & Live game's links did this session (#156): which failed (why, and when) and
 * which played, for every place a stream is chosen. Also which links are DRM-protected without a
 * licence (#166), so they are never auto-picked, never count as a game's working stream, and are
 * hidden when [hideUnplayable] is on.
 *
 * In memory only, keyed like [SportsLiveWorkingLinks] (a game by its content id, a link by
 * [SportsLiveWorkingLinks.linkKey]). A mark lasts [MARK_FOR_MS] (about a game session's slice); a failure
 * mark clears when the link plays. [revision] moves on every change, for lists to redraw.
 */
class SportsLiveLinkMarks(internal val nowMs: () -> Long = { System.currentTimeMillis() }) {
    companion object {
        const val MARK_FOR_MS = 30L * 60 * 1000

        /** A cause noted this recently is the cause of the failure recorded next for the link. */
        const val CAUSE_FRESH_MS = 2L * 60 * 1000
        private const val MAX_GAMES = 100
        private const val MAX_LINKS = 1_000

        /** The process-wide marks the app uses. */
        val shared = SportsLiveLinkMarks()
    }

    private class Game {
        val failed = HashMap<String, SportsLiveLinkMark.Failed>()

        /** Link -> when it last started playing. */
        val played = HashMap<String, Long>()
    }

    private class Cause(val cause: SportsLiveLinkCause, val atMs: Long)

    private val games = LinkedHashMap<String, Game>(16, 0.75f, true)
    private val causes = object : LinkedHashMap<String, Cause>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cause>?): Boolean = size > MAX_LINKS
    }
    private val protectedLinks = LinkedHashSet<String>()

    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    /** Settings → Sports & Live → Playing → "Hide links this TV can't play" (#166); On by default. */
    val hideUnplayable = MutableStateFlow(true)

    private fun bump() {
        _revision.update { it + 1 }
    }

    private fun game(key: String): Game {
        val now = nowMs()
        val game = games.getOrPut(key) { Game() }
        game.failed.values.removeAll { now - it.atMs >= MARK_FOR_MS }
        game.played.values.removeAll { now - it >= MARK_FOR_MS }
        while (games.size > MAX_GAMES) games.remove(games.keys.first())
        return game
    }

    /**
     * Why [url] failed, as classified from the player's error or its manifest. A DRM link is remembered
     * as protected for every game (unless its addon names a licence, [SportsLiveStreamLicences]).
     */
    fun noteCause(url: String?, cause: SportsLiveLinkCause) {
        val key = SportsLiveWorkingLinks.linkKey(url) ?: return
        synchronized(this) {
            causes[key] = Cause(cause, nowMs())
            if (cause == SportsLiveLinkCause.DRM && !SportsLiveStreamLicences.has(url)) {
                protectedLinks += key
                while (protectedLinks.size > MAX_LINKS) protectedLinks.remove(protectedLinks.first())
            }
        }
        bump()
    }

    /** The cause noted for [url] within [CAUSE_FRESH_MS], or null. */
    fun causeOf(url: String?): SportsLiveLinkCause? {
        val key = SportsLiveWorkingLinks.linkKey(url) ?: return null
        return synchronized(this) { causes[key]?.takeIf { nowMs() - it.atMs < CAUSE_FRESH_MS }?.cause }
    }

    /**
     * [url] failed for [gameKey] with [problem] (null: an error before or without a health problem). Its
     * reason is the cause noted for it, else what the problem says: a link that never played this
     * session "didn't start", one that did "stopped playing".
     */
    fun failed(gameKey: String, url: String?, problem: SportsLiveStreamHealth.Problem? = null) {
        val key = SportsLiveWorkingLinks.linkKey(url) ?: return
        val cause = causeOf(url)?.let(SportsLiveLinkFailure::of)
        synchronized(this) {
            val game = game(gameKey)
            val reason = cause ?: reasonFor(problem, playedBefore = key in game.played)
            game.failed[key] = SportsLiveLinkMark.Failed(reason, nowMs())
        }
        bump()
    }

    /** [url] shows its first frame for [gameKey]: its failure mark clears, and it reads "✓ Played". */
    fun played(gameKey: String, url: String?) {
        val key = SportsLiveWorkingLinks.linkKey(url) ?: return
        synchronized(this) {
            val game = game(gameKey)
            game.failed.remove(key)
            game.played[key] = nowMs()
        }
        bump()
    }

    /** True when [url] needs a DRM licence its addon does not provide: it cannot play on this TV. */
    fun isProtected(url: String?): Boolean {
        val key = SportsLiveWorkingLinks.linkKey(url) ?: return false
        return synchronized(this) { key in protectedLinks } && !SportsLiveStreamLicences.has(url)
    }

    /** The mark of [url] for [gameKey], or null (untried, or its mark lapsed). A protected link reads protected. */
    fun markOf(gameKey: String, url: String?): SportsLiveLinkMark? {
        val key = SportsLiveWorkingLinks.linkKey(url) ?: return null
        if (isProtected(url)) {
            val at = synchronized(this) { games[gameKey]?.failed?.get(key)?.atMs } ?: nowMs()
            return SportsLiveLinkMark.Failed(SportsLiveLinkFailure.PROTECTED, at)
        }
        return synchronized(this) {
            val now = nowMs()
            val game = games[gameKey] ?: return null
            game.failed[key]?.takeIf { now - it.atMs < MARK_FOR_MS }
                ?: game.played[key]?.takeIf { now - it < MARK_FOR_MS }?.let { SportsLiveLinkMark.Played }
        }
    }

    /** Forgets every mark ("Reset stream memory"). */
    fun clear() {
        synchronized(this) {
            games.clear()
            causes.clear()
            protectedLinks.clear()
        }
        bump()
    }

    private fun reasonFor(problem: SportsLiveStreamHealth.Problem?, playedBefore: Boolean): SportsLiveLinkFailure =
        when (problem) {
            SportsLiveStreamHealth.Problem.DEAD_LINK -> SportsLiveLinkFailure.DEAD
            SportsLiveStreamHealth.Problem.NO_START -> SportsLiveLinkFailure.DIDNT_START
            null -> if (playedBefore) SportsLiveLinkFailure.STOPPED else SportsLiveLinkFailure.DIDNT_START
            else -> SportsLiveLinkFailure.STOPPED
        }
}

/**
 * A stream list's order with the marks (#156): links that work or are untried first, then the
 * "Didn't work" group (links that failed for this game, and protected ones), then [isNotice] rows; each
 * part in its given order. Protected links are left out when [hideProtected] (#166), unless [keep]
 * (the link playing now).
 */
data class SportsLiveLinkGroups<T>(
    val items: List<T>,
    /** Index in [items] where "Didn't work" starts; -1 when no link is in it. */
    val didntWorkFrom: Int
) {
    companion object {
        fun <T> of(
            items: List<T>,
            markOf: (T) -> SportsLiveLinkMark?,
            hideProtected: Boolean,
            isNotice: (T) -> Boolean = { false },
            keep: (T) -> Boolean = { false }
        ): SportsLiveLinkGroups<T> {
            val working = ArrayList<T>(items.size)
            val failed = ArrayList<T>()
            val notices = ArrayList<T>()
            for (item in items) {
                if (isNotice(item)) {
                    notices += item
                    continue
                }
                val mark = markOf(item) as? SportsLiveLinkMark.Failed
                when {
                    mark == null -> working += item
                    mark.reason == SportsLiveLinkFailure.PROTECTED && hideProtected && !keep(item) -> Unit
                    else -> failed += item
                }
            }
            return SportsLiveLinkGroups(working + failed + notices, if (failed.isEmpty()) -1 else working.size)
        }
    }
}
