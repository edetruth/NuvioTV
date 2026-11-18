package com.nuvio.tv.ui.screens.sportslive

import android.widget.Toast
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
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
import com.nuvio.tv.core.sportslive.scores.PlayerCardTarget
import com.nuvio.tv.core.sportslive.scores.PlayerCards
import com.nuvio.tv.core.sportslive.scores.ScoreSide
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.alerts.GameReminders
import com.nuvio.tv.core.sportslive.scores.StandingsTable
import com.nuvio.tv.core.sportslive.scores.StatComparison
import com.nuvio.tv.core.sportslive.scores.TeamPages
import com.nuvio.tv.core.sportslive.scores.scoreDetails
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.ui.components.LightweightCard
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.SourceStatusFilterChip
import com.nuvio.tv.ui.screens.sportslive.dual.SportsLiveDualSessions
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
 * Back returns to where the page was opened. Highlights play in the app player (#61): OK plays a clip,
 * Watch all highlights plays them all in a row.
 */
@Composable
fun SportsLiveGameScreen(
    viewModel: SportsLiveGameViewModel = hiltViewModel(),
    playViewModel: SportsLivePlayViewModel = hiltViewModel(),
    remindersViewModel: SportsLiveRemindersViewModel = hiltViewModel(),
    onBack: () -> Unit,
    /** Opens a replay's Stream screen; the Boolean: the user picks there (no autoplay). */
    onPlay: (SportsLiveItem, Boolean) -> Unit,
    onPlayTarget: (SportsLiveZapTarget) -> Unit,
    onOpenDetail: (SportsLiveItem) -> Unit,
    /** Plays the first clip of a highlights playlist just started (SportsLiveHighlights). */
    onPlayHighlight: (HighlightClipPlay) -> Unit,
    /** Opens dual screen on the session just put in [SportsLiveDualSessions] (#57). */
    onOpenDual: () -> Unit = {},
    /** Opens the Team page (#62) on the request just put in [SportsLiveTeamRequests]. */
    onOpenTeam: () -> Unit = {},
    /** Opens the Player card just put in [SportsLivePlayerCardRequests] (#63). */
    onOpenPlayerCard: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
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

    val headerFocusRequesters = remember { GameHeaderButton.entries.associateWith { FocusRequester() } }
    val sectionFocusRequester = remember { FocusRequester() }
    val bodyFocusRequester = remember { FocusRequester() }
    val sections = state.sections
    val section = state.section
    val showsScore = state.showsScore
    // Remind me (#44): an upcoming game opened from its card.
    val reminders by remindersViewModel.state.collectAsStateWithLifecycle()
    val remindCard = state.card?.takeIf { event.state == ScoreState.PRE && GameReminders.canRemind(it, System.currentTimeMillis()) }
    val headerRows = gameHeaderRows(
        showsScore = showsScore,
        hasReplay = state.replay != null,
        homeFirst = state.homeFirst,
        canRemind = remindCard != null,
        hasWatchWith = state.offersWatchWith
    )

    // Back from highlights: focus returns to the card played, or to Watch all highlights.
    var returnToHighlights by rememberSaveable { mutableStateOf(false) }
    var returnHighlightId by rememberSaveable { mutableStateOf<String?>(null) }
    val highlightFocusRequester = remember { FocusRequester() }
    // First focus: Reveal while the score is hidden, else Watch replay, else Follow.
    LaunchedEffect(Unit) {
        withFrameNanos { }
        val highlightsShown = returnToHighlights && section == GameSection.HIGHLIGHTS
        val cardShown = highlightsShown && state.summary?.highlights.orEmpty().any { it.id == returnHighlightId }
        returnToHighlights = false
        if (highlightsShown) withFrameNanos { }
        // A card scrolled out of the list has no focus target: Watch all highlights instead.
        if (cardShown && runCatching { highlightFocusRequester.requestFocus() }.isSuccess) return@LaunchedEffect
        runCatching {
            if (highlightsShown) bodyFocusRequester.requestFocus()
            else headerFocusRequesters.getValue(gameHeaderFirstFocus(headerRows)).requestFocus()
        }
    }
    // Reveal goes away once pressed: focus moves to the next button.
    var revealPressed by remember { mutableStateOf(false) }
    LaunchedEffect(showsScore, revealPressed) {
        if (!showsScore || !revealPressed) return@LaunchedEffect
        revealPressed = false
        withFrameNanos { }
        runCatching { headerFocusRequesters.getValue(gameHeaderFirstFocus(headerRows)).requestFocus() }
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
            rows = headerRows,
            focusRequesters = headerFocusRequesters,
            onReveal = {
                revealPressed = true
                viewModel.reveal()
            },
            onWatchReplay = { replay ->
                if (replay.apiType == "series") onOpenDetail(replay) else playViewModel.play(replay)
            },
            onWatchWith = {
                val session = viewModel.watchWithSession()
                if (session == null) {
                    Toast.makeText(context, context.getString(R.string.sports_live_dual_no_pair), Toast.LENGTH_SHORT).show()
                } else {
                    SportsLiveDualSessions.start(session)
                    onOpenDual()
                }
            },
            onToggleFollow = viewModel::toggleFollow,
            reminded = remindCard != null && reminders.isReminded(remindCard),
            onToggleReminder = { remindCard?.let(remindersViewModel::toggle) },
            // OK on a team (logo and name): its Team page, for a pro league with ESPN's team id only.
            onOpenTeam = { side ->
                TeamPages.refOf(event, side)?.let { ref ->
                    SportsLiveTeamRequests.open(SportsLiveTeamRequest(ref))
                    onOpenTeam()
                }
            }
        )
        if (sections.isNotEmpty() && section != null) {
            GameSectionPill(
                sections = sections,
                selected = section,
                focusRequester = sectionFocusRequester,
                upFocusRequester = headerFocusRequesters.getValue(headerRows.last().first()),
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
            GameBody(
                state = state,
                section = section,
                entryFocusRequester = bodyFocusRequester,
                upFocusRequester = sectionFocusRequester,
                highlightFocus = returnHighlightId to highlightFocusRequester,
                onPlayHighlights = { clips ->
                    SportsLiveHighlights.start(clips)?.let { clip ->
                        returnToHighlights = true
                        // One card: back on it; Watch all: back on the button.
                        returnHighlightId = clips.singleOrNull()?.id
                        onPlayHighlight(clip)
                    }
                },
                onOpenPlayer = { target ->
                    SportsLivePlayerCardRequests.open(SportsLivePlayerCardRequest(target, spoilerSafe = state.spoilerSafe))
                    onOpenPlayerCard()
                }
            )
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
        // The short name ("Celtics", as the design): the summary's, else the scoreboard's for the same side.
        fun side(team: com.nuvio.tv.core.sportslive.scores.GameTeam, listed: com.nuvio.tv.core.sportslive.scores.ScoreTeam) =
            HeaderSide(team.short, team.shortDisplayName ?: listed.shortDisplayName ?: team.fullName, team.logo, team.record, team.score)
        return side(summary.away, event.away) to side(summary.home, event.home)
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
    /** The buttons as laid out ([gameHeaderRows]); only the last row's Down goes to the sections. */
    rows: List<List<GameHeaderButton>>,
    focusRequesters: Map<GameHeaderButton, FocusRequester>,
    onReveal: () -> Unit,
    onWatchReplay: (SportsLiveItem) -> Unit,
    onWatchWith: () -> Unit,
    onToggleFollow: (ScoreSide) -> Unit,
    reminded: Boolean = false,
    onToggleReminder: () -> Unit = {},
    onOpenTeam: (ScoreSide) -> Unit = {}
) {
    val summary = state.summary
    val showsScore = state.showsScore
    val (away, home) = headerSides(summary, event)
    // In the order of the card the page came from (N10): away first unless the card named home first.
    val (first, second) = inCardOrder(away, home, state.homeFirst)
    val gameState = summary?.state ?: event.state
    val live = gameState == ScoreState.IN
    // Middle and buttons have fixed widths, measured first: the teams share what is left, so a long
    // name wraps or ends in "…" and never squeezes the score and status line out.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NuvioTheme.radii.lg))
            .background(NuvioTheme.colors.BackgroundCard)
            .padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val (firstSide, secondSide) = inCardOrder(ScoreSide.AWAY, ScoreSide.HOME, state.homeFirst)
        HeaderTeamSlot(TeamPages.refOf(event, firstSide) != null, { onOpenTeam(firstSide) }, Modifier.weight(1f)) {
            HeaderTeam(first, showRecord = showsScore, alignEnd = false, modifier = Modifier.fillMaxWidth())
        }
        Column(
            modifier = Modifier.width(HEADER_MIDDLE_WIDTH),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
        ) {
            val firstScore = first.score
            val secondScore = second.score
            val played = gameState == ScoreState.IN || gameState == ScoreState.POST
            Text(
                text = when {
                    !showsScore -> stringResource(R.string.sports_live_results_score_hidden)
                    played && firstScore != null && secondScore != null ->
                        stringResource(R.string.sports_live_details_sides, firstScore.toString(), secondScore.toString())
                    else -> stringResource(R.string.sports_live_art_vs)
                },
                style = if (showsScore) {
                    MaterialTheme.typography.displayMedium.sportsLiveBold().copy(fontFeatureSettings = "tnum")
                } else {
                    MaterialTheme.typography.headlineSmall
                },
                color = if (showsScore) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary,
                maxLines = 1,
                softWrap = false
            )
            GameStatusLine(summary = summary, event = event, showsScore = showsScore, live = live)
        }
        HeaderTeamSlot(TeamPages.refOf(event, secondSide) != null, { onOpenTeam(secondSide) }, Modifier.weight(1f)) {
            HeaderTeam(second, showRecord = showsScore, alignEnd = true, modifier = Modifier.fillMaxWidth())
        }
        // A grid as the design: Reveal and Watch replay each on a row, then Follow ‹away› · Follow ‹home›.
        Column(
            modifier = Modifier.width(HEADER_BUTTONS_WIDTH),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                    row.forEach { button ->
                        val modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequesters.getValue(button))
                            // Down within the grid finds the row below; the last row's Down: the sections.
                            .then(if (gameHeaderDownLeaves(rows, button)) Modifier.focusProperties { down = downFocusRequester ?: FocusRequester.Cancel } else Modifier)
                        when (button) {
                            GameHeaderButton.REVEAL -> SportsLivePaneButton(onClick = onReveal, modifier = modifier) {
                                Text(stringResource(R.string.sports_live_game_reveal), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            GameHeaderButton.REPLAY -> state.replay?.let { replay ->
                                SportsLivePaneButton(onClick = { onWatchReplay(replay) }, modifier = modifier) {
                                    Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(NuvioTheme.spacing.xs))
                                    Text(stringResource(R.string.sports_live_game_watch_replay), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            GameHeaderButton.WATCH_WITH -> SportsLivePaneButton(onClick = onWatchWith, modifier = modifier) {
                                Text(stringResource(R.string.sports_live_dual_watch_with), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            GameHeaderButton.REMIND -> SportsLivePaneButton(onClick = onToggleReminder, modifier = modifier) {
                                Icon(imageVector = Icons.Rounded.Notifications, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(NuvioTheme.spacing.xs))
                                Text(
                                    stringResource(if (reminded) R.string.sports_live_cancel_reminder else R.string.sports_live_remind_me),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            GameHeaderButton.FOLLOW_AWAY -> FollowButton(
                                name = away.name,
                                followed = state.awayFollowed,
                                onClick = { onToggleFollow(ScoreSide.AWAY) },
                                modifier = modifier
                            )
                            GameHeaderButton.FOLLOW_HOME -> FollowButton(
                                name = home.name,
                                followed = state.homeFollowed,
                                onClick = { onToggleFollow(ScoreSide.HOME) },
                                modifier = modifier
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A header team: focusable, OK opening its Team page (#62), when it has one; else plain. */
@Composable
private fun HeaderTeamSlot(hasTeamPage: Boolean, onOpen: () -> Unit, modifier: Modifier, content: @Composable () -> Unit) {
    if (!hasTeamPage) {
        Box(modifier = modifier) { content() }
        return
    }
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    LightweightCard(
        onClick = onOpen,
        modifier = modifier,
        shape = shape,
        containerColor = Color.Transparent,
        focusedBorder = Border(border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape)
    ) {
        Box(modifier = Modifier.padding(NuvioTheme.spacing.xs)) { content() }
    }
}

/** A Game page header button. */
internal enum class GameHeaderButton { REVEAL, REPLAY, WATCH_WITH, REMIND, FOLLOW_AWAY, FOLLOW_HOME }

/**
 * The header's buttons, row by row: Reveal (only while the score is hidden), Watch replay (only with a
 * replay), Watch with another game (only for a live game opened from Live now, #57), Remind me (only
 * for an upcoming game opened from its card, #44), then both Follow buttons side by side, which every
 * game has, in the header's team order.
 */
internal fun gameHeaderRows(
    showsScore: Boolean,
    hasReplay: Boolean,
    homeFirst: Boolean = false,
    canRemind: Boolean = false,
    hasWatchWith: Boolean = false
): List<List<GameHeaderButton>> = buildList {
    if (!showsScore) add(listOf(GameHeaderButton.REVEAL))
    if (hasReplay) add(listOf(GameHeaderButton.REPLAY))
    if (hasWatchWith) add(listOf(GameHeaderButton.WATCH_WITH))
    if (canRemind) add(listOf(GameHeaderButton.REMIND))
    add(inCardOrder(GameHeaderButton.FOLLOW_AWAY, GameHeaderButton.FOLLOW_HOME, homeFirst).toList())
}

/** [away] and [home] in the order the page shows them: home first when [homeFirst] (N10). */
internal fun <T> inCardOrder(away: T, home: T, homeFirst: Boolean): Pair<T, T> = if (homeFirst) home to away else away to home

/** The page's first focus: Reveal when shown, else Watch replay, else Follow ‹away›. */
internal fun gameHeaderFirstFocus(rows: List<List<GameHeaderButton>>): GameHeaderButton = rows.first().first()

/** Whether Down from [button] leaves the header for the sections (the last row only). */
internal fun gameHeaderDownLeaves(rows: List<List<GameHeaderButton>>, button: GameHeaderButton): Boolean =
    button in rows.last()

// At 1080p (960 dp) the teams keep about 140 dp each beside these.
private val HEADER_MIDDLE_WIDTH = 220.dp
private val HEADER_BUTTONS_WIDTH = 280.dp
private val HEADER_LOGO_SIZE = 56.dp

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

/** A side of the header, as the design: logo circle, then the name (bold) over the record; the home side mirrored. */
@Composable
private fun HeaderTeam(side: HeaderSide, showRecord: Boolean, alignEnd: Boolean, modifier: Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md, if (alignEnd) Alignment.End else Alignment.Start),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (alignEnd) TeamNameBlock(side, showRecord, alignEnd = true, modifier = Modifier.weight(1f, fill = false))
        Box(
            modifier = Modifier
                .size(HEADER_LOGO_SIZE)
                .clip(CircleShape)
                .background(NuvioTheme.colors.SurfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (side.logo != null) {
                AsyncImage(model = rememberSizedImage(side.logo, HEADER_LOGO_SIZE * 0.68f, HEADER_LOGO_SIZE * 0.68f), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(HEADER_LOGO_SIZE * 0.68f))
            } else {
                Text(side.short, style = MaterialTheme.typography.titleSmall.sportsLiveBold(), color = NuvioTheme.colors.TextPrimary, maxLines = 1)
            }
        }
        if (!alignEnd) TeamNameBlock(side, showRecord, alignEnd = false, modifier = Modifier.weight(1f, fill = false))
    }
}

@Composable
private fun TeamNameBlock(side: HeaderSide, showRecord: Boolean, alignEnd: Boolean, modifier: Modifier) {
    Column(horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start, modifier = modifier) {
        // A long name (soccer) wraps to a second line, then ends in "…".
        Text(
            text = side.name,
            style = MaterialTheme.typography.titleMedium.sportsLiveBold(),
            color = NuvioTheme.colors.TextPrimary,
            textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        // The record after the game would tell how it ended: hidden with the score.
        if (showRecord && side.record != null) {
            Text(side.record, style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary, maxLines = 1)
        }
    }
}

/** "Final · Sat 15 Nov" over "TD Garden", the live clock in red, or the local kickoff. The ending hides with the score. */
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
    // The venue on a line of its own, so a long one never pushes the status out of the middle column.
    Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        if (status != null && live) {
            Text(status, style = MaterialTheme.typography.titleMedium, color = NuvioTheme.colors.Error, maxLines = 1)
        }
        val rest = listOfNotNull(status.takeUnless { live }, date)
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
    summary?.venue?.let { venue ->
        Text(
            text = venue,
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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
private fun GameBody(
    state: SportsLiveGameUiState,
    section: GameSection?,
    entryFocusRequester: FocusRequester,
    upFocusRequester: FocusRequester,
    /** The card to focus on return from playing it, and its requester. */
    highlightFocus: Pair<String?, FocusRequester>,
    onPlayHighlights: (List<GameHighlight>) -> Unit,
    onOpenPlayer: (PlayerCardTarget) -> Unit
) {
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
                    // A D-pad move into the list lands on its first row; a focus request for a row (Back
                    // from a clip, to its card) is let through, or it would land on Watch all highlights.
                    .focusProperties {
                        onEnter = { if (requestedFocusDirection != FocusDirection.Enter) entryFocusRequester.requestFocus() }
                    }
                    .dpadRepeatThrottle(),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                // Room under the last panel, so its bottom edge and ring scroll fully on screen (N10).
                contentPadding = PaddingValues(bottom = NuvioTheme.spacing.xl)
            ) {
                val first = Modifier
                    .focusRequester(entryFocusRequester)
                    .focusProperties { up = upFocusRequester }
                when (section) {
                    GameSection.GAME -> gameTab(summary, first, state.homeFirst, gamePlayerTarget(state), onOpenPlayer)
                    GameSection.BOX_SCORE -> boxScoreTab(summary.boxScore, first)
                    GameSection.STANDINGS -> standingsTab(summary.standings, first)
                    GameSection.HIGHLIGHTS -> highlightsTab(summary.highlights, first, highlightFocus, onPlayHighlights)
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

/** The Player card a top performer opens (#63), or null: pro leagues with an ESPN athlete id only. */
internal fun gamePlayerTarget(state: SportsLiveGameUiState): (Performer) -> PlayerCardTarget? = { performer ->
    state.summary?.let { summary ->
        val team = if (performer.side == ScoreSide.HOME) summary.home else summary.away
        PlayerCards.targetFor(performer, summary.league, state.event?.leagueSlug, team.fullName)
    }
}

private fun LazyListScope.gameTab(
    summary: GameSummary,
    first: Modifier,
    homeFirst: Boolean,
    playerTarget: (Performer) -> PlayerCardTarget?,
    onOpenPlayer: (PlayerCardTarget) -> Unit
) {
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
                    val targets = performers.map(playerTarget)
                    if (targets.any { it != null }) {
                        // Each player with a card is its own focus stop: OK opens the Player card (#63).
                        val firstOpenable = targets.indexOfFirst { it != null }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                // A performer's focus shows the whole card, its bottom included (N10).
                                .wholeInViewOnFocus()
                                .clip(RoundedCornerShape(NuvioTheme.radii.md))
                                .background(NuvioTheme.colors.BackgroundCard)
                                .padding(NuvioTheme.spacing.lg),
                            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
                        ) {
                            SectionTitle(stringResource(R.string.sports_live_game_top_performers))
                            performers.indices.chunked(2).forEach { pair ->
                                Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
                                    pair.forEach { index ->
                                        val target = targets[index]
                                        PerformerCard(
                                            performer = performers[index],
                                            modifier = Modifier.weight(1f).then(if (index == firstOpenable) focus(1) else Modifier),
                                            onClick = target?.let { open -> { onOpenPlayer(open) } }
                                        )
                                    }
                                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    } else if (performers.isNotEmpty()) {
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
                    val (left, right) = inCardOrder(summary.away.short, summary.home.short, homeFirst)
                    Row(modifier = Modifier.fillMaxWidth()) {
                        SectionTitle(left)
                        Spacer(Modifier.weight(1f))
                        SectionTitle(stringResource(R.string.sports_live_game_team_stats))
                        Spacer(Modifier.weight(1f))
                        SectionTitle(right)
                    }
                    stats.forEach { StatBar(it, homeFirst) }
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

/** A top performer; with [onClick] a focus stop whose OK opens the Player card (#63). */
@Composable
private fun PerformerCard(performer: Performer, modifier: Modifier, onClick: (() -> Unit)? = null) {
    if (onClick == null) {
        Column(
            modifier = modifier
                .clip(RoundedCornerShape(NuvioTheme.radii.md))
                .background(NuvioTheme.colors.SurfaceVariant)
                .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm)
        ) { PerformerText(performer) }
        return
    }
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    LightweightCard(
        onClick = onClick,
        modifier = modifier,
        shape = shape,
        containerColor = NuvioTheme.colors.SurfaceVariant,
        focusedBorder = Border(border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape)
    ) {
        Column(modifier = Modifier.padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm)) { PerformerText(performer) }
    }
}

@Composable
private fun PerformerText(performer: Performer) {
    val separator = stringResource(R.string.sports_live_game_info_separator)
    val statFormat = stringResource(R.string.sports_live_game_performer_stat)
    Column {
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
private fun StatBar(comparison: StatComparison, homeFirst: Boolean) {
    val percent = stringResource(R.string.sports_live_game_percent)
    fun shown(text: String) = if (comparison.stat.percent && !text.endsWith("%")) String.format(percent, text) else text
    // "away" and "home" below are the left and right sides, in the header's order (N10).
    val (leftSide, rightSide) = inCardOrder(ScoreSide.AWAY, ScoreSide.HOME, homeFirst)
    val awayBetter = comparison.better == leftSide
    val homeBetter = comparison.better == rightSide
    val (leftText, rightText) = inCardOrder(comparison.away, comparison.home, homeFirst)
    val (leftValue, rightValue) = inCardOrder(comparison.awayValue, comparison.homeValue, homeFirst)
    Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                shown(leftText),
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
                shown(rightText),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = if (homeBetter) FontWeight.Bold else FontWeight.Normal, fontFeatureSettings = "tnum"),
                color = NuvioTheme.colors.TextPrimary
            )
        }
        val (awayWeight, homeWeight) = statBarWeights(leftValue, rightValue)
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
private val COMPACT_STAT_COLUMN = 48.dp

@Composable
private fun TableLine(name: String, values: List<String>, header: Boolean, bold: Boolean = false, compact: Boolean = false) {
    // Compact: half the page wide (two standings side by side), the name takes what the stats leave.
    Row(modifier = if (compact) Modifier.fillMaxWidth() else Modifier, verticalAlignment = Alignment.CenterVertically) {
        val style = (if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium)
            .copy(fontWeight = FontWeight.Normal, fontFeatureSettings = "tnum")
            .let { if (bold) it.sportsLiveBold() else it }
        val color = if (header) NuvioTheme.colors.TextTertiary else NuvioTheme.colors.TextPrimary
        Text(
            name,
            style = style,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (compact) Modifier.weight(1f) else Modifier.width(NAME_COLUMN)
        )
        values.forEach { value ->
            Text(
                value,
                style = style,
                color = color,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(if (compact) COMPACT_STAT_COLUMN else STAT_COLUMN)
            )
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

/**
 * How the standings tables sit: two (each team's division or conference, when they differ) side by
 * side, so both show without scrolling; one alone full width; more go two by two.
 */
internal fun standingsColumns(tables: List<StandingsTable>): List<List<StandingsTable>> = tables.chunked(2)

private fun LazyListScope.standingsTab(tables: List<StandingsTable>, first: Modifier) {
    var firstUsed = false
    standingsColumns(tables).forEachIndexed { groupIndex, group ->
        if (group.size == 1) {
            singleStandings(group.single(), groupIndex, if (firstUsed) Modifier else first.also { firstUsed = true })
        } else {
            pairedStandings(group, groupIndex, if (firstUsed) Modifier else first.also { firstUsed = true })
        }
    }
}

/** Two tables side by side, row by row; [first] goes on the left table's first row. */
private fun LazyListScope.pairedStandings(tables: List<StandingsTable>, groupIndex: Int, first: Modifier) {
    item(key = "ph|$groupIndex") {
        Row(modifier = Modifier.padding(top = NuvioTheme.spacing.sm), horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl)) {
            tables.forEach { table ->
                Column(modifier = Modifier.weight(1f)) {
                    table.title?.let { SectionTitle(it) }
                    Box(Modifier.padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.xs)) {
                        TableLine(stringResource(R.string.sports_live_game_team), table.columns, header = true, compact = true)
                    }
                }
            }
        }
    }
    val rowCount = tables.maxOf { it.rows.size }
    for (rowIndex in 0 until rowCount) {
        item(key = "pr|$groupIndex|$rowIndex") {
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl)) {
                tables.forEachIndexed { tableIndex, table ->
                    Box(modifier = Modifier.weight(1f)) {
                        table.rows.getOrNull(rowIndex)?.let { row ->
                            // The game's two teams stand out.
                            GamePanel(
                                modifier = if (tableIndex == 0 && rowIndex == 0) first else Modifier,
                                highlighted = row.inGame,
                                padding = NuvioTheme.spacing.xs
                            ) {
                                Box(Modifier.padding(horizontal = NuvioTheme.spacing.sm)) {
                                    TableLine(row.team, row.values, header = false, bold = row.inGame, compact = true)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.singleStandings(table: StandingsTable, tableIndex: Int, first: Modifier) {
    item(key = "h|$tableIndex") {
        Column(modifier = Modifier.padding(top = NuvioTheme.spacing.sm)) {
            table.title?.let { SectionTitle(it) }
            Box(Modifier.padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.xs)) {
                TableLine(stringResource(R.string.sports_live_game_team), table.columns, header = true)
            }
        }
    }
    table.rows.forEachIndexed { rowIndex, row ->
        val modifier = if (rowIndex == 0) first else Modifier
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

/**
 * Highlights (#61): Watch all highlights (plays every clip in a row), then one card per clip, OK plays it.
 * Only reached once the score shows: clip titles and thumbnails give results away.
 */
private fun LazyListScope.highlightsTab(
    highlights: List<GameHighlight>,
    first: Modifier,
    focus: Pair<String?, FocusRequester>,
    onPlay: (List<GameHighlight>) -> Unit
) {
    item(key = "all") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)) {
            SportsLivePaneButton(onClick = { onPlay(highlights) }, modifier = first) {
                Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(NuvioTheme.spacing.xs))
                Text(stringResource(R.string.sports_live_game_watch_all_highlights), maxLines = 1)
            }
            Text(
                stringResource(R.string.sports_live_game_highlights_note),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextTertiary
            )
        }
    }
    items(highlights.size, key = { "v|${highlights[it].id}" }) { index ->
        val highlight = highlights[index]
        HighlightCard(
            highlight = highlight,
            modifier = if (highlight.id == focus.first) Modifier.focusRequester(focus.second) else Modifier,
            onClick = { onPlay(listOf(highlight)) }
        )
    }
}

@Composable
private fun HighlightCard(highlight: GameHighlight, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    LightweightCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        containerColor = NuvioTheme.colors.BackgroundCard,
        focusedBorder = Border(border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape)
    ) {
        Row(
            modifier = Modifier.padding(NuvioTheme.spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(192.dp)
                    .height(108.dp)
                    .clip(RoundedCornerShape(NuvioTheme.radii.sm))
                    .background(NuvioTheme.colors.PosterFallback)
            ) {
                highlight.thumbnail?.let { AsyncImage(model = rememberSizedImage(it, 192.dp, 108.dp), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(HIGHLIGHT_BADGE_COLOR),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = null, tint = NuvioTheme.colors.TextPrimary, modifier = Modifier.size(22.dp))
                }
                HighlightBadge(stringResource(R.string.sports_live_game_espn_highlight), Modifier.align(Alignment.TopStart))
                highlight.durationSeconds?.let { seconds ->
                    HighlightBadge(stringResource(R.string.sports_live_game_duration, seconds / 60, seconds % 60), Modifier.align(Alignment.BottomEnd))
                }
            }
            Text(
                highlight.title,
                style = MaterialTheme.typography.titleMedium,
                color = NuvioTheme.colors.TextPrimary,
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
        style = MaterialTheme.typography.labelSmall,
        color = NuvioTheme.colors.TextSecondary,
        maxLines = 1,
        modifier = modifier
            .padding(NuvioTheme.spacing.xs)
            .clip(RoundedCornerShape(NuvioTheme.radii.sm))
            .background(HIGHLIGHT_BADGE_COLOR)
            .padding(horizontal = NuvioTheme.spacing.xs)
    )
}

private val GAME_STAR_COLOR = Color(0xFFFFC107)
private val STAT_BETTER_COLOR = Color(0xFF9AC2FF)
private val STAT_OTHER_COLOR = Color(0xFF4B5A6E)
private val HIGHLIGHT_BADGE_COLOR = Color(0xD90A0D12)
