package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.scores.BoxScoreRow
import com.nuvio.tv.core.sportslive.scores.GameHighlight
import com.nuvio.tv.core.sportslive.scores.GameSection
import com.nuvio.tv.core.sportslive.scores.GameStat
import com.nuvio.tv.core.sportslive.scores.GameSummary
import com.nuvio.tv.core.sportslive.scores.Performer
import com.nuvio.tv.core.sportslive.scores.PlayerCardTarget
import com.nuvio.tv.core.sportslive.scores.PlayerCards
import com.nuvio.tv.core.sportslive.scores.RankDisplay
import com.nuvio.tv.core.sportslive.scores.StandingsRow
import com.nuvio.tv.core.sportslive.scores.TeamPages
import com.nuvio.tv.core.sportslive.scores.TeamRef
import com.nuvio.tv.core.sportslive.scores.ScoreDetails
import com.nuvio.tv.core.sportslive.scores.ScoreDetailsBuilder
import com.nuvio.tv.core.sportslive.scores.ScoreSide
import com.nuvio.tv.core.sportslive.scores.StandingsTable
import com.nuvio.tv.core.sportslive.scores.StatComparison
import com.nuvio.tv.core.sportslive.scores.scoreDetails
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveButton
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Colors
import com.nuvio.tv.ui.util.dpadRepeatThrottle

/** The key a highlight card is focused back by, after its clip played. */
internal fun gameHighlightKey(id: String): String = "hl|$id"

/** Watch all highlights' key, for the same. */
internal const val GAME_ALL_HIGHLIGHTS_KEY = "hl|*"

/** A top performer's key (by its place in the list), for focus on the way back from its Player card. */
private fun performerKey(index: Int): String = "pf|$index"

/** A Box score row's key (#180), for the same. */
private fun boxRowKey(side: ScoreSide, table: Int, row: Int): String = "bx|$side|$table|$row"

/**
 * The Game page's body: the hidden card, a load state, or the chosen [section] as one list whose every row
 * takes focus. [returnKey] names the element [returnFocus] goes on (Back from a clip or a Player card).
 */
@Composable
internal fun GameBody(
    state: SportsLiveGameUiState,
    section: GameSection?,
    entryFocus: FocusRequester,
    upFocus: FocusRequester,
    returnKey: String?,
    returnFocus: FocusRequester,
    onPlayHighlights: (List<GameHighlight>) -> Unit,
    /** A performer's or Box score player's Player card: its return key and target. */
    onOpenPlayer: (String, PlayerCardTarget) -> Unit,
    /** A Standings row's Team page (BUG11). */
    onOpenTeam: (TeamRef) -> Unit = {},
    /** Try again on the failure card (#97). */
    onRetry: () -> Unit = {},
    /** The failure card's Try again: the header's Down and the first focus while it shows (review R2). */
    failureFocus: FocusRequester = remember { FocusRequester() },
    /** #461: Try again takes focus as it appears ([SportsLivePageFocus.failureTakesFocus]). */
    failureTakesFocus: Boolean = true,
    /** #461: Try again gained or lost focus. */
    onFailureFocused: (Boolean) -> Unit = {},
    /** A college league's AP ranks by team id, for every Standings row (BUG18). */
    standingRanks: Map<String, Int> = emptyMap()
) {
    val summary = state.shownSummary
    when {
        // The Scores setting and spoiler protection not read yet (a frame or two): neither the hidden card nor stats.
        !state.settled -> Unit
        // An upcoming game's Preview is spoiler-neutral (#171): it shows with the score hidden too.
        // #474: an upcoming game still loading its Preview (or failed) is no hidden game: skeleton or Try again below.
        !state.showsScore && section != GameSection.PREVIEW && !(state.showsPreview && state.shownSummary == null) ->
            HiddenGameBody(hasReplay = state.replay != null)
        state.showsFailure ->
            SportsLivePageFailure(
                stringResource(R.string.sports_live_game_failed_title), state.retrying, onRetry, focus = failureFocus,
                takeFocus = failureTakesFocus, onFocused = onFailureFocused
            )
        summary == null -> SportsLiveGameSkeleton()
        section == null -> SportsLivePageMessage(stringResource(R.string.sports_live_game_no_stats))
        else -> androidx.compose.runtime.key(section) {
            // Standings open on the game's teams (BUG18).
            val listState = rememberLazyListState(
                initialFirstVisibleItemIndex = if (section == GameSection.STANDINGS) standingsEntry(summary.standings).scrollIndex else 0
            )
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    // A D-pad move into the list lands on its first row; a focus request for a row (Back from a
                    // clip or a Player card) is let through.
                    .focusProperties {
                        onEnter = { if (requestedFocusDirection != FocusDirection.Enter) entryFocus.requestFocus() }
                        // ← → never leave the body for a tab: that would switch section (#134).
                        onExit = {
                            if (requestedFocusDirection == FocusDirection.Left || requestedFocusDirection == FocusDirection.Right) cancelFocusChange()
                        }
                    }
                    .dpadRepeatThrottle(),
                verticalArrangement = Arrangement.spacedBy(SportsLiveTokens.Size.RowGap),
                // Room round the rows for the focus lift and ring, and under the last one (N10).
                contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 24.dp)
            ) {
                val first = Modifier
                    .focusRequester(entryFocus)
                    .focusProperties { up = upFocus }
                fun Modifier.returning(key: String): Modifier = if (key == returnKey) focusRequester(returnFocus) else this
                when (section) {
                    GameSection.PREVIEW -> previewTab(summary, first, state.homeFirst)
                    GameSection.GAME -> gameTab(summary, first, state.homeFirst, gamePlayerTarget(state), { modifier, index -> modifier.returning(performerKey(index)) }) { index, target ->
                        onOpenPlayer(performerKey(index), target)
                    }
                    GameSection.BOX_SCORE -> boxScoreTab(summary, first, gameBoxScoreTarget(state), { modifier, key -> modifier.returning(key) }) { key, target ->
                        onOpenPlayer(key, target)
                    }
                    GameSection.STANDINGS -> standingsTab(
                        summary.standings,
                        first,
                        ranks = standingRanks + listOf(summary.home, summary.away).mapNotNull { team ->
                            val id = team.id ?: return@mapNotNull null
                            RankDisplay.rank(summary.league, team.rank)?.let { id to it }
                        }.toMap(),
                        teamPage = { row ->
                            state.event?.let { event -> TeamPages.refOfStanding(event, row.teamId, row.team) }?.let { ref -> { onOpenTeam(ref) } }
                        }
                    )
                    GameSection.HIGHLIGHTS -> highlightsTab(summary.highlights, first, { modifier, key -> modifier.returning(key) }, onPlayHighlights)
                }
            }
        }
    }
}

/**
 * Board ⑧: scores hidden. A calm card over greyed placeholders of the Game section (Surface at 35 %), not one
 * grey line on black (F9). It takes no focus: the action row (Watch replay / Reveal score) holds it.
 */
@Composable
private fun HiddenGameBody(hasReplay: Boolean) {
    Box(modifier = Modifier.fillMaxSize().padding(top = 6.dp, bottom = 24.dp)) {
        val ghost = Colors.Surface.copy(alpha = 0.35f)
        val shape = SportsLiveTokens.Radius.RowShape
        Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(modifier = Modifier.weight(0.45f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.fillMaxWidth().height(64.dp).clip(shape).background(ghost))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f).height(PERFORMER_HEIGHT).clip(shape).background(ghost))
                    Box(Modifier.weight(1f).height(PERFORMER_HEIGHT).clip(shape).background(ghost))
                }
            }
            Column(
                modifier = Modifier.weight(0.55f).fillMaxHeight().clip(shape).background(ghost).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                repeat(5) { Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Colors.Divider)) }
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .widthIn(max = 380.dp)
                .clip(shape)
                .background(Colors.SurfaceRaised)
                .padding(horizontal = 24.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                stringResource(R.string.sports_live_game_hidden),
                style = SportsLiveTokens.style(SportsLiveTokens.Type.PanelTitle),
                color = Colors.TextPrimary,
                textAlign = TextAlign.Center
            )
            Text(
                stringResource(R.string.sports_live_game_hidden_reveal_note),
                style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta),
                color = Colors.TextSecondary,
                textAlign = TextAlign.Center
            )
            if (hasReplay) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.sports_live_game_hidden_replay_note),
                    style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta),
                    color = Colors.TextMuted,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * Top performers in the header's team order (live.20 TV QA: they were always away first, so a page naming the
 * home team first showed them the other way round): the first side's players in the left column, row by row.
 */
internal fun performersInCardOrder(performers: List<Performer>, homeFirst: Boolean): List<Performer> {
    val (firstSide, secondSide) = inCardOrder(ScoreSide.AWAY, ScoreSide.HOME, homeFirst)
    val first = performers.filter { it.side == firstSide }
    val second = performers.filter { it.side == secondSide }
    return (0 until maxOf(first.size, second.size)).flatMap { listOfNotNull(first.getOrNull(it), second.getOrNull(it)) }
}

/** The Player card a top performer opens (#63), or null: pro leagues with an ESPN athlete id only. */
internal fun gamePlayerTarget(state: SportsLiveGameUiState): (Performer) -> PlayerCardTarget? = { performer ->
    state.summary?.let { summary ->
        val team = if (performer.side == ScoreSide.HOME) summary.home else summary.away
        PlayerCards.targetFor(performer, summary.league, state.event?.leagueSlug, team.fullName)
    }
}

/** The Player card a Box score row opens (#180), or null: as a top performer's. */
internal fun gameBoxScoreTarget(state: SportsLiveGameUiState): (ScoreSide, BoxScoreRow) -> PlayerCardTarget? = { side, row ->
    state.summary?.let { summary ->
        val team = if (side == ScoreSide.HOME) summary.home else summary.away
        PlayerCards.targetFor(row, summary.league, state.event?.leagueSlug, team.fullName)
    }
}

/**
 * Board ④–⑥: the linescore card and Top performers 2 × 2 on the left, Team stats on the right. Each is a focus
 * stop (a performer with a Player card is its own, OK opens it), so ↓ reaches all of them.
 */
private fun LazyListScope.gameTab(
    summary: GameSummary,
    first: Modifier,
    homeFirst: Boolean,
    playerTarget: (Performer) -> PlayerCardTarget?,
    /** [Modifier] plus what focuses the performer at [Int] back on the way back from its Player card. */
    returning: (Modifier, Int) -> Modifier,
    onOpenPlayer: (Int, PlayerCardTarget) -> Unit
) {
    val details = summary.scoreDetails()?.takeIf { it.periods.isNotEmpty() || it.soccer }
    val performers = performersInCardOrder(summary.performers, homeFirst)
    val stats = summary.teamStats
    val firstPanel = when {
        details != null -> 0
        performers.isNotEmpty() -> 1
        else -> 2
    }
    fun focus(panel: Int): Modifier = if (panel == firstPanel) first else Modifier
    item(key = "game") {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            if (details != null || performers.isNotEmpty()) {
                Column(modifier = Modifier.weight(0.45f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (details != null) {
                        SportsLivePageSurface(modifier = Modifier.fillMaxWidth().then(focus(0)), container = Colors.Surface, lift = false) {
                            GameLinescore(details, homeFirst)
                        }
                    }
                    if (performers.isNotEmpty()) {
                        val targets = performers.map(playerTarget)
                        val firstOpenable = targets.indexOfFirst { it != null }.takeIf { it >= 0 } ?: 0
                        Column(
                            // A performer's focus shows the whole block, its heading included (N10).
                            modifier = Modifier.fillMaxWidth().wholeInViewOnFocus(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            SportsLivePageHeading(stringResource(R.string.sports_live_game_top_performers))
                            performers.indices.chunked(2).forEach { pair ->
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    pair.forEach { index ->
                                        val target = targets[index]
                                        PerformerCard(
                                            performer = performers[index],
                                            modifier = Modifier
                                                .weight(1f)
                                                .then(if (index == firstOpenable) focus(1) else Modifier)
                                                .then(returning(Modifier, index)),
                                            onClick = target?.let { open -> { onOpenPlayer(index, open) } }
                                        )
                                    }
                                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
            if (stats.isNotEmpty()) {
                SportsLivePageSurface(
                    modifier = Modifier.weight(0.55f).then(focus(2)),
                    container = Colors.Surface,
                    lift = false
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val (left, right) = inCardOrder(summary.away.short, summary.home.short, homeFirst)
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            SportsLivePageHeading(left, color = Colors.TextSecondary)
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                SportsLivePageHeading(stringResource(R.string.sports_live_game_team_stats))
                            }
                            SportsLivePageHeading(right, color = Colors.TextSecondary)
                        }
                        stats.forEach { StatBar(it, homeFirst) }
                    }
                }
            }
        }
    }
}

/** Board ④: the linescore fills its card; 34 px tabular numerals, the total bold. Soccer adds its scorers. */
@Composable
private fun GameLinescore(details: ScoreDetails.Shown, homeFirst: Boolean) {
    val strings = rememberScoreDetailsStrings()
    val total = stringResource(R.string.sports_live_details_period_total)
    val (top, bottom) = inCardOrder(details.first, details.second, homeFirst)
    val heads = details.periods.map { ScoreDetailsBuilder.periodText(it, strings) }
    val cell = SportsLiveTokens.style(LINESCORE_CELL)
    val tag = SportsLiveTokens.style(SportsLiveTokens.Type.Tag)
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (heads.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Spacer(Modifier.width(LINESCORE_LABEL))
                (heads + total).forEach { head ->
                    Text(head, style = tag, color = Colors.TextMuted, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.weight(1f))
                }
            }
            listOf(top, bottom).forEach { side ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        side.short,
                        style = SportsLiveTokens.style(SportsLiveTokens.Type.RowName).sportsLiveBold(),
                        color = Colors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(LINESCORE_LABEL)
                    )
                    side.linescores.forEach { value ->
                        Text(value?.toString() ?: strings.emptyPeriod, style = cell, color = Colors.TextPrimary, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.weight(1f))
                    }
                    Text(
                        side.score?.toString().orEmpty(),
                        style = cell.sportsLiveBold(),
                        color = Colors.TextPrimary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        if (details.soccer) {
            listOf(top, bottom).forEach { side ->
                ScoreDetailsBuilder.scorersText(side, strings)?.let { scorers ->
                    Text(
                        text = stringResource(R.string.sports_live_details_side_scorers, side.short, scorers),
                        style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta),
                        color = Colors.TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** Board ⑤: a fixed 136 px card; the team as a tag, the name a 30 px headline, the stat line 24 px over 2 lines at most. */
@Composable
private fun PerformerCard(performer: Performer, modifier: Modifier, onClick: (() -> Unit)?) {
    val separator = stringResource(R.string.sports_live_game_info_separator)
    val statFormat = stringResource(R.string.sports_live_game_performer_stat)
    val content: @Composable () -> Unit = {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            SportsLivePageHeading(performer.team, color = Colors.TextMuted)
            Text(
                performer.player,
                style = SportsLiveTokens.style(SportsLiveTokens.Type.RowName).sportsLiveBold(),
                color = Colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = performer.stats.joinToString(separator) { stat -> stat.label?.let { String.format(statFormat, stat.value, it) } ?: stat.value },
                style = SportsLiveTokens.style(PERFORMER_STAT),
                color = Colors.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    if (onClick == null) {
        // No Player card: not a focus stop (the linescore and stats cards carry the scroll).
        Box(modifier = modifier.height(PERFORMER_HEIGHT).clip(SportsLiveTokens.Radius.RowShape).background(Colors.Surface)) { content() }
    } else {
        SportsLivePageSurface(modifier = modifier.height(PERFORMER_HEIGHT), onClick = onClick, container = Colors.Surface) { content() }
    }
}

@Composable
private fun StatBar(comparison: StatComparison, homeFirst: Boolean) {
    val percent = stringResource(R.string.sports_live_game_percent)
    fun shown(text: String) = if (comparison.stat.percent && !text.endsWith("%")) String.format(percent, text) else text
    // Left and right in the header's order (N10).
    val (leftSide, rightSide) = inCardOrder(ScoreSide.AWAY, ScoreSide.HOME, homeFirst)
    val leftBetter = comparison.better == leftSide
    val rightBetter = comparison.better == rightSide
    val (leftText, rightText) = inCardOrder(comparison.away, comparison.home, homeFirst)
    val (leftValue, rightValue) = inCardOrder(comparison.awayValue, comparison.homeValue, homeFirst)
    val value = SportsLiveTokens.style(SportsLiveTokens.Type.RowName).copy(fontFeatureSettings = "tnum")
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(shown(leftText), style = if (leftBetter) value.sportsLiveBold() else value, color = Colors.TextPrimary, maxLines = 1)
            Text(
                stringResource(comparison.stat.labelRes()),
                style = SportsLiveTokens.style(SportsLiveTokens.Type.Control),
                color = Colors.TextSecondary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
            )
            Text(shown(rightText), style = if (rightBetter) value.sportsLiveBold() else value, color = Colors.TextPrimary, maxLines = 1)
        }
        val (leftWeight, rightWeight) = statBarWeights(leftValue, rightValue)
        Row(modifier = Modifier.fillMaxWidth().height(3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.weight(leftWeight).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(if (leftBetter) Colors.StatBetter else Colors.StatOther))
            Box(Modifier.weight(rightWeight).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(if (rightBetter) Colors.StatBetter else Colors.StatOther))
        }
    }
}

/** Each side's share of a stat bar; even when a value is missing, zero or negative. */
internal fun statBarWeights(away: Double?, home: Double?): Pair<Float, Float> {
    if (away == null || home == null || away < 0 || home < 0 || away + home <= 0) return 1f to 1f
    // A side at zero keeps a sliver, so the bar never vanishes (weights must be positive).
    val min = (away + home) * 0.02
    return maxOf(away, min).toFloat() to maxOf(home, min).toFloat()
}

private fun GameStat.labelRes(): Int = when (this) {
    GameStat.FIELD_GOAL_PCT -> R.string.sports_live_stat_fg_pct
    GameStat.THREE_POINT_PCT -> R.string.sports_live_stat_three_pct
    GameStat.REBOUNDS -> R.string.sports_live_stat_rebounds
    GameStat.ASSISTS -> R.string.sports_live_stat_assists
    GameStat.TURNOVERS -> R.string.sports_live_stat_turnovers
    GameStat.TOTAL_YARDS -> R.string.sports_live_stat_total_yards
    GameStat.PASSING_YARDS -> R.string.sports_live_stat_passing_yards
    GameStat.RUSHING_YARDS -> R.string.sports_live_stat_rushing_yards
    GameStat.FIRST_DOWNS -> R.string.sports_live_stat_first_downs
    GameStat.POSSESSION_TIME -> R.string.sports_live_stat_possession_time
    GameStat.SHOTS, GameStat.SOCCER_SHOTS -> R.string.sports_live_stat_shots
    GameStat.FACEOFF_PCT -> R.string.sports_live_stat_faceoff_pct
    GameStat.POWER_PLAY_GOALS -> R.string.sports_live_stat_power_play_goals
    GameStat.HITS -> R.string.sports_live_stat_hits
    GameStat.BLOCKED_SHOTS -> R.string.sports_live_stat_blocked_shots
    GameStat.PENALTY_MINUTES -> R.string.sports_live_stat_penalty_minutes
    GameStat.POSSESSION -> R.string.sports_live_stat_possession
    GameStat.SHOTS_ON_TARGET -> R.string.sports_live_stat_shots_on_target
    GameStat.CORNERS -> R.string.sports_live_stat_corners
    GameStat.FOULS -> R.string.sports_live_stat_fouls
}

/** A table line's cells: the name takes what the stat cells, [widths] wide each, leave. */
@Composable
private fun RowScope.TableCells(name: String, values: List<String>, style: TextStyle, color: androidx.compose.ui.graphics.Color, widths: List<Dp>) {
    Text(name, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    values.forEachIndexed { index, value ->
        Text(value, style = style, color = color, maxLines = 1, softWrap = false, textAlign = TextAlign.Center, modifier = Modifier.width(widths.getOrElse(index) { widths.lastOrNull() ?: MIN_TABLE_COLUMN }))
    }
}

/**
 * Each column's width (BUG1: a Totals "28-48" was cut to "28-4" in a fixed 44 dp column): the widest of its head
 * and values at [CELL_CHAR_DP] a character plus [CELL_PAD_DP], never under [minDp]. One rule for every sport
 * ("28-48", "22/31", "21:34").
 */
internal fun tableColumnWidthsDp(columns: List<List<String>>, minDp: Int): List<Int> =
    columns.map { values -> maxOf(minDp, (values.maxOfOrNull { it.trim().length } ?: 0) * CELL_CHAR_DP + CELL_PAD_DP) }

/** [labels] with every row's values (and [totals]) as columns, for [tableColumnWidthsDp]. */
internal fun tableColumns(labels: List<String>, rows: List<List<String>>, totals: List<String>? = null): List<List<String>> =
    labels.indices.map { i -> listOf(labels[i]) + rows.mapNotNull { it.getOrNull(i) } + listOfNotNull(totals?.getOrNull(i)) }

internal const val CELL_CHAR_DP = 9
internal const val CELL_PAD_DP = 6

private fun widthsOf(columns: List<List<String>>, min: Dp): List<Dp> = tableColumnWidthsDp(columns, min.value.toInt()).map { it.dp }

/** A table's column heads, lined up with its rows' cells (the rows' 12 dp inner padding). */
@Composable
private fun TableHeads(first: String, labels: List<String>, widths: List<Dp>) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val style = SportsLiveTokens.style(SportsLiveTokens.Type.Tag)
        TableCells(first, labels, style, Colors.TextMuted, widths)
    }
}

/**
 * Board ⑥ (F8): "New England · Passing", the "Player" heads, then a focusable 56 px row per player and the totals.
 * OK on a player with a Player card opens it (#180); every row stays a focus stop either way, so ↓ is unchanged.
 */
private fun LazyListScope.boxScoreTab(
    summary: GameSummary,
    first: Modifier,
    playerTarget: (ScoreSide, BoxScoreRow) -> PlayerCardTarget?,
    /** [Modifier] plus what focuses the row with this key back on the way back from its Player card. */
    returning: (Modifier, String) -> Modifier,
    onOpenPlayer: (String, PlayerCardTarget) -> Unit
) {
    var firstUsed = false
    summary.boxScore.forEach { team ->
        // "Milwaukee Bucks · Passing": the team's full name, not the feed's abbreviation.
        val teamName = (if (team.side == ScoreSide.HOME) summary.home else summary.away).displayName ?: team.team
        team.tables.forEachIndexed { tableIndex, table ->
            val widths = widthsOf(tableColumns(table.labels, table.rows.map { it.values }, table.totals), BOX_COLUMN)
            item(key = "h|${team.side}|$tableIndex") {
                val separator = stringResource(R.string.sports_live_game_info_separator)
                Column(modifier = Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        listOfNotNull(teamName, table.title).joinToString(separator),
                        style = SportsLiveTokens.style(SportsLiveTokens.Type.RowName).sportsLiveBold(),
                        color = Colors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    TableHeads(stringResource(R.string.sports_live_game_player), table.labels, widths)
                }
            }
            table.rows.forEachIndexed { rowIndex, row ->
                val key = boxRowKey(team.side, tableIndex, rowIndex)
                val modifier = returning(if (firstUsed) Modifier else first.also { firstUsed = true }, key)
                val target = playerTarget(team.side, row)
                item(key = "r|${team.side}|$tableIndex|$rowIndex") {
                    SportsLivePageRow(
                        height = PAGE_TABLE_ROW,
                        modifier = modifier,
                        onClick = target?.let { open -> { onOpenPlayer(key, open) } } ?: {},
                        lift = false
                    ) {
                        TableCells(listOfNotNull(row.player, row.position).joinToString(" "), row.values, tableStyle(), Colors.TextPrimary, widths)
                    }
                }
            }
            table.totals?.let { totals ->
                item(key = "t|${team.side}|$tableIndex") {
                    SportsLivePageRow(height = PAGE_TABLE_ROW, container = Colors.Surface, lift = false) {
                        TableCells(stringResource(R.string.sports_live_game_totals), totals, tableStyle().sportsLiveBold(), Colors.TextPrimary, widths)
                    }
                }
            }
        }
    }
}

@Composable
private fun tableStyle(): TextStyle = SportsLiveTokens.style(TABLE_CELL)

/**
 * How the standings tables sit: two (each team's division or conference, when they differ) side by
 * side, so both show without scrolling; one alone full width; more go two by two.
 */
internal fun standingsColumns(tables: List<StandingsTable>): List<List<StandingsTable>> = tables.chunked(2)

/**
 * Where the Standings tab opens (BUG18): the first row of one of the game's teams ([group], [table], [row]) takes
 * the entry focus, and the list starts at [scrollIndex] so that row is on screen (a team 12th of 16 was off it).
 */
internal data class StandingsEntry(val group: Int, val table: Int, val row: Int, val scrollIndex: Int)

/** Rows from the top of a group the list shows without scrolling, under its heads. */
internal const val STANDINGS_ROWS_ON_SCREEN = 5

/**
 * [StandingsEntry] for [tables] laid out as [standingsTab] does (each group: one heads item, then one item per row):
 * the game's team highest in its table, in the first group that has one; the top when none is marked. The list
 * stays at the group's heads while that row is among its first [STANDINGS_ROWS_ON_SCREEN], else starts two rows
 * above it.
 */
internal fun standingsEntry(tables: List<StandingsTable>): StandingsEntry {
    var item = 0
    standingsColumns(tables).forEachIndexed { groupIndex, group ->
        val hit = group.withIndex()
            .mapNotNull { (tableIndex, table) -> table.rows.indexOfFirst { it.inGame }.takeIf { it >= 0 }?.let { tableIndex to it } }
            .minByOrNull { it.second }
        if (hit != null) {
            val (tableIndex, row) = hit
            val scroll = if (row < STANDINGS_ROWS_ON_SCREEN) item else item + 1 + row - 2
            return StandingsEntry(groupIndex, tableIndex, row, scroll)
        }
        item += 1 + (group.maxOfOrNull { it.rows.size } ?: 0)
    }
    return StandingsEntry(0, 0, 0, 0)
}

/**
 * Standings: focusable 56 px rows; the game's two teams on the selected tint, bold, with a ranked college team's
 * "#3" ([ranks] by ESPN team id). The entry focus is on [standingsEntry]'s row; OK opens the row's Team page
 * ([teamPage], BUG11: rows took focus and did nothing).
 */
private fun LazyListScope.standingsTab(
    tables: List<StandingsTable>,
    first: Modifier,
    ranks: Map<String, Int> = emptyMap(),
    teamPage: (StandingsRow) -> (() -> Unit)? = { null }
) {
    val entry = standingsEntry(tables)
    standingsColumns(tables).forEachIndexed { groupIndex, group ->
        val column = if (group.size == 1) STANDINGS_COLUMN else STANDINGS_PAIRED_COLUMN
        val widths = group.map { table -> widthsOf(tableColumns(table.columns, table.rows.map { it.values }), column) }
        item(key = "sh|$groupIndex") {
            Row(modifier = Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                group.forEachIndexed { tableIndex, table ->
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            table.title.orEmpty(),
                            style = SportsLiveTokens.style(SportsLiveTokens.Type.RowName).sportsLiveBold(),
                            color = Colors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        TableHeads(stringResource(R.string.sports_live_game_team), table.columns, widths[tableIndex])
                    }
                }
            }
        }
        val rowCount = group.maxOf { it.rows.size }
        for (rowIndex in 0 until rowCount) {
            item(key = "sr|$groupIndex|$rowIndex") {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    group.forEachIndexed { tableIndex, table ->
                        Box(modifier = Modifier.weight(1f)) {
                            table.rows.getOrNull(rowIndex)?.let { row ->
                                val isEntry = groupIndex == entry.group && tableIndex == entry.table && rowIndex == entry.row
                                val rank = row.teamId?.let(ranks::get)
                                SportsLivePageRow(
                                    height = PAGE_TABLE_ROW,
                                    modifier = if (isEntry) first else Modifier,
                                    onClick = teamPage(row) ?: {},
                                    container = if (row.inGame) Colors.SelectedTint else Colors.Row,
                                    lift = false
                                ) {
                                    TableCells(
                                        rank?.let { RankDisplay.label(it) + " " + row.team } ?: row.team,
                                        row.values,
                                        tableStyle().let { if (row.inGame) it.sportsLiveBold() else it },
                                        Colors.TextPrimary,
                                        widths[tableIndex]
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Highlights (#61): Watch all highlights (plays every clip in a row), then one row per clip, OK plays it.
 * Only reached once the score shows: clip titles and thumbnails give results away.
 */
private fun LazyListScope.highlightsTab(
    highlights: List<GameHighlight>,
    first: Modifier,
    returning: (Modifier, String) -> Modifier,
    onPlay: (List<GameHighlight>) -> Unit
) {
    item(key = "all") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SportsLiveButton(onClick = { onPlay(highlights) }, modifier = returning(first, GAME_ALL_HIGHLIGHTS_KEY)) {
                Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.sports_live_game_watch_all_highlights), style = SportsLiveTokens.style(SportsLiveTokens.Type.Button), maxLines = 1)
            }
            Text(
                stringResource(R.string.sports_live_game_highlights_note),
                style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta),
                color = Colors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    items(highlights.size, key = { "v|${highlights[it].id}" }) { index ->
        val highlight = highlights[index]
        HighlightRow(highlight = highlight, modifier = returning(Modifier, gameHighlightKey(highlight.id)), onClick = { onPlay(listOf(highlight)) })
    }
}

@Composable
private fun HighlightRow(highlight: GameHighlight, modifier: Modifier, onClick: () -> Unit) {
    SportsLivePageSurface(modifier = modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(THUMB_WIDTH)
                    .height(THUMB_HEIGHT)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Colors.SurfaceRaised)
            ) {
                highlight.thumbnail?.let {
                    AsyncImage(model = rememberSizedImage(it, THUMB_WIDTH, THUMB_HEIGHT), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Box(
                    modifier = Modifier.align(Alignment.Center).size(32.dp).clip(CircleShape).background(BADGE_FILL),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = null, tint = Colors.TextPrimary, modifier = Modifier.size(18.dp))
                }
                HighlightBadge(stringResource(R.string.sports_live_game_espn_highlight), Modifier.align(Alignment.TopStart))
                highlight.durationSeconds?.let { seconds ->
                    HighlightBadge(stringResource(R.string.sports_live_game_duration, seconds / 60, seconds % 60), Modifier.align(Alignment.BottomEnd))
                }
            }
            Text(
                highlight.title,
                style = SportsLiveTokens.style(SportsLiveTokens.Type.RowName),
                color = Colors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun HighlightBadge(text: String, modifier: Modifier) {
    Text(
        text,
        style = SportsLiveTokens.style(SportsLiveTokens.Type.Tag),
        color = Colors.TextSecondary,
        maxLines = 1,
        modifier = modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(SportsLiveTokens.Radius.Badge))
            .background(BADGE_FILL)
            .padding(horizontal = 4.dp)
    )
}

/** Linescore numerals: 34 px tabular. */
private val LINESCORE_CELL = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")
private val LINESCORE_LABEL = 56.dp
/** Performer stat line: 24 px. */
private val PERFORMER_STAT = SportsLiveTokens.Type.Control.copy(fontWeight = FontWeight.Normal)
/** Top performer cards: 136 px. */
private val PERFORMER_HEIGHT = 68.dp
/** Box score and Standings cells: 28 px tabular. */
private val TABLE_CELL = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal, fontFeatureSettings = "tnum")
private val BOX_COLUMN = 44.dp
private val MIN_TABLE_COLUMN = 40.dp
private val STANDINGS_COLUMN = 56.dp
private val STANDINGS_PAIRED_COLUMN = 40.dp
private val THUMB_WIDTH = 160.dp
private val THUMB_HEIGHT = 90.dp
private val BADGE_FILL = Colors.Background.copy(alpha = 0.85f)
