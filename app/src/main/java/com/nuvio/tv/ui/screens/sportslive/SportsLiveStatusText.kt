package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.GameStatusStrings

/** The status formatter's words ([com.nuvio.tv.core.sportslive.GameStatusText]) from string resources. */
@Composable
internal fun rememberGameStatusStrings(): GameStatusStrings {
    val liveClock = stringResource(R.string.sports_live_status_live_clock)
    val live = stringResource(R.string.sports_live_live_pill)
    val clockPeriod = stringResource(R.string.sports_live_status_clock_period)
    val timeCountdown = stringResource(R.string.sports_live_status_time_countdown)
    val inHm = stringResource(R.string.sports_live_status_in_hm)
    val inH = stringResource(R.string.sports_live_status_in_h)
    val inM = stringResource(R.string.sports_live_status_in_m)
    val final = stringResource(R.string.sports_live_chip_final_note)
    val fullTime = stringResource(R.string.sports_live_results_note_ft)
    val probablyEnded = stringResource(R.string.sports_live_probably_ended)
    val postponed = stringResource(R.string.sports_live_chip_postponed)
    val chip = rememberScoreChipStrings()
    val listedLive = stringResource(R.string.sports_live_listed_live)
    val checkingScore = stringResource(R.string.sports_live_checking_score)
    return remember(liveClock, live, clockPeriod, timeCountdown, inHm, inH, inM, final, fullTime, probablyEnded, postponed, chip, listedLive, checkingScore) {
        GameStatusStrings(liveClock, live, clockPeriod, timeCountdown, inHm, inH, inM, final, fullTime, probablyEnded, postponed, chip, listedLive, checkingScore)
    }
}
