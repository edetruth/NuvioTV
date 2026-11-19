package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.core.sportslive.scores.ScoresMode

/** The sections of Settings → Sports & Live (#72), in screen order. */
internal enum class SportsLiveSettingsSection {
    WHAT_I_FOLLOW,
    SCORES_AND_SPOILERS,
    PLAYING,
    HOME_AND_ROWS,
    ALERTS,
    STATUS,
    /** Rarely used options, behind a "More" row that starts collapsed. */
    MORE
}

/**
 * Every setting on the Sports & Live screen, in screen order, with its section. The screen draws them
 * from this list, so each one appears exactly once.
 */
internal enum class SportsLiveSetting(val section: SportsLiveSettingsSection) {
    MY_SPORTS(SportsLiveSettingsSection.WHAT_I_FOLLOW),
    MY_TEAMS(SportsLiveSettingsSection.WHAT_I_FOLLOW),
    SCORES(SportsLiveSettingsSection.SCORES_AND_SPOILERS),
    SCORE_DELAY(SportsLiveSettingsSection.SCORES_AND_SPOILERS),
    OPEN_GAME(SportsLiveSettingsSection.PLAYING),
    PICTURE_IN_PICTURE(SportsLiveSettingsSection.PLAYING),
    SHOW_SECTION(SportsLiveSettingsSection.HOME_AND_ROWS),
    HOME_ROW(SportsLiveSettingsSection.HOME_AND_ROWS),
    CATCH_UP_ROW(SportsLiveSettingsSection.HOME_AND_ROWS),
    HOVER_PREVIEW(SportsLiveSettingsSection.HOME_AND_ROWS),
    REMIND_MY_TEAMS(SportsLiveSettingsSection.ALERTS),
    SCORE_ALERTS(SportsLiveSettingsSection.ALERTS),
    FULL_TIME_ALERTS(SportsLiveSettingsSection.ALERTS),
    SCORES_STATUS(SportsLiveSettingsSection.STATUS),
    DATA_SAVER(SportsLiveSettingsSection.MORE),
    RESET_STREAM_MEMORY(SportsLiveSettingsSection.MORE),
    /** Titles hidden from Live Now (#134): OK shows one again. */
    HIDDEN(SportsLiveSettingsSection.MORE),
    CATALOGS(SportsLiveSettingsSection.MORE);

    /** Drawn as its own card (it has a title and a list); the others share their section's card. */
    val ownCard: Boolean get() = this == MY_SPORTS || this == MY_TEAMS || this == HIDDEN || this == CATALOGS

    /**
     * Whether the row can be used (greyed out and skipped by the D-pad otherwise). Unchanged from the
     * screen before #72: almost everything needs the section shown; the rest as noted.
     */
    fun isEnabled(state: SportsLiveSettingsUiState): Boolean {
        val section = state.showSection
        val scores = state.scores.mode
        return when (this) {
            SHOW_SECTION, RESET_STREAM_MEMORY, MY_TEAMS, HIDDEN -> true
            SCORE_DELAY, FULL_TIME_ALERTS -> section && scores != ScoresMode.OFF
            SCORE_ALERTS -> section && scores == ScoresMode.SHOW
            // Scores Off means no score data at all, and the row needs the section's Home row: off with either.
            CATCH_UP_ROW -> section && state.showHomeRow && scores != ScoresMode.OFF
            HOVER_PREVIEW -> section && !state.dataSaver
            // The device's own picture-in-picture support is checked by the screen on top of this.
            MY_SPORTS, SCORES, OPEN_GAME, PICTURE_IN_PICTURE, HOME_ROW, REMIND_MY_TEAMS,
            SCORES_STATUS, DATA_SAVER, CATALOGS -> section
        }
    }
}

/** The sections in order, each with its settings in order. */
internal fun sportsLiveSettingsLayout(): List<Pair<SportsLiveSettingsSection, List<SportsLiveSetting>>> =
    SportsLiveSettingsSection.entries.map { section ->
        section to SportsLiveSetting.entries.filter { it.section == section }
    }

/** "More" starts collapsed each time the screen opens. */
internal const val SPORTS_LIVE_MORE_EXPANDED_BY_DEFAULT = false
