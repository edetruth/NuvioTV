package com.nuvio.tv.core.sportslive.alerts

import com.nuvio.tv.core.sportslive.SportsLiveSharedBuckets
import com.nuvio.tv.domain.model.ClaimedCatalog
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.repository.SportsLiveLoad

/**
 * [SportsLiveAlerts.candidates], kept while it would come out the same (#207): the same load (by identity), catalogs
 * and followed teams, and the same team directory, identities and ESPN starts ([SportsLiveSharedBuckets.currentStamp]).
 * The my-games keeper asks every 30 s, and each answer is two whole-tab sortings of the load (seconds on a slow box).
 */
internal class SportsLiveCandidatesMemo(
    private val stamp: () -> Long = { SportsLiveSharedBuckets.currentStamp() },
    private val build: (SportsLiveLoad?, List<ClaimedCatalog>, List<FollowedTeam>) -> List<SportsLiveItem> =
        { load, claimed, followed -> SportsLiveAlerts.candidates(load, claimed, followed) }
) {
    private class Key(val load: SportsLiveLoad?, val claimed: List<ClaimedCatalog>, val followed: List<FollowedTeam>, val stamp: Long)

    private var key: Key? = null
    private var value: List<SportsLiveItem> = emptyList()

    @Synchronized
    fun of(load: SportsLiveLoad?, claimed: List<ClaimedCatalog>, followed: List<FollowedTeam>): List<SportsLiveItem> {
        val now = stamp()
        val last = key
        if (last != null && last.load === load && last.stamp == now && last.claimed == claimed && last.followed == followed) return value
        value = build(load, claimed, followed)
        key = Key(load, claimed, followed, now)
        return value
    }
}
