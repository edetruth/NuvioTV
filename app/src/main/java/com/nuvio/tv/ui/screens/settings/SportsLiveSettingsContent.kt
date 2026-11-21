package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.core.sportslive.SportsLiveGameEndAction
import com.nuvio.tv.core.sportslive.SportsLiveOkOnLive
import com.nuvio.tv.core.sportslive.SportsLiveTipOff
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.SportsLiveOpenGame
import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.MyLeague
import com.nuvio.tv.core.sportslive.scores.MySport
import com.nuvio.tv.core.sportslive.scores.MySports
import com.nuvio.tv.core.sportslive.scores.ScoreDelayChoices
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.core.sportslive.scores.ScoresStatus
import com.nuvio.tv.domain.model.CatalogStats
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.SportsLiveOverride
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.sportslive.mini.SPORTS_LIVE_PIP_SETTINGS_ACTION
import com.nuvio.tv.ui.screens.sportslive.mini.sportsLivePipSupported
import com.nuvio.tv.ui.screens.sportslive.myLeagueLabel
import com.nuvio.tv.ui.screens.sportslive.mySportLabel
import com.nuvio.tv.ui.screens.sportslive.rememberSportsLiveClockMs
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveButton
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveButtonRole
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.ui.screens.settings.SportsLiveSettingsItem as Item

/** A page of Settings → Sports & Live (#134 V2): the main list, or a sub-step it opens in the same pane. */
internal sealed interface SportsLiveSettingsPage {
    data object Main : SportsLiveSettingsPage
    data object MyTeams : SportsLiveSettingsPage
    data object MySports : SportsLiveSettingsPage
    data class Leagues(val sport: MySport) : SportsLiveSettingsPage
    data object Catalogs : SportsLiveSettingsPage
    /** More → Hidden: titles hidden from Live Now (#134). */
    data object Hidden : SportsLiveSettingsPage
    /** Alerts → Teams I'm reminded about (#154). */
    data object RemindedTeams : SportsLiveSettingsPage

    /** Where Back goes, and the row there that opened this page (focus returns to it). */
    val parent: SportsLiveSettingsPage get() = if (this is Leagues) MySports else Main
    val openerKey: String?
        get() = when (this) {
            Main -> null
            MyTeams -> Item.Setting(SportsLiveSetting.MY_TEAMS).key
            MySports -> Item.Setting(SportsLiveSetting.MY_SPORTS).key
            is Leagues -> Item.Sport(sport).key
            Catalogs -> Item.Setting(SportsLiveSetting.CATALOGS).key
            Hidden -> Item.Setting(SportsLiveSetting.HIDDEN).key
            RemindedTeams -> Item.Setting(SportsLiveSetting.REMINDED_TEAMS).key
        }
}

/**
 * Settings → Sports & Live (#72, #134 V2): flat 72 px rows in sections ordered by use, one fixed help line
 * for the focused row (with why a row is off, #89), On/Off switches, and My teams, My sports, a sport's
 * leagues and Catalogs as sub-steps in the same pane (Back returns to the row that opened them).
 * [rootPage] is where the pane starts and Back stops (Results' "Edit my sports" starts at My sports).
 */
@Composable
internal fun SportsLiveSettingsContent(
    initialFocusRequester: FocusRequester?,
    modifier: Modifier = Modifier,
    rootPage: SportsLiveSettingsPage = SportsLiveSettingsPage.Main,
    /** "+ Add a team" (#149): opens Team search. */
    onAddTeam: () -> Unit = {},
    /** "Run setup again" (#134 phase 7, first-run): the Setup row shows only once this is given. */
    onRunSetup: (() -> Unit)? = null,
    viewModel: SportsLiveSettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val alerts by viewModel.alertSettings.collectAsStateWithLifecycle()
    val newSportsHint by viewModel.newSportsHint.collectAsStateWithLifecycle()
    val hiddenTitles by viewModel.hiddenTitles.collectAsStateWithLifecycle()
    val remindedTeams by viewModel.remindedTeams.collectAsStateWithLifecycle()
    val gameEndAction by viewModel.gameEndAction.collectAsStateWithLifecycle()
    val okOnLive by viewModel.okOnLive.collectAsStateWithLifecycle()
    val guideTable by viewModel.guideTable.collectAsStateWithLifecycle()
    // Scores status (#70): worked out on a 30 s clock, so a feed no longer in use drops out.
    val feedHealth by viewModel.feedHealth.collectAsStateWithLifecycle()
    val clockMs by rememberSportsLiveClockMs()
    val scoresStatus = remember(state.scores.mode, feedHealth, clockMs) {
        ScoresStatus.of(state.scores.mode, feedHealth.values, System.currentTimeMillis())
    }
    val context = LocalContext.current
    val pipSupported = remember(context) {
        sportsLivePipSupported(
            sdkInt = Build.VERSION.SDK_INT,
            hasPipFeature = context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        )
    }
    val pipSystemSetting = remember(context) { pipSystemSettingIntent(context) }

    // Saved, so the pane comes back on the same page after Team search (+ Add a team) returns.
    var page by rememberSaveable(stateSaver = PageSaver) { mutableStateOf(rootPage) }
    var moreExpanded by rememberSaveable { mutableStateOf(SPORTS_LIVE_MORE_EXPANDED_BY_DEFAULT) }
    var streamMemoryReset by remember { mutableStateOf(false) }
    var reportOpen by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<ScorePicker?>(null) }
    var filterKey by remember { mutableStateOf<String?>(null) }
    var unfollowTeam by remember { mutableStateOf<FollowedTeam?>(null) }
    var unfollowedIndex by remember { mutableStateOf<Int?>(null) }
    // The focused row (the help line follows it), and the row to focus once a page change has composed.
    var focusedKey by remember { mutableStateOf<String?>(null) }
    var pendingFocusKey by remember { mutableStateOf<String?>(null) }
    // The row that opened another screen (Run setup): Back from it lands there again (live.19 F19), not on
    // whichever row is nearest once the pane's first row has scrolled away.
    var returnFocusKey by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(focusedKey) {
        if (focusedKey != null && focusedKey != returnFocusKey) returnFocusKey = null
    }
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    fun requester(key: String) = requesters.getOrPut(key) { FocusRequester() }
    val rootListState = rememberLazyListState()
    val listState = if (page == rootPage) rootListState else key(page) { rememberLazyListState() }

    val items: List<Item> = when (val current = page) {
        SportsLiveSettingsPage.Main -> sportsLiveSettingsItems(
            moreExpanded = moreExpanded,
            setupAvailable = onRunSetup != null,
            pipSystemAvailable = pipSupported && pipSystemSetting != null,
            catalogCount = state.catalogs.size
        )
        SportsLiveSettingsPage.MyTeams -> myTeamsItems(state.followedTeams)
        SportsLiveSettingsPage.Hidden -> hiddenItems(hiddenTitles)
        SportsLiveSettingsPage.RemindedTeams -> remindedTeamsItems(remindedTeams)
        SportsLiveSettingsPage.MySports -> mySportsItems()
        is SportsLiveSettingsPage.Leagues -> sportLeagueItems(current.sport)
        SportsLiveSettingsPage.Catalogs -> state.catalogs.flatMap { catalog ->
            listOfNotNull(Item.Catalog(catalog.key), if (catalog.filterExtra != null) Item.CatalogFilter(catalog.key) else null)
        }
    }
    val currentItems by rememberUpdatedState(items)

    fun goTo(next: SportsLiveSettingsPage, focusKey: String?) {
        pendingFocusKey = focusKey ?: FIRST_ROW
        page = next
    }

    BackHandler(enabled = page != rootPage) { goTo(page.parent, page.openerKey) }

    LaunchedEffect(page) {
        if (page == SportsLiveSettingsPage.MySports) viewModel.onMySportsShown()
    }

    // Focus after a page change or an unfollow: the wanted row, scrolled into the list first if needed.
    LaunchedEffect(pendingFocusKey, page) {
        val wanted = pendingFocusKey ?: return@LaunchedEffect
        withFrameNanos { }
        val rows = currentItems
        val key = if (wanted == FIRST_ROW) rows.firstOrNull { it.isFocusable }?.key else wanted
        pendingFocusKey = null
        if (key == null) return@LaunchedEffect
        if (!runCatching { requester(key).requestFocus() }.getOrDefault(false)) {
            val index = rows.indexOfFirst { it.key == key }
            if (index >= 0) listState.scrollToItem(index + LIST_HEADER_ITEMS)
            withFrameNanos { }
            runCatching { requester(key).requestFocus() }
        }
    }

    // After OK → Stop following, focus goes to the team now in its place (the next, else the previous).
    LaunchedEffect(state.followedTeams) {
        val index = unfollowedIndex ?: return@LaunchedEffect
        unfollowedIndex = null
        val next = state.followedTeams.getOrNull(index.coerceAtMost(state.followedTeams.lastIndex))
        pendingFocusKey = next?.let { Item.Team(it).key } ?: Item.AddTeam.key
    }

    // The pane opens on Scores; while the section is hidden, on its Show switch, scrolled into view; back from Run
    // setup, on Run setup.
    val initialKey = sportsLiveSettingsInitialKey(
        onMainRoot = rootPage == SportsLiveSettingsPage.Main && page == rootPage,
        showSection = state.showSection,
        returnKey = returnFocusKey,
        firstFocusable = items.firstOrNull { it.isFocusable }?.key
    )
    LaunchedEffect(state.showSection) {
        if (rootPage != SportsLiveSettingsPage.Main || page != rootPage || state.showSection || focusedKey != null) return@LaunchedEffect
        val index = currentItems.indexOfFirst { it.key == initialKey }
        // Its section title stays in view above it.
        if (index > 0) rootListState.scrollToItem(index - 1 + LIST_HEADER_ITEMS)
    }

    fun rowModifier(item: Item): Modifier {
        val own = Modifier.focusRequester(requester(item.key))
        return if (initialFocusRequester != null && item.key == initialKey) {
            own.focusRequester(initialFocusRequester)
        } else {
            own
        }
    }

    // One setting's row: switches write through viewModel.setSwitch, pickers through the dialogs below.
    val settingRow: @Composable (SportsLiveSetting, Modifier, () -> Unit) -> Unit = { setting, modifier, onFocused ->
        val enabled = setting.offReason(state, pipSupported) == null
        val title = stringResource(setting.title)
        if (setting.isSwitch) {
            val stored = when (setting) {
                SportsLiveSetting.REMIND_MY_TEAMS -> alerts.remindMyTeams
                SportsLiveSetting.SCORE_ALERTS -> alerts.scoreAlerts
                SportsLiveSetting.FULL_TIME_ALERTS -> alerts.fullTimeAlerts
                SportsLiveSetting.CLOSE_GAME_ALERTS -> alerts.closeGameAlerts
                SportsLiveSetting.PICTURE_IN_PICTURE -> state.pipEnabled
                SportsLiveSetting.HIDE_UNPLAYABLE -> state.hideUnplayable
                SportsLiveSetting.SHOW_SECTION -> state.showSection
                SportsLiveSetting.HOME_ROW -> state.showHomeRow
                SportsLiveSetting.CATCH_UP_ROW -> state.catchUpRow
                SportsLiveSetting.MY_TEAMS_SPOILERS -> state.scoresChoice.myTeamsOnly
                SportsLiveSetting.REAL_TIME_SCORES -> state.scores.realTime
                SportsLiveSetting.HOVER_PREVIEW -> state.hoverPreview
                SportsLiveSetting.GUIDE_TABLE -> guideTable
                else -> state.dataSaver
            }
            // A row switched off by another setting reads Off; the help line says why.
            SportsLiveSettingRow(
                title = title,
                switchOn = stored && enabled,
                onClick = { viewModel.setSwitch(setting, !stored) },
                enabled = enabled,
                modifier = modifier,
                onFocused = onFocused
            )
        } else {
            val (value, onClick) = when (setting) {
                SportsLiveSetting.SCORES -> scoresModeLabel(state.scoresChoice.mode) to { picker = ScorePicker.MODE }
                SportsLiveSetting.SCORE_DELAY -> scoreDelayLabel(state.scores.delaySeconds) to { picker = ScorePicker.DELAY }
                SportsLiveSetting.MY_TEAMS -> (summarise(state.followedTeams.map { it.name })
                    ?: stringResource(R.string.sports_live_my_teams_none)) to { goTo(SportsLiveSettingsPage.MyTeams, null) }
                SportsLiveSetting.MY_SPORTS -> mySportsSummary(state.mySports) to { goTo(SportsLiveSettingsPage.MySports, null) }
                SportsLiveSetting.OPEN_GAME -> openGameLabel(state.openGame) to { picker = ScorePicker.OPEN_GAME }
                SportsLiveSetting.GAME_END -> gameEndLabel(gameEndAction) to { picker = ScorePicker.GAME_END }
                SportsLiveSetting.OK_ON_LIVE -> okOnLiveLabel(okOnLive) to { picker = ScorePicker.OK_ON_LIVE }
                SportsLiveSetting.TIP_OFF -> tipOffLabel(alerts.tipOff) to { picker = ScorePicker.TIP_OFF }
                SportsLiveSetting.REMINDED_TEAMS -> (summarise(remindedTeams.map { it.name })
                    ?: stringResource(R.string.sports_live_my_teams_none)) to { goTo(SportsLiveSettingsPage.RemindedTeams, null) }
                SportsLiveSetting.SCORES_STATUS -> scoresStatusText(scoresStatus) to { picker = ScorePicker.STATUS }
                SportsLiveSetting.RESET_STREAM_MEMORY ->
                    (if (streamMemoryReset) stringResource(R.string.sports_live_setting_reset_stream_memory_done) else null) to {
                        viewModel.resetStreamMemory()
                        streamMemoryReset = true
                    }
                SportsLiveSetting.CATALOGS -> state.catalogs.size.toString() to { goTo(SportsLiveSettingsPage.Catalogs, null) }
                SportsLiveSetting.HIDDEN -> hiddenTitles.size.toString() to { goTo(SportsLiveSettingsPage.Hidden, null) }
                SportsLiveSetting.REPORT_PROBLEM -> null to { reportOpen = true }
                SportsLiveSetting.RUN_SETUP -> null to {
                    returnFocusKey = Item.Setting(SportsLiveSetting.RUN_SETUP).key
                    onRunSetup?.invoke()
                    Unit
                }
                else -> null to {}
            }
            SportsLiveSettingRow(
                title = title,
                value = value,
                chevron = setting != SportsLiveSetting.RESET_STREAM_MEMORY,
                onClick = onClick,
                enabled = enabled,
                modifier = modifier,
                onFocused = onFocused
            )
        }
    }

    val itemRow: @Composable (Item) -> Unit = { item ->
        val modifier = rowModifier(item)
        val onFocused = { focusedKey = item.key }
        val sportsEnabled = rootPage != SportsLiveSettingsPage.Main || SportsLiveSetting.MY_SPORTS.isEnabled(state)
        val catalogsEnabled = SportsLiveSetting.CATALOGS.isEnabled(state)
        when (item) {
            is Item.Header -> SportsLiveSettingsSectionTitle(sectionTitle(item.section))
            is Item.Setting -> settingRow(item.setting, modifier, onFocused)
            Item.MoreToggle -> SportsLiveSettingRow(
                title = stringResource(R.string.sports_live_section_more_row),
                value = stringResource(if (moreExpanded) R.string.sports_live_more_hide else R.string.sports_live_more_show),
                onClick = { moreExpanded = !moreExpanded },
                modifier = modifier,
                onFocused = onFocused
            )
            Item.PipSystem -> SportsLiveSettingRow(
                title = stringResource(R.string.sports_live_setting_pip_system),
                chevron = true,
                onClick = { pipSystemSetting?.let { intent -> runCatching { context.startActivity(intent) } } },
                modifier = modifier,
                onFocused = onFocused
            )
            Item.AddTeam -> SportsLiveSettingRow(
                title = stringResource(R.string.sports_live_my_teams_add),
                value = stringResource(R.string.sports_live_my_teams_add_hint),
                chevron = true,
                onClick = onAddTeam,
                modifier = modifier,
                onFocused = onFocused
            )
            Item.NoTeams -> SportsLiveSettingsNote(stringResource(R.string.sports_live_my_teams_empty))
            is Item.HiddenTitle -> SportsLiveSettingRow(
                title = item.title,
                value = stringResource(R.string.sports_live_setting_hidden_show),
                onClick = { viewModel.unhide(item.title) },
                modifier = modifier,
                onFocused = onFocused
            )
            is Item.RemindedTeam -> SportsLiveSettingRow(
                title = item.team.name,
                tag = teamTag(item.team)?.uppercase(),
                value = stringResource(R.string.sports_live_setting_reminded_team_remove),
                onClick = { viewModel.removeRemindedTeam(item.team) },
                modifier = modifier,
                onFocused = onFocused
            )
            Item.NoRemindedTeams -> SportsLiveSettingRow(
                title = stringResource(R.string.sports_live_setting_reminded_teams_none),
                onClick = {},
                enabled = false,
                modifier = modifier,
                onFocused = onFocused
            )
            Item.NoHidden -> SportsLiveSettingRow(
                title = stringResource(R.string.sports_live_setting_hidden_none),
                onClick = {},
                enabled = false,
                modifier = modifier,
                onFocused = onFocused
            )
            is Item.Team -> SportsLiveSettingRow(
                title = item.team.name,
                tag = teamTag(item.team)?.uppercase(),
                followed = true,
                onClick = { unfollowTeam = item.team },
                modifier = modifier,
                onFocused = onFocused
            )
            is Item.Sport -> {
                val sport = item.sport
                val on = sport in state.mySports.effectiveSports
                if (sport.hasLeaguePage) {
                    SportsLiveSettingRow(
                        title = mySportLabel(sport),
                        value = sportLeaguesSummary(sport, state),
                        chevron = true,
                        onClick = { goTo(SportsLiveSettingsPage.Leagues(sport), null) },
                        enabled = sportsEnabled,
                        modifier = modifier,
                        onFocused = onFocused
                    )
                } else {
                    SportsLiveSettingRow(
                        title = mySportLabel(sport),
                        switchOn = on,
                        onClick = { viewModel.toggleMySport(sport) },
                        enabled = sportsEnabled,
                        modifier = modifier,
                        onFocused = onFocused
                    )
                }
            }
            Item.AllCompetitions -> SportsLiveSettingRow(
                title = stringResource(R.string.sports_live_my_sports_all_competitions),
                switchOn = state.mySports.allSoccerCompetitionsOn,
                onClick = viewModel::pressAllCompetitions,
                enabled = sportsEnabled,
                modifier = modifier,
                onFocused = onFocused
            )
            is Item.League -> SportsLiveSettingRow(
                title = myLeagueLabel(item.league),
                switchOn = state.mySports.leagueOn(item.league),
                onClick = { viewModel.pressMyLeague(item.league) },
                enabled = sportsEnabled,
                modifier = modifier,
                onFocused = onFocused
            )
            is Item.Catalog -> state.catalogs.firstOrNull { it.key == item.catalogKey }?.let { catalog ->
                SportsLiveSettingRow(
                    title = "${catalog.addonName} · ${catalog.catalogName}",
                    value = catalogStatus(catalog, withReason = false),
                    onClick = { viewModel.cycleOverride(catalog.key) },
                    enabled = catalogsEnabled,
                    modifier = modifier,
                    onFocused = onFocused
                )
            }
            is Item.CatalogFilter -> state.catalogs.firstOrNull { it.key == item.catalogKey }?.let { catalog ->
                SportsLiveSettingRow(
                    title = stringResource(
                        R.string.sports_live_filter_action,
                        catalog.filterValue ?: stringResource(R.string.sports_live_filter_all_option)
                    ),
                    chevron = true,
                    onClick = { filterKey = catalog.key },
                    enabled = catalogsEnabled,
                    modifier = modifier,
                    onFocused = onFocused
                )
            }
        }
    }

    val focusedItem = items.firstOrNull { it.key == focusedKey }
    val help = focusedItem?.let { sportsLiveHelpFor(it, state, pipSupported) }
    val helpPrefix = (focusedItem as? Item.Catalog)?.let { item ->
        state.catalogs.firstOrNull { it.key == item.catalogKey }?.let { catalog ->
            listOfNotNull(catalogStatus(catalog, withReason = true), catalog.stats?.let { catalogStats(it, catalog.filterValue) })
                .joinToString(" · ")
        }
    }
    val helpText = help?.let { helpLineText(it, helpPrefix) }

    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(SportsLiveTokens.Settings.RowGap),
            // Room for the focused row's ring and lift.
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
        ) {
            item(key = "page_header") { PageHeader(page, newSportsHint) }
            items(items, key = { it.key }) { item -> itemRow(item) }
        }
        SportsLiveHelpLine(helpText)
    }

    when (picker) {
        ScorePicker.MODE -> SettingsSingleChoiceDialog(
            title = stringResource(R.string.sports_live_setting_scores),
            options = ScoresMode.entries.map { SettingsPickerOption(it, scoresModeLabel(it)) },
            selectedValue = state.scoresChoice.mode,
            onOptionSelected = { mode ->
                picker = null
                viewModel.setScoresMode(mode)
            },
            onDismiss = { picker = null }
        )
        ScorePicker.DELAY -> SettingsSingleChoiceDialog(
            title = stringResource(R.string.sports_live_setting_score_delay),
            subtitle = stringResource(R.string.sports_live_setting_score_delay_subtitle),
            options = ScoreDelayChoices.SECONDS.map { SettingsPickerOption(it, scoreDelayLabel(it)) },
            selectedValue = state.scores.delaySeconds,
            onOptionSelected = { seconds ->
                picker = null
                viewModel.setScoreDelaySeconds(seconds)
            },
            onDismiss = { picker = null }
        )
        ScorePicker.OPEN_GAME -> SettingsSingleChoiceDialog(
            title = stringResource(R.string.sports_live_setting_open_game),
            subtitle = stringResource(R.string.sports_live_setting_open_game_subtitle),
            options = SportsLiveOpenGame.entries.map { SettingsPickerOption(it, openGameLabel(it)) },
            selectedValue = state.openGame,
            onOptionSelected = { openGame ->
                picker = null
                viewModel.setOpenGame(openGame)
            },
            onDismiss = { picker = null }
        )
        ScorePicker.GAME_END -> SettingsSingleChoiceDialog(
            title = stringResource(R.string.sports_live_setting_game_end),
            subtitle = stringResource(R.string.sports_live_setting_game_end_help),
            options = SportsLiveGameEndAction.entries.map { SettingsPickerOption(it, gameEndLabel(it)) },
            selectedValue = gameEndAction,
            onOptionSelected = { action ->
                picker = null
                viewModel.setGameEndAction(action)
            },
            onDismiss = { picker = null }
        )
        ScorePicker.OK_ON_LIVE -> SettingsSingleChoiceDialog(
            title = stringResource(R.string.sports_live_setting_ok_on_live),
            subtitle = stringResource(R.string.sports_live_setting_ok_on_live_help),
            options = SportsLiveOkOnLive.entries.map { SettingsPickerOption(it, okOnLiveLabel(it)) },
            selectedValue = okOnLive,
            onOptionSelected = { value ->
                picker = null
                viewModel.setOkOnLive(value)
            },
            onDismiss = { picker = null }
        )
        ScorePicker.TIP_OFF -> SettingsSingleChoiceDialog(
            title = stringResource(R.string.sports_live_setting_tip_off),
            subtitle = stringResource(R.string.sports_live_setting_tip_off_help),
            options = SportsLiveTipOff.entries.map { SettingsPickerOption(it, tipOffLabel(it)) },
            selectedValue = alerts.tipOff,
            onOptionSelected = { value ->
                picker = null
                viewModel.setTipOff(value)
            },
            onDismiss = { picker = null }
        )
        ScorePicker.STATUS -> ScoresStatusDialog(
            status = scoresStatus,
            onRetry = viewModel::retryScores,
            onDismiss = { picker = null }
        )
        null -> Unit
    }

    if (reportOpen) SportsLiveReportDialog(onDismiss = { reportOpen = false })

    state.catalogs.firstOrNull { it.key == filterKey }?.let { catalog ->
        val extra = catalog.filterExtra ?: return@let
        SettingsSingleChoiceDialog(
            title = stringResource(R.string.sports_live_filter_picker_title),
            subtitle = "${catalog.addonName} · ${catalog.catalogName}",
            options = listOf(SettingsPickerOption<String?>(null, stringResource(R.string.sports_live_filter_all_option))) +
                catalog.filterOptions.map { SettingsPickerOption<String?>(it, it) },
            selectedValue = catalog.filterValue,
            onOptionSelected = { value ->
                filterKey = null
                viewModel.setCatalogFilter(catalog.key, extra, value)
            },
            onDismiss = { filterKey = null },
            maxHeight = 420.dp
        )
    }

    unfollowTeam?.let { team ->
        UnfollowDialog(
            team = team,
            onConfirm = {
                unfollowTeam = null
                unfollowedIndex = state.followedTeams.indexOf(team).coerceAtLeast(0)
                viewModel.unfollow(team)
            },
            onDismiss = { unfollowTeam = null }
        )
    }
}

/**
 * The row the pane's first focus goes to: a sub-page's first row; on the main page the row that opened another
 * screen ([returnKey], Back from Run setup), else Scores, or Show Sports & Live while the section is hidden.
 */
internal fun sportsLiveSettingsInitialKey(onMainRoot: Boolean, showSection: Boolean, returnKey: String?, firstFocusable: String?): String? = when {
    !onMainRoot -> firstFocusable
    returnKey != null -> returnKey
    showSection -> Item.Setting(SportsLiveSetting.SCORES).key
    else -> Item.Setting(SportsLiveSetting.SHOW_SECTION).key
}

/** A page as a saved token: "main", "teams", "leagues:soccer"… (unknown tokens fall back to Main). */
internal val SportsLiveSettingsPage.token: String
    get() = when (this) {
        SportsLiveSettingsPage.Main -> "main"
        SportsLiveSettingsPage.MyTeams -> "teams"
        SportsLiveSettingsPage.MySports -> "sports"
        is SportsLiveSettingsPage.Leagues -> LEAGUES_PREFIX + sport.id
        SportsLiveSettingsPage.Catalogs -> "catalogs"
        SportsLiveSettingsPage.Hidden -> "hidden"
        SportsLiveSettingsPage.RemindedTeams -> "reminded_teams"
    }

internal fun sportsLiveSettingsPageOf(token: String): SportsLiveSettingsPage = when (token) {
    "teams" -> SportsLiveSettingsPage.MyTeams
    "sports" -> SportsLiveSettingsPage.MySports
    "catalogs" -> SportsLiveSettingsPage.Catalogs
    "hidden" -> SportsLiveSettingsPage.Hidden
    "reminded_teams" -> SportsLiveSettingsPage.RemindedTeams
    else -> if (token.startsWith(LEAGUES_PREFIX)) {
        MySport.ofId(token.removePrefix(LEAGUES_PREFIX))?.let { SportsLiveSettingsPage.Leagues(it) } ?: SportsLiveSettingsPage.Main
    } else {
        SportsLiveSettingsPage.Main
    }
}

private const val LEAGUES_PREFIX = "leagues:"

private val PageSaver = Saver<SportsLiveSettingsPage, String>(save = { it.token }, restore = ::sportsLiveSettingsPageOf)

/** The dialog open: scores mode, score delay, "When I open a game", or Scores status. */
private enum class ScorePicker { MODE, DELAY, OPEN_GAME, GAME_END, OK_ON_LIVE, TIP_OFF, STATUS }

/** Asked for the first row of a page, once it has composed. */
private const val FIRST_ROW = "\u0000first"

/** Lazy items before the rows: the page header. */
private const val LIST_HEADER_ITEMS = 1

@Composable
private fun PageHeader(page: SportsLiveSettingsPage, newSportsHint: Boolean) {
    val (title, subtitle) = when (page) {
        SportsLiveSettingsPage.Main ->
            stringResource(R.string.settings_sports_live) to stringResource(R.string.sports_live_settings_subtitle)
        SportsLiveSettingsPage.MyTeams ->
            stringResource(R.string.sports_live_my_teams_title) to stringResource(R.string.sports_live_my_teams_subtitle)
        SportsLiveSettingsPage.MySports -> stringResource(R.string.sports_live_my_sports_title) to listOfNotNull(
            stringResource(R.string.sports_live_my_sports_page_subtitle),
            if (newSportsHint) stringResource(R.string.sports_live_my_sports_new_sports) else null
        ).joinToString(" · ")
        is SportsLiveSettingsPage.Leagues -> mySportLabel(page.sport).let { sport ->
            sport to stringResource(R.string.sports_live_my_sports_leagues_subtitle, sport)
        }
        SportsLiveSettingsPage.Catalogs ->
            stringResource(R.string.sports_live_setting_catalogs) to stringResource(R.string.sports_live_setting_catalogs_subtitle)
        SportsLiveSettingsPage.Hidden ->
            stringResource(R.string.sports_live_setting_hidden) to stringResource(R.string.sports_live_setting_hidden_subtitle)
        SportsLiveSettingsPage.RemindedTeams ->
            stringResource(R.string.sports_live_setting_reminded_teams) to stringResource(R.string.sports_live_setting_reminded_teams_help)
    }
    SettingsDetailHeader(title = title, subtitle = subtitle)
}

/** "Stop following ‹team›?" (#89): OK on a team no longer unfollows at once. Cancel has focus. */
@Composable
private fun UnfollowDialog(team: FollowedTeam, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.sports_live_unfollow_title, team.name),
        subtitle = stringResource(R.string.sports_live_unfollow_body)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(SportsLiveTokens.Size.ButtonGap)) {
            SportsLiveButton(onClick = onConfirm, role = SportsLiveButtonRole.PRIMARY) {
                Text(stringResource(R.string.sports_live_unfollow_confirm), style = SportsLiveTokens.style(SportsLiveTokens.Type.Button))
            }
            SportsLiveButton(onClick = onDismiss, modifier = Modifier.focusRequester(cancelFocus)) {
                Text(stringResource(R.string.action_cancel), style = SportsLiveTokens.style(SportsLiveTokens.Type.Button))
            }
        }
    }
}

/** The help line's text: the reason a row is off, else its explanation, naming what it switches off now. */
@Composable
private fun helpLineText(help: SportsLiveHelp, prefix: String?): String {
    help.offReason?.let { return stringResource(it.text) }
    val base = stringResource(help.text).let { text -> if (prefix.isNullOrEmpty()) text else "$prefix. $text" }
    val off = help.switchesOff
    return when {
        off.isEmpty() -> base
        off.size <= HELP_MAX_NAMED -> stringResource(
            R.string.sports_live_help_switches_off,
            base,
            off.map { stringResource(it.title) }.joinToString(", ")
        )
        else -> stringResource(R.string.sports_live_help_switches_off_count, base, off.size)
    }
}

/** Beyond this many, the help line counts the rows a setting switches off instead of naming them. */
private const val HELP_MAX_NAMED = 4

@Composable
private fun mySportsSummary(mySports: MySports): String =
    if (mySports.isDefault) stringResource(R.string.sports_live_my_sports_all_sports)
    else summarise(mySports.effectiveSports.map { mySportLabel(it) }, max = 3).orEmpty()

/** A sport's row value: Off, "All competitions" (soccer), or its leagues ("NBA, College"). */
@Composable
private fun sportLeaguesSummary(sport: MySport, state: SportsLiveSettingsUiState): String {
    val mySports = state.mySports
    return when {
        sport !in mySports.effectiveSports -> stringResource(R.string.sports_live_switch_off)
        sport == MySport.SOCCER && mySports.allSoccerCompetitionsOn -> stringResource(R.string.sports_live_my_sports_all_competitions)
        else -> summarise(mySports.leaguesOf(sport).sortedBy { it.ordinal }.map { myLeagueLabel(it) }).orEmpty()
    }
}

/** A followed team's league tag ("NBA", "Premier League"), from its stored ESPN feed; else its sport word. */
@Composable
private fun teamTag(team: FollowedTeam): String? {
    val parts = team.league?.split('/')
    val league = parts?.takeIf { it.size == 2 }?.let { EspnLeague(it[0], it[1]) }
    val mine = league?.let { feed -> MyLeague.entries.firstOrNull { it.coversLeague(feed) } }
    return mine?.let { myLeagueLabel(it) } ?: team.sport
}

@Composable
private fun scoresModeLabel(mode: ScoresMode): String = stringResource(
    when (mode) {
        ScoresMode.SHOW -> R.string.sports_live_scores_show
        ScoresMode.HIDE_UNTIL_FOCUSED -> R.string.sports_live_scores_hide_until_focused
        ScoresMode.OFF -> R.string.sports_live_scores_off
    }
)

@Composable
private fun openGameLabel(openGame: SportsLiveOpenGame): String = stringResource(
    when (openGame) {
        SportsLiveOpenGame.PLAY_BEST -> R.string.sports_live_open_game_play_best
        SportsLiveOpenGame.LET_ME_CHOOSE -> R.string.sports_live_open_game_let_me_choose
    }
)

@Composable
private fun okOnLiveLabel(value: SportsLiveOkOnLive): String = stringResource(
    when (value) {
        SportsLiveOkOnLive.SHOW_CONTROLS -> R.string.sports_live_ok_on_live_controls
        SportsLiveOkOnLive.PAUSE -> R.string.sports_live_ok_on_live_pause
    }
)

@Composable
private fun tipOffLabel(value: SportsLiveTipOff): String = stringResource(
    when (value) {
        SportsLiveTipOff.REMIND -> R.string.sports_live_tip_off_remind
        SportsLiveTipOff.SWITCH -> R.string.sports_live_tip_off_switch
    }
)

@Composable
private fun gameEndLabel(action: SportsLiveGameEndAction): String = stringResource(
    when (action) {
        SportsLiveGameEndAction.MOVE -> R.string.sports_live_game_end_move
        SportsLiveGameEndAction.ASK -> R.string.sports_live_game_end_ask
        SportsLiveGameEndAction.STAY -> R.string.sports_live_game_end_stay
    }
)

@Composable
private fun scoreDelayLabel(seconds: Int): String =
    if (seconds <= 0) stringResource(R.string.sports_live_score_delay_off)
    else stringResource(R.string.sports_live_score_delay_seconds, seconds)

/** "Included", "Excluded", "Auto · in Sports & Live" (with its reason in the help line), "Auto · on Home". */
@Composable
private fun catalogStatus(catalog: SportsLiveCatalogSetting, withReason: Boolean): String = when (catalog.override) {
    SportsLiveOverride.INCLUDE -> stringResource(R.string.sports_live_override_included)
    SportsLiveOverride.EXCLUDE -> stringResource(R.string.sports_live_override_excluded)
    null -> catalog.autoClaim?.let {
        val auto = stringResource(R.string.sports_live_override_auto_in)
        if (withReason) "$auto (${it.reason})" else auto
    } ?: stringResource(R.string.sports_live_override_auto_out)
}

/**
 * "12 live · 8 timed · 3 unrecognised": the non-zero counts, or "No items" ("No items for United States"
 * when a pinned [filterValue] emptied it).
 */
@Composable
private fun catalogStats(stats: CatalogStats, filterValue: String?): String {
    val parts = listOf(
        R.plurals.sports_live_stat_live to stats.live,
        R.plurals.sports_live_stat_timed to stats.timed,
        R.plurals.sports_live_stat_time_tba to stats.upcomingTba,
        R.plurals.sports_live_stat_channels to stats.channels,
        R.plurals.sports_live_stat_replays to stats.replays,
        R.plurals.sports_live_stat_ended to stats.ended,
        R.plurals.sports_live_stat_unrecognised to stats.unrecognised
    ).filter { it.second > 0 }.map { (res, count) -> pluralStringResource(res, count, count) }
    return when {
        parts.isNotEmpty() -> parts.joinToString(" · ")
        filterValue != null -> stringResource(R.string.sports_live_stat_no_items_for, filterValue)
        else -> stringResource(R.string.sports_live_stat_empty)
    }
}

@Composable
private fun sectionTitle(section: SportsLiveSettingsSection): String = stringResource(
    when (section) {
        SportsLiveSettingsSection.SCORES_AND_SPOILERS -> R.string.sports_live_section_scores_and_spoilers
        SportsLiveSettingsSection.WHAT_I_FOLLOW -> R.string.sports_live_section_what_i_follow
        SportsLiveSettingsSection.ALERTS -> R.string.sports_live_alerts_title
        SportsLiveSettingsSection.PLAYING -> R.string.sports_live_section_playing
        SportsLiveSettingsSection.HOME_AND_ROWS -> R.string.sports_live_section_home_and_rows
        SportsLiveSettingsSection.SETUP -> R.string.sports_live_section_setup
        SportsLiveSettingsSection.STATUS -> R.string.sports_live_section_status
        SportsLiveSettingsSection.MORE -> R.string.sports_live_section_more
    }
)

/** The system's picture-in-picture setting (this app's page when it has one); null when nothing handles it. */
private fun pipSystemSettingIntent(context: Context): Intent? {
    val pm = context.packageManager
    return listOf(
        Intent(SPORTS_LIVE_PIP_SETTINGS_ACTION, Uri.fromParts("package", context.packageName, null)),
        Intent(SPORTS_LIVE_PIP_SETTINGS_ACTION)
    ).firstOrNull { it.resolveActivity(pm) != null }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
