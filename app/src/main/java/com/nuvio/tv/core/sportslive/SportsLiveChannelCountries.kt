package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.SportsLiveItem
import java.util.Locale

/** Why a country is one of the viewer's own (Channels phase 2): this TV's region, followed teams' leagues, or no country listed. */
sealed interface SportsLiveCountryReason {
    data object TvRegion : SportsLiveCountryReason
    data class Teams(val names: List<String>) : SportsLiveCountryReason
    data object NoCountry : SportsLiveCountryReason
}

/** One of the viewer's own countries: its [country] (a [SportsLiveChannelRegions] region) and why. */
data class SportsLiveYourCountry(val country: String, val reason: SportsLiveCountryReason)

/**
 * The countries Channels filters by (phase 2, design brief §4.2): a channel's country is its region
 * ([SportsLiveChannelRegions.regionOf]); channels with none sit under [SportsLiveChannelRegions.OTHER] ("No country
 * listed"), ticked by default so an addon that names no countries never vanishes. The viewer's own countries are this
 * TV's region plus the countries of followed teams' leagues. A stored choice of [ALL] lists every country; no stored
 * choice is the viewer's own.
 */
object SportsLiveChannelCountries {

    /** The stored choice that lists every country. */
    const val ALL = "*"

    fun countryOf(item: SportsLiveItem): String = SportsLiveChannelRegions.regionOf(item)

    /** This TV's region as a channel region ("GB" is "UK"); null when the TV names none. */
    fun tvRegion(locale: Locale = Locale.getDefault()): String? =
        locale.country.uppercase(Locale.ROOT).takeIf { it.length == 2 }?.let { if (it == "GB") "UK" else it }

    /**
     * The viewer's own countries, in this order: [tvRegion], each followed team's league's country (with the teams),
     * then "No country listed". Each country once.
     */
    fun yours(tvRegion: String?, followed: List<FollowedTeam>): List<SportsLiveYourCountry> {
        val out = LinkedHashMap<String, SportsLiveYourCountry>()
        tvRegion?.let { out[it] = SportsLiveYourCountry(it, SportsLiveCountryReason.TvRegion) }
        val teamsBy = LinkedHashMap<String, MutableList<String>>()
        followed.forEach { team -> leagueCountry(team.league)?.let { teamsBy.getOrPut(it) { mutableListOf() } += team.name } }
        teamsBy.forEach { (country, names) -> out.putIfAbsent(country, SportsLiveYourCountry(country, SportsLiveCountryReason.Teams(names))) }
        out[SportsLiveChannelRegions.OTHER] = SportsLiveYourCountry(SportsLiveChannelRegions.OTHER, SportsLiveCountryReason.NoCountry)
        return out.values.toList()
    }

    /**
     * The countries listed: [stored] as chosen ([ALL]: null, every country), else the viewer's own. A choice that
     * lists no channel at all falls back to every country, so the tab is never empty while channels exist.
     */
    fun selected(stored: Set<String>?, yours: List<SportsLiveYourCountry>, channels: List<SportsLiveItem>): Set<String>? {
        val chosen = when {
            stored == null -> yours.mapTo(LinkedHashSet()) { it.country }
            ALL in stored -> return null
            else -> stored
        }
        return chosen.takeIf { set -> channels.any { countryOf(it) in set } }
    }

    /** Whether [item] is listed under [selected] (null: every country). */
    fun listed(item: SportsLiveItem, selected: Set<String>?): Boolean = selected == null || countryOf(item) in selected

    /** Channels per country, most first (ties A to Z), "No country listed" included. */
    fun counts(channels: List<SportsLiveItem>): List<Pair<String, Int>> =
        channels.groupingBy(::countryOf).eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key to it.value }

    /** The [limit] countries with most channels that are not among [exclude] (the panel's MOST CHANNELS). */
    fun most(counts: List<Pair<String, Int>>, exclude: Set<String>, limit: Int = MOST_SHOWN): List<Pair<String, Int>> =
        counts.filter { (country, _) -> country !in exclude && country != SportsLiveChannelRegions.OTHER }.take(limit)

    const val MOST_SHOWN = 6

    /**
     * The country of an ESPN league path ("soccer/eng.1" is UK, "football/nfl" US); null for one with no single
     * country (Champions League, the World Cup).
     */
    fun leagueCountry(path: String?): String? {
        val league = path?.substringAfterLast('/')?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: return null
        LEAGUES[league]?.let { return it }
        val prefix = league.substringBefore('.', missingDelimiterValue = "")
        return SOCCER_PREFIXES[prefix]
    }

    private val LEAGUES = mapOf(
        "nfl" to "US", "nba" to "US", "wnba" to "US", "mlb" to "US", "nhl" to "US", "mls" to "US", "college-football" to "US",
        "mens-college-basketball" to "US", "womens-college-basketball" to "US", "nwsl" to "US", "pga" to "US", "nascar-premier" to "US",
        "cfl" to "CA", "afl" to "AU", "nrl" to "AU", "ipl" to "IN"
    )

    private val SOCCER_PREFIXES = mapOf(
        "eng" to "UK", "sco" to "UK", "wal" to "UK", "irl" to "IE", "usa" to "US", "can" to "CA", "mex" to "MX", "esp" to "ES",
        "ger" to "DE", "ita" to "IT", "fra" to "FR", "ned" to "NL", "por" to "PT", "bel" to "BE", "tur" to "TR", "bra" to "BR",
        "arg" to "AR", "aus" to "AU", "jpn" to "JP", "ksa" to "SA", "aut" to "AT", "sui" to "CH", "den" to "DK", "swe" to "SE",
        "nor" to "NO", "gre" to "GR", "col" to "CO", "chi" to "CL"
    )
}
