package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.SportsLiveMoreLive
import com.nuvio.tv.domain.model.SportsLiveItem

/**
 * One "All sports" entry in Live now's "My sports ▾" menu (live.26 shorter paths, #223, amendment A5): [group] null is
 * All sports itself (More live's view from its top), else one More live group ([SportsLiveMoreLive.Group.key]) with its
 * [count] of live games. Counts are game counts, never scores.
 */
internal data class SportsLiveAllSportsEntry(val group: String?, val count: Int?)

/**
 * The menu's All sports entries from [groups] (Live now's [SportsLiveUiState.moreLive], the very groups the "More live:
 * 7 Cricket · 5 Rugby" row counts, so the two always agree): All sports first, then each group with games, largest
 * first, Other last, as the groups come. A sport with 0 games is not offered; with nothing outside My sports, no entry.
 */
internal fun sportsLiveAllSportsEntries(groups: List<SportsLiveMoreLive.Group>): List<SportsLiveAllSportsEntry> {
    val shown = groups.filter { it.items.isNotEmpty() }
    if (shown.isEmpty()) return emptyList()
    return listOf(SportsLiveAllSportsEntry(group = null, count = null)) +
        shown.map { SportsLiveAllSportsEntry(group = it.key, count = it.items.size) }
}

/**
 * Where focus lands as More live's view opens from the menu: the first game of [group] in [items] (the view's rows,
 * [groupOf] naming each one's group by card key), else the first row (All sports, or a group gone meanwhile).
 */
internal fun sportsLiveMoreLiveFocusTarget(items: List<SportsLiveItem>, groupOf: Map<String, String>, group: String?): SportsLiveItem? =
    group?.let { key -> items.firstOrNull { groupOf[it.streamKey] == key } } ?: items.firstOrNull()
