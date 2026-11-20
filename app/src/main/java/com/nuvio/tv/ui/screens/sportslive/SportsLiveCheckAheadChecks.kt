package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.domain.model.SportsLiveItem
import javax.inject.Inject
import javax.inject.Singleton

/** What the in-player panel's check-ahead (#147) checks with: the resolver's lookup and probe, and its skip rule. */
@Singleton
class SportsLiveCheckAheadChecks @Inject constructor(private val resolver: SportsLiveZapResolver) {
    suspend fun check(item: SportsLiveItem) = resolver.checkAhead(item)

    fun skips(item: SportsLiveItem): Boolean = resolver.checkAheadSkips(item)
}
