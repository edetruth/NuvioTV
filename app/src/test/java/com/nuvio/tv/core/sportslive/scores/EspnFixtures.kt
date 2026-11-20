package com.nuvio.tv.core.sportslive.scores

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/** Trimmed real ESPN scoreboards under test resources `sportslive/scores/`. */
internal object EspnFixtures {
    val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    const val NBA_20261115 = "espn_nba_20261115.json"
    const val NFL_20261115 = "espn_nfl_20261115.json"
    const val ENG1_20261108 = "espn_eng1_20261108.json"
    const val CAF_QUAL_20261116 = "espn_caf_nations_qual_20261116.json"
    const val SOCCER_ALL_20261116 = "espn_soccer_all_20261116.json"
    /** Every event of soccer/all on 2026-11-16 (competition notes and uids kept, goal details dropped). */
    const val SOCCER_ALL_FULL_20261116 = "espn_soccer_all_full_20261116.json"
    /** soccer/all on 2026-11-08: Premier League, WSL, Liga F (one event without a league link), NWSL, NCAAM/NCAAW soccer. */
    const val SOCCER_ALL_WOMEN_20261108 = "espn_soccer_all_women_20261108.json"
    /** Division I men's college basketball, 2026-11-15 (44 games). */
    const val CBB_20261115 = "espn_cbb_20261115.json"
    /** soccer/all, 2026-11-17 (ET): the games live at capture, USA v Haiti among them (#66). */
    const val SOCCER_ALL_LIVE_20261117 = "espn_soccer_all_live_20261117.json"
    /** Live Division I men's college basketball, 2026-11-17: a real `situation` (last play only). */
    const val CBB_LIVE_20261117 = "espn_cbb_live_20261117.json"
    /** Hand-trimmed SAMPLE (labelled in the file): live NFL `situation`s, no NFL game was live to capture. */
    const val NFL_LIVE_SITUATION_SAMPLE = "espn_nfl_live_situation_sample.json"
    /** Hand-trimmed SAMPLE (labelled in the file): live MLB `situation`s, off-season when captured. */
    const val MLB_LIVE_SITUATION_SAMPLE = "espn_mlb_live_situation_sample.json"
    /** Hand-trimmed SAMPLE (labelled in the file): a playoff round of TBD slots, scheduled, without competitors (#70). */
    const val NFL_TBD_PLAYOFFS_SAMPLE = "espn_nfl_tbd_playoffs_sample.json"

    /** ger.1, esp.2 and soccer/all (four games, goal details dropped) on 2026-11-20, captured ~20:45Z: Bayern 1-1 Cologne and FC Andorra 1-3 Leganés final. */
    const val GER1_20261120 = "espn_ger1_20261120.json"
    const val ESP2_20261120 = "espn_esp2_20261120.json"
    const val SOCCER_ALL_20261120 = "espn_soccer_all_20261120.json"

    val CAF_QUAL = EspnLeague("soccer", "caf.nations_qual")
    val ENG1 = EspnLeague("soccer", "eng.1")

    fun json(file: String): String =
        javaClass.classLoader!!.getResourceAsStream("sportslive/scores/$file")!!.bufferedReader().use { it.readText() }

    fun events(file: String, league: EspnLeague): List<ScoreSnapshot> =
        EspnScoreProvider.toSnapshots(moshi.adapter(EspnScoreboardDto::class.java).fromJson(json(file))!!, league)
}
