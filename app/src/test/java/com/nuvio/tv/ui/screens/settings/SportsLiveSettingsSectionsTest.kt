package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.core.sportslive.scores.ScoreSettings
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.ui.screens.settings.SportsLiveSetting.CATALOGS
import com.nuvio.tv.ui.screens.settings.SportsLiveSetting.CATCH_UP_ROW
import com.nuvio.tv.ui.screens.settings.SportsLiveSetting.DATA_SAVER
import com.nuvio.tv.ui.screens.settings.SportsLiveSetting.FULL_TIME_ALERTS
import com.nuvio.tv.ui.screens.settings.SportsLiveSetting.HOME_ROW
import com.nuvio.tv.ui.screens.settings.SportsLiveSetting.HOVER_PREVIEW
import com.nuvio.tv.ui.screens.settings.SportsLiveSetting.MY_SPORTS
import com.nuvio.tv.ui.screens.settings.SportsLiveSetting.MY_TEAMS
import com.nuvio.tv.ui.screens.settings.SportsLiveSetting.OPEN_GAME
import com.nuvio.tv.ui.screens.settings.SportsLiveSetting.PICTURE_IN_PICTURE
import com.nuvio.tv.ui.screens.settings.SportsLiveSetting.REMIND_MY_TEAMS
import com.nuvio.tv.ui.screens.settings.SportsLiveSetting.RESET_STREAM_MEMORY
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
import com.nuvio.tv.ui.screens.settings.SportsLiveSettingsSection.STATUS
import com.nuvio.tv.ui.screens.settings.SportsLiveSettingsSection.WHAT_I_FOLLOW
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SportsLiveSettingsSectionsTest {

    private fun enabledIn(state: SportsLiveSettingsUiState) =
        SportsLiveSetting.entries.filter { it.isEnabled(state) }.toSet()

    @Test
    fun `sections come in the approved order`() {
        assertEquals(
            listOf(WHAT_I_FOLLOW, SCORES_AND_SPOILERS, PLAYING, HOME_AND_ROWS, ALERTS, STATUS, MORE),
            sportsLiveSettingsLayout().map { it.first }
        )
    }

    @Test
    fun `each section holds its settings in order`() {
        assertEquals(
            listOf(
                WHAT_I_FOLLOW to listOf(MY_SPORTS, MY_TEAMS),
                SCORES_AND_SPOILERS to listOf(SCORES, SCORE_DELAY),
                PLAYING to listOf(OPEN_GAME, PICTURE_IN_PICTURE),
                HOME_AND_ROWS to listOf(SHOW_SECTION, HOME_ROW, CATCH_UP_ROW, HOVER_PREVIEW),
                ALERTS to listOf(REMIND_MY_TEAMS, SCORE_ALERTS, FULL_TIME_ALERTS),
                STATUS to listOf(SCORES_STATUS),
                MORE to listOf(DATA_SAVER, RESET_STREAM_MEMORY, CATALOGS)
            ),
            sportsLiveSettingsLayout()
        )
    }

    @Test
    fun `every setting from before the regroup appears exactly once`() {
        val shown = sportsLiveSettingsLayout().flatMap { it.second }
        assertEquals(shown.size, shown.toSet().size)
        assertEquals(
            setOf(
                SHOW_SECTION, HOME_ROW, HOVER_PREVIEW, DATA_SAVER, OPEN_GAME, RESET_STREAM_MEMORY,
                PICTURE_IN_PICTURE, SCORES, SCORE_DELAY, SCORES_STATUS, REMIND_MY_TEAMS, SCORE_ALERTS,
                FULL_TIME_ALERTS, MY_SPORTS, MY_TEAMS, CATCH_UP_ROW, CATALOGS
            ),
            shown.toSet()
        )
        assertEquals(SportsLiveSetting.entries.toSet(), shown.toSet())
    }

    @Test
    fun `more is collapsed by default`() {
        assertFalse(SPORTS_LIVE_MORE_EXPANDED_BY_DEFAULT)
    }

    @Test
    fun `with the defaults every setting is usable`() {
        assertEquals(SportsLiveSetting.entries.toSet(), enabledIn(SportsLiveSettingsUiState()))
    }

    @Test
    fun `section off greys out everything but the show switch, stream memory and my teams`() {
        assertEquals(
            setOf(SHOW_SECTION, RESET_STREAM_MEMORY, MY_TEAMS),
            enabledIn(SportsLiveSettingsUiState(showSection = false))
        )
    }

    @Test
    fun `scores off greys out the delay, both score-based alerts and the catch-up row`() {
        val off = enabledIn(SportsLiveSettingsUiState(scores = ScoreSettings(ScoresMode.OFF, 0)))
        assertEquals(SportsLiveSetting.entries.toSet() - setOf(SCORE_DELAY, SCORE_ALERTS, FULL_TIME_ALERTS, CATCH_UP_ROW), off)
    }

    @Test
    fun `hide until focused greys out only score alerts`() {
        val hidden = enabledIn(SportsLiveSettingsUiState(scores = ScoreSettings(ScoresMode.HIDE_UNTIL_FOCUSED, 0)))
        assertEquals(SportsLiveSetting.entries.toSet() - SCORE_ALERTS, hidden)
    }

    @Test
    fun `home row off greys out the catch-up row`() {
        assertEquals(
            SportsLiveSetting.entries.toSet() - CATCH_UP_ROW,
            enabledIn(SportsLiveSettingsUiState(showHomeRow = false))
        )
    }

    @Test
    fun `data saver greys out hover preview`() {
        assertEquals(
            SportsLiveSetting.entries.toSet() - HOVER_PREVIEW,
            enabledIn(SportsLiveSettingsUiState(dataSaver = true))
        )
    }
}
