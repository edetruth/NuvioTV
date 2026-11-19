package com.nuvio.tv.core.sportslive

/**
 * One sports player's stream health (#138), shared by the full player in sports mode, each dual
 * screen pane and the mini player. Pure: the player's adapter feeds it [Sample]s about once a
 * second ([POLL_INTERVAL_MS]) and its errors ([onError]); it says when the stream has gone bad.
 *
 * It watches only a stream that has shown its first frame ([onFirstFrame]); start-up has its own
 * first-frame timeouts. It finds:
 * - [Problem.BUFFERING_TOO_LONG]: buffering for [BUFFERING_TOO_LONG_MS] while meant to play;
 * - [Problem.FROZEN]: ready and meant to play, but neither a new rendered frame nor any position
 *   advance for [FROZEN_MS] (both must stall, so an audio-only stream whose position moves is fine);
 * - [Problem.UNEXPECTED_END]: a live stream (one that was ever live) reached its end;
 * - [Problem.ERROR_BURST]: [ERROR_BURST_COUNT] errors within [ERROR_BURST_WINDOW_MS] ([onError]).
 *
 * A paused player (not meant to play), an ad, and a position jump (a seek or a discontinuity) never
 * count as a stall: each starts the stall timing over. A problem is reported once; the owner then
 * recovers and calls [rebaseline] (same link) or [reset] (another link).
 */
class SportsLiveStreamHealth(private val nowMs: () -> Long = { System.currentTimeMillis() }) {
    companion object {
        const val POLL_INTERVAL_MS = SportsLiveStreamThresholds.POLL_INTERVAL_MS
        const val BUFFERING_TOO_LONG_MS = SportsLiveStreamThresholds.BUFFERING_TOO_LONG_MS
        const val FROZEN_MS = SportsLiveStreamThresholds.FROZEN_MS
        const val ERROR_BURST_WINDOW_MS = SportsLiveStreamThresholds.ERROR_BURST_WINDOW_MS
        const val ERROR_BURST_COUNT = SportsLiveStreamThresholds.ERROR_BURST_COUNT
    }

    /**
     * [isStall]: the stream stopped moving (remembered as a stall, not a failure, for its source).
     * [retriesSameLink]: loading the same link again may help; when false the ladder moves on at once.
     */
    enum class Problem(val isStall: Boolean, val retriesSameLink: Boolean = true) {
        BUFFERING_TOO_LONG(isStall = true),
        FROZEN(isStall = true),
        UNEXPECTED_END(isStall = false),

        /** One playback error. */
        ERROR(isStall = false),

        /** A second error within [ERROR_BURST_WINDOW_MS]: retrying the same link again is pointless. */
        ERROR_BURST(isStall = false, retriesSameLink = false),

        /** A link never showed its first frame in time ([SportsLiveStartupTimeout]). */
        NO_START(isStall = false, retriesSameLink = false),

        /** The link's host does not exist (an unknown host, a DNS failure): fatal on the first hit. */
        DEAD_LINK(isStall = false, retriesSameLink = false),

        /** The stream fell behind its live window again and again ([onBehindLiveWindow]). */
        BEHIND_LIVE_WINDOW_REPEATED(isStall = true);

        /** The link never played: it did not start in time, or its host does not exist. */
        val isStartFailure: Boolean get() = this == NO_START || this == DEAD_LINK
    }

    enum class State { IDLE, BUFFERING, READY, ENDED }

    /**
     * What the player shows now. [positionMs] must only grow while playing (for a live stream, the
     * position in its period, not in the sliding window). [renderedFrames] is null when the stream
     * has no video or the count is unknown: the position alone decides then.
     */
    data class Sample(
        val state: State,
        val playWhenReady: Boolean,
        val positionMs: Long,
        val renderedFrames: Long?,
        val isLive: Boolean,
        val isPlayingAd: Boolean = false
    )

    private var started = false
    private var everLive = false
    private var reported = false
    private var bufferingSinceMs: Long? = null
    private var stuckSinceMs: Long? = null
    private var lastPositionMs: Long? = null
    private var lastFrames: Long? = null
    private val errorTimes = ArrayDeque<Long>()
    private val behindLiveWindowTimes = ArrayDeque<Long>()

    /** True when the last sample showed the stream moving (a new frame or a position advance). */
    var isProgressing: Boolean = false
        private set

    /** The link playing, as [SportsLiveWorkingLinks.linkKey], when the owner names it ([onFirstFrame]). */
    private var link: String? = null

    /**
     * The first frame of the link showed: watching starts. With [link] named, a different link than
     * the one before forgets the earlier link's errors (a first frame again after a retry keeps them).
     */
    fun onFirstFrame(link: String? = null) {
        val key = SportsLiveWorkingLinks.linkKey(link)
        if (key != null && key != this.link) {
            errorTimes.clear()
            behindLiveWindowTimes.clear()
            everLive = false
        }
        if (key != null) this.link = key
        started = true
        rebaseline()
    }

    /** Timing starts over on the same link (a retry, a seek): nothing is reported until it stalls again. */
    fun rebaseline() {
        reported = false
        bufferingSinceMs = null
        stuckSinceMs = null
        lastPositionMs = null
        lastFrames = null
        isProgressing = false
    }

    /** Another link loads: nothing is watched until its first frame, and earlier errors are forgotten. */
    fun reset() {
        started = false
        link = null
        everLive = false
        errorTimes.clear()
        behindLiveWindowTimes.clear()
        rebaseline()
    }

    /**
     * The live stream fell behind its live window (HLS BehindLiveWindowException). Null: the owner
     * moves it back to the live edge in place (same player, same link) and it does not count as an
     * error. [Problem.BEHIND_LIVE_WINDOW_REPEATED] when it is the
     * [SportsLiveStreamThresholds.BEHIND_LIVE_WINDOW_COUNT]th within the window: a stall to recover from.
     */
    fun onBehindLiveWindow(): Problem? {
        val now = nowMs()
        behindLiveWindowTimes.addLast(now)
        while (behindLiveWindowTimes.isNotEmpty() &&
            now - behindLiveWindowTimes.first() >= SportsLiveStreamThresholds.BEHIND_LIVE_WINDOW_WINDOW_MS
        ) behindLiveWindowTimes.removeFirst()
        if (behindLiveWindowTimes.size < SportsLiveStreamThresholds.BEHIND_LIVE_WINDOW_COUNT) {
            rebaseline()
            return null
        }
        behindLiveWindowTimes.clear()
        return report(Problem.BEHIND_LIVE_WINDOW_REPEATED)
    }

    /** A playback error: [Problem.ERROR_BURST] when it is the [ERROR_BURST_COUNT]th within the window. */
    fun onError(): Problem {
        val now = nowMs()
        errorTimes.addLast(now)
        while (errorTimes.isNotEmpty() && now - errorTimes.first() >= ERROR_BURST_WINDOW_MS) errorTimes.removeFirst()
        reported = true
        isProgressing = false
        return if (errorTimes.size >= ERROR_BURST_COUNT) Problem.ERROR_BURST else Problem.ERROR
    }

    /** The problem [sample] shows, once; null while the stream is healthy (or already reported). */
    fun sample(sample: Sample): Problem? {
        if (sample.isLive) everLive = true
        if (!started || reported) return null
        val now = nowMs()
        if (sample.state == State.ENDED) {
            isProgressing = false
            return if (everLive) report(Problem.UNEXPECTED_END) else null
        }
        // Paused, an ad, or nothing loaded: not a stall; timing starts over once it plays again.
        if (!sample.playWhenReady || sample.isPlayingAd || sample.state == State.IDLE) {
            bufferingSinceMs = null
            stuckSinceMs = null
            lastPositionMs = null
            lastFrames = null
            isProgressing = false
            return null
        }
        if (sample.state == State.BUFFERING) {
            isProgressing = false
            stuckSinceMs = null
            val since = bufferingSinceMs ?: now.also { bufferingSinceMs = it }
            return if (now - since >= BUFFERING_TOO_LONG_MS) report(Problem.BUFFERING_TOO_LONG) else null
        }
        // READY and meant to play.
        bufferingSinceMs = null
        val lastPosition = lastPositionMs
        val lastFrameCount = lastFrames
        lastPositionMs = sample.positionMs
        lastFrames = sample.renderedFrames
        if (lastPosition == null) {
            // The first sample since a baseline: stall timing starts here.
            isProgressing = false
            stuckSinceMs = now
            return null
        }
        // Any position change (a jump back is a seek or a discontinuity) counts as moving; so does
        // any frame-count change (it starts at zero again with a new decoder).
        val positionMoved = sample.positionMs != lastPosition
        val framesMoved = sample.renderedFrames != null && lastFrameCount != null &&
            sample.renderedFrames != lastFrameCount
        if (positionMoved || framesMoved) {
            // The stall timing counts from the last move.
            isProgressing = true
            stuckSinceMs = now
            return null
        }
        isProgressing = false
        val since = stuckSinceMs ?: now.also { stuckSinceMs = it }
        return if (now - since >= FROZEN_MS) report(Problem.FROZEN) else null
    }

    private fun report(problem: Problem): Problem {
        reported = true
        isProgressing = false
        return problem
    }
}
