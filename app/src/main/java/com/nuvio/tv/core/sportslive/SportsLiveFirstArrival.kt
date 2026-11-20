package com.nuvio.tv.core.sportslive

/**
 * When a sports start may play (live.20 B2): as soon as the first stream the user's auto-play rules pick has come
 * back, not after every addon answered. A better-ranked source still answering gets [GRACE_MS] from that first pick
 * to answer and win; past it, the pick plays and the slower sources keep answering in the background (the Sources
 * list, failover). A held "▶ Ready" link and the learned link order (#147) are applied before this: they decide the
 * pick and the rank, this decides only how long to wait for it.
 *
 * Why 1.5 s: on the real box (live.19 TV QA) a stream answer took 17 s, and the target for a start is < 5 s. On
 * 2026-11-18/19, addons answering one search came back in waves (cold answers clustered at 6.1 s) and HLS manifests
 * opened in 0.85 s (median): 1.5 s lets an addon of the same wave answer, costs under a third of the 5 s budget, and
 * a wrong first pick is recovered by failover far sooner than a slow addon is waited for.
 */
object SportsLiveFirstArrival {
    const val GRACE_MS = 1_500L

    /** A source's state in a start, in rank order. */
    enum class Answer {
        /** Still answering. */
        PENDING,

        /** Answered, with nothing the user's rules pick. */
        NO_PICK,

        /** Answered with a stream the user's rules pick. */
        PICK
    }

    /**
     * The index of the source in [answers] (rank order, best first) whose pick plays now, or null to keep waiting:
     * the best-ranked pick once every better-ranked source answered without one, or once [graceOver] (the grace
     * since the first pick came back has run out). Null too when no source has a pick yet.
     */
    fun choose(answers: List<Answer>, graceOver: Boolean): Int? {
        val best = answers.indexOfFirst { it == Answer.PICK }
        if (best < 0) return null
        val betterPending = answers.subList(0, best).any { it == Answer.PENDING }
        return if (!betterPending || graceOver) best else null
    }
}
