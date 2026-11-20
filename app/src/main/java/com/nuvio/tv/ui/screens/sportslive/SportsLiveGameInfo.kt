package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.GameInfo
import com.nuvio.tv.core.sportslive.scores.GameInfoBuilder
import com.nuvio.tv.core.sportslive.scores.GameSituations
import com.nuvio.tv.core.sportslive.scores.GameSummary
import com.nuvio.tv.core.sportslive.scores.GameSummaryLoad
import com.nuvio.tv.core.sportslive.scores.GameSummaryRepository
import com.nuvio.tv.core.sportslive.scores.LiveScoresRepository
import com.nuvio.tv.core.sportslive.scores.Performer
import com.nuvio.tv.core.sportslive.scores.ScoreChips
import com.nuvio.tv.core.sportslive.scores.ScoreDetails
import com.nuvio.tv.core.sportslive.scores.ScoreDetailsBuilder
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Colors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The in-player game info overlay (#139), on demand only: the full player's game, or in dual the game with
 * sound. It reads the delayed scoreboard snapshot the screen already polls and the Game page's summary for
 * the top performers, through the same cached repository (a live game's summary at most every 30 s, a
 * final's once). Nothing is requested or collected while it is closed. It closes by itself after
 * [HIDE_AFTER_MS].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SportsLiveGameInfoViewModel internal constructor(
    private val scores: StateFlow<Map<String, ScoreSnapshot>>,
    mode: Flow<ScoresMode>,
    private val loadSummary: suspend (EspnLeague, String) -> GameSummaryLoad,
    private val hideAfterMs: Long = HIDE_AFTER_MS
) : ViewModel() {

    @Inject
    constructor(repo: LiveScoresRepository, summaries: GameSummaryRepository) : this(
        scores = repo.scores,
        mode = repo.mode,
        // Parsing a summary stays off the main thread (#107).
        loadSummary = { league, eventId -> withContext(Dispatchers.Default) { summaries.load(league, eventId) } }
    )

    private val mode: StateFlow<ScoresMode?> = mode.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val item = MutableStateFlow<SportsLiveItem?>(null)
    private val summary = MutableStateFlow<GameSummary?>(null)
    private var hideJob: Job? = null
    private var summaryJob: Job? = null

    /** What the overlay shows; null while it is closed (or its game lost its match). */
    val info: StateFlow<GameInfo?> = item.flatMapLatest { shownItem ->
        if (shownItem == null) flowOf(null)
        else combine(scores, this.mode, summary) { scores, mode, summary ->
            GameInfoBuilder.of(shownItem, scores[shownItem.streamKey], mode, summary)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val shown: Boolean get() = item.value != null

    /** True when [item] has something to show now: Scores not Off and a strict ESPN match. */
    fun available(item: SportsLiveItem?): Boolean =
        GameInfoBuilder.available(item, item?.let { scores.value[it.streamKey] }, mode.value)

    /** Opens the overlay on [item]; false (nothing happens) when it has nothing to show. */
    fun show(item: SportsLiveItem): Boolean {
        if (!available(item)) return false
        showOn(item)
        return true
    }

    /**
     * While open, the overlay moves to [item] (dual: the sound moved to the other game) and its time
     * starts again; it closes when [item] has nothing to show. Nothing happens while it is closed.
     */
    fun follow(item: SportsLiveItem?) {
        val current = this.item.value ?: return
        if (item?.streamKey == current.streamKey) return
        if (item == null || !available(item)) hide() else showOn(item)
    }

    /** Closes the overlay; false when it was not open. */
    fun hide(): Boolean {
        if (item.value == null) return false
        item.value = null
        hideJob?.cancel()
        summaryJob?.cancel()
        return true
    }

    private fun showOn(item: SportsLiveItem) {
        this.item.value = item
        hideJob?.cancel()
        hideJob = viewModelScope.launch {
            delay(hideAfterMs)
            hide()
        }
        val event = ScoreChips.matchedEvent(item, scores.value[item.streamKey]) ?: return
        if (summary.value?.eventId != event.eventId) summary.value = null
        summaryJob?.cancel()
        summaryJob = viewModelScope.launch {
            val load = loadSummary(event.league, event.eventId)
            if (load is GameSummaryLoad.Loaded && load.summary.eventId == event.eventId) summary.value = load.summary
        }
    }

    companion object {
        /** The overlay closes by itself this long after it opened (or moved to the other game). */
        const val HIDE_AFTER_MS = 8_000L
    }
}

/**
 * The overlay's card: the scoreline with the clock (red while live), the linescore, the situation line and
 * one line per top performer. [compact]: stacked for a dual pane's column, else one slim bottom row.
 */
@Composable
internal fun SportsLiveGameInfoOverlay(info: GameInfo?, compact: Boolean, modifier: Modifier = Modifier) {
    // The last content stays while the card fades out.
    var last by remember { mutableStateOf(info) }
    LaunchedEffect(info) { if (info != null) last = info }
    AnimatedVisibility(visible = info != null, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        val shown = info ?: last ?: return@AnimatedVisibility
        val shape = RoundedCornerShape(SportsLiveTokens.Alert.Radius)
        val padded = Modifier
            .background(Colors.Surface.copy(alpha = SportsLiveTokens.Alert.FILL_ALPHA), shape)
            .padding(horizontal = if (compact) 12.dp else 16.dp, vertical = if (compact) 10.dp else 12.dp)
        if (compact) {
            // A dual pane's column: no heading over the performers, to fit under the tiles.
            Column(modifier = padded, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                InfoScore(shown)
                InfoLinescore(shown)
                InfoLeaders(shown.leaders, heading = false)
            }
        } else {
            Row(modifier = padded, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { InfoScore(shown) }
                InfoLinescore(shown)
                if (shown.leaders.isNotEmpty()) Column(modifier = Modifier.width(300.dp)) { InfoLeaders(shown.leaders, heading = true) }
            }
        }
    }
}

@Composable
private fun InfoScore(info: GameInfo) {
    val chipStrings = rememberScoreChipStrings()
    when (val details = info.details) {
        is ScoreDetails.Hidden -> InfoLine(chipStrings.hidden, Colors.TextSecondary)
        is ScoreDetails.Status -> InfoLine(details.text ?: chipStrings.postponed, Colors.TextSecondary)
        is ScoreDetails.Shown -> {
            Text(
                ScoreDetailsBuilder.scorelineText(details, rememberScoreDetailsStrings()),
                style = SportsLiveTokens.style(SportsLiveTokens.Type.RowScore),
                color = Colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // A live game with no clock from ESPN shows no status (never "Final").
            val status = details.status ?: chipStrings.finalNote.takeUnless { details.live }
            if (status != null) Row(verticalAlignment = Alignment.CenterVertically) {
                if (details.live) {
                    Box(Modifier.size(6.dp).background(Colors.LiveRed, CircleShape))
                }
                Text(
                    status,
                    style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta),
                    color = if (details.live) Colors.LiveText else Colors.TextSecondary,
                    maxLines = 1,
                    modifier = Modifier.padding(start = if (details.live) 6.dp else 0.dp)
                )
            }
            val situation = GameSituations.situationLine(info.situation, info.event, rememberGameSituationStrings())
            if (situation.isNotEmpty()) InfoLine(situation, Colors.TextSecondary)
        }
    }
}

/** The linescore; in a narrow column only its latest periods, so the total always shows. */
@Composable
private fun InfoLinescore(info: GameInfo) {
    val details = info.details as? ScoreDetails.Shown ?: return
    if (details.periods.isEmpty()) return
    BoxWithConstraints {
        // The table: a 56 dp label column, then 32 dp per period and for the total.
        val fits = ((maxWidth.value - 56f) / 32f).toInt() - 1
        val kept = if (fits in 1 until details.periods.size) latestPeriods(details, fits) else details
        LinescoreTable(kept, rememberScoreDetailsStrings())
    }
}

/** [details] with only its last [count] periods. */
internal fun latestPeriods(details: ScoreDetails.Shown, count: Int): ScoreDetails.Shown = details.copy(
    periods = details.periods.takeLast(count),
    first = details.first.copy(linescores = details.first.linescores.takeLast(count)),
    second = details.second.copy(linescores = details.second.linescores.takeLast(count))
)

@Composable
private fun InfoLeaders(leaders: List<Performer>, heading: Boolean) {
    if (leaders.isEmpty()) return
    val separator = stringResource(R.string.sports_live_game_info_separator)
    val statFormat = stringResource(R.string.sports_live_game_performer_stat)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (heading) Text(
            stringResource(R.string.sports_live_game_top_performers).uppercase(),
            style = SportsLiveTokens.style(SportsLiveTokens.Type.SectionHeader),
            color = Colors.TextMuted,
            maxLines = 1
        )
        leaders.forEach { performer ->
            InfoLine(performerLine(performer, separator, statFormat), Colors.TextPrimary)
        }
    }
}

/** "BOS · J. Tatum · 28 PTS · 9 REB": the team, the player and every category they lead, on one line. */
internal fun performerLine(performer: Performer, separator: String, statFormat: String): String =
    (listOf(performer.team, performer.player) + performer.stats.map { stat ->
        stat.label?.let { String.format(statFormat, stat.value, it) } ?: stat.value
    }).filter { it.isNotBlank() }.joinToString(separator)

@Composable
private fun InfoLine(text: String, color: Color) {
    Text(
        text,
        style = SportsLiveTokens.style(SportsLiveTokens.Type.PanelLine),
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}
