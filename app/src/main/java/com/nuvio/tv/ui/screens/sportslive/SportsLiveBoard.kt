package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.scores.ScoreMatcher
import com.nuvio.tv.core.sportslive.scores.BigGameReasonKind
import com.nuvio.tv.core.sportslive.scores.TeamStanding
import com.nuvio.tv.core.sportslive.scores.StandingsRepository
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoreChips
import com.nuvio.tv.core.sportslive.scores.TeamFollows
import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.BigGame
import androidx.compose.runtime.Immutable
import com.nuvio.tv.core.sportslive.EndedGame
import com.nuvio.tv.core.sportslive.SportsLiveChannelList
import com.nuvio.tv.core.sportslive.SportsLiveChannelLists
import com.nuvio.tv.core.sportslive.SportsLiveChannelSummary
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.GameArt
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.core.sportslive.scores.SpoilerProtection
import com.nuvio.tv.core.sportslive.scores.WhyTag
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.core.sportslive.scores.GameDay
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveTab
import java.time.ZoneId

/** The scores, setting and reveals the screen has: what the Scoreboard and drawn cards read. */
data class SportsLiveBoardScores(
    val scores: Map<String, ScoreSnapshot> = emptyMap(),
    val mode: ScoresMode? = null,
    val revealed: Set<String> = emptySet(),
    /** Spoiler protection (#151): a Just ended game's kept final is stamped with it too. */
    val protection: SpoilerProtection = SpoilerProtection(),
    /** Replays of games saved for later, by card key: listed first under Saved for later (#151). */
    val savedReplays: Set<String> = emptySet(),
    /** Replays' own ESPN games, by card key: the day a replay is listed under (BUG14, [GameDay]). */
    val replayMatches: Map<String, ScoreSnapshot> = emptyMap()
)

/**
 * Everything one build of a [SportsLiveBoard] reads; equal inputs give an equal board, so a focus move
 * (which changes the selection, not these) builds nothing.
 */
data class SportsLiveBoardInputs(
    val tab: SportsLiveTab,
    val filter: String?,
    val items: List<SportsLiveItem>,
    val scores: SportsLiveBoardScores,
    /** Live now's Just ended verdicts (#60); empty on other tabs. */
    val ended: Map<String, EndedGame>,
    /** Live now's "no streams yet" cards by card key (#145): drawn dimmed; empty on other tabs. */
    val noStreams: Set<String> = emptySet(),
    /** Live now's excitement badges by card key (#43, Scores shown); empty otherwise. */
    val excitement: Map<String, WhyTag>,
    val followed: List<FollowedTeam>,
    /** The view model's clock, to the minute. */
    val clockMs: Long,
    /** Learned logos' revision (#66): the drawn cards are built again when it moves. */
    val logoRevision: Int,
    /** Standings by league (#137): Upcoming's big games read them; empty on the other tabs. */
    val standings: Map<EspnLeague, Map<String, TeamStanding>> = emptyMap(),
    /** Channels' favorites, recent plays, browse view and countries (phases 1 and 2); none on the other tabs. */
    val channelLists: SportsLiveChannelLists = SportsLiveChannelLists.NONE
)

/**
 * What the list draws for one tab and filter (#71): the items in the order shown, the Scoreboard's rows
 * (Live now and Upcoming, V2: one view per tab) and each card's drawn art. Built in the view model on its
 * compute dispatcher, never in composition, so a large list (60 games on Upcoming) opens without holding
 * the main thread.
 */
@Immutable
data class SportsLiveBoard(
    val tab: SportsLiveTab,
    val filter: String?,
    val items: List<SportsLiveItem>,
    /** The list's rows: the Scoreboard's (Live now, Upcoming), Channels' by region, Replays' (V2 P4); null for Results. */
    val rows: List<ScoreboardRow>?,
    /** Each item's drawn card by `addonId|id` (null: the addon's art). */
    val arts: Map<String, GameArt?>,
    /** Upcoming's Agenda (V2, P3) or Replays' days (P4): [rows] by day; null on the other tabs. */
    val agenda: SportsLiveAgenda? = null,
    /** Channels (phase 2): its view, counts and countries; null on the other tabs. */
    val channels: SportsLiveChannelSummary? = null
) {
    /** True when this board shows [tab] with [filter]. */
    fun shows(tab: SportsLiveTab, filter: String?): Boolean = this.tab == tab && this.filter == filter

    val rowOf: Map<String, ScoreboardRow> by lazy { rows?.associateBy { it.item.streamKey }.orEmpty() }

    companion object {
        /** The tabs drawn from Scoreboard rows (V2, #132): Live now, and Upcoming (as its Agenda). */
        fun isScoreboard(tab: SportsLiveTab): Boolean = tab == SportsLiveTab.LIVE_NOW || tab == SportsLiveTab.UPCOMING

        /**
         * Upcoming's big games (#137): each row with a strict ESPN match rated ([BigGame]) from its ranks, records,
         * channel and note, and its league's standings; a big one carries its rating (the badge, Games to watch).
         */
        internal fun withBigGames(
            rows: List<ScoreboardRow>,
            scores: Map<String, ScoreSnapshot>,
            standings: Map<EspnLeague, Map<String, TeamStanding>>
        ): List<ScoreboardRow> = rows.map { row ->
            val event = ScoreChips.matchedEvent(row.item, scores[row.item.streamKey])?.takeIf { it.state == ScoreState.PRE }
                ?: return@map row
            val rating = BigGame.rate(event, standings[StandingsRepository.leagueOf(event)].orEmpty(), followed = row.followed)
            if (!rating.isBig) return@map row
            // The records read in the card's own order, as its two names above them.
            val sides = ScoreChips.orderedSides(row.item)
            val homeFirst = sides?.let { ScoreMatcher.firstIsHome(it.first, it.second, event) } == true
            val reasons = if (!homeFirst) rating.reasons else rating.reasons.map { reason ->
                val text = reason.text
                if (reason.kind == BigGameReasonKind.RECORDS && text != null) reason.copy(text = text.split(" vs ").reversed().joinToString(" vs ")) else reason
            }
            row.copy(bigGame = rating.copy(reasons = reasons, eventId = event.eventId))
        }

        /**
         * Builds the board for [inputs]: pure but for [channelDead] (Channels' Popular now leaves out channels whose
         * links all failed this session), and slow for a large list, so callers run it off the main thread.
         */
        fun build(
            inputs: SportsLiveBoardInputs,
            zone: ZoneId = ZoneId.systemDefault(),
            channelDead: (SportsLiveItem) -> Boolean = SportsLiveChannelList::deadByMarks
        ): SportsLiveBoard {
            val tab = inputs.tab
            val channelView = if (tab == SportsLiveTab.CHANNELS) {
                channelBoard(
                    items = inputs.items,
                    lists = inputs.channelLists,
                    sections = inputs.filter == null,
                    nowMs = inputs.clockMs,
                    followed = inputs.followed,
                    dead = channelDead
                )
            } else {
                null
            }
            val scores = inputs.scores
            val rows = if (isScoreboard(tab)) {
                scoreboardRows(
                    items = inputs.items,
                    tab = tab,
                    scores = scores.scores,
                    mode = scores.mode,
                    revealed = scores.revealed,
                    nowMs = inputs.clockMs,
                    ended = inputs.ended.mapValues { (_, ended) -> ended.event?.let { ended.copy(event = scores.protection.stamp(it)) } ?: ended },
                    followsSide = { item, side ->
                        // #212: with the card's strict ESPN game, ESPN ids decide which side a followed team is.
                        val event = ScoreChips.matchedEvent(item, scores.scores[item.streamKey])
                        inputs.followed.any { TeamFollows.followsCardSide(it, side, item, event) }
                    },
                    excitement = inputs.excitement
                ).map { row -> if (row.item.streamKey in inputs.noStreams) row.copy(noStreams = true) else row }
                    .let { built -> if (tab == SportsLiveTab.UPCOMING) withBigGames(built, scores.scores, inputs.standings) else built }
            } else {
                when (tab) {
                    // Channels (phases 1 and 2): Favorites, Recent and Popular now, or the browse view; Replays under
                    // their spoiler-free titles, by day (V2 P4).
                    SportsLiveTab.CHANNELS -> channelView?.rows
                    SportsLiveTab.REPLAYS -> replayRows(inputs.items)
                    else -> null
                }
            }
            val agenda = when {
                rows == null -> null
                tab == SportsLiveTab.UPCOMING -> SportsLiveAgenda.of(rows, inputs.clockMs, zone)
                tab == SportsLiveTab.REPLAYS -> SportsLiveAgenda.ofReplays(rows, inputs.clockMs, zone, scores.savedReplays) {
                    scores.replayMatches[it.streamKey] ?: scores.scores[it.streamKey]
                }
                else -> null
            }
            val items = agenda?.items ?: rows?.map { it.item } ?: inputs.items
            // The cards' drawn art; a row already carries its own.
            val arts = HashMap<String, GameArt?>(items.size * 2)
            rows?.forEach { arts[it.item.streamKey] = it.art }
            for (item in items) {
                if (item.streamKey !in arts) arts[item.streamKey] = GameArt.of(item, scores.scores[item.streamKey])
            }
            return SportsLiveBoard(tab = tab, filter = inputs.filter, items = items, rows = rows, arts = arts, agenda = agenda, channels = channelView?.summary)
        }
    }
}

/**
 * This board with [previous]'s row instances wherever a row did not change (#143). A score poll builds every row of the
 * tab again, and the list tells a row's change by its instance, so keeping the equal ones lets only the rows whose game
 * moved draw again. Only for the same tab and filter; the Agenda's rows are left as built.
 */
internal fun SportsLiveBoard.reusingRows(previous: SportsLiveBoard?): SportsLiveBoard {
    val built = rows ?: return this
    val before = previous?.takeIf { it.tab == tab && it.filter == filter }?.rows
    if (before.isNullOrEmpty()) return this
    val beforeByKey = HashMap<String, ScoreboardRow>(before.size * 2)
    before.forEach { beforeByKey[it.item.streamKey] = it }
    var reused = 0
    val rows = built.map { row ->
        val kept = beforeByKey[row.item.streamKey]?.takeIf { it == row }
        if (kept != null) reused++
        kept ?: row
    }
    if (reused == 0) return this
    // Nothing changed at all: the same list too.
    val sameList = rows.size == before.size && rows.indices.all { rows[it] === before[it] }
    return copy(rows = if (sameList) before else rows)
}

/**
 * The boards last built for each view (#143: tab, filter and Channels' browse view), with the inputs each was built
 * from. A tab opened again shows its last board at once ([shownFor]) while the fresh one is built, and builds nothing
 * when its inputs did not change ([reusable]). Bounded to the [maxViews] views used last; thread-safe.
 */
internal class SportsLiveBoardCache(private val maxViews: Int = MAX_VIEWS) {
    private val entries = object : LinkedHashMap<String, Pair<SportsLiveBoardInputs, SportsLiveBoard>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<SportsLiveBoardInputs, SportsLiveBoard>>?): Boolean =
            size > maxViews
    }

    /** The board last built for [inputs]' view, whatever it was built from; null when none. */
    @Synchronized
    fun shownFor(inputs: SportsLiveBoardInputs): SportsLiveBoard? = entries[viewOf(inputs)]?.second

    /** The board last built for [inputs]' view when it was built from these very inputs; null otherwise. */
    @Synchronized
    fun reusable(inputs: SportsLiveBoardInputs): SportsLiveBoard? = entries[viewOf(inputs)]?.takeIf { it.first == inputs }?.second

    @Synchronized
    fun put(inputs: SportsLiveBoardInputs, board: SportsLiveBoard) {
        entries[viewOf(inputs)] = inputs to board
    }

    /** True when a board is held for [tab] with [filter] and no Channels browse view. */
    @Synchronized
    fun has(tab: SportsLiveTab, filter: String?): Boolean = entries.containsKey(viewOf(tab, filter, null))

    companion object {
        const val MAX_VIEWS = 8

        fun viewOf(inputs: SportsLiveBoardInputs): String = viewOf(inputs.tab, inputs.filter, inputs.channelLists.browse?.kind?.name)

        private fun viewOf(tab: SportsLiveTab, filter: String?, browse: String?): String = "${tab.name}|${filter.orEmpty()}|${browse.orEmpty()}"
    }
}
