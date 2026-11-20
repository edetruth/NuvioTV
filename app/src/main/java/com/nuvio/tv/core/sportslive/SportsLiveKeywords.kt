package com.nuvio.tv.core.sportslive

import java.util.concurrent.ConcurrentHashMap

object SportsLiveKeywords {
    val SPORTS: Set<String> = setOf(
        "sport", "sports", "nfl", "nba", "mlb", "nhl", "ufc", "mma", "f1", "formula 1",
        "nascar", "motogp", "soccer", "football", "basketball", "baseball", "hockey",
        "tennis", "boxing", "cricket", "rugby", "golf", "wrestling",
        "replay", "replays", "highlights", "recap"
    )
    val REPLAY: Set<String> = setOf("replay", "replays", "highlights", "recap")
    val CHANNELS: Set<String> = setOf("channel", "channels")
    /** A catalog or genre listing TV networks ("Networks": ABC, A&E, AMC...): channels, never games (#145). */
    val NETWORKS: Set<String> = setOf("network", "networks", "tv network", "tv networks")
    val LIVE_TV: Set<String> = setOf("live tv")
    val EVENTS: Set<String> = setOf("event", "events")
    val LIVE: Set<String> = setOf("live")
    val UPCOMING: Set<String> = setOf("upcoming", "today", "schedule")

    // Catalog-name fallback for a catalog whose items carry no signals of their own.
    val FALLBACK_LIVE: Set<String> = setOf("live", "live now", "en vivo", "ao vivo", "direct", "direto")
    val FALLBACK_SCHEDULE: Set<String> =
        setOf("schedule", "upcoming", "today", "next", "coming up", "programación", "agenda")
    val FALLBACK_REPLAY: Set<String> = REPLAY + setOf("full match", "highlights", "resumen", "melhores momentos")

    /** Catalog names too generic to name a channel group ("Channels", "Live TV"), compared lowercased. */
    val GENERIC_CHANNEL_CATALOGS: Set<String> =
        setOf("channels", "channel list", "live tv", "tv", "live channels", "all channels")

    private val regexCache = ConcurrentHashMap<Set<String>, Finder>()

    // A keyword set's regex, and its answers for the texts it was asked about: every load asks again for the same
    // catalog names, genres and ids, item by item (#152: each regex call on Android allocates native memory).
    private class Finder(val regex: Regex) {
        val answers = Memo<String, String?>(FINDER_CACHE_SIZE) { text -> regex.find(text)?.value?.lowercase() }
    }

    private const val FINDER_CACHE_SIZE = 2_048

    fun containsKeyword(text: String?, keywords: Set<String>): Boolean =
        findKeyword(text, keywords) != null

    /** The first keyword found in [text] (lowercased), or null. */
    fun findKeyword(text: String?, keywords: Set<String>): String? {
        if (text.isNullOrBlank() || keywords.isEmpty()) return null
        return finderFor(keywords).answers(text)
    }

    // Boundaries are "not a letter or digit" rather than \b, so ids like "sports_recap"
    // still split on the underscore while "Transportation" does not match "sport".
    private fun finderFor(keywords: Set<String>): Finder = regexCache.getOrPut(keywords) {
        val alternatives = keywords.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        Finder(Regex("(?<![\\p{L}\\p{N}])(?:$alternatives)(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE))
    }
}
