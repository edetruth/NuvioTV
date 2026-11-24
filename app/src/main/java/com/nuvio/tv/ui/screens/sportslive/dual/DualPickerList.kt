package com.nuvio.tv.ui.screens.sportslive.dual

import com.nuvio.tv.core.sportslive.SportsLiveChannelFavorites
import com.nuvio.tv.core.sportslive.SportsLiveChannelPicks
import com.nuvio.tv.core.sportslive.SportsLiveDisplayText
import com.nuvio.tv.core.sportslive.SportsLiveSportKind
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.ScoreChips
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.ui.screens.sportslive.ScoreboardRow
import com.nuvio.tv.ui.screens.sportslive.ZapPanelRow
import com.nuvio.tv.ui.screens.sportslive.groupPanelRows
import com.nuvio.tv.ui.screens.sportslive.panelBoards
import java.util.Locale

/** The picker's sections (live.26, #222), in the order they are listed. */
internal enum class DualPickerSection { YOUR_TEAMS, SAME_LEAGUE, MORE_LIVE, CHANNELS }

/** One picker row: its game or channel, its [section] and how many sources play it ([sources], "2 sources" from 2). */
internal data class DualPickerRow(val item: SportsLiveItem, val section: DualPickerSection, val sources: Int) {
    /** "2 sources" shows only when the game has more than one; never a source's name (principle 9). */
    val showsSources: Boolean get() = sources > 1
}

/** The picker's rows in section order, and whether the sections are headed ([headed]). */
internal data class DualPickerList(val rows: List<DualPickerRow>, val headed: Boolean) {
    /** The section [index]'s row starts, when headers show; else null. Headers are never focusable rows. */
    fun headerAt(index: Int): DualPickerSection? {
        if (!headed) return null
        val section = rows[index].section
        return section.takeIf { index == 0 || rows[index - 1].section != section }
    }

    /** How many rows [section] has (the header's count). */
    fun countOf(section: DualPickerSection): Int = rows.count { it.section == section }

    companion object {
        val EMPTY = DualPickerList(emptyList(), headed = false)
    }
}

/** Fewer rows than this: one short list with no headers (brief approval question 2: headers over 1-5 rows are noise). */
internal const val DUAL_PICKER_HEADERS_FROM = 6

/**
 * "Pick another to watch" (live.26, #222): [candidates] (already the games that may go beside the one playing, in the
 * one shared order, the playing game never among them) in four sections, each keeping that order:
 * - **Your teams**: a followed team's game;
 * - **Same league**: a game of [anchor]'s league ([dualPickerLeagueOf]: ESPN's league, else the listing's own league
 *   word; a bare sport word like "Basketball" is no league, so no guessing);
 * - **More live**: every other game;
 * - **Channels**: the profile's favourite channels, then its recent ones ([SportsLiveChannelFavorites.sections] with
 *   [channelPicks]); never every channel the sources list.
 * Headers show only with [DUAL_PICKER_HEADERS_FROM] rows or more, and only when there are two sections or more.
 * [scores]: read once, for the league only (never a result): the list is built once per open and holds still.
 */
internal fun dualPickerList(
    candidates: List<SportsLiveItem>,
    anchor: SportsLiveItem?,
    scores: Map<String, ScoreSnapshot>,
    channelPicks: SportsLiveChannelPicks
): DualPickerList {
    val (channels, games) = candidates.partition { it.isChannel }
    val league = anchor?.takeUnless { it.isChannel }?.let { dualPickerLeagueOf(it, scores[it.streamKey]) }?.let(::dualPickerLeagueKey)
    val gameRows = games.map { item ->
        val section = when {
            item.followed -> DualPickerSection.YOUR_TEAMS
            league != null && dualPickerLeagueOf(item, scores[item.streamKey])?.let(::dualPickerLeagueKey) == league -> DualPickerSection.SAME_LEAGUE
            else -> DualPickerSection.MORE_LIVE
        }
        DualPickerRow(item, section, item.sourceKeys().size)
    }.sortedBy { it.section.ordinal }
    val picked = pickedChannels(channels, channelPicks)
    val rows = gameRows + picked.map { DualPickerRow(it, DualPickerSection.CHANNELS, it.sourceKeys().size) }
    val headed = rows.size >= DUAL_PICKER_HEADERS_FROM && rows.mapTo(HashSet()) { it.section }.size > 1
    return DualPickerList(rows, headed)
}

/** Only a channel the viewer chose or played: with no picks, no channels at all (gap 8: never 8,000 channels). */
private fun pickedChannels(channels: List<SportsLiveItem>, channelPicks: SportsLiveChannelPicks): List<SportsLiveItem> =
    if (channels.isEmpty()) emptyList() else SportsLiveChannelFavorites.sections(channels, channelPicks).let { it.favorites + it.recent }

/**
 * The [candidates] the picker lists ([dualPickerList]): every game and the picked channels. Its rows are drawn for these
 * only (gemini final review #4: on Channels the candidates hold every channel, thousands on the weak box).
 */
internal fun dualPickerShownItems(candidates: List<SportsLiveItem>, channelPicks: SportsLiveChannelPicks): List<SportsLiveItem> {
    val (channels, games) = candidates.partition { it.isChannel }
    return games + pickedChannels(channels, channelPicks)
}

/**
 * The entry ("Watch with another game", "Change the other game") is offered only when [candidates] hold another live
 * game (approved scope item 3): channels alone never open the picker, and it never opens empty (principle 8).
 */
internal fun dualPickerOffers(candidates: List<SportsLiveItem>): Boolean = candidates.any { !it.isChannel }

/**
 * A league name as "Same league" compares it (live.26 decision): the competition it names when ESPN's maps know one
 * ("EPL" and "Premier League" are both `soccer/eng.1`), else the name itself. Strict matching stays for game merges.
 */
internal fun dualPickerLeagueKey(league: String): String = EspnLeagueMap.competitionOf(league)?.path ?: league

/**
 * [item]'s league for "Same league", lower case: its strictly matched ESPN event's competition ([ScoreSnapshot.leagueName],
 * else its feed's league slug), else the listing's sport text when it names a league ("NBA", "Premier League"): never a
 * bare sport word ("Basketball"), a catch-all ("Sports") or the catalog's own name. Null when none is known.
 */
internal fun dualPickerLeagueOf(item: SportsLiveItem, snapshot: ScoreSnapshot?): String? {
    ScoreChips.matchedEvent(item, snapshot)?.let { event ->
        return (event.leagueName?.takeIf { it.isNotBlank() } ?: event.leagueSlug).trim().lowercase(Locale.ROOT)
    }
    val text = item.sport?.let { SportsLiveDisplayText.label(it) ?: it }?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (SportsLiveTeams.isGenericSport(text) || SportsLiveSportKind.isPlainSportWord(text)) return null
    if (text.equals(item.catalogName.trim(), ignoreCase = true)) return null
    return text.lowercase(Locale.ROOT)
}

/**
 * The picker's rows by card key (live.23 TV test: they showed no logos and no score): each candidate's logos and
 * score chip as the live games panel builds them ([groupPanelRows]), so the Scores setting holds: Show the score,
 * Hide until focused a hidden chip until the row has been focused ([revealed]), Off no chip at all. A spoiler-
 * protected game (followed team's results hidden, Watch later) stays hidden whatever the setting.
 */
internal fun dualPickerRows(
    candidates: List<SportsLiveItem>,
    scores: Map<String, ScoreSnapshot>,
    mode: ScoresMode?,
    revealed: Set<String>,
    nowMs: Long
): Map<String, ZapPanelRow> =
    groupPanelRows(candidates, scores, mode, revealed, nowMs).associateBy { it.item.streamKey }

/**
 * The picker's rows drawn as Live Now draws them ([panelBoards], the in-player panel's own path): tag, logos, names,
 * rank, score chip and status, by card key. The score goes through the one spoiler gate (#117, [ScoreChips] and
 * [com.nuvio.tv.core.sportslive.scores.SpoilerPolicy]): Show the score, Hide until focused once the row was focused
 * ([revealed]), Off none; a protected game (Watch later, a followed team's hidden result) never.
 */
internal fun dualPickerBoards(
    candidates: List<SportsLiveItem>,
    scores: Map<String, ScoreSnapshot>,
    mode: ScoresMode?,
    revealed: Set<String>,
    nowMs: Long
): Map<String, ScoreboardRow> =
    panelBoards(dualPickerRows(candidates, scores, mode, revealed, nowMs).values.toList(), scores, mode, revealed, nowMs)
        .associate { it.board.item.streamKey to it.board }
