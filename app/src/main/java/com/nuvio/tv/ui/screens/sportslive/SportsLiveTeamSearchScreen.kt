package com.nuvio.tv.ui.screens.sportslive

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.scores.MyLeague
import com.nuvio.tv.core.sportslive.scores.MySport
import com.nuvio.tv.core.sportslive.scores.TeamHit
import com.nuvio.tv.core.sportslive.scores.TeamLogoUrls
import com.nuvio.tv.core.sportslive.scores.TeamSearch
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveButton
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveFocus
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Colors
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Focus
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Size

/** Board sizes for team search (TeamSearch.dc.html; board px ÷ 2). */
private object SearchSize {
    /** A letter key: 80 × 72 px. */
    val KeyWidth = 40.dp
    val KeyHeight = 36.dp
    val KeyGap = 4.dp
    const val KEYS_PER_ROW = 6
    val GridWidth = KeyWidth * KEYS_PER_ROW + KeyGap * (KEYS_PER_ROW - 1)
    val FieldHeight = 28.dp
    val ColumnGap = 24.dp
    val YourTeamsWidth = 170.dp
    val ResultRow = 48.dp
    val ResultLogo = 22.dp
    val YourTeamRow = 30.dp
    val YourTeamLogo = 16.dp
    /** The screen's own left edge (no rail here): x = 120 px. */
    val ScreenStart = 60.dp
}

private val KEYS: List<String> = ('a'..'z').map(Char::toString) + ('1'..'9').map(Char::toString) + "0"

/**
 * Settings → My teams → "+ Add a team" (#149): the letter grid, the sport filter, the results and Your teams.
 * Back leaves. First-run step 3 draws [SportsLiveTeamSearch] inside its own frame.
 */
@Composable
fun SportsLiveTeamSearchScreen(
    onBack: () -> Unit,
    viewModel: SportsLiveTeamSearchViewModel = hiltViewModel()
) {
    BackHandler { onBack() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Colors.Background)
            .padding(start = SearchSize.ScreenStart, end = Size.SafeEnd, top = Size.SafeTop, bottom = Size.SafeBottom)
    ) {
        SportsLiveTeamSearch(viewModel = viewModel, modifier = Modifier.fillMaxSize())
    }
}

/**
 * Team search as a block (#149): the screen above and first-run step 3 both draw it. Focus starts on the
 * grid's "a" when [focusOnOpen].
 */
@Composable
fun SportsLiveTeamSearch(
    viewModel: SportsLiveTeamSearchViewModel,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true,
    focusOnOpen: Boolean = true
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val voice = rememberSportsLiveVoiceInput(onResult = viewModel::setQuery)
    val firstKey = remember { FocusRequester() }
    if (focusOnOpen) {
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { firstKey.requestFocus() }
        }
    }
    Column(modifier = modifier.onPreviewKeyEvent { voice.onKey(it) }) {
        if (showTitle) {
            Text(
                text = stringResource(R.string.sports_live_team_search_title),
                style = SportsLiveTokens.style(TitleStyle),
                color = Colors.TextPrimary
            )
            Spacer(Modifier.height(16.dp))
        }
        Row(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.width(SearchSize.GridWidth)) {
                SearchField(query = state.query, listening = voice.listening)
                Spacer(Modifier.height(10.dp))
                LetterGrid(
                    firstKey = firstKey,
                    onKey = viewModel::type,
                    onDelete = viewModel::deleteLetter,
                    onClear = viewModel::clear
                )
                if (voice.available) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Rounded.Mic, contentDescription = null, tint = Colors.TextMuted, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.sports_live_team_search_mic),
                            style = SportsLiveTokens.style(SportsLiveTokens.Type.Tag),
                            color = Colors.TextMuted
                        )
                    }
                }
            }
            Spacer(Modifier.width(SearchSize.ColumnGap))
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                SportFilter(selected = state.sport, sports = state.sports, onSelect = viewModel::selectSport)
                Spacer(Modifier.height(12.dp))
                ResultsPane(state = state, onToggle = viewModel::toggle, onRetry = viewModel::refill)
            }
            Spacer(Modifier.width(SearchSize.ColumnGap))
            YourTeams(state = state, onUnfollow = viewModel::unfollow, fallbackFocus = firstKey)
        }
    }
}

private val TitleStyle = TextStyle(fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)

@Composable
private fun SearchField(query: String, listening: Boolean) {
    val shape = SportsLiveTokens.Radius.ButtonShape
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(SearchSize.FieldHeight)
            .background(Colors.Row, shape)
            .border(1.dp, Colors.OutlineStrong, shape)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Search, contentDescription = null, tint = Colors.TextMuted, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(8.dp))
        val empty = query.isEmpty()
        Text(
            text = when {
                listening -> stringResource(R.string.sports_live_team_search_listening)
                empty -> stringResource(R.string.sports_live_team_search_placeholder)
                else -> "$query|"
            },
            style = SportsLiveTokens.style(if (empty || listening) SportsLiveTokens.Type.Meta else SportsLiveTokens.Type.RowName),
            color = if (empty || listening) Colors.TextMuted else Colors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun LetterGrid(firstKey: FocusRequester, onKey: (String) -> Unit, onDelete: () -> Unit, onClear: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(SearchSize.KeyGap)) {
        KEYS.chunked(SearchSize.KEYS_PER_ROW).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(SearchSize.KeyGap)) {
                row.forEach { key ->
                    GridKey(
                        onClick = { onKey(key) },
                        width = SearchSize.KeyWidth,
                        modifier = if (key == "a") Modifier.focusRequester(firstKey) else Modifier
                    ) { KeyLabel(key) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(SearchSize.KeyGap)) {
            val wide = SearchSize.KeyWidth * 3 + SearchSize.KeyGap * 2
            val rest = (SearchSize.GridWidth - wide - SearchSize.KeyGap * 2) / 2
            GridKey(onClick = { onKey(" ") }, width = wide) { KeyLabel(stringResource(R.string.sports_live_team_search_space)) }
            GridKey(onClick = onDelete, width = rest) {
                Icon(
                    Icons.AutoMirrored.Rounded.Backspace,
                    contentDescription = stringResource(R.string.sports_live_team_search_delete),
                    modifier = Modifier.size(14.dp)
                )
            }
            GridKey(onClick = onClear, width = rest) { KeyLabel(stringResource(R.string.sports_live_team_search_clear)) }
        }
    }
}

@Composable
private fun KeyLabel(text: String) {
    Text(text = text, style = SportsLiveTokens.style(SportsLiveTokens.Type.RowName), maxLines = 1)
}

@Composable
private fun GridKey(onClick: () -> Unit, width: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = SportsLiveTokens.Radius.ButtonShape
    Surface(
        onClick = onClick,
        modifier = modifier.size(width, SearchSize.KeyHeight),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = Focus.LIFT),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Colors.Row,
            contentColor = Colors.TextPrimary,
            focusedContainerColor = Colors.RowFocused,
            focusedContentColor = Colors.TextPrimary,
            pressedContainerColor = Colors.RowFocused,
            pressedContentColor = Colors.TextPrimary
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = SportsLiveFocus.ring(shape), pressedBorder = SportsLiveFocus.ring(shape))
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
    }
}

/** The sport filter: one focus stop; ← → pick while it has focus (at either end they move focus on). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SportFilter(selected: MySport?, sports: List<MySport>, onSelect: (MySport?) -> Unit) {
    val options: List<MySport?> = listOf<MySport?>(null) + sports
    val index = options.indexOf(selected).coerceAtLeast(0)
    val shape = SportsLiveTokens.Radius.PillShape
    Surface(
        onClick = { onSelect(options[(index + 1) % options.size]) },
        modifier = Modifier
            .height(Size.ControlHeight)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionRight -> if (index < options.lastIndex) { onSelect(options[index + 1]); true } else false
                    Key.DirectionLeft -> if (index > 0) { onSelect(options[index - 1]); true } else false
                    else -> false
                }
            },
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = Focus.LIFT),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Colors.TextPrimary,
            focusedContainerColor = SportsLiveFocus.focusedFill(Color.Transparent),
            focusedContentColor = Colors.TextPrimary,
            pressedContainerColor = SportsLiveFocus.focusedFill(Color.Transparent),
            pressedContentColor = Colors.TextPrimary
        ),
        border = ClickableSurfaceDefaults.border(
            border = SportsLiveFocus.outline(shape),
            focusedBorder = SportsLiveFocus.ring(shape),
            pressedBorder = SportsLiveFocus.ring(shape)
        )
    ) {
        // Wider than the column with five sports: it scrolls, keeping the picked sport in view.
        Row(
            modifier = Modifier.fillMaxHeight().horizontalScroll(rememberScrollState()).padding(horizontal = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            options.forEach { option ->
                val on = option == selected
                val inView = remember { BringIntoViewRequester() }
                if (on) LaunchedEffect(Unit) { inView.bringIntoView() }
                Text(
                    text = option?.let { mySportLabel(it) } ?: stringResource(R.string.sports_live_team_search_all),
                    style = SportsLiveTokens.style(if (on) SportsLiveTokens.Type.ControlSelected else SportsLiveTokens.Type.Control),
                    color = if (on) Colors.TextPrimary else Colors.TextInactive,
                    maxLines = 1,
                    modifier = Modifier
                        .bringIntoViewRequester(inView)
                        .background(if (on) SportsLiveFocus.selectedFill else Color.Transparent, shape)
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun ResultsPane(state: TeamSearchUiState, onToggle: (TeamHit) -> Unit, onRetry: () -> Unit) {
    val filling = state.filling?.let { stringResource(R.string.sports_live_team_search_getting, leagueTag(it)) }
    when {
        state.offline -> StateCard(text = stringResource(R.string.sports_live_team_search_offline)) {
            SportsLiveButton(onClick = onRetry) { Text(stringResource(R.string.sports_live_team_search_retry)) }
        }
        state.firstFill || (!state.browsing && state.hits.isEmpty() && filling != null) -> Column {
            SectionLabel(filling.orEmpty())
            Spacer(Modifier.height(8.dp))
            repeat(SKELETON_ROWS) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(SearchSize.ResultRow)
                        .background(Colors.Row.copy(alpha = 0.6f), SportsLiveTokens.Radius.RowShape)
                )
                Spacer(Modifier.height(Size.RowGap))
            }
        }
        !state.browsing && state.hits.isEmpty() -> StateCard(
            text = stringResource(R.string.sports_live_team_search_no_match, state.query.trim())
        )
        else -> LazyColumn(
            verticalArrangement = Arrangement.spacedBy(Size.RowGap),
            contentPadding = PaddingValues(vertical = 4.dp, horizontal = 4.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            if (filling != null) item(key = "filling") { MutedLine(filling) }
            if (state.browsing) {
                state.groups.forEach { group ->
                    item(key = "h|${group.league.id}") {
                        SectionLabel(stringResource(R.string.sports_live_team_search_league_header, leagueTag(group.league).uppercase(), group.teams.size))
                    }
                    items(group.teams, key = { it.key }) { hit -> ResultRow(hit, state.isFollowed(hit)) { onToggle(hit) } }
                }
            } else {
                item(key = "results") { SectionLabel(stringResource(R.string.sports_live_team_search_results, state.hits.size).uppercase()) }
                items(state.hits, key = { it.key }) { hit -> ResultRow(hit, state.isFollowed(hit)) { onToggle(hit) } }
                item(key = "not_meant") { MutedLine(stringResource(R.string.sports_live_team_search_not_meant)) }
            }
        }
    }
}

private const val SKELETON_ROWS = 4

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = SportsLiveTokens.style(SportsLiveTokens.Type.SectionHeader),
        color = Colors.TextMuted,
        maxLines = 1,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
    )
}

@Composable
private fun MutedLine(text: String) {
    Text(
        text = text,
        style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta),
        color = Colors.TextMuted,
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

/** The empty/error card: one message and, when there is one, the next thing to do. */
@Composable
private fun StateCard(text: String, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Colors.Surface, SportsLiveTokens.Radius.RowShape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = text, style = SportsLiveTokens.style(SportsLiveTokens.Type.RowName), color = Colors.TextSecondary)
        action?.invoke()
    }
}

/** A result: logo, name, then league · sport · why it matched; OK follows in place (☆ Follow → ★ Following). */
@Composable
private fun ResultRow(hit: TeamHit, following: Boolean, onClick: () -> Unit) {
    val shape = SportsLiveTokens.Radius.RowShape
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(SearchSize.ResultRow),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = Focus.LIFT),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Colors.Row,
            contentColor = Colors.TextPrimary,
            focusedContainerColor = Colors.RowFocused,
            focusedContentColor = Colors.TextPrimary,
            pressedContainerColor = Colors.RowFocused,
            pressedContentColor = Colors.TextPrimary
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = SportsLiveFocus.ring(shape), pressedBorder = SportsLiveFocus.ring(shape))
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TeamLogo(hit.team.logo, SearchSize.ResultLogo)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = hit.team.displayName,
                    style = SportsLiveTokens.style(SportsLiveTokens.Type.RowName).copy(fontWeight = FontWeight.SemiBold),
                    color = Colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val why = TeamSearch.reason(hit)?.let { stringResource(R.string.sports_live_team_search_matches, it) }
                Text(
                    text = listOfNotNull(leagueTag(hit.league), sportWord(hit.league), why).joinToString(" · "),
                    style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta),
                    color = Colors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(if (following) R.string.sports_live_team_search_following else R.string.sports_live_team_search_follow),
                style = SportsLiveTokens.style(SportsLiveTokens.Type.Button),
                color = if (following) Colors.FollowedGold else Colors.TextSecondary,
                maxLines = 1
            )
        }
    }
}

/** Your teams: the running list; teams followed on this visit are tagged "New"; OK stops following. */
@Composable
private fun YourTeams(state: TeamSearchUiState, onUnfollow: (FollowedTeam) -> Unit, fallbackFocus: FocusRequester) {
    // After OK stops following a team, focus goes to the row now in its place, else the grid.
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    var removedIndex by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(state.followed) {
        val index = removedIndex ?: return@LaunchedEffect
        removedIndex = null
        val next = state.followed.getOrNull(index.coerceAtMost(state.followed.lastIndex))
        withFrameNanos { }
        runCatching { (next?.let { requesters[TeamSearchState.followKey(it)] } ?: fallbackFocus).requestFocus() }
    }
    Column(modifier = Modifier.width(SearchSize.YourTeamsWidth).fillMaxHeight()) {
        Row(modifier = Modifier.height(Size.ControlHeight), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "★ ",
                style = SportsLiveTokens.style(SportsLiveTokens.Type.SectionHeader),
                color = Colors.FollowedGold
            )
            Text(
                text = stringResource(R.string.sports_live_team_search_your_teams, state.followed.size).uppercase(),
                style = SportsLiveTokens.style(SportsLiveTokens.Type.SectionHeader),
                color = Colors.TextMuted,
                maxLines = 1
            )
        }
        Spacer(Modifier.height(12.dp))
        if (state.followed.isEmpty()) {
            Text(
                text = stringResource(R.string.sports_live_team_search_your_teams_empty),
                style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta),
                color = Colors.TextMuted
            )
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(4.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(state.followed.size, key = { TeamSearchState.followKey(state.followed[it]) }) { index ->
                val team = state.followed[index]
                val key = TeamSearchState.followKey(team)
                YourTeamRow(
                    team = team,
                    logo = state.logoOf(team),
                    isNew = state.isNew(team),
                    modifier = Modifier.focusRequester(requesters.getOrPut(key) { FocusRequester() }),
                    onClick = {
                        removedIndex = index
                        onUnfollow(team)
                    }
                )
            }
        }
    }
}

@Composable
private fun YourTeamRow(team: FollowedTeam, logo: String?, isNew: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = SportsLiveTokens.Radius.ButtonShape
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(SearchSize.YourTeamRow),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = Focus.LIFT),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Colors.TextPrimary,
            focusedContainerColor = SportsLiveFocus.focusedFill(Color.Transparent),
            focusedContentColor = Colors.TextPrimary,
            pressedContainerColor = SportsLiveFocus.focusedFill(Color.Transparent),
            pressedContentColor = Colors.TextPrimary
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = SportsLiveFocus.ring(shape), pressedBorder = SportsLiveFocus.ring(shape))
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TeamLogo(logo, SearchSize.YourTeamLogo)
            Spacer(Modifier.width(8.dp))
            Text(
                text = team.name,
                style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta),
                color = Colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (isNew) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.sports_live_team_search_new),
                    style = SportsLiveTokens.style(SportsLiveTokens.Type.Tag),
                    color = Colors.WhyTagBlue,
                    maxLines = 1
                )
            }
        }
    }
}

/** A team's logo, or an empty slot of the same size so names line up. */
@Composable
private fun TeamLogo(logo: String?, size: Dp) {
    if (logo == null) {
        Spacer(Modifier.size(size))
        return
    }
    AsyncImage(
        model = rememberSizedImage(TeamLogoUrls.scaled(logo, TeamLogoUrls.CARD_PX), size, size),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.size(size)
    )
}

/** A league as line 2 names it: the college leagues by their NCAA tags. */
@Composable
private fun leagueTag(league: MyLeague): String = when (league) {
    MyLeague.COLLEGE_BASKETBALL -> stringResource(R.string.sports_live_league_tag_ncaam)
    MyLeague.COLLEGE_FOOTBALL -> stringResource(R.string.sports_live_league_tag_ncaaf)
    MyLeague.WOMENS_COLLEGE_BASKETBALL -> stringResource(R.string.sports_live_league_tag_ncaaw)
    else -> myLeagueLabel(league)
}

/** The sport as line 2 names it: "College basketball" for the college leagues. */
@Composable
private fun sportWord(league: MyLeague): String = when (league) {
    MyLeague.COLLEGE_BASKETBALL, MyLeague.COLLEGE_FOOTBALL -> myLeagueLabel(league)
    MyLeague.WOMENS_COLLEGE_BASKETBALL -> myLeagueLabel(MyLeague.COLLEGE_BASKETBALL)
    else -> mySportLabel(league.sport)
}
