package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.runtime.Immutable
import com.nuvio.tv.core.sportslive.EndedGame
import com.nuvio.tv.core.sportslive.EndedSignal
import com.nuvio.tv.core.sportslive.GameStatus
import com.nuvio.tv.core.sportslive.GameStatusStrings
import com.nuvio.tv.core.sportslive.GameStatusText
import com.nuvio.tv.core.sportslive.SportsLiveDisplayText
import com.nuvio.tv.core.sportslive.SportsLiveChannelRegions
import com.nuvio.tv.core.sportslive.SportsLiveProgress
import com.nuvio.tv.core.sportslive.displayName
import com.nuvio.tv.core.sportslive.SportsLiveSportKind
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.ChipScore
import com.nuvio.tv.core.sportslive.scores.GameArt
import com.nuvio.tv.core.sportslive.scores.GameArtTeam
import com.nuvio.tv.core.sportslive.scores.ScoreChip
import com.nuvio.tv.core.sportslive.scores.ScoreChips
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.core.sportslive.scores.WhyTag
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveTab

/**
 * The Scoreboard's groups (V2 Live Now, #132), in the order they are listed: games in play, games
 * starting soon (with a countdown), [LATER] games (Upcoming only), [CHANNELS] (channels only, never mixed
 * with games), then the dimmed [JUST_ENDED] games (#60).
 */
enum class ScoreboardGroup { LIVE, STARTING_SOON, LATER, CHANNELS, JUST_ENDED }

/**
 * A Scoreboard row: its [group], the score [chip] (spoiler setting applied; null when unmatched,
 * upcoming, Scores Off or an estimated end), the drawn [art] (null for a channel) and which sides a
 * followed team plays, in the card's team order. [probablyEnded]: an estimated end (#60), shown as
 * "Probably ended" with no score. [startsIn]: (hours, minutes) to a timed kickoff of a Starting soon
 * row, at the clock the rows were built with; null otherwise (a date with no time never counts down).
 */
@Immutable
data class ScoreboardRow(
    val item: SportsLiveItem,
    val group: ScoreboardGroup,
    val chip: ScoreChip?,
    val art: GameArt?,
    val firstFollowed: Boolean,
    val secondFollowed: Boolean,
    val probablyEnded: Boolean,
    val startsIn: Pair<Int, Int>? = null,
    /** Where the game stands ([GameStatus.of]): [group] and the status column are read from it. */
    val status: GameStatus = GameStatus.Channel,
    /** The share of regulation played (0..1) of a live game ([SportsLiveProgress]); null draws no bar. */
    val progress: Float? = null,
    /** The excitement badge under the period (#43, F2): "Close · 2 min left", "OT"; null for none. */
    val excitement: WhyTag? = null,
    /**
     * A real two-sided match (V2 P3): an ESPN match, or two parsed sides that both read as teams. Anything
     * else ("ATP 250 - Stockholm Open", a bare "Tennis") is drawn as a single-line row.
     */
    val twoSided: Boolean = art != null,
    /** The sport its title, genres or catalog name tell, for the tag when no league is known ("TENNIS"). */
    val sportTag: SportsLiveSportKind? = null,
    /** The list section it sits under (P4): a channel's region ([SportsLiveChannelRegions]); null for none. */
    val section: String? = null,
    /** Its last stream lookup found nothing (#145): kept, and dimmed until focused, as Just ended is. */
    val noStreams: Boolean = false
) {
    /** A followed team plays in it: the gold left bar. */
    val followed: Boolean get() = firstFollowed || secondFollowed || item.followed
}

/**
 * The Scoreboard's rows for [items] (one tab, as filtered): [groupPanelRows]' order (live furthest in
 * first, starting soon soonest first, followed teams first in each group), regrouped by [scoreboardGroupOf]
 * and listed group by group. On Upcoming a game is LIVE only when ESPN says it is in play. [followsSide]
 * tells whether a followed team is a card's side (by the side's card name). [excitement] holds each live
 * game's badge by card key (Live now, Scores shown); it never re-orders anything.
 */
internal fun scoreboardRows(
    items: List<SportsLiveItem>,
    tab: SportsLiveTab,
    scores: Map<String, ScoreSnapshot>,
    mode: ScoresMode?,
    revealed: Set<String>,
    nowMs: Long,
    ended: Map<String, EndedGame> = emptyMap(),
    followsSide: (SportsLiveItem, String) -> Boolean = { _, _ -> false },
    excitement: Map<String, WhyTag> = emptyMap()
): List<ScoreboardRow> {
    val scoresOn = mode != null && mode != ScoresMode.OFF
    return groupPanelRows(items, scores, mode, revealed, nowMs, ended, listedLive = tab != SportsLiveTab.UPCOMING).map { row ->
        val endedGame = ended[row.item.streamKey]
        val snapshot = (scores[row.item.streamKey] ?: endedGame?.event).takeIf { scoresOn }
        scoreboardRowOf(row, snapshot, endedGame, nowMs, followsSide, GameArt.of(row.item, snapshot), tab)
            .let { built -> excitement[row.item.streamKey]?.takeIf { built.group == ScoreboardGroup.LIVE }?.let { built.copy(excitement = it) } ?: built }
    }.sortedBy { it.group.ordinal }
}

/**
 * [row] (a [groupPanelRows] row) as a Scoreboard row: the Scoreboard and the in-player panel (#83) draw
 * every game from this one model. [snapshot] is the game's score (Scores on), [endedGame] its Just ended
 * verdict, [art] its drawn teams (the panel passes the ones it already holds).
 */
internal fun scoreboardRowOf(
    row: ZapPanelRow,
    snapshot: ScoreSnapshot?,
    endedGame: EndedGame?,
    nowMs: Long,
    followsSide: (SportsLiveItem, String) -> Boolean,
    art: GameArt?,
    tab: SportsLiveTab = SportsLiveTab.LIVE_NOW
): ScoreboardRow {
    val item = row.item
    // One status source (#102): the row's group is its game's status, as in the panel and the cards.
    val status = row.status
    val group = scoreboardGroupOf(status, item, tab)
    val estimate = endedGame?.signal == EndedSignal.ESTIMATE
    val sides = ScoreChips.orderedSides(item)
    val matched = ScoreChips.matchedEvent(item, snapshot) != null
    return ScoreboardRow(
        item = item,
        group = group,
        chip = if (estimate) null else row.chip,
        art = art,
        firstFollowed = sides != null && followsSide(item, sides.first),
        secondFollowed = sides != null && followsSide(item, sides.second),
        probablyEnded = estimate,
        startsIn = (status as? GameStatus.StartingSoon)?.let { GameStatus.startsIn(it.startMs, nowMs) },
        status = status,
        progress = if (group == ScoreboardGroup.LIVE && status is GameStatus.Live) {
            SportsLiveProgress.share(item, status.event, nowMs)
        } else {
            null
        },
        twoSided = scoreboardTwoSided(art, matched),
        sportTag = SportsLiveSportKind.tagOf(item)
    )
}

/**
 * The Channels tab's rows (V2 P4): one per channel, grouped by region ([SportsLiveChannelRegions.grouped]:
 * country codes, long names, then Other), each region's channels as listed.
 */
internal fun channelRows(items: List<SportsLiveItem>): List<ScoreboardRow> =
    SportsLiveChannelRegions.grouped(items).flatMap { (region, channels) ->
        channels.map { item ->
            ScoreboardRow(
                item = item, group = ScoreboardGroup.CHANNELS, chip = null, art = null,
                firstFollowed = false, secondFollowed = false, probablyEnded = false,
                status = GameStatus.Channel, twoSided = false, section = region
            )
        }
    }

/**
 * The Replays tab's rows (V2 P4, #90): each replay under its spoiler-free title ([SportsLiveDisplayText.spoilerFree])
 * with no description, so neither the row nor the stage can tell a result; a two-team row with drawn logos when
 * the title names a match ([GameArt.ofReplay]), else a single-line row. Never a score.
 */
internal fun replayRows(items: List<SportsLiveItem>, art: (SportsLiveItem, String) -> GameArt? = { item, title -> GameArt.ofReplay(item, title) }): List<ScoreboardRow> =
    items.map { raw ->
        val title = SportsLiveDisplayText.spoilerFree(raw.meta.name)
        val item = raw.copy(meta = raw.meta.copy(name = title, description = null))
        val drawn = art(item, title)
        ScoreboardRow(
            item = item, group = ScoreboardGroup.LATER, chip = null, art = drawn,
            firstFollowed = false, secondFollowed = false, probablyEnded = false,
            status = GameStatus.Channel,
            twoSided = scoreboardTwoSided(drawn, matched = false),
            sportTag = SportsLiveSportKind.tagOf(item)
        )
    }

/**
 * A single-line row's title (V2 P3): the clean title, its two written parts joined by " · " when the addon
 * wrote a non-team pair as a game ("ATP 250 vs Stockholm Open" → "ATP 250 · Stockholm Open").
 */
internal fun singleLineTitle(item: SportsLiveItem): String {
    if (item.isChannel) return item.displayName
    // The clean title too (P4): its spaces are plain ones, so a pair written with odd spacing reads the same in
    // the list and in the player's panel.
    val clean = item.displayName
    val written = SportsLiveTeams.writtenOrder(item.meta.name) ?: SportsLiveTeams.writtenOrder(clean) ?: splitVersus(clean)
    return written?.let { (first, second) -> "${SportsLiveDisplayText.name(first)} · ${SportsLiveDisplayText.name(second)}" }
        ?: clean
}

/** "A vs B" / "A v B" in any case: its two parts; null when it is not written that way. */
private fun splitVersus(title: String): Pair<String, String>? {
    val parts = VERSUS.split(title, limit = 2).map { it.trim() }
    return if (parts.size == 2 && parts.all { it.isNotEmpty() }) parts[0] to parts[1] else null
}

private val VERSUS = Regex("""\s+v(?:s\.?|\.)?\s+""", RegexOption.IGNORE_CASE)

/**
 * Whether a row draws two teams (V2 P3): only for a real two-sided match, an ESPN match ([matched]) or two
 * sides of [art] that both read as teams ([SportsLiveTeams.isTeamLike]).
 */
internal fun scoreboardTwoSided(art: GameArt?, matched: Boolean): Boolean =
    art != null && (matched || (SportsLiveTeams.isTeamLike(art.first.name) && SportsLiveTeams.isTeamLike(art.second.name)))

/**
 * The Scoreboard group of [item] with [status] on [tab]: a channel always sits under CHANNELS; on Live
 * now (whose games are all listed live) a game ESPN still has as later counts as starting soon and a
 * postponed one stays with the live games, as there is no LATER group there.
 */
internal fun scoreboardGroupOf(status: GameStatus, item: SportsLiveItem, tab: SportsLiveTab = SportsLiveTab.LIVE_NOW): ScoreboardGroup {
    if (item.isChannel) return ScoreboardGroup.CHANNELS
    val liveNow = tab != SportsLiveTab.UPCOMING
    return when (status) {
        is GameStatus.Live -> ScoreboardGroup.LIVE
        is GameStatus.StartingSoon -> ScoreboardGroup.STARTING_SOON
        is GameStatus.Final, is GameStatus.JustEnded -> ScoreboardGroup.JUST_ENDED
        is GameStatus.Upcoming -> if (liveNow) ScoreboardGroup.STARTING_SOON else ScoreboardGroup.LATER
        is GameStatus.Postponed -> if (liveNow) ScoreboardGroup.LIVE else ScoreboardGroup.LATER
        GameStatus.Channel -> ScoreboardGroup.CHANNELS
    }
}

/** A Just ended (#60) or "no streams yet" (#145) row is dimmed (60 %) until it has focus. */
internal fun ScoreboardRow.dimmed(focused: Boolean): Boolean =
    (group == ScoreboardGroup.JUST_ENDED || noStreams) && !focused

/**
 * The names a Scoreboard row may give [team], longest first (N1): the full name, ESPN's short name,
 * then the abbreviation. The row shows the first that fits both team lines; blanks and repeats are dropped.
 */
internal fun scoreboardNameChoices(team: GameArtTeam): List<String> =
    listOf(team.name, team.shortName, team.short).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        .ifEmpty { listOf(team.name) }

/** What a row's scores column shows. */
internal sealed interface ScoreboardScores {
    /** Both scores, stacked; [leader] is 0 (first), 1 (second) or null (level), set semibold / white. */
    data class Shown(val first: Int, val second: Int, val leader: Int?) : ScoreboardScores

    /** Hide until focused (spoilers unchanged): the hidden-score mark, never a number. */
    data object Hidden : ScoreboardScores

    /** Nothing: not started, unmatched, a channel, Scores Off or an estimated end. */
    data object None : ScoreboardScores
}

/** A row's scores column from its [chip] (spoiler setting already applied). */
internal fun scoreboardScoresOf(chip: ScoreChip?): ScoreboardScores {
    val score: ChipScore = when (chip) {
        is ScoreChip.Live -> chip.score
        is ScoreChip.Final -> chip.score
        ScoreChip.Hidden -> return ScoreboardScores.Hidden
        else -> null
    } ?: return ScoreboardScores.None
    val leader = when {
        score.first > score.second -> 0
        score.second > score.first -> 1
        else -> null
    }
    return ScoreboardScores.Shown(score.first, score.second, leader)
}

/**
 * A row's status column (75 dp): [primary] on line 1 (the clock with a red dot when [live], the kickoff,
 * the final word), [secondary] on line 2 (the period, the countdown). [urgent]: a countdown inside the last
 * hour, drawn in a red outline.
 */
internal data class ScoreboardStatusLines(
    val primary: String?,
    val secondary: String? = null,
    val live: Boolean = false,
    val urgent: Boolean = false
)

/**
 * [row]'s status column in the one status format (#102). [kickoff] words its start ("8:30 PM",
 * "Tomorrow 6:30 PM"); null when it has none.
 */
internal fun scoreboardStatusLines(row: ScoreboardRow, strings: GameStatusStrings, kickoff: String?): ScoreboardStatusLines {
    val chip = row.chip
    val sport = GameStatusText.sportOf(row.status)
    return when {
        row.probablyEnded -> ScoreboardStatusLines(strings.probablyEnded)
        // Spoilers unchanged: "Score hidden", no clock and no dot, whatever the game's state.
        chip is ScoreChip.Hidden -> ScoreboardStatusLines(strings.chip.hidden)
        chip is ScoreChip.Live -> {
            val clock = chip.clock?.let { splitClock(it) }
            ScoreboardStatusLines(clock?.first ?: strings.live, clock?.second, live = true)
        }
        chip is ScoreChip.Final -> ScoreboardStatusLines(chip.note ?: GameStatusText.finalWord(sport, strings))
        chip is ScoreChip.Status -> ScoreboardStatusLines(chip.text ?: strings.postponed)
        row.group == ScoreboardGroup.JUST_ENDED -> ScoreboardStatusLines(GameStatusText.finalWord(sport, strings))
        row.group == ScoreboardGroup.LIVE && row.status is GameStatus.Live -> ScoreboardStatusLines(strings.live, live = true)
        row.status is GameStatus.Postponed -> ScoreboardStatusLines((row.status as GameStatus.Postponed).text ?: strings.postponed)
        row.group == ScoreboardGroup.CHANNELS -> ScoreboardStatusLines(null)
        else -> {
            val countdown = row.startsIn?.let { GameStatusText.countdown(it, strings) }
            ScoreboardStatusLines(kickoff, countdown, urgent = row.startsIn?.first == 0)
        }
    }
}

/** ESPN's clock as (clock, period): "9:51 - 2nd Half" → ("9:51", "2nd half"), "Q4 4:12" → ("4:12", "Q4"); else whole on line 1. */
internal fun splitClock(clockText: String): Pair<String, String?> {
    val text = clockText.trim()
    val parts = text.split(" - ", limit = 2).map { it.trim() }
    if (parts.size == 2 && parts.all { it.isNotEmpty() }) {
        val period = parts[1].let { if (it.first().isDigit()) it.lowercase() else it }
        return parts[0] to period
    }
    QUARTER_CLOCK.matchEntire(text)?.let { return it.groupValues[2] to it.groupValues[1] }
    return text to null
}

private val QUARTER_CLOCK = Regex("""^(Q\d|OT\d?)\s+(\d{1,2}:\d{2})$""", RegexOption.IGNORE_CASE)

/** An entry of the More… sheet (V2, #132, #134), in the order it lists them. */
internal enum class SportsLiveMoreEntry { WATCH_WITH, CHOOSE_STREAM, GAME_STATS, FOLLOW, REMIND, DETAILS, HIDE }

/**
 * What More… (or Hold OK on a row) lists for one item (#134 order): **Watch with another game** where dual screen
 * is offered ([watchWith]: a live game on Live now, or a channel; it always opens the picker, F13), **Choose
 * stream…** for what plays live ([chooseStream]), **Game stats** only for a strict ESPN match ([strictMatch]),
 * **Follow ‹team›** once per side matched to a real team ([sides], [com.nuvio.tv.core.sportslive.scores.FollowTargets]),
 * **Remind me** only for a game not started ([remindable]), **Details** always (the raw addon text lives only
 * there), and **Hide from Live Now** for an unmatched item on Live Now ([hide]). The team pages sit between
 * Remind me and Details.
 */
internal fun sportsLiveMoreEntries(
    watchWith: Boolean,
    chooseStream: Boolean,
    strictMatch: Boolean,
    sides: Int,
    remindable: Boolean,
    hide: Boolean = false
): List<SportsLiveMoreEntry> = buildList {
    if (watchWith) add(SportsLiveMoreEntry.WATCH_WITH)
    if (chooseStream) add(SportsLiveMoreEntry.CHOOSE_STREAM)
    if (strictMatch) add(SportsLiveMoreEntry.GAME_STATS)
    repeat(sides) { add(SportsLiveMoreEntry.FOLLOW) }
    if (remindable) add(SportsLiveMoreEntry.REMIND)
    add(SportsLiveMoreEntry.DETAILS)
    if (hide) add(SportsLiveMoreEntry.HIDE)
}

/**
 * "Hide from Live Now" (#134) is offered on [item]: a Live now event (not a channel) that matches no ESPN game
 * ([strictMatch]) and no real team ([followable]): a talk show, a series, a sport ESPN does not carry.
 */
internal fun sportsLiveOffersHide(tab: SportsLiveTab, item: SportsLiveItem, strictMatch: Boolean, followable: Boolean): Boolean =
    tab == SportsLiveTab.LIVE_NOW && !item.isChannel && !strictMatch && !followable

/** The stage's buttons: always **Watch** (primary) and **More…** (outlined), one row, never wrapping. */
internal enum class SportsLiveStageButton { WATCH, MORE }

/**
 * The stage's fixed layout (V2, F4): one size for every row, so nothing re-flows as focus moves and the
 * buttons never leave the screen. Board px ÷ 2.
 */
internal object SportsLiveStageLayout {
    const val WIDTH_DP = 360
    const val MEDIA_HEIGHT_DP = 203
    const val SCORE_BLOCK_HEIGHT_DP = 132
    const val GAP_DP = 10
    const val BUTTON_ROW_HEIGHT_DP = 40

    /** The stage's whole height: media, score block, buttons and the gaps between. */
    const val HEIGHT_DP = MEDIA_HEIGHT_DP + GAP_DP + SCORE_BLOCK_HEIGHT_DP + GAP_DP + BUTTON_ROW_HEIGHT_DP

    /** The buttons of the stage, whatever is focused: the same two for a game, an upcoming game or a channel. */
    fun buttons(@Suppress("UNUSED_PARAMETER") row: ScoreboardRow?): List<SportsLiveStageButton> =
        listOf(SportsLiveStageButton.WATCH, SportsLiveStageButton.MORE)
}

/**
 * Where Live Now's focus goes on return from another screen (F3): the list opens at its top, so the LIVE
 * group shows; the card that had focus keeps it while it sits among the first [rowsOnScreen] rows, else
 * the first row takes focus. Null when the list is empty.
 */
internal fun sportsLiveReturnFocusKey(keys: List<String>, focusedKey: String?, rowsOnScreen: Int): String? {
    val index = focusedKey?.let(keys::indexOf) ?: -1
    return if (index in 0 until rowsOnScreen) focusedKey else keys.firstOrNull()
}

/**
 * How far a list scrolls to bring a focused row ([offset], [size] in px) into a viewport [containerSize]
 * tall, keeping it [margin] px from either edge (about two rows, V2): 0 while it already is; a row taller
 * than the room left aligns to the top margin.
 */
internal fun sportsLiveKeepFromEdge(offset: Float, size: Float, containerSize: Float, margin: Float): Float {
    val room = containerSize - 2 * margin
    if (size > room) return offset - margin
    val end = offset + size
    return when {
        offset < margin -> offset - margin
        end > containerSize - margin -> end - (containerSize - margin)
        else -> 0f
    }
}

/**
 * Time to kickoff for a Starting soon row as (hours, minutes), rounded up to the minute; null once
 * [startMs] has passed or when it is unknown.
 */
internal fun scoreboardStartsIn(startMs: Long?, nowMs: Long): Pair<Int, Int>? = startMs?.let { GameStatus.startsIn(it, nowMs) }
