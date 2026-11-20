package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.ui.screens.settings.SportsLiveSetting.CLOSE_GAME_ALERTS
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.scores.MyLeague
import com.nuvio.tv.core.sportslive.scores.MySport
import com.nuvio.tv.core.sportslive.scores.MySports
import com.nuvio.tv.core.sportslive.scores.ScoreSettings
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.ui.screens.settings.SportsLiveOffReason.DATA_SAVER_ON
import com.nuvio.tv.ui.screens.settings.SportsLiveOffReason.HOME_ROW_OFF
import com.nuvio.tv.ui.screens.settings.SportsLiveOffReason.PIP_UNSUPPORTED
import com.nuvio.tv.ui.screens.settings.SportsLiveOffReason.SCORES_NOT_SHOW
import com.nuvio.tv.ui.screens.settings.SportsLiveOffReason.SCORES_OFF
import com.nuvio.tv.ui.screens.settings.SportsLiveOffReason.SECTION_HIDDEN
import com.nuvio.tv.ui.screens.settings.SportsLiveSetting.CATALOGS
import com.nuvio.tv.ui.screens.settings.SportsLiveSetting.CATCH_UP_ROW
import com.nuvio.tv.ui.screens.settings.SportsLiveSetting.DATA_SAVER
import com.nuvio.tv.ui.screens.settings.SportsLiveSetting.FULL_TIME_ALERTS
import com.nuvio.tv.ui.screens.settings.SportsLiveSetting.GAME_END
import com.nuvio.tv.ui.screens.settings.SportsLiveSetting.REMINDED_TEAMS
import com.nuvio.tv.ui.screens.settings.SportsLiveSetting.HIDDEN
import com.nuvio.tv.ui.screens.settings.SportsLiveSetting.HOME_ROW
import com.nuvio.tv.ui.screens.settings.SportsLiveSetting.HOVER_PREVIEW
import com.nuvio.tv.ui.screens.settings.SportsLiveSetting.MY_SPORTS
import com.nuvio.tv.ui.screens.settings.SportsLiveSetting.MY_TEAMS
import com.nuvio.tv.ui.screens.settings.SportsLiveSetting.OPEN_GAME
import com.nuvio.tv.ui.screens.settings.SportsLiveSetting.HIDE_UNPLAYABLE
import com.nuvio.tv.ui.screens.settings.SportsLiveSetting.PICTURE_IN_PICTURE
import com.nuvio.tv.ui.screens.settings.SportsLiveSetting.REMIND_MY_TEAMS
import com.nuvio.tv.ui.screens.settings.SportsLiveSetting.RESET_STREAM_MEMORY
import com.nuvio.tv.ui.screens.settings.SportsLiveSetting.RUN_SETUP
import com.nuvio.tv.ui.screens.settings.SportsLiveSetting.SCORES
import com.nuvio.tv.ui.screens.settings.SportsLiveSetting.SCORES_STATUS
import com.nuvio.tv.ui.screens.settings.SportsLiveSetting.SCORE_ALERTS
import com.nuvio.tv.ui.screens.settings.SportsLiveSetting.SCORE_DELAY
import com.nuvio.tv.ui.screens.settings.SportsLiveSetting.SHOW_SECTION
import com.nuvio.tv.ui.screens.settings.SportsLiveSettingsSection.ALERTS
import com.nuvio.tv.ui.screens.settings.SportsLiveSettingsSection.HOME_AND_ROWS
import com.nuvio.tv.ui.screens.settings.SportsLiveSettingsSection.MORE
import com.nuvio.tv.ui.screens.settings.SportsLiveSettingsSection.PLAYING
import com.nuvio.tv.ui.screens.settings.SportsLiveSettingsSection.SCORES_AND_SPOILERS
import com.nuvio.tv.ui.screens.settings.SportsLiveSettingsSection.SETUP
import com.nuvio.tv.ui.screens.settings.SportsLiveSettingsSection.STATUS
import com.nuvio.tv.ui.screens.settings.SportsLiveSettingsSection.WHAT_I_FOLLOW
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.nuvio.tv.ui.screens.settings.SportsLiveSettingsItem as Item

/** Settings → Sports & Live V2 (#134 phase 6): row order, the help line, and why a row is off (#89). */
class SportsLiveSettingsHelpTest {

    private fun header(section: SportsLiveSettingsSection) = Item.Header(section)
    private fun row(setting: SportsLiveSetting) = Item.Setting(setting)

    private val scoresOff = SportsLiveSettingsUiState(scores = ScoreSettings(ScoresMode.OFF, 0))
    private val hideUntilFocused = SportsLiveSettingsUiState(scores = ScoreSettings(ScoresMode.HIDE_UNTIL_FOCUSED, 0))

    // Row order

    @Test
    fun `main page rows run by use with more collapsed and no setup yet`() {
        assertEquals(
            listOf(
                header(SCORES_AND_SPOILERS), row(SCORES), row(SCORE_DELAY),
                header(WHAT_I_FOLLOW), row(MY_TEAMS), row(MY_SPORTS),
                header(ALERTS), row(REMIND_MY_TEAMS), row(SCORE_ALERTS), row(FULL_TIME_ALERTS), row(CLOSE_GAME_ALERTS), row(REMINDED_TEAMS),
                header(PLAYING), row(OPEN_GAME), row(GAME_END), row(HIDE_UNPLAYABLE), row(PICTURE_IN_PICTURE),
                header(HOME_AND_ROWS), row(SHOW_SECTION), row(HOME_ROW), row(CATCH_UP_ROW), row(HOVER_PREVIEW),
                header(STATUS), row(SCORES_STATUS),
                header(MORE), Item.MoreToggle
            ),
            sportsLiveSettingsItems(moreExpanded = false, setupAvailable = false, pipSystemAvailable = false, catalogCount = 0)
        )
    }

    @Test
    fun `setup, the system pip row, more and catalogs slot into their places`() {
        val items = sportsLiveSettingsItems(moreExpanded = true, setupAvailable = true, pipSystemAvailable = true, catalogCount = 3)
        assertEquals(
            listOf(row(OPEN_GAME), row(GAME_END), row(HIDE_UNPLAYABLE), row(PICTURE_IN_PICTURE), Item.PipSystem),
            items.subList(items.indexOf(header(PLAYING)) + 1, items.indexOf(header(HOME_AND_ROWS)))
        )
        assertEquals(listOf(header(SETUP), row(RUN_SETUP), header(STATUS)), items.subList(items.indexOf(header(SETUP)), items.indexOf(header(STATUS)) + 1))
        assertEquals(
            listOf(header(MORE), Item.MoreToggle, row(DATA_SAVER), row(RESET_STREAM_MEMORY), row(HIDDEN), row(CATALOGS)),
            items.takeLast(6)
        )
    }

    @Test
    fun `catalogs only show when there are catalogs`() {
        val items = sportsLiveSettingsItems(moreExpanded = true, setupAvailable = false, pipSystemAvailable = false, catalogCount = 0)
        assertFalse(row(CATALOGS) in items)
        assertEquals(Item.MoreToggle, items[items.indexOf(header(MORE)) + 1])
    }

    @Test
    fun `scores is the first row and every key is unique`() {
        val items = sportsLiveSettingsItems(moreExpanded = true, setupAvailable = true, pipSystemAvailable = true, catalogCount = 1)
        assertEquals(row(SCORES), items.first { it !is Item.Header })
        assertEquals(items.size, items.map { it.key }.toSet().size)
    }

    @Test
    fun `my sports is one row per sport and soccer leagues start with all competitions`() {
        assertEquals(MySport.entries.map { Item.Sport(it) }, mySportsItems())
        val soccer = sportLeagueItems(MySport.SOCCER)
        assertEquals(Item.AllCompetitions, soccer.first())
        assertEquals(MySport.SOCCER.leagues.map { Item.League(it) }, soccer.drop(1))
        assertEquals(MySport.BASKETBALL.leagues.map { Item.League(it) }, sportLeagueItems(MySport.BASKETBALL))
        assertTrue(Item.League(MyLeague.WNBA) in sportLeagueItems(MySport.BASKETBALL))
        assertTrue(Item.League(MyLeague.NWSL) in soccer)
    }

    @Test
    fun `my teams starts with add a team, then the teams or a note`() {
        val celtics = FollowedTeam("Boston Celtics", "Basketball")
        val arsenal = FollowedTeam("Arsenal", "Soccer")
        assertEquals(listOf(Item.AddTeam, Item.NoTeams), myTeamsItems(emptyList()))
        assertEquals(listOf(Item.AddTeam, Item.Team(celtics), Item.Team(arsenal)), myTeamsItems(listOf(celtics, arsenal)))
        assertEquals(Item.AddTeam, myTeamsItems(listOf(celtics)).first { it.isFocusable })
    }

    @Test
    fun `hidden lists the titles or one row that keeps focus on the page`() {
        assertEquals(listOf(Item.NoHidden), hiddenItems(emptyList()))
        assertTrue(Item.NoHidden.isFocusable)
        assertFalse(Item.NoTeams.isFocusable)
        assertEquals(listOf(Item.HiddenTitle("A"), Item.HiddenTitle("B")), hiddenItems(listOf("A", "B")))
    }

    @Test
    fun `every page survives its saved token`() {
        val pages = listOf(
            SportsLiveSettingsPage.Main, SportsLiveSettingsPage.MyTeams, SportsLiveSettingsPage.MySports,
            SportsLiveSettingsPage.Catalogs, SportsLiveSettingsPage.Hidden
        ) + MySport.entries.map { SportsLiveSettingsPage.Leagues(it) }
        for (page in pages) assertEquals(page, sportsLiveSettingsPageOf(page.token))
        assertEquals(SportsLiveSettingsPage.Main, sportsLiveSettingsPageOf("leagues:curling"))
        assertEquals(SportsLiveSettingsPage.Main, sportsLiveSettingsPageOf("something else"))
    }

    @Test
    fun `only sports with several leagues open a leagues page`() {
        assertTrue(MySport.BASKETBALL.hasLeaguePage)
        assertTrue(MySport.SOCCER.hasLeaguePage)
        assertFalse(MySport.HOCKEY.hasLeaguePage)
        assertFalse(MySport.MOTORSPORT.hasLeaguePage)
    }

    // Why a row is off

    @Test
    fun `a row has an off reason exactly when it is greyed out`() {
        val states = listOf(
            SportsLiveSettingsUiState(),
            SportsLiveSettingsUiState(showSection = false),
            scoresOff,
            hideUntilFocused,
            SportsLiveSettingsUiState(showHomeRow = false),
            SportsLiveSettingsUiState(dataSaver = true),
            SportsLiveSettingsUiState(showHomeRow = false, dataSaver = true, scores = ScoreSettings(ScoresMode.OFF, 0))
        )
        for (state in states) for (setting in SportsLiveSetting.entries) {
            assertEquals("$setting in $state", !setting.isEnabled(state), setting.offReason(state) != null)
        }
    }

    @Test
    fun `scores off names scores as the reason`() {
        assertEquals(SCORES_OFF, SCORE_DELAY.offReason(scoresOff))
        assertEquals(SCORES_OFF, FULL_TIME_ALERTS.offReason(scoresOff))
        assertEquals(SCORES_OFF, CATCH_UP_ROW.offReason(scoresOff))
        assertEquals(SCORES_NOT_SHOW, SCORE_ALERTS.offReason(scoresOff))
        assertEquals(SCORES, SCORES_OFF.cause)
    }

    @Test
    fun `hide until focused switches off only score alerts`() {
        assertEquals(SCORES_NOT_SHOW, SCORE_ALERTS.offReason(hideUntilFocused))
        assertNull(SCORE_DELAY.offReason(hideUntilFocused))
        assertNull(CATCH_UP_ROW.offReason(hideUntilFocused))
    }

    @Test
    fun `the catch-up row names the home row before scores`() {
        val both = SportsLiveSettingsUiState(showHomeRow = false, scores = ScoreSettings(ScoresMode.OFF, 0))
        assertEquals(HOME_ROW_OFF, CATCH_UP_ROW.offReason(both))
        assertEquals(HOME_ROW, HOME_ROW_OFF.cause)
    }

    @Test
    fun `data saver names itself for hover preview`() {
        assertEquals(DATA_SAVER_ON, HOVER_PREVIEW.offReason(SportsLiveSettingsUiState(dataSaver = true)))
        assertEquals(DATA_SAVER, DATA_SAVER_ON.cause)
    }

    @Test
    fun `a hidden section is the first reason for every row it greys out`() {
        val hidden = SportsLiveSettingsUiState(showSection = false, showHomeRow = false, dataSaver = true)
        for (setting in SportsLiveSetting.entries - setOf(SHOW_SECTION, RESET_STREAM_MEMORY, MY_TEAMS, RUN_SETUP, HIDDEN)) {
            assertEquals("$setting", SECTION_HIDDEN, setting.offReason(hidden))
        }
    }

    @Test
    fun `an unsupported tv is the reason for picture-in-picture before anything else`() {
        assertEquals(PIP_UNSUPPORTED, PICTURE_IN_PICTURE.offReason(SportsLiveSettingsUiState(), pipSupported = false))
        assertEquals(PIP_UNSUPPORTED, PICTURE_IN_PICTURE.offReason(SportsLiveSettingsUiState(showSection = false), pipSupported = false))
        assertNull(PIP_UNSUPPORTED.cause)
        assertNull(PICTURE_IN_PICTURE.offReason(SportsLiveSettingsUiState(), pipSupported = true))
    }

    @Test
    fun `a cause lists the rows it switches off right now in screen order`() {
        assertEquals(listOf(SCORE_DELAY, SCORE_ALERTS, FULL_TIME_ALERTS, CLOSE_GAME_ALERTS, CATCH_UP_ROW), sportsLiveSwitchedOffBy(SCORES, scoresOff))
        assertEquals(listOf(SCORE_ALERTS), sportsLiveSwitchedOffBy(SCORES, hideUntilFocused))
        assertEquals(emptyList<SportsLiveSetting>(), sportsLiveSwitchedOffBy(SCORES, SportsLiveSettingsUiState()))
        assertEquals(listOf(CATCH_UP_ROW), sportsLiveSwitchedOffBy(HOME_ROW, SportsLiveSettingsUiState(showHomeRow = false)))
        assertEquals(listOf(HOVER_PREVIEW), sportsLiveSwitchedOffBy(DATA_SAVER, SportsLiveSettingsUiState(dataSaver = true)))
        assertEquals(
            SportsLiveSetting.entries - setOf(SHOW_SECTION, RESET_STREAM_MEMORY, MY_TEAMS, RUN_SETUP, HIDDEN),
            sportsLiveSwitchedOffBy(SHOW_SECTION, SportsLiveSettingsUiState(showSection = false))
        )
    }

    // The help line

    @Test
    fun `a usable setting explains itself`() {
        for (setting in SportsLiveSetting.entries) {
            assertEquals(SportsLiveHelp(setting.help), sportsLiveHelpFor(row(setting), SportsLiveSettingsUiState()))
        }
        assertEquals(R.string.sports_live_setting_scores_help, SCORES.help)
    }

    @Test
    fun `a row that is off shows its reason in place of its explanation`() {
        assertEquals(SportsLiveHelp(SCORE_DELAY.help, offReason = SCORES_OFF), sportsLiveHelpFor(row(SCORE_DELAY), scoresOff))
        assertEquals(
            SportsLiveHelp(PICTURE_IN_PICTURE.help, offReason = PIP_UNSUPPORTED),
            sportsLiveHelpFor(row(PICTURE_IN_PICTURE), SportsLiveSettingsUiState(), pipSupported = false)
        )
    }

    @Test
    fun `a setting that switches others off names them`() {
        assertEquals(
            SportsLiveHelp(SCORES.help, switchesOff = listOf(SCORE_DELAY, SCORE_ALERTS, FULL_TIME_ALERTS, CLOSE_GAME_ALERTS, CATCH_UP_ROW)),
            sportsLiveHelpFor(row(SCORES), scoresOff)
        )
        assertEquals(
            SportsLiveHelp(DATA_SAVER.help, switchesOff = listOf(HOVER_PREVIEW)),
            sportsLiveHelpFor(row(DATA_SAVER), SportsLiveSettingsUiState(dataSaver = true))
        )
    }

    @Test
    fun `headers have no help and the other rows have their own line`() {
        val state = SportsLiveSettingsUiState()
        assertNull(sportsLiveHelpFor(header(SCORES_AND_SPOILERS), state))
        assertEquals(R.string.sports_live_section_more_subtitle, sportsLiveHelpFor(Item.MoreToggle, state)?.text)
        assertEquals(R.string.sports_live_setting_pip_system_subtitle, sportsLiveHelpFor(Item.PipSystem, state)?.text)
        assertNull(sportsLiveHelpFor(Item.NoTeams, state))
        assertEquals(R.string.sports_live_setting_hidden_empty, sportsLiveHelpFor(Item.NoHidden, state)?.text)
        assertEquals(R.string.sports_live_team_search_placeholder, sportsLiveHelpFor(Item.AddTeam, state)?.text)
        assertEquals(R.string.sports_live_setting_hidden_subtitle, sportsLiveHelpFor(Item.HiddenTitle("Talk show"), state)?.text)
        assertEquals(R.string.sports_live_setting_catalogs_subtitle, sportsLiveHelpFor(Item.Catalog("k"), state)?.text)
        assertEquals(R.string.sports_live_catalog_filter_help, sportsLiveHelpFor(Item.CatalogFilter("k"), state)?.text)
    }

    @Test
    fun `a sport row says whether ok opens its leagues, switches it, or cannot turn the last sport off`() {
        val state = SportsLiveSettingsUiState()
        assertEquals(R.string.sports_live_my_sports_sport_help, sportsLiveHelpFor(Item.Sport(MySport.BASKETBALL), state)?.text)
        assertEquals(R.string.sports_live_my_sports_single_help, sportsLiveHelpFor(Item.Sport(MySport.HOCKEY), state)?.text)
        val hockeyOnly = SportsLiveSettingsUiState(mySports = MySports(mapOf(MySport.HOCKEY to emptySet())))
        assertEquals(R.string.sports_live_my_sports_last_sport, sportsLiveHelpFor(Item.Sport(MySport.HOCKEY), hockeyOnly)?.text)
        // Turning another sport on is always possible.
        assertEquals(R.string.sports_live_my_sports_single_help, sportsLiveHelpFor(Item.Sport(MySport.MMA), hockeyOnly)?.text)
    }

    @Test
    fun `a league row warns when it is the last sport's only league`() {
        val nbaOnly = SportsLiveSettingsUiState(mySports = MySports(mapOf(MySport.BASKETBALL to setOf(MyLeague.NBA))))
        assertEquals(R.string.sports_live_my_sports_last_sport, sportsLiveHelpFor(Item.League(MyLeague.NBA), nbaOnly)?.text)
        assertEquals(R.string.sports_live_my_sports_league_help, sportsLiveHelpFor(Item.League(MyLeague.COLLEGE_BASKETBALL), nbaOnly)?.text)
        val soccerOnly = SportsLiveSettingsUiState(mySports = MySports(mapOf(MySport.SOCCER to emptySet())))
        assertEquals(R.string.sports_live_my_sports_last_sport, sportsLiveHelpFor(Item.AllCompetitions, soccerOnly)?.text)
        assertEquals(R.string.sports_live_my_sports_all_competitions_help, sportsLiveHelpFor(Item.AllCompetitions, SportsLiveSettingsUiState())?.text)
    }

    // My sports presses (no league = off)

    private val basketballAndFootball = MySports(mapOf(MySport.BASKETBALL to emptySet(), MySport.FOOTBALL to emptySet()))

    @Test
    fun `turning off a sport's only league turns the sport off`() {
        val after = basketballAndFootball.pressLeague(MyLeague.NBA)
        assertEquals(listOf(MySport.FOOTBALL), after.effectiveSports)
        assertFalse(after.leagueOn(MyLeague.NBA))
    }

    @Test
    fun `a league toggles within its sport and turns an off sport on`() {
        val withCollege = basketballAndFootball.pressLeague(MyLeague.COLLEGE_BASKETBALL)
        assertEquals(setOf(MyLeague.NBA, MyLeague.COLLEGE_BASKETBALL), withCollege.leaguesOf(MySport.BASKETBALL))
        assertEquals(setOf(MyLeague.COLLEGE_BASKETBALL), withCollege.pressLeague(MyLeague.NBA).leaguesOf(MySport.BASKETBALL))
        val hockey = basketballAndFootball.pressLeague(MyLeague.NHL)
        assertTrue(hockey.leagueOn(MyLeague.NHL))
        assertTrue(MySport.HOCKEY in hockey.effectiveSports)
    }

    @Test
    fun `the last sport's only league stays on`() {
        val nbaOnly = MySports(mapOf(MySport.BASKETBALL to setOf(MyLeague.NBA)))
        assertEquals(nbaOnly, nbaOnly.pressLeague(MyLeague.NBA))
    }

    @Test
    fun `all competitions turns soccer off, back on, and clears named competitions`() {
        val all = MySports(mapOf(MySport.BASKETBALL to emptySet(), MySport.SOCCER to emptySet()))
        assertTrue(all.allSoccerCompetitionsOn)
        val off = all.pressAllCompetitions()
        assertFalse(MySport.SOCCER in off.effectiveSports)
        assertFalse(off.allSoccerCompetitionsOn)
        assertTrue(off.pressAllCompetitions().allSoccerCompetitionsOn)
        val epl = all.pressLeague(MyLeague.PREMIER_LEAGUE)
        assertTrue(epl.leagueOn(MyLeague.PREMIER_LEAGUE))
        assertFalse(epl.allSoccerCompetitionsOn)
        assertTrue(epl.pressAllCompetitions().allSoccerCompetitionsOn)
    }

    @Test
    fun `a soccer competition shows on only when named`() {
        assertFalse(MySports.DEFAULT.leagueOn(MyLeague.PREMIER_LEAGUE))
        assertTrue(MySports.DEFAULT.leagueOn(MyLeague.NBA))
        assertFalse(MySports.DEFAULT.leagueOn(MyLeague.COLLEGE_BASKETBALL))
    }

    @Test
    fun `a summary names two then counts the rest`() {
        assertNull(summarise(emptyList()))
        assertEquals("NBA, College", summarise(listOf("NBA", "College")))
        assertEquals("Premier League, MLS +2", summarise(listOf("Premier League", "MLS", "La Liga", "Serie A")))
        assertEquals("A, B, C +1", summarise(listOf("A", "B", "C", "D"), max = 3))
    }
}
