package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.displayName
import com.nuvio.tv.core.sportslive.EndedGame
import com.nuvio.tv.core.sportslive.GameStatus
import com.nuvio.tv.core.sportslive.SportsLiveEndedGames
import com.nuvio.tv.core.sportslive.SportsLiveItemBucketer
import com.nuvio.tv.core.sportslive.SportsLivePlayingGame
import com.nuvio.tv.core.sportslive.SportsLiveSharedBuckets
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
import com.nuvio.tv.core.sportslive.scores.TeamFollows
import com.nuvio.tv.core.sportslive.scores.TeamLogoBook
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.domain.repository.SportsLiveLoad
import kotlin.math.abs

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
    addonOrder: List<String> = emptyList(),
    /** The load [items] come from: its Live now is the one the section and Home share ([SportsLiveSharedBuckets], live.20). */
    load: SportsLiveLoad? = null,
    shared: SportsLiveSharedBuckets = SportsLiveSharedBuckets.shared
): List<SportsLiveItem> =
    (
        if (load != null) {
            shared.tab(load, claimedKeys, addonOrder, SportsLiveTab.LIVE_NOW, nowMs)
        } else {
            SportsLiveItemBucketer.bucketTab(items.filter { it.catalogKey in claimedKeys }, SportsLiveTab.LIVE_NOW, nowMs, addonOrder)
        }
        )
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
    // More live (user 2026-11-21) replaced the All sports chip: its view (the All sports value) is a chip only while shown.
    val offersAllSports = selected == allSports
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

/**
 * The games the open panel tracks for scores (live.20): those it lists under its chip, and the game [playing] when
 * the chip or My sports leaves it out, so its pinned Now playing row is scored like any other row (the Scores
 * setting and spoiler protection apply to it as to them).
 */
internal fun panelTrackedCards(shown: List<SportsLiveItem>, playing: SportsLiveItem?): List<SportsLiveItem> =
    if (playing == null || shown.any { it.streamKey == playing.streamKey }) shown else shown + playing

/** The panel games under [chip] (null and All sports are All, otherwise a sport or "★ My teams"). */
internal fun filterPanelItems(items: List<SportsLiveItem>, chip: String?): List<SportsLiveItem> =
    if (chip == null) items else items.filter { it.matchesZapFilter(chip, SportsLiveTab.LIVE_NOW) }

/**
 * The games the "Just watched" line (J7) may stand for: the live ones the panel lists under its filter ([shown]: My
 * sports' games, the chip's sport, ★ My teams' followed games), none [finished]. User 2026-11-21: a game watched earlier
 * must not sit under Now playing in every category (Nice vs PSG under Basketball).
 */
internal fun justWatchedPool(shown: List<SportsLiveItem>, finished: Set<String>): List<SportsLiveItem> =
    shown.filter { it.streamKey !in finished }

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
    )?.copy(panelChip = panelChip, dualReturn = dualReturn)

/**
 * What the zap banner shows (#76); [switching] while the game's stream is being looked up. A game
 * matched to its teams carries [art] (logos, names) and the score [chip] under the Scores setting (never
 * revealed by the banner: Hide until focused reads "Score hidden"; Off, none). [next] is the game ▼ / CH−
 * zaps to from here and [previous] the one ▲ / CH+ zaps to (BUG6/Q10: from the frozen zap order), null when there is none.
 */
data class SportsLiveZapBanner(
    /** The matchup or the channel, as the card names it. */
    val name: String,
    /** The compact league or sport tag ("NBA", "SOCCER", V2); null when none names a sport. Never the source. */
    val tag: String?,
    val position: Int,
    val total: Int,
    val switching: Boolean,
    val art: GameArt? = null,
    val chip: ScoreChip? = null,
    val next: String? = null,
    val previous: String? = null,
    /** A CH± zap's lookup is slow (#158): "Looking for streams…" with Skip (OK) shows. */
    val looking: Boolean = false
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
        tag = (art?.league ?: SportsLiveTeams.sportLabelOf(item)?.takeUnless(SportsLiveTeams::isGenericSport))?.let(::scoreboardTag),
        position = position,
        total = total,
        switching = switching,
        art = art,
        // The banner never reveals a score: Hide until focused shows it hidden.
        chip = ScoreChips.of(item, event, mode, revealed = false),
        next = step(forward = true, endedKeys = endedKeys)?.current?.takeIf { it.streamKey != item.streamKey }?.displayName,
        previous = step(forward = false, endedKeys = endedKeys)?.current?.takeIf { it.streamKey != item.streamKey }?.displayName
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
    val section: ScoreboardGroup = sectionOf(group),
    /**
     * The group the row is listed under (#164): its [section], except while the open panel's order is frozen
     * ([freezePanelLineup]), where a game that ended since the panel opened stays under its old header, dimmed.
     */
    val placed: ScoreboardGroup = section,
    /** Listed since the panel opened (#164): appended to the end of its group, with a "New" tag. */
    val isNew: Boolean = false,
    /** Gone from the list since the panel opened (#164): kept in place, dimmed, until the panel closes. */
    val gone: Boolean = false,
    /** Its status and teams were read from a strictly matched ESPN event (live.21: the guide keeps ESPN's reading). */
    val matched: Boolean = false
) {
    /** CH+/CH− stop on it (F11): every row but a final, a just ended game or one gone, which the panel still lists. */
    val zappable: Boolean get() = section != ScoreboardGroup.JUST_ENDED && !gone

    /**
     * A final or a just ended game (#213, #216): the one rule the side panel and the guide share. It is never listed under
     * Live or another header (the panel moves it to Just ended as it ends, [freezePanelLineup]); the guide lists none.
     */
    val finished: Boolean get() = section == ScoreboardGroup.JUST_ENDED
}

/** A row built with no status (tests, the fallback row): its V2 group from its panel [group]. */
private fun sectionOf(group: ZapPanelGroup): ScoreboardGroup = when (group) {
    ZapPanelGroup.LIVE_NOW -> ScoreboardGroup.LIVE
    ZapPanelGroup.STARTING_SOON -> ScoreboardGroup.STARTING_SOON
    ZapPanelGroup.OTHER -> ScoreboardGroup.LATER
    ZapPanelGroup.JUST_ENDED -> ScoreboardGroup.JUST_ENDED
}

/**
 * The panel's [items] grouped by game state (#17): **Live now** first, then **Starting soon** (within [SOON_WINDOW_MS]),
 * then the rest (channels, later games, finished or postponed ones; under Hide until focused too, as the cards label it
 * final, its score still hidden). With [keepOrder] (Live now, the panel, the guide and CH±: [items] in the shared order,
 * [com.nuvio.tv.core.sportslive.SportsLiveLineup], live.25) each group keeps [items]' order. Otherwise (Upcoming) the
 * game furthest in (earliest start) on top and games with no start after them, starting soon soonest first, and
 * [lineupRankOf] first inside each: followed teams, then live-scored games. A game's state comes
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
    listedLive: Boolean = true,
    /** [items] come in the shared order ([com.nuvio.tv.core.sportslive.SportsLiveLineup], live.25), kept inside each group. */
    keepOrder: Boolean = false,
    /** Card keys never taken as live on the list's word (H: listings that are no game): live only when ESPN says so. */
    unconfirmed: Set<String> = emptySet()
): List<ZapPanelRow> {
    class Entry(val row: ZapPanelRow, val startMs: Long?, val index: Int, val rank: Int)
    val entries = items.mapIndexed { index, item ->
        val endedGame = ended[item.streamKey]
        // A Just ended game keeps the final it was moved on, whatever is tracked now.
        val snapshot = (scores[item.streamKey] ?: endedGame?.event).takeIf { mode != null && mode != ScoresMode.OFF }
        val event = ScoreChips.matchedEvent(item, snapshot)
        // One status source (#102): a final is grouped as one in every mode, as the cards label it (C3);
        // Hide until focused keeps only its score hidden (the chip).
        val status = GameStatus.of(item, snapshot, endedGame, nowMs, listedLive && item.streamKey !in unconfirmed)
        val group = panelGroupOf(status)
        val start = when (status) {
            is GameStatus.StartingSoon -> status.startMs
            is GameStatus.Live -> status.event?.takeIf { it.timeValid }?.startMs ?: item.startMs?.takeIf { item.startHasTimeOfDay }
            else -> null
        }
        val chip = ScoreChips.of(item, event, mode, item.streamKey in revealed)
        val teams = PanelArt.of(item, event, known)
        val art = teams.takeIf { group == ZapPanelGroup.STARTING_SOON }
        // A live score shows only for a game in play that strictly matches its event (any score provider).
        val liveScored = group == ZapPanelGroup.LIVE_NOW && event != null
        // No big-game or crunch-time lift (live.25, Fable sorting audit): excitement is a badge in place, never a sort key.
        val rank = lineupRankOf(item, liveScored)
        Entry(ZapPanelRow(item, group, chip, art, teams, status, scoreboardGroupOf(status, item), matched = event != null), start, index, rank)
    }
    val order = if (keepOrder) {
        compareBy<Entry> { it.row.group.ordinal }.thenBy { it.index }
    } else {
        compareBy<Entry> { it.row.group.ordinal }
            .thenBy { it.rank }
            .thenBy { if (it.row.group >= ZapPanelGroup.OTHER) 0L else it.startMs ?: Long.MAX_VALUE }
            .thenBy { it.index }
    }
    return entries.sortedWith(order).map { it.row }
}

/**
 * Where a game sits inside its group when [groupPanelRows] ranks (Upcoming; live lists come in the shared order):
 * followed teams' games first ([LINEUP_RANK_FOLLOWED]), then games with a live score ([liveScored],
 * [LINEUP_RANK_SCORED]), then the rest; the group's own order breaks ties. No big-game or crunch-time slot (live.25).
 */
internal fun lineupRankOf(item: SportsLiveItem, liveScored: Boolean): Int = when {
    item.followed -> LINEUP_RANK_FOLLOWED
    liveScored -> LINEUP_RANK_SCORED
    else -> LINEUP_RANK_REST
}

internal const val LINEUP_RANK_FOLLOWED = 0
internal const val LINEUP_RANK_SCORED = 1
internal const val LINEUP_RANK_REST = 2

/** The panel group of a game's [status]: live, starting soon, just ended, or the rest. */
internal fun panelGroupOf(status: GameStatus): ZapPanelGroup = when (status) {
    is GameStatus.Live -> ZapPanelGroup.LIVE_NOW
    is GameStatus.StartingSoon -> ZapPanelGroup.STARTING_SOON
    is GameStatus.JustEnded -> ZapPanelGroup.JUST_ENDED
    is GameStatus.Upcoming, is GameStatus.Final, is GameStatus.Postponed, GameStatus.Channel -> ZapPanelGroup.OTHER
}

/**
 * The panel's one ordered list (#142), the list the panel shows and CH+/CH− walk: [grouped] ([groupPanelRows]
 * order) listed by V2 group ([ZapPanelRow.section]: live, starting soon, channels, just ended), followed teams'
 * games first inside each group, then the profile's [favorites] channels (by stream key, Channels phase 1: so a
 * Channels session zaps between favorites first), then as grouped. While the panel is open its order is frozen
 * ([freezePanelLineup]).
 *
 * [seed] (live.25, #225): Live Now's order as shown when the game was started there ([SportsLiveZapSession.seed], stream
 * keys): inside each group its games keep that order, so the panel, the guide and CH± follow the list you launched
 * from, calm order included; games it did not show (held behind "N new", or started since) follow, in the shared order.
 */
internal fun panelLineup(grouped: List<ZapPanelRow>, favorites: Set<String> = emptySet(), seed: List<String>? = null): List<ZapPanelRow> {
    val seedAt = seed?.withIndex()?.associate { (at, key) -> key to at }.orEmpty()
    return grouped.sortedWith(
        compareBy<ZapPanelRow> { it.section.ordinal }
            .thenBy { seedAt[it.item.streamKey] ?: Int.MAX_VALUE }
            .thenByDescending { it.item.followed }
            .thenByDescending { it.item.isChannel && it.item.streamKey in favorites }
    )
}

/**
 * The open panel's order (#164), taken when it opened: the rows' stream [keys] top to bottom, the group each is
 * listed under ([placed]), the rows added since ([added]) and each row as last seen ([last]), for one gone since.
 */
internal data class FrozenPanelOrder(
    val keys: List<String>,
    val placed: Map<String, ScoreboardGroup>,
    val added: Set<String>,
    val last: Map<String, ZapPanelRow>
)

/**
 * [fresh] ([panelLineup] order) laid out in the [frozen] order of the open panel (#164), and the order to keep
 * for the next update. Null [frozen] takes [fresh] as the snapshot. Otherwise rows never move: each known row
 * keeps its place and its group header, its score, clock and status updated in place; a row gone from [fresh]
 * stays where it was, dimmed ([ZapPanelRow.gone]) and out of the zap order; a row new to the list is appended to
 * the end of its group, tagged New ([ZapPanelRow.isNew]). A game that ends ([ZapPanelRow.finished]) is the exception: it
 * goes to the start of Just ended at once (#216: FT games sat under LIVE while the panel was open).
 */
internal fun freezePanelLineup(fresh: List<ZapPanelRow>, frozen: FrozenPanelOrder?): Pair<List<ZapPanelRow>, FrozenPanelOrder> {
    if (frozen == null) {
        val snapshot = FrozenPanelOrder(
            keys = fresh.map { it.item.streamKey },
            placed = fresh.associate { it.item.streamKey to it.section },
            added = emptySet(),
            last = fresh.associateBy { it.item.streamKey }
        )
        return fresh to snapshot
    }
    val freshByKey = fresh.associateBy { it.item.streamKey }
    val rows = ArrayList<ZapPanelRow>(frozen.keys.size + fresh.size)
    frozen.keys.forEach { key ->
        val placed = frozen.placed[key] ?: return@forEach
        val row = freshByKey[key]
        if (row != null) {
            rows += row.copy(placed = placed, isNew = key in frozen.added)
        } else {
            frozen.last[key]?.let { rows += it.copy(placed = placed, gone = true) }
        }
    }
    // Ended since the panel opened: out of its old group, to the start of Just ended (or the end of the list).
    val endedNow = rows.filter { it.finished && !it.gone && it.placed != ScoreboardGroup.JUST_ENDED }
    if (endedNow.isNotEmpty()) {
        rows.removeAll(endedNow.toSet())
        val at = rows.indexOfFirst { it.placed == ScoreboardGroup.JUST_ENDED }.takeIf { it >= 0 } ?: rows.size
        rows.addAll(at, endedNow.map { it.copy(placed = ScoreboardGroup.JUST_ENDED) })
    }
    val added = LinkedHashSet(frozen.added)
    fresh.forEach { row ->
        val key = row.item.streamKey
        if (key in frozen.placed) return@forEach
        // The end of its group's run; with none, where its group would start.
        val lastOfGroup = rows.indexOfLast { it.placed == row.section }
        val at = if (lastOfGroup >= 0) {
            lastOfGroup + 1
        } else {
            rows.indexOfFirst { it.placed.ordinal > row.section.ordinal }.takeIf { it >= 0 } ?: rows.size
        }
        rows.add(at, row.copy(isNew = true))
        added += key
    }
    val next = FrozenPanelOrder(
        keys = rows.map { it.item.streamKey },
        placed = rows.associate { it.item.streamKey to it.placed },
        added = added,
        last = frozen.last + freshByKey
    )
    return rows to next
}

/** The open panel's order is taken afresh after this long without a key pressed in it (#164). */
internal const val PANEL_IDLE_REORDER_MS = 2 * 60_000L

/**
 * A line of the panel's list under its pinned **Now playing** row (#140): a group's [Header] (its [count] of
 * games in that run), a [Game] row (never the game playing: it is drawn under Now playing only), or the last line,
 * [HiddenByMySports] (#167).
 */
internal sealed interface PanelGuideEntry {
    val key: String

    data class Header(val section: ScoreboardGroup, val count: Int, val run: Int) : PanelGuideEntry {
        override val key: String get() = "group_${run}_$section"
    }

    /** [justWatched]: the "Just watched" line under Now playing (J7), the game played before this one. */
    data class Game(val row: ZapPanelRow, val justWatched: Boolean = false) : PanelGuideEntry {
        override val key: String get() = row.item.streamKey
    }

    /**
     * The list's last line under My sports: "More live: 4 Racing · 3 Cricket ›" ([summary], user 2026-11-21; #167's
     * "N more hidden by My sports" before), OK opens More live's games.
     */
    data class HiddenByMySports(val count: Int, val summary: List<Pair<String, Int>> = emptyList()) : PanelGuideEntry {
        override val key: String get() = HIDDEN_BY_MY_SPORTS_KEY
    }
}

internal const val HIDDEN_BY_MY_SPORTS_KEY = "hidden_by_my_sports"

/** The key focus may land on for [entry]: a game row or the last line. */
private fun focusKeyOf(entry: PanelGuideEntry): String? = when (entry) {
    is PanelGuideEntry.Game -> entry.key
    is PanelGuideEntry.HiddenByMySports -> entry.key
    is PanelGuideEntry.Header -> null
}

/**
 * The in-player panel as a cable guide (#140): [nowPlaying] (the game playing, pinned above the list; null
 * when the lineup leaves it out) and the list [entries] under it.
 */
internal data class PanelGuide(
    val nowPlaying: ZapPanelRow?,
    val entries: List<PanelGuideEntry>,
    /** The lineup, top to bottom, the game playing's own place included (CH± walk it); the list draws [entries]. */
    val rows: List<ZapPanelRow> = entries.mapNotNull { (it as? PanelGuideEntry.Game)?.row }
) {

    /** The first line focus can land on, top down; null when none. */
    val firstFocusableKey: String? get() = entries.firstNotNullOfOrNull(::focusKeyOf)

    /** The entry keyed [key]'s index in [entries] (the LazyColumn's item index); -1 when not listed. */
    fun indexOf(key: String?): Int = if (key == null) -1 else entries.indexOfFirst { it.key == key }

    /**
     * Where focus goes when its row is no longer listed (#167): the focusable line nearest list index [lastIndex],
     * the lower one on a tie; null when there is none.
     */
    fun nearestFocusable(lastIndex: Int): String? {
        var best: String? = null
        var bestDistance = Int.MAX_VALUE
        entries.forEachIndexed { index, entry ->
            val key = focusKeyOf(entry) ?: return@forEachIndexed
            val distance = abs(index - lastIndex)
            if (distance < bestDistance || (distance == bestDistance && index > lastIndex)) {
                best = key
                bestDistance = distance
            }
        }
        return best
    }
}

/**
 * The panel's [lineup] (#142, the one list CH± walk) laid out under Now playing (#140), in its own order: a
 * header starts each run of one group ([ZapPanelRow.placed]) and counts the games drawn under it. The game playing
 * ([currentKey]) is pinned above the list and drawn there only (user TV report, live.20: a second "▶ Watching" row
 * read as a duplicate); it keeps its place in [PanelGuide.rows], so CH− still plays the game after that place and
 * CH+ the one before it, both wrapping at the ends. A run holding only the game playing gets no header. Another
 * addon's copy of a game already drawn (the same ESPN event, [eventIdOf]; the game playing's too) is not drawn
 * again either: one game, one row. [hiddenByMySports] above 0 ends the list with the "more hidden by My sports"
 * line (#167).
 */
internal fun panelGuide(
    lineup: List<ZapPanelRow>,
    currentKey: String?,
    hiddenByMySports: Int = 0,
    /** The game playing when the lineup leaves it out (a chip, My sports): its copies are left out all the same. */
    playing: SportsLiveItem? = null,
    /** More live's row ([SportsLiveMoreLive.summary]) for the last line. */
    moreLive: List<Pair<String, Int>> = emptyList(),
    /** J7: the game played before this one, still live: the first line, its copies not listed again. */
    justWatched: ZapPanelRow? = null
): PanelGuide {
    val current = lineup.firstOrNull { it.item.streamKey == currentKey }
    // J5: another addon's copy of the game playing (same teams, same day) is the game playing: not listed again.
    val playingItem = current?.item ?: playing
    val seenEvents = HashSet<String>()
    current?.let(::eventIdOf)?.let(seenEvents::add)
    val back = justWatched?.takeIf { row -> playingItem == null || !SportsLivePlayingGame.isSameGame(row.item, playingItem) }
    val entries = buildList {
        back?.let { add(PanelGuideEntry.Game(it, justWatched = true)) }
        back?.let(::eventIdOf)?.let(seenEvents::add)
        var start = 0
        var run = 0
        while (start < lineup.size) {
            val section = lineup[start].placed
            var end = start
            while (end < lineup.size && lineup[end].placed == section) end++
            val drawn = (start until end).map { lineup[it] }.filter { row ->
                row.item.streamKey != currentKey && eventIdOf(row)?.let(seenEvents::add) != false &&
                    (playingItem == null || !SportsLivePlayingGame.isSameGame(row.item, playingItem)) &&
                    (back == null || !SportsLivePlayingGame.isSameGame(row.item, back.item))
            }
            if (drawn.isNotEmpty()) {
                add(PanelGuideEntry.Header(section, drawn.size, run++))
                drawn.forEach { add(PanelGuideEntry.Game(it)) }
            }
            start = end
        }
        if (hiddenByMySports > 0) add(PanelGuideEntry.HiddenByMySports(hiddenByMySports, moreLive))
    }
    return PanelGuide(current, entries, lineup)
}

/** The ESPN event [row]'s status was read from (its strict match), or null. */
internal fun eventIdOf(row: ZapPanelRow): String? = when (val status = row.status) {
    is GameStatus.Live -> status.event?.eventId
    is GameStatus.Final -> status.event.eventId
    is GameStatus.JustEnded -> status.ended.event?.eventId
    else -> null
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
    followed: List<FollowedTeam> = emptyList(),
    /** Card keys the score feeds confirmed they do not have (#215): an addon-live one reads "Listed live". */
    unmatched: Set<String> = emptySet(),
    /** The profile's favorite channels by stream key (Channels phase 1): their rows carry the gold ★. */
    favorites: Set<String> = emptySet()
): List<ZapPanelBoard> {
    val scoresOn = mode != null && mode != ScoresMode.OFF
    // #212: with the card's strict ESPN game, ESPN ids decide which side a followed team is (the star sits on that side).
    val followsSide: (SportsLiveItem, String) -> Boolean = { item, side ->
        val event = ScoreChips.matchedEvent(item, scores[item.streamKey] ?: ended[item.streamKey]?.event)
        followed.any { TeamFollows.followsCardSide(it, side, item, event) }
    }
    return rows.map { row ->
        val item = row.item
        val endedGame = ended[item.streamKey]
        val snapshot = (scores[item.streamKey] ?: endedGame?.event).takeIf { scoresOn }
        val board = scoreboardRowOf(row, snapshot, endedGame, nowMs, followsSide, row.teams).let { built ->
            if (listedLiveOnly(built.status, scoresOn, item.streamKey in unmatched)) built.copy(listedOnly = true) else built
        }.let { built -> if (item.isChannel && item.streamKey in favorites) built.copy(favorite = true) else built }
        val situation = if (board.chip is ScoreChip.Live) {
            GameSituations.of(item, snapshot, mode, item.streamKey in revealed)
        } else {
            null
        }
        ZapPanelBoard(board, situation, snapshot.takeIf { situation != null })
    }
}

/**
 * #375: the panel's [boards] with the games of [awaiting] (ESPN-covered, no feed answered yet; [com.nuvio.tv.core.sportslive.SportsLiveScoreWait])
 * read as Live Now reads them ([withAwaitingScore]): their start, not LIVE, no progress bar. Only a live row is touched.
 */
internal fun withPanelAwaiting(boards: List<ZapPanelBoard>, awaiting: Set<String>): List<ZapPanelBoard> =
    if (awaiting.isEmpty()) boards else boards.map { board ->
        if (board.board.item.streamKey in awaiting) board.copy(board = withAwaitingScore(listOf(board.board), awaiting).single()) else board
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
    val nowPlayingRow: ZapPanelRow? = rows.firstOrNull { it.item.streamKey == currentKey },
    /** Games My sports hides while the panel is on All (My sports, #167): the list's last line offers them. */
    val hiddenByMySports: Int = 0,
    /** More live's row (user 2026-11-21): (group key, count), largest first; empty with nothing hidden. */
    val moreLive: List<Pair<String, Int>> = emptyList(),
    /** J7: the game played before the one playing, still live, as a row: the "Just watched" line. */
    val justWatchedRow: ZapPanelRow? = null,
    /** The shared Live now list is not loaded yet (Watch from outside Sports & Live): "Loading games…" shows. */
    val loading: Boolean = false,
    /** The profile picked My sports (not the default): the list is My sports' games, even when it hides none. */
    val mySportsPicked: Boolean = false
) {
    val items: List<SportsLiveItem> = rows.map { it.item }

    /**
     * The first chip (null) is My sports, not All (TV report #14: "All 52" under My sports with nothing to hide, as
     * live.23 took All sports away): picked My sports, More live's row, or its view as a chip.
     */
    val mySportsScoped: Boolean
        get() = mySportsPicked || moreLive.isNotEmpty() || SportsLiveUiState.ALL_SPORTS_FILTER in chips

    /** The list under Now playing, in the lineup's own order (#140, #164). */
    internal val guide: PanelGuide by lazy { panelGuide(rows, currentKey, hiddenByMySports, playing = nowPlayingRow?.item, moreLive = moreLive, justWatched = justWatchedRow) }
}

/** What OK on a panel row does (#140): play its stream, or open its Game page on the box score. */
internal enum class PanelRowOk { PLAY, BOX_SCORE }

/**
 * OK on [row] (#140): a Just ended game with a strict ESPN match ([game], null without one) opens its Game page,
 * the box score, as Game stats in its menu does; every other row (a Just ended game ESPN does not know too) plays.
 */
internal fun panelRowOk(row: ZapPanelRow, game: ScoreSnapshot?): PanelRowOk =
    // L3: a postponed or cancelled game sits in Just ended too, but was never played: no box score, no postgame; it plays.
    if (row.section == ScoreboardGroup.JUST_ENDED && row.status !is GameStatus.Postponed && game != null) PanelRowOk.BOX_SCORE else PanelRowOk.PLAY

/**
 * What Hold OK on a panel row lists (#140), in order: **Watch both** first (the game playing beside the one
 * held, where dual screen pairs them: [watchBoth]), then **Choose stream** ([chooseStream]), **Game stats** (a
 * strict ESPN match only: [strictMatch]), **Follow ‹team›** once per side ([sides]) and **Report a problem** last.
 * The More… menu's entries ([sportsLiveMoreEntries]) without Details or Remind me, which the player has no room for.
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
    val targets: List<FollowTarget> = emptyList(),
    /** A channel: whether it is a favorite now ("Remove from favorites", else "Add to favorites", first); null for a game. */
    val favorite: Boolean? = null
) {
    val watchBoth: Boolean get() = SportsLiveMoreEntry.WATCH_WITH in entries
    val chooseStream: Boolean get() = SportsLiveMoreEntry.CHOOSE_STREAM in entries
}
