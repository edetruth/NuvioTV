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

    val CAF_QUAL = EspnLeague("soccer", "caf.nations_qual")
    val ENG1 = EspnLeague("soccer", "eng.1")

    fun json(file: String): String =
        javaClass.classLoader!!.getResourceAsStream("sportslive/scores/$file")!!.bufferedReader().use { it.readText() }

    fun events(file: String, league: EspnLeague): List<ScoreSnapshot> =
        EspnScoreProvider.toSnapshots(moshi.adapter(EspnScoreboardDto::class.java).fromJson(json(file))!!, league)
}
