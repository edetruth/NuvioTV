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

    val CAF_QUAL = EspnLeague("soccer", "caf.nations_qual")
    val ENG1 = EspnLeague("soccer", "eng.1")

    fun json(file: String): String =
        javaClass.classLoader!!.getResourceAsStream("sportslive/scores/$file")!!.bufferedReader().use { it.readText() }

    fun events(file: String, league: EspnLeague): List<ScoreSnapshot> =
        EspnScoreProvider.toSnapshots(moshi.adapter(EspnScoreboardDto::class.java).fromJson(json(file))!!, league)
}
