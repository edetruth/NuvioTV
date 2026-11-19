package com.nuvio.tv.ui.screens.sportslive

/** A playback start's request (#175): the item it is for ([key]) and the [generation] it was made in. */
data class SportsLiveRequestToken(val key: String, val generation: Long)

/**
 * Which start may still begin playback (#175). Every preview, stage, Watch or zap start takes a token
 * ([begin]); a focus or selection change, a newer start or a stop ([invalidate]) makes earlier tokens
 * stale. A lookup that lands with a stale token never starts playback: since #158 a slow lookup may finish
 * many seconds later, after focus moved on. Its links may still go to the caches, Sources and the ladder,
 * which the resolver records on its own, whoever asked. Main thread only.
 */
class SportsLiveRequestGate {
    private var generation = 0L
    private var currentKey: String? = null

    /** A new start for [key]: every earlier token is stale from now on. */
    fun begin(key: String): SportsLiveRequestToken {
        generation++
        currentKey = key
        return SportsLiveRequestToken(key, generation)
    }

    /** Focus or selection moved, or the start was stopped: no token so far may start playback. */
    fun invalidate() {
        generation++
        currentKey = null
    }

    /** True while [token] is the latest start and was not invalidated: its result may play. */
    fun mayPlay(token: SportsLiveRequestToken): Boolean = sportsLiveMayPlay(token, generation, currentKey)
}

/** A result for [token] may start playback only while it is the [generation]'s start for [currentKey]. */
internal fun sportsLiveMayPlay(token: SportsLiveRequestToken, generation: Long, currentKey: String?): Boolean =
    token.generation == generation && token.key == currentKey
