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
import com.nuvio.tv.core.sportslive.GameStatus
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
    /** Live now's cards the score feeds confirmed they do not have (#215, [listedLiveOnly]); empty on other tabs. */
    val unmatched: Set<String> = emptySet(),
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
    val channelLists: SportsLiveChannelLists = SportsLiveChannelLists.NONE,
    /** Live now's listings that are no game (H) among [items], by card key: LIVE only when ESPN has them in play. */
    val otherEvents: Set<String> = emptySet(),
    /** Live now's More live view is open: [items] are its games, each by its group key (the list's sections). */
    val moreLive: Map<String, String> = emptyMap()
)

/**
 * The inputs of [tab]'s board with [filter] over [items], as the view model builds them from [state] and the screen's
 * [scores] (live.23 RC upshift): the one place they are made, so Upcoming built ahead ([SportsLiveViewModel] prewarm)
 * reads what its normal build reads (scores, followed teams, standings) and lays out the same sections.
 */
internal fun sportsLiveBoardInputsOf(
    state: SportsLiveUiState,
    tab: SportsLiveTab,
    filter: String?,
    items: List<SportsLiveItem>,
    scores: SportsLiveBoardScores,
    followed: List<FollowedTeam>,
    logoRevision: Int,
    standings: Map<EspnLeague, Map<String, TeamStanding>>,
    channelLists: SportsLiveChannelLists,
    unmatched: Set<String>
): SportsLiveBoardInputs {
    val live = tab == SportsLiveTab.LIVE_NOW
    return SportsLiveBoardInputs(
        tab = tab,
        filter = filter,
        items = items,
        scores = scores,
        ended = if (live) state.justEnded else emptyMap(),
        noStreams = if (live) state.noStreams else emptySet(),
        unmatched = if (live) unmatched else emptySet(),
        excitement = if (live) state.whyTags else emptyMap(),
        otherEvents = if (live && state.moreLiveOpen) state.moreLiveOther else emptySet(),
        moreLive = if (live && state.moreLiveOpen) state.moreLiveGroupOf else emptyMap(),
        followed = followed,
        clockMs = state.nowMs / SPORTS_LIVE_BOARD_MINUTE_MS * SPORTS_LIVE_BOARD_MINUTE_MS,
        logoRevision = logoRevision,
        // Upcoming's big games (#137) read them.
        standings = if (tab == SportsLiveTab.UPCOMING) standings else emptyMap(),
        channelLists = if (tab == SportsLiveTab.CHANNELS) channelLists else SportsLiveChannelLists.NONE
    )
}

private const val SPORTS_LIVE_BOARD_MINUTE_MS = 60_000L

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
    val channels: SportsLiveChannelSummary? = null,
    /** Live now's More live view: [rows] are its games, by sport ([ScoreboardRow.section]). */
    val moreLive: Boolean = false
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
            // Live now (J2): its games come in importance order, kept inside each status group.
            val liveNow = tab == SportsLiveTab.LIVE_NOW
            val rows = if (liveNow && inputs.moreLive.isNotEmpty()) {
                // #215: More live's games ESPN confirmed it does not have read "Listed live" too; its listings that are
                // no game already show no LIVE unless ESPN has them in play (H), so this never touches them.
                moreLiveRows(inputs.items, inputs.moreLive, inputs.otherEvents, scores, inputs.clockMs)
                    .let { built -> withListedLive(built, scoresOn = scores.mode != null && scores.mode != ScoresMode.OFF, unmatched = inputs.unmatched) }
            } else if (isScoreboard(tab)) {
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
                    excitement = inputs.excitement,
                    keepOrder = liveNow
                ).map { row -> if (row.item.streamKey in inputs.noStreams) row.copy(noStreams = true) else row }
                    .let { built -> withListedLive(built, scoresOn = scores.mode != null && scores.mode != ScoresMode.OFF, unmatched = inputs.unmatched) }
                    .let { built -> if (tab == SportsLiveTab.UPCOMING) withBigGames(built, scores.scores, inputs.standings) else built }
                    .let { built -> if (liveNow) scoredLiveFirst(built) else built }
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
            return SportsLiveBoard(
                tab = tab, filter = inputs.filter, items = items, rows = rows, arts = arts, agenda = agenda, channels = channelView?.summary,
                moreLive = liveNow && inputs.moreLive.isNotEmpty()
            )
        }
    }
}

/**
 * Live now's live games with a score of ours first (live.24, audit A6): inside the LIVE group, a game whose row shows no
 * score (unmatched, or listed live only) moves below the scored ones; each part keeps its importance order. Under Scores
 * Off no row has a score, so nothing moves; under Hide a hidden score still counts as scored.
 */
internal fun scoredLiveFirst(rows: List<ScoreboardRow>): List<ScoreboardRow> =
    rows.sortedWith(compareBy<ScoreboardRow> { it.group.ordinal }.thenBy { if (it.group == ScoreboardGroup.LIVE && it.chip == null) 1 else 0 })

/**
 * The More live view's rows (user 2026-11-21): [items] in their order (group by group), each under its sport
 * ([ScoreboardRow.section] = its group key in [groupOf]). A listing that is no game ([otherEvents], H) says LIVE only when
 * ESPN has it in play; otherwise it shows no status (the addon's own LIVE is not trusted for these).
 */
internal fun moreLiveRows(
    items: List<SportsLiveItem>,
    groupOf: Map<String, String>,
    otherEvents: Set<String>,
    scores: SportsLiveBoardScores,
    nowMs: Long
): List<ScoreboardRow> {
    val index = items.withIndex().associate { (i, item) -> item.streamKey to i }
    return scoreboardRows(
        items = items,
        tab = SportsLiveTab.LIVE_NOW,
        scores = scores.scores,
        mode = scores.mode,
        revealed = scores.revealed,
        nowMs = nowMs,
        keepOrder = true
    ).sortedBy { index[it.item.streamKey] ?: Int.MAX_VALUE }.map { row ->
        val key = row.item.streamKey
        val status = row.status
        val unconfirmed = key in otherEvents && !(status is GameStatus.Live && status.event != null)
        val shown = if (unconfirmed) {
            row.copy(status = GameStatus.Upcoming(null, hasTimeOfDay = false), group = ScoreboardGroup.STARTING_SOON, chip = null, progress = null)
        } else {
            row
        }
        shown.copy(section = groupOf[key])
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

    private var scope: Any? = null

    /**
     * What of the spoiler protection a kept board depends on (live.23 review S2): the saved / ended games and "Hide my
     * teams' scores". Not the whole [SpoilerProtection]: its clock changes on every read.
     */
    internal data class ProtectionScope(val games: Set<Pair<String, Long?>>, val myTeams: Boolean) {
        companion object {
            val NONE = ProtectionScope(emptySet(), false)
            fun of(protection: SpoilerProtection) =
                ProtectionScope(protection.games.mapTo(HashSet()) { it.eventId to it.endedAtMs }, protection.myTeams)
        }
    }

    /** Bumped each time the boards are dropped: a board built before it is never put back ([putIfCurrent]). */
    @get:Synchronized
    var generation = 0
        private set

    /**
     * The profile and settings every board was built for (#143): a profile switch, or a change of My sports, the followed
     * teams, the Scores setting, the hidden titles or the spoiler protection ([ProtectionScope]), drops every board, so
     * another profile's or setting's list, or a score just protected, never shows, not even for a moment.
     */
    @Synchronized
    fun scopeTo(next: Any) {
        if (scope != null && scope != next) clear()
        scope = next
    }

    @Synchronized
    fun clear() {
        entries.clear()
        generation++
    }

    /** [put], unless the boards were dropped since [builtIn] (a board built ahead for a profile no longer shown). */
    @Synchronized
    fun putIfCurrent(builtIn: Int, inputs: SportsLiveBoardInputs, board: SportsLiveBoard) {
        if (builtIn == generation) put(inputs, board)
    }

    /** True when a board is held for [tab] with [filter] and no Channels browse view. */
    @Synchronized
    fun has(tab: SportsLiveTab, filter: String?): Boolean = entries.containsKey(viewOf(tab, filter, null))

    companion object {
        const val MAX_VIEWS = 8

        // Live now's More live view is a view of its own: its board never shows (or lends rows) for the main list.
        fun viewOf(inputs: SportsLiveBoardInputs): String = viewOf(
            inputs.tab,
            inputs.filter,
            inputs.channelLists.browse?.kind?.name ?: MORE_LIVE_VIEW.takeIf { inputs.moreLive.isNotEmpty() || inputs.otherEvents.isNotEmpty() }
        )

        private const val MORE_LIVE_VIEW = "moreLive"

        private fun viewOf(tab: SportsLiveTab, filter: String?, browse: String?): String = "${tab.name}|${filter.orEmpty()}|${browse.orEmpty()}"
    }
}

/**
 * Upcoming's ESPN matches as last seen (live.23 RC, TV report: "coming from Channels back to Upcoming it loads, then
 * shifts to say Games to watch"). The screen tracks scores only for the tab on screen, so on the way back Upcoming's
 * games have no match until their feeds answer again; the board built in that gap lost its big games (Games to watch),
 * ESPN's start times and logos, and the next one put them back above the time slots. Each Upcoming card with no match
 * now is given its last pre-game one, so the board comes back as it was left and later builds only update rows.
 * Pre-game only: no score is ever kept (a game in play or over forgets its card), nothing under Scores Off, and a drop
 * of every board (profile or setting change, [SportsLiveBoardCache.generation]) forgets them all.
 */
internal class SportsLiveKnownMatches(private val max: Int = MAX_KEPT) {
    private val pre = object : LinkedHashMap<String, ScoreSnapshot>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ScoreSnapshot>?): Boolean = size > max
    }
    private var generation = 0

    /** Learns [scores]' pre-game matches, by card key, for the boards of [generation]. */
    @Synchronized
    fun learn(scores: Map<String, ScoreSnapshot>, generation: Int) {
        if (generation != this.generation) {
            pre.clear()
            this.generation = generation
        }
        for ((key, snapshot) in scores) {
            if (snapshot.state == ScoreState.PRE) pre[key] = snapshot else pre.remove(key)
        }
    }

    /** [inputs] with each Upcoming card that has no match now given its last pre-game one; any other board as it is. */
    @Synchronized
    fun fill(inputs: SportsLiveBoardInputs): SportsLiveBoardInputs {
        val scores = inputs.scores
        if (inputs.tab != SportsLiveTab.UPCOMING || scores.mode == null || scores.mode == ScoresMode.OFF || pre.isEmpty()) return inputs
        var filled: HashMap<String, ScoreSnapshot>? = null
        for (item in inputs.items) {
            val key = item.streamKey
            if (key in scores.scores) continue
            val known = pre[key] ?: continue
            val into = filled ?: HashMap(scores.scores).also { filled = it }
            into[key] = known
        }
        return filled?.let { inputs.copy(scores = scores.copy(scores = it)) } ?: inputs
    }

    companion object {
        /** Upcoming holds ~2,500 games on the user's My sports; room for all of them. */
        const val MAX_KEPT = 5_000
    }
}
