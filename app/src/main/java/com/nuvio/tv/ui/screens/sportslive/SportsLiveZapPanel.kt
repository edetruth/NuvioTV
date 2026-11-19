package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.displayName
import com.nuvio.tv.core.sportslive.EndedGame
import com.nuvio.tv.core.sportslive.GameStatus
import com.nuvio.tv.core.sportslive.SportsLiveEndedGames
import com.nuvio.tv.core.sportslive.SportsLiveItemBucketer
import com.nuvio.tv.core.sportslive.SportsLiveSportKind
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.FollowTarget
import com.nuvio.tv.core.sportslive.scores.GameArt
import com.nuvio.tv.core.sportslive.scores.GameSituation
import com.nuvio.tv.core.sportslive.scores.GameSituations
import com.nuvio.tv.core.sportslive.scores.ScoreChip
import com.nuvio.tv.core.sportslive.scores.ScoreChips
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
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
 * With My sports scoping the panel (#146), the chips and sports are [scoped]'s, and **All sports** follows
 * All while My sports hides any of [items] (or is picked).
 */
internal fun panelChips(items: List<SportsLiveItem>, selected: String?, scoped: List<SportsLiveItem> = items): List<String?> {
    val allSports = SportsLiveUiState.ALL_SPORTS_FILTER
    // One chip per sport; a catch-all ("Sports", "⚡ Live Events") is only All again, so it gets none (V2 P3).
    val sports = scoped.mapNotNull({ SportsLiveSportKind.filterKeyOf(it) }).toMutableSet()
    if (selected != null && selected != SportsLiveUiState.MY_TEAMS_FILTER && selected != allSports) sports += selected
    val myTeams = scoped.any { it.followed } || selected == SportsLiveUiState.MY_TEAMS_FILTER
    val offersAllSports = scoped.size < items.size || selected == allSports
    return listOfNotNull(SportsLiveUiState.MY_TEAMS_FILTER.takeIf { myTeams }) + null +
        listOfNotNull(allSports.takeIf { offersAllSports }) + sports.sortedWith(String.CASE_INSENSITIVE_ORDER)
}

/**
 * The live games panel's chip counts (#79): All, "★ My teams" and each sport over [scoped] (My sports' games,
 * #146), as the section counts them, and All sports over [items] while My sports hides some.
 */
internal fun panelChipCounts(items: List<SportsLiveItem>, scoped: List<SportsLiveItem> = items): Map<String?, Int> {
    val counts = sportsLiveFilterCounts(scoped, { SportsLiveSportKind.filterKeyOf(it) })
    return if (scoped.size < items.size) counts + (SportsLiveUiState.ALL_SPORTS_FILTER to items.size) else counts
}

/** The panel games under [chip] (null and All sports are All, otherwise a sport or "★ My teams"). */
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
        name = item.displayName,
        detail = listOfNotNull(art?.league ?: SportsLiveTeams.sportLabelOf(item), item.addonName).joinToString(" · "),
        position = position,
        total = total,
        switching = switching,
        art = art,
        // The banner never reveals a score: Hide until focused shows it hidden.
        chip = ScoreChips.of(item, event, mode, revealed = false),
        next = step(forward = true, endedKeys = endedKeys)?.current?.takeIf { it.streamKey != item.streamKey }?.displayName
    )
}

/** How far ahead a game counts as starting soon in the live games panel. */
internal const val SOON_WINDOW_MS = GameStatus.SOON_WINDOW_MS

/** The live games panel's groups, in the order they are listed. */
enum class ZapPanelGroup { LIVE_NOW, STARTING_SOON, OTHER, JUST_ENDED }

/**
 * A row of the live games panel: its [group], the S4 score [chip] (null when the game is unmatched,
 * upcoming or Scores is Off), for a game starting soon its drawn card's [art] for the kickoff, and for
 * any row that names two teams their [teams] (logos when both have one, #130, and names, #66: never a score, so they
 * show at once, from the logos learned before when the game is not matched yet).
 */
data class ZapPanelRow(
    val item: SportsLiveItem,
    val group: ZapPanelGroup,
    val chip: ScoreChip?,
    val art: GameArt?,
    val teams: GameArt? = null,
    /** Where the game stands ([GameStatus.of]): [group] is read from it. */
    val status: GameStatus = GameStatus.Channel,
    /**
     * The row's V2 group (#142, the Scoreboard's [scoreboardGroupOf]): the panel heads and orders its rows by
     * it, and CH+/CH− walk them in that order. [groupPanelRows] sets it from [status].
     */
    val section: ScoreboardGroup = sectionOf(group)
) {
    /** CH+/CH− stop on it (F11): every row but a final or just ended game, which the panel still lists. */
    val zappable: Boolean get() = section != ScoreboardGroup.JUST_ENDED
}

/** A row built with no status (tests, the fallback row): its V2 group from its panel [group]. */
private fun sectionOf(group: ZapPanelGroup): ScoreboardGroup = when (group) {
    ZapPanelGroup.LIVE_NOW -> ScoreboardGroup.LIVE
    ZapPanelGroup.STARTING_SOON -> ScoreboardGroup.STARTING_SOON
    ZapPanelGroup.OTHER -> ScoreboardGroup.LATER
    ZapPanelGroup.JUST_ENDED -> ScoreboardGroup.JUST_ENDED
}

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
    known: TeamLogoBook = TeamLogoBook.shared,
    /** The list says its games are live (Live now, the panel); Upcoming's are not: ESPN alone makes one live there. */
    listedLive: Boolean = true
): List<ZapPanelRow> {
    class Entry(val row: ZapPanelRow, val startMs: Long?, val index: Int)
    val entries = items.mapIndexed { index, item ->
        val endedGame = ended[item.streamKey]
        // A Just ended game keeps the final it was moved on, whatever is tracked now.
        val snapshot = (scores[item.streamKey] ?: endedGame?.event).takeIf { mode != null && mode != ScoresMode.OFF }
        val event = ScoreChips.matchedEvent(item, snapshot)
        // One status source (#102): a final is grouped as one in every mode, as the cards label it (C3);
        // Hide until focused keeps only its score hidden (the chip).
        val status = GameStatus.of(item, snapshot, endedGame, nowMs, listedLive)
        val group = panelGroupOf(status)
        val start = when (status) {
            is GameStatus.StartingSoon -> status.startMs
            is GameStatus.Live -> status.event?.takeIf { it.timeValid }?.startMs ?: item.startMs?.takeIf { item.startHasTimeOfDay }
            else -> null
        }
        val chip = ScoreChips.of(item, event, mode, item.streamKey in revealed)
        val teams = PanelArt.of(item, event, known)
        val art = teams.takeIf { group == ZapPanelGroup.STARTING_SOON }
        Entry(ZapPanelRow(item, group, chip, art, teams, status, scoreboardGroupOf(status, item)), start, index)
    }
    val order = compareBy<Entry> { it.row.group.ordinal }
        .thenByDescending { it.row.item.followed }
        .thenBy { if (it.row.group >= ZapPanelGroup.OTHER) 0L else it.startMs ?: Long.MAX_VALUE }
        .thenBy { it.index }
    return entries.sortedWith(order).map { it.row }
}

/** The panel group of a game's [status]: live, starting soon, just ended, or the rest. */
internal fun panelGroupOf(status: GameStatus): ZapPanelGroup = when (status) {
    is GameStatus.Live -> ZapPanelGroup.LIVE_NOW
    is GameStatus.StartingSoon -> ZapPanelGroup.STARTING_SOON
    is GameStatus.JustEnded -> ZapPanelGroup.JUST_ENDED
    is GameStatus.Upcoming, is GameStatus.Final, is GameStatus.Postponed, GameStatus.Channel -> ZapPanelGroup.OTHER
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
    return fresh.sortedWith(compareBy<ZapPanelRow> { it.section.ordinal }.thenBy { rank[it.item.streamKey] ?: Int.MAX_VALUE })
}

/**
 * The panel's one ordered list (#142), the list the panel shows and CH+/CH− walk: [grouped] ([groupPanelRows]
 * order) listed by V2 group ([ZapPanelRow.section]: live, starting soon, channels, just ended), followed teams'
 * games first inside each group, then as grouped. While a row has focus, [previous] (the order last shown)
 * keeps each group as it was ([stabilizePanelRows]); null takes the fresh order.
 */
internal fun panelLineup(grouped: List<ZapPanelRow>, previous: List<String>? = null): List<ZapPanelRow> {
    val fresh = grouped.sortedWith(compareBy<ZapPanelRow> { it.section.ordinal }.thenByDescending { it.item.followed })
    return stabilizePanelRows(fresh, previous)
}

/**
 * Whether the panel may take the fresh order now (I3): when no game row has focus, or on a new minute
 * since the order last shown was taken ([lastMinute], null when none was). Otherwise the rows keep
 * their order under the user's focus ([stabilizePanelRows]).
 */
internal fun panelMayReorder(focusedKey: String?, nowMs: Long, lastMinute: Long?): Boolean =
    focusedKey == null || lastMinute == null || nowMs / PANEL_REORDER_MS != lastMinute

internal const val PANEL_REORDER_MS = 60_000L

/**
 * A line of the panel's list under its pinned **Now playing** row (#140): a group's [Header] (its [count] of
 * games in that run), the thin [FromTheTop] divider where the lineup wraps, or a [Game] row.
 */
internal sealed interface PanelGuideEntry {
    val key: String

    data class Header(val section: ScoreboardGroup, val count: Int, val segment: Int) : PanelGuideEntry {
        override val key: String get() = "group_${segment}_$section"
    }

    data object FromTheTop : PanelGuideEntry {
        override val key: String get() = "from_the_top"
    }

    data class Game(val row: ZapPanelRow) : PanelGuideEntry {
        override val key: String get() = row.item.streamKey
    }
}

/**
 * The in-player panel as a cable guide (#140): [nowPlaying] (the game playing, pinned above the list; null
 * when the lineup leaves it out) and the list [entries] under it.
 */
internal data class PanelGuide(val nowPlaying: ZapPanelRow?, val entries: List<PanelGuideEntry>) {
    /** The list's game rows, top to bottom. */
    val rows: List<ZapPanelRow> get() = entries.mapNotNull { (it as? PanelGuideEntry.Game)?.row }

    /** [key]'s index in [entries] (the LazyColumn's item index); -1 when not listed. */
    fun indexOf(key: String?): Int = entries.indexOfFirst { it is PanelGuideEntry.Game && it.row.item.streamKey == key }
}

/**
 * The panel's [lineup] (#142, the one list CH± walk) laid out under Now playing (#140), the way a cable guide
 * starts at your channel: the game after [currentKey] first (what CH− / ▼ plays), on to the lineup's end, then
 * a "From the top" divider and the lineup's first game on to the one just before the current one (what CH+ /
 * ▲ plays). The current game is pinned, never repeated. Games CH± pass over (finals, [ZapPanelRow.zappable])
 * stay listed, in their own group after the wrapped ones, so the list's game rows down to them are exactly
 * the zap order. Each group's header starts its run within a segment. With the current game not in [lineup],
 * the lineup in its own order (CH− plays its first game, CH+ its last).
 */
internal fun panelGuide(lineup: List<ZapPanelRow>, currentKey: String?): PanelGuide {
    val here = lineup.indexOfFirst { it.item.streamKey == currentKey }
    val after = if (here < 0) lineup else lineup.subList(here + 1, lineup.size)
    val before = if (here < 0) emptyList() else lineup.subList(0, here)
    val ahead = after.filter { it.zappable }
    val wrapped = before.filter { it.zappable }
    val passedOver = lineup.filterIndexed { index, row -> index != here && !row.zappable }
    val entries = buildList {
        addSegment(ahead, segment = 0)
        if (ahead.isNotEmpty() && wrapped.isNotEmpty()) add(PanelGuideEntry.FromTheTop)
        addSegment(wrapped, segment = 1)
        addSegment(passedOver, segment = 2)
    }
    return PanelGuide(lineup.getOrNull(here), entries)
}

/** [rows] with a header above each run of one group. */
private fun MutableList<PanelGuideEntry>.addSegment(rows: List<ZapPanelRow>, segment: Int) {
    var start = 0
    while (start < rows.size) {
        val section = rows[start].section
        var end = start
        while (end < rows.size && rows[end].section == section) end++
        add(PanelGuideEntry.Header(section, end - start, segment))
        for (i in start until end) add(PanelGuideEntry.Game(rows[i]))
        start = end
    }
}

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
        val board = scoreboardRowOf(row, snapshot, endedGame, nowMs, followsSide, row.teams)
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
    val boards: Map<String, ZapPanelBoard> = emptyMap(),
    /** Rows whose OK opens the box score ([PanelRowOk.BOX_SCORE]), by stream key: they hint "Box score ›" under focus. */
    val boxScores: Set<String> = emptySet(),
    /**
     * The game playing as a row (#140), for the pinned Now playing row: its row in [rows], else built on its
     * own (the chip or My sports leaves it out of the list). Null when it is not known at all.
     */
    val nowPlayingRow: ZapPanelRow? = rows.firstOrNull { it.item.streamKey == currentKey }
) {
    val items: List<SportsLiveItem> = rows.map { it.item }

    /** The list under Now playing, wrapped round from the game after the current one (#140). */
    internal val guide: PanelGuide by lazy { panelGuide(rows, currentKey) }
}

/**
 * Whether the panel's top-right corner shows (V2 P3): only while the focused game's preview video loads or
 * plays ([previewPlaying]); never a logos-and-time card in its place.
 */
internal fun panelPreviewCornerShown(previewPlaying: Boolean): Boolean = previewPlaying

/** What OK on a panel row does (#140): play its stream, or open its Game page on the box score. */
internal enum class PanelRowOk { PLAY, BOX_SCORE }

/**
 * OK on [row] (#140): a Just ended game with a strict ESPN match ([game], null without one) opens its Game page,
 * the box score, as Game stats in its menu does; every other row (a Just ended game ESPN does not know too) plays.
 */
internal fun panelRowOk(row: ZapPanelRow, game: ScoreSnapshot?): PanelRowOk =
    if (row.section == ScoreboardGroup.JUST_ENDED && game != null) PanelRowOk.BOX_SCORE else PanelRowOk.PLAY

/**
 * What Hold OK on a panel row lists (#140), in order: **Watch both** first (the game playing beside the one
 * held, where dual screen pairs them: [watchBoth]), then **Choose stream** ([chooseStream]), **Game stats** (a
 * strict ESPN match only: [strictMatch]) and **Follow ‹team›** once per side ([sides]). The More… menu's
 * entries ([sportsLiveMoreEntries]) without Details or Remind me, which the player has no room for.
 */
internal fun panelMenuEntries(watchBoth: Boolean, chooseStream: Boolean, strictMatch: Boolean, sides: Int): List<SportsLiveMoreEntry> =
    sportsLiveMoreEntries(watchBoth, chooseStream, strictMatch, sides, remindable = false)
        .filter { it != SportsLiveMoreEntry.DETAILS && it != SportsLiveMoreEntry.REMIND }

/**
 * The Hold OK menu of a panel row (#140): its [entries] ([panelMenuEntries]), each side with whether a
 * followed team plays it, and the strict ESPN [game] Game stats opens (null: not offered).
 */
internal data class SportsLivePanelMenu(
    val item: SportsLiveItem,
    val entries: List<SportsLiveMoreEntry>,
    val sides: List<Pair<String, Boolean>>,
    val game: ScoreSnapshot?,
    /** OK on the row opens its box score ([PanelRowOk.BOX_SCORE]): **Watch postgame** leads the menu, so its stream stays one press away. */
    val postgame: Boolean = false,
    /** The real teams Follow offers (#134), one per entry of [sides]. */
    val targets: List<FollowTarget> = emptyList()
) {
    val watchBoth: Boolean get() = SportsLiveMoreEntry.WATCH_WITH in entries
    val chooseStream: Boolean get() = SportsLiveMoreEntry.CHOOSE_STREAM in entries
}
