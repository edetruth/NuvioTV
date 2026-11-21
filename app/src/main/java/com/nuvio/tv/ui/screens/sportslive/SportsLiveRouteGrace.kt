package com.nuvio.tv.ui.screens.sportslive

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest

/**
 * #211: when to send the user Home because Sports & Live is not available. [state] is (availability, on a Sports & Live
 * route); null availability is "not known yet" and never redirects. A redirect comes only once the section has stayed
 * unavailable on its route for [graceMs]: a short drop (addon manifests reloading, a fresh install still installing
 * addons) that recovers within it, or leaving the route, cancels it, so a Back from a game never lands on Home for that.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun sportsLiveUnavailableRedirects(
    state: Flow<Pair<Boolean?, Boolean>>,
    graceMs: Long = SPORTS_LIVE_UNAVAILABLE_GRACE_MS
): Flow<Unit> =
    state.distinctUntilChanged().transformLatest { (availability, onSportsLiveRoute) ->
        if (availability == false && onSportsLiveRoute) {
            delay(graceMs)
            emit(Unit)
        }
    }

/** How long Sports & Live must stay unavailable before its route sends the user Home. */
internal const val SPORTS_LIVE_UNAVAILABLE_GRACE_MS = 5_000L
