package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.streams.UnplayableStreamFilter
import com.nuvio.tv.domain.model.Stream

/**
 * How many automatic recoveries a game may have (#138): at most [MAX_RECOVERIES] within
 * [WINDOW_MS], across every sports player that plays it, before the viewer is asked what to do.
 * In memory only.
 */
class SportsLiveRecoveryBudget(private val nowMs: () -> Long = { System.currentTimeMillis() }) {
    companion object {
        const val MAX_RECOVERIES = SportsLiveStreamThresholds.MAX_RECOVERIES
        const val WINDOW_MS = SportsLiveStreamThresholds.RECOVERY_WINDOW_MS
        private const val MAX_GAMES = 200

        /** The process-wide budget the app uses. */
        val shared = SportsLiveRecoveryBudget()
    }

    private val spent = LinkedHashMap<String, ArrayDeque<Long>>()

    /** Spends one recovery of [gameKey]; false (nothing spent) when its budget is used up. */
    fun take(gameKey: String): Boolean = synchronized(this) {
        val now = nowMs()
        val times = spent.getOrPut(gameKey) { ArrayDeque() }
        while (times.isNotEmpty() && now - times.first() >= WINDOW_MS) times.removeFirst()
        if (times.size >= MAX_RECOVERIES) return false
        times.addLast(now)
        while (spent.size > MAX_GAMES) spent.remove(spent.keys.first())
        true
    }

    internal fun clear() = synchronized(this) { spent.clear() }
}

/**
 * One sports player's recovery steps (#138); each player (the full player, each dual pane, the mini
 * player) has its own, so a dual pane never touches the other pane. For a [SportsLiveStreamHealth]
 * problem it says what to do, spending one of the game's [SportsLiveRecoveryBudget] recoveries:
 * 1. [Step.RETRY_SAME]: the same link once more, at the live edge (a transient failure, the way
 *    switching away and back works), unless it was already retried within the budget window or the
 *    problem rules a retry out ([SportsLiveStreamHealth.Problem.retriesSameLink]: an error burst, a
 *    link that never started, a dead host);
 * 2. [Step.NEXT_LINK]: the next best link for the game, then other sources ([SportsLiveRecoveryCandidates]);
 *    the player asks the viewer when none is left;
 * 3. [Step.CHOICES]: the budget is used up; the player asks the viewer (Choose stream / Next live game).
 */
class SportsLiveRecoveryLadder(
    private val budget: SportsLiveRecoveryBudget = SportsLiveRecoveryBudget.shared,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    enum class Step { RETRY_SAME, NEXT_LINK, CHOICES }

    /** Link -> when it was last retried as is. */
    private val retried = HashMap<String, Long>()

    /**
     * [started] is false while [url] has not shown a first frame (#153): a link that never started
     * ([SportsLiveStreamHealth.Problem.NO_START], a dead host) then goes to the next link without spending
     * the game's budget, which is for a game that was playing; the caller's failover run bounds it
     * ([SportsLiveFailover]). The dual and mini players keep the default.
     */
    fun decide(gameKey: String, url: String?, problem: SportsLiveStreamHealth.Problem, started: Boolean = true): Step {
        if (!started && problem.isStartFailure) return Step.NEXT_LINK
        if (!budget.take(gameKey)) return Step.CHOICES
        val link = SportsLiveWorkingLinks.linkKey(url) ?: return Step.NEXT_LINK
        val now = nowMs()
        retried.values.removeAll { now - it >= SportsLiveRecoveryBudget.WINDOW_MS }
        if (!problem.retriesSameLink || link in retried) return Step.NEXT_LINK
        retried[link] = now
        return Step.RETRY_SAME
    }
}

/**
 * The links a recovery may switch to (#138), best first: the game's own links (good ones first, as
 * [SportsLiveWorkingLinks.order] ranks them), then its other sources' (a merged card's siblings).
 * Never a link already [tried], a quarantined one, one from a source cooling down after a rate
 * limit ([coolingAddonNames]), an addon's notice entry or a junk link.
 */
object SportsLiveRecoveryCandidates {
    fun order(
        gameKey: String,
        primary: List<Stream>,
        siblings: List<Stream>,
        tried: Set<String>,
        workingLinks: SportsLiveWorkingLinks,
        coolingAddonNames: Set<String>
    ): List<Stream> {
        val seen = HashSet<String>(tried)
        return (eligible(gameKey, primary, workingLinks, coolingAddonNames) +
            eligible(gameKey, siblings, workingLinks, coolingAddonNames))
            .filter { stream -> SportsLiveWorkingLinks.linkKey(stream.getStreamUrl())?.let(seen::add) == true }
    }

    /**
     * [streams] a recovery may use, in [SportsLiveWorkingLinks.order] (see [order]). Links without a
     * url (a torrent, one the full player prepares) are kept; [order] drops them.
     */
    fun eligible(
        gameKey: String,
        streams: List<Stream>,
        workingLinks: SportsLiveWorkingLinks,
        coolingAddonNames: Set<String>
    ): List<Stream> = workingLinks.order(
        gameKey,
        streams.filterNot { stream ->
            workingLinks.isQuarantined(gameKey, stream) ||
                stream.addonName.trim() in coolingAddonNames ||
                UnplayableStreamFilter.isUnplayableJunk(stream) ||
                SportsLiveNoticeEntries.isNotice(stream)
        }
    )
}
