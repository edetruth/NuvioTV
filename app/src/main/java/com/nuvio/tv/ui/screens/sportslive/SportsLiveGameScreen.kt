package com.nuvio.tv.ui.screens.sportslive

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.scores.BoxScoreTeam
import com.nuvio.tv.core.sportslive.scores.GameHighlight
import com.nuvio.tv.core.sportslive.scores.GameSection
import com.nuvio.tv.core.sportslive.scores.GameStat
import com.nuvio.tv.core.sportslive.scores.GameSummary
import com.nuvio.tv.core.sportslive.scores.Performer
import com.nuvio.tv.core.sportslive.scores.ScoreSide
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.StandingsTable
import com.nuvio.tv.core.sportslive.scores.StatComparison
import com.nuvio.tv.core.sportslive.scores.scoreDetails
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.ui.components.LightweightCard
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.SourceStatusFilterChip
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.dpadRepeatThrottle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * The Game page (#18, `GamePage.dc.html`): a header panel (logos, names, records, the big score, the
 * status line, Watch replay / Reveal / Follow ‹team›), the section pill (Game · Box Score · Standings ·
 * Highlights, empty ones hidden) and the chosen section. ← → change sections from the body, ↑ ↓ scroll,
 * Back returns to where the page was opened. Highlights are listed, never played.
 */
@Composable
fun SportsLiveGameScreen(
    viewModel: SportsLiveGameViewModel = hiltViewModel(),
    playViewModel: SportsLivePlayViewModel = hiltViewModel(),
    onBack: () -> Unit,
    /** Opens a replay's Stream screen; the Boolean: the user picks there (no autoplay). */
    onPlay: (SportsLiveItem, Boolean) -> Unit,
    onPlayTarget: (SportsLiveZapTarget) -> Unit,
    onOpenDetail: (SportsLiveItem) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val currentOnBack by rememberUpdatedState(onBack)
    val event = state.event
    if (event == null) {
        // Nothing to show (the app was restored without the request): back to where it was opened.
        LaunchedEffect(Unit) { currentOnBack() }
        return
    }
    BackHandler { currentOnBack() }

    // Loads and refreshes only while the page is on screen.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, lifecycleEvent ->
            when (lifecycleEvent) {
                Lifecycle.Event.ON_START -> viewModel.setVisible(true)
                Lifecycle.Event.ON_STOP -> viewModel.setVisible(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.setVisible(false)
        }
    }
    SportsLivePlayHost(playViewModel = playViewModel, onOpenStreamScreen = onPlay, onPlayTarget = onPlayTarget)

    val revealFocusRequester = remember { FocusRequester() }
    val replayFocusRequester = remember { FocusRequester() }
    val followFocusRequester = remember { FocusRequester() }
    val sectionFocusRequester = remember { FocusRequester() }
    val bodyFocusRequester = remember { FocusRequester() }
    val sections = state.sections
    val section = state.section
    val showsScore = state.showsScore

    // First focus: Reveal while the score is hidden, else Watch replay, else Follow.
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching {
            when {
                !showsScore -> revealFocusRequester
                state.replay != null -> replayFocusRequester
                else -> followFocusRequester
            }.requestFocus()
        }
    }
    // Reveal goes away once pressed: focus moves to the next button.
    var revealPressed by remember { mutableStateOf(false) }
    LaunchedEffect(showsScore, revealPressed) {
        if (!showsScore || !revealPressed) return@LaunchedEffect
        revealPressed = false
        withFrameNanos { }
        runCatching { (if (state.replay != null) replayFocusRequester else followFocusRequester).requestFocus() }
    }
    // ← → in the body change section; focus then goes to the new section's first row.
    var refocusBody by remember { mutableStateOf(false) }
    LaunchedEffect(section, refocusBody) {
        if (!refocusBody) return@LaunchedEffect
        refocusBody = false
        repeat(2) { withFrameNanos { } }
        runCatching { bodyFocusRequester.requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
            .padding(horizontal = NuvioTheme.spacing.xxxl, vertical = NuvioTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
    ) {
        GameHeader(
            state = state,
            event = event,
            // No sections: nothing focusable below the header, so Down stays put.
            downFocusRequester = if (sections.isNotEmpty() && section != null) sectionFocusRequester else null,
            revealFocusRequester = revealFocusRequester,
            replayFocusRequester = replayFocusRequester,
            followFocusRequester = followFocusRequester,
            onReveal = {
                revealPressed = true
                viewModel.reveal()
            },
            onWatchReplay = { replay ->
                if (replay.apiType == "series") onOpenDetail(replay) else playViewModel.play(replay)
            },
            onToggleFollow = viewModel::toggleFollow
        )
        if (sections.isNotEmpty() && section != null) {
            GameSectionPill(
                sections = sections,
                selected = section,
                focusRequester = sectionFocusRequester,
                upFocusRequester = followFocusRequester,
                downFocusRequester = bodyFocusRequester,
                onSelect = viewModel::selectSection
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onPreviewKeyEvent { keyEvent ->
                    val native = keyEvent.nativeKeyEvent
                    val step = when (native.keyCode) {
                        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> -1
                        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> 1
                        else -> return@onPreviewKeyEvent false
                    }
                    if (native.action == AndroidKeyEvent.ACTION_DOWN && section != null) {
                        val next = sections.getOrNull(sections.indexOf(section) + step)
                        if (next != null) {
                            viewModel.selectSection(next)
                            refocusBody = true
                        }
                    }
                    true
                }
        ) {
            GameBody(state = state, section = section, entryFocusRequester = bodyFocusRequester, upFocusRequester = sectionFocusRequester)
        }
        Text(
            text = stringResource(R.string.sports_live_game_keys),
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextTertiary
        )
    }
}

/** One side of the header: from the summary when loaded, else from the event as opened. */
private data class HeaderSide(val short: String, val name: String, val logo: String?, val record: String?, val score: Int?)

private fun headerSides(summary: GameSummary?, event: ScoreSnapshot): Pair<HeaderSide, HeaderSide> {
    if (summary != null) {
        fun side(team: com.nuvio.tv.core.sportslive.scores.GameTeam) =
            HeaderSide(team.short, team.shortDisplayName ?: team.fullName, team.logo, team.record, team.score)
        return side(summary.away) to side(summary.home)
    }
    fun side(team: com.nuvio.tv.core.sportslive.scores.ScoreTeam) = HeaderSide(
        short = team.abbreviation ?: team.shortDisplayName ?: team.displayName.orEmpty(),
        name = team.shortDisplayName ?: team.displayName ?: team.name ?: team.abbreviation.orEmpty(),
        logo = team.logo,
        record = null,
        score = team.score
    )
    return side(event.away) to side(event.home)
}

@Composable
private fun GameHeader(
    state: SportsLiveGameUiState,
    event: ScoreSnapshot,
    downFocusRequester: FocusRequester?,
    revealFocusRequester: FocusRequester,
    replayFocusRequester: FocusRequester,
    followFocusRequester: FocusRequester,
    onReveal: () -> Unit,
    onWatchReplay: (SportsLiveItem) -> Unit,
    onToggleFollow: (ScoreSide) -> Unit
) {
    val summary = state.summary
    val showsScore = state.showsScore
    val (away, home) = headerSides(summary, event)
    val gameState = summary?.state ?: event.state
    val live = gameState == ScoreState.IN
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NuvioTheme.radii.lg))
            .background(NuvioTheme.colors.BackgroundCard)
            .padding(horizontal = NuvioTheme.spacing.xxl, vertical = NuvioTheme.spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HeaderTeam(away, showRecord = showsScore, alignEnd = false)
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
        ) {
            val awayScore = away.score
            val homeScore = home.score
            val played = gameState == ScoreState.IN || gameState == ScoreState.POST
            Text(
                text = when {
                    !showsScore -> stringResource(R.string.sports_live_results_score_hidden)
                    played && awayScore != null && homeScore != null ->
                        stringResource(R.string.sports_live_details_sides, awayScore.toString(), homeScore.toString())
                    else -> stringResource(R.string.sports_live_art_vs)
                },
                style = if (showsScore) {
                    MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")
                } else {
                    MaterialTheme.typography.headlineSmall
                },
                color = if (showsScore) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary,
                maxLines = 1
            )
            GameStatusLine(summary = summary, event = event, showsScore = showsScore, live = live)
        }
        HeaderTeam(home, showRecord = showsScore, alignEnd = true)
        Column(
            modifier = Modifier.widthIn(min = 220.dp, max = 320.dp),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            val buttonModifier = Modifier
                .fillMaxWidth()
                .focusProperties { down = downFocusRequester ?: FocusRequester.Cancel }
            if (!showsScore) {
                SportsLivePaneButton(onClick = onReveal, modifier = buttonModifier.focusRequester(revealFocusRequester)) {
                    Text(stringResource(R.string.sports_live_game_reveal), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            state.replay?.let { replay ->
                SportsLivePaneButton(onClick = { onWatchReplay(replay) }, modifier = buttonModifier.focusRequester(replayFocusRequester)) {
                    Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(NuvioTheme.spacing.xs))
                    Text(stringResource(R.string.sports_live_game_watch_replay), maxLines = 1)
                }
            }
            FollowButton(
                name = away.name,
                followed = state.awayFollowed,
                onClick = { onToggleFollow(ScoreSide.AWAY) },
                modifier = buttonModifier.focusRequester(followFocusRequester)
            )
            FollowButton(
                name = home.name,
                followed = state.homeFollowed,
                onClick = { onToggleFollow(ScoreSide.HOME) },
                modifier = buttonModifier
            )
        }
    }
}

@Composable
private fun FollowButton(name: String, followed: Boolean, onClick: () -> Unit, modifier: Modifier) {
    SportsLivePaneButton(onClick = onClick, modifier = modifier) {
        if (followed) {
            Icon(imageVector = Icons.Rounded.Star, contentDescription = null, tint = GAME_STAR_COLOR, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.xs))
        }
        Text(
            text = stringResource(if (followed) R.string.sports_live_game_following else R.string.sports_live_follow, name),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun HeaderTeam(side: HeaderSide, showRecord: Boolean, alignEnd: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md), verticalAlignment = Alignment.CenterVertically) {
        if (alignEnd) TeamNameBlock(side, showRecord, alignEnd = true)
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(NuvioTheme.colors.SurfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (side.logo != null) {
                AsyncImage(model = side.logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(60.dp))
            } else {
                Text(side.short, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = NuvioTheme.colors.TextPrimary)
            }
        }
        if (!alignEnd) TeamNameBlock(side, showRecord, alignEnd = false)
    }
}

@Composable
private fun TeamNameBlock(side: HeaderSide, showRecord: Boolean, alignEnd: Boolean) {
    Column(horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start, modifier = Modifier.widthIn(max = 240.dp)) {
        Text(side.short, style = MaterialTheme.typography.labelLarge, color = NuvioTheme.colors.TextTertiary, maxLines = 1)
        Text(
            text = side.name,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        // The record after the game would tell how it ended: hidden with the score.
        if (showRecord && side.record != null) {
            Text(side.record, style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary, maxLines = 1)
        }
    }
}

/** "Final · Sat 15 Nov · TD Garden", the live clock in red, or the local kickoff. The ending hides with the score. */
@Composable
private fun GameStatusLine(summary: GameSummary?, event: ScoreSnapshot, showsScore: Boolean, live: Boolean) {
    val separator = stringResource(R.string.sports_live_game_info_separator)
    val finalNote = stringResource(R.string.sports_live_chip_final_note)
    val postponed = stringResource(R.string.sports_live_chip_postponed)
    val state = summary?.state ?: event.state
    val clock = summary?.clockText ?: event.clockText
    val startMs = summary?.startMs ?: event.startMs
    val locale = Locale.getDefault()
    val date = remember(startMs, state, locale) {
        startMs?.let { start ->
            val zoned = Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault())
            val day = DateTimeFormatter.ofPattern("EEE d MMM", locale).format(zoned)
            if (state == ScoreState.PRE) day + " " + DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(zoned) else day
        }
    }
    val status = when {
        !showsScore -> null
        state == ScoreState.IN -> clock
        state == ScoreState.POST -> clock ?: finalNote
        state == ScoreState.OFF -> clock ?: postponed
        else -> null
    }
    Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        if (status != null && live) {
            Text(status, style = MaterialTheme.typography.titleMedium, color = NuvioTheme.colors.Error, maxLines = 1)
        }
        val rest = listOfNotNull(status.takeUnless { live }, date, summary?.venue)
        val prefix = if (status != null && live && rest.isNotEmpty()) separator else ""
        if (rest.isNotEmpty()) {
            Text(
                text = prefix + rest.joinToString(separator),
                style = MaterialTheme.typography.titleMedium,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun GameSectionPill(
    sections: List<GameSection>,
    selected: GameSection,
    focusRequester: FocusRequester,
    upFocusRequester: FocusRequester,
    downFocusRequester: FocusRequester,
    onSelect: (GameSection) -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(NuvioTheme.radii.full))
            .background(NuvioTheme.colors.BackgroundCard)
            .padding(NuvioTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
    ) {
        sections.forEach { section ->
            val isSelected = section == selected
            SourceStatusFilterChip(
                name = stringResource(section.labelRes()),
                isSelected = isSelected,
                onFocusSelect = { onSelect(section) },
                onClick = { onSelect(section) },
                modifier = Modifier
                    .then(if (isSelected) Modifier.focusRequester(focusRequester) else Modifier)
                    .focusProperties {
                        up = upFocusRequester
                        down = downFocusRequester
                    }
            )
        }
    }
}

private fun GameSection.labelRes(): Int = when (this) {
    GameSection.GAME -> R.string.sports_live_game_section_game
    GameSection.BOX_SCORE -> R.string.sports_live_game_section_box_score
    GameSection.STANDINGS -> R.string.sports_live_game_section_standings
    GameSection.HIGHLIGHTS -> R.string.sports_live_game_section_highlights
}

@Composable
private fun GameBody(state: SportsLiveGameUiState, section: GameSection?, entryFocusRequester: FocusRequester, upFocusRequester: FocusRequester) {
    val summary = state.summary
    when {
        !state.showsScore -> GameMessage(stringResource(R.string.sports_live_game_hidden))
        state.status == GameLoadStatus.FAILED && summary == null -> GameMessage(stringResource(R.string.sports_live_game_unavailable))
        summary == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
        section == null -> GameMessage(stringResource(R.string.sports_live_game_no_stats))
        else -> androidx.compose.runtime.key(section) {
            val listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .focusProperties { onEnter = { entryFocusRequester.requestFocus() } }
                    .dpadRepeatThrottle(),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
            ) {
                val first = Modifier
                    .focusRequester(entryFocusRequester)
                    .focusProperties { up = upFocusRequester }
                when (section) {
                    GameSection.GAME -> gameTab(summary, first)
                    GameSection.BOX_SCORE -> boxScoreTab(summary.boxScore, first)
                    GameSection.STANDINGS -> standingsTab(summary.standings, first)
                    GameSection.HIGHLIGHTS -> highlightsTab(summary.highlights, first)
                }
            }
        }
    }
}

@Composable
private fun GameMessage(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium, color = NuvioTheme.colors.TextSecondary, modifier = Modifier.padding(NuvioTheme.spacing.sm))
}

/** A focusable panel or row of the body: focus is what scrolls the list with the D-pad. OK does nothing. */
@Composable
private fun GamePanel(
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    padding: androidx.compose.ui.unit.Dp = NuvioTheme.spacing.lg,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    LightweightCard(
        onClick = {},
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        containerColor = if (highlighted) NuvioTheme.colors.SurfaceVariant else NuvioTheme.colors.BackgroundCard,
        focusedBorder = Border(border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape)
    ) {
        Column(modifier = Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm), content = content)
    }
}

private fun LazyListScope.gameTab(summary: GameSummary, first: Modifier) {
    val details = summary.scoreDetails()?.takeIf { it.periods.isNotEmpty() || it.soccer }
    val performers = summary.performers
    val stats = summary.teamStats
    // The body's first focus: the first panel shown.
    val firstPanel = when {
        details != null -> 0
        performers.isNotEmpty() -> 1
        else -> 2
    }
    fun focus(panel: Int): Modifier = if (panel == firstPanel) first else Modifier
    item(key = "game") {
        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl)) {
            if (details != null || performers.isNotEmpty()) {
                Column(modifier = Modifier.weight(0.45f), verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)) {
                    if (details != null) {
                        GamePanel(modifier = focus(0)) {
                            SportsLiveScoreDetails(details, showScoreline = false)
                        }
                    }
                    if (performers.isNotEmpty()) {
                        GamePanel(modifier = focus(1)) {
                            SectionTitle(stringResource(R.string.sports_live_game_top_performers))
                            performers.chunked(2).forEach { pair ->
                                Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
                                    pair.forEach { PerformerCard(it, Modifier.weight(1f)) }
                                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
            if (stats.isNotEmpty()) {
                GamePanel(modifier = Modifier.weight(0.55f).then(focus(2))) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        SectionTitle(summary.away.short)
                        Spacer(Modifier.weight(1f))
                        SectionTitle(stringResource(R.string.sports_live_game_team_stats))
                        Spacer(Modifier.weight(1f))
                        SectionTitle(summary.home.short)
                    }
                    stats.forEach { StatBar(it) }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text.uppercase(Locale.getDefault()),
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 2.sp),
        color = NuvioTheme.colors.TextTertiary,
        maxLines = 1
    )
}

@Composable
private fun PerformerCard(performer: Performer, modifier: Modifier) {
    val separator = stringResource(R.string.sports_live_game_info_separator)
    val statFormat = stringResource(R.string.sports_live_game_performer_stat)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(NuvioTheme.radii.md))
            .background(NuvioTheme.colors.SurfaceVariant)
            .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm)
    ) {
        Text(performer.team, style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextTertiary, maxLines = 1)
        Text(
            performer.player,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = performer.stats.joinToString(separator) { stat ->
                stat.label?.let { String.format(statFormat, stat.value, it) } ?: stat.value
            },
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun StatBar(comparison: StatComparison) {
    val percent = stringResource(R.string.sports_live_game_percent)
    fun shown(text: String) = if (comparison.stat.percent && !text.endsWith("%")) String.format(percent, text) else text
    val awayBetter = comparison.better == ScoreSide.AWAY
    val homeBetter = comparison.better == ScoreSide.HOME
    Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                shown(comparison.away),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = if (awayBetter) FontWeight.Bold else FontWeight.Normal, fontFeatureSettings = "tnum"),
                color = NuvioTheme.colors.TextPrimary
            )
            Text(
                stringResource(comparison.stat.labelRes()),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextTertiary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Text(
                shown(comparison.home),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = if (homeBetter) FontWeight.Bold else FontWeight.Normal, fontFeatureSettings = "tnum"),
                color = NuvioTheme.colors.TextPrimary
            )
        }
        val (awayWeight, homeWeight) = statBarWeights(comparison.awayValue, comparison.homeValue)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                Modifier
                    .weight(awayWeight)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (awayBetter) STAT_BETTER_COLOR else STAT_OTHER_COLOR)
            )
            Box(
                Modifier
                    .weight(homeWeight)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (homeBetter) STAT_BETTER_COLOR else STAT_OTHER_COLOR)
            )
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

private val NAME_COLUMN = 240.dp
private val STAT_COLUMN = 72.dp

@Composable
private fun TableLine(name: String, values: List<String>, header: Boolean, bold: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val style = (if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium)
            .copy(fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, fontFeatureSettings = "tnum")
        val color = if (header) NuvioTheme.colors.TextTertiary else NuvioTheme.colors.TextPrimary
        Text(name, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(NAME_COLUMN))
        values.forEach { value ->
            Text(value, style = style, color = color, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.width(STAT_COLUMN))
        }
    }
}

private fun LazyListScope.boxScoreTab(teams: List<BoxScoreTeam>, first: Modifier) {
    var firstUsed = false
    teams.forEach { team ->
        team.tables.forEachIndexed { tableIndex, table ->
            item(key = "h|${team.side}|$tableIndex") {
                Column(modifier = Modifier.padding(top = NuvioTheme.spacing.sm)) {
                    SectionTitle(listOfNotNull(team.team, table.title).joinToString(" "))
                    Box(Modifier.padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.xs)) {
                        TableLine(stringResource(R.string.sports_live_game_team), table.labels, header = true)
                    }
                }
            }
            table.rows.forEachIndexed { rowIndex, row ->
                val modifier = if (firstUsed) Modifier else first.also { firstUsed = true }
                item(key = "r|${team.side}|$tableIndex|$rowIndex") {
                    GamePanel(modifier = modifier, padding = NuvioTheme.spacing.xs) {
                        Box(Modifier.padding(horizontal = NuvioTheme.spacing.sm)) {
                            TableLine(listOfNotNull(row.player, row.position).joinToString(" "), row.values, header = false)
                        }
                    }
                }
            }
            table.totals?.let { totals ->
                item(key = "t|${team.side}|$tableIndex") {
                    Box(Modifier.padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.xs)) {
                        TableLine(stringResource(R.string.sports_live_game_totals), totals, header = false, bold = true)
                    }
                }
            }
        }
    }
}

private fun LazyListScope.standingsTab(tables: List<StandingsTable>, first: Modifier) {
    var firstUsed = false
    tables.forEachIndexed { tableIndex, table ->
        item(key = "h|$tableIndex") {
            Column(modifier = Modifier.padding(top = NuvioTheme.spacing.sm)) {
                table.title?.let { SectionTitle(it) }
                Box(Modifier.padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.xs)) {
                    TableLine(stringResource(R.string.sports_live_game_team), table.columns, header = true)
                }
            }
        }
        table.rows.forEachIndexed { rowIndex, row ->
            val modifier = if (firstUsed) Modifier else first.also { firstUsed = true }
            item(key = "r|$tableIndex|$rowIndex") {
                // The game's two teams stand out.
                GamePanel(modifier = modifier, highlighted = row.inGame, padding = NuvioTheme.spacing.xs) {
                    Box(Modifier.padding(horizontal = NuvioTheme.spacing.sm)) {
                        TableLine(row.team, row.values, header = false, bold = row.inGame)
                    }
                }
            }
        }
    }
}

private fun LazyListScope.highlightsTab(highlights: List<GameHighlight>, first: Modifier) {
    item(key = "note") {
        Text(
            stringResource(R.string.sports_live_game_highlights_note),
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextTertiary
        )
    }
    items(highlights.size, key = { "v|$it" }) { index ->
        val highlight = highlights[index]
        GamePanel(modifier = if (index == 0) first else Modifier, padding = NuvioTheme.spacing.sm) {
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .width(160.dp)
                        .height(90.dp)
                        .clip(RoundedCornerShape(NuvioTheme.radii.sm))
                        .background(NuvioTheme.colors.PosterFallback)
                ) {
                    highlight.thumbnail?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        highlight.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = NuvioTheme.colors.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    highlight.durationSeconds?.let { seconds ->
                        Text(
                            stringResource(R.string.sports_live_game_duration, seconds / 60, seconds % 60),
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.colors.TextTertiary
                        )
                    }
                }
            }
        }
    }
}

private val GAME_STAR_COLOR = Color(0xFFFFC107)
private val STAT_BETTER_COLOR = Color(0xFF9AC2FF)
private val STAT_OTHER_COLOR = Color(0xFF4B5A6E)
