package com.nuvio.tv.ui.screens.settings

import androidx.annotation.StringRes
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.scores.MyLeague
import com.nuvio.tv.core.sportslive.scores.MySport
import com.nuvio.tv.core.sportslive.scores.MySports
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.domain.model.FollowedTeam

/** The sections of Settings → Sports & Live (#72), in screen order: by use, Scores first (#134 V2). */
internal enum class SportsLiveSettingsSection {
    SCORES_AND_SPOILERS,
    WHAT_I_FOLLOW,
    ALERTS,
    PLAYING,
    HOME_AND_ROWS,
    /** "Run setup again" (#134 phase 7); the section shows only once first-run exists. */
    SETUP,
    STATUS,
    /** Rarely used options, behind a "More" row that starts collapsed. */
    MORE
}

/**
 * Every setting on the Sports & Live screen, in screen order, with its section. The screen draws them
 * from this list, so each one appears exactly once.
 */
internal enum class SportsLiveSetting(
    val section: SportsLiveSettingsSection,
    @StringRes val title: Int,
    /** Its explanation in the help line. */
    @StringRes val help: Int
) {
    SCORES(SportsLiveSettingsSection.SCORES_AND_SPOILERS, R.string.sports_live_setting_scores, R.string.sports_live_setting_scores_help),
    SCORE_DELAY(SportsLiveSettingsSection.SCORES_AND_SPOILERS, R.string.sports_live_setting_score_delay, R.string.sports_live_setting_score_delay_subtitle),
    /**
     * "Hide scores for my teams only" (#151, research item 5): on is Hide until I reveal for my teams' games only
     * ([com.nuvio.tv.core.sportslive.scores.ScoresChoice]); off by default.
     */
    MY_TEAMS_SPOILERS(SportsLiveSettingsSection.SCORES_AND_SPOILERS, R.string.sports_live_setting_my_teams_spoilers, R.string.sports_live_setting_my_teams_spoilers_subtitle),
    MY_TEAMS(SportsLiveSettingsSection.WHAT_I_FOLLOW, R.string.sports_live_my_teams_title, R.string.sports_live_my_teams_subtitle),
    MY_SPORTS(SportsLiveSettingsSection.WHAT_I_FOLLOW, R.string.sports_live_my_sports_title, R.string.sports_live_my_sports_subtitle),
    REMIND_MY_TEAMS(SportsLiveSettingsSection.ALERTS, R.string.sports_live_setting_remind_my_teams, R.string.sports_live_setting_remind_my_teams_subtitle),
    /** "At tip-off" (research Q17): Remind me (a toast) / Switch automatically (opt-in, 10 s countdown). */
    TIP_OFF(SportsLiveSettingsSection.ALERTS, R.string.sports_live_setting_tip_off, R.string.sports_live_setting_tip_off_help),
    SCORE_ALERTS(SportsLiveSettingsSection.ALERTS, R.string.sports_live_setting_score_alerts, R.string.sports_live_setting_score_alerts_subtitle),
    FULL_TIME_ALERTS(SportsLiveSettingsSection.ALERTS, R.string.sports_live_setting_full_time_alerts, R.string.sports_live_setting_full_time_alerts_subtitle),
    /** Crunch-time alerts (#137): close games late in My sports. */
    CLOSE_GAME_ALERTS(SportsLiveSettingsSection.ALERTS, R.string.sports_live_setting_close_game_alerts, R.string.sports_live_setting_close_game_alerts_subtitle),
    /** Reminder-only teams (#154): a summary row opening its page, where OK takes a team off. */
    REMINDED_TEAMS(SportsLiveSettingsSection.ALERTS, R.string.sports_live_setting_reminded_teams, R.string.sports_live_setting_reminded_teams_help),
    OPEN_GAME(SportsLiveSettingsSection.PLAYING, R.string.sports_live_setting_open_game, R.string.sports_live_setting_open_game_subtitle),
    /** "OK on live" (research Q19): Show controls (default) / Pause. */
    OK_ON_LIVE(SportsLiveSettingsSection.PLAYING, R.string.sports_live_setting_ok_on_live, R.string.sports_live_setting_ok_on_live_help),
    /** "When a game ends" (#161): Move to the next game / Ask / Stay. */
    GAME_END(SportsLiveSettingsSection.PLAYING, R.string.sports_live_setting_game_end, R.string.sports_live_setting_game_end_help),
    /** "Hide links this TV can't play" (#166): DRM links with no licence are left out of the stream lists. */
    HIDE_UNPLAYABLE(SportsLiveSettingsSection.PLAYING, R.string.sports_live_setting_hide_unplayable, R.string.sports_live_setting_hide_unplayable_subtitle),
    PICTURE_IN_PICTURE(SportsLiveSettingsSection.PLAYING, R.string.sports_live_setting_pip, R.string.sports_live_setting_pip_subtitle),
    SHOW_SECTION(SportsLiveSettingsSection.HOME_AND_ROWS, R.string.sports_live_setting_show_section, R.string.sports_live_setting_show_section_subtitle),
    HOME_ROW(SportsLiveSettingsSection.HOME_AND_ROWS, R.string.sports_live_setting_home_row, R.string.sports_live_setting_home_row_subtitle),
    CATCH_UP_ROW(SportsLiveSettingsSection.HOME_AND_ROWS, R.string.sports_live_setting_catch_up_row, R.string.sports_live_setting_catch_up_row_subtitle),
    HOVER_PREVIEW(SportsLiveSettingsSection.HOME_AND_ROWS, R.string.sports_live_setting_hover_preview, R.string.sports_live_setting_hover_preview_subtitle),
    RUN_SETUP(SportsLiveSettingsSection.SETUP, R.string.sports_live_setting_run_setup, R.string.sports_live_setting_run_setup_subtitle),
    SCORES_STATUS(SportsLiveSettingsSection.STATUS, R.string.sports_live_setting_scores_status, R.string.sports_live_setting_scores_status_subtitle),
    /** "In-player guide" (live.21 design B2, out of beta in live.23): Side panel / Table (smooth) / Table (jump), a picker; Table (smooth) by default. */
    GUIDE_TABLE(SportsLiveSettingsSection.MORE, R.string.sports_live_setting_guide_table, R.string.sports_live_setting_guide_table_help),
    DATA_SAVER(SportsLiveSettingsSection.MORE, R.string.sports_live_setting_data_saver, R.string.sports_live_setting_data_saver_subtitle),
    /** "Real-time scores" (live.20 Q18, #85): opt-in, every score delay off, also while video plays. */
    REAL_TIME_SCORES(SportsLiveSettingsSection.MORE, R.string.sports_live_setting_real_time_scores, R.string.sports_live_setting_real_time_scores_help),
    RESET_STREAM_MEMORY(SportsLiveSettingsSection.MORE, R.string.sports_live_setting_reset_stream_memory, R.string.sports_live_setting_reset_stream_memory_subtitle),
    /** Titles hidden from Live Now (#134): a summary row opening its page, where OK shows one again. */
    HIDDEN(SportsLiveSettingsSection.MORE, R.string.sports_live_setting_hidden, R.string.sports_live_setting_hidden_row_help),
    CATALOGS(SportsLiveSettingsSection.MORE, R.string.sports_live_setting_catalogs, R.string.sports_live_setting_catalogs_row_help),
    /** "Report a problem" (#172): opens the last report (its QR code and voice note), saving one when there is none. */
    REPORT_PROBLEM(SportsLiveSettingsSection.MORE, R.string.sports_live_setting_report_problem, R.string.sports_live_setting_report_problem_help);

    /**
     * Whether the row can be used. A row that can't stays focusable, so the help line can say why (#89),
     * but OK does nothing. Unchanged from the screen before #72: almost everything needs the section
     * shown; the rest as noted.
     */
    fun isEnabled(state: SportsLiveSettingsUiState): Boolean {
        val section = state.showSection
        val scores = state.scores.mode
        return when (this) {
            SHOW_SECTION, RESET_STREAM_MEMORY, MY_TEAMS, RUN_SETUP, HIDDEN, REPORT_PROBLEM -> true
            SCORE_DELAY, FULL_TIME_ALERTS, CLOSE_GAME_ALERTS, MY_TEAMS_SPOILERS -> section && scores != ScoresMode.OFF
            SCORE_ALERTS -> section && scores == ScoresMode.SHOW
            // Scores Off means no score data at all, and the row needs the section's Home row: off with either.
            CATCH_UP_ROW -> section && state.showHomeRow && scores != ScoresMode.OFF
            HOVER_PREVIEW -> section && !state.dataSaver
            // The device's own picture-in-picture support is checked by the screen on top of this.
            MY_SPORTS, SCORES, OPEN_GAME, GAME_END, HIDE_UNPLAYABLE, PICTURE_IN_PICTURE, HOME_ROW, REMIND_MY_TEAMS, REMINDED_TEAMS,
            TIP_OFF, OK_ON_LIVE,
            SCORES_STATUS, DATA_SAVER, REAL_TIME_SCORES, CATALOGS, GUIDE_TABLE -> section
        }
    }

    /** True for the rows OK switches On / Off; the rest open a picker, a page or run an action. */
    val isSwitch: Boolean
        get() = this in SWITCHES

    private companion object {
        val SWITCHES = setOf(
            REMIND_MY_TEAMS, SCORE_ALERTS, FULL_TIME_ALERTS, CLOSE_GAME_ALERTS, HIDE_UNPLAYABLE, PICTURE_IN_PICTURE, SHOW_SECTION, HOME_ROW,
            CATCH_UP_ROW, HOVER_PREVIEW, DATA_SAVER, MY_TEAMS_SPOILERS, REAL_TIME_SCORES
        )
    }
}

/**
 * Why a row is switched off (#89), and the setting responsible ([cause]; null when it is the device).
 * The help line says it in place of the row's explanation, with the fix.
 */
internal enum class SportsLiveOffReason(val cause: SportsLiveSetting?, @StringRes val text: Int) {
    SECTION_HIDDEN(SportsLiveSetting.SHOW_SECTION, R.string.sports_live_off_section_hidden),
    SCORES_OFF(SportsLiveSetting.SCORES, R.string.sports_live_off_scores_off),
    SCORES_NOT_SHOW(SportsLiveSetting.SCORES, R.string.sports_live_off_scores_not_show),
    HOME_ROW_OFF(SportsLiveSetting.HOME_ROW, R.string.sports_live_off_home_row),
    DATA_SAVER_ON(SportsLiveSetting.DATA_SAVER, R.string.sports_live_off_data_saver),
    PIP_UNSUPPORTED(null, R.string.sports_live_off_pip_unsupported)
}

/**
 * Why this setting is switched off in [state], or null when it can be used. [pipSupported] is the
 * device's picture-in-picture support (the one reason that is not another setting). The first cause
 * wins: the device, then the hidden section, then the setting that greys this row out.
 */
internal fun SportsLiveSetting.offReason(state: SportsLiveSettingsUiState, pipSupported: Boolean = true): SportsLiveOffReason? {
    if (this == SportsLiveSetting.PICTURE_IN_PICTURE && !pipSupported) return SportsLiveOffReason.PIP_UNSUPPORTED
    if (isEnabled(state)) return null
    if (!state.showSection) return SportsLiveOffReason.SECTION_HIDDEN
    return when (this) {
        SportsLiveSetting.SCORE_DELAY, SportsLiveSetting.FULL_TIME_ALERTS, SportsLiveSetting.CLOSE_GAME_ALERTS, SportsLiveSetting.MY_TEAMS_SPOILERS -> SportsLiveOffReason.SCORES_OFF
        SportsLiveSetting.SCORE_ALERTS -> SportsLiveOffReason.SCORES_NOT_SHOW
        SportsLiveSetting.CATCH_UP_ROW ->
            if (!state.showHomeRow) SportsLiveOffReason.HOME_ROW_OFF else SportsLiveOffReason.SCORES_OFF
        SportsLiveSetting.HOVER_PREVIEW -> SportsLiveOffReason.DATA_SAVER_ON
        // Every other row only needs the section, handled above.
        else -> SportsLiveOffReason.SECTION_HIDDEN
    }
}

/** The settings [cause] is switching off right now, in screen order: its help line names them (#89). */
internal fun sportsLiveSwitchedOffBy(cause: SportsLiveSetting, state: SportsLiveSettingsUiState): List<SportsLiveSetting> =
    SportsLiveSetting.entries.filter { it != cause && it.offReason(state)?.cause == cause }

/** One row (or plain header) of a Sports & Live settings page; [key] keeps it stable in a lazy list. */
internal sealed interface SportsLiveSettingsItem {
    val key: String

    /** A section title: plain text, never focused. */
    data class Header(val section: SportsLiveSettingsSection) : SportsLiveSettingsItem {
        override val key get() = "section_${section.name}"
    }

    data class Setting(val setting: SportsLiveSetting) : SportsLiveSettingsItem {
        override val key get() = "setting_${setting.name}"
    }

    /** "More settings": opens or closes the More section. */
    data object MoreToggle : SportsLiveSettingsItem {
        override val key get() = "more_toggle"
    }

    /** The TV's own picture-in-picture setting for this app, under the switch, where the TV has one. */
    data object PipSystem : SportsLiveSettingsItem {
        override val key get() = "pip_system"
    }

    /** "+ Add a team" (#149): first on the My teams page; opens Team search. */
    data object AddTeam : SportsLiveSettingsItem {
        override val key get() = "add_team"
    }

    /** My teams with no team yet: a plain note under "+ Add a team", never focused. */
    data object NoTeams : SportsLiveSettingsItem {
        override val key get() = "no_teams"
    }

    /** A title hidden from Live Now (#134): OK shows it there again. */
    data class HiddenTitle(val title: String) : SportsLiveSettingsItem {
        override val key get() = "hidden_$title"
    }

    /** A reminder-only team (#154): OK takes it off the list. */
    data class RemindedTeam(val team: FollowedTeam) : SportsLiveSettingsItem {
        override val key get() = "reminded_${team.espnId ?: team.name}_${team.league ?: team.sport}"
    }

    /** No reminder-only team: one row that says how to add one (OK does nothing). */
    data object NoRemindedTeams : SportsLiveSettingsItem {
        override val key get() = "no_reminded_teams"
    }

    /** Nothing hidden: one row that says how to hide a title (it keeps focus on the page; OK does nothing). */
    data object NoHidden : SportsLiveSettingsItem {
        override val key get() = "no_hidden"
    }

    data class Team(val team: FollowedTeam) : SportsLiveSettingsItem {
        override val key get() = "team_${team.espnId ?: team.name}_${team.sport}"
    }

    data class Sport(val sport: MySport) : SportsLiveSettingsItem {
        override val key get() = "sport_${sport.id}"
    }

    /** Soccer's "All competitions" switch on its leagues page. */
    data object AllCompetitions : SportsLiveSettingsItem {
        override val key get() = "all_competitions"
    }

    data class League(val league: MyLeague) : SportsLiveSettingsItem {
        override val key get() = "league_${league.token}"
    }

    data class Catalog(val catalogKey: String) : SportsLiveSettingsItem {
        override val key get() = "catalog_$catalogKey"
    }

    /** A catalog's pinned filter value ("Filter: United States"), under its catalog. */
    data class CatalogFilter(val catalogKey: String) : SportsLiveSettingsItem {
        override val key get() = "catalog_filter_$catalogKey"
    }
}

/**
 * The main page's rows, top to bottom (#134 V2): sections by use, Scores first. Setup shows only with
 * [setupAvailable] (first-run, phase 7); the PiP system row only with [pipSystemAvailable]; More's rows
 * only while [moreExpanded]; Catalogs only when there are [catalogCount] > 0.
 */
internal fun sportsLiveSettingsItems(
    moreExpanded: Boolean,
    setupAvailable: Boolean,
    pipSystemAvailable: Boolean,
    catalogCount: Int
): List<SportsLiveSettingsItem> = buildList {
    for ((section, settings) in sportsLiveSettingsLayout()) {
        val shown = settings.filter { setting ->
            when (setting) {
                SportsLiveSetting.RUN_SETUP -> setupAvailable
                SportsLiveSetting.CATALOGS -> catalogCount > 0
                else -> true
            }
        }
        if (shown.isEmpty() && section != SportsLiveSettingsSection.MORE) continue
        add(SportsLiveSettingsItem.Header(section))
        if (section == SportsLiveSettingsSection.MORE) {
            add(SportsLiveSettingsItem.MoreToggle)
            if (!moreExpanded) continue
        }
        for (setting in shown) {
            add(SportsLiveSettingsItem.Setting(setting))
            if (setting == SportsLiveSetting.PICTURE_IN_PICTURE && pipSystemAvailable) add(SportsLiveSettingsItem.PipSystem)
        }
    }
}

/** My teams' page: "+ Add a team" first (#149), then the teams in follow order, or a note when there are none. */
internal fun myTeamsItems(teams: List<FollowedTeam>): List<SportsLiveSettingsItem> =
    listOf(SportsLiveSettingsItem.AddTeam) +
        (if (teams.isEmpty()) listOf(SportsLiveSettingsItem.NoTeams) else teams.map { SportsLiveSettingsItem.Team(it) })

/** More, Hidden's page: the hidden titles in the order given (A to Z), or a note when there are none. */
internal fun hiddenItems(titles: List<String>): List<SportsLiveSettingsItem> =
    if (titles.isEmpty()) listOf(SportsLiveSettingsItem.NoHidden) else titles.map { SportsLiveSettingsItem.HiddenTitle(it) }

/** Alerts → "Teams I'm reminded about" (#154): the reminder-only teams in the order added, or a note. */
internal fun remindedTeamsItems(teams: List<FollowedTeam>): List<SportsLiveSettingsItem> =
    if (teams.isEmpty()) listOf(SportsLiveSettingsItem.NoRemindedTeams) else teams.map { SportsLiveSettingsItem.RemindedTeam(it) }

/** True for an item the D-pad can land on (headers and the My teams note are plain text). */
internal val SportsLiveSettingsItem.isFocusable: Boolean
    get() = this !is SportsLiveSettingsItem.Header && this != SportsLiveSettingsItem.NoTeams

/** My sports' page: one row per sport, in picker order. */
internal fun mySportsItems(): List<SportsLiveSettingsItem> = MySport.entries.map { SportsLiveSettingsItem.Sport(it) }

/** A sport's leagues page: soccer starts with "All competitions"; then each league in picker order. */
internal fun sportLeagueItems(sport: MySport): List<SportsLiveSettingsItem> =
    (if (sport == MySport.SOCCER) listOf(SportsLiveSettingsItem.AllCompetitions) else emptyList()) +
        sport.leagues.map { SportsLiveSettingsItem.League(it) }

/** A sport with one league is switched straight from its row; one with several opens its leagues page. */
internal val MySport.hasLeaguePage: Boolean get() = leagues.size > 1

/**
 * What the fixed help line says for the focused row: an explanation [text], or, when the row is switched
 * off, [offReason] in its place (#89). [switchesOff] lists the rows this one is switching off right now,
 * so a cause names its effects too.
 */
internal data class SportsLiveHelp(
    @StringRes val text: Int,
    val offReason: SportsLiveOffReason? = null,
    val switchesOff: List<SportsLiveSetting> = emptyList()
)

/** The help line for [item] in [state] (null for a header, which never takes focus). */
internal fun sportsLiveHelpFor(
    item: SportsLiveSettingsItem,
    state: SportsLiveSettingsUiState,
    pipSupported: Boolean = true
): SportsLiveHelp? = when (item) {
    is SportsLiveSettingsItem.Header, SportsLiveSettingsItem.NoTeams -> null
    SportsLiveSettingsItem.NoHidden -> SportsLiveHelp(R.string.sports_live_setting_hidden_empty)
    SportsLiveSettingsItem.NoRemindedTeams -> SportsLiveHelp(R.string.sports_live_setting_reminded_teams_empty)
    is SportsLiveSettingsItem.RemindedTeam -> SportsLiveHelp(R.string.sports_live_setting_reminded_team_help)
    is SportsLiveSettingsItem.Setting -> {
        val reason = item.setting.offReason(state, pipSupported)
        SportsLiveHelp(
            text = item.setting.help,
            offReason = reason,
            switchesOff = if (reason == null) sportsLiveSwitchedOffBy(item.setting, state) else emptyList()
        )
    }
    SportsLiveSettingsItem.MoreToggle -> SportsLiveHelp(R.string.sports_live_section_more_subtitle)
    SportsLiveSettingsItem.PipSystem -> SportsLiveHelp(R.string.sports_live_setting_pip_system_subtitle)
    SportsLiveSettingsItem.AddTeam -> SportsLiveHelp(R.string.sports_live_team_search_placeholder)
    is SportsLiveSettingsItem.HiddenTitle -> SportsLiveHelp(R.string.sports_live_setting_hidden_subtitle)
    is SportsLiveSettingsItem.Team -> SportsLiveHelp(R.string.sports_live_my_teams_team_help)
    is SportsLiveSettingsItem.Sport -> SportsLiveHelp(
        when {
            state.mySports.toggleSport(item.sport) == state.mySports -> R.string.sports_live_my_sports_last_sport
            item.sport.hasLeaguePage -> R.string.sports_live_my_sports_sport_help
            else -> R.string.sports_live_my_sports_single_help
        }
    )
    SportsLiveSettingsItem.AllCompetitions -> SportsLiveHelp(
        if (state.mySports.pressAllCompetitions() == state.mySports) R.string.sports_live_my_sports_last_sport
        else R.string.sports_live_my_sports_all_competitions_help
    )
    is SportsLiveSettingsItem.League -> SportsLiveHelp(
        if (state.mySports.pressLeague(item.league) == state.mySports) R.string.sports_live_my_sports_last_sport
        else R.string.sports_live_my_sports_league_help
    )
    is SportsLiveSettingsItem.Catalog -> SportsLiveHelp(R.string.sports_live_setting_catalogs_subtitle)
    is SportsLiveSettingsItem.CatalogFilter -> SportsLiveHelp(R.string.sports_live_catalog_filter_help)
}

/**
 * A league row pressed on its sport's page (#134: no league = the sport off). Turning off a non-soccer
 * sport's only league turns the sport off; otherwise the league toggles as before. The last sport stays
 * on, so that press changes nothing.
 */
internal fun MySports.pressLeague(league: MyLeague): MySports {
    val sport = league.sport
    if (sport != MySport.SOCCER && sport in effectiveSports && leaguesOf(sport) == setOf(league)) return toggleSport(sport)
    return toggleLeague(league)
}

/** Soccer's "All competitions" pressed: on turns soccer off; off (soccer off, or competitions named) turns it on. */
internal fun MySports.pressAllCompetitions(): MySports = when {
    MySport.SOCCER !in effectiveSports -> toggleSport(MySport.SOCCER)
    namedLeagues(MySport.SOCCER).isEmpty() -> toggleSport(MySport.SOCCER)
    else -> allSoccerCompetitions()
}

/** True when [league]'s switch shows On: its sport is on and the league is in effect (soccer: named). */
internal fun MySports.leagueOn(league: MyLeague): Boolean {
    val sport = league.sport
    if (sport !in effectiveSports) return false
    return if (sport == MySport.SOCCER) league in namedLeagues(sport) else league in leaguesOf(sport)
}

/** True when soccer is on with every competition. */
internal val MySports.allSoccerCompetitionsOn: Boolean
    get() = MySport.SOCCER in effectiveSports && namedLeagues(MySport.SOCCER).isEmpty()

/** The sections in order, each with its settings in order. */
internal fun sportsLiveSettingsLayout(): List<Pair<SportsLiveSettingsSection, List<SportsLiveSetting>>> =
    SportsLiveSettingsSection.entries.map { section ->
        section to SportsLiveSetting.entries.filter { it.section == section }
    }

/** "More" starts collapsed each time the screen opens. */
internal const val SPORTS_LIVE_MORE_EXPANDED_BY_DEFAULT = false

/** A summary value: the first [max] names, then "+N" for the rest ("Premier League, MLS +2"). */
internal fun summarise(names: List<String>, max: Int = 2): String? = when {
    names.isEmpty() -> null
    names.size <= max -> names.joinToString(", ")
    else -> names.take(max).joinToString(", ") + " +" + (names.size - max)
}
