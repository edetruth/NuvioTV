package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.SportsLiveTab

/**
 * How Live now and Upcoming lay out their games: the card list, the stadium Scoreboard (#23), or the
 * Matchday timeline (#24). The switch lists whatever [offeredFor] returns.
 */
enum class SportsLiveLayout {
    CARDS,
    SCOREBOARD,
    TIMELINE;

    companion object {
        /** The layouts [tab] can switch between; empty (no switch) for Channels, Replays and Results. */
        fun offeredFor(tab: SportsLiveTab): List<SportsLiveLayout> =
            if (tab == SportsLiveTab.LIVE_NOW || tab == SportsLiveTab.UPCOMING) entries else emptyList()

        /** [tab]'s layout from the stored choices: Cards unless the tab offers the stored one. */
        fun of(choices: Map<SportsLiveTab, SportsLiveLayout>, tab: SportsLiveTab): SportsLiveLayout =
            choices[tab]?.takeIf { it in offeredFor(tab) } ?: CARDS

        /** "LIVE_NOW=SCOREBOARD,UPCOMING=CARDS"; unknown tabs or layouts are skipped. */
        fun encode(choices: Map<SportsLiveTab, SportsLiveLayout>): String =
            choices.entries.sortedBy { it.key.ordinal }.joinToString(",") { "${it.key.name}=${it.value.name}" }

        fun decode(stored: String?): Map<SportsLiveTab, SportsLiveLayout> =
            stored.orEmpty().split(',').mapNotNull { entry ->
                val tab = SportsLiveTab.entries.firstOrNull { it.name == entry.substringBefore('=').trim() }
                val layout = entries.firstOrNull { it.name == entry.substringAfter('=', "").trim() }
                if (tab != null && layout != null) tab to layout else null
            }.toMap()
    }
}
