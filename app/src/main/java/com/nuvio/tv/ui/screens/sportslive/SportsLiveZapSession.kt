package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveTab

/**
 * The list a game was started from in Sports & Live, so the player can flip to the next or previous
 * game in that same list (same tab, same filter). The list is a snapshot taken at launch.
 */
data class SportsLiveZapSession(
    val items: List<SportsLiveItem>,
    val index: Int,
    val tab: SportsLiveTab,
    val filter: String?,
    val returnToHomeOnBack: Boolean
) {
    init {
        require(index in items.indices) { "index $index outside ${items.size} items" }
    }

    val current: SportsLiveItem get() = items[index]

    /** (1-based position, list size), for the "%1$d of %2$d" label. */
    val positionLabelArgs: Pair<Int, Int> get() = (index + 1) to items.size

    fun next(): SportsLiveZapSession = copy(index = (index + 1) % items.size)

    fun previous(): SportsLiveZapSession = copy(index = (index - 1 + items.size) % items.size)

    /** The session moved to the item whose [SportsLiveItem.streamKey] is [itemKey], or null if absent. */
    fun moveTo(itemKey: String): SportsLiveZapSession? =
        items.indexOfFirst { it.streamKey == itemKey }
            .takeIf { it >= 0 }
            ?.let { copy(index = it) }
}

/**
 * The zap session for the game playing now, process-wide like [com.nuvio.tv.ui.screens.player.SourceFallbackHandoff].
 * A player only zaps when its content id is the session's current item id, so a player opened for
 * anything else keeps upstream behavior. Lost on process death, which leaves upstream behavior too.
 */
internal object SportsLiveZapSessions {
    @Volatile
    private var session: SportsLiveZapSession? = null

    fun start(session: SportsLiveZapSession) {
        this.session = session
    }

    fun current(): SportsLiveZapSession? = session

    fun activeFor(contentId: String?): SportsLiveZapSession? =
        session?.takeIf { contentId != null && it.current.id == contentId }

    fun update(session: SportsLiveZapSession) {
        this.session = session
    }

    fun clear() {
        session = null
    }
}
