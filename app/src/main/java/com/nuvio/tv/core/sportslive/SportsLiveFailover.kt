package com.nuvio.tv.core.sportslive

/**
 * Policy for Sports & Live automatic source failover (#28): how many distinct sources the player may
 * auto-try per playback session before it stops and hands the user the manual recovery overlay. The
 * cap protects a metered/free-tier provider from being looped through every link automatically.
 */
object SportsLiveFailover {
    const val MAX_SOURCES = 3

    /** Whether another source may be auto-tried, given how many have already been tried this session. */
    fun canAutoTry(attempts: Int, cap: Int = MAX_SOURCES): Boolean = attempts < cap
}
