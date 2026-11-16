package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.SportsLiveItemBucketer
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveTab

/**
 * The player's live games panel: the section's Live now list (claimed catalogs only, ended games left
 * out), narrowed to the session's sport filter when the session was started from Live now.
 */
internal fun liveGamesPanelItems(
    items: List<SportsLiveItem>,
    claimedKeys: Set<String>,
    endedKeys: Set<String>,
    session: SportsLiveZapSession,
    nowMs: Long
): List<SportsLiveItem> {
    val live = SportsLiveItemBucketer.bucketTab(items.filter { it.catalogKey in claimedKeys }, SportsLiveTab.LIVE_NOW, nowMs)
        .filterNot { it.streamKey in endedKeys }
    val filter = session.filter?.takeIf { session.tab == SportsLiveTab.LIVE_NOW } ?: return live
    return live.filter { it.sport == filter }
}

/**
 * The session after [item] is picked in the live games panel ([panelItems]): moved to it when the
 * session lists it, otherwise a new Live now session over the panel list; null when neither has it.
 */
internal fun SportsLiveZapSession.pickFromPanel(
    item: SportsLiveItem,
    panelItems: List<SportsLiveItem>
): SportsLiveZapSession? =
    moveTo(item.streamKey) ?: SportsLiveZapSession.of(
        items = panelItems,
        itemKey = item.streamKey,
        tab = SportsLiveTab.LIVE_NOW,
        filter = filter.takeIf { tab == SportsLiveTab.LIVE_NOW },
        returnToHomeOnBack = returnToHomeOnBack
    )

/** What the zap banner shows; [switching] while the game's stream is being looked up. */
data class SportsLiveZapBanner(
    val name: String,
    /** "league · addon". */
    val detail: String,
    val position: Int,
    val total: Int,
    val switching: Boolean
)

internal fun SportsLiveZapSession.banner(switching: Boolean): SportsLiveZapBanner {
    val (position, total) = positionLabelArgs
    return SportsLiveZapBanner(
        name = current.meta.name,
        detail = "${current.sport ?: current.catalogName} · ${current.addonName}",
        position = position,
        total = total,
        switching = switching
    )
}

/** The live games panel's content; [currentKey] is the playing game's stream key, highlighted. */
data class SportsLiveZapPanelState(
    val items: List<SportsLiveItem>,
    val currentKey: String
)
