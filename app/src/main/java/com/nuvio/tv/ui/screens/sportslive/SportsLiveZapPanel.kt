package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.EndedGame
import com.nuvio.tv.core.sportslive.SportsLiveEndedGames
import com.nuvio.tv.core.sportslive.SportsLiveItemBucketer
import com.nuvio.tv.core.sportslive.scores.GameArt
import com.nuvio.tv.core.sportslive.scores.ScoreChip
import com.nuvio.tv.core.sportslive.scores.ScoreChips
import com.nuvio.tv.core.sportslive.scores.ScoreMatcher
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.core.sportslive.scores.TeamLogoBook
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveTab

/**
 * The player's live games panel: the section's Live now list (claimed catalogs only, ended games left
 * out, [followed] teams' games first, merged games' primaries picked by [addonOrder]), narrowed to the session's filter (a sport or "★ My teams") when
 * the session was started from Live now.
 */
internal fun liveGamesPanelItems(
    items: List<SportsLiveItem>,
    claimedKeys: Set<String>,
    endedKeys: Set<String>,
    followed: List<FollowedTeam>,
    session: SportsLiveZapSession,
    nowMs: Long,
    addonOrder: List<String> = emptyList()
): List<SportsLiveItem> {
    val live = SportsLiveItemBucketer.bucketTab(items.filter { it.catalogKey in claimedKeys }, SportsLiveTab.LIVE_NOW, nowMs, addonOrder)
        .let { SportsLiveItemBucketer.pinFollowed(it, followed) }
        .filterNot { it.hasEnded(endedKeys) }
    val filter = session.filter?.takeIf { session.tab == SportsLiveTab.LIVE_NOW } ?: return live
    if (filter == SportsLiveUiState.MY_TEAMS_FILTER) return live.filter { it.followed }
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

/** How far ahead a game counts as starting soon in the live games panel. */
internal const val SOON_WINDOW_MS = 3 * 60 * 60 * 1000L

/** The live games panel's groups, in the order they are listed. */
enum class ZapPanelGroup { LIVE_NOW, STARTING_SOON, OTHER, JUST_ENDED }

/**
 * A row of the live games panel: its [group], the S4 score [chip] (null when the game is unmatched,
 * upcoming or Scores is Off), for a game starting soon its drawn card's [art] for the kickoff, and for
 * any row that names two teams their [teams] (logos and abbreviations, #66: never a score, so they
 * show at once, from the logos learned before when the game is not matched yet).
 */
data class ZapPanelRow(
    val item: SportsLiveItem,
    val group: ZapPanelGroup,
    val chip: ScoreChip?,
    val art: GameArt?,
    val teams: GameArt? = null
)

/**
 * The panel's [items] grouped by game state (#17): **Live now** first, the game furthest in (earliest
 * start) on top and games with no start after them, then **Starting soon** (within [SOON_WINDOW_MS]),
 * soonest first, then the rest (channels, later games, finished or postponed ones; under Hide until
 * focused a finished game keeps its place by start time). Followed teams' games stay first inside each group. A game's state comes
 * from its matched score event only when the card's sides sit on it one way round (the chip's rule);
 * otherwise from its start time. [scores] is ignored while Scores is Off. Games found over (#60, [ended]
 * by card key: the Just ended verdicts of [SportsLiveEndedGames.split]) form a last **Just ended** group.
 */
internal fun groupPanelRows(
    items: List<SportsLiveItem>,
    scores: Map<String, ScoreSnapshot>,
    mode: ScoresMode?,
    revealed: Set<String>,
    nowMs: Long,
    ended: Map<String, EndedGame> = emptyMap(),
    known: TeamLogoBook = TeamLogoBook.shared
): List<ZapPanelRow> {
    class Entry(val row: ZapPanelRow, val startMs: Long?, val index: Int)
    val entries = items.mapIndexed { index, item ->
        val endedGame = ended[item.streamKey]
        // A Just ended game keeps the final it was moved on, whatever is tracked now.
        val snapshot = (scores[item.streamKey] ?: endedGame?.event).takeIf { mode != null && mode != ScoresMode.OFF }
        val sides = ScoreChips.orderedSides(item)
        val event = snapshot?.takeIf { sides != null && ScoreMatcher.firstIsHome(sides.first, sides.second, it) != null }
        val eventStart = event?.takeIf { it.timeValid }?.startMs
        val start = eventStart ?: item.startMs?.takeIf { item.startHasTimeOfDay }
        val soon = start != null && start <= nowMs + SOON_WINDOW_MS
        // Under Hide until focused a finished game is grouped by its start like an unmatched one, so its
        // place in the list never gives the result away.
        val state = event?.state?.takeUnless { it == ScoreState.POST && mode == ScoresMode.HIDE_UNTIL_FOCUSED }
        val group = if (endedGame != null) ZapPanelGroup.JUST_ENDED else when (state) {
            ScoreState.IN -> ZapPanelGroup.LIVE_NOW
            ScoreState.PRE -> if (soon) ZapPanelGroup.STARTING_SOON else ZapPanelGroup.OTHER
            ScoreState.POST, ScoreState.OFF -> ZapPanelGroup.OTHER
            // Unmatched: every panel game is one its addon marks live; a later start says it has not begun.
            ScoreState.UNKNOWN, null -> when {
                item.isChannel -> ZapPanelGroup.OTHER
                start != null && start > nowMs -> if (soon) ZapPanelGroup.STARTING_SOON else ZapPanelGroup.OTHER
                else -> ZapPanelGroup.LIVE_NOW
            }
        }
        val chip = ScoreChips.of(item, event, mode, item.streamKey in revealed)
        val teams = GameArt.of(item, event, known)
        val art = teams.takeIf { group == ZapPanelGroup.STARTING_SOON }
        Entry(ZapPanelRow(item, group, chip, art, teams), start, index)
    }
    val order = compareBy<Entry> { it.row.group.ordinal }
        .thenByDescending { it.row.item.followed }
        .thenBy { if (it.row.group >= ZapPanelGroup.OTHER) 0L else it.startMs ?: Long.MAX_VALUE }
        .thenBy { it.index }
    return entries.sortedWith(order).map { it.row }
}

/**
 * The live games panel's content: its [rows], grouped and ordered by [groupPanelRows]; [currentKey] is
 * the playing game's stream key, highlighted. [items] are the rows' games in that order (a pick's zap
 * session follows the order shown).
 */
data class SportsLiveZapPanelState(
    val rows: List<ZapPanelRow>,
    val currentKey: String
) {
    val items: List<SportsLiveItem> = rows.map { it.item }
}
