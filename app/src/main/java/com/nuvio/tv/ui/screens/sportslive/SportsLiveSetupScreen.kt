package com.nuvio.tv.ui.screens.sportslive

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.scores.MyLeague
import com.nuvio.tv.core.sportslive.scores.MySport
import com.nuvio.tv.core.sportslive.scores.MySports
import com.nuvio.tv.core.sportslive.scores.ScoreDelayChoices
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.ui.screens.settings.allSoccerCompetitionsOn
import com.nuvio.tv.ui.screens.settings.leagueOn
import com.nuvio.tv.ui.screens.settings.summarise
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveButton
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveButtonRole
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveFocus
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Colors
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Focus
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Size
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Type

/** Board sizes for first-run setup (FirstRun.dc.html; board px ÷ 2). */
private object SetupSize {
    /** The screen's left edge (no rail here), as Team search: x = 120 px. */
    val ScreenStart = 60.dp
    /** The fixed footer: 80 px. */
    val Footer = 40.dp
    /** Step 2: the sports list (540 px) and the leagues column (480 px), 64 px apart. */
    val SportsWidth = 270.dp
    val LeaguesWidth = 240.dp
    val ColumnGap = 32.dp
    val CheckRow = 30.dp
    val CheckRowGap = 3.dp
    val Check = 14.dp
    /** Step 4: three cards, 32 px apart. */
    val CardHeight = 168.dp
    val CardGap = 16.dp
    /** Step dots: 10 px, the current one a 32 px bar. */
    val Dot = 5.dp
    val DotCurrent = 16.dp
    /** Done: the check circle (104 px). */
    val DoneMark = 52.dp
}

private val StepTitle = TextStyle(fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)

/**
 * First-run setup (#134 phase 7, FirstRun board): Welcome → Sports and leagues → Your teams → Scores and
 * spoilers → Done. A fixed footer (Skip setup · step dots · Back · Next) on every step; focus starts on the
 * step's main control and ↓ out of the step reaches Next. Back goes a step back, and on Welcome skips.
 * [onExit] runs once, with how it ended.
 */
@Composable
fun SportsLiveSetupScreen(
    onExit: (SetupExit) -> Unit,
    viewModel: SportsLiveSetupViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val step = state.step
    val next = remember { FocusRequester() }
    val main = remember { FocusRequester() }

    BackHandler { viewModel.back() }
    LaunchedEffect(state.exit) { state.exit?.let(onExit) }
    // Focus the step's main control once it has composed (step 3's search focuses its own grid).
    LaunchedEffect(step) {
        if (step == SetupStep.TEAMS) return@LaunchedEffect
        withFrameNanos { }
        runCatching { (if (step == SetupStep.WELCOME || step == SetupStep.DONE) next else main).requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Colors.Background)
            .padding(start = SetupSize.ScreenStart, end = Size.SafeEnd, top = Size.SafeTop, bottom = Size.SafeBottom)
    ) {
        Text(
            text = stringResource(R.string.sports_live_setup_step, step.number, SetupStep.entries.size).uppercase(),
            style = SportsLiveTokens.style(Type.SectionHeader),
            color = Colors.TextMuted,
            maxLines = 1
        )
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                // ↓ out of the step always reaches Next, whichever footer button is below.
                .focusProperties {
                    this.onExit = {
                        if (requestedFocusDirection == FocusDirection.Down) {
                            cancelFocusChange()
                            next.requestFocus()
                        }
                    }
                }
                .focusGroup()
        ) {
            when (step) {
                SetupStep.WELCOME -> WelcomeStep()
                SetupStep.SPORTS -> SportsStep(
                    sports = state.sports,
                    first = main,
                    next = next,
                    onSport = viewModel::toggleSport,
                    onLeague = viewModel::pressLeague,
                    onAllCompetitions = viewModel::pressAllCompetitions
                )
                SetupStep.TEAMS -> TeamsStep(next = next)
                SetupStep.SCORES -> ScoresStep(
                    option = state.scoresOption,
                    delaySeconds = state.delaySeconds,
                    selectedCard = main,
                    onOption = viewModel::selectScoresOption,
                    onDelay = viewModel::selectDelay
                )
                SetupStep.DONE -> DoneStep(state)
            }
        }
        Footer(
            step = step,
            next = next,
            onSkip = viewModel::skip,
            onBack = viewModel::back,
            onNext = viewModel::next
        )
    }
}

/** The fixed footer: Skip setup (ghost) on the left; step dots, Back and Next (PRIMARY) on the right. */
@Composable
private fun Footer(step: SetupStep, next: FocusRequester, onSkip: () -> Unit, onBack: () -> Unit, onNext: () -> Unit) {
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Colors.Divider))
        Row(
            modifier = Modifier.fillMaxWidth().height(SetupSize.Footer),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Nothing is left to skip on the last step (TV QA P3).
            if (setupShowsSkip(step)) GhostButton(text = stringResource(R.string.sports_live_setup_skip), onClick = onSkip)
            Spacer(Modifier.weight(1f))
            StepDots(step)
            Spacer(Modifier.width(Size.ButtonGap * 2))
            if (step.previous != null) {
                SportsLiveButton(onClick = onBack) { Text(stringResource(R.string.sports_live_setup_back)) }
                Spacer(Modifier.width(Size.ButtonGap))
            }
            SportsLiveButton(
                onClick = onNext,
                role = SportsLiveButtonRole.PRIMARY,
                modifier = Modifier.focusRequester(next),
                contentPadding = PaddingValues(horizontal = 24.dp)
            ) {
                Text(
                    stringResource(
                        when (step) {
                            SetupStep.WELCOME -> R.string.sports_live_setup_get_started
                            SetupStep.DONE -> R.string.sports_live_setup_go_live_now
                            else -> R.string.sports_live_setup_next
                        }
                    ),
                    maxLines = 1
                )
            }
        }
    }
}

/** Skip setup shows on every step but the last ("You're set", TV QA P3): nothing is left to skip there. */
internal fun setupShowsSkip(step: SetupStep): Boolean = step != SetupStep.DONE

/** A ghost button (Skip setup): text only at rest; focus adds the ring and the lift. */
@Composable
private fun GhostButton(text: String, onClick: () -> Unit) {
    val shape = SportsLiveTokens.Radius.ButtonShape
    Button(
        onClick = onClick,
        modifier = Modifier.height(Size.ButtonHeight),
        scale = ButtonDefaults.scale(focusedScale = Focus.LIFT),
        colors = ButtonDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Colors.TextSecondary,
            focusedContainerColor = SportsLiveFocus.focusedFill(Color.Transparent),
            focusedContentColor = Colors.TextPrimary,
            pressedContainerColor = SportsLiveFocus.focusedFill(Color.Transparent),
            pressedContentColor = Colors.TextPrimary
        ),
        border = ButtonDefaults.border(
            border = Border.None,
            focusedBorder = SportsLiveFocus.ring(shape),
            pressedBorder = SportsLiveFocus.ring(shape)
        ),
        shape = ButtonDefaults.shape(shape),
        contentPadding = PaddingValues(horizontal = 12.dp)
    ) {
        Text(text = text, style = SportsLiveTokens.style(Type.Button), maxLines = 1)
    }
}

@Composable
private fun StepDots(step: SetupStep) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        SetupStep.entries.forEach {
            val current = it == step
            Box(
                Modifier
                    .size(width = if (current) SetupSize.DotCurrent else SetupSize.Dot, height = SetupSize.Dot)
                    .background(if (current) Colors.TextPrimary else Colors.TextMuted.copy(alpha = 0.45f), CircleShape)
            )
        }
    }
}

@Composable
private fun StepHeading(title: String, subtitle: String?) {
    Text(text = title, style = SportsLiveTokens.style(StepTitle), color = Colors.TextPrimary, maxLines = 1)
    if (subtitle != null) {
        Spacer(Modifier.height(4.dp))
        Text(text = subtitle, style = SportsLiveTokens.style(Type.Meta), color = Colors.TextMuted, maxLines = 2)
    }
}

// --- 1 · Welcome ------------------------------------------------------------------------------------------

@Composable
private fun WelcomeStep() {
    Column(modifier = Modifier.fillMaxHeight(), verticalArrangement = Arrangement.Center) {
        Text(
            text = stringResource(R.string.sports_live_setup_welcome_title),
            style = SportsLiveTokens.style(Type.PageTitle),
            color = Colors.TextPrimary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.sports_live_setup_welcome_subtitle),
            style = SportsLiveTokens.style(Type.Meta),
            color = Colors.TextMuted
        )
        Spacer(Modifier.height(24.dp))
        WelcomeLine(stringResource(R.string.sports_live_setup_welcome_live)) {
            Box(Modifier.size(7.dp).background(Colors.LiveRed, CircleShape))
        }
        WelcomeLine(stringResource(R.string.sports_live_setup_welcome_teams)) {
            Text("★", style = SportsLiveTokens.style(Type.Meta), color = Colors.FollowedGold)
        }
        WelcomeLine(stringResource(R.string.sports_live_setup_welcome_scores)) {
            Icon(Icons.Rounded.Visibility, contentDescription = null, tint = Colors.WhyTagBlue, modifier = Modifier.size(13.dp))
        }
        // Room under the lines, so the block sits a little above the middle.
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun WelcomeLine(text: String, icon: @Composable () -> Unit) {
    Row(modifier = Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(22.dp), contentAlignment = Alignment.CenterStart) { icon() }
        Text(text = text, style = SportsLiveTokens.style(Type.RowName), color = Colors.TextSecondary)
    }
}

// --- 2 · Sports and leagues -------------------------------------------------------------------------------

/**
 * The sports list on the left (check = SelectedTint + ✓) and the focused sport's leagues on the right; → enters
 * the leagues and ← returns to their sport. The picks are a draft that Next saves as My sports.
 */
@Composable
private fun SportsStep(
    sports: MySports,
    first: FocusRequester,
    next: FocusRequester,
    onSport: (MySport) -> Unit,
    onLeague: (MyLeague) -> Unit,
    onAllCompetitions: () -> Unit
) {
    var shown by remember { mutableStateOf(MySport.entries.first()) }
    val sportFocus = remember { MySport.entries.associateWith { FocusRequester() } }
    val firstLeague = remember { FocusRequester() }
    Column(modifier = Modifier.fillMaxSize()) {
        StepHeading(
            title = stringResource(R.string.sports_live_setup_sports_title),
            subtitle = stringResource(R.string.sports_live_setup_sports_subtitle)
        )
        Spacer(Modifier.height(14.dp))
        Row(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.width(SetupSize.SportsWidth).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(SetupSize.CheckRowGap),
                contentPadding = PaddingValues(4.dp)
            ) {
                itemsIndexed(MySport.entries, key = { _, sport -> sport.id }) { index, sport ->
                    CheckRow(
                        label = mySportLabel(sport),
                        checked = sport in sports.effectiveSports,
                        value = sportSummary(sport, sports),
                        marked = sport == shown,
                        onClick = { onSport(sport) },
                        modifier = Modifier
                            .focusRequester(sportFocus.getValue(sport))
                            .then(if (index == 0) Modifier.focusRequester(first) else Modifier)
                            .onFocusChanged { if (it.isFocused) shown = sport }
                            .focusProperties {
                                right = firstLeague
                                if (index == MySport.entries.lastIndex) down = next
                            }
                    )
                }
            }
            Spacer(Modifier.width(SetupSize.ColumnGap))
            Column(modifier = Modifier.width(SetupSize.LeaguesWidth).fillMaxHeight()) {
                Text(
                    text = stringResource(R.string.sports_live_setup_sports_leagues, mySportLabel(shown)).uppercase(),
                    style = SportsLiveTokens.style(Type.SectionHeader),
                    color = Colors.TextMuted,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 4.dp)
                )
                val soccer = shown == MySport.SOCCER
                val leagues = shown.leagues
                val count = leagues.size + if (soccer) 1 else 0
                // A fresh list per sport, so → always finds its first row in view.
                key(shown) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(SetupSize.CheckRowGap),
                        contentPadding = PaddingValues(4.dp)
                    ) {
                        // A row's ← goes back to the sport whose leagues these are; the last row's ↓ to Next.
                        fun Modifier.leagueRow(index: Int) = this
                            .then(if (index == 0) Modifier.focusRequester(firstLeague) else Modifier)
                            .focusProperties {
                                left = sportFocus.getValue(shown)
                                if (index == count - 1) down = next
                            }
                        if (soccer) {
                            item(key = "all") {
                                CheckRow(
                                    label = stringResource(R.string.sports_live_my_sports_all_competitions),
                                    checked = sports.allSoccerCompetitionsOn,
                                    onClick = onAllCompetitions,
                                    modifier = Modifier.leagueRow(0)
                                )
                            }
                        }
                        itemsIndexed(leagues, key = { _, league -> league.id }) { index, league ->
                            CheckRow(
                                label = myLeagueLabel(league),
                                checked = sports.leagueOn(league),
                                onClick = { onLeague(league) },
                                modifier = Modifier.leagueRow(index + if (soccer) 1 else 0)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A sport's value on the left: its leagues ("NBA, College basketball"), "All competitions", or nothing when off. */
@Composable
private fun sportSummary(sport: MySport, sports: MySports): String? = when {
    sport !in sports.effectiveSports -> null
    sport == MySport.SOCCER && sports.allSoccerCompetitionsOn -> stringResource(R.string.sports_live_my_sports_all_competitions)
    sport.leagues.size == 1 -> null
    else -> summarise(sports.leaguesOf(sport).sortedBy { it.ordinal }.map { myLeagueLabel(it) })
}

/**
 * A row with a check box: checked = SelectedTint fill + ✓; [marked] = the white left bar of the sport whose
 * leagues show; focus = the ring and a 1.02 lift.
 */
@Composable
private fun CheckRow(
    label: String,
    checked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    marked: Boolean = false
) {
    val shape = SportsLiveTokens.Radius.ButtonShape
    val fill = if (checked) Colors.SelectedTint else Colors.Row
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(SetupSize.CheckRow),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = SportsLiveTokens.Settings.ROW_LIFT),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = fill,
            contentColor = Colors.TextPrimary,
            focusedContainerColor = SportsLiveTokens.focusFill(fill),
            focusedContentColor = Colors.TextPrimary,
            pressedContainerColor = SportsLiveTokens.focusFill(fill),
            pressedContentColor = Colors.TextPrimary
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = SportsLiveFocus.ring(shape), pressedBorder = SportsLiveFocus.ring(shape))
    ) {
        Box(Modifier.fillMaxSize()) {
            if (marked) {
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .width(2.dp)
                        .fillMaxHeight(0.6f)
                        .background(Colors.FocusWhite, RoundedCornerShape(1.dp))
                )
            }
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CheckBox(checked)
                Spacer(Modifier.width(9.dp))
                Text(
                    text = label,
                    style = SportsLiveTokens.style(Type.RowName).copy(fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (checked) Colors.TextPrimary else Colors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (value != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = value,
                        style = SportsLiveTokens.style(Type.Tag),
                        color = Colors.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun CheckBox(checked: Boolean) {
    val shape = RoundedCornerShape(3.dp)
    Box(
        modifier = Modifier
            .size(SetupSize.Check)
            .border(1.dp, if (checked) Colors.TextPrimary else Colors.OutlineStrong, shape),
        contentAlignment = Alignment.Center
    ) {
        if (checked) Icon(Icons.Rounded.Check, contentDescription = null, tint = Colors.TextPrimary, modifier = Modifier.size(11.dp))
    }
}

// --- 3 · Your teams ---------------------------------------------------------------------------------------

/** Team search (#149) with the filter on My sports, the sports step 2 just saved; OK follows in place. */
@Composable
private fun TeamsStep(next: FocusRequester, searchViewModel: SportsLiveTeamSearchViewModel = hiltViewModel()) {
    LaunchedEffect(Unit) { searchViewModel.scopeToMySports() }
    Column(modifier = Modifier.fillMaxSize()) {
        StepHeading(title = stringResource(R.string.sports_live_setup_teams_title), subtitle = null)
        Spacer(Modifier.height(12.dp))
        SportsLiveTeamSearch(
            viewModel = searchViewModel,
            modifier = Modifier.fillMaxSize(),
            showTitle = false,
            downFocus = next
        )
    }
}

// --- 4 · Scores and spoilers ------------------------------------------------------------------------------

/** Four cards, each showing a real row as it would read, then the score delay. Next saves both. */
@Composable
private fun ScoresStep(
    option: SetupScoresOption,
    delaySeconds: Int,
    selectedCard: FocusRequester,
    onOption: (SetupScoresOption) -> Unit,
    onDelay: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        StepHeading(
            title = stringResource(R.string.sports_live_setup_scores_title),
            subtitle = stringResource(R.string.sports_live_setup_scores_subtitle)
        )
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(SetupSize.CardGap), modifier = Modifier.padding(4.dp)) {
            SetupScoresOption.entries.forEach { card ->
                ScoresCard(
                    option = card,
                    selected = card == option,
                    onClick = { onOption(card) },
                    modifier = Modifier
                        .weight(1f)
                        .then(if (card == option) Modifier.focusRequester(selectedCard) else Modifier)
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            Text(
                text = stringResource(R.string.sports_live_setting_score_delay),
                style = SportsLiveTokens.style(Type.Button),
                color = Colors.TextPrimary,
                maxLines = 1
            )
            Spacer(Modifier.width(14.dp))
            ScoreDelayChoices.SECONDS.forEach { seconds ->
                DelayChip(
                    text = if (seconds == 0) stringResource(R.string.sports_live_score_delay_off)
                    else stringResource(R.string.sports_live_setup_delay_seconds, seconds),
                    selected = seconds == delaySeconds,
                    onClick = { onDelay(seconds) }
                )
                Spacer(Modifier.width(6.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.sports_live_setup_delay_help),
                style = SportsLiveTokens.style(Type.Tag),
                color = Colors.TextMuted,
                maxLines = 2,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ScoresCard(option: SetupScoresOption, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val mode = option.choice.mode
    val shape = SportsLiveTokens.Radius.RowShape
    val fill = if (selected) Colors.SelectedTint else Colors.Surface
    Surface(
        onClick = onClick,
        modifier = modifier.height(SetupSize.CardHeight),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = Focus.LIFT),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = fill,
            contentColor = Colors.TextPrimary,
            focusedContainerColor = SportsLiveTokens.focusFill(fill),
            focusedContentColor = Colors.TextPrimary,
            pressedContainerColor = SportsLiveTokens.focusFill(fill),
            pressedContentColor = Colors.TextPrimary
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = SportsLiveFocus.ring(shape), pressedBorder = SportsLiveFocus.ring(shape))
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = scoresOptionTitle(option),
                    style = SportsLiveTokens.style(Type.Button),
                    color = Colors.TextPrimary,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                if (selected) CheckBox(checked = true)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(
                    when (option) {
                        SetupScoresOption.SHOW -> R.string.sports_live_setup_scores_show_help
                        SetupScoresOption.MY_TEAMS -> R.string.sports_live_setup_scores_my_teams_help
                        SetupScoresOption.HIDE -> R.string.sports_live_setup_scores_hide_help
                        SetupScoresOption.NEVER -> R.string.sports_live_setup_scores_off_help
                    }
                ),
                style = SportsLiveTokens.style(Type.Tag),
                color = Colors.TextSecondary,
                // TV QA P2: the copy fits whole (short, up to four lines in the card's room); never cut mid-sentence.
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.weight(1f))
            SampleRow(mode)
        }
    }
}

@Composable
private fun scoresOptionTitle(option: SetupScoresOption): String = stringResource(
    when (option) {
        SetupScoresOption.SHOW -> R.string.sports_live_scores_show
        SetupScoresOption.MY_TEAMS -> R.string.sports_live_setup_scores_my_teams
        SetupScoresOption.HIDE -> R.string.sports_live_scores_hide_until_focused
        SetupScoresOption.NEVER -> R.string.sports_live_scores_off
    }
)

/** Sample teams' marks on the cards' rows (the board's generic Celtics / Knicks); drawn, not addon data. */
private object Sample {
    val HomeMark = Color(0xFF2E9E5B)
    val AwayMark = Color(0xFFE07A2E)
}

/** One game row as [mode] draws it: scores and clock, the hidden-score eye (as the board's rows), or no score at all. */
@Composable
private fun SampleRow(mode: ScoresMode) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Colors.Row, SportsLiveTokens.Radius.ButtonShape)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            SampleTeam(stringResource(R.string.sports_live_setup_sample_home), Sample.HomeMark)
            SampleTeam(stringResource(R.string.sports_live_setup_sample_away), Sample.AwayMark)
        }
        when (mode) {
            ScoresMode.SHOW -> {
                Column(horizontalAlignment = Alignment.End) {
                    SampleScore("91")
                    SampleScore("88")
                }
                Spacer(Modifier.width(8.dp))
                SampleClock("4:12")
            }
            ScoresMode.HIDE_UNTIL_FOCUSED -> {
                // TV QA P1: the eye the board's rows show, not the words, which cut the team names.
                Icon(
                    imageVector = Icons.Rounded.VisibilityOff,
                    contentDescription = stringResource(R.string.sports_live_results_score_hidden),
                    tint = Colors.TextMuted,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(8.dp))
                SampleClock("Q4")
            }
            ScoresMode.OFF -> SampleClock("Q4")
        }
    }
}

@Composable
private fun SampleTeam(name: String, mark: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(mark, CircleShape))
        Spacer(Modifier.width(5.dp))
        Text(text = name, style = SportsLiveTokens.style(Type.Tag), color = Colors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SampleScore(score: String) {
    Text(text = score, style = SportsLiveTokens.style(Type.Tag).copy(fontWeight = FontWeight.Bold), color = Colors.TextPrimary, maxLines = 1)
}

@Composable
private fun SampleClock(clock: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(5.dp).background(Colors.LiveRed, CircleShape))
        Spacer(Modifier.width(3.dp))
        Text(text = clock, style = SportsLiveTokens.style(Type.Tag), color = Colors.LiveText, maxLines = 1)
    }
}

/** A score delay choice: a pill; selected = SelectedTint, focus = the ring and the lift. */
@Composable
private fun DelayChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = SportsLiveTokens.Radius.PillShape
    val fill = if (selected) Colors.SelectedTint else Color.Transparent
    Surface(
        onClick = onClick,
        modifier = Modifier.height(Size.ControlHeight),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = Focus.LIFT),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = fill,
            contentColor = if (selected) Colors.TextPrimary else Colors.TextInactive,
            focusedContainerColor = SportsLiveFocus.focusedFill(fill),
            focusedContentColor = Colors.TextPrimary,
            pressedContainerColor = SportsLiveFocus.focusedFill(fill),
            pressedContentColor = Colors.TextPrimary
        ),
        border = ClickableSurfaceDefaults.border(
            border = SportsLiveFocus.outline(shape),
            focusedBorder = SportsLiveFocus.ring(shape),
            pressedBorder = SportsLiveFocus.ring(shape)
        )
    ) {
        Box(Modifier.fillMaxHeight().padding(horizontal = 11.dp), contentAlignment = Alignment.Center) {
            Text(
                text = text,
                style = SportsLiveTokens.style(if (selected) Type.ControlSelected else Type.Control),
                maxLines = 1
            )
        }
    }
}

// --- 5 · Done ---------------------------------------------------------------------------------------------

/** You're set: a summary of chips, the reminders line, and where to run setup again. */
@Composable
private fun DoneStep(state: SportsLiveSetupUiState) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(SetupSize.DoneMark)
                .border(2.dp, Colors.SetBlue, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = Colors.TextPrimary, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.sports_live_setup_done_title),
            style = SportsLiveTokens.style(Type.PageTitle),
            color = Colors.TextPrimary
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val sports = state.sports
            SummaryChip(
                if (sports.isDefault) stringResource(R.string.sports_live_my_sports_all_sports)
                else pluralStringResource(R.plurals.sports_live_setup_done_sports, sports.effectiveSports.size, sports.effectiveSports.size)
            )
            if (state.followed.isNotEmpty()) {
                SummaryChip(
                    "★ " + pluralStringResource(R.plurals.sports_live_setup_done_teams, state.followed.size, state.followed.size),
                    gold = true
                )
            }
            SummaryChip(stringResource(R.string.sports_live_setup_done_scores, scoresOptionTitle(state.scoresOption)))
            SummaryChip(
                stringResource(
                    R.string.sports_live_setup_done_delay,
                    if (state.delaySeconds <= 0) stringResource(R.string.sports_live_score_delay_off)
                    else stringResource(R.string.sports_live_setup_delay_seconds, state.delaySeconds)
                )
            )
        }
        if (state.followed.isNotEmpty() && state.remindMyTeams) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.sports_live_setup_done_reminders),
                style = SportsLiveTokens.style(Type.Meta),
                color = Colors.TextSecondary
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.sports_live_setup_done_rerun),
            style = SportsLiveTokens.style(Type.Tag),
            color = Colors.TextMuted
        )
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun SummaryChip(text: String, gold: Boolean = false) {
    val shape = SportsLiveTokens.Radius.PillShape
    Text(
        text = text,
        style = SportsLiveTokens.style(Type.Control),
        color = if (gold) Colors.FollowedGold else Colors.TextSecondary,
        maxLines = 1,
        modifier = Modifier
            .border(1.dp, Colors.Outline, shape)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    )
}

/**
 * Opening Sports & Live (#134 phase 7): claims first-run setup for the profile, and [onShow] opens it when it
 * shows now. A profile already set up is marked done silently.
 */
@Composable
fun SportsLiveSetupGate(onShow: () -> Unit, viewModel: SportsLiveSetupGateViewModel = hiltViewModel()) {
    LaunchedEffect(Unit) { if (viewModel.claim()) onShow() }
}
