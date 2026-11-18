package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.scores.MyLeague
import com.nuvio.tv.core.sportslive.scores.MySport

/** A My sports sport as the picker and the Results chips name it. */
@Composable
internal fun mySportLabel(sport: MySport): String = stringResource(
    when (sport) {
        MySport.BASKETBALL -> R.string.sports_live_results_sport_basketball
        MySport.FOOTBALL -> R.string.sports_live_results_sport_football
        MySport.HOCKEY -> R.string.sports_live_results_sport_hockey
        MySport.BASEBALL -> R.string.sports_live_results_sport_baseball
        MySport.SOCCER -> R.string.sports_live_results_sport_soccer
        MySport.TENNIS -> R.string.sports_live_results_sport_tennis
        MySport.MMA -> R.string.sports_live_results_sport_mma
        MySport.MOTORSPORT -> R.string.sports_live_results_sport_motorsport
        MySport.GOLF -> R.string.sports_live_results_sport_golf
    }
)

/** A My sports league as the picker names it. */
@Composable
internal fun myLeagueLabel(league: MyLeague): String = stringResource(
    when (league) {
        MyLeague.NBA -> R.string.sports_live_league_nba
        MyLeague.COLLEGE_BASKETBALL -> R.string.sports_live_league_college_basketball
        MyLeague.NFL -> R.string.sports_live_league_nfl
        MyLeague.COLLEGE_FOOTBALL -> R.string.sports_live_league_college_football
        MyLeague.NHL -> R.string.sports_live_league_nhl
        MyLeague.MLB -> R.string.sports_live_league_mlb
        MyLeague.INTERNATIONALS -> R.string.sports_live_league_internationals
        MyLeague.CHAMPIONS_LEAGUE -> R.string.sports_live_league_champions_league
        MyLeague.EUROPA_LEAGUE -> R.string.sports_live_league_europa_league
        MyLeague.CONFERENCE_LEAGUE -> R.string.sports_live_league_conference_league
        MyLeague.PREMIER_LEAGUE -> R.string.sports_live_league_premier_league
        MyLeague.LA_LIGA -> R.string.sports_live_league_la_liga
        MyLeague.BUNDESLIGA -> R.string.sports_live_league_bundesliga
        MyLeague.SERIE_A -> R.string.sports_live_league_serie_a
        MyLeague.LIGUE_1 -> R.string.sports_live_league_ligue_1
        MyLeague.MLS -> R.string.sports_live_league_mls
        MyLeague.ATP -> R.string.sports_live_league_atp
        MyLeague.WTA -> R.string.sports_live_league_wta
        MyLeague.UFC -> R.string.sports_live_league_ufc
        MyLeague.F1 -> R.string.sports_live_league_f1
        MyLeague.PGA -> R.string.sports_live_league_pga
        MyLeague.LPGA -> R.string.sports_live_league_lpga
    }
)
