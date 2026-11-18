package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.EndedGame
import com.nuvio.tv.core.sportslive.SportsLiveEndedGames
import com.nuvio.tv.core.sportslive.SportsLiveItemBucketer
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.GameArt
import com.nuvio.tv.core.sportslive.scores.GameSituation
import com.nuvio.tv.core.sportslive.scores.GameSituations
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
 * out, [followed] teams' games first, merged games' primaries picked by [addonOrder]). Every game,
 * whatever filter the game playing was started under (#68); the panel's chips narrow it ([filterPanelItems]).
 */
internal fun liveGamesPanelItems(
    items: List<SportsLiveItem>,
    claimedKeys: Set<String>,
    endedKeys: Set<String>,
    followed: List<FollowedTeam>,
    nowMs: Long,
    addonOrder: List<String> = emptyList()
): List<SportsLiveItem> =
    SportsLiveItemBucketer.bucketTab(items.filter { it.catalogKey in claimedKeys }, SportsLiveTab.LIVE_NOW, nowMs, addonOrder)
        .let { SportsLiveItemBucketer.pinFollowed(it, followed) }
        .filterNot { it.hasEnded(endedKeys) }

/**
 * The live games panel's chips over [items] (#68): "★ My teams" when a followed team plays, All (null),
 * then each sport, A to Z. The [selected] chip stays listed when its games are gone, so it still shows.
 */
internal fun panelChips(items: List<SportsLiveItem>, selected: String?): List<String?> {
    val sports = items.mapNotNull { it.sport }.toMutableSet()
    if (selected != null && selected != SportsLiveUiState.MY_TEAMS_FILTER) sports += selected
    val myTeams = items.any { it.followed } || selected == SportsLiveUiState.MY_TEAMS_FILTER
    return listOfNotNull(SportsLiveUiState.MY_TEAMS_FILTER.takeIf { myTeams }) + null +
        sports.sortedWith(String.CASE_INSENSITIVE_ORDER)
}

/** The live games panel's chip counts over [items] (#79): All, "★ My teams" and each sport, as the section counts them. */
internal fun panelChipCounts(items: List<SportsLiveItem>): Map<String?, Int> = sportsLiveFilterCounts(items, SportsLiveItem::sport)

/** The panel games under [chip] (null is All, otherwise a sport or "★ My teams"). */
internal fun filterPanelItems(items: List<SportsLiveItem>, chip: String?): List<SportsLiveItem> =
    if (chip == null) items else items.filter { it.matchesZapFilter(chip, SportsLiveTab.LIVE_NOW) }

/**
 * The session after [item] is picked in the live games panel ([panelItems], every game it has): moved
 * to it when the session lists it, otherwise a new Live now session over the panel list, keeping the
 * chip picked; null when neither has it.
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
    )?.copy(panelChip = panelChip)

/**
 * What the zap banner shows (#76); [switching] while the game's stream is being looked up. A game
 * matched to its teams carries [art] (logos, names) and the score [chip] under the Scores setting (never
 * revealed by the banner: Hide until focused reads "Score hidden"; Off, none). [next] is the game ▼ / CH−
 * zaps to from here, null when there is none.
 */
data class SportsLiveZapBanner(
    /** The matchup or the channel, as the card names it. */
    val name: String,
    /** "league · source". */
    val detail: String,
    val position: Int,
    val total: Int,
    val switching: Boolean,
    val art: GameArt? = null,
    val chip: ScoreChip? = null,
    val next: String? = null
)

internal fun SportsLiveZapSession.banner(
    switching: Boolean,
    scores: Map<String, ScoreSnapshot> = emptyMap(),
    mode: ScoresMode? = null,
    endedKeys: Set<String> = emptySet(),
    known: TeamLogoBook = TeamLogoBook.shared
): SportsLiveZapBanner {
    val (position, total) = positionLabelArgs
    val item = current
    // A score only with the Scores setting known and on; never one of a game it does not strictly match.
    val event = scores[item.streamKey].takeIf { mode != null && mode != ScoresMode.OFF }
        ?.let { ScoreChips.matchedEvent(item, it) }
    val art = GameArt.of(item, event, known)
    return SportsLiveZapBanner(
        name = item.meta.name,
        detail = "${art?.league ?: item.sport ?: item.catalogName} · ${item.addonName}",
        position = position,
        total = total,
        switching = switching,
        art = art,
        // The banner never reveals a score: Hide until focused shows it hidden.
        chip = ScoreChips.of(item, event, mode, revealed = false),
        next = step(forward = true, endedKeys = endedKeys)?.current?.takeIf { it.streamKey != item.streamKey }?.meta?.name
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
 * focused too, as the cards label it final, its score still hidden). Followed teams' games stay first inside each group. A game's state comes
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
        // A final is grouped as one in every mode, as the cards label it (C3); Hide until focused keeps
        // only its score hidden (the chip).
        val state = event?.state
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
        val teams = PanelArt.of(item, event, known)
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
 * [fresh] rows ([groupPanelRows] order) with each group kept in the order of [previous] (stream keys,
 * as last shown): a row the user is on never moves within its group when a score or tick regroups the
 * list (I3). A row may still change group (a game starting, a final), as its header must say so; rows
 * new to the list follow the known ones of their group, in their fresh order. Null [previous]: as is.
 */
internal fun stabilizePanelRows(fresh: List<ZapPanelRow>, previous: List<String>?): List<ZapPanelRow> {
    if (previous == null) return fresh
    val rank = HashMap<String, Int>(previous.size * 2)
    previous.forEachIndexed { index, key -> rank.putIfAbsent(key, index) }
    // Stable: rows new to the list keep their fresh order after the known ones.
    return fresh.sortedWith(compareBy<ZapPanelRow> { it.group.ordinal }.thenBy { rank[it.item.streamKey] ?: Int.MAX_VALUE })
}

/**
 * Whether the panel may take the fresh order now (I3): when no game row has focus, or on a new minute
 * since the order last shown was taken ([lastMinute], null when none was). Otherwise the rows keep
 * their order under the user's focus ([stabilizePanelRows]).
 */
internal fun panelMayReorder(focusedKey: String?, nowMs: Long, lastMinute: Long?): Boolean =
    focusedKey == null || lastMinute == null || nowMs / PANEL_REORDER_MS != lastMinute

internal const val PANEL_REORDER_MS = 60_000L

/** The index of [key]'s row in the panel's list, which has a header above each group; -1 when not listed. */
internal fun panelListIndex(rows: List<ZapPanelRow>, key: String?): Int {
    var index = 0
    rows.forEachIndexed { i, row ->
        if (i == 0 || rows[i - 1].group != row.group) index++
        if (row.item.streamKey == key) return index
        index++
    }
    return -1
}

/** A focus stop of the live games panel outside its list. */
internal enum class ZapPanelStop { CHIPS }

/** Where Up goes from the panel's game row at [rowIndex]: from the first one, the chips (I3); null is the default. */
internal fun zapPanelUpFrom(rowIndex: Int): ZapPanelStop? = if (rowIndex == 0) ZapPanelStop.CHIPS else null

/** The panel rows' drawn teams, kept per game, event and logo-map revision: the rows are regrouped on every tick and score (#66). */
private object PanelArt {
    private val cache = HashMap<List<Any?>, GameArt?>()

    @Synchronized
    fun of(item: SportsLiveItem, event: ScoreSnapshot?, known: TeamLogoBook): GameArt? {
        val key = listOf(item, event, known, known.revision.value, known.active)
        cache[key]?.let { return it }
        if (key in cache) return null
        if (cache.size >= MAX_CACHED) cache.clear()
        return GameArt.of(item, event, known).also { cache[key] = it }
    }

    private const val MAX_CACHED = 512
}

/**
 * A panel row drawn as a Scoreboard row (#83): [board] is the Scoreboard's own model of the game, and
 * [situation] its SB1 situation with the [event] it reads from, only while its live score shows (never
 * before a key press in the panel under Hide until focused: [groupPanelRows]' reveal rule).
 */
data class ZapPanelBoard(
    val board: ScoreboardRow,
    val situation: GameSituation? = null,
    val event: ScoreSnapshot? = null
)

/**
 * The panel's [rows] (as shown) as Scoreboard rows, one each, in the same order: the same
 * [scoreboardRowOf] the Scoreboard uses (Live now), with the rows' drawn teams, stars on the sides a
 * [followed] team plays, and the situation line's inputs.
 */
internal fun panelBoards(
    rows: List<ZapPanelRow>,
    scores: Map<String, ScoreSnapshot>,
    mode: ScoresMode?,
    revealed: Set<String>,
    nowMs: Long,
    ended: Map<String, EndedGame> = emptyMap(),
    followed: List<FollowedTeam> = emptyList()
): List<ZapPanelBoard> {
    val scoresOn = mode != null && mode != ScoresMode.OFF
    val followsSide: (SportsLiveItem, String) -> Boolean = { item, side ->
        followed.any { SportsLiveTeams.followsSideOfAnySource(it, side, item) }
    }
    return rows.map { row ->
        val item = row.item
        val endedGame = ended[item.streamKey]
        val snapshot = (scores[item.streamKey] ?: endedGame?.event).takeIf { scoresOn }
        val board = scoreboardRowOf(row, SportsLiveTab.LIVE_NOW, snapshot, endedGame, nowMs, followsSide, row.teams)
        val situation = if (board.chip is ScoreChip.Live) {
            GameSituations.of(item, snapshot, mode, item.streamKey in revealed)
        } else {
            null
        }
        ZapPanelBoard(board, situation, snapshot.takeIf { situation != null })
    }
}

/**
 * The live games panel's content: its [rows], grouped and ordered by [groupPanelRows]; [currentKey] is
 * the playing game's stream key, highlighted. [items] are the rows' games in that order. [chips] are
 * the filter chips over [allItems] (every game listed, whatever the chip), [chip] the selected one
 * (null is All) that the rows are narrowed to.
 */
data class SportsLiveZapPanelState(
    val rows: List<ZapPanelRow>,
    val currentKey: String,
    val chips: List<String?> = listOf(null),
    val chip: String? = null,
    val allItems: List<SportsLiveItem> = rows.map { it.item },
    /** Each chip's game count over [allItems] (#79): All (null), "★ My teams" and each sport. */
    val counts: Map<String?, Int> = panelChipCounts(allItems),
    /** [rows] as Scoreboard rows (#83), by stream key: what each row draws. */
    val boards: Map<String, ZapPanelBoard> = emptyMap()
) {
    val items: List<SportsLiveItem> = rows.map { it.item }
}
