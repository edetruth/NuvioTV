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
    val LIVE_TV: Set<String> = setOf("live tv")
    val EVENTS: Set<String> = setOf("event", "events")
    val LIVE: Set<String> = setOf("live")
    val UPCOMING: Set<String> = setOf("upcoming", "today", "schedule")

    private val regexCache = ConcurrentHashMap<Set<String>, Regex>()

    fun containsKeyword(text: String?, keywords: Set<String>): Boolean =
        findKeyword(text, keywords) != null

    /** The first keyword found in [text] (lowercased), or null. */
    fun findKeyword(text: String?, keywords: Set<String>): String? {
        if (text.isNullOrBlank() || keywords.isEmpty()) return null
        return regexFor(keywords).find(text)?.value?.lowercase()
    }

    // Boundaries are "not a letter or digit" rather than \b, so ids like "sports_recap"
    // still split on the underscore while "Transportation" does not match "sport".
    private fun regexFor(keywords: Set<String>): Regex = regexCache.getOrPut(keywords) {
        val alternatives = keywords.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        Regex("(?<![\\p{L}\\p{N}])(?:$alternatives)(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
    }
}
