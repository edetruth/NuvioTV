package com.nuvio.tv.ui.screens.sportslive

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
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
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.SportsLiveToastKind
import com.nuvio.tv.core.sportslive.SportsLiveToasts
import com.nuvio.tv.core.sportslive.alerts.GameReminders
import com.nuvio.tv.core.sportslive.scores.GameSection
import com.nuvio.tv.core.sportslive.scores.GameSummary
import com.nuvio.tv.core.sportslive.scores.GameTeam
import com.nuvio.tv.core.sportslive.scores.RankDisplay
import com.nuvio.tv.core.sportslive.scores.ScoreSide
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoreTeam
import com.nuvio.tv.core.sportslive.scores.TeamPages
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.ui.components.AutoResizeText
import com.nuvio.tv.ui.screens.sportslive.dual.SportsLiveDualSessions
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveButton
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveButtonRole
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTextTab
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Colors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * The Game page (#18; V2 #134, `GamePage.dc.html`): a centred header with no card (logos, short names, the hero
 * score, status and venue), one action row (a PRIMARY from [gameActions] + More…, which holds Follow, Watch with
 * another game, Team pages and Details), text tabs (Game · Box score · Standings · Highlights, empty ones
 * hidden) and the chosen section. ← → move between tabs only; in the body they never switch section. Every row
 * of the body takes focus, so ↓ always scrolls. Scores hidden: the body is a calm card over greyed
 * placeholders. Back returns to where the page was opened (the player resumes, #153).
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

    val sections = state.sections
    val section = state.section
    val showsScore = state.showsScore
    // Remind me (#44): an upcoming game opened from its card.
    val reminders by remindersViewModel.state.collectAsStateWithLifecycle()
    val remindCard = state.card?.takeIf { event.state == ScoreState.PRE && GameReminders.canRemind(it, System.currentTimeMillis()) }
    val gameState = state.summary?.state ?: event.state
    // Watch: a live game opened from a card that plays it.
    val liveCard = state.card?.takeIf { gameState == ScoreState.IN && !it.isChannel }
    val actions = gameActions(
        showsScore = showsScore,
        hasReplay = state.replay != null,
        canWatchLive = liveCard != null,
        canRemind = remindCard != null
    )

    val actionFocus = remember { GameAction.entries.associateWith { FocusRequester() } }
    val moreFocus = remember { FocusRequester() }
    val tabFocus = remember { GameSection.entries.associateWith { FocusRequester() } }
    val bodyFocus = remember { FocusRequester() }
    // The first action, else More…: the header's first focus.
    val headerFirst = actions.firstOrNull()?.let(actionFocus::getValue) ?: moreFocus
    val selectedTab = section?.let(tabFocus::getValue)

    // Back from a clip or a Player card: focus returns to what opened it (a highlight, Watch all, a performer).
    // The key stays (the element keeps its requester); [returnPending] says to use it once.
    var returnKey by rememberSaveable { mutableStateOf<String?>(null) }
    var returnPending by rememberSaveable { mutableStateOf(false) }
    // Opened on a section ("Box score ›" from the player's panel, #145): its tab takes focus once it shows, so
    // ↓ reads it at once. The header holds focus until the summary has loaded.
    var sectionFocusPending by rememberSaveable { mutableStateOf(state.openedOnSection) }
    val returnFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        if (returnPending) {
            returnPending = false
            repeat(2) { withFrameNanos { } }
            // An element scrolled out of the list has no focus target: the header instead.
            if (runCatching { returnFocus.requestFocus() }.isSuccess) return@LaunchedEffect
        }
        runCatching { headerFirst.requestFocus() }
    }
    LaunchedEffect(selectedTab, showsScore) {
        if (!sectionFocusPending) return@LaunchedEffect
        // Hidden scores have no tabs until Reveal, and Reveal's own focus rule wins then.
        if (!showsScore) {
            sectionFocusPending = false
            return@LaunchedEffect
        }
        val tab = selectedTab ?: return@LaunchedEffect
        sectionFocusPending = false
        repeat(2) { withFrameNanos { } }
        runCatching { tab.requestFocus() }
    }
    // Reveal goes away once pressed: focus moves to the new first action.
    var revealPressed by remember { mutableStateOf(false) }
    LaunchedEffect(showsScore, revealPressed) {
        if (!showsScore || !revealPressed) return@LaunchedEffect
        revealPressed = false
        withFrameNanos { }
        runCatching { headerFirst.requestFocus() }
    }

    var menuOpen by remember { mutableStateOf(false) }
    val (awaySide, homeSide) = headerSides(state.summary, event)
    val (firstSide, secondSide) = inCardOrder(ScoreSide.AWAY, ScoreSide.HOME, state.homeFirst)
    fun sideOf(side: ScoreSide) = if (side == ScoreSide.HOME) homeSide else awaySide
    fun followedOf(side: ScoreSide) = if (side == ScoreSide.HOME) state.homeFollowed else state.awayFollowed

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Colors.Background)
            .padding(start = PAGE_SIDE, end = PAGE_SIDE, top = PAGE_TOP)
    ) {
        GameHero(state = state, event = event, first = sideOf(firstSide), second = sideOf(secondSide))
        Spacer(Modifier.height(10.dp))
        GameActionRow(
            actions = actions,
            actionFocus = actionFocus,
            moreFocus = moreFocus,
            // No tabs (scores hidden, or nothing to show): Down stays put.
            downFocus = selectedTab,
            reminded = remindCard != null && reminders.isReminded(remindCard),
            onAction = { action ->
                when (action) {
                    GameAction.WATCH -> liveCard?.let { playViewModel.play(it) }
                    GameAction.REMIND -> remindCard?.let(remindersViewModel::toggle)
                    GameAction.REPLAY -> state.replay?.let { replay ->
                        if (replay.apiType == "series") onOpenDetail(replay) else playViewModel.play(replay)
                    }
                    GameAction.REVEAL -> {
                        revealPressed = true
                        viewModel.reveal()
                    }
                }
            },
            onMore = { menuOpen = true }
        )
        if (sections.isNotEmpty() && section != null) {
            Spacer(Modifier.height(18.dp))
            GameTabs(
                sections = sections,
                selected = section,
                tabFocus = tabFocus,
                upFocus = headerFirst,
                downFocus = bodyFocus,
                onSelect = viewModel::selectSection
            )
            Spacer(Modifier.height(8.dp))
        } else {
            Spacer(Modifier.height(24.dp))
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            GameBody(
                state = state,
                section = section,
                entryFocus = bodyFocus,
                upFocus = selectedTab ?: headerFirst,
                returnKey = returnKey,
                returnFocus = returnFocus,
                onPlayHighlights = { clips ->
                    SportsLiveHighlights.start(clips)?.let { clip ->
                        // One card: back on it; Watch all: back on the button.
                        returnKey = clips.singleOrNull()?.let { gameHighlightKey(it.id) } ?: GAME_ALL_HIGHLIGHTS_KEY
                        returnPending = true
                        onPlayHighlight(clip)
                    }
                },
                onOpenPlayer = { key, target ->
                    returnKey = key
                    returnPending = true
                    SportsLivePlayerCardRequests.open(SportsLivePlayerCardRequest(target, spoilerSafe = state.spoilerSafe))
                    onOpenPlayerCard()
                }
            )
        }
    }

    if (menuOpen) {
        val order = listOf(firstSide, secondSide)
        val names = order.map { sideOf(it).name }
        SportsLiveCardMenu(
            title = stringResource(R.string.sports_live_matchup, names[0], names[1]),
            sides = order.map { sideOf(it).name to followedOf(it) },
            onToggleFollow = { index -> order.getOrNull(index)?.let(viewModel::toggleFollow) },
            onDetails = (state.replay ?: state.card)?.let { item -> { menuOpen = false; onOpenDetail(item) } },
            onDismiss = { menuOpen = false },
            onWatchWith = if (state.offersWatchWith) {
                {
                    menuOpen = false
                    val session = viewModel.watchWithSession()
                    if (session == null) {
                        SportsLiveToasts.show(SportsLiveToastKind.PROBLEM, context.getString(R.string.sports_live_dual_no_pair))
                    } else {
                        SportsLiveDualSessions.start(session)
                        onOpenDual()
                    }
                }
            } else null,
            followingLabel = true,
            // A pro team with ESPN's id only (#62).
            teamPages = order.mapNotNull { side ->
                TeamPages.refOf(event, side)?.let { ref ->
                    sideOf(side).name to {
                        menuOpen = false
                        SportsLiveTeamRequests.open(SportsLiveTeamRequest(ref))
                        onOpenTeam()
                    }
                }
            }
        )
    }
}

/** One side of the header: from the summary when loaded, else from the event as opened. */
internal data class HeaderSide(val name: String, val logo: String?, val record: String?, val score: Int?, val rank: Int? = null)

internal fun headerSides(summary: GameSummary?, event: ScoreSnapshot): Pair<HeaderSide, HeaderSide> {
    if (summary != null) {
        // The short name ("Celtics", as the design): the summary's, else the scoreboard's for the same side.
        fun side(team: GameTeam, listed: ScoreTeam) = HeaderSide(
            (team.shortDisplayName ?: listed.shortDisplayName ?: team.fullName).trim(),
            team.logo,
            team.record,
            team.score,
            // A ranked college team's "#3" (#137), from the summary, else the scoreboard.
            RankDisplay.rank(summary.league, team.rank ?: listed.rank)
        )
        return side(summary.away, event.away) to side(summary.home, event.home)
    }
    fun side(team: ScoreTeam) = HeaderSide(
        name = (team.shortDisplayName ?: team.displayName ?: team.name ?: team.abbreviation.orEmpty()).trim(),
        logo = team.logo,
        record = null,
        score = team.score,
        rank = RankDisplay.rank(event.league, team.rank)
    )
    return side(event.away) to side(event.home)
}

/** [away] and [home] in the order the page shows them: home first when [homeFirst] (N10). */
internal fun <T> inCardOrder(away: T, home: T, homeFirst: Boolean): Pair<T, T> = if (homeFirst) home to away else away to home

/**
 * The centred hero (board ①): each team a column (110 px logo, no circle; the short name at 40 px, shrinking to
 * 32 px and never wrapping, #145; the record, hidden with the score), and between them the 96 px score, the
 * status (28 px) and the venue (24 px).
 */
@Composable
private fun GameHero(state: SportsLiveGameUiState, event: ScoreSnapshot, first: HeaderSide, second: HeaderSide) {
    val summary = state.summary
    val showsScore = state.showsScore
    val logos = rememberTeamLogoPair(first.logo, second.logo, px = HERO_LOGO_PX)
    val gameState = summary?.state ?: event.state
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.Top
    ) {
        HeroTeam(first, logos?.first, logos?.shown == true, showsScore)
        Column(
            modifier = Modifier.width(HERO_MIDDLE_WIDTH),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val firstScore = first.score
            val secondScore = second.score
            val played = gameState == ScoreState.IN || gameState == ScoreState.POST
            val scoreShown = showsScore && played && firstScore != null && secondScore != null
            Box(modifier = Modifier.height(HERO_SCORE_HEIGHT), contentAlignment = Alignment.Center) {
                Text(
                    text = when {
                        // Before tip-off there is no score to hide: "vs" (#171).
                        !showsScore && played -> stringResource(R.string.sports_live_results_score_hidden)
                        !showsScore -> stringResource(R.string.sports_live_art_vs)
                        scoreShown -> stringResource(R.string.sports_live_details_sides, firstScore.toString(), secondScore.toString())
                        else -> stringResource(R.string.sports_live_art_vs)
                    },
                    style = SportsLiveTokens.style(if (scoreShown) SportsLiveTokens.Type.HeroScore else SportsLiveTokens.Type.StageScore)
                        .let { if (scoreShown) it.sportsLiveBold() else it.copy(fontWeight = FontWeight.Medium) },
                    color = if (scoreShown) Colors.TextPrimary else Colors.TextMuted,
                    maxLines = 1,
                    softWrap = false
                )
            }
            GameStatusLine(summary = summary, event = event, showsScore = showsScore, live = gameState == ScoreState.IN)
        }
        HeroTeam(second, logos?.second, logos?.shown == true, showsScore)
    }
}

@Composable
private fun HeroTeam(side: HeaderSide, logo: Painter?, logoShown: Boolean, showRecord: Boolean) {
    Column(modifier = Modifier.width(HERO_TEAM_WIDTH), horizontalAlignment = Alignment.CenterHorizontally) {
        // The logo's room stays, so both names sit on one line whether or not logos load (#130).
        Box(modifier = Modifier.height(HERO_LOGO), contentAlignment = Alignment.Center) {
            if (logo != null) TeamLogoSlot(painter = logo, shown = logoShown, size = HERO_LOGO)
        }
        Spacer(Modifier.height(6.dp))
        // One line, never broken mid-word ("Mammo/th", #145): a long name shrinks to 32 px, then is cut.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
            // A ranked college team (#137): a small muted "#3" before the name.
            side.rank?.let { rank ->
                Text(
                    text = RankDisplay.label(rank),
                    style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta).copy(fontFeatureSettings = "tnum"),
                    color = Colors.TextMuted,
                    maxLines = 1,
                    modifier = Modifier.padding(end = 6.dp)
                )
            }
            AutoResizeText(
                text = side.name,
                color = Colors.TextPrimary,
                style = SportsLiveTokens.style(HERO_NAME).sportsLiveBold(),
                minFontSize = HERO_NAME_MIN,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
        // The record after the game would tell how it ended: hidden with the score.
        if (showRecord && side.record != null) {
            Text(
                side.record,
                style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta).copy(fontFeatureSettings = "tnum"),
                color = Colors.TextMuted,
                maxLines = 1
            )
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
    val style = SportsLiveTokens.style(STATUS_STYLE)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (status != null && live) {
            SportsLiveLiveDot()
            Text(status, style = style, color = Colors.LiveText, maxLines = 1)
        }
        val rest = listOfNotNull(status.takeUnless { live }, date)
        if (rest.isNotEmpty()) {
            Text(
                text = (if (status != null && live) separator else "") + rest.joinToString(separator),
                style = style,
                color = Colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    // The venue on a line of its own, so a long one never pushes the status out of the middle column.
    summary?.venue?.let { venue ->
        Text(
            text = venue,
            style = SportsLiveTokens.style(SportsLiveTokens.Type.Control),
            color = Colors.TextMuted,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Board ②: the PRIMARY, the other actions as SECONDARY buttons, then More…; centred under the hero. */
@Composable
private fun GameActionRow(
    actions: List<GameAction>,
    actionFocus: Map<GameAction, FocusRequester>,
    moreFocus: FocusRequester,
    downFocus: FocusRequester?,
    reminded: Boolean,
    onAction: (GameAction) -> Unit,
    onMore: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SportsLiveTokens.Size.ButtonGap, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        fun Modifier.leaves(): Modifier = focusProperties { down = downFocus ?: FocusRequester.Cancel; up = FocusRequester.Cancel }
        actions.forEachIndexed { index, action ->
            val primary = index == 0
            SportsLiveButton(
                onClick = { onAction(action) },
                role = if (primary) SportsLiveButtonRole.PRIMARY else SportsLiveButtonRole.SECONDARY,
                modifier = Modifier
                    .then(if (primary) Modifier.widthIn(min = SportsLiveTokens.Size.ButtonMinWidth) else Modifier)
                    .focusRequester(actionFocus.getValue(action))
                    .leaves()
            ) {
                val icon = when (action) {
                    GameAction.WATCH, GameAction.REPLAY -> Icons.Rounded.PlayArrow
                    GameAction.REMIND -> if (reminded) Icons.Rounded.NotificationsActive else Icons.Rounded.Notifications
                    GameAction.REVEAL -> null
                }
                if (icon != null) {
                    Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = stringResource(
                        when (action) {
                            GameAction.WATCH -> R.string.sports_live_watch
                            GameAction.REMIND -> if (reminded) R.string.sports_live_cancel_reminder else R.string.sports_live_remind_me
                            GameAction.REPLAY -> R.string.sports_live_game_watch_replay
                            GameAction.REVEAL -> R.string.sports_live_game_reveal
                        }
                    ),
                    style = SportsLiveTokens.style(SportsLiveTokens.Type.Button),
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
        SportsLiveButton(onClick = onMore, modifier = Modifier.focusRequester(moreFocus).leaves()) {
            Text(stringResource(R.string.sports_live_more), style = SportsLiveTokens.style(SportsLiveTokens.Type.Button), maxLines = 1, softWrap = false)
        }
    }
}

/**
 * Board ③: the text tabs. A tab selects on focus; ← → step through the tabs ([gameTabKey]) and stop at the ends.
 * Coming in from above or below always lands on the selected tab, so passing through never switches section.
 */
@Composable
private fun GameTabs(
    sections: List<GameSection>,
    selected: GameSection,
    tabFocus: Map<GameSection, FocusRequester>,
    upFocus: FocusRequester,
    downFocus: FocusRequester,
    onSelect: (GameSection) -> Unit
) {
    val current by rememberUpdatedState(selected)
    Row(
        modifier = Modifier
            .focusProperties { onEnter = { tabFocus.getValue(current).requestFocus() } }
            .focusGroup()
            .onPreviewKeyEvent { keyEvent ->
                val native = keyEvent.nativeKeyEvent
                when (val result = gameTabKey(sections, current, native.keyCode)) {
                    GameTabKey.Pass -> false
                    GameTabKey.Stay -> true
                    is GameTabKey.Move -> {
                        if (native.action == AndroidKeyEvent.ACTION_DOWN) {
                            onSelect(result.section)
                            runCatching { tabFocus.getValue(result.section).requestFocus() }
                        }
                        true
                    }
                }
            },
        horizontalArrangement = Arrangement.spacedBy(SportsLiveTokens.Size.TabGap)
    ) {
        sections.forEach { section ->
            SportsLiveTextTab(
                label = stringResource(section.labelRes()),
                selected = section == selected,
                onFocused = { onSelect(section) },
                onClick = { onSelect(section) },
                modifier = Modifier
                    .focusRequester(tabFocus.getValue(section))
                    .focusProperties {
                        up = upFocus
                        down = downFocus
                    }
            )
        }
    }
}

private fun GameSection.labelRes(): Int = when (this) {
    GameSection.PREVIEW -> R.string.sports_live_game_section_preview
    GameSection.GAME -> R.string.sports_live_game_section_game
    GameSection.BOX_SCORE -> R.string.sports_live_game_section_box_score
    GameSection.STANDINGS -> R.string.sports_live_game_section_standings
    GameSection.HIGHLIGHTS -> R.string.sports_live_game_section_highlights
}

/** The 40 px short name; it shrinks to [HERO_NAME_MIN] (32 px) before it is cut, never wrapped. */
private val HERO_NAME = SportsLiveTokens.Type.RowName.copy(fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold)
private val HERO_NAME_MIN = 16.sp
/** Status: 28 px. */
private val STATUS_STYLE = SportsLiveTokens.Type.Meta.copy(fontSize = 14.sp, lineHeight = 18.sp)
/** Logos: 110 px, decoded at 220. */
private val HERO_LOGO = 55.dp
private const val HERO_LOGO_PX = 220
/** The board's team centres sit 540 px either side of the score. */
private val HERO_TEAM_WIDTH = 240.dp
private val HERO_MIDDLE_WIDTH = 300.dp
private val HERO_SCORE_HEIGHT = 56.dp
